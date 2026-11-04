/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_typography.h"
#include "buffer_ui_units.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_theme.h"
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <syslog.h>

/* Material typography role metrics, AndroidX TypeScaleTokens v0_103.
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/TypeScaleTokens.kt
 * Source SHA-256: 7ca6378374d78da8a1091d2ee4a4c228f70d0daef65dfd51c18b58f7d9679ba4
 * CJK font-family/available-weight mapping is a Vela adaptation. A Medium or
 * Bold face is preferred; SDK Demibold (600) is an explicit nearest-weight
 * fallback, never an assertion that its outlines have weight 500 or 700. */
static const struct buffer_ui_type_metrics metrics[BUFFER_TEXT_ROLE_COUNT]={
 {57,64,400,-20},{45,52,400,0},{36,44,400,0},
 {32,40,400,0},{28,36,400,0},{24,32,400,0},
 {22,28,400,0},{16,24,500,20},{14,20,500,10},
 {16,24,400,50},{14,20,400,20},{12,16,400,40},
 {14,20,500,10},{12,16,500,50},{11,16,500,50},
 {57,64,500,0},{45,52,500,0},{36,44,500,0},
 {32,40,500,0},{28,36,500,0},{24,32,500,0},
 {22,28,500,0},{16,24,700,15},{14,20,700,10},
 {16,24,500,15},{14,20,500,25},{12,16,500,40},
 {14,20,700,10},{12,16,700,50},{11,16,700,50}
};
struct binding { struct binding *next; lv_obj_t *object; enum buffer_ui_type_role role; bool control; };
static struct binding *bindings;
static unsigned scale_percent=100;
static const lv_font_t *borrowed[5];
static bool ready;
#if LV_USE_FREETYPE
#define FONT_CACHE 160
struct cached_font {lv_font_t *font;uint16_t pixels,weight;};
static struct cached_font cache[FONT_CACHE];
static char regular_path[256],medium_path[256],bold_path[256];
#endif
static const enum buffer_ui_type_role legacy_roles[]={BUFFER_TEXT_BODY_LARGE,
 BUFFER_TEXT_HEADLINE_LARGE,BUFFER_TEXT_TITLE_LARGE,BUFFER_TEXT_TITLE_MEDIUM,BUFFER_TEXT_LABEL_LARGE};
