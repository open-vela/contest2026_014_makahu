/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_a2ui_internal.h"
#include "buffer_app.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static pthread_once_t capture_once=PTHREAD_ONCE_INIT;
static pthread_key_t capture_key;
static int capture_key_error;
static void capture_key_init(void){capture_key_error=pthread_key_create(&capture_key,free);}
int buffer_a2ui_capture_context_begin(const char *id)
{
 if(!id||!*id||strnlen(id,BUFFER_ASSISTANT_ID_SIZE)>=BUFFER_ASSISTANT_ID_SIZE)return -EINVAL;
 int result=pthread_once(&capture_once,capture_key_init);if(result)return -result;if(capture_key_error)return -capture_key_error;
 char *value=pthread_getspecific(capture_key);
 if(!value){value=calloc(1,BUFFER_ASSISTANT_ID_SIZE);if(!value)return -ENOMEM;result=pthread_setspecific(capture_key,value);if(result){free(value);return -result;}}
 snprintf(value,BUFFER_ASSISTANT_ID_SIZE,"%s",id);return 0;
}
void buffer_a2ui_capture_context_end(void)
{
 if(pthread_once(&capture_once,capture_key_init)||capture_key_error)return;
 char *value=pthread_getspecific(capture_key);if(!pthread_setspecific(capture_key,NULL))free(value);
}

#define A2UI_TOKEN_LIMIT 2048
#define A2UI_ACTION_QUEUE 8
#define A2UI_BASIC_CATALOG \
  "https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json"
#define A2UI_ASSISTANT_CATALOG \
  "https://vela.local/a2ui/catalogs/assistant/1"

enum token_type_e
{
  TOKEN_OBJECT = 1,
  TOKEN_ARRAY,
  TOKEN_STRING,
  TOKEN_NUMBER,
  TOKEN_TRUE,
  TOKEN_FALSE,
  TOKEN_NULL
};

struct json_token_s
{
  uint32_t start;
  uint32_t end;
  uint32_t raw_start;
  uint32_t raw_end;
  int16_t first_child;
  int16_t next_sibling;
  uint8_t type;
  uint8_t is_key;
};

struct json_parser_s
{
  const char *json;
  size_t length;
  size_t position;
  size_t token_count;
  struct json_token_s tokens[A2UI_TOKEN_LIMIT];
};

struct component_update_s
{
  uint8_t component_index;
  int16_t children_token;
  int16_t child_token;
};

struct queued_action_s {struct buffer_a2ui_action_s event;unsigned handler_epoch;uint64_t attempt;};
struct action_receipt_s {bool used,pending,accepted;int result;uint64_t attempt;struct buffer_a2ui_action_s event;};
static struct action_receipt_s g_action_receipts[A2UI_ACTION_QUEUE];
static unsigned g_action_receipt_next;
static uint64_t g_action_attempt;

static pthread_mutex_t g_model_lock = PTHREAD_MUTEX_INITIALIZER;
static struct buffer_a2ui_surface_s g_surfaces[BUFFER_A2UI_SURFACE_COUNT];
static struct buffer_a2ui_surface_s g_candidate;
static struct json_parser_s g_parser;
static struct component_update_s g_updates[BUFFER_A2UI_COMPONENT_COUNT];
static char g_closed_ids[BUFFER_A2UI_CLOSED_IDS][BUFFER_A2UI_SURFACE_ID_SIZE];
static size_t g_closed_count;
static size_t g_closed_next;
static int g_active_surface = -1;
static uint64_t g_task_revision;
static uint64_t g_surface_sequence;
static buffer_a2ui_refresh_hook_fn g_refresh_hook;
static buffer_a2ui_action_fn g_action_handler;
static void *g_action_context;
static pthread_mutex_t g_action_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_action_changed = PTHREAD_COND_INITIALIZER;
static struct queued_action_s g_action_queue[A2UI_ACTION_QUEUE];
static size_t g_action_head;
static size_t g_action_count;
static bool g_action_worker_started;
static unsigned g_action_handler_epoch;
static int surface_index(const char *id);

static void *action_worker(void *arg)
{
  (void)arg;
  for (;;)
    {
      struct queued_action_s action;
      pthread_mutex_lock(&g_action_lock);
      while (g_action_count == 0)
        pthread_cond_wait(&g_action_changed, &g_action_lock);
      action = g_action_queue[g_action_head];
      g_action_head = (g_action_head + 1) % A2UI_ACTION_QUEUE;
      g_action_count--;
      pthread_mutex_unlock(&g_action_lock);

      pthread_mutex_lock(&g_model_lock);
      buffer_a2ui_action_fn handler = g_action_handler;
      void *context = g_action_context;
      int index=surface_index(action.event.surface_id);
      const struct buffer_a2ui_component_s *node=index<0?NULL:buffer_a2ui_find_component(&g_surfaces[index],action.event.component_id);
      bool current=node&&node->type==BUFFER_A2UI_BUTTON&&g_surfaces[index].task_revision==action.event.revision&&g_surfaces[index].sequence==action.event.sequence&&!strcmp(node->action,action.event.name)&&!strcmp(node->context_json,action.event.context_json)&&action.handler_epoch==g_action_handler_epoch;
      pthread_mutex_lock(&g_action_lock);bool live=false;
      for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++)if(g_action_receipts[i].used&&g_action_receipts[i].pending&&g_action_receipts[i].attempt==action.attempt&&!strcmp(g_action_receipts[i].event.id,action.event.id)){live=true;break;}
      pthread_mutex_unlock(&g_action_lock);
      pthread_mutex_unlock(&g_model_lock);
      if(!live)continue;
      int result=!current?-ESTALE:handler?handler(&action.event,context):-ESHUTDOWN;
      pthread_mutex_lock(&g_action_lock);
      for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++)if(g_action_receipts[i].used&&g_action_receipts[i].attempt==action.attempt&&!strcmp(g_action_receipts[i].event.id,action.event.id)){
       if(result<0){if(g_action_receipts[i].pending){g_action_receipts[i].pending=false;g_action_receipts[i].result=result;}}
       else g_action_receipts[i].accepted=true;
       break;
      }
      pthread_mutex_unlock(&g_action_lock);
    }
  return NULL;
}

static void parser_space(void)
{
  while (g_parser.position < g_parser.length)
    {
      char c = g_parser.json[g_parser.position];
      if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
      g_parser.position++;
    }
}

static int parser_token(uint8_t type, bool is_key)
{
  if (g_parser.token_count >= A2UI_TOKEN_LIMIT) return -ENOSPC;
  int index = (int)g_parser.token_count++;
  struct json_token_s *token = &g_parser.tokens[index];
  memset(token, 0, sizeof(*token));
  token->first_child = -1;
  token->next_sibling = -1;
  token->type = type;
  token->is_key = is_key;
  return index;
}

static void parser_append_child(int parent, int child)
{
  struct json_token_s *token = &g_parser.tokens[parent];
  if (token->first_child < 0)
    {
      token->first_child = (int16_t)child;
      return;
    }
  int last = token->first_child;
  while (g_parser.tokens[last].next_sibling >= 0)
    last = g_parser.tokens[last].next_sibling;
  g_parser.tokens[last].next_sibling = (int16_t)child;
}

static int parser_string(bool is_key, int *result)
{
  if (g_parser.position >= g_parser.length ||
      g_parser.json[g_parser.position] != '"') return -EINVAL;
  int index = parser_token(TOKEN_STRING, is_key);
  if (index < 0) return index;
  struct json_token_s *token = &g_parser.tokens[index];
  token->raw_start = (uint32_t)g_parser.position++;
  token->start = (uint32_t)g_parser.position;
  while (g_parser.position < g_parser.length)
    {
      unsigned char c = (unsigned char)g_parser.json[g_parser.position++];
      if (c == '"')
        {
          token->end = (uint32_t)(g_parser.position - 1);
          token->raw_end = (uint32_t)g_parser.position;
          *result = index;
          return 0;
        }
      if (c < 0x20) return -EINVAL;
      if (c == '\\')
        {
          if (g_parser.position >= g_parser.length) return -EINVAL;
          char escape = g_parser.json[g_parser.position++];
          if (escape == 'u')
            {
              if (g_parser.length - g_parser.position < 4) return -EINVAL;
              for (int i = 0; i < 4; i++)
                {
                  char digit = g_parser.json[g_parser.position++];
                  if (!((digit >= '0' && digit <= '9') ||
                        (digit >= 'a' && digit <= 'f') ||
                        (digit >= 'A' && digit <= 'F'))) return -EINVAL;
                }
            }
          else if (escape != '"' && escape != '\\' && escape != '/' &&
                   escape != 'b' && escape != 'f' && escape != 'n' &&
                   escape != 'r' && escape != 't') return -EINVAL;
        }
    }
  return -EINVAL;
}

static int parser_value(unsigned int depth, int *result);

static int parser_container(uint8_t type, unsigned int depth, int *result)
{
  if (depth > BUFFER_A2UI_DEPTH_MAX) return -E2BIG;
  char closing = type == TOKEN_OBJECT ? '}' : ']';
  int index = parser_token(type, false);
  if (index < 0) return index;
  struct json_token_s *token = &g_parser.tokens[index];
  token->raw_start = (uint32_t)g_parser.position++;
  parser_space();
  if (g_parser.position < g_parser.length &&
      g_parser.json[g_parser.position] == closing)
    {
      g_parser.position++;
      token->start = token->raw_start;
      token->end = (uint32_t)g_parser.position;
      token->raw_end = token->end;
      *result = index;
      return 0;
    }

  for (;;)
    {
      int child;
      if (type == TOKEN_OBJECT)
        {
          int key;
          int ret = parser_string(true, &key);
          if (ret < 0) return ret;
          parser_append_child(index, key);
          parser_space();
          if (g_parser.position >= g_parser.length ||
              g_parser.json[g_parser.position++] != ':') return -EINVAL;
          parser_space();
          ret = parser_value(depth + 1, &child);
          if (ret < 0) return ret;
          parser_append_child(index, child);
        }
      else
        {
          int ret = parser_value(depth + 1, &child);
          if (ret < 0) return ret;
          parser_append_child(index, child);
        }
      parser_space();
      if (g_parser.position >= g_parser.length) return -EINVAL;
      char separator = g_parser.json[g_parser.position++];
      if (separator == closing) break;
      if (separator != ',') return -EINVAL;
      parser_space();
    }
  token->start = token->raw_start;
  token->end = (uint32_t)g_parser.position;
  token->raw_end = token->end;
  *result = index;
  return 0;
}

