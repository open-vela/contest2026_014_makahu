/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_oobe_ui.h"

#include "buffer_ui_theme.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_icons.h"
#include "buffer_ui_motion.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if LV_USE_QRCODE
#  include <lvgl/src/libs/qrcode/lv_qrcode.h>
#  include <lvgl/src/libs/qrcode/qrcodegen.h>
#endif

struct buffer_oobe_ui_s;
struct buffer_oobe_ui_binding_s
{
  struct buffer_oobe_ui_s *ui;
  enum buffer_oobe_ui_action_e action;
  uint64_t revision;bool pressed;
};

struct buffer_oobe_ui_s
{
  lv_obj_t *root;
  lv_obj_t *body, *media, *copy;
  lv_obj_t *header,*brand,*step_row;
  int32_t layout_width, layout_height;
  unsigned layout_scale,layout_dpi;
  lv_obj_t *steps[4];
  lv_obj_t *step_labels[4];
  lv_obj_t *setup_label;
  lv_obj_t *hero;
  lv_obj_t *art;
  lv_obj_t *art_badge;
  lv_obj_t *title;
  lv_obj_t *subtitle;
  lv_obj_t *qr_panel;
  lv_obj_t *qr_fallback;
#if LV_USE_QRCODE
  lv_obj_t *qr;
  char qr_payload[96];
#endif
  lv_obj_t *pairing_code;
  lv_obj_t *status;
  lv_obj_t *actions;
  lv_obj_t *retry;
  lv_obj_t *confirm;
  lv_obj_t *reject;
  lv_obj_t *start;
  lv_obj_t *start_label;
  const lv_font_t *hero_font;
  const lv_font_t *body_font;
  const lv_font_t *small_font;
  buffer_oobe_ui_action_fn action;
  void *context;
  struct buffer_oobe_ui_binding_s bindings[4];
  bool compact;
  bool rendered;
  bool presentation_dirty;
  unsigned last_flags;
  uint64_t last_request_epoch,interaction_revision;
  int last_hint;
  char last_payload[96], last_code[7];
  int last_page;
  lv_obj_t *art_mark;
};

static void ui_visible(lv_obj_t *object, bool visible)
{
  if (object == NULL) return;
  if (visible) buffer_ui_remove_flag(object, LV_OBJ_FLAG_HIDDEN);
  else buffer_ui_add_flag(object, LV_OBJ_FLAG_HIDDEN);
}

static void ui_qr_resize(struct buffer_oobe_ui_s *ui,int32_t available)
{
  int modules=41; /* Public-key locator: 22-byte prefix + 64 hexadecimal bytes. */
#if LV_USE_QRCODE
  size_t length=ui->last_payload[0]?strlen(ui->last_payload):86;
  int version=qrcodegen_getMinFitVersion(qrcodegen_Ecc_MEDIUM,length);
  if(version>0)modules=qrcodegen_version2size(version);
#endif
  int target=buffer_ui_dp(192);if(target>available)target=available;
  int scale=target/(modules+8);if(scale<1)scale=1;
  int size=modules*scale,quiet=4*scale;
  lv_obj_set_size(ui->qr_panel,size+2*quiet,size+2*quiet);
  lv_obj_set_style_pad_all(ui->qr_panel,quiet,0);
#if LV_USE_QRCODE
  if(ui->qr){
    lv_draw_buf_t *buffer=lv_canvas_get_draw_buf(ui->qr);
    /* set_size allocates and clears the canvas, even for the same size.
     * Preserve it on ordinary status renders; invalidate payload on resize. */
    if(!buffer||buffer->header.w!=(uint32_t)size||buffer->header.h!=(uint32_t)size){
      lv_qrcode_set_size(ui->qr,size);ui->qr_payload[0]=0;
    }
    lv_obj_center(ui->qr);
  }
#endif
}

