/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_agent.h"
#include "buffer_https.h"
#include "buffer_source_events.h"
#include "buffer_private_storage.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <syslog.h>
#ifdef __NuttX__
#include <nuttx/fs/fs.h>
extern const struct mountpt_operations g_littlefs_operations;
#endif
#include <unistd.h>

#define AGENT_FILE CONFIG_BUFFER_VELA_DATA_DIR "/agent.json"
static pthread_mutex_t config_lock = PTHREAD_MUTEX_INITIALIZER;
/* Published status is independent of NAND/config serialization. UI readers
 * must never wait for config_load/config_save or credential processing. */
static pthread_mutex_t cached_status_lock = PTHREAD_MUTEX_INITIALIZER;
static struct buffer_agent_status_s cached_status;

static void status_update(const struct buffer_agent_config_s *config, int ret)
{
  pthread_mutex_lock(&cached_status_lock);
  bool validating = cached_status.validating;
  int runtime_error=!ret&&cached_status.revision==config->request.revision?cached_status.error:0;
  memset(&cached_status, 0, sizeof(cached_status));
  cached_status.validating = validating;
  if (!ret)
    {
      cached_status.revision = config->request.revision;
      cached_status.configured = true;
      cached_status.skipped = config->request.operation == BUFFER_OOBE_SKIP_AGENT;
      cached_status.ready =
        config->request.operation == BUFFER_OOBE_CONFIGURE_AGENT &&
        config->validated;
      cached_status.error = config->last_error?config->last_error:runtime_error;
    }
  else cached_status.error = ret == -ENOENT ? 0 : ret;
  pthread_mutex_unlock(&cached_status_lock);
}

void buffer_agent_snapshot(struct buffer_agent_status_s *status)
{
  if (!status) return;
  pthread_mutex_lock(&cached_status_lock); *status = cached_status; pthread_mutex_unlock(&cached_status_lock);
}

void buffer_agent_set_validating(bool validating)
{
  pthread_mutex_lock(&cached_status_lock); cached_status.validating = validating; pthread_mutex_unlock(&cached_status_lock);
}

void buffer_agent_report_error(int error)
{
  pthread_mutex_lock(&cached_status_lock);
  cached_status.error = error;
  pthread_mutex_unlock(&cached_status_lock);
}

static void wipe(void *data, size_t length)
{ volatile unsigned char *p = data; while (length--) *p++ = 0; }

void buffer_agent_config_wipe(struct buffer_agent_config_s *config)
{ if (config) wipe(config, sizeof(*config)); }

bool buffer_agent_valid_id(const char *id)
{
  if (!id || !id[0] || strlen(id) >= BUFFER_ID_SIZE) return false;
  for (const char *p = id; *p; p++)
    if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
          (*p >= '0' && *p <= '9') || *p == '-' || *p == '_')) return false;
  return true;
}

int buffer_agent_record_path(char *path, size_t size, const char *id)
{
  if (!buffer_agent_valid_id(id)) return -EINVAL;
  int n = snprintf(path, size, "%s/records/%s.json", CONFIG_BUFFER_VELA_DATA_DIR, id);
  return n < 0 || (size_t)n >= size ? -ENAMETOOLONG : 0;
}

static int private_directory(const char *path)
{
  struct stat st;
  if (mkdir(path, 0700) && errno != EEXIST) return -errno;
  if (lstat(path, &st) || !S_ISDIR(st.st_mode)) return -EACCES;
  /* chmod also updates ctime and commits LittleFS metadata. Most calls are
   * checks of existing private directories; keep those checks read-only. */
  if((st.st_mode&0777)!=0700){
    if (chmod(path, 0700)) return -errno;
    if (stat(path, &st) || (st.st_mode & 0777) != 0700) return -ENODEV;
  }
  return 0;
}