static int parser_value(unsigned int depth, int *result)
{
  parser_space();
  if (g_parser.position >= g_parser.length) return -EINVAL;
  char c = g_parser.json[g_parser.position];
  if (c == '{') return parser_container(TOKEN_OBJECT, depth, result);
  if (c == '[') return parser_container(TOKEN_ARRAY, depth, result);
  if (c == '"') return parser_string(false, result);

  uint8_t type;
  size_t length;
  if (c == 't' && g_parser.length - g_parser.position >= 4 &&
      memcmp(g_parser.json + g_parser.position, "true", 4) == 0)
    { type = TOKEN_TRUE; length = 4; }
  else if (c == 'f' && g_parser.length - g_parser.position >= 5 &&
           memcmp(g_parser.json + g_parser.position, "false", 5) == 0)
    { type = TOKEN_FALSE; length = 5; }
  else if (c == 'n' && g_parser.length - g_parser.position >= 4 &&
           memcmp(g_parser.json + g_parser.position, "null", 4) == 0)
    { type = TOKEN_NULL; length = 4; }
  else if (c == '-' || (c >= '0' && c <= '9'))
    {
      type = TOKEN_NUMBER;
      size_t start = g_parser.position;
      if (c == '-') g_parser.position++;
      if (g_parser.position >= g_parser.length) return -EINVAL;
      if (g_parser.json[g_parser.position] == '0') g_parser.position++;
      else
        {
          if (g_parser.json[g_parser.position] < '1' ||
              g_parser.json[g_parser.position] > '9') return -EINVAL;
          while (g_parser.position < g_parser.length &&
                 g_parser.json[g_parser.position] >= '0' &&
                 g_parser.json[g_parser.position] <= '9') g_parser.position++;
        }
      if (g_parser.position < g_parser.length &&
          g_parser.json[g_parser.position] == '.')
        {
          g_parser.position++;
          size_t digits = g_parser.position;
          while (g_parser.position < g_parser.length &&
                 g_parser.json[g_parser.position] >= '0' &&
                 g_parser.json[g_parser.position] <= '9') g_parser.position++;
          if (digits == g_parser.position) return -EINVAL;
        }
      if (g_parser.position < g_parser.length &&
          (g_parser.json[g_parser.position] == 'e' ||
           g_parser.json[g_parser.position] == 'E'))
        {
          g_parser.position++;
          if (g_parser.position < g_parser.length &&
              (g_parser.json[g_parser.position] == '+' ||
               g_parser.json[g_parser.position] == '-')) g_parser.position++;
          size_t digits = g_parser.position;
          while (g_parser.position < g_parser.length &&
                 g_parser.json[g_parser.position] >= '0' &&
                 g_parser.json[g_parser.position] <= '9') g_parser.position++;
          if (digits == g_parser.position) return -EINVAL;
        }
      int index = parser_token(type, false);
      if (index < 0) return index;
      g_parser.tokens[index].start = (uint32_t)start;
      g_parser.tokens[index].end = (uint32_t)g_parser.position;
      g_parser.tokens[index].raw_start = (uint32_t)start;
      g_parser.tokens[index].raw_end = (uint32_t)g_parser.position;
      *result = index;
      return 0;
    }
  else return -EINVAL;

  int index = parser_token(type, false);
  if (index < 0) return index;
  g_parser.tokens[index].start = (uint32_t)g_parser.position;
  g_parser.tokens[index].end = (uint32_t)(g_parser.position + length);
  g_parser.tokens[index].raw_start = (uint32_t)g_parser.position;
  g_parser.tokens[index].raw_end = (uint32_t)(g_parser.position + length);
  g_parser.position += length;
  *result = index;
  return 0;
}

static int parser_run(const char *json, size_t length, int *root)
{
  memset(&g_parser, 0, sizeof(g_parser));
  g_parser.json = json;
  g_parser.length = length;
  int ret = parser_value(0, root);
  if (ret < 0) return ret;
  parser_space();
  return g_parser.position == g_parser.length ? 0 : -EINVAL;
}

static int utf8_append(char *out, size_t capacity, size_t *used,
                       uint32_t codepoint)
{
  unsigned char bytes[4];
  size_t count;
  if (codepoint == 0 || codepoint > 0x10ffff ||
      (codepoint >= 0xd800 && codepoint <= 0xdfff)) return -EINVAL;
  if (codepoint < 0x80) { bytes[0] = codepoint; count = 1; }
  else if (codepoint < 0x800)
    {
      bytes[0] = 0xc0 | (codepoint >> 6);
      bytes[1] = 0x80 | (codepoint & 0x3f); count = 2;
    }
  else if (codepoint < 0x10000)
    {
      bytes[0] = 0xe0 | (codepoint >> 12);
      bytes[1] = 0x80 | ((codepoint >> 6) & 0x3f);
      bytes[2] = 0x80 | (codepoint & 0x3f); count = 3;
    }
  else
    {
      bytes[0] = 0xf0 | (codepoint >> 18);
      bytes[1] = 0x80 | ((codepoint >> 12) & 0x3f);
      bytes[2] = 0x80 | ((codepoint >> 6) & 0x3f);
      bytes[3] = 0x80 | (codepoint & 0x3f); count = 4;
    }
  if (*used + count >= capacity) return -ENOSPC;
  memcpy(out + *used, bytes, count);
  *used += count;
  return 0;
}

static int hex4(const char *text, uint32_t *value)
{
  uint32_t result = 0;
  for (int i = 0; i < 4; i++)
    {
      char c = text[i];
      unsigned int digit;
      if (c >= '0' && c <= '9') digit = (unsigned int)(c - '0');
      else if (c >= 'a' && c <= 'f') digit = (unsigned int)(c - 'a' + 10);
      else if (c >= 'A' && c <= 'F') digit = (unsigned int)(c - 'A' + 10);
      else return -EINVAL;
      result = (result << 4) | digit;
    }
  *value = result;
  return 0;
}

static int token_string(int index, char *out, size_t capacity)
{
  if (index < 0 || index >= (int)g_parser.token_count || out == NULL ||
      capacity == 0 || g_parser.tokens[index].type != TOKEN_STRING) return -EINVAL;
  const struct json_token_s *token = &g_parser.tokens[index];
  size_t used = 0;
  for (size_t i = token->start; i < token->end; i++)
    {
      unsigned char c = (unsigned char)g_parser.json[i];
      if (c != '\\')
        {
          if (used + 1 >= capacity) return -ENOSPC;
          out[used++] = (char)c;
          continue;
        }
      if (++i >= token->end) return -EINVAL;
      char escape = g_parser.json[i];
      if (escape == 'u')
        {
          uint32_t codepoint;
          if (token->end - i <= 4 || hex4(g_parser.json + i + 1, &codepoint) < 0)
            return -EINVAL;
          i += 4;
          if (codepoint >= 0xd800 && codepoint <= 0xdbff)
            {
              if (token->end - i <= 6 || g_parser.json[i + 1] != '\\' ||
                  g_parser.json[i + 2] != 'u') return -EINVAL;
              uint32_t low;
              if (hex4(g_parser.json + i + 3, &low) < 0 ||
                  low < 0xdc00 || low > 0xdfff) return -EINVAL;
              codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
              i += 6;
            }
          int ret = utf8_append(out, capacity, &used, codepoint);
          if (ret < 0) return ret;
        }
      else
        {
          char decoded;
          switch (escape)
            {
              case '"': decoded = '"'; break;
              case '\\': decoded = '\\'; break;
              case '/': decoded = '/'; break;
              case 'b': decoded = '\b'; break;
              case 'f': decoded = '\f'; break;
              case 'n': decoded = '\n'; break;
              case 'r': decoded = '\r'; break;
              case 't': decoded = '\t'; break;
              default: return -EINVAL;
            }
          if (decoded == 0 || used + 1 >= capacity) return -ENOSPC;
          out[used++] = decoded;
        }
    }
  out[used] = 0;
  return 0;
}

static bool token_equals(int index, const char *expected)
{
  char value[64];
  return token_string(index, value, sizeof(value)) == 0 &&
         strcmp(value, expected) == 0;
}

static int object_get(int object, const char *key)
{
  if (object < 0 || g_parser.tokens[object].type != TOKEN_OBJECT) return -1;
  int child = g_parser.tokens[object].first_child;
  while (child >= 0)
    {
      int value = g_parser.tokens[child].next_sibling;
      if (value < 0) return -1;
      if (token_equals(child, key)) return value;
      child = g_parser.tokens[value].next_sibling;
    }
  return -1;
}

static int object_key_count(int object)
{
  int count = 0;
  if (object < 0 || g_parser.tokens[object].type != TOKEN_OBJECT) return -1;
  for (int child = g_parser.tokens[object].first_child; child >= 0;)
    {
      int value = g_parser.tokens[child].next_sibling;
      if (value < 0) return -1;
      count++;
      child = g_parser.tokens[value].next_sibling;
    }
  return count;
}

static int token_raw(int index, char *out, size_t capacity)
{
  if (index < 0 || index >= (int)g_parser.token_count || out == NULL || capacity == 0)
    return -EINVAL;
  const struct json_token_s *token = &g_parser.tokens[index];
  size_t length = token->raw_end - token->raw_start;
  if (length >= capacity) return -ENOSPC;
  memcpy(out, g_parser.json + token->raw_start, length);
  out[length] = 0;
  return 0;
}

static int token_u32(int index, uint32_t *value)
{
  if (index < 0 || value == NULL || g_parser.tokens[index].type != TOKEN_NUMBER)
    return -EINVAL;
  const struct json_token_s *token = &g_parser.tokens[index];
  uint64_t result = 0;
  if (token->start == token->end) return -EINVAL;
  for (size_t i = token->start; i < token->end; i++)
    {
      char c = g_parser.json[i];
      if (c < '0' || c > '9') return -EINVAL;
      result = result * 10 + (unsigned int)(c - '0');
      if (result > UINT32_MAX) return -ERANGE;
    }
  *value = (uint32_t)result;
  return 0;
}

static int component_type(const char *name, enum buffer_a2ui_component_type_e *type)
{
  static const struct
  {
    const char *name;
    enum buffer_a2ui_component_type_e type;
  } types[] = {
    {"Column", BUFFER_A2UI_COLUMN}, {"Row", BUFFER_A2UI_ROW},
    {"Card", BUFFER_A2UI_CARD}, {"Text", BUFFER_A2UI_TEXT},
    {"Button", BUFFER_A2UI_BUTTON}, {"Divider", BUFFER_A2UI_DIVIDER},
    {"VelaTimerCard", BUFFER_A2UI_TIMER_CARD},
    {"VelaAlarmCard", BUFFER_A2UI_ALARM_CARD},
    {"VelaTranslationResult", BUFFER_A2UI_TRANSLATION_RESULT},
    {"VelaRecipeStep", BUFFER_A2UI_RECIPE_STEP},
  };
  for (size_t i = 0; i < sizeof(types) / sizeof(types[0]); i++)
    if (strcmp(name, types[i].name) == 0)
      {
        *type = types[i].type;
        return 0;
      }
  return -ENOTSUP;
}

static bool component_needs_extension(enum buffer_a2ui_component_type_e type)
{
  return type >= BUFFER_A2UI_TIMER_CARD;
}

static int component_find(const struct buffer_a2ui_surface_s *surface,
                          const char *id)
{
  for (size_t i = 0; i < surface->component_count; i++)
    if (strcmp(surface->components[i].id, id) == 0) return (int)i;
  return -1;
}

static int component_copy_string(int object, const char *key,
                                 char *out, size_t capacity,
                                 bool required)
{
  int token = object_get(object, key);
  if (token < 0) return required ? -EINVAL : 0;
  return token_string(token, out, capacity);
}