static void ui_layout_update(struct buffer_oobe_ui_s *ui)
{
  struct buffer_ui_viewport v = buffer_ui_viewport(ui->root);
  unsigned scale=buffer_ui_font_scale(),dpi=lv_display_get_dpi(lv_obj_get_display(ui->root));
  if (!ui->body || (v.width == ui->layout_width &&
                    v.height == ui->layout_height&&scale==ui->layout_scale&&dpi==ui->layout_dpi)) return;
  ui->layout_width = v.width;
  ui->layout_height = v.height;
  ui->layout_scale=scale;ui->layout_dpi=dpi;
  ui->presentation_dirty = true;
  ui->compact = v.short_screen || v.width < buffer_ui_dp(280);
  int available=v.width-2*v.padding-2*buffer_ui_dp(ui->compact?14:22);
  int media=buffer_ui_dp(184);
  bool side_by_side = v.landscape && v.short_screen&&available-media-v.gap>=buffer_ui_dp(160)*(int)scale/100;
  if(ui->title){if(buffer_ui_typography_ready())buffer_ui_text_role(ui->title,ui->compact?BUFFER_TEXT_TITLE_LARGE:BUFFER_TEXT_HEADLINE_LARGE);
  else lv_obj_set_style_text_font(ui->title,ui->compact?ui->body_font:ui->hero_font,0);}
  if(ui->subtitle){if(buffer_ui_typography_ready())buffer_ui_text_role(ui->subtitle,BUFFER_TEXT_BODY_LARGE);
  else lv_obj_set_style_text_font(ui->subtitle,ui->compact?ui->small_font:ui->body_font,0);}
  lv_obj_set_style_pad_all(ui->root, v.padding, 0);
  lv_obj_set_style_pad_row(ui->root, v.gap, 0);
  lv_obj_set_flex_flow(ui->body, side_by_side ? LV_FLEX_FLOW_ROW : LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_gap(ui->body, v.gap, 0);
  lv_obj_set_width(ui->media, side_by_side ? media : LV_PCT(100));
  lv_obj_set_width(ui->copy, side_by_side ? 0 : LV_PCT(100));
  lv_obj_set_flex_grow(ui->copy, side_by_side ? 1 : 0);
  /* Keep the entire scan target in the initial viewport on short portrait
   * displays too; explanatory text may scroll below it at large font scales. */
  lv_obj_move_to_index(ui->copy,side_by_side||ui->last_page==BUFFER_OOBE_UI_VERIFY||
      ui->last_page==BUFFER_OOBE_UI_WELCOME?1:0);
  int line=lv_font_get_line_height(lv_obj_get_style_text_font(ui->setup_label,0));
  lv_obj_set_height(ui->header,line);
  lv_point_t brand,setup;
  lv_text_get_size(&brand,"BUFFER",lv_obj_get_style_text_font(ui->brand,0),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
  lv_text_get_size(&setup,buffer_ui_label_source_text(ui->setup_label),lv_obj_get_style_text_font(ui->setup_label,0),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
  ui_visible(ui->brand,brand.x+setup.x+buffer_ui_dp(12)<=v.width-2*v.padding);
  int column=(v.width-2*v.padding)/4;
  bool captions=true;int step_height=buffer_ui_dp(14);
  for(unsigned i=0;i<4;i++){
    lv_point_t bounds;lv_text_get_size(&bounds,buffer_ui_label_source_text(ui->step_labels[i]),lv_obj_get_style_text_font(ui->step_labels[i],0),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
    if(bounds.x+buffer_ui_dp(8)>column)captions=false;
  }
  if(scale>125&&v.height<buffer_ui_dp(300))captions=false;
  if(captions)step_height+=line+buffer_ui_dp(4);
  lv_obj_set_height(ui->step_row,step_height);
  for(unsigned i=0;i<4;i++)ui_visible(ui->step_labels[i],captions);
  /* The full written step remains in the setup heading when dot captions
   * would overflow; no font reduction is used to keep all four labels. */
  ui_qr_resize(ui,side_by_side?media:available);
}
static void ui_layout(lv_event_t *event)
{ui_layout_update(lv_event_get_user_data(event));}

static lv_obj_t *ui_column(lv_obj_t *parent)
{
  lv_obj_t *obj = lv_obj_create(parent);
  lv_obj_remove_style_all(obj);
  lv_obj_set_size(obj, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(obj, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(obj, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_row(obj, buffer_ui_dp(4), 0);
  buffer_ui_remove_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
  return obj;
}

static void ui_consume_event(lv_event_t *event)
{
  (void)event;
}

static void ui_action_event(lv_event_t *event)
{
  struct buffer_oobe_ui_binding_s *binding = lv_event_get_user_data(event);
  if(!binding||!binding->ui)return;
  if(lv_event_get_code(event)==LV_EVENT_PRESSED){binding->revision=binding->ui->interaction_revision;binding->pressed=true;return;}
  bool valid=binding->pressed&&binding->revision==binding->ui->interaction_revision;
  binding->pressed=false;
  if(lv_obj_has_state(lv_event_get_current_target(event),LV_STATE_DISABLED))return;
  if((binding->action==BUFFER_OOBE_UI_ACTION_CONFIRM||binding->action==BUFFER_OOBE_UI_ACTION_REJECT)&&!valid)return;
  if(binding->ui->action)binding->ui->action(binding->action,binding->ui->context);
}

static lv_obj_t *ui_action_button(struct buffer_oobe_ui_s *ui,
                                  const char *text,
                                  enum buffer_oobe_ui_action_e action,
                                  bool primary)
{
  lv_obj_t *button = lv_button_create(ui->actions);
  lv_obj_t *label = lv_label_create(button);
  struct buffer_oobe_ui_binding_s *binding = &ui->bindings[action];

  lv_obj_set_size(button, 0, LV_SIZE_CONTENT);
  lv_obj_set_style_min_height(button, buffer_ui_dp(48), 0);
  lv_obj_set_style_min_width(button, buffer_ui_dp(80), 0);
  lv_obj_set_style_max_width(button, LV_PCT(100), 0);
  lv_obj_set_flex_grow(button, 1);
  buffer_ui_button_style(button, primary);
  if(buffer_ui_typography_ready())buffer_ui_button_label(label);
  else lv_obj_set_style_text_font(label, ui->body_font, 0);
  lv_obj_set_width(label, LV_PCT(100));
  lv_label_set_long_mode(label, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
  buffer_ui_label_set_text(label, text);
  lv_obj_center(label);
  binding->ui = ui;
  binding->action = action;
  lv_obj_add_event_cb(button, ui_action_event, LV_EVENT_CLICKED, binding);
  lv_obj_add_event_cb(button, ui_action_event, LV_EVENT_PRESSED, binding);
  return button;
}

static void ui_style_art(struct buffer_oobe_ui_s *ui, int32_t diameter)
{
  lv_obj_set_size(ui->art, diameter, diameter);
  lv_obj_remove_style_all(ui->art);
  buffer_ui_remove_flag(ui->art, LV_OBJ_FLAG_SCROLLABLE | LV_OBJ_FLAG_CLICKABLE);
  lv_obj_t *badge = lv_obj_create(ui->art);
  lv_obj_remove_style_all(badge);
  lv_obj_set_size(badge, diameter - buffer_ui_dp(12), diameter - buffer_ui_dp(12));
  lv_obj_center(badge);
  lv_obj_set_style_radius(badge, buffer_ui_dp(28), 0);
  buffer_ui_style_bg_color(badge, lv_color_hex(BUFFER_PRIMARY_CONTAINER), 0);
  buffer_ui_style_bg_opa(badge, LV_OPA_COVER, 0);
  buffer_ui_remove_flag(badge, LV_OBJ_FLAG_SCROLLABLE | LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_text_color(badge,lv_color_hex(BUFFER_ACCENT),0);
  ui->art_mark=buffer_ui_icon_create(badge,&buffer_settings_icon);
  lv_obj_set_size(ui->art_mark,buffer_ui_dp(32),buffer_ui_dp(32));
  lv_obj_center(ui->art_mark);
  ui->art_badge = badge;
}

static void ui_create_background_decoration(struct buffer_oobe_ui_s *ui,
                                             int32_t screen_width,
                                             int32_t screen_height)
{
  int32_t diameter = buffer_ui_dp(ui->compact ? 168 : 276);
  lv_obj_t *halo = lv_obj_create(ui->root);
  lv_obj_remove_style_all(halo);
  lv_obj_set_size(halo, diameter, diameter);
  lv_obj_set_pos(halo, screen_width - diameter / 2,
                 screen_height - diameter * 3 / 4);
  lv_obj_set_style_radius(halo, LV_RADIUS_CIRCLE, 0);
  buffer_ui_style_bg_color(halo, lv_color_hex(BUFFER_OOBE_DECOR), 0);
  buffer_ui_style_bg_opa(halo, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(halo, 0, 0);
  buffer_ui_remove_flag(halo, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
  buffer_ui_add_flag(halo, LV_OBJ_FLAG_FLOATING);


}

static void ui_create_step(struct buffer_oobe_ui_s *ui, lv_obj_t *parent,
                           unsigned int index, const char *label)
{
  lv_obj_t *item = lv_obj_create(parent);
  lv_obj_t *dot = lv_obj_create(item);
  lv_obj_t *caption = lv_label_create(item);

  lv_obj_remove_style_all(item);
  lv_obj_set_size(item, 0, LV_PCT(100));
  lv_obj_set_flex_grow(item, 1);
  lv_obj_set_flex_flow(item, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(item, LV_FLEX_ALIGN_CENTER,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  buffer_ui_remove_flag(item, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_size(dot, buffer_ui_dp(9), buffer_ui_dp(9));
  lv_obj_set_style_radius(dot, LV_RADIUS_CIRCLE, 0);
  buffer_ui_style_bg_color(dot, lv_color_hex(BUFFER_PANEL), 0);
  buffer_ui_style_bg_opa(dot, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(dot, buffer_ui_dp(1), 0);
  lv_obj_set_style_border_color(dot, lv_color_hex(BUFFER_ACCENT), 0);
  buffer_ui_label_set_text(caption, label);
  lv_obj_set_width(caption,LV_PCT(100));lv_obj_set_style_text_align(caption,LV_TEXT_ALIGN_CENTER,0);lv_label_set_long_mode(caption,LV_LABEL_LONG_WRAP);
  buffer_ui_style_text_color(caption, lv_color_hex(BUFFER_MUTED), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(caption,BUFFER_TEXT_LABEL_LARGE);
  else lv_obj_set_style_text_font(caption, ui->small_font, 0);
  ui->steps[index] = dot;
  ui->step_labels[index] = caption;
}

static unsigned int ui_active_step(enum buffer_oobe_ui_page_e page)
{
  switch (page)
    {
      case BUFFER_OOBE_UI_WELCOME:
      case BUFFER_OOBE_UI_VERIFY:
        return 0;
      case BUFFER_OOBE_UI_NETWORK:
        return 1;
      case BUFFER_OOBE_UI_SERVICE:
        return 2;
      case BUFFER_OOBE_UI_READY:
        return 3;
      case BUFFER_OOBE_UI_BLOCKED:
      default:
        return 0;
    }
}

static const char *ui_hint_text(const struct buffer_oobe_ui_state_s *state,
                               bool english)
{
  switch (state->hint)
    {
      case BUFFER_OOBE_UI_HINT_WAITING_PHONE:
        return english ? "Waiting for your phone" : "等待手机连接";
      case BUFFER_OOBE_UI_HINT_NETWORK_CONNECTING:
        return english ? "Connecting to the network" : "正在连接网络";
      case BUFFER_OOBE_UI_HINT_NETWORK_RETRY:
        return english ? "Connection did not finish. Try again." :
                         "网络连接未完成，请重试";
      case BUFFER_OOBE_UI_HINT_SERVICE_CONFIGURING:
        return english ? "Finish service setup on your phone" :
                         "请在手机完成服务设置";
      case BUFFER_OOBE_UI_HINT_SERVICE_VALIDATING:
        return english ? "Checking your service connection" :
                         "设备正在检查服务连接";
      case BUFFER_OOBE_UI_HINT_SERVICE_RETRY:
        return english ? "Check service settings and try again" :
                         "服务连接未完成，请检查设置后重试";
      case BUFFER_OOBE_UI_HINT_START_RETRY:
        return english ? "Could not save. Please try again." :
                         "保存失败，请重试";
      case BUFFER_OOBE_UI_HINT_NONE:
      default:
        return "";
    }
}

static void ui_set_step_state(struct buffer_oobe_ui_s *ui,
                              unsigned int active)
{
  for (unsigned int i = 0; i < 4; i++)
    {
      bool complete = i < active;
      bool current = i == active;
      lv_obj_set_size(ui->steps[i], current ? buffer_ui_dp(11) : buffer_ui_dp(9), current ? buffer_ui_dp(11) : buffer_ui_dp(9));
      buffer_ui_style_bg_color(ui->steps[i],
          lv_color_hex(complete || current ? BUFFER_ACCENT : BUFFER_PANEL), 0);
      lv_obj_set_style_border_width(ui->steps[i], current ? 0 : buffer_ui_dp(1), 0);
      buffer_ui_style_text_color(ui->step_labels[i],
          lv_color_hex(current || complete ? BUFFER_ACCENT : BUFFER_MUTED), 0);
    }
}

struct buffer_oobe_ui_s *buffer_oobe_ui_create(
    lv_obj_t *screen, const lv_font_t *hero_font,
    const lv_font_t *body_font, const lv_font_t *small_font,
    buffer_oobe_ui_action_fn action, void *context)
{
  struct buffer_oobe_ui_s *ui;
  lv_obj_t *header;
  lv_obj_t *brand;
  lv_obj_t *step_row;
  lv_obj_t *qr_panel;
  int32_t screen_width;
  int32_t screen_height;
  int32_t qr_size;
  int32_t art_size;

  if (screen == NULL) return NULL;
  ui = calloc(1, sizeof(*ui));
  if (ui == NULL) return NULL;
  ui->hero_font = hero_font != NULL ? hero_font : LV_FONT_DEFAULT;
  ui->body_font = body_font != NULL ? body_font : LV_FONT_DEFAULT;
  ui->small_font = small_font != NULL ? small_font : LV_FONT_DEFAULT;
  ui->action = action;
  ui->context = context;
  screen_width = LV_HOR_RES;
  screen_height = LV_VER_RES;
  ui->compact = screen_width <= buffer_ui_dp(260) || screen_height <= buffer_ui_dp(340);
  qr_size = buffer_ui_dp(ui->compact ? 108 : 160);
  art_size = buffer_ui_dp(ui->compact ? 76 : (screen_height < buffer_ui_dp(400) ? 92 : 116));

  ui->root = lv_obj_create(screen);
  if (ui->root == NULL)
    {
      free(ui);
      return NULL;
    }
  lv_obj_remove_style_all(ui->root);
  lv_obj_set_size(ui->root, LV_PCT(100), LV_PCT(100));
  lv_obj_set_pos(ui->root, 0, 0);
  buffer_ui_style_bg_color(ui->root, lv_color_hex(BUFFER_BG), 0);
  buffer_ui_style_bg_opa(ui->root, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(ui->root, 0, 0);
  lv_obj_set_style_radius(ui->root, 0, 0);
  lv_obj_set_style_pad_hor(ui->root,0,0);
  lv_obj_set_style_pad_top(ui->root, ui->compact ? buffer_ui_dp(5) : buffer_ui_dp(16), 0);
  lv_obj_set_style_pad_bottom(ui->root, ui->compact ? buffer_ui_dp(5) : buffer_ui_dp(16), 0);
  lv_obj_set_style_pad_row(ui->root, ui->compact ? buffer_ui_dp(2) : buffer_ui_dp(8), 0);
  lv_obj_set_flex_flow(ui->root, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(ui->root, LV_FLEX_ALIGN_START,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  buffer_ui_remove_flag(ui->root, LV_OBJ_FLAG_SCROLLABLE);
  /* This sibling sits over the home root and consumes taps in every blank
   * area as well as on the controls. */
  buffer_ui_add_flag(ui->root, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_add_event_cb(ui->root, ui_consume_event, LV_EVENT_ALL, NULL);
  ui_create_background_decoration(ui, screen_width, screen_height);

  header = lv_obj_create(ui->root);
  ui->header=header;
  lv_obj_remove_style_all(header);
  lv_obj_set_style_pad_hor(header,ui->compact?buffer_ui_dp(14):buffer_ui_dp(22),0);
  lv_obj_set_size(header, LV_PCT(100), buffer_ui_dp(22));
  lv_obj_set_flex_flow(header, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(header, LV_FLEX_ALIGN_SPACE_BETWEEN,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  buffer_ui_remove_flag(header, LV_OBJ_FLAG_SCROLLABLE);
  brand = lv_label_create(header);
  ui->brand=brand;
  buffer_ui_label_set_text(brand, "BUFFER");
  buffer_ui_style_text_color(brand, lv_color_hex(BUFFER_ACCENT), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(brand,BUFFER_TEXT_LABEL_LARGE);
  else lv_obj_set_style_text_font(brand, ui->small_font, 0);
  ui->setup_label = lv_label_create(header);
  buffer_ui_label_set_text(ui->setup_label, "设备设置");
  buffer_ui_style_text_color(ui->setup_label, lv_color_hex(BUFFER_MUTED), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(ui->setup_label,BUFFER_TEXT_LABEL_LARGE);
  else lv_obj_set_style_text_font(ui->setup_label, ui->small_font, 0);

  step_row = lv_obj_create(ui->root);
  ui->step_row=step_row;
  lv_obj_remove_style_all(step_row);
  lv_obj_set_style_pad_hor(step_row,ui->compact?buffer_ui_dp(14):buffer_ui_dp(22),0);
  lv_obj_set_size(step_row, LV_PCT(100), ui->compact ? buffer_ui_dp(31) : buffer_ui_dp(34));
  lv_obj_set_flex_flow(step_row, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(step_row, LV_FLEX_ALIGN_SPACE_BETWEEN,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  buffer_ui_remove_flag(step_row, LV_OBJ_FLAG_SCROLLABLE);
  static const char *const step_names[] = {"连接", "网络", "服务", "完成"};
  for (unsigned int i = 0; i < 4; i++)
    {
      ui_create_step(ui, step_row, i, step_names[i]);
    }

  ui->body = ui_column(ui->root);
  lv_obj_set_style_pad_hor(ui->body,ui->compact?buffer_ui_dp(14):buffer_ui_dp(22),0);
  lv_obj_set_height(ui->body, 0);
  lv_obj_set_flex_grow(ui->body, 1);
  buffer_ui_add_flag(ui->body, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(ui->body, LV_DIR_VER);
  ui->media = ui_column(ui->body);
  ui->copy = ui_column(ui->body);
  ui->art = lv_obj_create(ui->media);
  ui_style_art(ui, art_size);
  ui_visible(ui->art, false);

  ui->hero = lv_obj_create(ui->copy);
  lv_obj_remove_style_all(ui->hero);
  lv_obj_set_size(ui->hero, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(ui->hero, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(ui->hero, LV_FLEX_ALIGN_CENTER,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_row(ui->hero, ui->compact ? buffer_ui_dp(1) : buffer_ui_dp(3), 0);
  buffer_ui_remove_flag(ui->hero, LV_OBJ_FLAG_SCROLLABLE);

  ui->title = lv_label_create(ui->hero);
  lv_obj_set_width(ui->title, LV_PCT(100));
  lv_label_set_long_mode(ui->title, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(ui->title, LV_TEXT_ALIGN_CENTER, 0);
  buffer_ui_style_text_color(ui->title, lv_color_hex(BUFFER_ON_SURFACE), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(ui->title,BUFFER_TEXT_HEADLINE_LARGE);
  else lv_obj_set_style_text_font(ui->title, ui->hero_font, 0);

  ui->subtitle = lv_label_create(ui->hero);
  lv_obj_set_width(ui->subtitle, LV_PCT(100));
  lv_label_set_long_mode(ui->subtitle, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(ui->subtitle, LV_TEXT_ALIGN_CENTER, 0);
  buffer_ui_style_text_color(ui->subtitle, lv_color_hex(BUFFER_MUTED), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(ui->subtitle,BUFFER_TEXT_BODY_LARGE);
  else lv_obj_set_style_text_font(ui->subtitle, ui->body_font, 0);

  qr_panel = lv_obj_create(ui->media);
  ui->qr_panel = qr_panel;
  lv_obj_remove_style_all(qr_panel);
  lv_obj_set_size(qr_panel, qr_size + buffer_ui_dp(16), qr_size + buffer_ui_dp(16));
  lv_obj_set_style_radius(qr_panel, buffer_ui_dp(14), 0);
  buffer_ui_style_bg_color(qr_panel, lv_color_white(), 0);
  buffer_ui_style_bg_opa(qr_panel, LV_OPA_COVER, 0);
  lv_obj_set_style_pad_all(qr_panel, buffer_ui_dp(8), 0);
  lv_obj_set_style_shadow_width(qr_panel, ui->compact ? 0 : buffer_ui_dp(8), 0);
  lv_obj_set_style_shadow_color(qr_panel,
      lv_color_hex(BUFFER_PRIMARY_CONTAINER), 0);
  buffer_ui_remove_flag(qr_panel, LV_OBJ_FLAG_SCROLLABLE);
#if LV_USE_QRCODE
  ui->qr = lv_qrcode_create(qr_panel);
  if (ui->qr != NULL)
    {
      lv_qrcode_set_size(ui->qr, qr_size);
      lv_qrcode_set_dark_color(ui->qr, lv_color_black());
      lv_qrcode_set_light_color(ui->qr, lv_color_white());
      /* Exact integer modules, with a full four-module quiet zone supplied
       * by the white parent. Avoid version-dependent internal shrinking. */
      lv_qrcode_set_quiet_zone(ui->qr, false);
      lv_obj_center(ui->qr);
    }
#endif
  ui->qr_fallback = lv_label_create(qr_panel);
  lv_obj_set_width(ui->qr_fallback,LV_PCT(100));
  lv_label_set_long_mode(ui->qr_fallback,LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(ui->qr_fallback,LV_TEXT_ALIGN_CENTER,0);
  buffer_ui_label_set_text(ui->qr_fallback, "等待连接码");
  if(buffer_ui_typography_ready())buffer_ui_text_role(ui->qr_fallback,BUFFER_TEXT_LABEL_LARGE);
  else lv_obj_set_style_text_font(ui->qr_fallback, ui->small_font, 0);
  buffer_ui_style_text_color(ui->qr_fallback,
      lv_color_hex(BUFFER_MUTED), 0);
  lv_obj_center(ui->qr_fallback);
  ui_visible(qr_panel, false);

  ui->pairing_code = lv_label_create(ui->media);
  lv_obj_set_width(ui->pairing_code, LV_PCT(100));
  lv_obj_set_style_text_align(ui->pairing_code, LV_TEXT_ALIGN_CENTER, 0);
  buffer_ui_style_text_color(ui->pairing_code, lv_color_hex(BUFFER_ACCENT), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(ui->pairing_code,BUFFER_TEXT_HEADLINE_LARGE);
  else lv_obj_set_style_text_font(ui->pairing_code, ui->hero_font, 0);
  ui_visible(ui->pairing_code, false);

  ui->status = lv_label_create(ui->copy);
  lv_obj_set_width(ui->status, LV_PCT(100));
  lv_label_set_long_mode(ui->status, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(ui->status, LV_TEXT_ALIGN_CENTER, 0);
  buffer_ui_style_text_color(ui->status, lv_color_hex(BUFFER_ACCENT), 0);
  if(buffer_ui_typography_ready())buffer_ui_text_role(ui->status,BUFFER_TEXT_LABEL_LARGE);
  else lv_obj_set_style_text_font(ui->status, ui->small_font, 0);
  buffer_ui_style_bg_color(ui->status,
      lv_color_hex(BUFFER_PRIMARY_CONTAINER), 0);
  buffer_ui_style_bg_opa(ui->status, LV_OPA_50, 0);
  lv_obj_set_style_radius(ui->status, buffer_ui_dp(14), 0);
  lv_obj_set_style_pad_hor(ui->status, buffer_ui_dp(12), 0);
  lv_obj_set_style_pad_ver(ui->status, buffer_ui_dp(6), 0);
  ui_visible(ui->status, false);

  ui->actions = lv_obj_create(ui->root);
  lv_obj_remove_style_all(ui->actions);
  lv_obj_set_style_pad_hor(ui->actions,ui->compact?buffer_ui_dp(14):buffer_ui_dp(22),0);
  lv_obj_set_size(ui->actions, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(ui->actions, LV_FLEX_FLOW_ROW_WRAP);
  lv_obj_set_flex_align(ui->actions, LV_FLEX_ALIGN_CENTER,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(ui->actions, buffer_ui_dp(8), 0);
  buffer_ui_remove_flag(ui->actions, LV_OBJ_FLAG_SCROLLABLE);
  ui->confirm = ui_action_button(ui, "数字一致", BUFFER_OOBE_UI_ACTION_CONFIRM,
                                 true);
  ui->reject = ui_action_button(ui, "取消", BUFFER_OOBE_UI_ACTION_REJECT,
                                false);
  ui->retry = ui_action_button(ui, "重试", BUFFER_OOBE_UI_ACTION_RETRY,
                               true);
  ui->start = ui_action_button(ui, "开始使用", BUFFER_OOBE_UI_ACTION_START,
                               true);
  ui->start_label = lv_obj_get_child(ui->start, 0);
  ui_visible(ui->confirm, false);
  ui_visible(ui->reject, false);
  ui_visible(ui->retry, false);
  ui_visible(ui->start, false);
  ui_visible(ui->actions, false);
  lv_obj_add_event_cb(ui->root, ui_layout, LV_EVENT_SIZE_CHANGED, ui);
  lv_obj_update_layout(ui->root);
  ui_visible(ui->root, false);
  return ui;
}

void buffer_oobe_ui_render(struct buffer_oobe_ui_s *ui,
                           const struct buffer_oobe_ui_state_s *state)
{
  static const char *const step_names[] = {"连接", "网络", "服务", "完成"};
  const char *title;
  const char *subtitle;
  const char *status;
  bool english;
  bool show_art = false;
  bool show_qr = false;
  bool show_code = false;
  bool show_status = false;
  bool show_actions = false;
  unsigned int active;

  if (ui == NULL || state == NULL) return;
  ui_layout_update(ui);
  unsigned flags = (state->retry_visible ? 1u : 0u) |
      (state->retry_busy ? 2u : 0u) | (state->confirm_visible ? 4u : 0u) |
      (state->reject_visible ? 8u : 0u) | (state->start_busy ? 16u : 0u) |
      (state->english_fallback ? 32u : 0u);
  const char *payload = state->qr_payload ? state->qr_payload : "";
  const char *code = state->pairing_code && strlen(state->pairing_code) == 6 ?
                     state->pairing_code : "";
  if (ui->rendered && !ui->presentation_dirty && ui->last_page == (int)state->page &&
      ui->last_hint == (int)state->hint && ui->last_flags == flags &&
      ui->last_request_epoch==state->request_epoch&&
      !strcmp(ui->last_payload, payload) && !strcmp(ui->last_code, code))
    { ui_visible(ui->root, true); return; }
  if(!ui->rendered||ui->last_page!=(int)state->page||ui->last_request_epoch!=state->request_epoch||strcmp(ui->last_code,code))ui->interaction_revision++;
  ui->last_request_epoch=state->request_epoch;
  ui->presentation_dirty = false;
  ui->last_flags = flags;
  ui->last_hint = state->hint;
  snprintf(ui->last_payload, sizeof(ui->last_payload), "%s", payload);
  snprintf(ui->last_code, sizeof(ui->last_code), "%s", code);
  bool page_changed = !ui->rendered || ui->last_page != (int)state->page;
  if (page_changed)
    {
      buffer_ui_motion_cancel(ui->art);
      buffer_ui_motion_enter(ui->hero, 1, false);
      if (state->page == BUFFER_OOBE_UI_READY && ui->rendered)
        buffer_ui_motion_enter(ui->art, 1, false);
      buffer_ui_icon_set_src(ui->art_mark,state->page==BUFFER_OOBE_UI_READY?&buffer_ready_icon:
          state->page==BUFFER_OOBE_UI_BLOCKED?&buffer_warning_icon:
          state->page==BUFFER_OOBE_UI_NETWORK?&buffer_wifi_icon:&buffer_settings_icon);
      ui->last_page = state->page;
      ui->rendered = true;
    }
  english = state->english_fallback;
  active = ui_active_step(state->page);
  char step_caption[48];snprintf(step_caption,sizeof(step_caption),english?"Setup %u/4":"设置 %u/4",active+1);
  buffer_ui_label_set_text(ui->setup_label,step_caption);
  ui_set_step_state(ui, active);

  switch (state->page)
    {
      case BUFFER_OOBE_UI_WELCOME:
        title = ui->compact ?
            (english ? "Welcome to Buffer" : "欢迎使用 Buffer") :
            (english ? "Welcome to\nBuffer" : "欢迎使用\nBuffer");
        subtitle = english ? "Scan with your phone to connect" :
                             "用手机扫描二维码，连接这台设备";
        status = ui_hint_text(state, english);
        show_qr = true;
        show_status = status[0] != '\0';
        break;
      case BUFFER_OOBE_UI_VERIFY:
        title = english ? "Check the numbers" : "核对安全数字";
        subtitle = english ? "They should match on both screens" :
                             "确认手机与设备上的数字一致";
        status = ui_hint_text(state, english);
        show_code = true;
        show_status = status[0] != '\0';
        break;
      case BUFFER_OOBE_UI_NETWORK:
        title = english ? "Connect to Wi-Fi" : "连接网络";
        subtitle = english ? "Your phone can send the network details" :
                             "请在手机完成网络设置";
        status = ui_hint_text(state, english);
        show_art = true;
        show_status = status[0] != '\0';
        break;
      case BUFFER_OOBE_UI_SERVICE:
        title = english ? "Prepare your assistant" : "准备智能服务";
        subtitle = english ? "Buffer is checking the connection" :
                             "设备正在检查服务连接";
        status = ui_hint_text(state, english);
        show_art = true;
        show_status = status[0] != '\0';
        break;
      case BUFFER_OOBE_UI_READY:
        title = english ? "All set" : "一切就绪";
        subtitle = english ? "Start recording when you are ready" :
                             "点击“开始使用”即可记录";
        status = ui_hint_text(state, english);
        show_art = true;
        show_status = status[0] != '\0';
        break;
      case BUFFER_OOBE_UI_BLOCKED:
      default:
        title = english ? "Let's finish setup" : "继续完成设备设置";
        subtitle = english ? "Check the connection, then try again" :
                             "检查设备连接后再试一次";
        status = ui_hint_text(state, english);
        if (status[0] == '\0')
          status = english ? "Setup is not complete yet" : "设备设置尚未完成";
        show_art = true;
        show_status = true;
        break;
    }

  if (english)
    {
      static const char *const names[] = {"Phone", "Wi-Fi", "Service", "Done"};
      for (unsigned int i = 0; i < 4; i++)
        buffer_ui_label_set_text(ui->step_labels[i], names[i]);
    }
  else
    {
      for (unsigned int i = 0; i < 4; i++)
        buffer_ui_label_set_text(ui->step_labels[i], step_names[i]);
    }
  /* Re-evaluate caption fitting after a language change. */
  ui->layout_scale=0;ui_layout_update(ui);ui->presentation_dirty=false;

  buffer_ui_label_set_text(ui->qr_fallback,
                    english ? "Preparing connection" : "正在准备连接码");
  buffer_ui_label_set_text(lv_obj_get_child(ui->confirm, 0),
                    english ? "Numbers match" : "数字一致");
  buffer_ui_label_set_text(lv_obj_get_child(ui->reject, 0),
                    english ? "Cancel" : "取消");

  buffer_ui_label_set_text(ui->title, title);
  buffer_ui_label_set_text(ui->subtitle, subtitle);
  buffer_ui_label_set_text(ui->status, status);
  ui_visible(ui->art, show_art);
  ui_visible(ui->qr_panel, show_qr);
  ui_visible(ui->pairing_code, show_code);
  ui_visible(ui->media, show_art || show_qr || show_code);
  ui_visible(ui->status, show_status);

  if (show_code)
    buffer_ui_label_set_text(ui->pairing_code,
        state->pairing_code != NULL && strlen(state->pairing_code) == 6 ?
        state->pairing_code : "------");

#if LV_USE_QRCODE
  if(!payload[0])ui->qr_payload[0]=0;
  if (show_qr && ui->qr != NULL && state->qr_payload != NULL &&
      state->qr_payload[0] != '\0' &&
      strcmp(state->qr_payload, ui->qr_payload) != 0)
    {
      ui->qr_payload[0]=0;
      if (lv_qrcode_update(ui->qr, state->qr_payload,
                           (uint32_t)strlen(state->qr_payload)) == LV_RESULT_OK)
        {
          snprintf(ui->qr_payload, sizeof(ui->qr_payload), "%s",
                   state->qr_payload);
        }
    }
  ui_visible(ui->qr, show_qr && ui->qr != NULL && ui->qr_payload[0] != '\0');
  ui_visible(ui->qr_fallback,
      show_qr && (ui->qr == NULL || ui->qr_payload[0] == '\0'));
#else
  ui_visible(ui->qr_fallback, show_qr);
#endif

  ui_visible(ui->confirm, state->page == BUFFER_OOBE_UI_VERIFY &&
                            state->confirm_visible);
  ui_visible(ui->reject, state->page == BUFFER_OOBE_UI_VERIFY &&
                           state->reject_visible);
  ui_visible(ui->retry, state->retry_visible);
  ui_visible(ui->start, state->page == BUFFER_OOBE_UI_READY);
  if (state->retry_busy)
    {
      lv_obj_add_state(ui->retry, LV_STATE_DISABLED);
      buffer_ui_label_set_text(lv_obj_get_child(ui->retry, 0),
                        english ? "Connecting…" : "正在连接…");
    }
  else
    {
      lv_obj_clear_state(ui->retry, LV_STATE_DISABLED);
      buffer_ui_label_set_text(lv_obj_get_child(ui->retry, 0),
                        english ? "Try again" : "重试");
    }
  if (state->start_busy)
    {
      lv_obj_add_state(ui->start, LV_STATE_DISABLED);
      buffer_ui_label_set_text(ui->start_label,
                        english ? "Starting…" : "正在开始…");
    }
  else
    {
      lv_obj_clear_state(ui->start, LV_STATE_DISABLED);
      buffer_ui_label_set_text(ui->start_label,
                        english ? "Get started" : "开始使用");
    }
  show_actions = state->retry_visible ||
      (state->page == BUFFER_OOBE_UI_VERIFY &&
       (state->confirm_visible || state->reject_visible)) ||
      state->page == BUFFER_OOBE_UI_READY;
  ui_visible(ui->actions, show_actions);
  ui_visible(ui->root, true);
  lv_obj_update_layout(ui->root);
  lv_obj_t *buttons[]={ui->confirm,ui->reject,ui->retry,ui->start};
  int widths[4]={0},count=0,total=0,available=lv_obj_get_content_width(ui->actions),gap=buffer_ui_dp(8);
  for(unsigned i=0;i<4;i++)if(!lv_obj_has_flag(buttons[i],LV_OBJ_FLAG_HIDDEN)){
    buffer_ui_control_label_reflow(lv_obj_get_child(buttons[i],0));
    widths[i]=lv_obj_get_style_min_width(buttons[i],0);total+=widths[i];count++;
  }
  /* Choose the arrangement from measured labels. LVGL distributes growing
   * items before applying minimum widths, which can overflow a wrapped row. */
  bool row=count>0&&total+(count-1)*gap<=available;
  lv_obj_set_flex_flow(ui->actions,row?LV_FLEX_FLOW_ROW:LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(ui->actions,gap,0);
  int extra=row?(available-total-(count-1)*gap)/count:0;
  for(unsigned i=0;i<4;i++)if(widths[i]){lv_obj_set_flex_grow(buttons[i],0);lv_obj_set_width(buttons[i],row?widths[i]+extra:available);}
  if(show_code&&buffer_ui_typography_ready()){
    lv_obj_update_layout(ui->root);
    int width=lv_obj_get_content_width(ui->media),height=lv_obj_get_content_height(ui->body);
    const enum buffer_ui_type_role roles[]={BUFFER_TEXT_HEADLINE_LARGE,BUFFER_TEXT_TITLE_LARGE,BUFFER_TEXT_TITLE_MEDIUM};
    for(unsigned i=0;i<3;i++){
      const lv_font_t *font=buffer_ui_font(roles[i]);lv_point_t digits;
      lv_text_get_size(&digits,"000000",font,0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
      if((digits.x<=width&&lv_font_get_line_height(font)<=height)||i==2){buffer_ui_text_role(ui->pairing_code,roles[i]);break;}
    }
  }
  lv_obj_move_to_index(ui->root, -1);
}

void buffer_oobe_ui_set_visible(struct buffer_oobe_ui_s *ui, bool visible)
{
  if (ui == NULL) return;
  if(!visible)ui->interaction_revision++;
  ui_visible(ui->root, visible);
  if (visible) lv_obj_move_to_index(ui->root, -1);
}

void buffer_oobe_ui_destroy(struct buffer_oobe_ui_s *ui)
{
  if (ui == NULL) return;
  if (ui->root != NULL) lv_obj_del(ui->root);
  free(ui);
}
