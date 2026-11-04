/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_agent.h"
#include "buffer_fabric.h"
#include "buffer_coordinator.h"
#include "buffer_private_storage.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>

/* Local administrator diagnostics. No remote enrollment bypass, secrets in
 * argv, private audio generation, or endpoint/TLS override is provided. */
int buffer_agent_command(int argc, char **argv)
{
  int ret = -EINVAL;
  if (argc < 1) goto usage;
  if(!strcmp(argv[0],"storage-provision")&&argc==2){
    ret=buffer_private_storage_provision(argv[1]);
    printf("private_storage provisioning=%d (dedicated erased bufpriv only)\n",ret);
    return ret;
  }
  if (!strcmp(argv[0], "storage-check"))
    {
      ret = buffer_agent_store_init();
      if (!ret)
        {
          const char *path = CONFIG_BUFFER_VELA_DATA_DIR "/storage-probe.json";
          cJSON *probe = cJSON_CreateObject();
          if (!probe) return -ENOMEM;
          cJSON_AddBoolToObject(probe, "private_storage_probe", true);
          ret = buffer_agent_atomic_json(path, probe); cJSON_Delete(probe);
          struct stat st;
          if (!ret && (stat(path, &st) || (st.st_mode & 0777) != 0600)) ret = -EACCES;
          cJSON *loaded = !ret ? buffer_agent_read_json(path, 1024) : NULL;
          if (!ret && !loaded) ret = -EIO;
          cJSON_Delete(loaded); unlink(path);
        }
      printf("private_storage=%s error=%d path=%s\n", ret ? "unavailable" : "verified", ret, CONFIG_BUFFER_VELA_DATA_DIR);
      return ret;
    }
  if (!strcmp(argv[0], "status"))
    {
      ret = buffer_agent_store_init();
      struct buffer_agent_status_s status; buffer_agent_snapshot(&status);
      printf("agent storage=%d configured=%d ready=%d skipped=%d validating=%d error=%d revision=%llu\n",
             ret, status.configured, status.ready, status.skipped, status.validating,
             status.error, (unsigned long long)status.revision);
#ifdef CONFIG_BUFFER_VELA_FABRIC
      struct buffer_fabric_status_s fabric;
      buffer_fabric_snapshot(&fabric);
      printf("fabric binding=%d error=%d connected=%d active=%d\n",
             fabric.binding, fabric.error, fabric.connected, fabric.active);
      char owner[65]; uint64_t term;
      buffer_coordinator_status(owner,sizeof(owner),&term);
      printf("coordinator owner=%s term=%llu local=%d\n",owner,(unsigned long long)term,buffer_coordinator_local());
#endif
      return ret;
    }
  if (!strcmp(argv[0], "process"))
    { ret = buffer_agent_process_once(); printf("agent process=%d\n", ret); return ret; }
  if (!strcmp(argv[0], "text") && argc == 3)
    { ret = buffer_agent_submit_text(argv[1], argv[2], "normal", time(NULL) * 1000LL);
      printf("agent queued=%d\n", ret); return ret; }
  if (!strcmp(argv[0], "auto-text") && argc == 3)
    { ret = buffer_agent_submit_auto_text(argv[1], argv[2], "normal", time(NULL) * 1000LL);
      printf("agent queued=%d target=auto\n", ret); return ret; }
  if (!strcmp(argv[0],"assistant-text") && argc==3)
    { ret=buffer_agent_submit_assistant_text(argv[1],argv[2],time(NULL)*1000LL);
      printf("assistant queued=%d\n",ret); return ret; }
  if (!strcmp(argv[0], "configure-file") && argc == 2)
    {
      /* Input is a local administrator-provided, typed OOBE request. Its
       * credentials are written only by the same verified private store. */
      int fd = open(argv[1], O_RDONLY);
      if (fd < 0) return -errno;
      char *data = calloc(1, 12289);
      struct buffer_oobe_request_s *request = calloc(1, sizeof(*request));
      if (!data || !request) { free(data); free(request); close(fd); return -ENOMEM; }
      ssize_t length = read(fd, data, 12289); close(fd);
      if (length > 0 && length <= 12288)
        {
          ret = buffer_oobe_decode((unsigned char *)data, length, request);
          if (!ret && request->operation != BUFFER_OOBE_CONFIGURE_AGENT &&
              request->operation != BUFFER_OOBE_SKIP_AGENT) ret = -EINVAL;
          if (!ret) ret = buffer_agent_config_apply(request);
        }
      memset(data, 0, 12289); memset(request, 0, sizeof(*request)); free(data); free(request);
      printf("agent configuration_saved=%d (service validation pending)\n", ret);
      if (!ret) buffer_agent_retry();
      return ret;
    }
usage:
  printf("buffer_app agent storage-provision --initialize-erased-bufpriv (S1 factory layout, erased partition only)\n");
  printf("buffer_app agent storage-check|status|process|text <id> <text>|auto-text <id> <text>|assistant-text <id> <text>|configure-file <local-request.json>\n");
  return -EINVAL;
}
