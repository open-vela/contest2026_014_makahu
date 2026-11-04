/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_desktop.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_typography.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#define MAX_CROSS 4
#define MAX_MAIN (BUFFER_DESKTOP_TILES*4)
struct desktop_tile {
 struct buffer_ui_desktop *desktop;
 lv_obj_t *root;
 char key[BUFFER_DESKTOP_KEY],laid_key[BUFFER_DESKTOP_KEY];
 unsigned columns,rows;
 unsigned preferred_width_dp;
 int32_t primary,cross,width,height;
 bool visible;
};
struct buffer_ui_desktop {
 lv_obj_t *viewport,*window;
 struct desktop_tile tiles[BUFFER_DESKTOP_TILES];
 char anchor[BUFFER_DESKTOP_KEY];
 int32_t width,height;
 unsigned dpi,scale;
 bool horizontal,dirty,arranging,pending,strip;
 buffer_desktop_laid_out laid_out;void *context;
};
static int32_t scroll(struct buffer_ui_desktop *d)
{return d->horizontal?lv_obj_get_scroll_x(d->viewport):lv_obj_get_scroll_y(d->viewport);}
static bool pressed(void)
{
 for(lv_indev_t *i=lv_indev_get_next(NULL);i;i=lv_indev_get_next(i))
  if(lv_indev_get_state(i)==LV_INDEV_STATE_PRESSED)return true;
 return false;
}
static struct desktop_tile *nearest(struct buffer_ui_desktop *d)
{
 struct desktop_tile *best=NULL;int32_t distance=INT32_MAX,position=scroll(d);
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++){
  struct desktop_tile *t=&d->tiles[i];if(!t->root||!t->visible||!t->laid_key[0])continue;
  int32_t delta=abs(t->primary-position);
  if(delta<distance){distance=delta;best=t;}
 }
 return best;
}
static void remember(struct buffer_ui_desktop *d)
{struct desktop_tile *t=nearest(d);if(t)strcpy(d->anchor,t->laid_key);}
static void tile_event(lv_event_t *event)
{
 struct desktop_tile *tile=lv_event_get_user_data(event);struct buffer_ui_desktop *d=tile->desktop;
 if(lv_event_get_code(event)==LV_EVENT_DELETE){
  memset(tile,0,sizeof(*tile));d->dirty=true;
 }else if(!d->arranging&&tile->key[0])strcpy(d->anchor,tile->key);
}
static void deferred_reflow(void *context)
{
 struct buffer_ui_desktop *d=context;
 /* SIZE_CHANGED is dispatched inside LVGL's layout pass. A nested
  * update_layout is ignored there; finish that pass before measuring scroll
  * extents or restoring anchors against the new object coordinates. */
 lv_obj_update_layout(d->viewport);d->pending=false;
 (void)buffer_ui_desktop_reflow(d);
}
static void viewport_event(lv_event_t *event)
{
 struct buffer_ui_desktop *d=lv_event_get_user_data(event);lv_event_code_t code=lv_event_get_code(event);
 if(code==LV_EVENT_DELETE)buffer_ui_desktop_destroy(d);
 else if(!d->arranging&&code==LV_EVENT_SIZE_CHANGED){
  d->dirty=true;if(!d->pending&&lv_async_call(deferred_reflow,d)==LV_RESULT_OK)d->pending=true;
 }
 else if(!d->arranging&&!d->pending&&code==LV_EVENT_SCROLL_END)remember(d);
}
struct buffer_ui_desktop *buffer_ui_desktop_create(lv_obj_t *viewport,lv_obj_t *window,
                        buffer_desktop_laid_out laid_out,void *context)
{
 if(!viewport)return NULL;
 struct buffer_ui_desktop *d=lv_malloc_zeroed(sizeof(*d));if(!d)return NULL;
 d->viewport=viewport;d->window=window?window:viewport;d->laid_out=laid_out;d->context=context;d->dirty=true;
 lv_obj_set_layout(viewport,LV_LAYOUT_NONE);lv_obj_set_style_pad_all(viewport,0,0);
 lv_obj_add_flag(viewport,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_remove_flag(viewport,LV_OBJ_FLAG_SCROLL_CHAIN|LV_OBJ_FLAG_SCROLL_ONE);
 lv_obj_add_event_cb(viewport,viewport_event,LV_EVENT_ALL,d);return d;
}
void buffer_ui_desktop_destroy(struct buffer_ui_desktop *d)
{
 if(!d)return;
 if(d->pending)lv_async_call_cancel(deferred_reflow,d);
 lv_obj_remove_event_cb(d->viewport,viewport_event);
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(d->tiles[i].root)lv_obj_remove_event_cb(d->tiles[i].root,tile_event);
 lv_free(d);
}
int buffer_ui_desktop_bind(struct buffer_ui_desktop *d,lv_obj_t *content,
                           const char *key,unsigned columns,unsigned rows)
{
 if(!d||!content||!key||strlen(key)>=BUFFER_DESKTOP_KEY||!columns||!rows||columns>4||rows>4||lv_obj_get_parent(content)!=d->viewport)return -EINVAL;
 struct desktop_tile *tile=NULL;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++){
  struct desktop_tile *t=&d->tiles[i];
  if(t->root==content)tile=t;
  else if(*key&&t->root&&!strcmp(t->key,key))return -EEXIST;
 }
 if(!tile)for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(!d->tiles[i].root){tile=&d->tiles[i];break;}
 if(!tile)return -ENOSPC;
 if(tile->root&&!strcmp(tile->key,key)&&tile->columns==columns&&tile->rows==rows)return 0;
 if(!tile->root){
  tile->root=content;tile->desktop=d;
  lv_obj_set_align(content,LV_ALIGN_TOP_LEFT);lv_obj_set_flex_grow(content,0);
  lv_obj_add_flag(content,LV_OBJ_FLAG_SNAPPABLE);
  lv_obj_add_event_cb(content,tile_event,LV_EVENT_DELETE,tile);
  lv_obj_add_event_cb(content,tile_event,LV_EVENT_PRESSED,tile);
 }
 strcpy(tile->key,key);tile->columns=columns;tile->rows=rows;d->dirty=true;return 0;
}
void buffer_ui_desktop_strip(struct buffer_ui_desktop *d,bool enabled)
{if(d&&d->strip!=enabled){d->strip=enabled;d->dirty=true;}}
int buffer_ui_desktop_panel(struct buffer_ui_desktop *d,lv_obj_t *content,const char *key,unsigned width)
{
 if(width<48||width>1024)return -EINVAL;
 int result=buffer_ui_desktop_bind(d,content,key,1,1);if(result)return result;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(d->tiles[i].root==content){
  if(d->tiles[i].preferred_width_dp!=width){d->tiles[i].preferred_width_dp=width;d->dirty=true;}return 0;
 }
 return -ENOENT;
}
static void restore(struct buffer_ui_desktop *d,const char *key,int32_t offset)
{
 struct desktop_tile *selected=NULL;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(d->tiles[i].visible&&!strcmp(d->tiles[i].laid_key,key)){selected=&d->tiles[i];break;}
 if(!selected){remember(d);return;}
 int32_t target=selected->primary+offset;if(target<0)target=0;
 int32_t maximum=d->horizontal?lv_obj_get_scroll_x(d->viewport)+lv_obj_get_scroll_right(d->viewport):lv_obj_get_scroll_y(d->viewport)+lv_obj_get_scroll_bottom(d->viewport);
 if(maximum<0)maximum=0;
 if(target>maximum)target=maximum;
 if(d->horizontal)lv_obj_scroll_to(d->viewport,target,0,LV_ANIM_OFF);
 else lv_obj_scroll_to(d->viewport,0,target,LV_ANIM_OFF);
 strcpy(d->anchor,selected->laid_key);
}
bool buffer_ui_desktop_reflow(struct buffer_ui_desktop *d)
{
 if(!d||d->arranging||!lv_obj_is_visible(d->viewport)||pressed()||lv_obj_is_scrolling(d->viewport))return false;
 int32_t w=lv_obj_get_content_width(d->viewport),h=lv_obj_get_content_height(d->viewport);
 if(w<=0||h<=0)return false;
 unsigned dpi=lv_display_get_dpi(lv_obj_get_display(d->viewport)),scale=buffer_ui_font_scale();
 bool horizontal=lv_obj_get_width(d->window)>lv_obj_get_height(d->window);
 /* Shell reservations reduce available space, not the window orientation. */
 bool changed=d->dirty||w!=d->width||h!=d->height||dpi!=d->dpi||scale!=d->scale;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++){
  struct desktop_tile *t=&d->tiles[i];
  bool visible=t->root&&t->key[0]&&!lv_obj_has_flag(t->root,LV_OBJ_FLAG_HIDDEN);
  if(visible!=t->visible)changed=true;
 }
 if(!changed)return false;
 char anchor[BUFFER_DESKTOP_KEY]="";int32_t offset=0,old_position=0;
 bool rotating=d->width&&d->horizontal!=horizontal;
 struct desktop_tile *old=nearest(d);
 if(rotating&&d->anchor[0]){
  for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(d->tiles[i].visible&&!strcmp(d->tiles[i].laid_key,d->anchor)){old=&d->tiles[i];break;}
 }
 if(old){strcpy(anchor,old->laid_key);old_position=old->primary;offset=rotating?0:scroll(d)-old_position;}
 /* A removed/rebound anchor falls to a surviving neighbour, never the new
  * content that happened to reuse its LVGL object or the first grid page. */
 bool retained=false;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++){
  struct desktop_tile *t=&d->tiles[i];
  if(t->root&&!lv_obj_has_flag(t->root,LV_OBJ_FLAG_HIDDEN)&&!strcmp(t->key,anchor))retained=true;
 }
 if(anchor[0]&&!retained){
  struct desktop_tile *best=NULL;int32_t distance=INT32_MAX;
  for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++){
   struct desktop_tile *t=&d->tiles[i];
   if(!t->root||!t->visible||lv_obj_has_flag(t->root,LV_OBJ_FLAG_HIDDEN)||strcmp(t->key,t->laid_key))continue;
   int32_t delta=abs(t->primary-old_position);
   if(delta<distance||(delta==distance&&best&&best->primary<old_position&&t->primary>=old_position)){best=t;distance=delta;}
  }
  if(best){strcpy(anchor,best->key);offset=0;}else anchor[0]=0;
 }
 d->arranging=true;d->horizontal=horizontal;
 int32_t gap=buffer_ui_dp(d->strip?10:12),main_size=horizontal?w:h,cross_size=horizontal?h:w;
 int32_t cross_min=buffer_ui_dp(horizontal?(scale>125?144:112):136);
 unsigned cross_count=(cross_size+gap)/(cross_min+gap);if(!cross_count)cross_count=1;if(cross_count>MAX_CROSS)cross_count=MAX_CROSS;
 int32_t cross_unit=(cross_size-(cross_count-1)*gap)/(int32_t)cross_count;
 int32_t main_unit=buffer_ui_dp(horizontal?160:(scale>125?160:128));if(main_unit>main_size)main_unit=main_size;
 unsigned main_span_max=(main_size+gap)/(main_unit+gap);if(!main_span_max)main_span_max=1;
 uint8_t occupied[MAX_MAIN]={0};
 int32_t strip_position=0;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++){
  struct desktop_tile *t=&d->tiles[i];t->visible=t->root&&t->key[0]&&!lv_obj_has_flag(t->root,LV_OBJ_FLAG_HIDDEN);
  if(!t->visible){t->laid_key[0]=0;continue;}
  if(d->strip){
   int32_t length=buffer_ui_dp(horizontal?(t->preferred_width_dp?t->preferred_width_dp:192):240)*(int32_t)scale/100;
   if(length>main_size)length=main_size;
   t->primary=strip_position;t->cross=0;t->width=horizontal?length:cross_size;t->height=horizontal?cross_size:length;
   lv_obj_set_pos(t->root,horizontal?strip_position:0,horizontal?0:strip_position);lv_obj_set_size(t->root,t->width,t->height);
   strcpy(t->laid_key,t->key);strip_position+=length+gap;continue;
  }
  unsigned a=horizontal?t->columns:t->rows,b=horizontal?t->rows:t->columns;
  if(a>main_span_max)a=main_span_max;
  if(b>cross_count)b=cross_count;
  unsigned primary=0,cross=0;bool found=false;
  for(primary=0;primary+a<=MAX_MAIN&&!found;primary++)for(cross=0;cross+b<=cross_count;cross++){
   unsigned mask=((1u<<b)-1)<<cross;bool free=true;
   for(unsigned n=0;n<a;n++)if(occupied[primary+n]&mask)free=false;
   if(!free)continue;
   for(unsigned n=0;n<a;n++)occupied[primary+n]|=mask;
   found=true;break;
  }
  if(!found)continue;
  primary--;
  int32_t p=(int32_t)primary*(main_unit+gap),c=(int32_t)cross*(cross_unit+gap);
  int32_t main_length=(int32_t)a*main_unit+(a-1)*gap,cross_length=(int32_t)b*cross_unit+(b-1)*gap;
  t->primary=p;t->cross=c;t->width=horizontal?main_length:cross_length;t->height=horizontal?cross_length:main_length;
  lv_obj_set_pos(t->root,horizontal?p:c,horizontal?c:p);lv_obj_set_size(t->root,t->width,t->height);
  strcpy(t->laid_key,t->key);
 }
 lv_obj_set_scroll_dir(d->viewport,horizontal?LV_DIR_HOR:LV_DIR_VER);
 lv_obj_set_scroll_snap_x(d->viewport,horizontal?LV_SCROLL_SNAP_START:LV_SCROLL_SNAP_NONE);
 lv_obj_set_scroll_snap_y(d->viewport,horizontal?LV_SCROLL_SNAP_NONE:LV_SCROLL_SNAP_START);
 /* Mixed footprints can backfill a hole before an earlier-created tile.
  * Match the object reading order to the laid-out primary/cross coordinates;
  * Shell then preserves this order for keyboard traversal. Stable slots and
  * keys remain unchanged, and hidden tiles stay after visible content. */
 struct desktop_tile *ordered[BUFFER_DESKTOP_TILES];unsigned count=0;
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(d->tiles[i].visible){
  struct desktop_tile *tile=&d->tiles[i];unsigned at=count;
  while(at&&(ordered[at-1]->primary>tile->primary||
    (ordered[at-1]->primary==tile->primary&&ordered[at-1]->cross>tile->cross))){ordered[at]=ordered[at-1];at--;}
  ordered[at]=tile;count++;
 }
 for(unsigned i=0;i<count;i++)if(lv_obj_get_index(ordered[i]->root)!=(int32_t)i)lv_obj_move_to_index(ordered[i]->root,(int32_t)i);
 lv_obj_update_layout(d->viewport);
 if(d->laid_out)d->laid_out(d->context);
 lv_obj_update_layout(d->viewport);
 restore(d,anchor,offset);
 d->width=w;d->height=h;d->dpi=dpi;d->scale=scale;d->dirty=false;d->arranging=false;
 return true;
}
bool buffer_ui_desktop_reveal(struct buffer_ui_desktop *d,const char *key)
{
 if(!d||!key)return false;
 lv_obj_update_layout(d->viewport);
 (void)buffer_ui_desktop_reflow(d);
 for(unsigned i=0;i<BUFFER_DESKTOP_TILES;i++)if(d->tiles[i].visible&&!strcmp(d->tiles[i].laid_key,key)){
  d->arranging=true;restore(d,key,0);d->arranging=false;return true;
 }
 return false;
}
const char *buffer_ui_desktop_anchor(struct buffer_ui_desktop *d){return d?d->anchor:"";}
