/* Shell-private keyboard/encoder scopes. App-local hidden routes stay in the
 * app's group; stale input is rejected before LVGL's widget event handler. */
struct focus_device {
 struct focus_device *next;lv_indev_t *input;lv_group_t *original;
 lv_indev_read_preprocess_cb_t previous;
 bool key_down,space_enter;
};
static struct focus_device *focus_devices;
static lv_group_t *focus_base,*focus_original_default;
static bool focus_owned_base,focus_dirty,focus_pending,focus_busy;
static void focus_deferred(void *unused);
static bool focus_group_live(lv_group_t *group)
{for(uint32_t i=0;group&&i<lv_group_get_count();i++)if(lv_group_by_index(i)==group)return true;return false;}
static bool focus_input_live(lv_indev_t *input)
{for(lv_indev_t *i=lv_indev_get_next(NULL);i;i=lv_indev_get_next(i))if(i==input)return true;return false;}
static bool focus_space_action(lv_obj_t *object)
{
 if(!object||!lv_obj_has_flag(object,LV_OBJ_FLAG_CLICKABLE))return false;
#if LV_USE_TEXTAREA
 if(lv_obj_check_type(object,&lv_textarea_class))return false;
#endif
 if(lv_obj_check_type(object,&lv_button_class))return true;
 for(uint32_t i=0;i<lv_obj_get_event_count(object);i++){
  lv_event_dsc_t *event=lv_obj_get_event_dsc(object,i);
  if(event&&event->filter==LV_EVENT_CLICKED)return true;
 }
 return false;
}
/* Normalize before native keypad dispatch, so Space gets the same pressed,
 * repeat, release, cancellation and one-click behavior as Enter. Translating
 * only LV_EVENT_KEY would miss release and bypass native input ownership. */
static bool focus_key_preprocess(lv_indev_t *input,lv_indev_data_t *data)
{
 struct focus_device *device=focus_devices;
 while(device&&device->input!=input)device=device->next;
 if(!device)return false;
 if(device->previous&&device->previous(input,data))return true;
 if(data->state==LV_INDEV_STATE_PRESSED){
  if(!device->key_down){
   lv_group_t *group=lv_indev_get_group(input);
   device->space_enter=data->key==' '&&group&&focus_space_action(lv_group_get_focused(group));
  }
  device->key_down=true;
  if(device->space_enter&&data->key!=' '&&data->key!=LV_KEY_ENTER)device->space_enter=false;
 }else device->key_down=false;
 if(device->space_enter)data->key=LV_KEY_ENTER;
 if(!device->key_down)device->space_enter=false;
 return false;
}
static void focus_input_deleted(lv_event_t *event)
{
 struct focus_device *device=lv_event_get_user_data(event),**at=&focus_devices;
 while(*at&&*at!=device)at=&(*at)->next;
 if(*at)*at=device->next;
 lv_free(device);
}
static bool focus_descendant(lv_obj_t *object,lv_obj_t *root)
{for(lv_obj_t *p=object;p;p=lv_obj_get_parent(p))if(p==root)return true;return false;}
static bool focus_shown(lv_obj_t *object)
{for(lv_obj_t *p=object;p;p=lv_obj_get_parent(p))if(lv_obj_has_flag(p,LV_OBJ_FLAG_HIDDEN))return false;return true;}
static lv_group_t *focus_active_group(void)
{return !shell_root||!focus_shown(shell_root)?focus_base:overlay?overlay->group:app?app->group:focus_base;}
static bool focus_allowed(lv_obj_t *object)
{
 if(!object||!focus_shown(object)||lv_obj_has_state(object,LV_STATE_DISABLED))return false;
 if(!shell_root||!focus_shown(shell_root))return !focus_descendant(object,shell_root);
 if(capture_expanded)return focus_descendant(object,capture_bar)||focus_descendant(object,capture_preview);
 if(overlay)return focus_descendant(object,overlay->content);
 if(app)return focus_descendant(object,app->content)||focus_descendant(object,capture_bar)||focus_descendant(object,capture_preview)||focus_descendant(object,status_bar);
 return focus_descendant(object,shell_root);
}
static lv_group_t *focus_owner(lv_obj_t *object)
{
 if(capture_expanded&&(focus_descendant(object,capture_bar)||focus_descendant(object,capture_preview)))return overlay?overlay->group:app?app->group:focus_base;
 for(lv_obj_t *p=object;p;p=lv_obj_get_parent(p))
  for(unsigned i=0;i<SURFACES;i++)if(surfaces[i].content==p)return surfaces[i].group;
 if(!overlay&&app&&(focus_descendant(object,capture_bar)||focus_descendant(object,capture_preview)||focus_descendant(object,status_bar)))return app->group;
 return focus_base;
}
static void focus_schedule(void)
{
 focus_dirty=true;
 if(shell_root&&focus_base&&!focus_pending&&!focus_busy&&lv_async_call(focus_deferred,NULL)==LV_RESULT_OK)focus_pending=true;
}
static void focus_tree_changed(lv_event_t *event)
{(void)event;focus_schedule();}
/* This pinned LVGL exposes obj_ll but no public group-order iterator. Move
 * existing list nodes rather than remove/re-add or swap objects: that keeps
 * the focused node, editing state and scroll unchanged, and sends no false
 * blur/focus events. Keep SDK-specific list access in this adapter. */
