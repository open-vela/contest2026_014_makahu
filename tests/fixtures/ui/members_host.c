/* Production Fabric-enabled members page and Shell; transport snapshots and
 * queued commands are deterministic adapters. No live membership changes. */
#include <assert.h>
#include <stdio.h>
#include "buffer_ui.c"
#include "visual_capture.h"
#include "edge_touch_fixture.h"
static struct buffer_fabric_status_s state={.binding=0,.code=-1,.member_revision=1};
static struct buffer_fabric_member_s members[3];
static size_t count=2;static unsigned removals,confirmations,additions;
static char removed[65];
void buffer_fabric_snapshot(struct buffer_fabric_status_s *out){*out=state;}
size_t buffer_fabric_members(struct buffer_fabric_member_s *out,size_t capacity)
{assert(capacity>=count);memcpy(out,members,count*sizeof(*out));return count;}
int buffer_fabric_add_member(void){additions++;return 0;}
void buffer_fabric_cancel_add_member(void){}
int buffer_fabric_remove_member(const char *id){removals++;strcpy(removed,id);return 0;}
void buffer_fabric_confirm_member(const char *id,int code,unsigned generation,bool accepted)
{assert(!strcmp(id,state.pending_member)&&code==state.code&&generation==state.member_generation);(void)accepted;confirmations++;}
static void pump(unsigned n){while(n--){lv_tick_inc(16);lv_timer_handler();}}
static void click(lv_obj_t *button){lv_obj_send_event(button,LV_EVENT_PRESSED,NULL);lv_obj_send_event(button,LV_EVENT_RELEASED,NULL);lv_obj_send_event(button,LV_EVENT_CLICKED,NULL);}
static void inside(lv_obj_t *child,lv_obj_t *parent)
{lv_area_t a,b;lv_obj_get_coords(child,&a);lv_obj_get_coords(parent,&b);assert(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2);}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(320,240);static uint32_t pixels[1280*16];
 lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 (void)buffer_ui_typography_init();buffer_ui_motion_init();buffer_ui_motion_policy(true,false);
 g_root=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(g_root);lv_obj_set_size(g_root,LV_PCT(100),LV_PCT(100));
 lv_obj_t *desktop=lv_obj_create(g_root);lv_obj_set_size(desktop,LV_PCT(100),LV_PCT(100));buffer_ui_shell_init(g_root,desktop);
 memset(members[0].device_id,'a',64);memset(members[1].device_id,'b',64);members[0].selected=true;
 buffer_members_create();buffer_members_refresh();buffer_ui_shell_show("members");pump(10);
 click(g_members_add);assert(additions==1);state.adding_member=true;buffer_members_refresh();assert(lv_obj_has_state(g_members_add,LV_STATE_DISABLED)&&!lv_obj_has_flag(g_members_stop,LV_OBJ_FLAG_HIDDEN));
 state.adding_member=false;state.member_confirmation=true;strcpy(state.pending_member,members[1].device_id);state.member_generation=5;
 buffer_members_refresh();buffer_ui_shell_home();buffer_members_refresh();assert(!buffer_ui_shell_is_open("members"));
 state.member_generation++;buffer_members_refresh();assert(buffer_ui_shell_is_open("members"));
 lv_obj_send_event(g_members_accept,LV_EVENT_PRESSED,NULL);state.member_generation++;buffer_members_refresh();lv_obj_send_event(g_members_accept,LV_EVENT_CLICKED,NULL);assert(!confirmations);
 click(g_members_accept);assert(confirmations==1);
 state.member_confirmation=false;buffer_members_refresh();
 lv_obj_send_event(g_member_remove[0],LV_EVENT_PRESSED,NULL);struct buffer_fabric_member_s swap=members[0];members[0]=members[1];members[1]=swap;state.member_revision++;buffer_members_refresh();
 lv_obj_send_event(g_member_remove[0],LV_EVENT_CLICKED,NULL);assert(!g_member_remove_pending[0]&&!removals);
 click(g_member_remove[0]);assert(g_member_remove_pending[0]&&!removals);assert(buffer_ui_shell_back()&&buffer_ui_shell_is_open("members")&&!g_member_remove_pending[0]);
 assert(lv_group_get_focused(lv_obj_get_group(g_member_remove[0]))==g_member_remove[0]);
 click(g_member_remove[0]);swap=members[0];members[0]=members[1];members[1]=swap;state.member_revision++;buffer_members_refresh();
 assert(buffer_ui_shell_back());assert(lv_group_get_focused(lv_obj_get_group(g_member_remove[1]))==g_member_remove[1]);
 click(g_member_remove[0]);state.member_revision++;buffer_members_refresh();click(g_member_remove_commit);assert(!removals&&lv_obj_has_state(g_member_remove_commit,LV_STATE_DISABLED));assert(buffer_ui_shell_back());
 click(g_member_remove[0]);click(g_member_remove_commit);assert(removals==1&&!strcmp(removed,members[0].device_id)&&g_member_removing[0]);
 buffer_members_refresh();assert(lv_obj_has_state(g_member_remove[0],LV_STATE_DISABLED));assert(!strstr(lv_label_get_text(g_members_feedback),"已移除"));
 members[0]=members[1];count=1;state.member_revision++;buffer_members_refresh();assert(!g_member_removing[0]&&strstr(lv_label_get_text(g_members_feedback),"已移除"));
 const int sizes[][2]={{320,240},{240,320},{240,240},{480,320},{320,480},{800,480},{480,800},{1280,720},{200,240}};
 for(unsigned i=0;i<sizeof(sizes)/sizeof(sizes[0]);i++){
  lv_display_set_resolution(display,sizes[i][0],sizes[i][1]);pump(80);buffer_members_refresh();pump(3);
  inside(lv_obj_get_child(g_members_panel,0),g_members_panel);inside(g_members_body,g_members_panel);assert(lv_obj_get_height(g_members_body)>0);
  screenshot("members",sizes[i][0],sizes[i][1]);click(g_member_remove[0]);pump(5);
  inside(g_member_remove_panel,g_members_panel);inside(lv_obj_get_parent(g_member_remove_commit),g_member_remove_panel);assert(lv_obj_get_height(lv_obj_get_parent(g_member_remove_copy))>0);
  inside(g_member_remove_commit,lv_obj_get_parent(g_member_remove_commit));inside(lv_obj_get_child(lv_obj_get_parent(g_member_remove_commit),0),lv_obj_get_parent(g_member_remove_commit));
  inside(lv_obj_get_child(g_member_remove_commit,0),g_member_remove_commit);
  screenshot("members-remove",sizes[i][0],sizes[i][1]);edge_touch_back(g_root,i%2);assert(buffer_ui_shell_is_open("members")&&!g_member_remove_pending[0]);pump(80);
  unsigned before=flushes;buffer_members_refresh();pump(3);assert(flushes==before);
 }
 edge_touch_back(g_root,false);assert(!buffer_ui_shell_is_open("members"));buffer_members_refresh();assert(!buffer_ui_shell_is_open("members"));
 buffer_ui_shell_shutdown();lv_obj_delete(g_root);g_root=NULL;buffer_ui_motion_shutdown();buffer_ui_typography_shutdown();lv_display_delete(display);lv_deinit();
 puts("PASS: members page, stable removal confirmation, published completion, confirmation epoch fencing, no repeated navigation hijack and adaptive bounds");
}
