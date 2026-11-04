/* Native NuttX pointer contract: only DOWN/MOVE supply coordinates. */
static lv_point_t edge_fixture_point;
static lv_indev_state_t edge_fixture_state;
static void edge_fixture_read(lv_indev_t *input,lv_indev_data_t *data)
{
 (void)input;if(edge_fixture_state==LV_INDEV_STATE_PRESSED)data->point=edge_fixture_point;
 data->state=edge_fixture_state;
}
static void edge_touch_back(lv_obj_t *window,bool left)
{
 lv_obj_update_layout(window);lv_area_t area;lv_obj_get_coords(window,&area);
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(input,edge_fixture_read);
 buffer_ui_shell_focus_refresh();int x=left?area.x1+2:area.x2-2;
 edge_fixture_point=(lv_point_t){x,(area.y1+area.y2)/2};edge_fixture_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 for(int i=1;i<=6;i++){
  edge_fixture_point.x=x+(left?1:-1)*buffer_ui_dp(i*8);lv_indev_read(input);lv_tick_inc(16);lv_timer_handler();
 }
 edge_fixture_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);
 for(unsigned i=0;i<8;i++){lv_tick_inc(16);lv_timer_handler();}
 lv_indev_delete(input);
}
