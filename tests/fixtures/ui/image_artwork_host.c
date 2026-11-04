/* Actual production image binding + LVGL. Cache readiness is controlled to
 * exercise deferred publication, rebinding and deletion of multiple views. */
#include <assert.h>
#include <errno.h>
#include <string.h>
#include "buffer_ui_artwork.h"
#include "buffer_images.h"
static uint16_t pixels[176*160];
static unsigned acquired,released;
static bool second_ready;
static uint32_t rendered[240][320];
int buffer_images_acquire(const char *id,struct buffer_image_view *view)
{
 if(strcmp(id,"one")&&(strcmp(id,"two")||!second_ready))return -ENOENT;
 *view=(struct buffer_image_view){pixels,176,160,++acquired};return 0;
}
void buffer_images_release(unsigned lease){assert(lease&&lease<=acquired);released++;}
static void flush(lv_display_t *display,const lv_area_t *area,uint8_t *data)
{
 unsigned stride=lv_draw_buf_width_to_stride(lv_area_get_width(area),lv_display_get_color_format(display));
 for(int y=area->y1;y<=area->y2;y++)for(int x=area->x1;x<=area->x2;x++){
#if LV_COLOR_DEPTH == 16
  uint16_t value;memcpy(&value,data+(y-area->y1)*stride+2*(x-area->x1),2);
  rendered[y][x]=(((value>>11)&31)*255/31<<16)|(((value>>5)&63)*255/63<<8)|(value&31)*255/31;
#else
  uint8_t *value=data+(y-area->y1)*stride+4*(x-area->x1);rendered[y][x]=(value[2]<<16)|(value[1]<<8)|value[0];
#endif
 }
 lv_display_flush_ready(display);
}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(320,240);static uint32_t buffer[320*16];
 lv_display_set_buffers(display,buffer,NULL,sizeof(buffer),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 lv_obj_t *first=lv_image_create(lv_screen_active()),*second=lv_image_create(lv_screen_active());
 assert(!buffer_ui_recipe_artwork("海带红烧肉"));
 assert(buffer_ui_recipe_image_bind(first,"one")&&buffer_ui_recipe_image_bind(second,"one"));
 assert(acquired==2&&!released&&buffer_ui_recipe_image_ready(first));
 for(unsigned i=0;i<20;i++)assert(buffer_ui_recipe_image_bind(first,"one"));
 assert(acquired==2);
 lv_image_set_inner_align(first,LV_IMAGE_ALIGN_STRETCH);lv_obj_set_size(first,116,106);lv_obj_set_size(second,160,80);
 lv_tick_inc(16);lv_refr_now(display);
 assert(!buffer_ui_recipe_image_bind(first,"two")&&!buffer_ui_recipe_image_ready(first)&&released==1);
 lv_obj_set_size(first,150,60);lv_tick_inc(16);lv_refr_now(display);
 second_ready=true;assert(buffer_ui_recipe_image_bind(first,"two")&&acquired==3);
 lv_image_set_inner_align(first,LV_IMAGE_ALIGN_STRETCH);lv_tick_inc(16);lv_refr_now(display);
 lv_obj_delete(second);assert(released==2);lv_obj_delete(first);assert(released==3);
 for(unsigned i=0;i<sizeof(pixels)/sizeof(pixels[0]);i++)pixels[i]=0xf81f;
 lv_obj_set_style_bg_color(lv_screen_active(),lv_color_white(),0);
 lv_obj_t *frame;lv_obj_t *photo=buffer_ui_recipe_image_create(lv_screen_active(),20,&frame);
 lv_obj_set_pos(frame,10,10);lv_obj_set_style_border_width(frame,5,0);lv_obj_set_style_border_color(frame,lv_color_hex(0x00ff00),0);lv_obj_set_style_border_post(frame,true,0);
 assert(buffer_ui_recipe_image_bind(photo,"one"));lv_image_set_inner_align(photo,LV_IMAGE_ALIGN_STRETCH);
 for(unsigned width=100;width<=160;width+=60){
  lv_obj_set_size(frame,width,80);lv_tick_inc(16);lv_refr_now(display);
  assert(rendered[15][15]==0xffffff); /* Scaled image cannot paint through the rounded corner. */
  assert(rendered[11][40]==0x00ff00); /* Border stays over the child. */
  assert(rendered[40][40]==0xff00ff); /* Interior retains the actual photograph. */
 }
 lv_obj_delete(frame);assert(released==4);
 lv_display_delete(display);lv_deinit();return 0;
}
