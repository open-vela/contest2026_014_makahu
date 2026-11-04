/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_layout.h"

static void launcher_deleted(lv_event_t *event)
{lv_free(lv_event_get_user_data(event));}

const char *buffer_ui_launcher_id(lv_obj_t *root)
{
  if(!root)return "";
  for(uint32_t i=0;i<lv_obj_get_event_count(root);i++){
    lv_event_dsc_t *event=lv_obj_get_event_dsc(root,i);
    if(event&&lv_event_dsc_get_cb(event)==launcher_deleted)
      return lv_event_dsc_get_user_data(event);
  }
  return "";
}

bool buffer_ui_launcher_identity(lv_obj_t *root,const char *id)
{
  if(!root||!id||!*id||*buffer_ui_launcher_id(root))return false;
  size_t bytes=strlen(id)+1;char *copy=lv_malloc(bytes);
  if(!copy)return false;
  memcpy(copy,id,bytes);
  if(!lv_obj_add_event_cb(root,launcher_deleted,LV_EVENT_DELETE,copy))
    {lv_free(copy);return false;}
  return true;
}

struct label_source { char *text; };

static void source_deleted(lv_event_t *event)
{
  struct label_source *source = lv_event_get_user_data(event);
  lv_free(source->text);
  lv_free(source);
}

const char *buffer_ui_label_source_text(lv_obj_t *label)
{
 for(uint32_t i=0;i<lv_obj_get_event_count(label);i++){
  lv_event_dsc_t *event=lv_obj_get_event_dsc(label,i);
  if(event&&lv_event_dsc_get_cb(event)==source_deleted){
   struct label_source *source=lv_event_dsc_get_user_data(event);
   if(source->text)return source->text;
  }
 }
 return lv_label_get_text(label);
}
void buffer_ui_label_set_text(lv_obj_t *label, const char *text)
{
  if (!text) return;
  struct label_source *source = NULL;
  for (uint32_t i = 0; i < lv_obj_get_event_count(label); i++)
    {
      lv_event_dsc_t *event = lv_obj_get_event_dsc(label, i);
      if (event && lv_event_dsc_get_cb(event) == source_deleted)
        { source = lv_event_dsc_get_user_data(event); break; }
    }
  if (source && source->text && !strcmp(source->text, text)) return;
  if (!source && !strcmp(lv_label_get_text(label), text)) return;
  /* LONG_DOT replaces bytes in LVGL's label buffer with dots. Comparing
   * that display buffer to the source would redraw clipped text forever.
   * Own the exact source only for these labels, and free it on deletion. */
  if (source || lv_label_get_long_mode(label) == LV_LABEL_LONG_DOT)
    {
      size_t length = strlen(text) + 1;
      char *copy = lv_malloc(length);
      if (copy)
        {
          memcpy(copy, text, length);
          if (!source)
            {
              source = lv_malloc_zeroed(sizeof(*source));
              if (source && !lv_obj_add_event_cb(label, source_deleted, LV_EVENT_DELETE, source))
                { lv_free(source); source = NULL; }
            }
          if (source)
            { lv_free(source->text); source->text = copy; }
          else lv_free(copy);
        }
      else if (source)
        { lv_free(source->text); source->text = NULL; }
    }
  lv_label_set_text(label, text);
  buffer_ui_control_label_reflow(label);
}