static int persistent_backend(const char *path)
{
  int ready=buffer_private_storage_prepare();if(ready)return ready;
#ifdef __NuttX__
#ifndef CONFIG_FS_LITTLEFS_ATTR_UPDATE
  return -ENODEV;
#else
  struct file directory;
  char parent[320];
  if (strlen(path) >= sizeof(parent)) return -ENAMETOOLONG;
  strcpy(parent, path);
  for (;;)
    {
      /* statfs() calls lfs_fs_size(): a complete NAND traversal under the
       * filesystem mutex. Inspect the opened mount's operations instead;
       * opening also resolves the real backend after any mount change. */
      int ret = file_open(&directory, parent, O_RDONLY | O_DIRECTORY);
      if (!ret)
        {
          const struct fs_dirent_s *dir=directory.f_priv;
          bool persistent = dir && dir->fd_root &&
              dir->fd_root->u.i_mops == &g_littlefs_operations;
          int closed = file_close(&directory);
          return !persistent ? -ENODEV : closed;
        }
      if (ret == -ENOTDIR)
        {
          /* Atomic writers validate an existing destination file too.
           * Check its containing mount; a non-directory path component
           * still fails stat() and is rejected. */
          struct stat st;
          if (stat(parent,&st) || !S_ISREG(st.st_mode)) return ret;
        }
      else if (ret != -ENOENT) return ret;
      char *end = strrchr(parent, '/');
      if (!end || !strcmp(parent,"/")) return -ENODEV;
      if (end == parent) parent[1] = 0;
      else *end = 0;
    }
#endif
#else
  (void)path;
#endif
  return 0;
}

int buffer_agent_store_init(void)
{
  int ret = persistent_backend(CONFIG_BUFFER_VELA_DATA_DIR);
  if (ret < 0) return ret;
  ret = private_directory(CONFIG_BUFFER_VELA_DATA_DIR);
  if (ret < 0) return ret;
  ret = private_directory(CONFIG_BUFFER_VELA_DATA_DIR "/records");
  if (ret < 0) return ret;
  ret = buffer_source_events_init();
  if (ret < 0) return ret;
  struct buffer_agent_config_s *config = calloc(1, sizeof(*config));
  if (!config) return -ENOMEM;
  ret = buffer_agent_config_load(config);
  buffer_agent_config_wipe(config); free(config);
  return ret == -ENOENT ? 0 : ret;
}

int buffer_agent_private_directory(const char *path)
{
  int ret=persistent_backend(path);
  return ret<0?ret:private_directory(path);
}

int buffer_agent_sync_parent(const char *path)
{
#ifndef __NuttX__
  char parent[320];
  if (strlen(path)>=sizeof(parent)) return -ENAMETOOLONG;
  snprintf(parent,sizeof(parent),"%s",path);
  char *slash=strrchr(parent,'/');
  if (slash) *slash=0;
  int fd=open(slash?(parent[0]?parent:"/"):".",O_RDONLY|O_DIRECTORY);
  if (fd<0) return -errno;
  int ret=fsync(fd)?-errno:0;
  if (close(fd)&&!ret) ret=-errno;
  return ret;
#else
  /* The private writer admits only LittleFS: rename/unlink commit metadata
   * and invoke cfg.sync. Its directory descriptors do not support fsync. */
  (void)path;
  return 0;
#endif
}

