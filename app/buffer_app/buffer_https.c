/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_https.h"
#include "buffer_agent_roots.h"
#include "buffer_entropy.h"

#include <mbedtls/ctr_drbg.h>
#include <mbedtls/ssl.h>
#include <mbedtls/x509_crt.h>
#include <mbedtls/net_sockets.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <syslog.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#if !defined(MBEDTLS_HAVE_TIME_DATE) || !defined(MBEDTLS_X509_CRT_PARSE_C)
#error Buffer HTTPS requires certificate parsing and certificate date validation
#endif
#ifndef CONFIG_BUFFER_VELA_AGENT_CA_FILE
#define CONFIG_BUFFER_VELA_AGENT_CA_FILE ""
#endif
#ifndef CONFIG_BUFFER_VELA_AGENT_HTTP_TIMEOUT_MS
#define CONFIG_BUFFER_VELA_AGENT_HTTP_TIMEOUT_MS 45000
#endif
#define HEADER_LIMIT 8192u
#define BODY_LIMIT (64u * 1024u)
#define IMAGE_BODY_LIMIT (256u * 1024u)
#define WIRE_LIMIT (HEADER_LIMIT + BODY_LIMIT + 8192u)
#define REQUEST_LIMIT (768u * 1024u)

struct endpoint { char host[64]; char port[6]; char path[1536]; };
struct bio { int fd; int64_t deadline; buffer_https_cancel_fn cancelled; void *context; };