static void focus_order_walk(lv_obj_t *object,lv_group_t *group,void **next)
{
 if(lv_obj_get_group(object)==group){
  lv_obj_t **node;
  _LV_LL_READ(&group->obj_ll,node)if(*node==object)break;
  if(node){
   if(node==*next)*next=_lv_ll_get_next(&group->obj_ll,node);
   else _lv_ll_move_before(&group->obj_ll,node,*next);
  }
 }
 for(uint32_t i=0;i<lv_obj_get_child_count(object);i++)focus_order_walk(lv_obj_get_child(object,i),group,next);
}
static void focus_order(lv_group_t *group)
{void *next=_lv_ll_get_head(&group->obj_ll);focus_order_walk(shell_root,group,&next);}
static lv_obj_t *focus_first(lv_obj_t *object,lv_group_t *group)
{
 if(!object||!focus_shown(object))return NULL;
 if(lv_obj_get_group(object)==group&&focus_allowed(object))return object;
 for(uint32_t i=0;i<lv_obj_get_child_count(object);i++){lv_obj_t *found=focus_first(lv_obj_get_child(object,i),group);if(found)return found;}
 return NULL;
}
static bool focus_has_keys(lv_group_t *group)
{
 for(lv_indev_t *input=lv_indev_get_next(NULL);input;input=lv_indev_get_next(input))
  if(lv_indev_get_group(input)==group&&(lv_indev_get_type(input)==LV_INDEV_TYPE_KEYPAD||lv_indev_get_type(input)==LV_INDEV_TYPE_ENCODER))return true;
 return false;
}
static void focus_group_changed(lv_group_t *group)
{
 if(focus_busy||group!=focus_active_group())return;
 focus_busy=true;lv_obj_t *object=lv_group_get_focused(group);
 if(!focus_allowed(object)){
  object=focus_first(shell_root,group);if(object)lv_group_focus_obj(object);
 }
 if(object&&focus_allowed(object)&&focus_has_keys(group))lv_obj_scroll_to_view_recursive(object,LV_ANIM_OFF);
 focus_busy=false;
}
static void focus_guard(lv_event_t *event)
{
 lv_obj_t *object=lv_event_get_current_target(event);lv_event_code_t code=lv_event_get_code(event);
 if(code==LV_EVENT_DELETE){
  if(capture_return_focus==object)capture_return_focus=NULL;
  for(unsigned i=0;i<SURFACES;i++){
   if(surfaces[i].focus==object)surfaces[i].focus=NULL;
   for(unsigned j=0;j<surfaces[i].local_depth;j++)if(surfaces[i].local_focus[j]==object)surfaces[i].local_focus[j]=NULL;
  }
  if(capture_bar==object)capture_bar=NULL;
  if(capture_preview==object)capture_preview=NULL;
  return;
 }
 if(code==LV_EVENT_FOCUSED){
  if(!focus_busy&&focus_allowed(object)&&focus_has_keys(lv_obj_get_group(object)))lv_obj_scroll_to_view_recursive(object,LV_ANIM_OFF);
  return;
 }
 bool input=code==LV_EVENT_KEY||
   (lv_indev_active()&&(code==LV_EVENT_PRESSED||code==LV_EVENT_RELEASED||code==LV_EVENT_CLICKED||code==LV_EVENT_LONG_PRESSED||code==LV_EVENT_LONG_PRESSED_REPEAT));
 if(!input)return;
 if(!focus_allowed(object)){
  lv_event_stop_processing(event);lv_event_stop_bubbling(event);
  if(lv_obj_has_state(object,LV_STATE_PRESSED))lv_obj_send_event(object,LV_EVENT_PRESS_LOST,lv_indev_active());
  if(lv_indev_active()){lv_indev_wait_release(lv_indev_active());lv_indev_reset(lv_indev_active(),NULL);}
  buffer_ui_shell_focus_refresh();return;
 }
 lv_indev_t *input_device=lv_indev_active();
 bool keyboard=code==LV_EVENT_KEY||(input_device&&(lv_indev_get_type(input_device)==LV_INDEV_TYPE_KEYPAD||lv_indev_get_type(input_device)==LV_INDEV_TYPE_ENCODER));
 if(keyboard&&!lv_obj_is_visible(object)){
  lv_event_stop_processing(event);lv_event_stop_bubbling(event);lv_obj_scroll_to_view_recursive(object,LV_ANIM_OFF);
  if(input_device){lv_indev_wait_release(input_device);lv_indev_reset(input_device,NULL);}return;
 }
 if(code==LV_EVENT_KEY&&lv_event_get_key(event)==LV_KEY_ESC){
  lv_event_stop_processing(event);lv_event_stop_bubbling(event);(void)buffer_ui_shell_back();
 }
}
static void focus_walk(lv_obj_t *object,bool shutdown)
{
 /* CHILD_CHANGED (reorder/reparent) does not bubble in this SDK. Observe
  * parents directly so reused A2UI/list nodes get their current reading order. */
 if(shutdown)lv_obj_remove_event_cb(object,focus_tree_changed);
 else if(lv_obj_get_child_count(object)){
  bool watched=false;
  for(uint32_t i=0;i<lv_obj_get_event_count(object);i++){
   lv_event_dsc_t *event=lv_obj_get_event_dsc(object,i);
   if(event&&lv_event_dsc_get_cb(event)==focus_tree_changed&&event->filter==LV_EVENT_CHILD_CHANGED)watched=true;
  }
  if(!watched)lv_obj_add_event_cb(object,focus_tree_changed,LV_EVENT_CHILD_CHANGED,NULL);
 }
 bool eligible=lv_obj_get_group(object)||lv_obj_is_group_def(object);
 if(!eligible&&lv_obj_has_flag(object,LV_OBJ_FLAG_CLICKABLE))
  for(uint32_t i=0;i<lv_obj_get_event_count(object);i++){
   lv_event_dsc_t *event=lv_obj_get_event_dsc(object,i);
   if(event&&event->filter==LV_EVENT_CLICKED)eligible=true;
  }
 if(eligible){
  bool guarded=false,original_scroll=lv_obj_has_flag(object,LV_OBJ_FLAG_SCROLL_ON_FOCUS);
  for(uint32_t i=0;i<lv_obj_get_event_count(object);i++){
   lv_event_dsc_t *event=lv_obj_get_event_dsc(object,i);
   if(event&&lv_event_dsc_get_cb(event)==focus_guard){guarded=true;original_scroll=(uintptr_t)lv_event_dsc_get_user_data(event)!=0;break;}
  }
  if(shutdown){
   lv_obj_remove_event_cb(object,focus_guard);
   if(guarded&&original_scroll)lv_obj_add_flag(object,LV_OBJ_FLAG_SCROLL_ON_FOCUS);
   lv_group_t *group=lv_obj_get_group(object);bool owned=group==focus_base;
   for(unsigned i=0;i<SURFACES;i++)if(group&&group==surfaces[i].group)owned=true;
   if(owned){lv_group_remove_obj(object);if(focus_group_live(focus_original_default))lv_group_add_obj(focus_original_default,object);}
  }else{
   lv_group_t *owner=focus_owner(object);
   if(owner&&lv_obj_get_group(object)!=owner)lv_group_add_obj(owner,object);
   /* Shell reveals a changed focus immediately. Native FOCUSED otherwise
    * starts an additional animated scroll after our group callback, leaving
    * desktop refresh deferred and competing with its snap/reading anchor. */
   lv_obj_remove_flag(object,LV_OBJ_FLAG_SCROLL_ON_FOCUS);
   if(!guarded)lv_obj_add_event_cb(object,focus_guard,LV_EVENT_ALL|LV_EVENT_PREPROCESS,(void *)(uintptr_t)original_scroll);
  }
 }
 for(uint32_t i=0;i<lv_obj_get_child_count(object);i++)focus_walk(lv_obj_get_child(object,i),shutdown);
}
static bool focus_managed_group(lv_group_t *group)
{
 if(!group||group==focus_base)return true;
 for(unsigned i=0;i<SURFACES;i++)if(group==surfaces[i].group)return true;
 return false;
}
void buffer_ui_shell_focus_refresh(void)
{
 if(!shell_root||!focus_base||focus_busy)return;
 if(focus_pending){lv_async_call_cancel(focus_deferred,NULL);focus_pending=false;}
 focus_busy=true;
 lv_group_t *active=focus_active_group();
 for(lv_indev_t *input=lv_indev_get_next(NULL);input;input=lv_indev_get_next(input)){
  edge_register(input);
  lv_indev_type_t type=lv_indev_get_type(input);
  if((type!=LV_INDEV_TYPE_KEYPAD&&type!=LV_INDEV_TYPE_ENCODER)||lv_indev_get_display(input)!=lv_obj_get_display(shell_root)||!focus_managed_group(lv_indev_get_group(input)))continue;
  struct focus_device *device=focus_devices;
  while(device&&device->input!=input)device=device->next;
  if(!device){
   device=lv_malloc(sizeof(*device));if(!device)continue;
   *device=(struct focus_device){.next=focus_devices,.input=input,.original=lv_indev_get_group(input)};focus_devices=device;
   if(type==LV_INDEV_TYPE_KEYPAD){device->previous=lv_indev_get_read_preprocess_cb(input);lv_indev_set_read_preprocess_cb(input,focus_key_preprocess);}
   lv_indev_add_event_cb(input,focus_input_deleted,LV_EVENT_DELETE,device);
  }
  if(lv_indev_get_group(input)!=active){
   lv_group_t *old_group=lv_indev_get_group(input);lv_obj_t *old=old_group?lv_group_get_focused(old_group):NULL;
   if(old&&lv_obj_has_state(old,LV_STATE_PRESSED))lv_obj_send_event(old,LV_EVENT_PRESS_LOST,input);
   if(!shell_root||!focus_group_live(active)){focus_busy=false;return;}
   if(lv_indev_get_state(input)==LV_INDEV_STATE_PRESSED)lv_indev_wait_release(input);
   lv_indev_reset(input,NULL);lv_indev_set_group(input,active);
  }
 }
 if(focus_dirty){
  focus_dirty=false;focus_walk(shell_root,false);focus_order(focus_base);
  for(unsigned i=0;i<SURFACES;i++)if(surfaces[i].group)focus_order(surfaces[i].group);
 }
 /* A poll must not scroll back to an old focus after a pointer drag. Only
  * a new focus or invalid route needs navigation; unseen key activation
  * above first reveals its target and consumes that gesture. */
 focus_busy=false;if(active&&!focus_allowed(lv_group_get_focused(active)))focus_group_changed(active);
}
static void focus_deferred(void *unused)
{(void)unused;focus_pending=false;buffer_ui_shell_layout();buffer_ui_shell_focus_refresh();}
static void focus_init(void)
{
 focus_original_default=lv_group_get_default();focus_base=focus_original_default;
 focus_owned_base=!focus_base;if(!focus_base){focus_base=lv_group_create();lv_group_set_default(focus_base);}
 focus_dirty=true;
}
static void focus_shutdown(void)
{
 if(!focus_base&&!focus_original_default)return;
 if(focus_pending){lv_async_call_cancel(focus_deferred,NULL);focus_pending=false;}
 focus_busy=true;
 if(shell_root){lv_obj_remove_event_cb(shell_root,focus_tree_changed);focus_walk(shell_root,true);}
 while(focus_devices){
  struct focus_device *device=focus_devices;focus_devices=device->next;
  if(focus_input_live(device->input)){
   /* Restoring a borrowed group cannot release an old Enter/Space contact
    * onto its newly focused control outside the Shell. */
   if(lv_indev_get_state(device->input)==LV_INDEV_STATE_PRESSED){lv_indev_wait_release(device->input);lv_indev_reset(device->input,NULL);}
   if(lv_indev_get_read_preprocess_cb(device->input)==focus_key_preprocess)lv_indev_set_read_preprocess_cb(device->input,device->previous);
   lv_indev_remove_event_cb_with_user_data(device->input,focus_input_deleted,device);
   if(focus_managed_group(lv_indev_get_group(device->input)))
    lv_indev_set_group(device->input,focus_group_live(device->original)&&!(focus_owned_base&&device->original==focus_base)?device->original:NULL);
  }
  lv_free(device);
 }
 for(unsigned i=0;i<SURFACES;i++)if(surfaces[i].group){lv_group_delete(surfaces[i].group);surfaces[i].group=NULL;}
 if(focus_owned_base&&focus_base)lv_group_delete(focus_base);
 if(focus_group_live(focus_original_default))lv_group_set_default(focus_original_default);else lv_group_set_default(NULL);
 focus_base=focus_original_default=NULL;focus_owned_base=focus_dirty=focus_busy=false;
}
