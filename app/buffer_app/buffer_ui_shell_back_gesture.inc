/* SPDX-License-Identifier: Apache-2.0
 * Shell-owned edge feedback, following AOSP BackPanel/EdgePanelParams:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/navigationbar/gestural/
 * Native LVGL rectangles and pinned Material Symbols Rounded chevrons.
 * Values are dp; no window screenshot, full-screen draw layer or idle polling.
 */
#include <lvgl/src/indev/lv_indev_private.h>
struct edge_device {
 struct edge_device *next;
 lv_indev_t *input;
 lv_indev_read_preprocess_cb_t previous;
 bool down;
 bool pull_owned;

 struct surface *pull_surface;
 lv_obj_t *pull_target;
 lv_point_t pull_start;
};
enum edge_property {EDGE_WIDTH,EDGE_HEIGHT,EDGE_MARGIN,EDGE_Y,EDGE_NEAR,EDGE_FAR,EDGE_ALPHA,EDGE_PROPERTIES};
static struct edge_device *edge_devices;
static lv_indev_t *edge_input;
static lv_obj_t *edge_target,*edge_hint,*edge_glyph;
static lv_timer_t *edge_frame;
static struct buffer_spring edge_springs[EDGE_PROPERTIES];
static lv_point_t edge_start;
static struct surface *edge_surface;
static int edge_side,edge_hint_side;
static bool edge_owned,edge_armed,edge_cancelled,edge_exiting;
static uint32_t edge_last_tick;

static lv_point_t edge_display_point(lv_indev_t *input,lv_point_t point)
{
 /* Preprocessing runs before LVGL's pointer rotation. Use the same mapping
  * for hit bands and distance, without rotating data passed to LVGL twice. */
 lv_display_t *display=lv_indev_get_display(input);
 lv_display_rotation_t rotation=lv_display_get_rotation(display);
 int w=lv_display_get_original_horizontal_resolution(display),h=lv_display_get_original_vertical_resolution(display);
 if(rotation==LV_DISPLAY_ROTATION_180||rotation==LV_DISPLAY_ROTATION_270){point.x=w-point.x-1;point.y=h-point.y-1;}
 if(rotation==LV_DISPLAY_ROTATION_90||rotation==LV_DISPLAY_ROTATION_270){int y=point.y;point.y=point.x;point.x=h-y-1;}
 return point;
}

