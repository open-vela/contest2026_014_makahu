/* Real LVGL mixed footprints, stable anchors and actual pointer scrolling. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "buffer_ui_desktop.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_motion.h"
static lv_point_t pointer;
static lv_indev_state_t pointer_state;
static lv_point_t probe;
static uint32_t probe_pixel;
static unsigned clicks;
static void flush(lv_display_t *display,const lv_area_t *area,uint8_t *pixels)
{
 if(probe.x>=area->x1&&probe.x<=area->x2&&probe.y>=area->y1&&probe.y<=area->y2){
  unsigned stride=lv_draw_buf_width_to_stride(lv_area_get_width(area),LV_COLOR_FORMAT_XRGB8888);
  memcpy(&probe_pixel,pixels+(probe.y-area->y1)*stride+4*(probe.x-area->x1),4);
 }
 lv_display_flush_ready(display);
}
static void clicked(lv_event_t *event){(void)event;clicks++;}
static void read_pointer(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;data->point=pointer;data->state=pointer_state;}
static void pump(unsigned ms)
{for(unsigned i=0;i<ms;i+=16){lv_tick_inc(16);lv_timer_handler();lv_obj_update_layout(lv_screen_active());}}
static lv_obj_t *plain(lv_obj_t *parent)
{lv_obj_t *obj=lv_obj_create(parent);lv_obj_remove_style_all(obj);lv_obj_remove_flag(obj,LV_OBJ_FLAG_SCROLLABLE);return obj;}
static void content_padding(lv_display_t *display,bool horizontal)
{
 lv_display_set_resolution(display,horizontal?320:240,horizontal?240:320);
 lv_obj_t *view=plain(lv_screen_active());lv_obj_set_size(view,LV_PCT(100),LV_PCT(100));
 struct buffer_ui_desktop *desktop=buffer_ui_desktop_create(view,view,NULL,NULL);assert(desktop);
 lv_obj_set_style_pad_all(view,10,0);buffer_ui_desktop_strip(desktop,true);
 lv_obj_t *first=plain(view),*middle=plain(view),*last=plain(view);
 lv_obj_set_style_bg_color(middle,lv_color_hex(0x1357bd),0);lv_obj_set_style_bg_opa(middle,LV_OPA_COVER,0);
 lv_obj_add_flag(middle,LV_OBJ_FLAG_CLICKABLE);lv_obj_add_event_cb(middle,clicked,LV_EVENT_CLICKED,NULL);
 assert(!buffer_ui_desktop_panel(desktop,first,"first",192));
 assert(!buffer_ui_desktop_panel(desktop,middle,"middle",192));
 assert(!buffer_ui_desktop_panel(desktop,last,"last",192));pump(32);
 (void)buffer_ui_desktop_reflow(desktop);pump(32);
 assert(buffer_ui_desktop_reveal(desktop,"first"));pump(16);
 lv_area_t bounds;lv_obj_get_coords(first,&bounds);assert(bounds.x1==10&&bounds.y1==10);
 assert(buffer_ui_desktop_reveal(desktop,"last"));pump(16);
 lv_obj_get_coords(last,&bounds);
 assert(horizontal?bounds.x2==309:bounds.y2==309);
 /* A moving tile crosses the resting inset. Both paint and hit testing must
  * reach the display edge, while the first/last resting inset stays intact. */
 if(horizontal)lv_obj_scroll_to_x(view,lv_obj_get_x(middle)+5,LV_ANIM_OFF);
 else lv_obj_scroll_to_y(view,lv_obj_get_y(middle)+5,LV_ANIM_OFF);
 probe=horizontal?(lv_point_t){7,50}:(lv_point_t){50,7};probe_pixel=0;
 lv_obj_invalidate(view);pump(32);lv_refr_now(display);
 if((probe_pixel&0xffffff)!=0x1357bd){lv_obj_get_coords(middle,&bounds);fprintf(stderr,"padding probe: horizontal=%d pixel=%08x tile=%d,%d:%d,%d scroll=%d,%d\n",horizontal,probe_pixel,bounds.x1,bounds.y1,bounds.x2,bounds.y2,lv_obj_get_scroll_x(view),lv_obj_get_scroll_y(view));}
 assert((probe_pixel&0xffffff)==0x1357bd);
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(input,read_pointer);
 pointer=probe;unsigned before=clicks;pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(clicks==before+1);
 lv_indev_delete(input);lv_obj_delete(view);
}
static void segment_shapes(lv_display_t *display)
{
 lv_display_set_resolution(display,320,240);
 lv_obj_t *view=plain(lv_screen_active());lv_obj_set_size(view,LV_PCT(100),LV_PCT(100));
 lv_obj_set_style_bg_color(view,lv_color_white(),0);lv_obj_set_style_bg_opa(view,LV_OPA_COVER,0);
 lv_obj_t *button=plain(view);buffer_ui_button_style(button,false);lv_obj_set_pos(button,20,20);lv_obj_set_size(button,100,100);
 lv_obj_set_style_bg_color(button,lv_color_hex(0x1357bd),0);lv_obj_set_style_bg_opa(button,LV_OPA_COVER,0);
 lv_obj_set_style_bg_color(button,lv_color_hex(0x1357bd),LV_STATE_CHECKED);
 lv_obj_set_style_bg_color(button,lv_color_hex(0x1357bd),LV_STATE_CHECKED|LV_STATE_PRESSED);
 buffer_ui_motion_init();buffer_ui_motion_policy(true,false);
 for(unsigned row=0;row<3;row++){
  buffer_ui_segment_shape(button,row==0,row==2);
  lv_obj_send_event(button,LV_EVENT_PRESSED,NULL);pump(32);
  lv_obj_send_event(button,LV_EVENT_RELEASED,NULL);pump(32);
  assert(lv_obj_get_style_radius(button,0)==3);
  for(unsigned state=0;state<3;state++){
   if(state)lv_obj_add_state(button,LV_STATE_CHECKED);else lv_obj_remove_state(button,LV_STATE_CHECKED);
   if(state==2)lv_obj_add_state(button,LV_STATE_PRESSED);else lv_obj_remove_state(button,LV_STATE_PRESSED);
   for(unsigned bottom=0;bottom<2;bottom++){
   probe=(lv_point_t){22,bottom?117:22};probe_pixel=0;lv_obj_invalidate(view);pump(32);lv_refr_now(display);
   bool rounded=bottom?row==2:row==0;
   assert((probe_pixel&0xffffff)==(rounded?0xffffff:0x1357bd));
   }
  }
  lv_obj_remove_state(button,LV_STATE_CHECKED|LV_STATE_PRESSED);
 }
 buffer_ui_motion_shutdown();lv_obj_delete(view);
}
static void nonoverlap(lv_obj_t **tiles,unsigned count,lv_obj_t *viewport,bool horizontal)
{
 int last_primary=-1,last_cross=-1;
 for(uint32_t i=0;i<lv_obj_get_child_count(viewport);i++){
  lv_obj_t *tile=lv_obj_get_child(viewport,i);if(lv_obj_has_flag(tile,LV_OBJ_FLAG_HIDDEN))continue;
  int primary=horizontal?lv_obj_get_x(tile):lv_obj_get_y(tile),cross=horizontal?lv_obj_get_y(tile):lv_obj_get_x(tile);
  assert(primary>last_primary||(primary==last_primary&&cross>=last_cross));last_primary=primary;last_cross=cross;
 }
 for(unsigned i=0;i<count;i++)if(!lv_obj_has_flag(tiles[i],LV_OBJ_FLAG_HIDDEN)){
  int x=lv_obj_get_x(tiles[i]),y=lv_obj_get_y(tiles[i]),w=lv_obj_get_width(tiles[i]),h=lv_obj_get_height(tiles[i]);
  assert(w>0&&h>0);assert(x>=0&&y>=0);
  assert(horizontal?y+h<=lv_obj_get_content_height(viewport):x+w<=lv_obj_get_content_width(viewport));
  for(unsigned j=i+1;j<count;j++)if(!lv_obj_has_flag(tiles[j],LV_OBJ_FLAG_HIDDEN)){
   int xx=lv_obj_get_x(tiles[j]),yy=lv_obj_get_y(tiles[j]),ww=lv_obj_get_width(tiles[j]),hh=lv_obj_get_height(tiles[j]);
   assert(x+w<=xx||xx+ww<=x||y+h<=yy||yy+hh<=y);
  }
 }
}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(800,480);lv_display_set_dpi(display,160);
 static uint32_t pixels[800*16];lv_display_set_color_format(display,LV_COLOR_FORMAT_XRGB8888);
 lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 lv_obj_t *view=plain(lv_screen_active());lv_obj_set_size(view,LV_PCT(100),LV_PCT(100));pump(16);
 struct buffer_ui_desktop *desktop=buffer_ui_desktop_create(view,view,NULL,NULL);assert(desktop);
 lv_obj_t *tiles[24];for(unsigned i=0;i<24;i++)tiles[i]=plain(view);
 lv_obj_add_flag(tiles[0],LV_OBJ_FLAG_HIDDEN);
 assert(!buffer_ui_desktop_bind(desktop,tiles[0],"inserted",2,1));
 assert(!buffer_ui_desktop_bind(desktop,tiles[1],"a",2,1));
 assert(!buffer_ui_desktop_bind(desktop,tiles[2],"b",1,1));
 assert(!buffer_ui_desktop_bind(desktop,tiles[3],"c",1,2));
 assert(!buffer_ui_desktop_bind(desktop,tiles[4],"d",2,2));
 for(unsigned i=5;i<24;i++){char key[32];snprintf(key,sizeof(key),"item-%u",i);assert(!buffer_ui_desktop_bind(desktop,tiles[i],key,1,1));}
 assert(buffer_ui_desktop_bind(desktop,tiles[2],"a",1,1)==-EEXIST);
 assert(buffer_ui_desktop_reflow(desktop));pump(32);nonoverlap(tiles,24,view,true);
 assert(lv_obj_get_width(tiles[1])>lv_obj_get_width(tiles[2]));
 assert(lv_obj_get_height(tiles[3])>lv_obj_get_height(tiles[2]));
 assert(buffer_ui_desktop_reveal(desktop,"c"));pump(16);
 int before=lv_obj_get_x(tiles[3])-lv_obj_get_scroll_x(view);
 lv_obj_remove_flag(tiles[0],LV_OBJ_FLAG_HIDDEN);assert(buffer_ui_desktop_reflow(desktop));pump(16);
 assert(!strcmp(buffer_ui_desktop_anchor(desktop),"c"));
 assert(lv_obj_get_x(tiles[3])-lv_obj_get_scroll_x(view)==before);
 lv_display_set_resolution(display,240,320);pump(64);
 assert(lv_obj_get_scroll_dir(view)==LV_DIR_VER);assert(!strcmp(buffer_ui_desktop_anchor(desktop),"c"));
 assert(lv_obj_get_y(tiles[3])==lv_obj_get_scroll_y(view));nonoverlap(tiles,24,view,false);
 lv_obj_add_flag(tiles[3],LV_OBJ_FLAG_HIDDEN);assert(buffer_ui_desktop_reflow(desktop));pump(16);
 assert(strcmp(buffer_ui_desktop_anchor(desktop),"c")&&strcmp(buffer_ui_desktop_anchor(desktop),"inserted"));
 assert(buffer_ui_desktop_reveal(desktop,"d"));
 assert(!buffer_ui_desktop_bind(desktop,tiles[4],"replacement",2,2));assert(buffer_ui_desktop_reflow(desktop));
 assert(strcmp(buffer_ui_desktop_anchor(desktop),"replacement"));
 lv_display_set_resolution(display,320,240);pump(64);
 assert(lv_obj_get_scroll_dir(view)==LV_DIR_HOR);assert(buffer_ui_desktop_reveal(desktop,"inserted"));
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(input,read_pointer);
 pointer=(lv_point_t){280,100};pointer_state=LV_INDEV_STATE_PRESSED;pump(48);
 for(unsigned i=1;i<=5;i++){pointer.x=280-(int)i*40;pump(32);}
 pointer_state=LV_INDEV_STATE_RELEASED;pump(1600);
 assert(lv_obj_get_scroll_x(view)>0);int position=lv_obj_get_scroll_x(view);
 int end=position+lv_obj_get_scroll_right(view);bool snapped=abs(position-end)<=1;
 for(unsigned i=0;i<24;i++)if(!lv_obj_has_flag(tiles[i],LV_OBJ_FLAG_HIDDEN)&&abs(lv_obj_get_x(tiles[i])-position)<=1)snapped=true;
 assert(snapped);
 lv_indev_delete(input);lv_obj_delete(tiles[10]);tiles[10]=NULL;
 (void)buffer_ui_desktop_reflow(desktop);
 /* A viewport owns manager teardown, including callbacks on remaining tiles. */
 lv_obj_delete(view);content_padding(display,true);content_padding(display,false);
 segment_shapes(display);
 lv_display_delete(display);lv_deinit();
 puts("PASS: mixed grid, stable anchors, pointer snap, content padding preserves edge painting/input and resting insets");
}
