/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_artwork.h"
#include <string.h>
#include <stdio.h>
#ifdef BUFFER_UI_TEST_BUILD
#include "buffer_ui_recipe_art.inc"
#else
#include "buffer_images.h"
#endif
static const unsigned char empty_pixel[1]={0};
static const lv_image_dsc_t empty_image={.header={.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_A8,.w=1,.h=1,.stride=1},.data_size=1,.data=empty_pixel};
lv_obj_t *buffer_ui_recipe_image_create(lv_obj_t *parent,int32_t radius,lv_obj_t **frame)
{
 lv_obj_t *clip=lv_obj_create(parent);lv_obj_remove_style_all(clip);
 lv_obj_set_size(clip,LV_PCT(100),LV_PCT(100));
 lv_obj_set_style_radius(clip,radius,0);lv_obj_set_style_clip_corner(clip,true,0);
 lv_obj_remove_flag(clip,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_CLICK_FOCUSABLE);
 lv_obj_t *image=lv_image_create(clip);lv_obj_set_size(image,LV_PCT(100),LV_PCT(100));lv_obj_center(image);
 lv_obj_remove_flag(image,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_CLICK_FOCUSABLE);
 *frame=clip;return image;
}
const lv_image_dsc_t *buffer_ui_recipe_artwork(const char *id)
{
#ifdef BUFFER_UI_TEST_BUILD
 return id&&!strcmp(id,"fixture-recipe-photo")?ui_recipe_art("海带红烧肉"):NULL;
#else
 (void)id;return NULL;
#endif
}
#ifndef BUFFER_UI_TEST_BUILD
struct image_binding {struct buffer_image_view view;lv_image_dsc_t source;char id[BUFFER_IMAGE_ID_BYTES];};
static void image_deleted(lv_event_t *event)
{struct image_binding *binding=lv_event_get_user_data(event);if(binding->view.lease){lv_image_cache_drop(&binding->source);buffer_images_release(binding->view.lease);}lv_free(binding);}
static struct image_binding *image_binding(lv_obj_t *image)
{
 for(uint32_t i=0;i<lv_obj_get_event_count(image);i++){
  lv_event_dsc_t *event=lv_obj_get_event_dsc(image,i);if(event&&lv_event_dsc_get_cb(event)==image_deleted)return lv_event_dsc_get_user_data(event);
 }return NULL;
}
#endif
bool buffer_ui_recipe_image_bind(lv_obj_t *image,const char *id)
{
 if(!image)return false;
#ifdef BUFFER_UI_TEST_BUILD
 const lv_image_dsc_t *source=buffer_ui_recipe_artwork(id);
 if(lv_image_get_src(image)!=(source?source:&empty_image)){lv_image_set_inner_align(image,LV_IMAGE_ALIGN_CENTER);lv_image_set_src(image,source?source:&empty_image);}return source!=NULL;
#else
 struct image_binding *binding=image_binding(image);
 if(binding&&binding->view.lease&&id&&!strcmp(binding->id,id))return true;
 if((!id||!*id)&&(!binding||!binding->view.lease)){if(lv_image_get_src(image)!=&empty_image){lv_image_set_inner_align(image,LV_IMAGE_ALIGN_CENTER);lv_image_set_src(image,&empty_image);}return false;}
 if(!binding){binding=lv_malloc_zeroed(sizeof(*binding));if(!binding)return false;if(!lv_obj_add_event_cb(image,image_deleted,LV_EVENT_DELETE,binding)){lv_free(binding);return false;}}
 /* Switch to a stable empty descriptor before releasing a reused slot. */
 if(lv_image_get_src(image)!=&empty_image){lv_image_set_inner_align(image,LV_IMAGE_ALIGN_CENTER);lv_image_set_src(image,&empty_image);}
 if(binding->view.lease){lv_image_cache_drop(&binding->source);buffer_images_release(binding->view.lease);}
 memset(&binding->view,0,sizeof(binding->view));binding->id[0]=0;
 if(!id||buffer_images_acquire(id,&binding->view))return false;
 snprintf(binding->id,sizeof(binding->id),"%s",id);
 binding->source=(lv_image_dsc_t){.header={.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_RGB565,.w=binding->view.width,.h=binding->view.height,.stride=binding->view.width*2},.data_size=binding->view.width*binding->view.height*2,.data=(const uint8_t *)binding->view.pixels};
 lv_image_set_src(image,&binding->source);return true;
#endif
}
bool buffer_ui_recipe_image_ready(lv_obj_t *image)
{return image&&lv_image_get_src(image)&&lv_image_get_src(image)!=&empty_image;}
