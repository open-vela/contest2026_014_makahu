"""Compile and exercise the production assistant domain and A2UI model on host."""
from host_support import PROJECT_ROOT, SDK_ROOT, CC

from pathlib import Path
import json
import subprocess
import tempfile


ROOT = PROJECT_ROOT


HARNESS = r'''
#include "buffer_a2ui_internal.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static void check_reply_budget(void)
{
  char text[BUFFER_A2UI_REPLY_TEXT_MAX + 1];
  memset(text, 0, sizeof(text));
  for (size_t i = 0; i < 341; i++) memcpy(text + i * 3, "汉", 3);
  text[1023] = 'x';
  text[1024] = 0;
  assert(buffer_a2ui_present_reply("reply-max", text) == 0);
  struct buffer_a2ui_surface_s surface;
  assert(buffer_a2ui_model_get_active(&surface) == 0);
  assert(surface.full_screen && surface.component_count == 11);
  const struct buffer_a2ui_component_s *root =
      buffer_a2ui_find_component(&surface, "root");
  assert(root != NULL && root->child_count == 10);
  char reconstructed[BUFFER_A2UI_REPLY_TEXT_MAX + 1] = {0};
  size_t offset = 0;
  for (size_t i = 1; i < root->child_count; i++)
    {
      const struct buffer_a2ui_component_s *piece =
          &surface.components[root->children[i]];
      size_t length = strlen(piece->text);
      assert(offset + length <= BUFFER_A2UI_REPLY_TEXT_MAX);
      memcpy(reconstructed + offset, piece->text, length);
      offset += length;
    }
  reconstructed[offset] = 0;
  assert(offset == BUFFER_A2UI_REPLY_TEXT_MAX);
  assert(memcmp(reconstructed, text, offset) == 0);

  char too_long[BUFFER_A2UI_REPLY_TEXT_MAX + 2];
  memset(too_long, 'a', sizeof(too_long) - 1);
  too_long[sizeof(too_long) - 1] = 0;
  assert(buffer_a2ui_present_reply("reply-too-long", too_long) == -E2BIG);
  assert(buffer_a2ui_present_reply("reply-invalid", "\xc0\xaf") == -EINVAL);
}

static void check_protocol_subset(void)
{
  static const char create[] =
    "{\"version\":\"v0.9.1\",\"createSurface\":{"
    "\"surfaceId\":\"fixture-surface\","
    "\"catalogId\":\"https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json\"}}";
  static const char components[] =
    "{\"version\":\"v0.9.1\",\"updateComponents\":{"
    "\"surfaceId\":\"fixture-surface\",\"components\":["
    "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"label\"]},"
    "{\"id\":\"label\",\"component\":\"Text\",\"text\":{\"path\":\"/title\"}}]}}";
  static const char data[] =
    "{\"version\":\"v0.9.1\",\"updateDataModel\":{"
    "\"surfaceId\":\"fixture-surface\",\"path\":\"/title\",\"value\":\"ready\"}}";
  static const char unknown_component[] =
    "{\"version\":\"v0.9.1\",\"updateComponents\":{"
    "\"surfaceId\":\"fixture-surface\",\"components\":["
    "{\"id\":\"extra\",\"component\":\"UnboundedWidget\"}]}}";
  static const char invalid_button_child[] =
    "{\"version\":\"v0.9.1\",\"updateComponents\":{"
    "\"surfaceId\":\"fixture-surface\",\"components\":["
    "{\"id\":\"action\",\"component\":\"Button\","
    "\"child\":\"not_text\",\"action\":{\"event\":{\"name\":\"tap\"}}},"
    "{\"id\":\"not_text\",\"component\":\"Column\",\"children\":[]}]}}";
  static const char cycle[] =
    "{\"version\":\"v0.9.1\",\"updateComponents\":{"
    "\"surfaceId\":\"fixture-surface\",\"components\":["
    "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"label\"]},"
    "{\"id\":\"label\",\"component\":\"Column\",\"children\":[\"root\"]}]}}";
  struct buffer_a2ui_surface_s before;
  struct buffer_a2ui_surface_s after;
  assert(buffer_a2ui_apply_message(create, sizeof(create) - 1, 1, true) == 0);
  assert(buffer_a2ui_apply_message(components, sizeof(components) - 1, 1, false) == 0);
  assert(buffer_a2ui_apply_message(data, sizeof(data) - 1, 2, false) == 0);
  assert(buffer_a2ui_model_get_surface("fixture-surface", &before) == 0);
  const struct buffer_a2ui_component_s *label =
      buffer_a2ui_find_component(&before, "label");
  assert(label != NULL && strcmp(buffer_a2ui_component_text(&before, label),
                                 "ready") == 0);
  assert(buffer_a2ui_apply_message(unknown_component,
      sizeof(unknown_component) - 1, 3, false) == -ENOTSUP);
  assert(buffer_a2ui_apply_message(invalid_button_child,
      sizeof(invalid_button_child) - 1, 3, false) == -ENOTSUP);
  assert(buffer_a2ui_apply_message(cycle, sizeof(cycle) - 1, 3, false) == -ELOOP);
  assert(buffer_a2ui_model_get_surface("fixture-surface", &after) == 0);
  assert(after.sequence == before.sequence && after.component_count == 2);
  assert(buffer_a2ui_apply_message(data, sizeof(data) - 1, 1, false) == -ESTALE);
  assert(buffer_a2ui_delete_surface("fixture-surface", 4) == 0);
}

static void check_recipe_and_timer_close(void)
{
  struct buffer_assistant_request_s request = {0};
  struct buffer_assistant_result_s result;
  struct buffer_assistant_item_s items[BUFFER_ASSISTANT_MAX_TIMERS];
  size_t before_count;
  size_t after_count;
  request.operation = BUFFER_ASSISTANT_RECIPE_SHOW;
  strcpy(request.operation_id, "recipe-show");
  strcpy(request.recipe_title, "炖豆腐");
  strcpy(request.ingredients, "豆腐、番茄");
  request.step_count = 1;
  strcpy(request.steps[0].title, "小火炖煮");
  strcpy(request.steps[0].detail, "炖至入味");
  request.steps[0].timer_ms = 5000;
  assert(buffer_assistant_snapshot(BUFFER_ASSISTANT_EVENT_TIMER, items,
      BUFFER_ASSISTANT_MAX_TIMERS, &before_count) == 0);
  assert(buffer_assistant_execute(&request, &result) == 0);
  assert(buffer_assistant_snapshot(BUFFER_ASSISTANT_EVENT_TIMER, items,
      BUFFER_ASSISTANT_MAX_TIMERS, &after_count) == 0);
  assert(after_count == before_count);

  struct buffer_a2ui_surface_s surface;
  assert(buffer_a2ui_model_get_active(&surface) == 0 && surface.full_screen);
  const struct buffer_a2ui_component_s *step =
      buffer_a2ui_find_component(&surface, "step1");
  assert(step != NULL && step->type == BUFFER_A2UI_RECIPE_STEP);
  assert(step->payload.recipe_step.timer_ms == 5000);
  assert(step->payload.recipe_step.timer_id[0] == 0);

  memset(&request, 0, sizeof(request));
  request.operation = BUFFER_ASSISTANT_TIMER_CREATE;
  strcpy(request.operation_id, step->payload.recipe_step.request_id);
  strcpy(request.label, step->payload.recipe_step.title);
  request.duration_ms = step->payload.recipe_step.timer_ms;
  assert(buffer_assistant_execute(&request, &result) == 0);
  assert(buffer_a2ui_model_bind_timer(surface.id, "step1", result.object_id,
      surface.task_revision+1,step->payload.recipe_step.request_id) == -ESTALE);
  assert(buffer_a2ui_model_bind_timer(surface.id, "step1", result.object_id,
      surface.task_revision,step->payload.recipe_step.request_id) == 0);
  assert(buffer_a2ui_model_activate(surface.id) == 0);
  assert(buffer_a2ui_model_get_active(&surface) == 0);
  step = buffer_a2ui_find_component(&surface, "step1");
  assert(step != NULL && strcmp(step->payload.recipe_step.timer_id,
                                 result.object_id) == 0);

  memset(&request, 0, sizeof(request));
  request.operation = BUFFER_ASSISTANT_TIMER_CREATE;
  strcpy(request.operation_id, "timer-page-closed");
  strcpy(request.label, "关闭页面后继续");
  request.duration_ms = 1000;
  assert(buffer_assistant_execute(&request, &result) == 0);
  struct buffer_a2ui_surface_s closed_surface;
  assert(buffer_a2ui_model_get_active(&closed_surface) == 0);
  char closed_surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
  strcpy(closed_surface_id, closed_surface.id);
  buffer_a2ui_close_active();
  assert(buffer_a2ui_model_get_active(&closed_surface) == -ENOENT);
  assert(buffer_a2ui_model_get_surface(closed_surface_id,
                                       &closed_surface) == -ENOENT);
  bool fired = false;
  for (int i = 0; i < 30; i++)
    {
      usleep(100000);
      struct buffer_assistant_item_s item;
      if (buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,
                               result.object_id, &item) == 0 &&
          item.state == BUFFER_ASSISTANT_FIRED)
        {
          fired = true;
          break;
        }
    }
  assert(fired && "timer did not fire after its surface was closed");

  /* The persistent due notice can explicitly reopen the dismissed card. */
  assert(buffer_a2ui_present_timer(result.object_id) == 0);
  assert(buffer_a2ui_model_get_active(&closed_surface) == 0);
  assert(buffer_a2ui_find_component(&closed_surface, "timer") != NULL);

  memset(&request, 0, sizeof(request));
  request.operation = BUFFER_ASSISTANT_TIMER_CANCEL;
  strcpy(request.operation_id, "timer-dismiss-fired");
  strcpy(request.object_id, result.object_id);
  assert(buffer_assistant_execute(&request, &result) == 0);
  struct buffer_assistant_item_s dismissed;
  assert(buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,
                              request.object_id, &dismissed) == 0);
  assert(dismissed.state == BUFFER_ASSISTANT_CANCELLED);
}

static void check_official_sample(const char *path)
{
  FILE *input = fopen(path, "r");
  assert(input != NULL);
  char *line = NULL;
  size_t capacity = 0;
  ssize_t length;
  uint64_t revision = 0;
  while ((length = getline(&line, &capacity, input)) >= 0)
    {
      revision++;
      int ret = buffer_a2ui_apply_message(line, (size_t)length, revision,
                                          revision == 1);
      assert(ret == 0);
    }
  free(line);
  fclose(input);
  assert(revision == 2);

  struct buffer_a2ui_surface_s surface;
  assert(buffer_a2ui_model_get_active(&surface) == 0);
  assert(strcmp(surface.catalog_id,
      "https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json") == 0);
  const struct buffer_a2ui_component_s *root =
      buffer_a2ui_find_component(&surface, "root");
  const struct buffer_a2ui_component_s *button =
      buffer_a2ui_find_component(&surface, "action_button");
  assert(root != NULL && root->type == BUFFER_A2UI_COLUMN &&
         root->child_count == 2);
  assert(button != NULL && button->type == BUFFER_A2UI_BUTTON &&
         strcmp(button->action, "button_clicked") == 0);
  assert(strcmp(buffer_a2ui_component_text(&surface, button), "Click Me") == 0);
  puts("PASS: official interactive-button sample maps to native Column/Text/Button");
}

static void create_durable_timer(void)
{
  struct buffer_assistant_request_s request = {0};
  struct buffer_assistant_result_s first;
  struct buffer_assistant_result_s replay;
  struct buffer_assistant_item_s item;
  request.operation = BUFFER_ASSISTANT_TIMER_CREATE;
  strcpy(request.operation_id, "durable-timer-create");
  strcpy(request.label, "持久计时");
  request.duration_ms = 30000;
  assert(buffer_assistant_execute(&request, &first) == 0);
  assert(buffer_assistant_execute(&request, &replay) == 0);
  assert(replay.duplicate && strcmp(first.object_id, replay.object_id) == 0);
  assert(buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,
                              first.object_id, &item) == 0);
  assert(item.state == BUFFER_ASSISTANT_RUNNING);
  request.duration_ms++;
  assert(buffer_assistant_execute(&request, &replay) == -EALREADY);
  memset(&request,0,sizeof(request));strcpy(request.object_id,first.object_id);
  request.operation=BUFFER_ASSISTANT_TIMER_PAUSE;strcpy(request.operation_id,"durable-timer-pause");
  usleep(30000);assert(!buffer_assistant_execute(&request,&replay));assert(replay.state==BUFFER_ASSISTANT_PAUSED&&replay.remaining_ms<30000);
  request.operation=BUFFER_ASSISTANT_TIMER_RESTART;strcpy(request.operation_id,"durable-timer-restart");
  assert(!buffer_assistant_execute(&request,&replay));assert(!replay.duplicate&&replay.state==BUFFER_ASSISTANT_RUNNING&&replay.remaining_ms==30000&&!strcmp(replay.object_id,first.object_id));
  assert(!buffer_assistant_execute(&request,&replay)&&replay.duplicate);
  printf("DURABLE_TIMER_ID=%s\n", first.object_id);
  memset(&request,0,sizeof(request));request.operation=BUFFER_ASSISTANT_TIMER_CREATE;
  strcpy(request.operation_id,"recipe-0123456789abcdef-1");strcpy(request.label,"菜谱步骤计时");request.duration_ms=30000;
  assert(!buffer_assistant_execute(&request,&replay));
  assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,replay.object_id,&item)&&item.recipe_step);
}

static void check_alarm_domain(void)
{
  struct buffer_assistant_request_s request = {0};
  struct buffer_assistant_result_s result;
  struct buffer_assistant_item_s item;
  request.operation = BUFFER_ASSISTANT_ALARM_CREATE;
  strcpy(request.operation_id, "alarm-cancel-create");
  strcpy(request.label, "取消测试");
  request.scheduled_at_unix_ms = (int64_t)time(NULL) * 1000 + 600000;
  assert(buffer_assistant_execute(&request, &result) == 0);
  struct buffer_assistant_result_s replay;
  assert(buffer_assistant_execute(&request, &replay) == 0);
  assert(replay.duplicate && strcmp(replay.object_id, result.object_id) == 0);
  memset(&request, 0, sizeof(request));
  request.operation = BUFFER_ASSISTANT_ALARM_CANCEL;
  strcpy(request.operation_id, "alarm-cancel-action");
  strcpy(request.object_id, result.object_id);
  assert(buffer_assistant_execute(&request, &replay) == 0);
  assert(buffer_assistant_get(BUFFER_ASSISTANT_EVENT_ALARM,
                              result.object_id, &item) == 0);
  assert(item.state == BUFFER_ASSISTANT_CANCELLED);

  memset(&request, 0, sizeof(request));
  request.operation = BUFFER_ASSISTANT_ALARM_CREATE;
  strcpy(request.operation_id, "alarm-expire-create");
  strcpy(request.label, "到期测试");
  request.scheduled_at_unix_ms = (int64_t)time(NULL) * 1000 + 2500;
  assert(buffer_assistant_execute(&request, &result) == 0);
  memset(&item, 0, sizeof(item));
  for (int i = 0; i < 40; i++)
    {
      usleep(100000);
      if (buffer_assistant_get(BUFFER_ASSISTANT_EVENT_ALARM,
                               result.object_id, &item) == 0 &&
          item.state == BUFFER_ASSISTANT_FIRED) break;
    }
  assert(item.state == BUFFER_ASSISTANT_FIRED &&
         "alarm did not transition to fired at its wall deadline");
  assert(buffer_a2ui_present_alarm(result.object_id) == 0);
  struct buffer_a2ui_surface_s alarm_surface;
  assert(buffer_a2ui_model_get_active(&alarm_surface) == 0);
  const struct buffer_a2ui_component_s *alarm =
      buffer_a2ui_find_component(&alarm_surface, "alarm");
  assert(alarm && alarm->payload.alarm.state == BUFFER_ASSISTANT_FIRED);
  memset(&request, 0, sizeof(request));
  request.operation = BUFFER_ASSISTANT_ALARM_CANCEL;
  strcpy(request.operation_id, "alarm-expire-dismiss");
  strcpy(request.object_id, result.object_id);
  assert(buffer_assistant_execute(&request, &replay) == 0);
  assert(replay.state == BUFFER_ASSISTANT_CANCELLED &&
         strcmp(replay.object_id, result.object_id) == 0);
  assert(buffer_assistant_get(BUFFER_ASSISTANT_EVENT_ALARM,
                              result.object_id, &item) == 0);
  assert(item.state == BUFFER_ASSISTANT_CANCELLED);
  assert(buffer_a2ui_model_get_active(&alarm_surface) == 0);
  alarm = buffer_a2ui_find_component(&alarm_surface, "alarm");
  assert(alarm && alarm->payload.alarm.state == BUFFER_ASSISTANT_CANCELLED);
  assert(buffer_assistant_execute(&request, &replay) == 0 && replay.duplicate);
  assert(buffer_assistant_get(BUFFER_ASSISTANT_EVENT_ALARM,
                              result.object_id, &item) == 0);
  assert(item.state == BUFFER_ASSISTANT_CANCELLED);
}

static void verify_durable_timer(const char *timer_id)
{
  struct buffer_assistant_request_s request = {0};
  struct buffer_assistant_result_s replay;
  struct buffer_assistant_item_s item;
  request.operation = BUFFER_ASSISTANT_TIMER_CREATE;
  strcpy(request.operation_id, "durable-timer-create");
  strcpy(request.label, "持久计时");
  request.duration_ms = 30000;
  assert(buffer_assistant_execute(&request, &replay) == 0);
  assert(replay.duplicate && strcmp(replay.object_id, timer_id) == 0);
  assert(buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER, timer_id, &item) == 0);
  assert(item.state == BUFFER_ASSISTANT_RUNNING && item.remaining_ms > 0 &&
         item.remaining_ms <= 30000);
  memset(&request,0,sizeof(request));request.operation=BUFFER_ASSISTANT_TIMER_RESTART;
  strcpy(request.operation_id,"durable-timer-restart");strcpy(request.object_id,timer_id);
  assert(!buffer_assistant_execute(&request,&replay)&&replay.duplicate&&!strcmp(replay.object_id,timer_id));
  struct buffer_assistant_item_s after;assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,timer_id,&after));
  assert(after.remaining_ms<=item.remaining_ms);
  memset(&request,0,sizeof(request));request.operation=BUFFER_ASSISTANT_TIMER_CREATE;
  strcpy(request.operation_id,"recipe-0123456789abcdef-1");strcpy(request.label,"菜谱步骤计时");request.duration_ms=30000;
  assert(!buffer_assistant_execute(&request,&replay)&&replay.duplicate);
  assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,replay.object_id,&item)&&item.recipe_step);
}

static unsigned generic_calls;
static char generic_first_id[64],generic_last_id[64];
static int generic_action(const struct buffer_a2ui_action_s *event,void *context)
{
  (void)context;assert(!strcmp(event->name,"button_clicked"));
  if(!generic_calls)strcpy(generic_first_id,event->id);
  strcpy(generic_last_id,event->id);__atomic_add_fetch(&generic_calls,1,__ATOMIC_RELEASE);
  return generic_calls==1?-ETIMEDOUT:0;
}
static void check_generic_actions(void)
{
  assert(!buffer_a2ui_set_action_handler(generic_action,NULL));
  struct buffer_a2ui_surface_s surface;assert(!buffer_a2ui_model_get_active(&surface));
  const struct buffer_a2ui_component_s *button=buffer_a2ui_find_component(&surface,"action_button");assert(button);
  assert(buffer_a2ui_model_emit_action(surface.id,button->id,"forged",button->context_json)==-ESTALE);
  assert(buffer_a2ui_model_emit_action(surface.id,button->id,button->action,"{\"forged\":1}")==-ESTALE);
  assert(!buffer_a2ui_model_emit_action(surface.id,button->id,button->action,button->context_json));
  struct buffer_a2ui_action_status_s status;
  for(unsigned i=0;i<1000;i++){assert(!buffer_a2ui_model_action_status(surface.id,button->id,&status));if(!status.pending)break;usleep(1000);}
  assert(!status.pending&&status.result==-ETIMEDOUT&&generic_calls==1);
  assert(!buffer_a2ui_model_emit_action(surface.id,button->id,button->action,button->context_json));
  assert(buffer_a2ui_model_emit_action(surface.id,button->id,button->action,button->context_json)==-EALREADY);
  for(unsigned i=0;i<1000&&__atomic_load_n(&generic_calls,__ATOMIC_ACQUIRE)<2;i++)usleep(1000);
  assert(generic_calls==2&&!strcmp(generic_first_id,generic_last_id));
  assert(!buffer_a2ui_model_action_status(surface.id,button->id,&status)&&status.pending);
  buffer_a2ui_action_complete(status.id,0);
  assert(!buffer_a2ui_model_action_status(surface.id,button->id,&status)&&!status.pending&&!status.result);
  assert(!buffer_a2ui_model_emit_action(surface.id,button->id,button->action,button->context_json));
  for(unsigned i=0;i<1000&&__atomic_load_n(&generic_calls,__ATOMIC_ACQUIRE)<3;i++)usleep(1000);
  assert(generic_calls==3&&strcmp(generic_first_id,generic_last_id));buffer_a2ui_action_complete(generic_last_id,0);
  assert(!buffer_a2ui_set_action_handler(NULL,NULL));
  puts("PASS declared generic actions, forged input rejection, async receipt, busy dedup and stable retry identity");
}

int main(int argc, char **argv)
{
  assert(buffer_assistant_init() == 0);
  if (argc == 2 && strcmp(argv[1], "create") == 0)
    {
      check_protocol_subset();
      check_reply_budget();
      create_durable_timer();
      check_alarm_domain();
      check_recipe_and_timer_close();
      puts("PASS: production domain/model host compile, bounded reply, protocol,");
      puts("      idempotency, persistence, recipe-on-click binding, closed-page timer");
      return 0;
    }
  if (argc == 3 && strcmp(argv[1], "verify") == 0)
    {
      verify_durable_timer(argv[2]);
      puts("PASS: timer and idempotency survived process restart");
      return 0;
    }
  if (argc == 3 && strcmp(argv[1], "official") == 0)
    {
      check_official_sample(argv[2]);
      check_generic_actions();
      return 0;
    }
  return 2;
}
'''


