/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela persistent queue
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netutils/cJSON.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define BUFFER_QUEUE_DIR CONFIG_BUFFER_VELA_DATA_DIR "/queue"
#define BUFFER_PAIRING_FILE CONFIG_BUFFER_VELA_DATA_DIR "/phone.conf"
#define BUFFER_PAIRING_TMP CONFIG_BUFFER_VELA_DATA_DIR "/phone.conf.tmp"
#define BUFFER_DEVICE_ID_FILE CONFIG_BUFFER_VELA_DATA_DIR "/device.id"
#define BUFFER_DEVICE_ID_TMP CONFIG_BUFFER_VELA_DATA_DIR "/device.id.tmp"
#define BUFFER_PAIRING_CODE_FILE CONFIG_BUFFER_VELA_DATA_DIR "/pairing.code"
#define BUFFER_PAIRING_CODE_TMP CONFIG_BUFFER_VELA_DATA_DIR "/pairing.code.tmp"
#define BUFFER_FOCUS_FILE CONFIG_BUFFER_VELA_DATA_DIR "/focus.conf"
#define BUFFER_FOCUS_TMP CONFIG_BUFFER_VELA_DATA_DIR "/focus.conf.tmp"
#define BUFFER_MODE_FILE CONFIG_BUFFER_VELA_DATA_DIR "/mode.conf"
#define BUFFER_MODE_TMP CONFIG_BUFFER_VELA_DATA_DIR "/mode.conf.tmp"
#define BUFFER_PENDING_ACTION_FILE CONFIG_BUFFER_VELA_DATA_DIR "/pending.action"
#define BUFFER_PENDING_ACTION_TMP CONFIG_BUFFER_VELA_DATA_DIR "/pending.action.tmp"
#define BUFFER_PENDING_MODE_FILE CONFIG_BUFFER_VELA_DATA_DIR "/pending.mode"
#define BUFFER_PENDING_MODE_TMP CONFIG_BUFFER_VELA_DATA_DIR "/pending.mode.tmp"
#define BUFFER_CARDS_FILE CONFIG_BUFFER_VELA_DATA_DIR "/cards.json"
#define BUFFER_CARDS_TMP CONFIG_BUFFER_VELA_DATA_DIR "/cards.json.tmp"
#define BUFFER_CARDS_JSON_SIZE (16 * 1024)

static int buffer_mkdir(const char *path)
{
  if (mkdir(path, 0700) < 0 && errno != EEXIST)
    {
      return -errno;
    }

  /* The directory may predate the Buffer app with a wider mode. */
  if (chmod(path, 0700) < 0)
    {
      return -errno;
    }

  return 0;
}

static int buffer_write_atomic(const char *temporary_path,
                               const char *final_path,
                               const char *data, size_t length)
{
  FILE *file;

  file = fopen(temporary_path, "w");
  if (file == NULL)
    {
      return -errno;
    }

  if (chmod(temporary_path, 0600) < 0)
    {
      int ret = -errno;
      fclose(file);
      unlink(temporary_path);
      return ret;
    }

  if (length > 0 && fwrite(data, 1, length, file) != length)
    {
      int ret = -errno;
      fclose(file);
      unlink(temporary_path);
      return ret == 0 ? -EIO : ret;
    }

  if (fclose(file) != 0)
    {
      int ret = -errno;
      unlink(temporary_path);
      return ret == 0 ? -EIO : ret;
    }

  if (rename(temporary_path, final_path) < 0)
    {
      int ret = -errno;
      unlink(temporary_path);
      return ret;
    }

  return 0;
}

static int buffer_path(char *path, size_t size, const char *capture_id,
                       const char *suffix)
{
  int ret = snprintf(path, size, "%s/%s%s", BUFFER_QUEUE_DIR,
                     capture_id, suffix);
  if (ret < 0 || (size_t)ret >= size)
    {
      return -ENAMETOOLONG;
    }

  return 0;
}