int buffer_agent_atomic_bytes(const char *path, const char *data, size_t length, size_t limit)
{
  if (!path||!data||!length||length>limit||limit>BUFFER_SOURCE_INTENT_BYTES) return -EFBIG;
  int backend = persistent_backend(path);
  if (backend < 0) return backend;
  char temporary[320];
  int ret = 0, fd = -1, stage = 1;
  if (snprintf(temporary, sizeof(temporary), "%s.tmp", path) >= (int)sizeof(temporary))
    { ret = -EFBIG; goto done; }
  int flags = O_WRONLY | O_CREAT | O_TRUNC;
#ifdef O_NOFOLLOW
  flags |= O_NOFOLLOW;
#endif
  fd = open(temporary, flags, 0600);
  if (fd < 0) { ret = -errno; goto done; }
  stage = 2;
  if (fchmod(fd, 0600)) { ret = -errno; goto close_file; }
  stage = 3;
  struct stat st;
  if (fstat(fd, &st)) { ret = -errno; goto close_file; }
  if ((st.st_mode & 0777) != 0600) { ret = -ENODEV; goto close_file; }
  stage = 4;
  for (size_t offset = 0; offset < length; )
    {
      ssize_t n = write(fd, data + offset, length - offset);
      if (n < 0 && errno == EINTR) continue;
      if (n <= 0) { ret = n < 0 ? -errno : -EIO; goto close_file; }
      offset += (size_t)n;
    }
  stage = 5;
  if (fsync(fd)) ret = -errno;
close_file:
  if (!ret) stage = 6;
  if (close(fd) && !ret) ret = -errno;
  fd = -1;
  if (!ret) stage = 7;
  if (!ret && rename(temporary, path)) ret = -errno;
  if (!ret) ret=buffer_agent_sync_parent(path);
  if (ret) unlink(temporary);
done:
  if (ret) syslog(LOG_WARNING, "Buffer Store: atomic stage=%d result=%d\n", stage, ret);
  return ret;
}

int buffer_agent_atomic_json(const char *path, const cJSON *json)
{
  if (buffer_source_is_record_path(path)) return buffer_source_record_save(path,json);
  if (buffer_source_is_projection_path(path)) return buffer_source_projection_json(path,json,NULL,NULL);
  char *raw=cJSON_PrintUnformatted(json);
  if (!raw) return -ENOMEM;
  size_t length=strlen(raw);
  int ret=buffer_agent_atomic_bytes(path,raw,length,BUFFER_AGENT_JSON_BYTES);
  wipe(raw,length);free(raw);return ret;
}

int buffer_agent_read_bytes(const char *path, size_t limit, char **data, size_t *bytes)
{
  if (!path||!data||!bytes||!limit||limit>BUFFER_SOURCE_INTENT_BYTES) return -EINVAL;
  *data=NULL;*bytes=0;
  struct stat st;
  int flags = O_RDONLY;
#ifdef O_NOFOLLOW
  flags |= O_NOFOLLOW;
#endif
  int fd = open(path, flags);
  if (fd < 0) return -errno;
  if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
      st.st_size > (off_t)limit || (st.st_mode & 0077))
    { close(fd); return -EACCES; }
  size_t length = (size_t)st.st_size, offset = 0;
  char *text = calloc(1, length + 1);
  if (!text) { close(fd); return -ENOMEM; }
  while (offset < length)
    {
      ssize_t n = read(fd, text + offset, length - offset);
      if (n < 0 && errno == EINTR) continue;
      if (n <= 0) { close(fd); wipe(text, length); free(text); return -EIO; }
      offset += (size_t)n;
    }
  if (close(fd)||memchr(text,0,length)) {wipe(text,length);free(text);return -EIO;}
  *data=text;*bytes=length;return 0;
}

cJSON *buffer_agent_parse_json(const char *raw)
{
  if(!raw){errno=EINVAL;return NULL;}
  unsigned depth=0;bool quoted=false,escaped=false;
  for(const char *p=raw;*p;p++){
    if(quoted){if(escaped)escaped=false;else if(*p=='\\')escaped=true;else if(*p=='"')quoted=false;}
    else if(*p=='"')quoted=true;
    else if(*p=='{'||*p=='['){if(++depth>32){errno=EFBIG;return NULL;}}
    else if(*p=='}'||*p==']'){if(!depth){errno=EINVAL;return NULL;}depth--;}
  }
  if(depth||quoted){errno=EINVAL;return NULL;}
  cJSON *json=cJSON_ParseWithOpts(raw,NULL,true);
  if (!json)
    errno = EINVAL;
  return json;
}

