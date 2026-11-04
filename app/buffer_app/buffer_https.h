/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_HTTPS_H
#define BUFFER_HTTPS_H
#include <stddef.h>
#include <stdbool.h>

/* No redirect, proxy, HTTP fallback or caller-controlled verification mode.
 * Response ownership passes to the caller only on success. */
int buffer_https_post(const char *base_url, const char *api_key,
                      const char *json, size_t json_length,
                      char **response, int *status);
typedef int (*buffer_https_data_fn)(const char *data, size_t length, void *context);
typedef bool (*buffer_https_cancel_fn)(void *context);
int buffer_https_post_stream(const char *base_url, const char *api_key,
                             const char *json, size_t length,
                             buffer_https_data_fn data,
                             buffer_https_cancel_fn cancelled,
                             void *context, int *status);
int buffer_https_url_valid(const char *url);
/* Credential-free, bounded image-provider GET. Binary length is explicit. */
int buffer_https_get_public(const char *url,unsigned timeout_ms,
                            char **response,size_t *length,int *status);
#endif