static int component_child_tokens(int object, struct component_update_s *update,
                                  struct buffer_a2ui_component_s *component)
{
  int children = object_get(object, "children");
  int child = object_get(object, "child");
  update->children_token = -1;
  update->child_token = -1;
  if (children >= 0 && child >= 0) return -EINVAL;
  if (children >= 0)
    {
      if (g_parser.tokens[children].type != TOKEN_ARRAY) return -EINVAL;
      update->children_token = (int16_t)children;
      int count = 0;
      for (int item = g_parser.tokens[children].first_child; item >= 0;
           item = g_parser.tokens[item].next_sibling)
        {
          if (g_parser.tokens[item].type != TOKEN_STRING ||
              ++count > BUFFER_A2UI_CHILD_COUNT) return -E2BIG;
        }
      component->child_count = (uint8_t)count;
      return 0;
    }
  if (child >= 0)
    {
      if (g_parser.tokens[child].type != TOKEN_STRING) return -EINVAL;
      update->child_token = (int16_t)child;
      component->child_count = 1;
      return 0;
    }
  component->child_count = 0;
  return 0;
}

static int component_parse_action(int object,
                                  struct buffer_a2ui_component_s *component)
{
  int action = object_get(object, "action");
  if (action < 0) return -EINVAL;
  if (g_parser.tokens[action].type != TOKEN_OBJECT) return -EINVAL;
  int event = object_get(action, "event");
  if (event < 0 || g_parser.tokens[event].type != TOKEN_OBJECT) return -EINVAL;
  int name = object_get(event, "name");
  if (name < 0 || token_string(name, component->action,
                               sizeof(component->action)) < 0 ||
      component->action[0] == 0) return -EINVAL;
  int context = object_get(event, "context");
  if (context >= 0 && g_parser.tokens[context].type != TOKEN_OBJECT) return -EINVAL;
  if (context >= 0)
    return token_raw(context, component->context_json,
                     sizeof(component->context_json));
  snprintf(component->context_json, sizeof(component->context_json), "{}");
  return 0;
}

static int component_parse(int object, struct buffer_a2ui_surface_s *surface,
                           struct buffer_a2ui_component_s *component,
                           struct component_update_s *update)
{
  if (g_parser.tokens[object].type != TOKEN_OBJECT) return -EINVAL;
  memset(component, 0, sizeof(*component));
  update->children_token = -1;
  update->child_token = -1;
  int id = object_get(object, "id");
  int component_token = object_get(object, "component");
  char type_name[48];
  if (id < 0 || token_string(id, component->id, sizeof(component->id)) < 0 ||
      component->id[0] == 0 || component_token < 0 ||
      token_string(component_token, type_name, sizeof(type_name)) < 0 ||
      component_type(type_name, &component->type) < 0) return -ENOTSUP;
  if (component_needs_extension(component->type) &&
      strcmp(surface->catalog_id, A2UI_ASSISTANT_CATALOG) != 0) return -ENOTSUP;

  switch (component->type)
    {
      case BUFFER_A2UI_COLUMN:
      case BUFFER_A2UI_ROW:
        return component_child_tokens(object, update, component) < 0 ? -EINVAL :
               update->children_token < 0 ? -EINVAL : 0;
      case BUFFER_A2UI_CARD:
        if (component_child_tokens(object, update, component) < 0 ||
            component->child_count != 1) return -EINVAL;
        return 0;
      case BUFFER_A2UI_TEXT:
        {
          int text = object_get(object, "text");
          if (text < 0) return -EINVAL;
          if (g_parser.tokens[text].type == TOKEN_STRING)
            {
              int ret = token_string(text, component->text,
                                     sizeof(component->text));
              if (ret < 0) return ret;
            }
          else if (g_parser.tokens[text].type == TOKEN_OBJECT)
            {
              int path = object_get(text, "path");
              if (path < 0 || token_string(path, component->binding,
                                           sizeof(component->binding)) < 0 ||
                  component->binding[0] != '/') return -EINVAL;
            }
          else return -EINVAL;
          int variant = object_get(object, "variant");
          if (variant >= 0)
            {
              if (token_equals(variant, "h1")) component->variant = 1;
              else if (token_equals(variant, "h2")) component->variant = 2;
              else if (token_equals(variant, "h3")) component->variant = 3;
              else if (token_equals(variant, "caption")) component->variant = 4;
              else if (token_equals(variant, "body")) component->variant = 0;
              else return -ENOTSUP;
            }
          return 0;
        }
      case BUFFER_A2UI_BUTTON:
        if (component_child_tokens(object, update, component) < 0 ||
            component->child_count != 1) return -EINVAL;
        return component_parse_action(object, component);
      case BUFFER_A2UI_DIVIDER:
        return 0;
      case BUFFER_A2UI_TIMER_CARD:
        if (component_copy_string(object, "timerId", component->payload.timer.id,
                                  sizeof(component->payload.timer.id), true) < 0)
          return -EINVAL;
        (void)component_copy_string(object, "label", component->payload.timer.label,
                                    sizeof(component->payload.timer.label), false);
        return 0;
      case BUFFER_A2UI_ALARM_CARD:
        if (component_copy_string(object, "alarmId", component->payload.alarm.id,
                                  sizeof(component->payload.alarm.id), true) < 0)
          return -EINVAL;
        (void)component_copy_string(object, "label", component->payload.alarm.label,
                                    sizeof(component->payload.alarm.label), false);
        return 0;
      case BUFFER_A2UI_TRANSLATION_RESULT:
        if (component_copy_string(object, "original",
                component->payload.translation.original,
                sizeof(component->payload.translation.original), true) < 0 ||
            component_copy_string(object, "translated",
                component->payload.translation.translated,
                sizeof(component->payload.translation.translated), true) < 0 ||
            component_copy_string(object, "sourceLanguage",
                component->payload.translation.source_language,
                sizeof(component->payload.translation.source_language), true) < 0 ||
            component_copy_string(object, "targetLanguage",
                component->payload.translation.target_language,
                sizeof(component->payload.translation.target_language), true) < 0)
          return -EINVAL;
        return 0;
      case BUFFER_A2UI_RECIPE_STEP:
        if (component_copy_string(object, "title", component->payload.recipe_step.title,
                sizeof(component->payload.recipe_step.title), true) < 0 ||
            component_copy_string(object, "detail", component->payload.recipe_step.detail,
                sizeof(component->payload.recipe_step.detail), false) < 0)
          return -EINVAL;
        {
          int timer = object_get(object, "timerMs");
          if (timer >= 0 && token_u32(timer,
                  &component->payload.recipe_step.timer_ms) < 0) return -EINVAL;
          int timer_id = object_get(object, "timerId");
          if (timer_id >= 0 && token_string(timer_id,
                  component->payload.recipe_step.timer_id,
                  sizeof(component->payload.recipe_step.timer_id)) < 0) return -EINVAL;
        }
        return 0;
      default:
        return -ENOTSUP;
    }
}

static int data_find(const struct buffer_a2ui_surface_s *surface, const char *path)
{
  for (size_t i = 0; i < surface->data_count; i++)
    if (strcmp(surface->data[i].path, path) == 0) return (int)i;
  return -1;
}

static int data_upsert(struct buffer_a2ui_surface_s *surface, const char *path,
                       const char *value,bool json_value)
{
  int index = data_find(surface, path);
  if (index < 0)
    {
      if (surface->data_count >= BUFFER_A2UI_DATA_COUNT) return -ENOSPC;
      index = surface->data_count++;
    }
  surface->data[index].json_value=json_value;
  snprintf(surface->data[index].path, sizeof(surface->data[index].path), "%s", path);
  snprintf(surface->data[index].value, sizeof(surface->data[index].value), "%s", value);
  return 0;
}

static bool data_is_descendant(const char *path, const char *parent)
{
  size_t length = strlen(parent);
  if (strcmp(path, parent) == 0) return true;
  if (strcmp(parent, "/") == 0) return path[0] == '/';
  return strncmp(path, parent, length) == 0 && path[length] == '/';
}

static void data_remove_prefix(struct buffer_a2ui_surface_s *surface,
                               const char *path)
{
  for (size_t i = 0; i < surface->data_count;)
    if (data_is_descendant(surface->data[i].path, path))
      {
        surface->data[i] = surface->data[--surface->data_count];
        memset(&surface->data[surface->data_count], 0,
               sizeof(surface->data[surface->data_count]));
      }
    else i++;
}

static int path_append(char *out, size_t capacity, const char *base,
                       const char *key)
{
  size_t used = strlen(base);
  if (used + 2 >= capacity) return -ENOSPC;
  if (used == 0) out[used++] = '/';
  else if (base[used - 1] != '/') out[used++] = '/';
  for (const char *p = key; *p; p++)
    {
      if (*p == '~' || *p == '/')
        {
          if (used + 2 >= capacity) return -ENOSPC;
          out[used++] = '~'; out[used++] = *p == '~' ? '0' : '1';
        }
      else
        {
          if (used + 1 >= capacity) return -ENOSPC;
          out[used++] = *p;
        }
    }
  out[used] = 0;
  return 0;
}

static int data_flatten(struct buffer_a2ui_surface_s *surface, const char *path,
                        int token, unsigned int depth)
{
  if (depth > BUFFER_A2UI_DEPTH_MAX) return -E2BIG;
  struct json_token_s *value = &g_parser.tokens[token];
  if (value->type == TOKEN_OBJECT)
    {
      for (int key_token = value->first_child; key_token >= 0;)
        {
          int child = g_parser.tokens[key_token].next_sibling;
          char key[64];
          char child_path[BUFFER_A2UI_BINDING_SIZE];
          if (child < 0 || token_string(key_token, key, sizeof(key)) < 0 ||
              path_append(child_path, sizeof(child_path), path, key) < 0)
            return -EINVAL;
          int ret = data_flatten(surface, child_path, child, depth + 1);
          if (ret < 0) return ret;
          key_token = g_parser.tokens[child].next_sibling;
        }
      return 0;
    }
  if (value->type == TOKEN_ARRAY)
    {
      unsigned int item_index = 0;
      for (int child = value->first_child; child >= 0;
           child = g_parser.tokens[child].next_sibling, item_index++)
        {
          char index[16];
          char child_path[BUFFER_A2UI_BINDING_SIZE];
          snprintf(index, sizeof(index), "%u", item_index);
          if (path_append(child_path, sizeof(child_path), path, index) < 0)
            return -ENOSPC;
          int ret = data_flatten(surface, child_path, child, depth + 1);
          if (ret < 0) return ret;
        }
      return 0;
    }

  char decoded[BUFFER_A2UI_COMPONENT_TEXT_MAX];
  if (value->type == TOKEN_STRING)
    {
      if (token_string(token, decoded, sizeof(decoded)) < 0) return -ENOSPC;
    }
  else if (value->type == TOKEN_NUMBER || value->type == TOKEN_TRUE ||
           value->type == TOKEN_FALSE)
    {
      size_t length = value->raw_end - value->raw_start;
      if (length >= sizeof(decoded)) return -ENOSPC;
      memcpy(decoded, g_parser.json + value->raw_start, length);
      decoded[length] = 0;
    }
  else decoded[0] = 0;
  return data_upsert(surface, path[0] == 0 ? "/" : path, decoded,value->type!=TOKEN_STRING);
}

static int data_update(struct buffer_a2ui_surface_s *surface, int message)
{
  int path_token = object_get(message, "path");
  int value_token = object_get(message, "value");
  char path[BUFFER_A2UI_BINDING_SIZE] = "/";
  if (path_token >= 0 && token_string(path_token, path, sizeof(path)) < 0)
    return -EINVAL;
  if (path[0] != '/') return -EINVAL;

  bool replace_all = strcmp(path, "/") == 0;
  if (replace_all) surface->data_count = 0;
  else data_remove_prefix(surface, path);
  if (value_token < 0) return 0;
  char base[BUFFER_A2UI_BINDING_SIZE];
  snprintf(base, sizeof(base), "%s", replace_all ? "" : path);
  return data_flatten(surface, base, value_token, 0);
}