def test_production_assistant_domain_and_a2ui_are_host_linkable():
    with tempfile.TemporaryDirectory(prefix="buffer-assistant-a2ui-") as temporary:
        work = Path(temporary)
        state = work / "state"
        state.mkdir(mode=0o700)
        source = work / "fixture.c"
        binary = work / "fixture"
        (work / "nuttx").mkdir()
        (work / "nuttx/config.h").write_text("")
        (work / "netutils").mkdir()
        (work / "netutils/cJSON.h").symlink_to(SDK_ROOT / "apps/netutils/cjson/cJSON/cJSON.h")
        source.write_text(HARNESS)
        subprocess.run(
            [
                *CC, "-std=c11", "-D_DEFAULT_SOURCE", "-Wall", "-Wextra",
                "-Werror", "-pthread", f'-DCONFIG_BUFFER_VELA_DATA_DIR="{state}"',
                "-I", str(work), "-I", str(ROOT / "app/buffer_app"), str(source),
                str(ROOT / "app/buffer_app/buffer_assistant.c"),
                str(ROOT / "app/buffer_app/buffer_a2ui_model.c"),
                "-o", str(binary),
            ],
            check=True,
        )
        created = subprocess.run(
            [str(binary), "create"], check=True, capture_output=True,
            text=True, timeout=12,
        )
        timer_id = next(
            line.split("=", 1)[1]
            for line in created.stdout.splitlines()
            if line.startswith("DURABLE_TIMER_ID=")
        )
        subprocess.run([str(binary), "verify", timer_id], check=True, timeout=5)

        official = json.loads(
            (ROOT / "tests/fixtures/a2ui_v0_9_1_official_interactive_button.json")
            .read_text(encoding="utf-8")
        )
        native_messages = []
        for message in official["messages"]:
            adapted = dict(message)
            adapted["version"] = "v0.9.1"
            if "createSurface" in adapted:
                create_surface = dict(adapted["createSurface"])
                create_surface["catalogId"] = (
                    "https://a2ui.org/specification/v0_9_1/"
                    "catalogs/basic/catalog.json"
                )
                adapted["createSurface"] = create_surface
            native_messages.append(json.dumps(adapted, ensure_ascii=False))
        native_fixture = work / "official-interactive-button-v0_9_1.jsonl"
        native_fixture.write_text("\n".join(native_messages) + "\n",
                                  encoding="utf-8")
        rendered = subprocess.run(
            [str(binary), "official", str(native_fixture)], check=True,
            capture_output=True, text=True, timeout=5,
        )
        assert "PASS: official interactive-button sample" in rendered.stdout
