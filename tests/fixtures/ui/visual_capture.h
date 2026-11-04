#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
static unsigned flushes;
static uint8_t screen[1280*800*3];
static void screenshot(const char *page,int w,int h)
{
 const char *dir=getenv("BUFFER_UI_SCREENSHOTS");if(!dir)return;
 char path[512];snprintf(path,sizeof(path),"%s/%s-%dx%d.ppm",dir,page,w,h);
 FILE *f=fopen(path,"wb");assert(f);fprintf(f,"P6\n%d %d\n255\n",w,h);
 fwrite(screen,3,w*h,f);fclose(f);
}
static void flush(lv_display_t *d,const lv_area_t *a,uint8_t *p)
{
 if(getenv("BUFFER_UI_SCREENSHOTS")){
  int w=lv_display_get_horizontal_resolution(d);
  unsigned stride=lv_draw_buf_width_to_stride(lv_area_get_width(a),lv_display_get_color_format(d));
  for(int y=a->y1;y<=a->y2;y++)for(int x=a->x1;x<=a->x2;x++){
   uint8_t *out=screen+3*(y*w+x);
#if LV_COLOR_DEPTH == 16
   uint16_t v;memcpy(&v,p+(y-a->y1)*stride+2*(x-a->x1),2);
   out[0]=((v>>11)&31)*255/31;out[1]=((v>>5)&63)*255/63;out[2]=(v&31)*255/31;
#else
   uint8_t *in=p+(y-a->y1)*stride+4*(x-a->x1);out[0]=in[2];out[1]=in[1];out[2]=in[0];
#endif
  }
 }
 flushes++;lv_display_flush_ready(d);
}