static int surface_string_budget(const struct buffer_a2ui_surface_s *surface)
{
  size_t bytes = strlen(surface->id) + strlen(surface->catalog_id);
  size_t data_bytes = 0;
  for (size_t i = 0; i < surface->component_count; i++)
    {
      const struct buffer_a2ui_component_s *c = &surface->components[i];
      bytes += strlen(c->id) + strlen(c->text) + strlen(c->binding) +
               strlen(c->action) + strlen(c->context_json);
      switch (c->type)
        {
          case BUFFER_A2UI_TIMER_CARD:
            bytes += strlen(c->payload.timer.id) + strlen(c->payload.timer.label);
            break;
          case BUFFER_A2UI_ALARM_CARD:
            bytes += strlen(c->payload.alarm.id) + strlen(c->payload.alarm.label);
            break;
          case BUFFER_A2UI_TRANSLATION_RESULT:
            bytes += strlen(c->payload.translation.original) +
                     strlen(c->payload.translation.translated) +
                     strlen(c->payload.translation.source_language) +
                     strlen(c->payload.translation.target_language);
            break;
          case BUFFER_A2UI_RECIPE_STEP:
            bytes += strlen(c->payload.recipe_step.title) +
                     strlen(c->payload.recipe_step.detail) +
                     strlen(c->payload.recipe_step.timer_id) +
                     strlen(c->payload.recipe_step.request_id);
            break;
          default:
            break;
        }
    }
  for (size_t i = 0; i < surface->data_count; i++)
    {
      size_t entry_bytes = strlen(surface->data[i].path) +
                           strlen(surface->data[i].value);
      data_bytes += entry_bytes;
      bytes += entry_bytes;
    }
  if (data_bytes > BUFFER_A2UI_DATA_BUDGET) return -E2BIG;
  return bytes > BUFFER_A2UI_STRING_BUDGET ? -E2BIG : 0;
}

static int graph_visit(const struct buffer_a2ui_surface_s *surface, int index,
                       uint8_t *marks, uint8_t *parents, unsigned int depth)
{
  if (depth > BUFFER_A2UI_DEPTH_MAX) return -E2BIG;
  if (index < 0 || index >= surface->component_count) return -ENOENT;
  if (marks[index] == 1) return -ELOOP;
  if (marks[index] == 2) return 0;
  marks[index] = 1;
  const struct buffer_a2ui_component_s *component = &surface->components[index];
  if (component->child_count > BUFFER_A2UI_CHILD_COUNT) return -E2BIG;
  for (size_t i = 0; i < component->child_count; i++)
    {
      int child = component->children[i];
      if (child >= surface->component_count || ++parents[child] > 1) return -EINVAL;
      int ret = graph_visit(surface, child, marks, parents, depth + 1);
      if (ret < 0) return ret;
    }
  marks[index] = 2;
  return 0;
}

static int surface_validate(struct buffer_a2ui_surface_s *surface)
{
  int root = component_find(surface, "root");
  uint8_t marks[BUFFER_A2UI_COMPONENT_COUNT] = {0};
  uint8_t parents[BUFFER_A2UI_COMPONENT_COUNT] = {0};
  if (root < 0) return -EINVAL;
  if (surface_string_budget(surface) < 0) return -E2BIG;
  for (size_t i = 0; i < surface->component_count; i++)
    {
      const struct buffer_a2ui_component_s *component =
          &surface->components[i];
      if (component->type == BUFFER_A2UI_BUTTON)
        {
          if (component->child_count != 1 ||
              component->children[0] >= surface->component_count)
            return -EINVAL;
          if (surface->components[component->children[0]].type !=
              BUFFER_A2UI_TEXT)
            return -ENOTSUP;
        }

      int ret = graph_visit(surface, (int)i, marks, parents, 1);
      if (ret < 0) return ret;
    }
  if (parents[root] != 0) return -EINVAL;
  surface->has_tree = true;
  return 0;
}

static int surface_index(const char *id)
{
  for (size_t i = 0; i < BUFFER_A2UI_SURFACE_COUNT; i++)
    if (g_surfaces[i].used && strcmp(g_surfaces[i].id, id) == 0) return (int)i;
  return -1;
}

static bool was_closed(const char *id)
{
  for (size_t i = 0; i < g_closed_count; i++)
    if (strcmp(g_closed_ids[i], id) == 0) return true;
  return false;
}

static void remember_closed(const char *id)
{
  size_t index;
  if (g_closed_count < BUFFER_A2UI_CLOSED_IDS)
    index = g_closed_count++;
  else
    {
      index = g_closed_next;
      g_closed_next = (g_closed_next + 1) % BUFFER_A2UI_CLOSED_IDS;
    }
  snprintf(g_closed_ids[index], sizeof(g_closed_ids[index]), "%s", id);
}

static int surface_allocate(const char *id, const char *catalog,
                            uint64_t task_revision, bool local_surface,
                            bool full_screen, bool explicit_show)
{
  if (!explicit_show && was_closed(id)) return -ESTALE;
  if (explicit_show)
    for (size_t i = 0; i < g_closed_count; i++)
      if (strcmp(g_closed_ids[i], id) == 0)
        {
          g_closed_ids[i][0] = 0;
          break;
        }
  int index = -1;
  for (size_t i = 0; i < BUFFER_A2UI_SURFACE_COUNT; i++)
    if (!g_surfaces[i].used) { index = (int)i; break; }
  if (index < 0)
    {
      index = 0;
      for (size_t i = 1; i < BUFFER_A2UI_SURFACE_COUNT; i++)
        if (g_surfaces[i].sequence < g_surfaces[index].sequence) index = (int)i;
      remember_closed(g_surfaces[index].id);
      if (g_active_surface == index) g_active_surface = -1;
    }
  memset(&g_surfaces[index], 0, sizeof(g_surfaces[index]));
  g_surfaces[index].used = true;
  g_surfaces[index].local_surface = local_surface;
  g_surfaces[index].full_screen = full_screen;
  g_surfaces[index].task_revision = task_revision;
  g_surfaces[index].sequence = ++g_surface_sequence;
  snprintf(g_surfaces[index].id, sizeof(g_surfaces[index].id), "%s", id);
  snprintf(g_surfaces[index].catalog_id, sizeof(g_surfaces[index].catalog_id),
           "%s", catalog);
  return index;
}

static int message_create(int payload, uint64_t revision, bool explicit_show)
{
  char id[BUFFER_A2UI_SURFACE_ID_SIZE];
  char catalog[BUFFER_A2UI_CATALOG_SIZE];
  if (object_key_count(payload) < 2 ||
      component_copy_string(payload, "surfaceId", id, sizeof(id), true) < 0 ||
      component_copy_string(payload, "catalogId", catalog, sizeof(catalog), true) < 0)
    return -EINVAL;
  if (id[0] == 0 ||
      (strcmp(catalog, A2UI_BASIC_CATALOG) != 0 &&
       strcmp(catalog, A2UI_ASSISTANT_CATALOG) != 0)) return -ENOTSUP;
  if (surface_index(id) >= 0) return -EEXIST;
  int index = surface_allocate(id, catalog, revision, false, true, explicit_show);
  return index < 0 ? index : 0;
}

/* A wire recipe does not carry client idempotency keys. Derive one from its
 * semantic action, never from its transient component-array position. */
static void recipe_request_key(char *out,size_t capacity,const char *surface,
                               const struct buffer_a2ui_component_s *step,
                               uint64_t revision)
{
  uint64_t hash=1469598103934665603ULL;
  const char *parts[]={surface,step->id,step->payload.recipe_step.title};
  for(unsigned i=0;i<3;i++){
    for(const unsigned char *p=(const unsigned char *)parts[i];*p;p++)
      {hash^=*p;hash*=1099511628211ULL;}
    hash^=0;hash*=1099511628211ULL;
  }
  for(unsigned i=0;i<8;i++){hash^=(revision>>(i*8))&255;hash*=1099511628211ULL;}
  for(unsigned i=0;i<4;i++){hash^=(step->payload.recipe_step.timer_ms>>(i*8))&255;hash*=1099511628211ULL;}
  snprintf(out,capacity,"recipe-%016llx",(unsigned long long)hash);
}

static int message_components(int payload, uint64_t revision)
{
  char id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (component_copy_string(payload, "surfaceId", id, sizeof(id), true) < 0)
    return -EINVAL;
  int index = surface_index(id);
  if (index < 0) return -ENOENT;
  if (revision < g_surfaces[index].task_revision) return -ESTALE;
  int list = object_get(payload, "components");
  if (list < 0 || g_parser.tokens[list].type != TOKEN_ARRAY) return -EINVAL;
  g_candidate = g_surfaces[index];
  size_t update_count = 0;
  for (int item = g_parser.tokens[list].first_child; item >= 0;
       item = g_parser.tokens[item].next_sibling)
    {
      if (update_count >= BUFFER_A2UI_COMPONENT_COUNT) return -E2BIG;
      struct buffer_a2ui_component_s parsed;
      struct component_update_s update = {.children_token = -1, .child_token = -1};
      int ret = component_parse(item, &g_candidate, &parsed, &update);
      if (ret < 0) return ret;
      for (size_t n = 0; n < update_count; n++)
        if (strcmp(g_candidate.components[g_updates[n].component_index].id,
                   parsed.id) == 0) return -EINVAL;
      int component = component_find(&g_candidate, parsed.id);
      if (component < 0)
        {
          if (g_candidate.component_count >= BUFFER_A2UI_COMPONENT_COUNT)
            return -ENOSPC;
          component = g_candidate.component_count++;
        }
      if (parsed.type == BUFFER_A2UI_RECIPE_STEP)
        {
          recipe_request_key(parsed.payload.recipe_step.request_id,
                             sizeof(parsed.payload.recipe_step.request_id),id,&parsed,revision);
          const struct buffer_a2ui_component_s *old=&g_candidate.components[component];
          if(old->type==BUFFER_A2UI_RECIPE_STEP&&
             !strcmp(old->payload.recipe_step.request_id,parsed.payload.recipe_step.request_id))
            snprintf(parsed.payload.recipe_step.timer_id,
                     sizeof(parsed.payload.recipe_step.timer_id),"%s",old->payload.recipe_step.timer_id);
        }
      g_candidate.components[component] = parsed;
      update.component_index = (uint8_t)component;
      g_updates[update_count++] = update;
    }

  for (size_t u = 0; u < update_count; u++)
    {
      struct buffer_a2ui_component_s *component =
          &g_candidate.components[g_updates[u].component_index];
      int16_t token = g_updates[u].children_token;
      int child_token = g_updates[u].child_token;
      size_t n = 0;
      if (token >= 0)
        for (int child = g_parser.tokens[token].first_child; child >= 0;
             child = g_parser.tokens[child].next_sibling)
          {
            char child_id[BUFFER_A2UI_COMPONENT_ID_SIZE];
            if (token_string(child, child_id, sizeof(child_id)) < 0) return -EINVAL;
            int child_index = component_find(&g_candidate, child_id);
            if (child_index < 0) return -ENOENT;
            component->children[n++] = (uint8_t)child_index;
          }
      else if (child_token >= 0)
        {
          char child_id[BUFFER_A2UI_COMPONENT_ID_SIZE];
          if (token_string(child_token, child_id, sizeof(child_id)) < 0) return -EINVAL;
          int child_index = component_find(&g_candidate, child_id);
          if (child_index < 0) return -ENOENT;
          component->children[0] = (uint8_t)child_index;
        }
    }
  int ret = surface_validate(&g_candidate);
  if (ret < 0) return ret;
  g_candidate.task_revision = revision;
  g_candidate.sequence = ++g_surface_sequence;
  g_surfaces[index] = g_candidate;
  g_active_surface = index;
  return 1;
}

