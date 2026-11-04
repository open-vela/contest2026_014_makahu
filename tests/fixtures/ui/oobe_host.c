/* Actual production onboarding, resized in place across aspect ratios. */
#include <assert.h>
#include <stdio.h>
#include "buffer_oobe_ui.c"
#include "visual_capture.h"
static void pump(unsigned n)
{while(n--){lv_tick_inc(16);lv_timer_handler();}}
static unsigned actions[4];
static void invoked(enum buffer_oobe_ui_action_e action,void *unused){(void)unused;actions[action]++;}
static void click(lv_obj_t *button){lv_obj_send_event(button,LV_EVENT_PRESSED,NULL);lv_obj_send_event(button,LV_EVENT_RELEASED,NULL);lv_obj_send_event(button,LV_EVENT_CLICKED,NULL);}
static void inside(lv_obj_t *child,lv_obj_t *parent)
{
 lv_area_t a,b;lv_obj_get_coords(child,&a);lv_obj_get_coords(parent,&b);
 if(!(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2))fprintf(stderr,"outside: %d,%d:%d,%d parent %d,%d:%d,%d\n",a.x1,a.y1,a.x2,a.y2,b.x1,b.y1,b.x2,b.y2);
 assert(a.x1>=b.x1 && a.x2<=b.x2 && a.y1>=b.y1 && a.y2<=b.y2);
}
static void qr_valid(struct buffer_oobe_ui_s *ui,const char *payload)
{
 assert(ui->qr && !lv_obj_has_flag(ui->qr,LV_OBJ_FLAG_HIDDEN));
 assert(!strcmp(ui->qr_payload,payload));
 lv_draw_buf_t *buffer=lv_canvas_get_draw_buf(ui->qr);assert(buffer);
 int modules=qrcodegen_version2size(qrcodegen_getMinFitVersion(qrcodegen_Ecc_MEDIUM,strlen(payload)));
 assert(buffer->header.w%(unsigned)modules==0);
 unsigned scale=buffer->header.w/(unsigned)modules;assert(scale>=2);
 assert(lv_obj_get_style_pad_left(ui->qr_panel,0)>=(int)scale*4);
 unsigned dark=0;for(unsigned y=0;y<buffer->header.h;y++)for(unsigned x=0;x<buffer->header.w;x++){
  if(buffer->data[8+y*buffer->header.stride+x/8]&(1u<<(7-x%8)))dark++;
 }
 assert(dark>buffer->header.w*buffer->header.h/5);
 inside(ui->qr,ui->qr_panel);inside(ui->qr_panel,ui->media);
}
int main(void)
{
 lv_init();lv_display_t *d=lv_display_create(320,480);
 static uint32_t pixels[1280*16];
 lv_display_set_buffers(d,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);
 lv_display_set_flush_cb(d,flush);buffer_ui_motion_init();buffer_ui_motion_policy(true,false);
 (void)buffer_ui_typography_init();
 const lv_font_t *hero=NULL,*large=NULL,*small=NULL;
#ifdef BUFFER_UI_REAL_FONTS
 assert(buffer_ui_typography_ready());
 hero=buffer_ui_font(BUFFER_TEXT_HEADLINE_LARGE);large=buffer_ui_font(BUFFER_TEXT_BODY_LARGE);small=buffer_ui_font(BUFFER_TEXT_LABEL_LARGE);
#endif
 struct buffer_oobe_ui_s *ui=buffer_oobe_ui_create(lv_screen_active(),hero,large,small,invoked,NULL);assert(ui);
 const int sizes[][2]={{320,240},{240,320},{240,240},{320,480},{480,320},{480,800},{800,480},{1280,720},{200,240}};
 for(unsigned n=0;n<sizeof(sizes)/sizeof(sizes[0]);n++){
  lv_display_set_resolution(d,sizes[n][0],sizes[n][1]);pump(3);
  for(int english=0;english<2;english++)for(int page=BUFFER_OOBE_UI_WELCOME;page<=BUFFER_OOBE_UI_BLOCKED;page++){
   struct buffer_oobe_ui_state_s s={0};s.page=page;s.english_fallback=english;
   s.qr_payload="vela-fabric://pair/v1/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
   s.confirm_visible=s.reject_visible=true;s.retry_visible=page==BUFFER_OOBE_UI_BLOCKED;s.pairing_code="123456";
   buffer_oobe_ui_render(ui,&s);pump(5);lv_obj_update_layout(ui->root);
   char name[64];snprintf(name,sizeof(name),"oobe-%d-%d",page,english);screenshot(name,sizes[n][0],sizes[n][1]);
   inside(ui->body,ui->root);
   if(page==BUFFER_OOBE_UI_WELCOME){
    qr_valid(ui,s.qr_payload);
    inside(ui->qr_panel,ui->body);
    /* Status/language rerenders at unchanged dimensions must not clear the
     * cached QR canvas. Real pairing payload stays constant across resize. */
    lv_draw_buf_t *before=lv_canvas_get_draw_buf(ui->qr);
    s.retry_busy=!s.retry_busy;buffer_oobe_ui_render(ui,&s);pump(3);
    assert(before==lv_canvas_get_draw_buf(ui->qr));qr_valid(ui,s.qr_payload);
   }
   if(page==BUFFER_OOBE_UI_VERIFY)inside(ui->pairing_code,ui->body);
   assert(lv_obj_get_height(ui->body)>0);inside(ui->setup_label,ui->header);if(!lv_obj_has_flag(ui->brand,LV_OBJ_FLAG_HIDDEN))inside(ui->brand,ui->header);
   if(!lv_obj_has_flag(ui->actions,LV_OBJ_FLAG_HIDDEN)){
    inside(ui->actions,ui->root);
    lv_obj_t *buttons[]={ui->confirm,ui->reject,ui->retry,ui->start};
    for(unsigned b=0;b<4;b++)if(!lv_obj_has_flag(buttons[b],LV_OBJ_FLAG_HIDDEN)){inside(buttons[b],ui->actions);inside(lv_obj_get_child(buttons[b],0),buttons[b]);}
   }
   lv_area_t body,copy;lv_obj_get_coords(ui->body,&body);lv_obj_get_coords(ui->copy,&copy);
   assert(copy.x1>=body.x1 && copy.x2<=body.x2);
   if(lv_obj_get_style_flex_flow(ui->body,0)==LV_FLEX_FLOW_ROW&&!lv_obj_has_flag(ui->media,LV_OBJ_FLAG_HIDDEN)){
    lv_area_t media;lv_obj_get_coords(ui->media,&media);
    assert(media.x2<copy.x1);
   }
   /* Unchanged presentation must not dirty the display on each poll. */
   pump(5);unsigned before=flushes;buffer_oobe_ui_render(ui,&s);pump(5);
   assert(flushes==before);
  }
  printf("onboarding %dx%d: controls bounded, scrollable content, idle frame stable\n",sizes[n][0],sizes[n][1]);
 }
 struct buffer_oobe_ui_state_s s={.page=BUFFER_OOBE_UI_VERIFY,.confirm_visible=true,.reject_visible=true,.pairing_code="123456",.request_epoch=1};
 buffer_oobe_ui_render(ui,&s);pump(5);lv_obj_send_event(ui->confirm,LV_EVENT_PRESSED,NULL);
 s.request_epoch++;buffer_oobe_ui_render(ui,&s);lv_obj_send_event(ui->confirm,LV_EVENT_CLICKED,NULL);assert(!actions[BUFFER_OOBE_UI_ACTION_CONFIRM]);
 click(ui->confirm);assert(actions[BUFFER_OOBE_UI_ACTION_CONFIRM]==1);
 lv_obj_send_event(ui->reject,LV_EVENT_PRESSED,NULL);s.pairing_code="654321";buffer_oobe_ui_render(ui,&s);lv_obj_send_event(ui->reject,LV_EVENT_CLICKED,NULL);assert(!actions[BUFFER_OOBE_UI_ACTION_REJECT]);
 click(ui->reject);assert(actions[BUFFER_OOBE_UI_ACTION_REJECT]==1);
 s.page=BUFFER_OOBE_UI_READY;s.start_busy=true;buffer_oobe_ui_render(ui,&s);click(ui->start);assert(!actions[BUFFER_OOBE_UI_ACTION_START]);
 s.start_busy=false;buffer_oobe_ui_render(ui,&s);click(ui->start);assert(actions[BUFFER_OOBE_UI_ACTION_START]==1);
 s.page=BUFFER_OOBE_UI_BLOCKED;s.retry_visible=true;s.retry_busy=true;buffer_oobe_ui_render(ui,&s);click(ui->retry);assert(!actions[BUFFER_OOBE_UI_ACTION_RETRY]);
 s.retry_busy=false;buffer_oobe_ui_render(ui,&s);click(ui->retry);assert(actions[BUFFER_OOBE_UI_ACTION_RETRY]==1);
 if(buffer_ui_typography_ready()){
  unsigned original=buffer_ui_font_scale();assert(!buffer_ui_typography_scale(original==100?200:100));buffer_oobe_ui_render(ui,&s);pump(10);inside(ui->actions,ui->root);assert(lv_obj_get_height(ui->body)>0);assert(!buffer_ui_typography_scale(original));
  assert(!buffer_ui_typography_scale(200));
  s.page=BUFFER_OOBE_UI_WELCOME;s.retry_visible=false;s.qr_payload="vela-fabric://pair/v1/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  for(unsigned n=0;n<sizeof(sizes)/sizeof(sizes[0]);n++){
   lv_display_set_resolution(d,sizes[n][0],sizes[n][1]);pump(3);buffer_oobe_ui_render(ui,&s);pump(10);
   qr_valid(ui,s.qr_payload);inside(ui->qr_panel,ui->body);
   screenshot("oobe-qr-200",sizes[n][0],sizes[n][1]);
  }
  assert(!buffer_ui_typography_scale(original));
 }
 buffer_oobe_ui_destroy(ui);buffer_ui_motion_shutdown();buffer_ui_typography_shutdown();lv_display_delete(d);lv_deinit();return 0;
}
