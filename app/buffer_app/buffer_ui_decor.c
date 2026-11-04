/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_decor.h"
#include "buffer_ui_theme.h"
#include <stdint.h>
#include "buffer_ui_decor_assets.inc"
static void surface_draw(lv_event_t *event)
{
 lv_obj_t *object=lv_event_get_current_target(event);uintptr_t value=(uintptr_t)lv_event_get_user_data(event);
 unsigned mode=value&3u;int top=(int)(value>>2);lv_area_t area;lv_obj_get_coords(object,&area);
 int width=lv_area_get_width(&area),height=lv_area_get_height(&area)-top;if(width<1||height<1)return;
 lv_draw_image_dsc_t draw;lv_draw_image_dsc_init(&draw);draw.base.layer=lv_event_get_layer(event);lv_obj_init_draw_image_dsc(object,LV_PART_MAIN,&draw);draw.recolor_opa=LV_OPA_TRANSP;draw.src=mode==BUFFER_DECOR_LISTENING?&decor_listening:&decor_session;
 draw.scale_x=(width*LV_SCALE_NONE+319)/320;draw.scale_y=(height*LV_SCALE_NONE+202)/203;draw.pivot=(lv_point_t){0,0};
 area.y1+=top;area.x2=area.x1+319;area.y2=area.y1+202;
 lv_draw_image(lv_event_get_layer(event),&draw,&area);
}
void buffer_ui_surface_decor(lv_obj_t *object,enum buffer_ui_surface_decor decor,int top)
{
 if(!object||(unsigned)decor>BUFFER_DECOR_LISTENING||top<0)return;
 uintptr_t value=((uintptr_t)top<<2)|(unsigned)decor;bool removed=false;
 for(uint32_t i=0;i<lv_obj_get_event_count(object);i++){
  lv_event_dsc_t *event=lv_obj_get_event_dsc(object,i);
  if(event&&lv_event_dsc_get_cb(event)==surface_draw){if(decor&&(uintptr_t)lv_event_dsc_get_user_data(event)==value)return;lv_obj_remove_event_cb(object,surface_draw);removed=true;break;}
 }
 if(!decor&&!removed)return;
 if(decor)lv_obj_add_event_cb(object,surface_draw,LV_EVENT_DRAW_MAIN,(void *)value);
 lv_obj_invalidate(object);
}
static void bubble_draw(lv_event_t *event)
{
 lv_obj_t *object=lv_event_get_current_target(event);unsigned corner=(unsigned)(uintptr_t)lv_event_get_user_data(event);
 lv_area_t area;lv_obj_get_coords(object,&area);int radius=lv_obj_get_style_radius(object,0);
 if(radius>lv_area_get_width(&area)/2)radius=lv_area_get_width(&area)/2;
 if(radius>lv_area_get_height(&area)/2)radius=lv_area_get_height(&area)/2;
 if(radius<1)return;
 if(corner==BUFFER_BUBBLE_REQUEST)area.x1=area.x2-radius+1;else area.x2=area.x1+radius-1;
 if(corner==BUFFER_BUBBLE_CONTINUATION)area.y2=area.y1+radius-1;else area.y1=area.y2-radius+1;
 lv_draw_rect_dsc_t fill;lv_draw_rect_dsc_init(&fill);fill.base.layer=lv_event_get_layer(event);lv_obj_init_draw_rect_dsc(object,LV_PART_MAIN,&fill);
 lv_draw_image_dsc_t draw;lv_draw_image_dsc_init(&draw);draw.src=corner==BUFFER_BUBBLE_REQUEST?&corner_request:corner==BUFFER_BUBBLE_REPLY?&corner_reply:&corner_continuation;
 draw.opa=fill.bg_opa;draw.recolor=fill.bg_color;draw.recolor_opa=LV_OPA_COVER;draw.scale_x=draw.scale_y=(radius*LV_SCALE_NONE+63)/64;draw.pivot=(lv_point_t){0,0};draw.antialias=true;
 area.x2=area.x1+63;area.y2=area.y1+63;lv_draw_image(lv_event_get_layer(event),&draw,&area);
}
void buffer_ui_bubble_corner(lv_obj_t *object,enum buffer_ui_bubble_corner corner)
{
 if(!object||(unsigned)corner>BUFFER_BUBBLE_CONTINUATION)return;
 lv_obj_remove_event_cb(object,bubble_draw);lv_obj_add_event_cb(object,bubble_draw,LV_EVENT_DRAW_MAIN,(void *)(uintptr_t)corner);
}