cJSON *buffer_agent_read_json_plain(const char *path, size_t limit)
{
  char *raw=NULL;size_t length=0;
  int ret=buffer_agent_read_bytes(path,limit,&raw,&length);
  if (ret) {errno=-ret;return NULL;}
  cJSON *json=buffer_agent_parse_json(raw);
  wipe(raw,length);free(raw);
  return json;
}

cJSON *buffer_agent_read_json(const char *path, size_t limit)
{
  if (buffer_source_is_record_path(path)) return buffer_source_record_read(path,limit);
  if (buffer_source_is_projection_path(path)) return buffer_source_projection_read(path,limit);
  return buffer_agent_read_json_plain(path,limit);
}

static void wipe_json_keys(cJSON *json)
{
  if (!json) return;
  cJSON *request = cJSON_GetObjectItemCaseSensitive(json, "request");
  cJSON *payload = cJSON_GetObjectItemCaseSensitive(request, "payload");
  cJSON *key = cJSON_GetObjectItemCaseSensitive(payload, "api_key");
  if (cJSON_IsString(key)) wipe(key->valuestring, strlen(key->valuestring));
}

static int config_load(struct buffer_agent_config_s *config)
{
  buffer_agent_config_wipe(config);
  cJSON *json = buffer_agent_read_json(AGENT_FILE, BUFFER_AGENT_JSON_BYTES);
  if (!json) return -errno;
  cJSON *request = cJSON_GetObjectItemCaseSensitive(json, "request");
  cJSON *validated = cJSON_GetObjectItemCaseSensitive(json, "validated");
  cJSON *error = cJSON_GetObjectItemCaseSensitive(json, "last_error");
  char *encoded = cJSON_PrintUnformatted(request);
  int ret = encoded ? buffer_oobe_decode((const unsigned char *)encoded,
      strlen(encoded), &config->request) : -EINVAL;
  if (!ret && config->request.operation != BUFFER_OOBE_CONFIGURE_AGENT &&
      config->request.operation != BUFFER_OOBE_SKIP_AGENT) ret = -EINVAL;
  if (!ret && (!cJSON_IsBool(validated) || !cJSON_IsNumber(error) ||
                error->valuedouble > 0 || error->valuedouble < -4096 ||
                (double)error->valueint != error->valuedouble)) ret = -EINVAL;
  if (!ret)
    {
      config->validated = cJSON_IsTrue(validated);
      config->last_error = error->valueint;
    }
  if (encoded) { wipe(encoded, strlen(encoded)); free(encoded); }
  wipe_json_keys(json); cJSON_Delete(json);
  if (ret < 0) buffer_agent_config_wipe(config);
  return ret;
}

int buffer_agent_config_load(struct buffer_agent_config_s *config)
{
  if (!config) return -EINVAL;
  pthread_mutex_lock(&config_lock);
  int ret = config_load(config);
  status_update(config, ret);
  pthread_mutex_unlock(&config_lock);
  return ret;
}

static int config_save(const struct buffer_agent_config_s *config)
{
  const struct buffer_oobe_request_s *r = &config->request;
  cJSON *json = cJSON_CreateObject(), *request = cJSON_CreateObject();
  cJSON *payload = cJSON_CreateObject();
  if (!json || !request || !payload)
    { cJSON_Delete(json); cJSON_Delete(request); cJSON_Delete(payload); return -ENOMEM; }
  cJSON_AddStringToObject(request, "type", "vela.oobe.request");
  cJSON_AddNumberToObject(request, "protocol", 1);
  cJSON_AddStringToObject(request, "request_id", r->request_id);
  cJSON_AddNumberToObject(request, "revision", (double)r->revision);
  cJSON_AddStringToObject(request, "operation", r->operation == BUFFER_OOBE_SKIP_AGENT ?
      "agent.skip" : "agent.configure");
  cJSON_AddItemToObject(request, "payload", payload);
  if (r->operation == BUFFER_OOBE_CONFIGURE_AGENT)
    {
      cJSON_AddStringToObject(payload, "base_url", r->base_url);
      cJSON_AddStringToObject(payload, "completion_model", r->completion_model);
      cJSON_AddStringToObject(payload, "transcription_model", r->transcription_model);
      cJSON_AddStringToObject(payload, "api_key", r->api_key);
      cJSON_AddStringToObject(payload, "time_zone", r->time_zone);
      cJSON_AddStringToObject(payload, "language", r->language);
    }
  cJSON_AddItemToObject(json, "request", request);
  cJSON_AddBoolToObject(json, "validated", config->validated);
  cJSON_AddNumberToObject(json, "last_error", config->last_error);
  int ret = buffer_agent_atomic_json(AGENT_FILE, json);
  wipe_json_keys(json); cJSON_Delete(json);
  return ret;
}

