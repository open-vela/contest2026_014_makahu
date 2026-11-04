/* Exercise actual SDK G2D command generation with a bounded DMA emulator. */
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <lvgl/lvgl.h>
#define G2D_CMD_FILLRECT_H 1
#define G2D_PIXEL_ALPHA 0
#define DRAW_UNIT_ID_G2D 5
#define SUNXI_G2D_LIMIT_FILL 0
#define SUNXI_G2D_LIMIT_OPA_FILL 1
struct image {unsigned alpha,mode,color,format;int width,height;unsigned align[3];uintptr_t laddr[3];unsigned use_phy_addr;struct {int x,y,w,h;} clip_rect;};
typedef struct {struct image dst_image_h;} g2d_fillrect_h;
static uintptr_t allocation_begin,allocation_end;
static unsigned expected_format;
static unsigned g2d_get_px_format(lv_color_format_t cf)
{return cf==LV_COLOR_FORMAT_RGB565?10:cf==LV_COLOR_FORMAT_RGB888?8:cf==LV_COLOR_FORMAT_XRGB8888?1:0;}
static void hal_dcache_clean_invalidate(unsigned long address,unsigned long length)
{assert(address>=allocation_begin&&address+length<=allocation_end);}
static int sunxi_g2d_control(int command,void *payload)
{
 assert(command==G2D_CMD_FILLRECT_H);g2d_fillrect_h *fill=payload;
 struct image *d=&fill->dst_image_h;assert(d->format==expected_format);
 unsigned bytes=d->format==10?2:d->format==8?3:4;
 for(int y=0;y<d->clip_rect.h;y++)for(int x=0;x<d->clip_rect.w;x++){
  uintptr_t at=d->laddr[0]+((uintptr_t)y*d->width+x)*bytes;
  assert(at>=allocation_begin&&at+bytes<=allocation_end);
  memset((void *)at,0x5a,bytes);
 }
 return 0;
}
static int sunxifb_g2d_get_limit(int kind){return kind==SUNXI_G2D_LIMIT_FILL?2073600:12100;}
#define _g2d_cf_supported(cf) ((void)(cf),true)
#define _g2d_draw_img_supported(dsc,size,cf) ((void)(dsc),(void)(size),(void)(cf),true)
#include "g2d_fill_production.inc"
int main(void)
{
 lv_init();
 const lv_color_format_t formats[]={LV_COLOR_FORMAT_RGB565,LV_COLOR_FORMAT_RGB888,LV_COLOR_FORMAT_ARGB8888,LV_COLOR_FORMAT_XRGB8888};
 for(unsigned f=0;f<4;f++){
  unsigned bytes=lv_color_format_get_size(formats[f]),stride=(320*bytes+15)&~15u,size=stride*240;
  uint8_t *memory=lv_malloc(size+32);assert(memory);memset(memory,0xcc,size+32);
  uint8_t *pixels=memory+16;allocation_begin=(uintptr_t)pixels;allocation_end=allocation_begin+size;
  expected_format=g2d_get_px_format(formats[f]);
  lv_area_t whole={0,0,319,239},corner={311,235,319,239};
  lv_draw_fill_dsc_t dsc;lv_draw_fill_dsc_init(&dsc);dsc.color=lv_color_hex(0xf7faf3);dsc.opa=255;
  _g2d_fill(pixels,&whole,stride,&whole,formats[f],&dsc);
  _g2d_fill(pixels,&corner,stride,&whole,formats[f],&dsc);
  for(unsigned i=0;i<16;i++)assert(memory[i]==0xcc&&memory[size+16+i]==0xcc);
  lv_free(memory);
 }
 lv_layer_t layer={0};layer.color_format=LV_COLOR_FORMAT_RGB565;
 lv_draw_fill_dsc_t translucent;lv_draw_fill_dsc_init(&translucent);translucent.opa=180;translucent.base.layer=&layer;
 lv_draw_task_t task={0};task.type=LV_DRAW_TASK_TYPE_FILL;task.draw_dsc=&translucent;
 task.area=(lv_area_t){0,0,307,179};task.clip_area=task.area;task.preference_score=100;
 assert(_g2d_evaluate(NULL,&task)==0); /* Same animated calendar rectangle as S1. */
 translucent.opa=255;task.area=(lv_area_t){0,0,2047,2047};task.clip_area=task.area;
 assert(_g2d_evaluate(NULL,&task)==1);
 layer.color_format=LV_COLOR_FORMAT_A8;task.preference_score=100;assert(_g2d_evaluate(NULL,&task)==0);
 puts("PASS: G2D RGB565/RGB888/ARGB8888 formats, allocation guards, corner cache span, translucent fallback");
 lv_deinit();return 0;
}