static int64_t now_ms(void)
{
  struct timespec ts;
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int endpoint_parse(const char *url, struct endpoint *ep)
{
  const char *authority, *end, *colon, *path;
  size_t length;
  if (!url || strlen(url) > 256 || strncmp(url, "https://", 8) ||
      strpbrk(url, "?#@\\ \t\r\n")) return -EINVAL;
  authority = url + 8;
  path = strchr(authority, '/');
  end = path ? path : authority + strlen(authority);
  colon = memchr(authority, ':', (size_t)(end - authority));
  length = (size_t)((colon ? colon : end) - authority);
  if (!length || length >= sizeof(ep->host)) return -EINVAL;
  for (size_t i = 0; i < length; i++)
    if (!((authority[i] >= 'a' && authority[i] <= 'z') ||
          (authority[i] >= 'A' && authority[i] <= 'Z') ||
          (authority[i] >= '0' && authority[i] <= '9') ||
          authority[i] == '-' || authority[i] == '.')) return -EINVAL;
  memcpy(ep->host, authority, length); ep->host[length] = 0;
  strcpy(ep->port, "443");
  if (colon)
    {
      unsigned int port = 0;
      if (end - colon < 2 || end - colon > 6) return -EINVAL;
      for (const char *p = colon + 1; p < end; p++)
        { if (*p < '0' || *p > '9') return -EINVAL; port = port * 10 + *p - '0'; }
      if (!port || port > 65535) return -EINVAL;
      snprintf(ep->port, sizeof(ep->port), "%u", port);
    }
  length = path ? strlen(path) : 0;
  while (length && path[length - 1] == '/') length--;
  if (length >= strlen("/chat/completions") &&
      !strncmp(path + length - strlen("/chat/completions"), "/chat/completions",
                strlen("/chat/completions")))
    { memcpy(ep->path, path, length); ep->path[length] = 0; return 0; }
  if (!length) { strcpy(ep->path, "/v1/chat/completions"); return 0; }
  if (length + sizeof("/chat/completions") > sizeof(ep->path)) return -EINVAL;
  if (length) memcpy(ep->path, path, length);
  strcpy(ep->path + length, "/chat/completions");
  return 0;
}

int buffer_https_url_valid(const char *url)
{
  struct endpoint ep;
  return endpoint_parse(url, &ep) == 0;
}

static int wait_fd(struct bio *bio, short events)
{
  while (true)
    {
      if (bio->cancelled && bio->cancelled(bio->context)) return -ECANCELED;
      int64_t remaining = bio->deadline - now_ms();
      struct pollfd pfd = { .fd = bio->fd, .events = events };
      if (remaining <= 0) return -ETIMEDOUT;
      int ret = poll(&pfd, 1, bio->cancelled && remaining > 250 ? 250 : (int)remaining);
      if (ret < 0 && errno == EINTR) continue;
      if (ret < 0) return -errno;
      if (!ret) { if (bio->cancelled) continue; return -ETIMEDOUT; }
      if (pfd.revents & (events | POLLHUP)) return 0;
      return -EIO;
    }
}

static int tcp_connect(struct bio *bio, const struct endpoint *ep)
{
  struct addrinfo hints, *addresses = NULL;
  int ret = -EHOSTUNREACH;
  memset(&hints, 0, sizeof(hints));
  hints.ai_socktype = SOCK_STREAM; hints.ai_family = AF_UNSPEC;
  if (getaddrinfo(ep->host, ep->port, &hints, &addresses) != 0) return ret;
  /* DNS uses the platform resolver's own timeout. Socket/TLS/HTTP operations
   * share a single monotonic deadline, so slow chunks cannot renew it. */
  for (struct addrinfo *a = addresses; a; a = a->ai_next)
    {
      bio->fd = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
      if (bio->fd < 0) continue;
      if (fcntl(bio->fd, F_SETFL, O_NONBLOCK) < 0) { ret = -errno; goto next; }
      if (connect(bio->fd, a->ai_addr, a->ai_addrlen) == 0) { ret = 0; break; }
      if (errno == EINPROGRESS || errno == EWOULDBLOCK)
        {
          ret = wait_fd(bio, POLLOUT);
          if (!ret)
            {
              int error = 0; socklen_t size = sizeof(error);
              if (getsockopt(bio->fd, SOL_SOCKET, SO_ERROR, &error, &size) < 0)
                ret = -errno;
              else ret = error ? -error : 0;
            }
          if (!ret) break;
        }
      else ret = -errno;
next:
      close(bio->fd); bio->fd = -1;
      if (ret == -ETIMEDOUT) break;
    }
  freeaddrinfo(addresses);
  return ret;
}

static int secure_entropy(void *ctx, unsigned char *out, size_t size)
{
  (void)ctx;
  return buffer_entropy_read(out, size) == 0 ? 0 :
      MBEDTLS_ERR_CTR_DRBG_ENTROPY_SOURCE_FAILED;
}

static int bio_io(void *ctx, unsigned char *data, size_t size, bool writing)
{
  struct bio *bio = ctx;
  while (true)
    {
      int ready = wait_fd(bio, writing ? POLLOUT : POLLIN);
      if (ready < 0) return ready == -ETIMEDOUT ? MBEDTLS_ERR_SSL_TIMEOUT :
          (writing ? MBEDTLS_ERR_NET_SEND_FAILED : MBEDTLS_ERR_NET_RECV_FAILED);
      ssize_t n;
      if (writing)
        {
#ifdef MSG_NOSIGNAL
          n = send(bio->fd, data, size, MSG_NOSIGNAL);
#else
          n = send(bio->fd, data, size, 0);
#endif
        }
      else n = recv(bio->fd, data, size, 0);
      if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
      if (n < 0) return writing ? MBEDTLS_ERR_NET_SEND_FAILED : MBEDTLS_ERR_NET_RECV_FAILED;
      return (int)n;
    }
}
static int bio_send(void *ctx, const unsigned char *p, size_t n)
{ return bio_io(ctx, (unsigned char *)p, n, true); }
static int bio_recv(void *ctx, unsigned char *p, size_t n)
{ return bio_io(ctx, p, n, false); }

static int load_ca(mbedtls_x509_crt *ca)
{
  const char *path = CONFIG_BUFFER_VELA_AGENT_CA_FILE;
  if (!path[0]) return mbedtls_x509_crt_parse(ca,
      (const unsigned char *)buffer_agent_roots, sizeof(buffer_agent_roots)) == 0 ? 0 : -EACCES;
  FILE *file = fopen(path, "r");
  if (!file) return -errno;
  size_t limit = 320u * 1024u;
  unsigned char *pem = calloc(1, limit + 1);
  if (!pem) { fclose(file); return -ENOMEM; }
  size_t length = fread(pem, 1, limit, file);
  int ret = ferror(file) || !feof(file) ? -EFBIG : 0;
  fclose(file);
  if (!ret) {
      /* Terminate after the actual read, including on DMA/semihost backends
       * which may invalidate untouched tail cache lines of the input buffer. */
      pem[length] = 0;
      int parsed = length ? mbedtls_x509_crt_parse(ca, pem, length + 1) : -1;
      if (parsed != 0) { fprintf(stderr,"Buffer HTTPS: managed CA parse failed (%d)\n",parsed); ret=-EACCES; }
  }
  free(pem);
  return ret;
}

static int ssl_write_all(mbedtls_ssl_context *ssl, const char *p, size_t length)
{
  while (length)
    {
      size_t batch = length > 8192 ? 8192 : length;
      int n = mbedtls_ssl_write(ssl, (const unsigned char *)p, batch);
      if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
      if (n <= 0) return n == MBEDTLS_ERR_SSL_TIMEOUT ? -ETIMEDOUT : -EIO;
      p += n; length -= (size_t)n;
    }
  return 0;
}

/* Parse HTTP with explicit byte bounds. Duplicate framing and incomplete
 * chunks/Content-Length fail; a model result is never accepted truncated. */
static int response_parse_bytes(char *wire, size_t length, char **body, int *status, bool binary, size_t *body_length)
{
  const size_t body_limit=binary?IMAGE_BODY_LIMIT:BODY_LIMIT;
  if (!binary && memchr(wire, 0, length)) return -EPROTO;
  char *split = strstr(wire, "\r\n\r\n");
  /* Do not wait for the TLS connection to close after a complete HTTP frame.
   * Partial frames must remain unmodified for the next socket read. */
  if (!split) return length > HEADER_LIMIT ? -EFBIG : -EAGAIN;
  if ((size_t)(split - wire) > HEADER_LIMIT) return -EFBIG;
  if (memchr(wire,0,(size_t)(split-wire))) return -EPROTO;
  char *line = strstr(wire, "\r\n");
  if (!line || line > split || sscanf(wire, "HTTP/1.%*1[01] %d", status) != 1 ||
      *status < 200 || *status > 599) return -EPROTO;
  bool has_length = false, chunked = false;
  size_t content_length = 0;
  for (char *p = line + 2; p < split; )
    {
      char *end = strstr(p, "\r\n"), *colon = strchr(p, ':');
      if (!end || !colon || colon > end || *p == ' ' || *p == '\t') return -EPROTO;
      char *value = colon + 1;
      while (value < end && (*value == ' ' || *value == '\t')) value++;
      if ((colon - p == 14) && !strncasecmp(p, "Content-Length", 14))
        {
          if (has_length || value == end) return -EPROTO;
          has_length = true;
          for (char *q = value; q < end; q++)
            {
              if (*q < '0' || *q > '9') return -EPROTO;
              content_length = content_length * 10 + (unsigned)(*q - '0');
              if (content_length > body_limit) return -EFBIG;
            }
        }
      if ((colon - p == 17) && !strncasecmp(p, "Transfer-Encoding", 17))
        {
          if (chunked || end - value != 7 || strncasecmp(value, "chunked", 7)) return -EPROTO;
          chunked = true;
        }
      if ((colon - p == 16) && !strncasecmp(p, "Content-Encoding", 16) &&
          (end - value != 8 || strncasecmp(value, "identity", 8))) return -ENOTSUP;
      p = end + 2;
    }
  char *data = split + 4;
  size_t available = length - (size_t)(data - wire), decoded = 0;
  if ((has_length && chunked) || (!has_length && !chunked)) return -EPROTO;
  if (chunked)
    {
      char *p = data, *end = wire + length;
      while (true)
        {
          char *eol = strstr(p, "\r\n");
          if (!eol) return end - p > 8 ? -EPROTO : -EAGAIN;
          if (eol - p > 8 || eol == p) return -EPROTO;
          size_t chunk = 0;
          for (char *q = p; q < eol; q++)
            {
              unsigned digit;
              if (*q >= '0' && *q <= '9') digit = *q - '0';
              else if (*q >= 'a' && *q <= 'f') digit = *q - 'a' + 10;
              else if (*q >= 'A' && *q <= 'F') digit = *q - 'A' + 10;
              else return -EPROTO;
              chunk = chunk * 16 + digit;
              if (chunk > body_limit) return -EFBIG;
            }
          p = eol + 2;
          if (!chunk)
            {
              /* Bounded trailers; accept only an empty trailer section. */
              if (end - p < 2) return -EAGAIN;
              if (end - p != 2 || memcmp(p, "\r\n", 2)) return -EPROTO;
              break;
            }
          if (chunk > (size_t)(end - p) || end - p - (ptrdiff_t)chunk < 2) return -EAGAIN;
          if (memcmp(p + chunk, "\r\n", 2)) return -EPROTO;
          if (decoded + chunk > body_limit) return -EFBIG;
          decoded += chunk; p += chunk + 2;
        }
      p = data;
      size_t copied = 0;
      while (copied < decoded)
        {
          char *eol = strstr(p, "\r\n");
          size_t chunk = (size_t)strtoul(p, NULL, 16);
          p = eol + 2;
          memmove(data + copied, p, chunk);
          copied += chunk; p += chunk + 2;
        }
    }
  else
    {
      if (available > body_limit) return -EFBIG;
      if (content_length > available) return -EAGAIN;
      if (content_length != available) return -EPROTO;
      decoded = available;
    }
  if (!binary && memchr(data, 0, decoded)) return -EPROTO;
  *body = malloc(decoded + 1);
  if (!*body) return -ENOMEM;
  memcpy(*body, data, decoded); (*body)[decoded] = 0;
  if(body_length)*body_length=decoded;
  return 0;
}

static int response_parse(char *wire,size_t length,char **body,int *status)
{return response_parse_bytes(wire,length,body,status,false,NULL);}

/* Public image search and provider thumbnails only. The model never supplies
 * a network authority or credentials to this transport. */
static int public_endpoint(const char *url,struct endpoint *ep)
{
 if(!url||strncmp(url,"https://",8)||strlen(url)>=sizeof(ep->path)||strpbrk(url,"#@\\ \t\r\n"))return -EINVAL;
 const char *path=strchr(url+8,'/');if(!path)return -EINVAL;
 size_t host=(size_t)(path-url-8);if(!host||host>=sizeof(ep->host))return -EINVAL;
 memcpy(ep->host,url+8,host);ep->host[host]=0;
 bool provider=!strcmp(ep->host,"api.ruseo.cn");
 bool preview=strlen(ep->host)==14&&!strncmp(ep->host,"img",3)&&ep->host[3]>='0'&&ep->host[3]<='9'&&!strcmp(ep->host+4,".baidu.com");
 if(!provider&&!preview)return -EACCES;
 if(provider&&strncmp(path,"/api/baidu_image?",17))return -EACCES;
 for(const unsigned char *p=(const unsigned char *)path;*p;p++)if(*p<0x21||*p>=0x7f)return -EINVAL;
 strcpy(ep->port,"443");snprintf(ep->path,sizeof(ep->path),"%s",path);return 0;
}

/* Forward only complete, validated HTTP chunks. SSE event boundaries may
 * span chunks and are handled by the caller. Never modify the framing buffer. */
static int stream_progress(const char *wire, size_t length, size_t *delivered,
                           buffer_https_data_fn callback, void *context)
{
  const char *split = strstr(wire, "\r\n\r\n");
  if (!split) return 0;
  bool chunked = false;
  for (const char *p = strstr(wire, "\r\n") + 2; p < split; )
    {
      const char *end = strstr(p, "\r\n");
      if (!end) return -EPROTO;
      if (end-p >= 18 && !strncasecmp(p, "Transfer-Encoding:", 18)) chunked = true;
      p = end+2;
    }
  const char *data = split+4, *end = wire+length;
  if (!chunked)
    {
      size_t available = end-data;
      if (available > *delivered)
        {
          int ret = callback(data+*delivered, available-*delivered, context);
          if (ret < 0) return ret;
          *delivered = available;
        }
      return 0;
    }
  size_t decoded = 0;
  for (const char *p = data; p < end; )
    {
      const char *eol = strstr(p, "\r\n");
      if (!eol) break;
      size_t chunk = strtoul(p, NULL, 16);
      p = eol+2;
      if (!chunk || chunk > (size_t)(end-p) || end-p-(ptrdiff_t)chunk < 2) break;
      if (decoded+chunk > *delivered)
        {
          size_t skip = *delivered > decoded ? *delivered-decoded : 0;
          int ret = callback(p+skip, chunk-skip, context);
          if (ret < 0) return ret;
          *delivered = decoded+chunk;
        }
      decoded += chunk; p += chunk+2;
    }
  return 0;
}

static int https_request(const char *base_url, const char *api_key,
                      const char *json, size_t json_length,
                      char **response, int *status, buffer_https_data_fn on_data,
                      buffer_https_cancel_fn cancelled, void *context, bool public_get, size_t *body_length, unsigned timeout_ms)
{
  const size_t wire_limit=public_get?HEADER_LIMIT+IMAGE_BODY_LIMIT+8192u:WIRE_LIMIT;
  int64_t began=now_ms(),setup_done=0,connect_done=0,tls_done=0,send_done=0;
  struct endpoint ep;
  struct bio bio = { .fd = -1, .deadline = now_ms() +
      (public_get ? timeout_ms : on_data ? 15000 : CONFIG_BUFFER_VELA_AGENT_HTTP_TIMEOUT_MS),
      .cancelled = cancelled, .context = context };
  mbedtls_ssl_context ssl;
  mbedtls_ssl_config cfg;
  mbedtls_x509_crt ca;
  mbedtls_ctr_drbg_context rng;
  char *header = NULL, *wire = NULL;
  int ret;
  if (!response || !status) return -EINVAL;
  *response = NULL; *status = 0;
  if(public_get){if(!body_length||timeout_ms<1000||timeout_ms>15000)return -EINVAL;int parsed=public_endpoint(base_url,&ep);if(parsed)return parsed;*body_length=0;}
  else{
    if (!api_key || !api_key[0] || strlen(api_key) > 8192 || !json ||
        json_length > REQUEST_LIMIT || endpoint_parse(base_url, &ep)) return -EINVAL;
    for (const char *p = api_key; *p; p++) if ((unsigned char)*p < 0x20 || *p == 0x7f) return -EINVAL;
  }
  if (time(NULL) < 1704067200) return -EAGAIN;
  syslog(LOG_INFO,"Buffer HTTPS: begin bytes=%lu stream=%d\n",(unsigned long)json_length,on_data!=NULL);
  mbedtls_ssl_init(&ssl); mbedtls_ssl_config_init(&cfg);
  mbedtls_x509_crt_init(&ca); mbedtls_ctr_drbg_init(&rng);
  ret = load_ca(&ca);
  if (ret < 0) goto done;
  static const unsigned char personalization[] = "buffer-agent-https";
  if (mbedtls_ctr_drbg_seed(&rng, secure_entropy, NULL, personalization,
                            sizeof(personalization) - 1)) { ret = -EIO; goto done; }
  if (mbedtls_ssl_config_defaults(&cfg, MBEDTLS_SSL_IS_CLIENT,
        MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT)) { ret = -EIO; goto done; }
  mbedtls_ssl_conf_authmode(&cfg, MBEDTLS_SSL_VERIFY_REQUIRED);
  mbedtls_ssl_conf_ca_chain(&cfg, &ca, NULL);
  mbedtls_ssl_conf_rng(&cfg, mbedtls_ctr_drbg_random, &rng);
  mbedtls_ssl_conf_min_version(&cfg, MBEDTLS_SSL_MAJOR_VERSION_3, MBEDTLS_SSL_MINOR_VERSION_3);
#ifdef MBEDTLS_SSL_ALPN
  static const char *alpn[] = { "http/1.1", NULL };
  if (mbedtls_ssl_conf_alpn_protocols(&cfg, alpn)) { ret = -EIO; goto done; }
#endif
  if (mbedtls_ssl_setup(&ssl, &cfg) || mbedtls_ssl_set_hostname(&ssl, ep.host))
    { ret = -EIO; goto done; }
  setup_done=now_ms();syslog(LOG_INFO,"Buffer HTTPS: phase=connect\n");
  ret = tcp_connect(&bio, &ep);
  if (ret < 0) goto done;
  connect_done=now_ms();syslog(LOG_INFO,"Buffer HTTPS: phase=tls\n");
  mbedtls_ssl_set_bio(&ssl, &bio, bio_send, bio_recv, NULL);
  do { ret = mbedtls_ssl_handshake(&ssl); }
  while (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE);
  if (ret || mbedtls_ssl_get_verify_result(&ssl))
    {
      uint32_t flags = mbedtls_ssl_get_verify_result(&ssl);
      fprintf(stderr,"Buffer HTTPS: TLS handshake=%d certificate_flags=0x%08lx\n",ret,(unsigned long)flags);
      ret = flags && flags != UINT32_MAX ? -EACCES :
          (ret == MBEDTLS_ERR_SSL_TIMEOUT ? -ETIMEDOUT : -EIO);
      goto done;
    }
  tls_done=now_ms();syslog(LOG_INFO,"Buffer HTTPS: phase=send\n");
  size_t capacity = strlen(api_key) + sizeof(ep.path) + 512;
  header = malloc(capacity); wire = calloc(1, wire_limit + 1);
  if (!header || !wire) { ret = -ENOMEM; goto done; }
  int n;
  if(public_get)n=snprintf(header,capacity,"GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: BufferVela/1.0\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",ep.path,ep.host);
  else n = snprintf(header, capacity,
      "POST %s HTTP/1.1\r\nHost: %s:%s\r\nAuthorization: Bearer %s\r\n"
      "Content-Type: application/json\r\nAccept-Encoding: identity\r\n"
      "Connection: close\r\nContent-Length: %lu\r\n\r\n",
      ep.path, ep.host, ep.port, api_key, (unsigned long)json_length);
  if (n < 0 || (size_t)n >= capacity) { ret = -EFBIG; goto done; }
  ret = ssl_write_all(&ssl, header, (size_t)n);
  if (!ret) ret = ssl_write_all(&ssl, json, json_length);
  if (ret < 0) goto done;
  send_done=now_ms();syslog(LOG_INFO,"Buffer HTTPS: phase=read\n");
  size_t used = 0, delivered = 0;
  while (true)
    {
      if (used == wire_limit) { ret = -EFBIG; goto done; }
      n = mbedtls_ssl_read(&ssl, (unsigned char *)wire + used, wire_limit - used);
      if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
      if (!n || n == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY)
        { ret = -EPROTO; goto done; }
      if (n < 0) { ret = n == MBEDTLS_ERR_SSL_TIMEOUT ? -ETIMEDOUT : -EIO; goto done; }
      used += (size_t)n;
      wire[used] = 0;
      ret = public_get?response_parse_bytes(wire,used,response,status,true,body_length):response_parse(wire, used, response, status);
      if (ret == 0 && on_data && *status >= 200 && *status < 300)
        {
          size_t length = strlen(*response);
          if (length > delivered) ret = on_data(*response+delivered, length-delivered, context);
        }
      if (ret != -EAGAIN) goto done;
      if (on_data && *status >= 200 && *status < 300)
        {
          ret = stream_progress(wire, used, &delivered, on_data, context);
          if (ret < 0) goto done;
        }
    }
done:
  syslog(LOG_INFO,"Buffer HTTPS timing: setup_ms=%lld connect_ms=%lld tls_ms=%lld send_ms=%lld read_ms=%lld total_ms=%lld result=%d http=%d\n",
    (long long)(setup_done?setup_done-began:-1),(long long)(connect_done?connect_done-setup_done:-1),
    (long long)(tls_done?tls_done-connect_done:-1),(long long)(send_done?send_done-tls_done:-1),
    (long long)(send_done?now_ms()-send_done:-1),(long long)(now_ms()-began),ret,*status);
  if (header) { volatile char *p = header;
    size_t bytes = strlen(api_key) + sizeof(ep.path) + 512; while (bytes--) *p++ = 0; }
  free(header); free(wire);
  if (bio.fd >= 0) close(bio.fd);
  mbedtls_ssl_free(&ssl); mbedtls_ssl_config_free(&cfg);
  mbedtls_x509_crt_free(&ca); mbedtls_ctr_drbg_free(&rng);
  return ret;
}

int buffer_https_post(const char *base_url, const char *api_key,
                      const char *json, size_t length, char **response, int *status)
{
  return https_request(base_url, api_key, json, length, response, status, NULL, NULL, NULL, false, NULL, 0);
}
int buffer_https_post_stream(const char *base_url, const char *api_key,
                             const char *json, size_t length,
                             buffer_https_data_fn data,
                             buffer_https_cancel_fn cancelled,
                             void *context, int *status)
{
  if (!data) return -EINVAL;
  char *response = NULL;
  int ret = https_request(base_url, api_key, json, length, &response, status,
                        data, cancelled, context, false, NULL, 0);
  if (response) { memset(response, 0, strlen(response)); free(response); }
  if (cancelled && cancelled(context)) return -ECANCELED;
  return ret;
}
int buffer_https_get_public(const char *url,unsigned timeout_ms,char **response,size_t *length,int *status)
{return https_request(url,"",NULL,0,response,status,NULL,NULL,NULL,true,length,timeout_ms);}