static bool buffer_store_valid_device_id(const char *device_id)
{
  size_t length;
  size_t index;

  if (device_id == NULL)
    {
      return false;
    }

  length = strlen(device_id);
  if (length == 0 || length >= BUFFER_ID_SIZE)
    {
      return false;
    }

  for (index = 0; index < length; index++)
    {
      unsigned char character = (unsigned char)device_id[index];
      if (!((character >= 'a' && character <= 'z') ||
            (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') ||
            character == '.' || character == '_' || character == '-'))
        {
          return false;
        }
    }

  return true;
}

static int buffer_store_generate_device_id(char *device_id,
                                           size_t device_id_size)
{
  static const char alphabet[] = "0123456789abcdef";
  unsigned char random_bytes[16];
  size_t index;
  int ret;

  if (device_id == NULL || device_id_size == 0)
    {
      return -EINVAL;
    }

  arc4random_buf(random_bytes, sizeof(random_bytes));
  ret = snprintf(device_id, device_id_size, "vela-gemini-s1-");
  if (ret < 0 || (size_t)ret >= device_id_size)
    {
      return -ENAMETOOLONG;
    }

  for (index = 0; index < sizeof(random_bytes); index++)
    {
      if ((size_t)ret + 2 >= device_id_size)
        {
          return -ENAMETOOLONG;
        }

      device_id[ret++] = alphabet[random_bytes[index] >> 4];
      device_id[ret++] = alphabet[random_bytes[index] & 0x0f];
    }

  device_id[ret] = '\0';
  return 0;
}

static int buffer_is_capture_name(const char *name)
{
  size_t length = strlen(name);

  return length > 4 && strcmp(name + length - 4, ".wav") == 0;
}

int buffer_store_init(void)
{
  int ret;

  ret = buffer_mkdir(CONFIG_BUFFER_VELA_DATA_DIR);
  if (ret < 0)
    {
      return ret;
    }

  return buffer_mkdir(BUFFER_QUEUE_DIR);
}

int buffer_store_load_device_id(char *device_id, size_t device_id_size)
{
  FILE *file;
  char line[BUFFER_ID_SIZE + 2];
  char generated[BUFFER_ID_SIZE];
  int ret;

  if (device_id == NULL || device_id_size == 0)
    {
      return -EINVAL;
    }

  device_id[0] = '\0';
  file = fopen(BUFFER_DEVICE_ID_FILE, "r");
  if (file != NULL)
    {
      if (fgets(line, sizeof(line), file) != NULL)
        {
          line[strcspn(line, "\r\n")] = '\0';
          if (buffer_store_valid_device_id(line))
            {
              if (strlen(line) >= device_id_size)
                {
                  fclose(file);
                  return -ENAMETOOLONG;
                }
              snprintf(device_id, device_id_size, "%s", line);
              fclose(file);
              return 0;
            }
        }

      ret = ferror(file) ? -EIO : -EINVAL;
      fclose(file);
      return ret;
    }
  else if (errno != ENOENT)
    {
      return -errno;
    }

  ret = buffer_store_generate_device_id(generated, sizeof(generated));
  if (ret < 0)
    {
      return ret;
    }

  if (strlen(generated) >= device_id_size)
    {
      return -ENAMETOOLONG;
    }
  ret = buffer_write_atomic(BUFFER_DEVICE_ID_TMP, BUFFER_DEVICE_ID_FILE,
                            generated, strlen(generated));
  if (ret < 0)
    {
      return ret;
    }

  snprintf(device_id, device_id_size, "%s", generated);
  return 0;
}

static int buffer_store_generate_pairing_code(char *pairing_code,
                                               size_t pairing_code_size)
{
  uint32_t value;
  int ret;

  if (pairing_code == NULL || pairing_code_size < BUFFER_PAIRING_CODE_SIZE)
    {
      return -EINVAL;
    }

  /* Use the platform random source instead of boot time and PID.  The code
   * is a short bootstrap secret; the long-lived token returned after the
   * handshake protects subsequent traffic. */
  arc4random_buf(&value, sizeof(value));
  value = value % 1000000u;
  ret = snprintf(pairing_code, pairing_code_size, "%06u",
                 (unsigned int)value);
  return ret < 0 || (size_t)ret >= pairing_code_size ? -ENAMETOOLONG : 0;
}

static int buffer_store_valid_pairing_code(const char *pairing_code)
{
  int index;

  if (pairing_code == NULL || strlen(pairing_code) != 6)
    {
      return 0;
    }

  for (index = 0; index < 6; index++)
    {
      if (pairing_code[index] < '0' || pairing_code[index] > '9')
        {
          return 0;
        }
    }

  return 1;
}

int buffer_store_load_pairing_code(char *pairing_code,
                                   size_t pairing_code_size)
{
  FILE *file;
  char line[BUFFER_PAIRING_CODE_SIZE + 8];
  int ret;

  if (pairing_code == NULL || pairing_code_size < BUFFER_PAIRING_CODE_SIZE)
    {
      return -EINVAL;
    }

  pairing_code[0] = '\0';
  file = fopen(BUFFER_PAIRING_CODE_FILE, "r");
  if (file != NULL)
    {
      if (fgets(line, sizeof(line), file) != NULL)
        {
          line[strcspn(line, "\r\n")] = '\0';
          if (buffer_store_valid_pairing_code(line))
            {
              snprintf(pairing_code, pairing_code_size, "%s", line);
              fclose(file);
              return 0;
            }
        }

      fclose(file);
    }
  else if (errno != ENOENT)
    {
      return -errno;
    }

  ret = buffer_store_generate_pairing_code(pairing_code, pairing_code_size);
  if (ret < 0)
    {
      return ret;
    }

  return buffer_write_atomic(BUFFER_PAIRING_CODE_TMP,
                             BUFFER_PAIRING_CODE_FILE, pairing_code, 6);
}

int buffer_store_load_focus_interval(uint32_t *interval)
{
  char line[32];
  char extra;
  unsigned long value;
  FILE *file;
  int ret = -EINVAL;

  if (interval == NULL)
    {
      return -EINVAL;
    }
  file = fopen(BUFFER_FOCUS_FILE, "r");
  if (file == NULL)
    {
      return -errno;
    }
  if (fgets(line, sizeof(line), file) != NULL &&
      sscanf(line, "%lu %c", &value, &extra) == 1 &&
      value >= BUFFER_FOCUS_INTERVAL_MIN_MS &&
      value <= BUFFER_FOCUS_INTERVAL_MAX_MS && fgetc(file) == EOF)
    {
      ret = 0;
    }
  if (ferror(file)) ret = -EIO;
  if (fclose(file) != 0) ret = -errno;
  if (ret == 0) *interval = (uint32_t)value;
  return ret;
}

int buffer_store_save_focus_interval(uint32_t interval)
{
  char line[32];
  int length;

  if (interval < BUFFER_FOCUS_INTERVAL_MIN_MS ||
      interval > BUFFER_FOCUS_INTERVAL_MAX_MS)
    {
      return -EINVAL;
    }
  length = snprintf(line, sizeof(line), "%lu\n", (unsigned long)interval);
  return buffer_write_atomic(BUFFER_FOCUS_TMP, BUFFER_FOCUS_FILE,
                              line, (size_t)length);
}

int buffer_store_load_mode(char *mode, size_t mode_size)
{
  FILE *file;
  char line[64];

  if (mode == NULL || mode_size == 0)
    {
      return -EINVAL;
    }

  snprintf(mode, mode_size, "normal");
  file = fopen(BUFFER_MODE_FILE, "r");
  if (file == NULL)
    {
      return errno == ENOENT ? -ENOENT : -errno;
    }

  while (fgets(line, sizeof(line), file) != NULL)
    {
      line[strcspn(line, "\r\n")] = '\0';
      if (strncmp(line, "mode=", 5) == 0)
        {
          const char *value = line + 5;
          if (strcmp(value, "normal") == 0 || strcmp(value, "focus") == 0 ||
              strcmp(value, "bedtime") == 0 ||
              strcmp(value, "rabbit_hole") == 0)
            {
              snprintf(mode, mode_size, "%s", value);
            }
        }
    }

  fclose(file);
  return 0;
}

int buffer_store_save_mode(const char *mode)
{
  char content[BUFFER_MODE_SIZE + 8];
  int ret;

  if (mode == NULL || mode[0] == '\0')
    {
      return -EINVAL;
    }

  ret = snprintf(content, sizeof(content), "mode=%s\n", mode);
  if (ret < 0 || (size_t)ret >= sizeof(content))
    {
      return -ENAMETOOLONG;
    }

  return buffer_write_atomic(BUFFER_MODE_TMP, BUFFER_MODE_FILE, content,
                             (size_t)ret);
}

static int buffer_store_generate_action_id(char *action_id,
                                           size_t action_id_size)
{
  uint32_t random_value;
  int ret;

  if (action_id == NULL || action_id_size == 0)
    {
      return -EINVAL;
    }

  arc4random_buf(&random_value, sizeof(random_value));
  ret = snprintf(action_id, action_id_size, "act-%08lx-%08lx",
                 (unsigned long)time(NULL), (unsigned long)random_value);
  return ret < 0 || (size_t)ret >= action_id_size ? -ENAMETOOLONG : 0;
}

int buffer_store_save_pending_action(const char *card_id,
                                     const char *action_id,
                                     const char *action)
{
  char content[BUFFER_ID_SIZE + BUFFER_ACTION_ID_SIZE +
               BUFFER_ACTION_SIZE + 48];
  int ret;

  if (card_id == NULL || action == NULL || card_id[0] == '\0' ||
      action[0] == '\0' || action_id == NULL || action_id[0] == '\0')
    {
      return -EINVAL;
    }

  ret = snprintf(content, sizeof(content),
                 "card_id=%s\naction_id=%s\naction=%s\n",
                 card_id, action_id, action);
  if (ret < 0 || (size_t)ret >= sizeof(content))
    {
      return -ENAMETOOLONG;
    }

  return buffer_write_atomic(BUFFER_PENDING_ACTION_TMP,
                             BUFFER_PENDING_ACTION_FILE, content,
                             (size_t)ret);
}

int buffer_store_load_pending_action(char *card_id, size_t card_id_size,
                                     char *action_id, size_t action_id_size,
                                     char *action, size_t action_size)
{
  FILE *file;
  char line[BUFFER_ID_SIZE + BUFFER_ACTION_ID_SIZE +
            BUFFER_ACTION_SIZE + 32];
  int ret;

  if (card_id == NULL || card_id_size == 0 || action == NULL ||
      action_size == 0 || action_id == NULL || action_id_size == 0)
    {
      return -EINVAL;
    }

  card_id[0] = '\0';
  action_id[0] = '\0';
  action[0] = '\0';
  file = fopen(BUFFER_PENDING_ACTION_FILE, "r");
  if (file == NULL)
    {
      return -errno;
    }

  while (fgets(line, sizeof(line), file) != NULL)
    {
      line[strcspn(line, "\r\n")] = '\0';
      if (strncmp(line, "card_id=", 8) == 0)
        {
          snprintf(card_id, card_id_size, "%s", line + 8);
        }
      else if (strncmp(line, "action=", 7) == 0)
        {
          snprintf(action, action_size, "%s", line + 7);
        }
      else if (strncmp(line, "action_id=", 10) == 0)
        {
          snprintf(action_id, action_id_size, "%s", line + 10);
        }
    }

  fclose(file);
  if (card_id[0] == '\0' || action[0] == '\0')
    {
      return -EINVAL;
    }

  /* Migrate a pending action written by an older firmware.  Keeping the
   * action alive across the migration is more important than rejecting the
   * old file; the generated id makes retries idempotent from this point on. */
  if (action_id[0] == '\0')
    {
      ret = buffer_store_generate_action_id(action_id, action_id_size);
      if (ret < 0)
        {
          return ret;
        }

      return buffer_store_save_pending_action(card_id, action_id, action);
    }

  return 0;
}

void buffer_store_clear_pending_action(void)
{
  unlink(BUFFER_PENDING_ACTION_FILE);
  unlink(BUFFER_PENDING_ACTION_TMP);
}

int buffer_store_save_pending_mode(const char *mode, const char *mode_id)
{
  char content[BUFFER_MODE_SIZE + BUFFER_ACTION_ID_SIZE + 24];
  int ret;

  if (mode == NULL || mode[0] == '\0' || mode_id == NULL || mode_id[0] == '\0')
    {
      return -EINVAL;
    }

  ret = snprintf(content, sizeof(content), "mode=%s\nmode_id=%s\n",
                 mode, mode_id);
  if (ret < 0 || (size_t)ret >= sizeof(content))
    {
      return -ENAMETOOLONG;
    }

  return buffer_write_atomic(BUFFER_PENDING_MODE_TMP, BUFFER_PENDING_MODE_FILE,
                             content, (size_t)ret);
}

int buffer_store_load_pending_mode(char *mode, size_t mode_size,
                                   char *mode_id, size_t mode_id_size)
{
  FILE *file;
  char line[BUFFER_MODE_SIZE + BUFFER_ACTION_ID_SIZE + 24];
  int ret;

  if (mode == NULL || mode_size == 0 || mode_id == NULL || mode_id_size == 0)
    {
      return -EINVAL;
    }

  mode[0] = '\0';
  mode_id[0] = '\0';
  file = fopen(BUFFER_PENDING_MODE_FILE, "r");
  if (file == NULL)
    {
      return -errno;
    }

  while (fgets(line, sizeof(line), file) != NULL)
    {
      line[strcspn(line, "\r\n")] = '\0';
      if (strncmp(line, "mode=", 5) == 0)
        {
          const char *value = line + 5;
          if (strcmp(value, "normal") == 0 || strcmp(value, "focus") == 0 ||
              strcmp(value, "bedtime") == 0 ||
              strcmp(value, "rabbit_hole") == 0)
            {
              snprintf(mode, mode_size, "%s", value);
            }
        }
      else if (strncmp(line, "mode_id=", 8) == 0)
        {
          snprintf(mode_id, mode_id_size, "%s", line + 8);
        }
    }

  fclose(file);
  if (mode[0] == '\0')
    {
      return -EINVAL;
    }

  /* Older firmware stored only the mode.  Migrate that pending update so a
   * retry after an ACK loss becomes idempotent as well. */
  if (mode_id[0] == '\0')
    {
      ret = buffer_store_generate_action_id(mode_id, mode_id_size);
      if (ret < 0)
        {
          return ret;
        }

      return buffer_store_save_pending_mode(mode, mode_id);
    }

  return 0;
}

void buffer_store_clear_pending_mode(void)
{
  unlink(BUFFER_PENDING_MODE_FILE);
  unlink(BUFFER_PENDING_MODE_TMP);
}

static void buffer_store_copy_json_string(char *destination, size_t size,
                                           cJSON *object, const char *name)
{
  cJSON *value = cJSON_GetObjectItemCaseSensitive(object, name);

  if (value != NULL && cJSON_IsString(value) && value->valuestring != NULL)
    {
      snprintf(destination, size, "%s", value->valuestring);
    }
  else
    {
      destination[0] = '\0';
    }
}

static void buffer_store_append_action(char *destination, size_t size,
                                       size_t *used, const char *action)
{
  size_t length;
  bool needs_separator;

  if (destination == NULL || size == 0 || used == NULL || action == NULL)
    {
      return;
    }

  length = strlen(action);
  needs_separator = *used != 0;
  if (length == 0 || *used + (needs_separator ? 1 : 0) + length + 1 > size)
    {
      return;
    }

  if (needs_separator)
    {
      destination[(*used)++] = ',';
    }
  memcpy(destination + *used, action, length);
  *used += length;
  destination[*used] = '\0';
}

static void buffer_store_copy_json_actions(char *destination, size_t size,
                                            cJSON *object)
{
  cJSON *actions;
  cJSON *item;
  size_t used = 0;

  if (destination == NULL || size == 0)
    {
      return;
    }

  destination[0] = '\0';
  actions = cJSON_GetObjectItemCaseSensitive(object, "actions");
  if (actions == NULL)
    {
      return;
    }

  if (cJSON_IsString(actions) && actions->valuestring != NULL)
    {
      /* Read the pre-array cache format written by early firmware. */
      const char *cursor = actions->valuestring;
      while (*cursor != '\0')
        {
          const char *end = strchr(cursor, ',');
          char action[BUFFER_ACTION_SIZE];
          size_t length = end == NULL ? strlen(cursor) : (size_t)(end - cursor);

          if (length >= sizeof(action))
            {
              break;
            }

          memcpy(action, cursor, length);
          action[length] = '\0';
          buffer_store_append_action(destination, size, &used, action);
          if (end == NULL)
            {
              break;
            }
          cursor = end + 1;
        }
      return;
    }

  if (!cJSON_IsArray(actions))
    {
      return;
    }

  cJSON_ArrayForEach(item, actions)
    {
      if (!cJSON_IsString(item) || item->valuestring == NULL)
        {
          continue;
        }

      buffer_store_append_action(destination, size, &used,
                                  item->valuestring);
    }
}

static int buffer_store_add_json_actions(cJSON *object, const char *actions)
{
  cJSON *array;
  const char *cursor;

  if (object == NULL || actions == NULL)
    {
      return -EINVAL;
    }

  array = cJSON_CreateArray();
  if (array == NULL)
    {
      return -ENOMEM;
    }

  cursor = actions;
  while (*cursor != '\0')
    {
      const char *end = strchr(cursor, ',');
      size_t length = end == NULL ? strlen(cursor) : (size_t)(end - cursor);
      char action[BUFFER_ACTION_SIZE];
      cJSON *value;

      if (length >= sizeof(action))
        {
          cJSON_Delete(array);
          return -ENAMETOOLONG;
        }
      if (length > 0)
        {
          memcpy(action, cursor, length);
          action[length] = '\0';
          value = cJSON_CreateString(action);
          if (value == NULL)
            {
              cJSON_Delete(array);
              return -ENOMEM;
            }
          cJSON_AddItemToArray(array, value);
        }

      if (end == NULL)
        {
          break;
        }
      cursor = end + 1;
    }

  cJSON_AddItemToObject(object, "actions", array);
  return 0;
}

int buffer_store_load_cards(struct buffer_card_s *cards, int max_cards)
{
  FILE *file;
  char *serialized;
  size_t length;
  cJSON *root;
  cJSON *item;
  int count = 0;

  if (cards == NULL || max_cards <= 0)
    {
      return -EINVAL;
    }

  file = fopen(BUFFER_CARDS_FILE, "r");
  if (file == NULL)
    {
      return errno == ENOENT ? 0 : -errno;
    }

  serialized = calloc(1, BUFFER_CARDS_JSON_SIZE);
  if (serialized == NULL)
    {
      fclose(file);
      return -ENOMEM;
    }

  length = fread(serialized, 1, BUFFER_CARDS_JSON_SIZE - 1, file);
  if (ferror(file) != 0 ||
      (length == BUFFER_CARDS_JSON_SIZE - 1 && feof(file) == 0))
    {
      fclose(file);
      free(serialized);
      return -EFBIG;
    }
  fclose(file);

  root = cJSON_Parse(serialized);
  free(serialized);
  if (root == NULL || !cJSON_IsArray(root))
    {
      cJSON_Delete(root);
      return -EINVAL;
    }

  cJSON_ArrayForEach(item, root)
    {
      cJSON *created;

      if (count >= max_cards || !cJSON_IsObject(item))
        {
          break;
        }

      memset(&cards[count], 0, sizeof(cards[count]));
      buffer_store_copy_json_string(cards[count].card_id,
                                    sizeof(cards[count].card_id), item,
                                    "card_id");
      buffer_store_copy_json_string(cards[count].capture_id,
                                    sizeof(cards[count].capture_id), item,
                                    "capture_id");
      buffer_store_copy_json_string(cards[count].kind,
                                    sizeof(cards[count].kind), item, "kind");
      buffer_store_copy_json_string(cards[count].title,
                                    sizeof(cards[count].title), item, "title");
      buffer_store_copy_json_string(cards[count].summary,
                                    sizeof(cards[count].summary), item,
                                    "summary");
      buffer_store_copy_json_string(cards[count].answer,
                                    sizeof(cards[count].answer), item,
                                    "answer");
      buffer_store_copy_json_string(cards[count].state,
                                    sizeof(cards[count].state), item, "state");
      buffer_store_copy_json_actions(cards[count].actions,
                                     sizeof(cards[count].actions), item);
      created = cJSON_GetObjectItemCaseSensitive(item, "created_at");
      cards[count].created_at = created != NULL && cJSON_IsNumber(created)
                                  ? (int64_t)created->valuedouble : 0;
      if (cards[count].card_id[0] != '\0')
        {
          count++;
        }
    }

  cJSON_Delete(root);
  return count;
}

int buffer_store_save_cards(const struct buffer_card_s *cards, int count)
{
  cJSON *root;
  char *serialized;
  int index;
  int ret;

  if (count < 0)
    {
      return -EINVAL;
    }
  if (count > BUFFER_CARD_LIMIT)
    {
      count = BUFFER_CARD_LIMIT;
    }
  if (count > 0 && cards == NULL)
    {
      return -EINVAL;
    }

  root = cJSON_CreateArray();
  if (root == NULL)
    {
      return -ENOMEM;
    }

  for (index = 0; index < count; index++)
    {
      cJSON *item = cJSON_CreateObject();
      if (item == NULL)
        {
          cJSON_Delete(root);
          return -ENOMEM;
        }

      cJSON_AddStringToObject(item, "card_id", cards[index].card_id);
      cJSON_AddStringToObject(item, "capture_id", cards[index].capture_id);
      cJSON_AddStringToObject(item, "kind", cards[index].kind);
      cJSON_AddStringToObject(item, "title", cards[index].title);
      cJSON_AddStringToObject(item, "summary", cards[index].summary);
      cJSON_AddStringToObject(item, "answer", cards[index].answer);
      cJSON_AddStringToObject(item, "state", cards[index].state);
      ret = buffer_store_add_json_actions(item, cards[index].actions);
      if (ret < 0)
        {
          cJSON_Delete(item);
          cJSON_Delete(root);
          return ret;
        }
      cJSON_AddNumberToObject(item, "created_at",
                              (double)cards[index].created_at);
      cJSON_AddItemToArray(root, item);
    }

  serialized = cJSON_PrintUnformatted(root);
  cJSON_Delete(root);
  if (serialized == NULL)
    {
      return -ENOMEM;
    }

  if (strlen(serialized) >= BUFFER_CARDS_JSON_SIZE)
    {
      free(serialized);
      return -EFBIG;
    }

  ret = buffer_write_atomic(BUFFER_CARDS_TMP, BUFFER_CARDS_FILE,
                            serialized, strlen(serialized));
  free(serialized);
  return ret;
}

int buffer_store_pending_count(void)
{
  DIR *dir;
  struct dirent *entry;
  int count = 0;

  dir = opendir(BUFFER_QUEUE_DIR);
  if (dir == NULL)
    {
      return 0;
    }

  while ((entry = readdir(dir)) != NULL)
    {
      if (buffer_is_capture_name(entry->d_name))
        {
          count++;
        }
    }

  closedir(dir);
  return count;
}

int buffer_store_queue_limit_reached(void)
{
  return buffer_store_pending_count() >= CONFIG_BUFFER_VELA_QUEUE_LIMIT;
}

int buffer_store_list_pending(char ids[][BUFFER_ID_SIZE], int max_ids)
{
  DIR *dir;
  struct dirent *entry;
  int count = 0;

  if (ids == NULL || max_ids <= 0)
    {
      return -EINVAL;
    }

  dir = opendir(BUFFER_QUEUE_DIR);
  if (dir == NULL)
    {
      return -errno;
    }

  while ((entry = readdir(dir)) != NULL && count < max_ids)
    {
      size_t length;

      if (!buffer_is_capture_name(entry->d_name))
        {
          continue;
        }

      length = strlen(entry->d_name) - 4;
      if (length == 0 || length >= BUFFER_ID_SIZE)
        {
          continue;
        }

      memcpy(ids[count], entry->d_name, length);
      ids[count][length] = '\0';
      count++;
    }

  closedir(dir);
  return count;
}

int buffer_store_read_audio(const char *capture_id,
                            unsigned char **data, size_t *length)
{
  char path[256];
  struct stat st;
  unsigned char *buffer;
  size_t offset = 0;
  int fd;
  int ret;

  if (capture_id == NULL || data == NULL || length == NULL)
    {
      return -EINVAL;
    }

  ret = buffer_path(path, sizeof(path), capture_id, ".wav");
  if (ret < 0 || stat(path, &st) < 0)
    {
      return ret < 0 ? ret : -errno;
    }

  if (st.st_size <= 0 || st.st_size > CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES)
    {
      return -EFBIG;
    }

  buffer = malloc((size_t)st.st_size);
  if (buffer == NULL)
    {
      return -ENOMEM;
    }

  fd = open(path, O_RDONLY);
  if (fd < 0)
    {
      ret = -errno;
      free(buffer);
      return ret;
    }

  while (offset < (size_t)st.st_size)
    {
      ssize_t nread = read(fd, buffer + offset, (size_t)st.st_size - offset);
      if (nread < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          ret = -errno;
          close(fd);
          free(buffer);
          return ret;
        }

      if (nread == 0)
        {
          ret = -EIO;
          close(fd);
          free(buffer);
          return ret;
        }

      offset += (size_t)nread;
    }

  close(fd);
  *data = buffer;
  *length = offset;
  return 0;
}

int buffer_store_read_metadata(const char *capture_id, int64_t *created_at,
                               uint32_t *duration_ms, char *mode,
                               size_t mode_size)
{
  char path[256];
  char line[128];
  FILE *file;
  struct stat st;
  bool found_created_at = false;
  bool found_duration = false;
  bool found_mode = false;
  int ret;

  if (capture_id == NULL || created_at == NULL || duration_ms == NULL ||
      mode == NULL || mode_size == 0)
    {
      return -EINVAL;
    }

  *created_at = (int64_t)time(NULL) * 1000;
  *duration_ms = 0;
  snprintf(mode, mode_size, "normal");

  ret = buffer_path(path, sizeof(path), capture_id, ".wav");
  if (ret < 0 || stat(path, &st) < 0)
    {
      return ret < 0 ? ret : -errno;
    }

  /* The queued format is PCM S16LE mono at 16 kHz with a 44-byte WAV header. */
  if (st.st_size > 44)
    {
      *duration_ms = (uint32_t)(((st.st_size - 44) * 1000) / 32000);
    }

  ret = buffer_path(path, sizeof(path), capture_id, ".meta");
  if (ret < 0)
    {
      return ret;
    }

  file = fopen(path, "r");
  if (file == NULL)
    {
      return -errno;
    }

  while (fgets(line, sizeof(line), file) != NULL)
    {
      if (strncmp(line, "created_at=", 11) == 0)
        {
          *created_at = strtoll(line + 11, NULL, 10);
          found_created_at = true;
        }
      else if (strncmp(line, "duration_ms=", 12) == 0)
        {
          *duration_ms = (uint32_t)strtoul(line + 12, NULL, 10);
          found_duration = true;
        }
      else if (strncmp(line, "mode=", 5) == 0)
        {
          line[strcspn(line, "\r\n")] = '\0';
          snprintf(mode, mode_size, "%s", line + 5);
          found_mode = true;
        }
    }

  if (ferror(file) != 0)
    {
      ret = errno > 0 ? -errno : -EIO;
      fclose(file);
      return ret;
    }
  fclose(file);
  if (!found_created_at || !found_duration || !found_mode)
    {
      return -EINVAL;
    }
  if (mode[0] != '\0' && strcmp(mode, "normal") != 0 &&
      strcmp(mode, "focus") != 0 && strcmp(mode, "bedtime") != 0 &&
      strcmp(mode, "rabbit_hole") != 0)
    {
      return -EINVAL;
    }

  return 0;
}

static int buffer_store_valid_capture_state(const char *state)
{
  static const char *const states[] = {
    "queued",
    "uploading_metadata",
    "uploading_blob",
    "waiting_ack",
    "uploaded",
    "failed_retryable",
    "failed_permanent",
    "storage_full",
  };
  size_t index;

  if (state == NULL || state[0] == '\0')
    {
      return 0;
    }

  for (index = 0; index < sizeof(states) / sizeof(states[0]); index++)
    {
      if (strcmp(state, states[index]) == 0)
        {
          return 1;
        }
    }

  return 0;
}

int buffer_store_set_capture_state(const char *capture_id, const char *state)
{
  char path[256];
  char temporary_path[256];
  char line[160];
  char content[512];
  FILE *file;
  size_t used = 0;
  bool replaced = false;
  int ret;

  if (capture_id == NULL || capture_id[0] == '\0' ||
      !buffer_store_valid_capture_state(state))
    {
      return -EINVAL;
    }

  ret = buffer_path(path, sizeof(path), capture_id, ".meta");
  if (ret < 0)
    {
      return ret;
    }
  ret = buffer_path(temporary_path, sizeof(temporary_path), capture_id,
                    ".meta.state.part");
  if (ret < 0)
    {
      return ret;
    }

  file = fopen(path, "r");
  if (file == NULL)
    {
      return -errno;
    }

  while (fgets(line, sizeof(line), file) != NULL)
    {
      size_t length;

      if (strncmp(line, "state=", 6) == 0)
        {
          if (replaced)
            {
              continue;
            }

          ret = snprintf(content + used, sizeof(content) - used,
                         "state=%s\n", state);
          if (ret < 0 || (size_t)ret >= sizeof(content) - used)
            {
              fclose(file);
              return -EFBIG;
            }

          used += (size_t)ret;
          replaced = true;
          continue;
        }

      length = strlen(line);
      if (used + length >= sizeof(content))
        {
          fclose(file);
          return -EFBIG;
        }

      memcpy(content + used, line, length);
      used += length;
    }

  if (ferror(file) != 0)
    {
      ret = errno > 0 ? -errno : -EIO;
      fclose(file);
      return ret;
    }
  fclose(file);

  if (!replaced)
    {
      ret = snprintf(content + used, sizeof(content) - used,
                     "state=%s\n", state);
      if (ret < 0 || (size_t)ret >= sizeof(content) - used)
        {
          return -EFBIG;
        }
      used += (size_t)ret;
    }

  return buffer_write_atomic(temporary_path, path, content, used);
}

static int buffer_store_unlink_capture_file(const char *capture_id,
                                            const char *suffix)
{
  char path[256];
  int ret;

  ret = buffer_path(path, sizeof(path), capture_id, suffix);
  if (ret < 0)
    {
      return ret;
    }

  if (unlink(path) < 0 && errno != ENOENT)
    {
      return -errno;
    }

  return 0;
}

int buffer_store_ack(const char *capture_id)
{
  static const char *const suffixes[] = {
    ".wav",
    ".meta",
    ".meta.part",
    ".meta.state.part",
  };
  int first_error = 0;
  size_t index;

  if (capture_id == NULL || capture_id[0] == '\0')
    {
      return -EINVAL;
    }

  for (index = 0; index < sizeof(suffixes) / sizeof(suffixes[0]); index++)
    {
      int ret = buffer_store_unlink_capture_file(capture_id, suffixes[index]);
      if (ret < 0 && first_error == 0)
        {
          first_error = ret;
        }
    }

  return first_error;
}

/* Keep the enrollment receipt in the same rename as the owner credential. */
static pthread_mutex_t g_pairing_store_lock = PTHREAD_MUTEX_INITIALIZER;

static bool buffer_store_valid_enrollment_id(const char *id)
{
  if (id == NULL || strlen(id) != 32) return false;
  for (size_t i = 0; i < 32; i++)
    if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f')))
      return false;
  return true;
}

static int buffer_store_write_pairing(const char *host, const char *token,
                                      const char *phone_id, const char *request_id)
{
  char content[512];
  if (!host || !token || !phone_id || !request_id || !host[0] || !token[0] || !phone_id[0] ||
      strlen(host) > 95 || strlen(token) > 127 || strlen(phone_id) > 79 ||
      strpbrk(host, "\r\n") || strpbrk(token, "\r\n") || strpbrk(phone_id, "\r\n") ||
      (request_id[0] && !buffer_store_valid_enrollment_id(request_id))) return -EINVAL;
  int length = snprintf(content, sizeof(content),
      "host=%s\ntoken=%s\nphone_device_id=%s\nenrollment_id=%s\n",
      host, token, phone_id, request_id);
  if (length < 0 || (size_t)length >= sizeof(content)) return -ENAMETOOLONG;
  return buffer_write_atomic(BUFFER_PAIRING_TMP, BUFFER_PAIRING_FILE, content, (size_t)length);
}

int buffer_store_load_enrollment(char *host, size_t host_size,
                                 char *token, size_t token_size,
                                 char *phone_id, size_t phone_id_size,
                                 char *request_id, size_t request_id_size)
{
  if (!host || !token || !phone_id || !request_id || !host_size || !token_size ||
      !phone_id_size || request_id_size < 33) return -EINVAL;
  host[0] = token[0] = phone_id[0] = request_id[0] = 0;
  FILE *file = fopen(BUFFER_PAIRING_FILE, "r");
  if (!file) return -errno;
  char line[256];
  unsigned int seen = 0;
  int ret = 0;
  while (fgets(line, sizeof(line), file))
    {
      if (!strchr(line, '\n') && !feof(file)) { ret = -EINVAL; break; }
      line[strcspn(line, "\r\n")] = 0;
      char *out;
      const char *value;
      size_t size;
      unsigned int bit;
      if (!strncmp(line, "host=", 5))
        { out = host; size = host_size; value = line + 5; bit = 1; }
      else if (!strncmp(line, "token=", 6))
        { out = token; size = token_size; value = line + 6; bit = 2; }
      else if (!strncmp(line, "phone_device_id=", 16))
        { out = phone_id; size = phone_id_size; value = line + 16; bit = 4; }
      else if (!strncmp(line, "enrollment_id=", 14))
        { out = request_id; size = request_id_size; value = line + 14; bit = 8; }
      else { ret = -EINVAL; break; }
      if ((seen & bit) || strlen(value) >= size) { ret = -EINVAL; break; }
      strcpy(out, value); seen |= bit;
    }
  if (ferror(file)) ret = -EIO;
  fclose(file);
  if ((seen & 7) != 7 || !host[0] || !token[0] || !phone_id[0] ||
      (request_id[0] && !buffer_store_valid_enrollment_id(request_id))) ret = -EINVAL;
  if (ret < 0) host[0] = token[0] = phone_id[0] = request_id[0] = 0;
  return ret;
}

int buffer_store_load_pairing(char *host, size_t host_size,
                              char *token, size_t token_size,
                              char *phone_device_id, size_t phone_device_id_size)
{
  char request_id[33];
  return buffer_store_load_enrollment(host, host_size, token, token_size,
      phone_device_id, phone_device_id_size, request_id, sizeof(request_id));
}

int buffer_store_save_enrollment(const char *host, const char *token,
                                 const char *phone_id, const char *request_id)
{
  if (!buffer_store_valid_enrollment_id(request_id)) return -EINVAL;
  pthread_mutex_lock(&g_pairing_store_lock);
  int ret = buffer_store_write_pairing(host, token, phone_id, request_id);
  pthread_mutex_unlock(&g_pairing_store_lock);
  return ret;
}

int buffer_store_save_pairing(const char *host, const char *token,
                              const char *phone_device_id)
{
  char old_host[96], old_token[128], old_phone[80], request_id[33];
  if (!host || !token || !phone_device_id) return -EINVAL;
  pthread_mutex_lock(&g_pairing_store_lock);
  int ret = buffer_store_load_enrollment(old_host, sizeof(old_host), old_token,
      sizeof(old_token), old_phone, sizeof(old_phone), request_id, sizeof(request_id));
  /* Address discovery must preserve the receipt; a different owner/token must not. */
  if (ret < 0 || strcmp(token, old_token) || strcmp(phone_device_id, old_phone)) request_id[0] = 0;
  ret = buffer_store_write_pairing(host, token, phone_device_id, request_id);
  pthread_mutex_unlock(&g_pairing_store_lock);
  return ret;
}