static int message_data(int payload, uint64_t revision)
{
  char id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (component_copy_string(payload, "surfaceId", id, sizeof(id), true) < 0)
    return -EINVAL;
  int index = surface_index(id);
  if (index < 0) return -ENOENT;
  if (revision < g_surfaces[index].task_revision) return -ESTALE;
  g_candidate = g_surfaces[index];
  int ret = data_update(&g_candidate, payload);
  if (ret < 0) return ret;
  ret = surface_string_budget(&g_candidate);
  if (ret < 0) return ret;
  if (g_candidate.has_tree) g_active_surface = index;
  g_candidate.task_revision = revision;
  g_candidate.sequence = ++g_surface_sequence;
  g_surfaces[index] = g_candidate;
  return g_surfaces[index].has_tree ? 1 : 0;
}

static int surface_delete_index(int index)
{
  if (index < 0 || index >= BUFFER_A2UI_SURFACE_COUNT ||
      !g_surfaces[index].used) return -ENOENT;
  remember_closed(g_surfaces[index].id);
  memset(&g_surfaces[index], 0, sizeof(g_surfaces[index]));
  if (g_active_surface == index) g_active_surface = -1;
  for (size_t i = 0; i < BUFFER_A2UI_SURFACE_COUNT; i++)
    if (g_surfaces[i].used && g_surfaces[i].has_tree &&
        (g_active_surface < 0 ||
         g_surfaces[i].sequence > g_surfaces[g_active_surface].sequence))
      g_active_surface = (int)i;
  return 1;
}

static int message_delete(int payload, uint64_t revision)
{
  char id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (component_copy_string(payload, "surfaceId", id, sizeof(id), true) < 0)
    return -EINVAL;
  int index = surface_index(id);
  if (index < 0) return -ENOENT;
  if (revision < g_surfaces[index].task_revision) return -ESTALE;
  return surface_delete_index(index);
}

int buffer_a2ui_apply_message(const char *json, size_t length,
                              uint64_t task_revision, bool explicit_show)
{
  if (json == NULL || length == 0 || length > BUFFER_A2UI_MESSAGE_MAX)
    return length > BUFFER_A2UI_MESSAGE_MAX ? -E2BIG : -EINVAL;
  bool refresh = false;
  pthread_mutex_lock(&g_model_lock);
  if (task_revision < g_task_revision)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ESTALE;
    }
  int root;
  int ret = parser_run(json, length, &root);
  if (ret < 0 || g_parser.tokens[root].type != TOKEN_OBJECT ||
      object_key_count(root) != 2)
    {
      pthread_mutex_unlock(&g_model_lock);
      return ret < 0 ? ret : -EINVAL;
    }
  int version = object_get(root, "version");
  if (version < 0 || !token_equals(version, "v0.9.1"))
    {
      pthread_mutex_unlock(&g_model_lock);
      return -EPROTONOSUPPORT;
    }
  int message = -1;
  int kind = 0;
  static const char *const keys[] = {
    "createSurface", "updateComponents", "updateDataModel", "deleteSurface"
  };
  for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++)
    if (object_get(root, keys[i]) >= 0)
      {
        if (kind != 0)
          {
            pthread_mutex_unlock(&g_model_lock);
            return -EINVAL;
          }
        kind = (int)i + 1;
        message = object_get(root, keys[i]);
      }
  if (message < 0 || g_parser.tokens[message].type != TOKEN_OBJECT)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -EINVAL;
    }
  switch (kind)
    {
      case 1: ret = message_create(message, task_revision, explicit_show); break;
      case 2: ret = message_components(message, task_revision); break;
      case 3: ret = message_data(message, task_revision); break;
      case 4: ret = message_delete(message, task_revision); break;
      default: ret = -EINVAL; break;
    }
  if (ret >= 0 && task_revision > g_task_revision)
    g_task_revision = task_revision;
  if (ret > 0) { refresh = true; ret = 0; }
  buffer_a2ui_refresh_hook_fn hook = refresh ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret;
}

void buffer_a2ui_set_task_revision(uint64_t task_revision)
{
  pthread_mutex_lock(&g_model_lock);
  if (task_revision > g_task_revision) g_task_revision = task_revision;
  pthread_mutex_unlock(&g_model_lock);
}

int buffer_a2ui_set_action_handler(buffer_a2ui_action_fn handler,
                                   void *context)
{
  pthread_mutex_lock(&g_model_lock);
  g_action_handler = handler;
  g_action_context = context;
  g_action_handler_epoch++;
  pthread_mutex_unlock(&g_model_lock);
  if (handler == NULL) return 0;
  pthread_mutex_lock(&g_action_lock);
  if (!g_action_worker_started)
    {
      pthread_t worker;
      int ret = buffer_thread_create(&worker, action_worker, NULL);
      if (ret != 0)
        {
          pthread_mutex_unlock(&g_action_lock);
          pthread_mutex_lock(&g_model_lock);
          if(g_action_handler==handler&&g_action_context==context){g_action_handler=NULL;g_action_context=NULL;g_action_handler_epoch++;}
          pthread_mutex_unlock(&g_model_lock);
          return -ret;
        }
      pthread_detach(worker);
      g_action_worker_started = true;
    }
  pthread_mutex_unlock(&g_action_lock);
  return 0;
}

int buffer_a2ui_delete_surface(const char *surface_id, uint64_t task_revision)
{
  if (surface_id == NULL || surface_id[0] == 0) return -EINVAL;
  pthread_mutex_lock(&g_model_lock);
  if (task_revision < g_task_revision)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ESTALE;
    }
  int index = surface_index(surface_id);
  if (index < 0)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ENOENT;
    }
  if (task_revision < g_surfaces[index].task_revision)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ESTALE;
    }
  int ret = surface_delete_index(index);
  if (task_revision > g_task_revision) g_task_revision = task_revision;
  buffer_a2ui_refresh_hook_fn hook = ret > 0 ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret < 0 ? ret : 0;
}

void buffer_a2ui_close_active(void)
{
  buffer_a2ui_refresh_hook_fn hook = NULL;
  pthread_mutex_lock(&g_model_lock);
  if (g_active_surface >= 0)
    {
      remember_closed(g_surfaces[g_active_surface].id);
      memset(&g_surfaces[g_active_surface], 0,
             sizeof(g_surfaces[g_active_surface]));
      /* A user close returns to Buffer home. Retained surfaces stay dormant
       * until a later explicit presentation activates them. */
      g_active_surface = -1;
      hook = g_refresh_hook;
    }
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
}

/* Close the surface actually rendered, even if a newer task is waiting in
 * the model. The pending task remains dormant until explicitly presented. */
void buffer_a2ui_model_close(const char *surface_id)
{
  if (!surface_id || !surface_id[0]) return;
  pthread_mutex_lock(&g_model_lock);
  for (unsigned i = 0; i < BUFFER_A2UI_SURFACE_COUNT; i++)
    if (g_surfaces[i].used && !strcmp(g_surfaces[i].id, surface_id))
      {
        remember_closed(surface_id);
        memset(&g_surfaces[i], 0, sizeof(g_surfaces[i]));
        break;
      }
  g_active_surface = -1;
  buffer_a2ui_refresh_hook_fn hook = g_refresh_hook;
  pthread_mutex_unlock(&g_model_lock);
  if (hook) hook();
}

static void summary_copy(char *target,size_t capacity,const char *text)
{
 size_t length=strlen(text);if(length>=capacity){length=capacity-1;while(length&&((unsigned char)text[length]&0xc0)==0x80)length--;}
 memcpy(target,text,length);target[length]=0;
}
/* Bounded home summaries; never copy whole renderer trees into the UI stack. */
static void local_recipe_fingerprint(const struct buffer_a2ui_surface_s *surface,char out[17])
{
 uint64_t hash=1469598103934665603ull;const char *title="";
 for(unsigned i=0;i<surface->component_count;i++)if(surface->components[i].type==BUFFER_A2UI_TEXT&&(surface->components[i].variant==1||surface->components[i].variant==2)){title=buffer_a2ui_component_text(surface,&surface->components[i]);break;}
 for(const unsigned char *p=(const unsigned char *)title;*p;p++){hash^=*p;hash*=1099511628211ull;}hash^=0xff;hash*=1099511628211ull;
 for(unsigned i=0;i<surface->component_count;i++){
  const struct buffer_a2ui_component_s *c=&surface->components[i];if(c->type!=BUFFER_A2UI_RECIPE_STEP)continue;
  const char *parts[]={c->id,c->payload.recipe_step.title,c->payload.recipe_step.detail,c->payload.recipe_step.request_id};
  for(unsigned j=0;j<4;j++){for(const unsigned char *p=(const unsigned char *)parts[j];*p;p++){hash^=*p;hash*=1099511628211ull;}hash^=0xff;hash*=1099511628211ull;}
  for(unsigned b=0;b<4;b++){hash^=(c->payload.recipe_step.timer_ms>>(b*8))&255;hash*=1099511628211ull;}
 }
 snprintf(out,17,"%016llx",(unsigned long long)hash);
}
int buffer_a2ui_surface_cards(struct buffer_assistant_surface_card_s *cards,
                              size_t capacity)
{
  if (!cards || !capacity) return 0;
  size_t count = 0;
  if(pthread_mutex_trylock(&g_model_lock)!=0)return -EAGAIN;
  for (unsigned i = 0; i < BUFFER_A2UI_SURFACE_COUNT && count < capacity; i++)
    {
      const struct buffer_a2ui_surface_s *surface = &g_surfaces[i];
      if (!surface->used || !surface->has_tree) continue;
      bool clock = false, other = false;
      for (unsigned n = 0; n < surface->component_count; n++)
        {
          unsigned type = surface->components[n].type;
          if (type == BUFFER_A2UI_TIMER_CARD || type == BUFFER_A2UI_ALARM_CARD) clock = true;
          if (type == BUFFER_A2UI_TEXT || type == BUFFER_A2UI_TRANSLATION_RESULT ||
              type == BUFFER_A2UI_RECIPE_STEP) other = true;
        }
      if (clock && !other) continue; /* Timers/alarms come from their durable domain. */
      struct buffer_assistant_surface_card_s *card = &cards[count++];
      memset(card, 0, sizeof(*card));
      card->sequence=surface->sequence;
      snprintf(card->id, sizeof(card->id), "%s", surface->id);
      snprintf(card->title, sizeof(card->title), "回答");
      for (unsigned n = 0; n < surface->component_count; n++)
        {
          const struct buffer_a2ui_component_s *component = &surface->components[n];
          if (component->type == BUFFER_A2UI_TRANSLATION_RESULT)
            {
              snprintf(card->title, sizeof(card->title), "翻译");
              summary_copy(card->preview,sizeof(card->preview),component->payload.translation.translated);
              break;
            }
          if (component->type == BUFFER_A2UI_RECIPE_STEP)
            {
              card->task_revision=surface->task_revision;snprintf(card->recipe_operation_id,sizeof(card->recipe_operation_id),"%s",surface->recipe_operation_id);
              if(surface->recipe_operation_id[0])local_recipe_fingerprint(surface,card->recipe_fingerprint);
              card->recipe=true;snprintf(card->image_id,sizeof(card->image_id),"%s",surface->image_id);
              snprintf(card->title, sizeof(card->title), "菜谱");
              for(unsigned heading=0;heading<surface->component_count;heading++){
                const struct buffer_a2ui_component_s *text=&surface->components[heading];
                if(text->type==BUFFER_A2UI_TEXT&&(text->variant==1||text->variant==2)){
                  summary_copy(card->title,sizeof(card->title),buffer_a2ui_component_text(surface,text));break;
                }
              }
              summary_copy(card->preview,sizeof(card->preview),component->payload.recipe_step.title);
              break;
            }
          if (component->type == BUFFER_A2UI_TEXT && !card->preview[0])
            summary_copy(card->preview,sizeof(card->preview),buffer_a2ui_component_text(surface,component));
        }
    }
  pthread_mutex_unlock(&g_model_lock);
  return (int)count;
}

