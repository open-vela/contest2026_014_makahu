/* Resolve the owning control's on-color in the draw descriptor. LVGL can
 * change combined states without delivering STYLE_CHANGED to children (for
 * example a pressed shadow), so draw-time resolution covers every state and
 * needs no timer, style mutation, or callback retaining a deleted child. */
static void buffer_ui_icon_draw(lv_event_t *event)
{
 lv_draw_image_dsc_t *draw=lv_draw_task_get_image_dsc(lv_event_get_draw_task(event));
 if(!draw)return;
 lv_obj_t *image=lv_event_get_target(event);
 draw->recolor=lv_obj_get_style_text_color(lv_obj_get_parent(image),0);
 draw->recolor_opa=LV_OPA_COVER;
}
/* Native masks are selected after integer dp layout has resolved. */
static inline void buffer_ui_icon_set_src(lv_obj_t *image,const lv_image_dsc_t *source)
{
 int pixels=lv_obj_get_width(image),height=lv_obj_get_height(image);
 if(height<pixels)pixels=height;
 if(pixels<1)pixels=buffer_ui_dp(BUFFER_GLYPH_DP);
 const lv_image_dsc_t *native=buffer_ui_icon_source(source,pixels);
 if(lv_image_get_src(image)!=native)lv_image_set_src(image,native);
 lv_image_set_scale(image,LV_SCALE_NONE);
 lv_image_set_inner_align(image,native&&native->header.w==(unsigned)pixels?LV_IMAGE_ALIGN_CENTER:LV_IMAGE_ALIGN_STRETCH);
}
static void buffer_ui_icon_size_changed(lv_event_t *event)
{
 lv_obj_t *image=lv_event_get_target(event);
 buffer_ui_icon_set_src(image,lv_image_get_src(image));
}
static inline lv_obj_t *buffer_ui_icon_create(lv_obj_t *parent,const lv_image_dsc_t *source)
{
 lv_obj_t *image=lv_image_create(parent);lv_image_set_src(image,source);
 lv_image_set_inner_align(image,LV_IMAGE_ALIGN_CENTER);
 lv_obj_add_event_cb(image,buffer_ui_icon_size_changed,LV_EVENT_SIZE_CHANGED,NULL);
 lv_obj_set_size(image,buffer_ui_dp(BUFFER_GLYPH_DP),buffer_ui_dp(BUFFER_GLYPH_DP));
 buffer_ui_icon_set_src(image,source);
 lv_obj_remove_flag(image,LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_add_flag(image,LV_OBJ_FLAG_SEND_DRAW_TASK_EVENTS);
 lv_obj_add_event_cb(image,buffer_ui_icon_draw,LV_EVENT_DRAW_TASK_ADDED,NULL);
 return image;
}
static inline lv_obj_t *buffer_ui_icon_button(lv_obj_t *button,const lv_image_dsc_t *source)
{
 lv_obj_t *label=lv_obj_get_child(button,0);
 if(label && lv_obj_check_type(label,&lv_label_class)) lv_obj_add_flag(label,LV_OBJ_FLAG_HIDDEN);
 /* A hidden text caption must not retain a large-text minimum width. */
 lv_obj_set_style_min_width(button,buffer_ui_dp(48),0);
 lv_obj_set_style_min_height(button,buffer_ui_dp(48),0);
 lv_obj_set_style_pad_all(button,0,0);
 lv_obj_t *image=buffer_ui_icon_create(button,source);lv_obj_center(image);return image;
}