int buffer_agent_config_apply(const struct buffer_oobe_request_s *request)
{
  if (!request || (request->operation != BUFFER_OOBE_CONFIGURE_AGENT &&
      request->operation != BUFFER_OOBE_SKIP_AGENT)) return -EINVAL;
  if (request->operation == BUFFER_OOBE_CONFIGURE_AGENT &&
      (!request->api_key[0] || !buffer_https_url_valid(request->base_url))) return -EINVAL;
  int initialized = buffer_agent_store_init();
  if (initialized < 0 && initialized != -EINVAL) return initialized;
  struct buffer_agent_config_s *current = calloc(1, sizeof(*current));
  if (!current) return -ENOMEM;
  pthread_mutex_lock(&config_lock);
  int ret = config_load(current);
  if (ret == -ENOENT) ret = 0;
  else if (!ret && request->revision < current->request.revision) ret = -ESTALE;
  else if (!ret && request->revision == current->request.revision)
    {
      ret = memcmp(request, &current->request, sizeof(*request)) ? -EEXIST : 1;
    }
  if (!ret)
    {
      buffer_agent_config_wipe(current);
      current->request = *request;
      /* Skipping setup is not successful service verification. Keep old
       * on-disk skip records from ever advertising LLM/ASR readiness. */
      current->validated = false;
      ret = config_save(current);
    }
  else if (ret == 1) ret = 0;
  if (!ret) status_update(current, 0);
  pthread_mutex_unlock(&config_lock);
  buffer_agent_config_wipe(current); free(current);
  return ret;
}

int buffer_agent_config_result(const struct buffer_agent_config_s *config,
                               bool validated, int error)
{
  struct buffer_agent_config_s *current = calloc(1, sizeof(*current));
  if (!current) return -ENOMEM;
  pthread_mutex_lock(&config_lock);
  int ret = config_load(current);
  if (!ret && memcmp(&config->request, &current->request, sizeof(config->request))) ret = -ESTALE;
  if (!ret)
    { current->validated = validated; current->last_error = error; ret = config_save(current); }
  if (!ret) status_update(current, 0);
  if(!ret)buffer_agent_report_error(error);
  pthread_mutex_unlock(&config_lock);
  buffer_agent_config_wipe(current); free(current);
  return ret;
}

const char *buffer_agent_error_code(int error)
{
  switch (error)
    {
      case 0: return "";
      case -EACCES: return "tls_certificate_or_permissions";
      case -EPERM: return "service_authentication";
      case -EAGAIN: return "clock_or_service_not_ready";
      case -ETIMEDOUT: return "service_timeout";
      case -ESTALE: return "stale_revision";
      case -EEXIST: return "revision_conflict";
      case -ENOSPC: return "storage_or_record_budget";
      case -ENODEV: case -EROFS: return "storage_unavailable";
      case -ENOTSUP: return "persistent_private_storage_required";
      case -EFBIG: return "message_or_model_budget";
      case -EINVAL: case -EPROTO: return "invalid_configuration_or_model_result";
      case -ENOMEM: return "out_of_memory";
      default: return "network_or_storage_error";
    }
}
