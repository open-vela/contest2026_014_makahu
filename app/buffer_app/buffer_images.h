/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_IMAGES_H
#define BUFFER_IMAGES_H
#include <stddef.h>
#include <stdint.h>
#include <netutils/cJSON.h>
#define BUFFER_IMAGE_ID_BYTES 65
#define BUFFER_IMAGE_WIDTH 176
#define BUFFER_IMAGE_HEIGHT 160
/* Worker-only search, persistence, download and JPEG decode. Selection is
 * restricted to a result returned for this capture, never a model URL. */
cJSON *buffer_images_search(const char *capture_id,const char *query,unsigned page);
int buffer_images_select(const char *capture_id,const char *image_id);
int buffer_images_request(const char *capture_id,const char *image_id);
int buffer_images_prepare(const char *capture_id,const char *image_id);
struct buffer_image_view {const uint16_t *pixels;unsigned width,height,lease;};
/* Owner-thread lookup never waits for I/O/decode. A lease pins its pixels until
 * the last image widget releases them, including across LRU replacement. */
int buffer_images_acquire(const char *image_id,struct buffer_image_view *view);
void buffer_images_release(unsigned lease);
void buffer_images_shutdown(void);
#endif
