/* Actual native draw tasks: SVG gradient resizing/clipping and opacity-safe
 * asymmetric corners. No screenshot or mocked paint implementation. */
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include "buffer_ui_decor.h"
static unsigned char rgb[100*80*3];
static void flush(lv_display_t *display,const lv_area_t *area,uint8_t *data)
{
 unsigned stride=lv_draw_buf_width_to_stride(lv_area_get_width(area),lv_display_get_color_format(display));
 for(int y=area->y1;y<=area->y2;y++)for(int x=area->x1;x<=area->x2;x++){
  unsigned char *out=rgb+3*(y*100+x);
#if LV_COLOR_DEPTH == 16
  uint16_t v;memcpy(&v,data+(y-area->y1)*stride+2*(x-area->x1),2);out[0]=((v>>11)&31)*255/31;out[1]=((v>>5)&63)*255/63;out[2]=(v&31)*255/31;
#else
  unsigned char *in=data+(y-area->y1)*stride+4*(x-area->x1);out[0]=in[2];out[1]=in[1];out[2]=in[0];
#endif
 }
 lv_display_flush_ready(display);
}
static void paint(lv_display_t *display){lv_obj_update_layout(lv_screen_active());lv_tick_inc(16);lv_refr_now(display);}
static unsigned char *pixel(int x,int y){return rgb+3*(y*100+x);}
static bool white(int x,int y){unsigned char *p=pixel(x,y);return p[0]>250&&p[1]>250&&p[2]>250;}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(100,80);lv_display_set_dpi(display,160);static uint32_t buffer[100*16];
 lv_display_set_buffers(display,buffer,NULL,sizeof(buffer),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 lv_obj_set_style_bg_color(lv_screen_active(),lv_color_white(),0);
 lv_obj_t *surface=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(surface);lv_obj_set_pos(surface,5,5);lv_obj_set_size(surface,80,60);lv_obj_set_style_bg_color(surface,lv_color_white(),0);lv_obj_set_style_bg_opa(surface,LV_OPA_COVER,0);
 buffer_ui_surface_decor(surface,BUFFER_DECOR_LISTENING,5);paint(display);
 assert(white(10,6)&&white(90,60));unsigned char *bottom=pixel(40,60);assert(bottom[1]>bottom[0]+10);
 unsigned events=lv_obj_get_event_count(surface);for(unsigned i=0;i<20;i++)buffer_ui_surface_decor(surface,BUFFER_DECOR_LISTENING,5);assert(lv_obj_get_event_count(surface)==events);
 buffer_ui_surface_decor(surface,BUFFER_DECOR_NONE,0);paint(display);assert(white(40,60));
 lv_obj_delete(surface);
 for(unsigned corner=0;corner<3;corner++){
  lv_obj_t *bubble=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(bubble);lv_obj_set_pos(bubble,5,5);lv_obj_set_size(bubble,40,40);lv_obj_set_style_radius(bubble,20,0);lv_obj_set_style_bg_color(bubble,lv_color_hex(0x396552),0);lv_obj_set_style_bg_opa(bubble,LV_OPA_COVER,0);
  buffer_ui_bubble_corner(bubble,corner);paint(display);
  int x=corner==BUFFER_BUBBLE_REQUEST?43:6,y=corner==BUFFER_BUBBLE_CONTINUATION?6:43;
  assert(!white(x,y));
  lv_obj_set_style_opa(bubble,128,0);paint(display);
  unsigned char *edge=pixel(x,y),*center=pixel(25,25),*overlap=pixel(corner==BUFFER_BUBBLE_REQUEST?35:14,corner==BUFFER_BUBBLE_CONTINUATION?14:35);
  for(unsigned c=0;c<3;c++){assert(abs((int)edge[c]-center[c])<=8);assert(abs((int)overlap[c]-center[c])<=8);}
  lv_obj_delete(bubble);
 }
 lv_display_delete(display);lv_deinit();return 0;
}