int buffer_a2ui_model_get_active(struct buffer_a2ui_surface_s *surface)
{
  if (surface == NULL) return -EINVAL;
  if(pthread_mutex_trylock(&g_model_lock)!=0)return -EAGAIN;
  if (g_active_surface < 0 || !g_surfaces[g_active_surface].used ||
      !g_surfaces[g_active_surface].has_tree)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ENOENT;
    }
  *surface = g_surfaces[g_active_surface];
  pthread_mutex_unlock(&g_model_lock);
  return 0;
}

int buffer_a2ui_model_surface_revision(const char *id,uint64_t *revision)
{
 if(!id||!revision)return -EINVAL;
 if(pthread_mutex_trylock(&g_model_lock)!=0)return -EAGAIN;
 int index=surface_index(id);
 int result=index<0||!g_surfaces[index].has_tree?-ENOENT:0;
 if(!result)*revision=g_surfaces[index].task_revision;
 pthread_mutex_unlock(&g_model_lock);return result;
}

int buffer_a2ui_model_get_surface(const char *surface_id,
                                  struct buffer_a2ui_surface_s *surface)
{
  if (surface_id == NULL || surface == NULL) return -EINVAL;
  pthread_mutex_lock(&g_model_lock);
  int index = surface_index(surface_id);
  if (index < 0)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ENOENT;
    }
  *surface = g_surfaces[index];
  pthread_mutex_unlock(&g_model_lock);
  return 0;
}

int buffer_a2ui_model_bind_timer(const char *surface_id,
                                 const char *component_id,
                                 const char *timer_id,
                                 uint64_t revision,const char *request_id)
{
  if (surface_id == NULL || component_id == NULL || timer_id == NULL ||
      !*timer_id || strlen(timer_id)>=BUFFER_ASSISTANT_ID_SIZE ||
      request_id==NULL || !*request_id) return -EINVAL;
  pthread_mutex_lock(&g_model_lock);
  int index = surface_index(surface_id);
  int ret = -ENOENT;
  if (index >= 0)
    {
      int comp = component_find(&g_surfaces[index], component_id);
      if (comp >= 0 && g_surfaces[index].components[comp].type ==
                       BUFFER_A2UI_RECIPE_STEP)
        {
          const struct buffer_a2ui_component_s *step=&g_surfaces[index].components[comp];
          if(g_surfaces[index].task_revision!=revision||
             strcmp(step->payload.recipe_step.request_id,request_id)||
             (step->payload.recipe_step.timer_id[0]&&strcmp(step->payload.recipe_step.timer_id,timer_id)))
            {pthread_mutex_unlock(&g_model_lock);return -ESTALE;}
          snprintf(g_surfaces[index].components[comp].payload.recipe_step.timer_id,
                   sizeof(g_surfaces[index].components[comp].payload.recipe_step.timer_id),
                   "%s", timer_id);
          g_surfaces[index].sequence = ++g_surface_sequence;
          g_active_surface = index;
          ret = 0;
        }
    }
  buffer_a2ui_refresh_hook_fn hook = ret == 0 ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret;
}

int buffer_a2ui_model_try_activate(const char *surface_id,struct buffer_a2ui_surface_s *surface)
{
  if(!surface_id||!*surface_id||strlen(surface_id)>=BUFFER_A2UI_SURFACE_ID_SIZE||!surface)return -EINVAL;
  if(pthread_mutex_trylock(&g_model_lock)!=0)return -EAGAIN;
  int index=surface_index(surface_id);
  if(index<0||!g_surfaces[index].has_tree){pthread_mutex_unlock(&g_model_lock);return -ENOENT;}
  g_surfaces[index].sequence=++g_surface_sequence;g_active_surface=index;
  *surface=g_surfaces[index];
  pthread_mutex_unlock(&g_model_lock);
  /* The navigation caller already schedules rendering; a refresh hook could
   * re-enter the renderer before its selected snapshot has been installed. */
  return 0;
}

int buffer_a2ui_model_activate(const char *surface_id)
{
  if (surface_id == NULL) return -EINVAL;
  pthread_mutex_lock(&g_model_lock);
  int index = surface_index(surface_id);
  if (index < 0 || !g_surfaces[index].has_tree)
    {
      pthread_mutex_unlock(&g_model_lock);
      return -ENOENT;
    }
  g_surfaces[index].sequence = ++g_surface_sequence;
  g_active_surface = index;
  buffer_a2ui_refresh_hook_fn hook = g_refresh_hook;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return 0;
}

int buffer_a2ui_model_set_refresh_hook(buffer_a2ui_refresh_hook_fn hook)
{
  pthread_mutex_lock(&g_model_lock);
  g_refresh_hook = hook;
  pthread_mutex_unlock(&g_model_lock);
  return 0;
}

void buffer_a2ui_actions_unavailable(int error)
{
 if(error>=0)return;
 pthread_mutex_lock(&g_action_lock);
 for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++)if(g_action_receipts[i].pending){g_action_receipts[i].pending=false;g_action_receipts[i].result=error;}
 pthread_mutex_unlock(&g_action_lock);
}
void buffer_a2ui_action_processing(const char *id)
{
 if(!id)return;
 pthread_mutex_lock(&g_action_lock);
 for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++)if(g_action_receipts[i].used&&!strcmp(g_action_receipts[i].event.id,id)){
  if(g_action_receipts[i].pending||g_action_receipts[i].result<0){g_action_receipts[i].pending=g_action_receipts[i].accepted=true;g_action_receipts[i].result=0;}break;
 }
 pthread_mutex_unlock(&g_action_lock);
}
void buffer_a2ui_action_complete(const char *id,int result)
{
 if(!id)return;
 pthread_mutex_lock(&g_action_lock);
 for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++)if(g_action_receipts[i].used&&!strcmp(g_action_receipts[i].event.id,id)){
  g_action_receipts[i].pending=false;g_action_receipts[i].result=result;break;
 }
 pthread_mutex_unlock(&g_action_lock);
}
int buffer_a2ui_model_action_status(const char *surface,const char *component,struct buffer_a2ui_action_status_s *out)
{
 if(!surface||!component||!out)return -EINVAL;
 if(pthread_mutex_trylock(&g_model_lock))return -EAGAIN;
 int index=surface_index(surface),result=-ENOENT;
 const struct buffer_a2ui_component_s *node=index<0?NULL:buffer_a2ui_find_component(&g_surfaces[index],component);
 if(node&&node->type==BUFFER_A2UI_BUTTON){
  if(pthread_mutex_trylock(&g_action_lock)){pthread_mutex_unlock(&g_model_lock);return -EAGAIN;}
  for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++){
   const struct action_receipt_s *r=&g_action_receipts[i];
   if(r->used&&r->event.revision==g_surfaces[index].task_revision&&!strcmp(r->event.surface_id,surface)&&!strcmp(r->event.component_id,component)&&!strcmp(r->event.name,node->action)&&!strcmp(r->event.context_json,node->context_json)){
    *out=(struct buffer_a2ui_action_status_s){.pending=r->pending,.accepted=r->accepted,.result=r->result};strcpy(out->id,r->event.id);result=0;break;
   }
  }
  pthread_mutex_unlock(&g_action_lock);
 }
 pthread_mutex_unlock(&g_model_lock);return result;
}
int buffer_a2ui_model_emit_action(const char *surface_id,const char *component_id,const char *action_name,const char *context_json)
{
 if(!surface_id||!component_id||!action_name||!context_json||
    strnlen(surface_id,64)>=64||strnlen(component_id,32)>=32||strnlen(action_name,48)>=48||strnlen(context_json,192)>=192)return -EINVAL;
 pthread_mutex_lock(&g_model_lock);
 int index=surface_index(surface_id),result=0;
 const struct buffer_a2ui_component_s *node=index<0?NULL:buffer_a2ui_find_component(&g_surfaces[index],component_id);
 if(!g_action_handler)result=-ENOSYS;
 else if(!node||node->type!=BUFFER_A2UI_BUTTON||strcmp(node->action,action_name)||strcmp(node->context_json,context_json))result=-ESTALE;
 if(result){pthread_mutex_unlock(&g_model_lock);return result;}
 pthread_mutex_lock(&g_action_lock);
 unsigned selected=A2UI_ACTION_QUEUE;
 for(unsigned i=0;i<A2UI_ACTION_QUEUE;i++){
  const struct action_receipt_s *r=&g_action_receipts[i];
  if(r->used&&r->event.revision==g_surfaces[index].task_revision&&!strcmp(r->event.surface_id,surface_id)&&!strcmp(r->event.component_id,component_id)&&!strcmp(r->event.name,action_name)&&!strcmp(r->event.context_json,context_json)){selected=i;break;}
 }
 if(selected<A2UI_ACTION_QUEUE&&g_action_receipts[selected].pending)result=-EALREADY;
 else if(g_action_count>=A2UI_ACTION_QUEUE)result=-ENOSPC;
 else {
  if(selected==A2UI_ACTION_QUEUE)for(unsigned n=0;n<A2UI_ACTION_QUEUE;n++){
   unsigned i=(g_action_receipt_next+n)%A2UI_ACTION_QUEUE;
   if(!g_action_receipts[i].pending){selected=i;g_action_receipt_next=(i+1)%A2UI_ACTION_QUEUE;break;}
  }
  if(selected==A2UI_ACTION_QUEUE)result=-ENOSPC;
  else {
   struct action_receipt_s *r=&g_action_receipts[selected];
   bool retry=r->used&&r->result<0&&r->event.revision==g_surfaces[index].task_revision&&!strcmp(r->event.surface_id,surface_id)&&!strcmp(r->event.component_id,component_id)&&!strcmp(r->event.name,action_name)&&!strcmp(r->event.context_json,context_json);
   char previous_id[64];snprintf(previous_id,sizeof(previous_id),"%s",retry?r->event.id:"");
   memset(r,0,sizeof(*r));r->used=r->pending=true;r->attempt=++g_action_attempt;
   struct buffer_a2ui_action_s *e=&r->event;
   if(retry)strcpy(e->id,previous_id);
   else{unsigned char entropy[16];arc4random_buf(entropy,sizeof(entropy));strcpy(e->id,"ui-");for(unsigned i=0;i<sizeof(entropy);i++)snprintf(e->id+3+2*i,3,"%02x",entropy[i]);}
   strcpy(e->surface_id,surface_id);strcpy(e->component_id,component_id);strcpy(e->name,action_name);strcpy(e->context_json,context_json);e->revision=g_surfaces[index].task_revision;e->sequence=g_surfaces[index].sequence;
   struct queued_action_s *queued=&g_action_queue[(g_action_head+g_action_count)%A2UI_ACTION_QUEUE];queued->event=*e;queued->handler_epoch=g_action_handler_epoch;queued->attempt=r->attempt;g_action_count++;pthread_cond_signal(&g_action_changed);
  }
 }
 pthread_mutex_unlock(&g_action_lock);pthread_mutex_unlock(&g_model_lock);return result;
}

