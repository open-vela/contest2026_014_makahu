/* Every native Rounded mask is decoded by the actual SDK image decoder and
 * compared byte-for-byte (via CRC32) with its uncompressed SVG raster. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "buffer_ui_icons.h"
struct icon_alpha_check {const lv_image_dsc_t *image;uint32_t crc;};
#include "icon_alpha_checks.inc"
static uint32_t alpha_crc(const lv_draw_buf_t *buffer)
{
 uint32_t crc=~0u;
 for(unsigned y=0;y<buffer->header.h;y++)for(unsigned x=0;x<buffer->header.w;x++){
  crc^=buffer->data[y*buffer->header.stride+x];
  for(unsigned bit=0;bit<8;bit++)crc=(crc>>1)^(0xedb88320u&-(crc&1));
 }
 return ~crc;
}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(320,240);assert(display);
 for(unsigned i=0;i<sizeof(checks)/sizeof(checks[0]);i++){
  lv_image_decoder_dsc_t decoder;assert(lv_image_decoder_open(&decoder,checks[i].image,NULL)==LV_RESULT_OK);
  assert(decoder.decoded&&decoder.decoded->header.w==checks[i].image->header.w&&decoder.decoded->header.h==checks[i].image->header.h);
  assert(decoder.decoded->header.cf==LV_COLOR_FORMAT_A8);assert(alpha_crc(decoder.decoded)==checks[i].crc);
  lv_image_decoder_close(&decoder);
 }
 lv_display_delete(display);lv_deinit();
 printf("PASS %zu native Rounded masks retain every original alpha byte through SDK lossless decoding\n",sizeof(checks)/sizeof(checks[0]));
 return 0;
}
