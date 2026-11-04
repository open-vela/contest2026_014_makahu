/* Real fonts, live role updates, scale/dp separation and switch hit targets. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include "buffer_ui_typography.h"
#include "buffer_ui_controls.h"
#include "buffer_ui_layout.h"
static unsigned changes;
static bool value;
static uint32_t input_key;
static lv_indev_state_t input_state;
static void read_key(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;data->key=input_key;data->state=input_state;}
static void changed(bool checked,void *context){assert(context==&changes);changes++;value=checked;}
static void pump(void){lv_obj_update_layout(lv_screen_active());}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(800,480);
 const char *dpi=getenv("BUFFER_UI_DPI");if(dpi)lv_display_set_dpi(display,atoi(dpi));
 int result=buffer_ui_typography_init();
#ifdef BUFFER_UI_REAL_FONTS
 assert(!result&&buffer_ui_typography_ready());
 assert(buffer_ui_font(BUFFER_TEXT_BODY_LARGE)!=buffer_ui_font(BUFFER_TEXT_BODY_LARGE_EMPHASIZED));
 assert(buffer_ui_font(BUFFER_TEXT_BODY_LARGE_EMPHASIZED)!=buffer_ui_font(BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED));
#else
 assert(result==-ENOTSUP);
#endif
 lv_obj_t *labels[BUFFER_TEXT_ROLE_COUNT];
#ifdef BUFFER_UI_REAL_FONTS
 unsigned baseline_heights[BUFFER_TEXT_ROLE_COUNT];
 unsigned baseline_scale=buffer_ui_font_scale();
 int32_t baseline_lines[BUFFER_TEXT_ROLE_COUNT];
#endif
 for(unsigned i=0;i<BUFFER_TEXT_ROLE_COUNT;i++){
  const struct buffer_ui_type_metrics *m=buffer_ui_type_metrics(i);assert(m&&m->line_sp>=m->size_sp);
  assert(m->weight==400||m->weight==500||m->weight==700);
  labels[i]=lv_label_create(lv_screen_active());buffer_ui_text_role(labels[i],i);
  lv_label_set_text(labels[i],"中文日历 12:34 Aa");
#ifdef BUFFER_UI_REAL_FONTS
  lv_font_glyph_dsc_t glyph;
  const lv_font_t *font=lv_obj_get_style_text_font(labels[i],0);
  assert(lv_font_get_glyph_dsc(font,&glyph,0x4e2d,0)&&!glyph.is_placeholder);baseline_heights[i]=glyph.box_h;baseline_lines[i]=lv_font_get_line_height(font);
#endif
 }
 int32_t target=buffer_ui_dp(48);const unsigned scales[]={125,150,200,100};
 for(unsigned n=0;n<4;n++){
  assert(!buffer_ui_typography_scale(scales[n]));assert(buffer_ui_font_scale()==scales[n]);
  assert(buffer_ui_dp(48)==target);
  assert(buffer_ui_sp(100)==(int32_t)((100u*lv_display_get_dpi(display)*scales[n]+8000u)/16000u));
  for(unsigned i=0;i<BUFFER_TEXT_ROLE_COUNT;i++){
   assert(lv_obj_get_style_text_font(labels[i],0)==buffer_ui_font(i));
#ifdef BUFFER_UI_REAL_FONTS
   lv_font_glyph_dsc_t glyph;assert(lv_font_get_glyph_dsc(buffer_ui_font(i),&glyph,0x4e2d,0));
   /* Grid fitting changes the ink box: MiSans's 37px and 73px 中 are
    * 35px and 67px high. Check measured growth with a 4px hinting budget,
    * independently of the requested role size and pointer identity. */
   assert(abs((int)glyph.box_h*(int)baseline_scale-(int)baseline_heights[i]*(int)scales[n])<=(int)(4*baseline_scale));
   if(scales[n]>baseline_scale)assert(lv_font_get_line_height(buffer_ui_font(i))>baseline_lines[i]);
   if(scales[n]==200&&baseline_scale==100)assert(glyph.box_h>baseline_heights[i]*3/2);
#endif
  }
 }
 assert(buffer_ui_typography_scale(0)==-ERANGE&&buffer_ui_font_scale()==100);
 assert(buffer_ui_type_metrics(BUFFER_TEXT_ROLE_COUNT)==NULL);
 lv_group_t *group=lv_group_create();lv_group_set_default(group);
 lv_obj_t *row=buffer_ui_switch_create(lv_screen_active(),"减少动态",false,changed,&changes);assert(row);
 pump();assert(lv_obj_get_height(row)>=buffer_ui_dp(48));
 lv_obj_send_event(row,LV_EVENT_CLICKED,NULL);assert(changes==1&&value&&lv_obj_has_state(row,LV_STATE_CHECKED));
 buffer_ui_switch_disable(row,true);lv_obj_send_event(row,LV_EVENT_CLICKED,NULL);assert(changes==1);
 buffer_ui_switch_set(row,false);assert(!lv_obj_has_state(row,LV_STATE_CHECKED)&&changes==1);
 buffer_ui_switch_disable(row,false);
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_KEYPAD);lv_indev_set_read_cb(input,read_key);lv_indev_set_group(input,group);
 lv_group_focus_obj(row);input_key=LV_KEY_ENTER;input_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 assert(changes==1&&!lv_obj_has_state(row,LV_STATE_CHECKED));input_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(changes==2&&value);
 input_key=' ';input_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);assert(changes==3&&!value);
 input_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(changes==3);
 lv_indev_delete(input);
 lv_obj_t *owner=lv_button_create(lv_screen_active());buffer_ui_button_style(owner,true);
 lv_obj_t *caption=lv_label_create(owner);buffer_ui_button_label(caption);
 lv_obj_add_state(owner,LV_STATE_DISABLED);
 assert(lv_color_eq(lv_obj_get_style_text_color(caption,0),lv_obj_get_style_text_color(owner,0)));
 lv_obj_delete(row);lv_obj_delete(owner);
 for(unsigned i=0;i<BUFFER_TEXT_ROLE_COUNT;i++)lv_obj_delete(labels[i]);
 buffer_ui_typography_shutdown();lv_group_delete(group);lv_display_delete(display);lv_deinit();
 puts("PASS: 30 typography roles, live 100/125/150/200% sp, CJK coverage, switch input/state and cleanup");
}