static void edge_hint_draw(lv_event_t *event)
{
 if(lv_event_get_code(event)==LV_EVENT_DELETE){edge_hint=edge_glyph=NULL;return;}
 lv_layer_t *layer=lv_event_get_layer(event);lv_area_t area;lv_obj_get_coords(edge_hint,&area);
 lv_area_t saved=layer->_clip_area;int32_t middle=(area.x1+area.x2)/2;
 lv_draw_rect_dsc_t draw;lv_draw_rect_dsc_init(&draw);
 draw.bg_color=lv_color_hex(BUFFER_PRIMARY_CONTAINER);
 draw.bg_opa=(lv_opa_t)LV_CLAMP(0,(int)edge_springs[EDGE_ALPHA].value,255);
 /* Split native fills vertically to give the screen-facing and far corners
  * independent radii, matching Android's deformable background. */
 draw.radius=buffer_ui_dp(LV_MAX(0,(int)edge_springs[edge_hint_side>0?EDGE_NEAR:EDGE_FAR].value));
 layer->_clip_area.x2=LV_MIN(saved.x2,middle);
 if(layer->_clip_area.x1<=layer->_clip_area.x2)lv_draw_rect(layer,&draw,&area);
 layer->_clip_area=saved;layer->_clip_area.x1=LV_MAX(saved.x1,middle+1);
 draw.radius=buffer_ui_dp(LV_MAX(0,(int)edge_springs[edge_hint_side>0?EDGE_FAR:EDGE_NEAR].value));
 if(layer->_clip_area.x1<=layer->_clip_area.x2)lv_draw_rect(layer,&draw,&area);
 layer->_clip_area=saved;
}
static void edge_hint_apply(void)
{
 if(!edge_hint||!shell_root)return;
 int w=buffer_ui_dp(LV_MAX(0,(int)edge_springs[EDGE_WIDTH].value));
 int h=buffer_ui_dp(LV_MAX(0,(int)edge_springs[EDGE_HEIGHT].value));
 int margin=buffer_ui_dp(LV_MAX(0,(int)edge_springs[EDGE_MARGIN].value));
 int available=lv_obj_get_width(shell_root);
 if(w>available)w=available;
 lv_obj_set_size(edge_hint,w,h);
 lv_obj_set_pos(edge_hint,edge_hint_side>0?margin:available-margin-w,(int)edge_springs[EDGE_Y].value-h/2);
 lv_obj_set_size(edge_glyph,buffer_ui_dp(24),buffer_ui_dp(24));lv_obj_center(edge_glyph);
 lv_obj_set_style_image_opa(edge_glyph,LV_CLAMP(0,(int)edge_springs[EDGE_ALPHA].value,255),0);
 lv_obj_invalidate(edge_hint);
}
static void edge_hint_frame(lv_timer_t *timer)
{
 (void)timer;uint32_t now=lv_tick_get(),elapsed=now-edge_last_tick;edge_last_tick=now;
 bool busy=false;
 for(unsigned i=0;i<EDGE_PROPERTIES;i++)
  busy|=buffer_spring_step(&edge_springs[i],i==EDGE_ALPHA?BUFFER_EF:BUFFER_SF,elapsed,buffer_ui_motion_reduced());
 edge_hint_apply();
 if(!busy){
  lv_timer_pause(edge_frame);
  if(edge_exiting&&edge_hint)lv_obj_add_flag(edge_hint,LV_OBJ_FLAG_HIDDEN);
 }
}
static bool edge_hint_create(void)
{
 if(edge_hint)return true;
 edge_hint=lv_obj_create(shell_root);if(!edge_hint)return false;
 lv_obj_remove_style_all(edge_hint);
 lv_obj_add_flag(edge_hint,LV_OBJ_FLAG_FLOATING|LV_OBJ_FLAG_HIDDEN);
 lv_obj_remove_flag(edge_hint,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_CLICK_FOCUSABLE|LV_OBJ_FLAG_GESTURE_BUBBLE);
 lv_obj_set_style_text_color(edge_hint,lv_color_hex(BUFFER_ACCENT),0);
 lv_obj_add_event_cb(edge_hint,edge_hint_draw,LV_EVENT_DRAW_MAIN,NULL);
 lv_obj_add_event_cb(edge_hint,edge_hint_draw,LV_EVENT_DELETE,NULL);
 edge_glyph=buffer_ui_icon_create(edge_hint,&buffer_previous_icon);
 edge_frame=lv_timer_create(edge_hint_frame,16,NULL);
 if(!edge_glyph||!edge_frame){
  if(edge_frame)lv_timer_delete(edge_frame);
  edge_frame=NULL;lv_obj_delete(edge_hint);return false;
 }
 lv_timer_pause(edge_frame);return true;
}
static void edge_hint_targets(float width,float height,float margin,float y,float near,float far,float alpha)
{
 float values[]={width,height,margin,y,near,far,alpha};
 for(unsigned i=0;i<EDGE_PROPERTIES;i++)buffer_spring_target(&edge_springs[i],values[i]);
 if(lv_timer_get_paused(edge_frame))edge_last_tick=lv_tick_get();
 lv_timer_resume(edge_frame);
 if(buffer_ui_motion_reduced())edge_hint_frame(edge_frame);
}
static void edge_hint_finish(bool commit)
{
 if(!edge_hint||lv_obj_has_flag(edge_hint,LV_OBJ_FLAG_HIDDEN))return;
 edge_exiting=true;
 edge_hint_targets(commit?edge_springs[EDGE_WIDTH].value*.85f:0,
                   edge_springs[EDGE_HEIGHT].value*(commit?.85f:1),
                   commit?edge_springs[EDGE_MARGIN].value+4:4,
                   edge_springs[EDGE_Y].value,commit?24:6,commit?24:6,0);
}
static void edge_invalidate(void)
{
 if(!edge_input)return;
 edge_cancelled=true;edge_armed=false;edge_hint_finish(false);
 if(!edge_owned){edge_input=NULL;edge_target=NULL;edge_surface=NULL;}
}
static void edge_hint_update(lv_point_t point)
{
 if(!edge_hint_create())return;
 float dp=(float)buffer_ui_dp(32);if(dp<1)dp=1;
 float distance=(float)((point.x-edge_start.x)*edge_side),progress=LV_CLAMP(0,distance/dp,1);
 float stretch=LV_CLAMP(0,(distance-dp)/dp,1);
 lv_area_t bounds;lv_obj_get_coords(shell_root,&bounds);
 int half=buffer_ui_dp(24),height=lv_area_get_height(&bounds);
 int offset=LV_MIN(buffer_ui_dp(64),height/4);
 float center=edge_start.y-bounds.y1-offset+(point.y-edge_start.y)*.2f;
 center=LV_CLAMP(LV_MIN(buffer_ui_dp(64),height/2),center,LV_MAX(half,height-half));
 if(lv_obj_has_flag(edge_hint,LV_OBJ_FLAG_HIDDEN)){
  memset(edge_springs,0,sizeof(edge_springs));edge_springs[EDGE_HEIGHT].value=48;
  edge_springs[EDGE_Y].value=center;
  edge_hint_side=edge_side;edge_exiting=false;
  /* AOSP mirrors the panel, but keeps both arrows in the same reading
   * direction. Only RTL changes the semantic Back arrow direction. */
  buffer_ui_icon_set_src(edge_glyph,lv_obj_get_style_base_dir(shell_root,0)==LV_BASE_DIR_RTL?&buffer_next_icon:&buffer_previous_icon);
  lv_obj_remove_flag(edge_hint,LV_OBJ_FLAG_HIDDEN);lv_obj_move_foreground(edge_hint);
 }
 edge_hint_targets(edge_armed?48+stretch*12:51*progress,edge_armed?48:46,
                   edge_armed?14+stretch*4:4,center,
                   edge_armed?24:6+progress*10,edge_armed?24:6+progress*14,255*progress);
}
static bool overlay_pull(struct edge_device *device,lv_indev_data_t *data,lv_point_t point,bool was_down)
{
 if(device->pull_owned){
  device->input->pointer.last_raw_point=data->point;
  struct surface *surface=device->pull_surface;
  if(surface&&surface==overlay){
   int dy=point.y-device->pull_start.y;
   if(device->down)lv_obj_set_style_translate_y(surface->content,LV_CLAMP(-lv_obj_get_height(surface->content),dy,0),0);
   else overlay_settle(surface,dy>-buffer_ui_dp(32));
  }
  if(!device->down){device->pull_owned=false;device->pull_surface=NULL;}
  return true;
 }
 if(device->down&&!was_down){
  device->pull_surface=NULL;device->pull_target=NULL;
  if(overlay&&!capture_expanded&&focus_shown(shell_root)){
   lv_area_t area;lv_obj_get_coords(overlay->content,&area);
   if(_lv_area_is_point_on(&area,&point,0)){device->pull_surface=overlay;device->pull_start=point;}
  }
 }
 if(!device->pull_surface)return false;
 if(!device->down||device->pull_surface!=overlay||capture_expanded||edge_owned){device->pull_surface=NULL;return false;}
 int dx=point.x-device->pull_start.x,dy=point.y-device->pull_start.y;
 if(dy<=-buffer_ui_dp(8)&&-dy>LV_ABS(dx)){
  lv_obj_t *target=device->pull_target;device->pull_target=NULL;device->pull_owned=true;
  if(edge_input==device->input)edge_invalidate();
  buffer_ui_motion_cancel(overlay->content);lv_anim_delete(overlay->content,NULL);
  if(target&&lv_obj_is_valid(target))lv_obj_send_event(target,LV_EVENT_PRESS_LOST,device->input);
  lv_indev_reset(device->input,NULL);device->input->pointer.last_raw_point=data->point;
  lv_obj_set_style_translate_y(overlay->content,dy,0);return true;
 }
 return false;
}
static bool edge_preprocess(lv_indev_t *input,lv_indev_data_t *data)
{
 struct edge_device *device=edge_devices;
 while(device&&device->input!=input)device=device->next;
 if(!device)return false;
 bool was_down=device->down;device->down=data->state==LV_INDEV_STATE_PRESSED;
 if(device->previous&&device->previous(input,data)){if(edge_input==input)edge_invalidate();return true;}
 lv_point_t point=edge_display_point(input,data->point);
 if(status_drag_preprocess(input,data,point))return true;
 if(overlay_pull(device,data,point,was_down))return true;
 if(device->down&&!was_down){
  if(edge_input||!shell_root||!focus_shown(shell_root)||(!app&&!overlay)||capture_expanded)return false;
  lv_area_t area;lv_obj_get_coords(shell_root,&area);int band=buffer_ui_dp(24);
  if(point.y<area.y1||point.y>area.y2)return false;
  edge_side=point.x>=area.x1&&point.x<area.x1+band?1:
            point.x<=area.x2&&point.x>area.x2-band?-1:0;
  if(edge_side){edge_input=input;edge_start=point;edge_surface=overlay?overlay:app;
   edge_owned=edge_armed=edge_cancelled=false;edge_target=NULL;}
 }
 if(edge_input!=input)return false;
 int dx=(point.x-edge_start.x)*edge_side,dy=LV_ABS(point.y-edge_start.y);
 if(edge_surface!=(overlay?overlay:app)||capture_expanded)edge_invalidate();
 if(edge_input!=input)return false;
 if(!edge_owned&&device->down){
  if(dy>=buffer_ui_dp(8)&&dy>=LV_ABS(dx)){edge_invalidate();return false;}
  if(dx>=buffer_ui_dp(8)&&dx>dy){
   edge_owned=true;
   if(edge_hint)lv_obj_add_flag(edge_hint,LV_OBJ_FLAG_HIDDEN);
   if(edge_target&&lv_obj_is_valid(edge_target))lv_obj_send_event(edge_target,LV_EVENT_PRESS_LOST,input);
   lv_indev_reset(input,NULL);edge_target=NULL;
  }
 }
 if(!edge_owned){if(!device->down)edge_input=NULL;return false;}
 /* NuttX TOUCH_UP and empty polls leave point untouched. indev_read_core
  * seeds it from last_raw_point, normally saved by indev_pointer_proc.
  * We bypass that function while owning the contact, so maintain its raw
  * coordinate cache here; otherwise release jumps back to the edge. */
 input->pointer.last_raw_point=data->point;
 if(!edge_cancelled){
  /* Hysteresis allows a small finger tremor after activation. Moving back
   * below 24dp disarms; a new 32dp crossing arms again. */
  edge_armed=dx>=buffer_ui_dp(edge_armed?24:32)&&dx>dy;
  if(device->down)edge_hint_update(point);
 }
 if(!device->down){
  bool commit=edge_armed&&!edge_cancelled;edge_input=NULL;edge_surface=NULL;edge_target=NULL;
  edge_owned=edge_armed=edge_cancelled=false;edge_hint_finish(commit);
  if(commit)(void)buffer_ui_shell_back();
  if(edge_hint&&!lv_obj_has_flag(edge_hint,LV_OBJ_FLAG_HIDDEN))lv_obj_move_foreground(edge_hint);
 }
 return true;
}
static void edge_event(lv_event_t *event)
{
 struct edge_device *device=lv_event_get_user_data(event);
 if(lv_event_get_code(event)==LV_EVENT_PRESSED){
  if(device->pull_surface){
   device->pull_target=lv_event_get_param(event);
   /* A scrollable child gets the whole contact when it has upward scroll
    * room at press time. Reaching its end cannot dismiss halfway through. */
   for(lv_obj_t *p=device->pull_target;p&&p!=device->pull_surface->content;p=lv_obj_get_parent(p)){
    lv_area_t content;lv_obj_get_content_coords(p,&content);
    if(lv_obj_has_flag(p,LV_OBJ_FLAG_SCROLLABLE)&&lv_obj_get_scroll_bottom(p)>0&&_lv_area_is_point_on(&content,&device->pull_start,0)){device->pull_surface=NULL;break;}
   }
  }
  if(edge_input==device->input&&!edge_owned)edge_target=lv_event_get_param(event);
 }else if(lv_event_get_code(event)==LV_EVENT_DELETE){
  if(edge_input==device->input){edge_invalidate();edge_input=NULL;edge_target=NULL;edge_surface=NULL;}
  struct edge_device **at=&edge_devices;while(*at&&*at!=device)at=&(*at)->next;
  if(*at)*at=device->next;
  lv_free(device);
 }
}
static void edge_register(lv_indev_t *input)
{
 if(lv_indev_get_type(input)!=LV_INDEV_TYPE_POINTER||lv_indev_get_display(input)!=lv_obj_get_display(shell_root))return;
 for(struct edge_device *d=edge_devices;d;d=d->next)if(d->input==input)return;
 struct edge_device *device=lv_malloc(sizeof(*device));if(!device)return;
 *device=(struct edge_device){.next=edge_devices,.input=input,.previous=lv_indev_get_read_preprocess_cb(input)};edge_devices=device;
 lv_indev_set_read_preprocess_cb(input,edge_preprocess);
 lv_indev_add_event_cb(input,edge_event,LV_EVENT_ALL,device);
}
static void edge_shutdown(void)
{
 while(edge_devices){
  struct edge_device *d=edge_devices;edge_devices=d->next;
  if(lv_indev_get_read_preprocess_cb(d->input)==edge_preprocess)lv_indev_set_read_preprocess_cb(d->input,d->previous);
  lv_indev_remove_event_cb_with_user_data(d->input,edge_event,d);lv_free(d);
 }
 if(edge_frame)lv_timer_delete(edge_frame);
 edge_frame=NULL;if(edge_hint)lv_obj_delete(edge_hint);
 edge_input=NULL;edge_target=NULL;edge_surface=NULL;edge_owned=edge_armed=edge_cancelled=edge_exiting=false;
 memset(edge_springs,0,sizeof(edge_springs));
}