const struct buffer_a2ui_component_s *buffer_a2ui_find_component(
    const struct buffer_a2ui_surface_s *surface, const char *component_id)
{
  if (surface == NULL || component_id == NULL) return NULL;
  int index = component_find(surface, component_id);
  return index < 0 ? NULL : &surface->components[index];
}

const char *buffer_a2ui_component_text(
    const struct buffer_a2ui_surface_s *surface,
    const struct buffer_a2ui_component_s *component)
{
  if (surface == NULL || component == NULL) return "";
  if (component->type == BUFFER_A2UI_BUTTON)
    {
      if (component->child_count != 1 ||
          component->children[0] >= surface->component_count)
        return "";
      const struct buffer_a2ui_component_s *label =
          &surface->components[component->children[0]];
      if (label->type != BUFFER_A2UI_TEXT) return "";
      return buffer_a2ui_component_text(surface, label);
    }
  if (component->binding[0] == 0) return component->text;
  for (size_t i = 0; i < surface->data_count; i++)
    if (strcmp(surface->data[i].path, component->binding) == 0)
      return surface->data[i].value;
  return "";
}

static uint64_t local_hash(const char *text, size_t extra)
{
  uint64_t hash = 1469598103934665603ULL;
  for (const unsigned char *p = (const unsigned char *)text; *p; p++)
    { hash ^= *p; hash *= 1099511628211ULL; }
  hash ^= (uint8_t)extra;
  return hash * 1099511628211ULL;
}

int buffer_a2ui_recipe_timer_request(char *out,size_t capacity,const char *operation_id,unsigned step)
{
 if(!out||!capacity||!operation_id||!*operation_id||step>=BUFFER_ASSISTANT_MAX_RECIPE_STEPS)return -EINVAL;
 int n=snprintf(out,capacity,"recipe-%016llx-%u",(unsigned long long)local_hash(operation_id,step+1),step+1);
 return n<0||(size_t)n>=capacity?-ENOSPC:0;
}
static int local_surface_begin(const char *id, bool full_screen)
{
  int index = surface_index(id);
  if (index < 0)
    index = surface_allocate(id, A2UI_ASSISTANT_CATALOG, g_task_revision,
                             true, full_screen, true);
  else
    {
      memset(&g_surfaces[index], 0, sizeof(g_surfaces[index]));
      g_surfaces[index].used = true;
      g_surfaces[index].local_surface = true;
      g_surfaces[index].full_screen = full_screen;
      g_surfaces[index].task_revision = g_task_revision;
      snprintf(g_surfaces[index].id, sizeof(g_surfaces[index].id), "%s", id);
      snprintf(g_surfaces[index].catalog_id,
               sizeof(g_surfaces[index].catalog_id), "%s", A2UI_ASSISTANT_CATALOG);
    }
  if (index < 0) return index;
  g_surfaces[index].component_count = 0;
  g_surfaces[index].data_count = 0;
  memset(g_surfaces[index].components, 0,
         sizeof(g_surfaces[index].components));
  return index;
}

static struct buffer_a2ui_component_s *local_component(int surface_index_value,
                                                        const char *id,
                                                        enum buffer_a2ui_component_type_e type)
{
  struct buffer_a2ui_surface_s *surface = &g_surfaces[surface_index_value];
  if (surface->component_count >= BUFFER_A2UI_COMPONENT_COUNT) return NULL;
  struct buffer_a2ui_component_s *component =
      &surface->components[surface->component_count++];
  memset(component, 0, sizeof(*component));
  snprintf(component->id, sizeof(component->id), "%s", id);
  component->type = type;
  return component;
}

static int local_surface_finish(int index)
{
  int ret = surface_validate(&g_surfaces[index]);
  if (ret < 0) return ret;
  const char *capture=NULL;
  if(!pthread_once(&capture_once,capture_key_init)&&!capture_key_error)capture=pthread_getspecific(capture_key);
  snprintf(g_surfaces[index].capture_id,sizeof(g_surfaces[index].capture_id),"%s",capture?capture:"");
  g_surfaces[index].sequence = ++g_surface_sequence;
  g_active_surface = index;
  return 0;
}

static void local_surface_id(char *out, size_t capacity,
                             const char *prefix, const char *key)
{
  snprintf(out, capacity, "%s-%016llx", prefix,
           (unsigned long long)local_hash(key, 0));
}

int buffer_a2ui_present_timer(const char *timer_id)
{
  struct buffer_assistant_item_s item;
  char surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (timer_id == NULL || strlen(timer_id) >= BUFFER_ASSISTANT_ID_SIZE)
    return -EINVAL;
  int ret = buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER, timer_id, &item);
  if (ret < 0) return ret;
  local_surface_id(surface_id, sizeof(surface_id), "local-timer", timer_id);
  pthread_mutex_lock(&g_model_lock);
  int index = local_surface_begin(surface_id, false);
  if (index >= 0)
    {
      struct buffer_a2ui_component_s *root =
          local_component(index, "root", BUFFER_A2UI_COLUMN);
      struct buffer_a2ui_component_s *timer =
          local_component(index, "timer", BUFFER_A2UI_TIMER_CARD);
      if (root == NULL || timer == NULL) ret = -ENOSPC;
      else
        {
          root->child_count = 1;
          root->children[0] = 1;
          snprintf(timer->payload.timer.id, sizeof(timer->payload.timer.id), "%s",
                   item.object_id);
          snprintf(timer->payload.timer.label, sizeof(timer->payload.timer.label), "%s",
                   item.label);
          ret = local_surface_finish(index);
        }
    }
  else ret = index;
  buffer_a2ui_refresh_hook_fn hook = ret == 0 ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret;
}

int buffer_a2ui_present_alarm(const char *alarm_id)
{
  struct buffer_assistant_item_s item;
  char surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (alarm_id == NULL || strlen(alarm_id) >= BUFFER_ASSISTANT_ID_SIZE)
    return -EINVAL;
  int ret = buffer_assistant_get(BUFFER_ASSISTANT_EVENT_ALARM, alarm_id, &item);
  if (ret < 0) return ret;
  local_surface_id(surface_id, sizeof(surface_id), "local-alarm", alarm_id);
  pthread_mutex_lock(&g_model_lock);
  int index = local_surface_begin(surface_id, false);
  if (index >= 0)
    {
      struct buffer_a2ui_component_s *root =
          local_component(index, "root", BUFFER_A2UI_COLUMN);
      struct buffer_a2ui_component_s *alarm =
          local_component(index, "alarm", BUFFER_A2UI_ALARM_CARD);
      if (root == NULL || alarm == NULL) ret = -ENOSPC;
      else
        {
          root->child_count = 1;
          root->children[0] = 1;
          snprintf(alarm->payload.alarm.id, sizeof(alarm->payload.alarm.id), "%s",
                   item.object_id);
          snprintf(alarm->payload.alarm.label, sizeof(alarm->payload.alarm.label), "%s",
                   item.label);
          alarm->payload.alarm.state = item.state;
          ret = local_surface_finish(index);
        }
    }
  else ret = index;
  buffer_a2ui_refresh_hook_fn hook = ret == 0 ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret;
}

int buffer_a2ui_present_translation(const char *operation_id,
                                    const char *original,
                                    const char *translated,
                                    const char *source_language,
                                    const char *target_language)
{
  char surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (operation_id == NULL || original == NULL || translated == NULL ||
      source_language == NULL || target_language == NULL ||
      strlen(original) >= BUFFER_A2UI_COMPONENT_TEXT_MAX ||
      strlen(translated) >= BUFFER_A2UI_COMPONENT_TEXT_MAX ||
      strlen(source_language) >= 32 || strlen(target_language) >= 32)
    return -ENOSPC;
  local_surface_id(surface_id, sizeof(surface_id), "local-translation", operation_id);
  pthread_mutex_lock(&g_model_lock);
  int index = local_surface_begin(surface_id, true);
  int ret = index;
  if (index >= 0)
    {
      struct buffer_a2ui_component_s *root =
          local_component(index, "root", BUFFER_A2UI_COLUMN);
      struct buffer_a2ui_component_s *title =
          local_component(index, "title", BUFFER_A2UI_TEXT);
      struct buffer_a2ui_component_s *translation =
          local_component(index, "translation", BUFFER_A2UI_TRANSLATION_RESULT);
      if (root == NULL || title == NULL || translation == NULL) ret = -ENOSPC;
      else
        {
          root->child_count = 2;
          root->children[0] = 1;
          root->children[1] = 2;
          title->variant = 2;
          snprintf(title->text, sizeof(title->text), "翻译");
          snprintf(translation->payload.translation.original,
                   sizeof(translation->payload.translation.original), "%s", original);
          snprintf(translation->payload.translation.translated,
                   sizeof(translation->payload.translation.translated), "%s", translated);
          snprintf(translation->payload.translation.source_language,
                   sizeof(translation->payload.translation.source_language), "%s",
                   source_language);
          snprintf(translation->payload.translation.target_language,
                   sizeof(translation->payload.translation.target_language), "%s",
                   target_language);
          ret = local_surface_finish(index);
        }
    }
  buffer_a2ui_refresh_hook_fn hook = ret == 0 ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret;
}