const struct buffer_ui_type_metrics *buffer_ui_type_metrics(enum buffer_ui_type_role role)
{return (unsigned)role<BUFFER_TEXT_ROLE_COUNT?&metrics[role]:NULL;}
unsigned buffer_ui_font_scale(void){return scale_percent;}
int32_t buffer_ui_scaled_sp(int32_t value)
{
 lv_display_t *display=lv_display_get_default();
 unsigned dpi=display?lv_display_get_dpi(display):LV_DPI_DEF;if(!dpi)dpi=160;
 int64_t scaled=(int64_t)value*dpi*scale_percent;
 int32_t pixels=(int32_t)((scaled+(scaled<0?-8000:8000))/16000);
 return value&&!pixels?(value<0?-1:1):pixels;
}
static const lv_font_t *external_font(enum buffer_ui_type_role role)
{
 unsigned base=(unsigned)role%BUFFER_TEXT_BASE_COUNT;
 unsigned slot=base<=BUFFER_TEXT_HEADLINE_SMALL?1:
   base==BUFFER_TEXT_TITLE_LARGE?2:base<=BUFFER_TEXT_TITLE_SMALL?3:
   base<=BUFFER_TEXT_BODY_SMALL?0:4;
 return borrowed[slot]?borrowed[slot]:LV_FONT_DEFAULT;
}
#if LV_USE_FREETYPE
static bool readable_cjk(const lv_font_t *font)
{
 static const uint32_t probes[]={0x4e2d,0x65e5,0x5386,0x3002,'0','A'};
 for(unsigned i=0;i<sizeof(probes)/sizeof(probes[0]);i++){
  lv_font_glyph_dsc_t glyph;
  if(!lv_font_get_glyph_dsc(font,&glyph,probes[i],0)||glyph.is_placeholder)return false;
 }
 return true;
}
static const lv_font_t *load_font(enum buffer_ui_type_role role,unsigned scale)
{
 const struct buffer_ui_type_metrics *type=&metrics[role];
 lv_display_t *display=lv_display_get_default();unsigned dpi=display?lv_display_get_dpi(display):LV_DPI_DEF;
 if(!dpi)dpi=160;
 unsigned pixels=((unsigned)type->size_sp*dpi*scale+8000)/16000;
 if(!pixels)pixels=1;
 unsigned weight=type->weight;
 const char *path=regular_path;
 if(weight>=700&&bold_path[0])path=bold_path;
 else if(weight>=500&&medium_path[0])path=medium_path;
 /* Cache by the real file/style rather than a semantic weight. */
 unsigned face=medium_path[0]&&!strcmp(path,medium_path)?500:
   bold_path[0]&&!strcmp(path,bold_path)?700:400;
 for(unsigned i=0;i<FONT_CACHE;i++)if(cache[i].font&&cache[i].pixels==pixels&&cache[i].weight==face)return cache[i].font;
 unsigned slot=FONT_CACHE;for(unsigned i=0;i<FONT_CACHE;i++)if(!cache[i].font){slot=i;break;}
 if(slot==FONT_CACHE)return NULL;
 lv_font_t *font=lv_freetype_font_create(path,LV_FREETYPE_FONT_RENDER_MODE_BITMAP,pixels,LV_FREETYPE_FONT_STYLE_NORMAL);
 if(!font)return NULL;
 cache[slot]=(struct cached_font){font,pixels,face};return font;
}
static bool derive_face(char *target,size_t capacity,const char *name)
{
 const char *slash=strrchr(regular_path,'/');if(!slash)return false;
 int length=snprintf(target,capacity,"%.*s/%s",(int)(slash-regular_path),regular_path,name);
 if(length<0||(size_t)length>=capacity||access(target,R_OK)){target[0]=0;return false;}
 lv_font_t *probe=lv_freetype_font_create(target,LV_FREETYPE_FONT_RENDER_MODE_BITMAP,16,LV_FREETYPE_FONT_STYLE_NORMAL);
 bool valid=probe&&readable_cjk(probe);if(probe)lv_freetype_font_delete(probe);
 if(!valid)target[0]=0;
 return valid;
}
#endif
const lv_font_t *buffer_ui_font(enum buffer_ui_type_role role)
{
 if((unsigned)role>=BUFFER_TEXT_ROLE_COUNT)role=BUFFER_TEXT_BODY_LARGE;
#if LV_USE_FREETYPE
 if(ready){
  const lv_font_t *font=load_font(role,scale_percent);if(font)return font;
  /* Resource exhaustion may reduce typography precision, never CJK coverage. */
  const lv_font_t *nearest=NULL;int distance=0x7fffffff;
  int pixels=buffer_ui_sp(metrics[role].size_sp);
  for(unsigned i=0;i<FONT_CACHE;i++)if(cache[i].font){
    int d=abs((int)cache[i].pixels-pixels);if(d<distance){distance=d;nearest=cache[i].font;}
  }
  if(nearest)return nearest;
 }
#endif
 return external_font(role);
}
static void deleted(lv_event_t *event)
{
 struct binding *binding=lv_event_get_user_data(event),**link=&bindings;
 while(*link&&*link!=binding)link=&(*link)->next;
 if(*link)*link=binding->next;
 lv_free(binding);
}
static struct binding *binding_for(lv_obj_t *object)
{for(struct binding *b=bindings;b;b=b->next)if(b->object==object)return b;return NULL;}
void buffer_ui_control_label_reflow(lv_obj_t *label)
{
 struct binding *binding=binding_for(label);
 if(!binding||!binding->control||lv_obj_has_flag(label,LV_OBJ_FLAG_HIDDEN))return;
 lv_obj_t *button=lv_obj_get_parent(label),*parent=lv_obj_get_parent(button);
 if(!button||!parent)return;
 int32_t padding=lv_obj_get_style_pad_left(button,0)+lv_obj_get_style_pad_right(button,0);
 int32_t vertical=lv_obj_get_style_pad_top(button,0)+lv_obj_get_style_pad_bottom(button,0);
 int32_t available=lv_obj_get_content_width(parent);
 if(available<=0)available=lv_display_get_horizontal_resolution(lv_obj_get_display(label));
 if(available<buffer_ui_dp(48))available=buffer_ui_dp(48);
 const lv_font_t *font=lv_obj_get_style_text_font(label,0);
 lv_point_t natural;
 lv_text_get_size(&natural,buffer_ui_label_source_text(label),font,
    lv_obj_get_style_text_letter_space(label,0),lv_obj_get_style_text_line_space(label,0),LV_COORD_MAX,LV_TEXT_FLAG_NONE);
 int32_t width=natural.x+padding;
 if(width<buffer_ui_dp(48))width=buffer_ui_dp(48);
 if(width>available)width=available;
 int32_t content=width-padding;if(content<1)content=1;
 lv_point_t wrapped;
 lv_text_get_size(&wrapped,buffer_ui_label_source_text(label),font,
    lv_obj_get_style_text_letter_space(label,0),lv_obj_get_style_text_line_space(label,0),content,LV_TEXT_FLAG_NONE);
 int32_t height=wrapped.y+vertical;if(height<buffer_ui_dp(48))height=buffer_ui_dp(48);
 lv_obj_set_style_min_width(button,width,0);lv_obj_set_style_min_height(button,height,0);
 if(natural.x>content){lv_label_set_long_mode(label,LV_LABEL_LONG_WRAP);lv_obj_set_height(label,LV_SIZE_CONTENT);}
}
static void apply(struct binding *binding)
{
 const struct buffer_ui_type_metrics *type=&metrics[binding->role];
 const lv_font_t *font=buffer_ui_font(binding->role);
 lv_obj_set_style_text_font(binding->object,font,0);
 int32_t line=buffer_ui_sp(type->line_sp)-lv_font_get_line_height(font);
 lv_obj_set_style_text_line_space(binding->object,line>0?line:0,0);
 /* LVGL spacing is integral pixels. Retain subpixel tracking in the token
  * metadata and round here, without forcing 0.1sp to a whole pixel. */
 int64_t tracking=(int64_t)type->tracking_hundredths_sp*buffer_ui_sp(100);
 lv_obj_set_style_text_letter_space(binding->object,(int32_t)((tracking+(tracking<0?-5000:5000))/10000),0);
}
void buffer_ui_text_role(lv_obj_t *object,enum buffer_ui_type_role role)
{
 if(!object)return;
 if((unsigned)role>=BUFFER_TEXT_ROLE_COUNT)role=BUFFER_TEXT_BODY_LARGE;
 struct binding *binding=binding_for(object);
 if(!binding){
  binding=lv_malloc(sizeof(*binding));
  if(!binding){lv_obj_set_style_text_font(object,buffer_ui_font(role),0);return;}
  *binding=(struct binding){bindings,object,role,false};bindings=binding;
  if(!lv_obj_add_event_cb(object,deleted,LV_EVENT_DELETE,binding)){
   bindings=binding->next;lv_free(binding);lv_obj_set_style_text_font(object,buffer_ui_font(role),0);return;
  }
 }else binding->role=role;
 apply(binding);
}
void buffer_ui_type_fonts(const lv_font_t *h1,const lv_font_t *h2,
                          const lv_font_t *h3,const lv_font_t *body,const lv_font_t *label)
{borrowed[0]=body;borrowed[1]=h1;borrowed[2]=h2;borrowed[3]=h3;borrowed[4]=label;}
void buffer_ui_type(lv_obj_t *object,unsigned variant)
{
 if(variant>4)variant=0;
 buffer_ui_text_role(object,legacy_roles[variant]);
 lv_obj_set_style_text_color(object,lv_color_hex(variant==4?BUFFER_MUTED:BUFFER_ON_SURFACE),0);
}
void buffer_ui_button_label(lv_obj_t *label)
{
 buffer_ui_text_role(label,BUFFER_TEXT_LABEL_LARGE);
 struct binding *binding=binding_for(label);if(binding)binding->control=true;
 lv_obj_remove_local_style_prop(label,LV_STYLE_TEXT_COLOR,0);
 lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);
}
bool buffer_ui_typography_ready(void){return ready;}
int buffer_ui_typography_init(void)
{
 if(ready)return 0;
#ifdef CONFIG_BUFFER_VELA_FONT_SCALE
 scale_percent=CONFIG_BUFFER_VELA_FONT_SCALE;
#endif
 const char *configured=getenv("BUFFER_UI_FONT_SCALE");
 if(configured&&*configured){char *end;unsigned long value=strtoul(configured,&end,10);if(!*end&&value>=85&&value<=200)scale_percent=value;}
#if LV_USE_FREETYPE
 static const char *const paths[]={
#ifdef CONFIG_BUFFER_VELA_FONT_PATH
 CONFIG_BUFFER_VELA_FONT_PATH,
#endif
 "/resource/fonts/MiSans-Normal.ttf","/data/fonts/MiSans-Regular.ttf",
 "/data/fonts/MiSansVF.ttf","/etc/fonts/MiSans-Regular.ttf","/system/fonts/MiSans-Regular.ttf"};
 for(unsigned i=0;i<sizeof(paths)/sizeof(paths[0]);i++){
  if(access(paths[i],R_OK))continue;
  lv_font_t *font=lv_freetype_font_create(paths[i],LV_FREETYPE_FONT_RENDER_MODE_BITMAP,16,LV_FREETYPE_FONT_STYLE_NORMAL);
  bool valid=font&&readable_cjk(font);if(font)lv_freetype_font_delete(font);
  if(!valid)continue;
  snprintf(regular_path,sizeof(regular_path),"%s",paths[i]);
  if(!derive_face(medium_path,sizeof(medium_path),"MiSans-Medium.ttf"))derive_face(medium_path,sizeof(medium_path),"MiSans-Demibold.ttf");
  if(!derive_face(bold_path,sizeof(bold_path),"MiSans-Bold.ttf"))derive_face(bold_path,sizeof(bold_path),"MiSans-Demibold.ttf");
  syslog(LOG_INFO,"Buffer typography: regular=%s medium=%s bold=%s\n",regular_path,medium_path[0]?medium_path:regular_path,bold_path[0]?bold_path:regular_path);
  ready=true;
  for(unsigned j=0;j<sizeof(legacy_roles)/sizeof(legacy_roles[0]);j++)if(!load_font(legacy_roles[j],scale_percent)){buffer_ui_typography_shutdown();return -ENOMEM;}
  return 0;
 }
 return -ENOENT;
#else
 return -ENOTSUP;
#endif
}
int buffer_ui_typography_scale(unsigned percent)
{
 if(percent<85||percent>200)return -ERANGE;
 if(percent==scale_percent)return 0;
#if LV_USE_FREETYPE
 if(ready)for(struct binding *b=bindings;b;b=b->next)if(!load_font(b->role,percent))return -ENOMEM;
#endif
 scale_percent=percent;
 for(struct binding *b=bindings;b;b=b->next)apply(b);
 for(struct binding *b=bindings;b;b=b->next)if(b->control)buffer_ui_control_label_reflow(b->object);
 return 0;
}
void buffer_ui_typography_shutdown(void)
{
 /* Delete all bound UI objects before releasing their owned font resources. */
 while(bindings){struct binding *next=bindings->next;lv_obj_remove_event_cb(bindings->object,deleted);lv_free(bindings);bindings=next;}
#if LV_USE_FREETYPE
 for(unsigned i=0;i<FONT_CACHE;i++){if(cache[i].font)lv_freetype_font_delete(cache[i].font);cache[i]=(struct cached_font){0};}
 regular_path[0]=medium_path[0]=bold_path[0]=0;
#endif
 memset(borrowed,0,sizeof(borrowed));ready=false;scale_percent=100;
}
