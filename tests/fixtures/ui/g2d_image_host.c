/* Validate real G2D commands against padded source/destination rows. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <lvgl/lvgl.h>
#define G2D_CMD_BITBLT_H 1
#define G2D_CMD_BLD_H 2
#define G2D_BLT_NONE_H 0
#define G2D_BLD_SRCOVER 0
#define G2D_PIXEL_ALPHA 0
#define G2D_GLOBAL_ALPHA 1
#define G2D_MIXER_ALPHA 2
struct image {unsigned alpha,mode,color,format;int width,height;unsigned align[3];uintptr_t laddr[3];unsigned use_phy_addr;struct {int x,y,w,h;} clip_rect;};
typedef struct {unsigned flag_h;struct image src_image_h,dst_image_h;} g2d_blt_h;
typedef struct {unsigned bld_cmd;struct image src_image[2],dst_image;} g2d_bld;
static lv_draw_buf_t *src,*dst;
static unsigned commands;
static unsigned g2d_get_px_format(lv_color_format_t cf){return lv_color_format_get_size(cf);}
static void hal_dcache_clean_invalidate(unsigned long address,unsigned long length)
{
 lv_draw_buf_t *b=address>=(uintptr_t)src->data&&address<(uintptr_t)src->data+src->data_size?src:dst;
 assert(address>=(uintptr_t)b->data&&address+length<=(uintptr_t)b->data+b->data_size);
}
static void image_valid(const struct image *i)
{
 lv_draw_buf_t *b=i->laddr[0]==(uintptr_t)src->data?src:dst;
 assert(i->laddr[0]==(uintptr_t)b->data);
 assert((unsigned)i->width*i->format==b->header.stride);
 assert(i->clip_rect.x>=0&&i->clip_rect.y>=0);
 assert(i->clip_rect.x+i->clip_rect.w<=(int)b->header.w);
 assert(i->clip_rect.y+i->clip_rect.h<=(int)b->header.h);
}
static int sunxi_g2d_control(int cmd,void *p)
{
 if(cmd==G2D_CMD_BITBLT_H){g2d_blt_h *b=p;image_valid(&b->src_image_h);image_valid(&b->dst_image_h);}
 else {g2d_bld *b=p;image_valid(&b->src_image[1]);image_valid(&b->src_image[0]);image_valid(&b->dst_image);}
 commands++;return 0;
}
#include "g2d_image_production.inc"
int main(void)
{
 lv_init();
 const lv_color_format_t formats[]={LV_COLOR_FORMAT_RGB565,LV_COLOR_FORMAT_ARGB8888};
 for(unsigned f=0;f<2;f++){
  /* Uneven widths force padding, and clipping reaches the last allocated row. */
  src=lv_draw_buf_create(13,7,formats[f],32+(f?32:0));
  dst=lv_draw_buf_create(19,9,LV_COLOR_FORMAT_RGB565,48);assert(src&&dst);
  lv_area_t s={3,4,12,6},d={9,6,18,8},whole={0,0,18,8};
  lv_draw_image_dsc_t desc;lv_draw_image_dsc_init(&desc);
  _g2d_blit_no_alpha(dst,&d,src,&s,&desc,&whole,&whole);
  _g2d_blit(dst,&d,src,&s,180);
  lv_draw_buf_destroy(src);lv_draw_buf_destroy(dst);
 }
 assert(commands==4);lv_deinit();puts("PASS: padded RGB565/ARGB8888 pitches and last-row cache bounds");return 0;
}