static int reply_utf8_validate(const unsigned char *text,size_t length);
static size_t reply_chunk_end(const unsigned char *text,size_t length,size_t start);
static int recipe_present(const char *operation_id,const char *title_text,
                              const char *ingredients,
                              const struct buffer_assistant_recipe_step_s *steps,size_t step_count,const char *image_id,bool activate,const char timer_ids[][BUFFER_ASSISTANT_ID_SIZE],const char *expected_surface)
{
 char surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
 if(!image_id||strnlen(image_id,65)>=65)return -EINVAL;
 if(!operation_id||!*operation_id||strnlen(operation_id,BUFFER_ASSISTANT_ID_SIZE)>=BUFFER_ASSISTANT_ID_SIZE||
    !title_text||!ingredients||!steps||!step_count||step_count>BUFFER_ASSISTANT_MAX_RECIPE_STEPS||
    strnlen(title_text,BUFFER_ASSISTANT_LABEL_SIZE)>=BUFFER_ASSISTANT_LABEL_SIZE||
    strnlen(ingredients,BUFFER_ASSISTANT_TEXT_SIZE)>=BUFFER_ASSISTANT_TEXT_SIZE)return -EINVAL;
 size_t length=strlen(ingredients);
 if(reply_utf8_validate((const unsigned char *)title_text,strlen(title_text))||
    reply_utf8_validate((const unsigned char *)ingredients,length))return -EINVAL;
 for(size_t i=0;i<step_count;i++){
  if(strnlen(steps[i].title,sizeof(steps[i].title))>=sizeof(steps[i].title)||
     strnlen(steps[i].detail,sizeof(steps[i].detail))>=sizeof(steps[i].detail))return -ENOSPC;
  if(reply_utf8_validate((const unsigned char *)steps[i].title,strlen(steps[i].title))||
     reply_utf8_validate((const unsigned char *)steps[i].detail,strlen(steps[i].detail)))return -EINVAL;
 }
 local_surface_id(surface_id,sizeof(surface_id),"local-recipe",operation_id);
 if(expected_surface&&strcmp(expected_surface,surface_id))return -ESTALE;
 if(timer_ids)for(size_t i=0;i<step_count;i++)if(!memchr(timer_ids[i],0,BUFFER_ASSISTANT_ID_SIZE))return -EINVAL;
 pthread_mutex_lock(&g_model_lock);
 int original_active=g_active_surface;
 if(!activate){
  if(surface_index(surface_id)>=0){pthread_mutex_unlock(&g_model_lock);return 0;}
  bool free_slot=false;for(unsigned i=0;i<BUFFER_A2UI_SURFACE_COUNT;i++)if(!g_surfaces[i].used)free_slot=true;
  if(!free_slot||was_closed(surface_id)){pthread_mutex_unlock(&g_model_lock);return free_slot?-ESTALE:-ENOSPC;}
 }
 int previous=surface_index(surface_id);
 if(previous>=0)g_candidate=g_surfaces[previous];else memset(&g_candidate,0,sizeof(g_candidate));
 int index=local_surface_begin(surface_id,true),ret=index;
 if(index>=0){
  struct buffer_a2ui_component_s *root=local_component(index,"root",BUFFER_A2UI_COLUMN);
  snprintf(g_surfaces[index].recipe_operation_id,sizeof(g_surfaces[index].recipe_operation_id),"%s",operation_id);
  snprintf(g_surfaces[index].image_id,sizeof(g_surfaces[index].image_id),"%s",image_id);
  struct buffer_a2ui_component_s *title=local_component(index,"recipe-title",BUFFER_A2UI_TEXT);
  if(!root||!title)ret=-ENOSPC;
  else{
   title->variant=2;snprintf(title->text,sizeof(title->text),"%s",title_text);
   root->children[root->child_count++]=1;
   size_t offset=0;unsigned part=0;
   do{
    size_t end=reply_chunk_end((const unsigned char *)ingredients,length,offset);
    char id[32];if(!part)strcpy(id,"ingredients");else snprintf(id,sizeof(id),"ingredients-%u",part+1);
    struct buffer_a2ui_component_s *material=local_component(index,id,BUFFER_A2UI_TEXT);
    if(!material){ret=-ENOSPC;break;}
    material->variant=0;memcpy(material->text,ingredients+offset,end-offset);material->text[end-offset]=0;
    root->children[root->child_count++]=(uint8_t)(g_surfaces[index].component_count-1);
    offset=end;part++;
   }while(offset<length);
   for(size_t i=0;ret>=0&&i<step_count;i++){
    char id[32];snprintf(id,sizeof(id),"step%u",(unsigned)i+1);
    struct buffer_a2ui_component_s *step=local_component(index,id,BUFFER_A2UI_RECIPE_STEP);
    if(!step){ret=-ENOSPC;break;}
    root->children[root->child_count++]=(uint8_t)(g_surfaces[index].component_count-1);
    snprintf(step->payload.recipe_step.title,sizeof(step->payload.recipe_step.title),"%s",steps[i].title);
    memcpy(step->payload.recipe_step.detail,steps[i].detail,strlen(steps[i].detail)+1);
    step->payload.recipe_step.timer_ms=steps[i].timer_ms;
    (void)buffer_a2ui_recipe_timer_request(step->payload.recipe_step.request_id,sizeof(step->payload.recipe_step.request_id),operation_id,(unsigned)i);
    const struct buffer_a2ui_component_s *old=buffer_a2ui_find_component(&g_candidate,step->id);
    if(old&&old->type==BUFFER_A2UI_RECIPE_STEP&&g_candidate.task_revision==g_task_revision&&
       !strcmp(old->payload.recipe_step.request_id,step->payload.recipe_step.request_id)&&
       !strcmp(old->payload.recipe_step.title,step->payload.recipe_step.title)&&
       old->payload.recipe_step.timer_ms==step->payload.recipe_step.timer_ms)
      snprintf(step->payload.recipe_step.timer_id,sizeof(step->payload.recipe_step.timer_id),"%s",old->payload.recipe_step.timer_id);
    if(timer_ids&&timer_ids[i][0])snprintf(step->payload.recipe_step.timer_id,sizeof(step->payload.recipe_step.timer_id),"%s",timer_ids[i]);
   }
   if(ret>=0)ret=local_surface_finish(index);
  }
 }
 if(!activate){g_active_surface=original_active;if(ret&&index>=0)memset(&g_surfaces[index],0,sizeof(g_surfaces[index]));}
 buffer_a2ui_refresh_hook_fn hook=ret==0?g_refresh_hook:NULL;
 pthread_mutex_unlock(&g_model_lock);if(hook)hook();return ret;
}

int buffer_a2ui_present_recipe_image(const char *operation_id,const char *title,const char *ingredients,const struct buffer_assistant_recipe_step_s *steps,size_t count,const char *image_id)
{return recipe_present(operation_id,title,ingredients,steps,count,image_id,true,NULL,NULL);}
int buffer_a2ui_restore_recipe(const struct buffer_assistant_request_s *request,const char timer_ids[][BUFFER_ASSISTANT_ID_SIZE],const char *expected_surface)
{
 if(!request||request->operation!=BUFFER_ASSISTANT_RECIPE_SHOW||!expected_surface)return -EINVAL;
 return recipe_present(request->operation_id,request->recipe_title,request->ingredients,request->steps,request->step_count,request->image_id,false,timer_ids,expected_surface);
}

int buffer_a2ui_present_recipe(const char *operation_id,const char *title,const char *ingredients,const struct buffer_assistant_recipe_step_s *steps,size_t count)
{return buffer_a2ui_present_recipe_image(operation_id,title,ingredients,steps,count,"");}

static int reply_utf8_validate(const unsigned char *text, size_t length)
{
  for (size_t i = 0; i < length;)
    {
      unsigned char lead = text[i];
      size_t width;
      uint32_t codepoint;
      if (lead < 0x80)
        {
          if (lead == 0) return -EINVAL;
          i++;
          continue;
        }
      if (lead >= 0xc2 && lead <= 0xdf)
        { width = 2; codepoint = lead & 0x1f; }
      else if (lead >= 0xe0 && lead <= 0xef)
        { width = 3; codepoint = lead & 0x0f; }
      else if (lead >= 0xf0 && lead <= 0xf4)
        { width = 4; codepoint = lead & 0x07; }
      else return -EINVAL;
      if (length - i < width) return -EINVAL;
      for (size_t n = 1; n < width; n++)
        {
          unsigned char continuation = text[i + n];
          if ((continuation & 0xc0) != 0x80) return -EINVAL;
          codepoint = (codepoint << 6) | (continuation & 0x3f);
        }
      if ((width == 3 && codepoint < 0x800) ||
          (width == 4 && codepoint < 0x10000) ||
          (codepoint >= 0xd800 && codepoint <= 0xdfff) ||
          codepoint > 0x10ffff) return -EINVAL;
      i += width;
    }
  return 0;
}

static size_t reply_chunk_end(const unsigned char *text, size_t length,
                              size_t start)
{
  size_t end = start + BUFFER_A2UI_COMPONENT_TEXT_MAX - 1;
  if (end > length) end = length;
  while (end > start && (text[end] & 0xc0) == 0x80) end--;
  return end;
}

int buffer_a2ui_present_reply(const char *operation_id, const char *text)
{
  char surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
  if (operation_id == NULL || text == NULL || operation_id[0] == 0)
    return -EINVAL;
  size_t operation_length = strnlen(operation_id, BUFFER_ASSISTANT_ID_SIZE);
  size_t length = strnlen(text, BUFFER_A2UI_REPLY_TEXT_MAX + 1);
  if (operation_length >= BUFFER_ASSISTANT_ID_SIZE) return -ENAMETOOLONG;
  if (length > BUFFER_A2UI_REPLY_TEXT_MAX) return -E2BIG;
  if (length == 0) return -EINVAL;
  int ret = reply_utf8_validate((const unsigned char *)text, length);
  if (ret < 0) return ret;

  local_surface_id(surface_id, sizeof(surface_id), "local-reply", operation_id);
  pthread_mutex_lock(&g_model_lock);
  int index = local_surface_begin(surface_id, true);
  ret = index;
  if (index >= 0)
    {
      size_t chunks = 0;
      for (size_t offset = 0; offset < length;)
        {
          size_t end = reply_chunk_end((const unsigned char *)text, length,
                                       offset);
          if (end <= offset)
            {
              ret = -EINVAL;
              break;
            }
          chunks++;
          offset = end;
        }
      if (ret >= 0 && chunks + 2 > BUFFER_A2UI_COMPONENT_COUNT) ret = -E2BIG;
      struct buffer_a2ui_component_s *root = NULL;
      struct buffer_a2ui_component_s *heading = NULL;
      if (ret >= 0)
        {
          root = local_component(index, "root", BUFFER_A2UI_COLUMN);
          heading = local_component(index, "heading", BUFFER_A2UI_TEXT);
          if (root == NULL || heading == NULL) ret = -ENOSPC;
        }
      if (ret >= 0)
        {
          root->child_count = (uint8_t)(chunks + 1);
          root->children[0] = 1;
          heading->variant = 2;
          memcpy(heading->text, "助手回复", sizeof("助手回复"));
          size_t offset = 0;
          size_t chunk_index = 0;
          while (offset < length)
            {
              size_t end = reply_chunk_end((const unsigned char *)text,
                                           length, offset);
              char component_id[BUFFER_A2UI_COMPONENT_ID_SIZE];
              snprintf(component_id, sizeof(component_id), "reply%u",
                       (unsigned int)chunk_index + 1);
              struct buffer_a2ui_component_s *piece = local_component(
                  index, component_id, BUFFER_A2UI_TEXT);
              if (piece == NULL)
                {
                  ret = -ENOSPC;
                  break;
                }
              size_t piece_length = end - offset;
              memcpy(piece->text, text + offset, piece_length);
              piece->text[piece_length] = 0;
              root->children[chunk_index + 1] =
                  (uint8_t)(chunk_index + 2);
              chunk_index++;
              offset = end;
            }
          if (ret >= 0) ret = local_surface_finish(index);
        }
    }
  buffer_a2ui_refresh_hook_fn hook = ret == 0 ? g_refresh_hook : NULL;
  pthread_mutex_unlock(&g_model_lock);
  if (hook != NULL) hook();
  return ret;
}
