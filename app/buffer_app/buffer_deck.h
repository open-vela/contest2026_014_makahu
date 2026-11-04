/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_DECK_H
#define BUFFER_DECK_H

#include "buffer_app.h"
#include <netutils/cJSON.h>
#include <mbedtls/sha256.h>
#include <math.h>
#include <string.h>
#include <time.h>

/* Call only after a closed state has been durably accepted. A later full
 * projection may be waiting on unrelated history; retire this visible tile
 * immediately instead of keeping an already archived card on the desktop. */
static inline void buffer_deck_retire_home(const char *id)
{
  pthread_mutex_lock(&g_buffer.lock);
  for(int i=0;i<g_buffer.card_count;i++)if(!strcmp(g_buffer.cards[i].card_id,id)){
    memmove(&g_buffer.cards[i],&g_buffer.cards[i+1],
            (size_t)(g_buffer.card_count-i-1)*sizeof(g_buffer.cards[0]));
    memset(&g_buffer.cards[--g_buffer.card_count],0,sizeof(g_buffer.cards[0]));
    if(g_buffer.card_index>i)g_buffer.card_index--;
    if(g_buffer.card_index>=g_buffer.card_count)g_buffer.card_index=0;
    break;
  }
  pthread_mutex_unlock(&g_buffer.lock);
}

/* Bounded streaming selection. Retain both category winners and the newest
 * fillers, so a rare category cannot be evicted before the scan completes. */
struct buffer_deck_s
{
  struct buffer_card_s recent[BUFFER_CARD_LIMIT];
  struct buffer_card_s category[3];
  int count;
  bool normal;
  bool bedtime;
  int64_t now, day_start, day_end;
};

static inline const char *buffer_deck_text(const cJSON *value,const char *key)
{
  const cJSON *v=cJSON_GetObjectItemCaseSensitive(value,key);
  return cJSON_IsString(v) ? v->valuestring : "";
}

static inline bool buffer_deck_fingerprint(const cJSON *card,char hex[65])
{
  mbedtls_sha256_context context;
  unsigned char digest[32],zero=0;
  const char *const keys[]={"title","summary","answer","kind","state"};
  mbedtls_sha256_init(&context);
  int ret=mbedtls_sha256_starts(&context,0);
  for (unsigned i=0;i<sizeof(keys)/sizeof(keys[0]) && !ret;i++)
    {
      const char *value=buffer_deck_text(card,keys[i]);
      ret=mbedtls_sha256_update(&context,(const unsigned char *)value,strlen(value));
      if (!ret) ret=mbedtls_sha256_update(&context,&zero,1);
    }
  const unsigned char confirmed=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(card,"classification_confirmed")) ? '1':'0';
  if (!ret) ret=mbedtls_sha256_update(&context,&confirmed,1);
  if (!ret) ret=mbedtls_sha256_update(&context,&zero,1);
  const cJSON *tag,*tags=cJSON_GetObjectItemCaseSensitive(card,"tags");
  cJSON_ArrayForEach(tag,tags)
    {
      if (!cJSON_IsString(tag)) {ret=-1;break;}
      if (!ret) ret=mbedtls_sha256_update(&context,(const unsigned char *)tag->valuestring,strlen(tag->valuestring));
      if (!ret) ret=mbedtls_sha256_update(&context,&zero,1);
    }
  if (!ret) ret=mbedtls_sha256_finish(&context,digest);
  mbedtls_sha256_free(&context);
  if (ret) return false;
  static const char digits[]="0123456789abcdef";
  for (unsigned i=0;i<32;i++) {hex[i*2]=digits[digest[i]>>4];hex[i*2+1]=digits[digest[i]&15];}
  hex[64]=0;return true;
}

static inline int64_t buffer_deck_number(const cJSON *object, const char *key)
{
  const cJSON *v = cJSON_GetObjectItemCaseSensitive(object, key);
  return cJSON_IsNumber(v) && isfinite(v->valuedouble) &&
         v->valuedouble >= 0 && v->valuedouble <= 9007199254740991.0 &&
         floor(v->valuedouble) == v->valuedouble ? (int64_t)v->valuedouble : 0;
}

static inline void buffer_deck_metadata(struct buffer_card_s *card,
                                        const cJSON *value)
{
  const cJSON *schedule = cJSON_GetObjectItemCaseSensitive(value, "schedule");
  const cJSON *display = cJSON_GetObjectItemCaseSensitive(value, "display_return");
  card->schedule_start = buffer_deck_number(schedule, "start");
  card->schedule_end = buffer_deck_number(schedule, "end");
  card->hidden_until = buffer_deck_number(display, "hidden_until");
  card->returned_at = buffer_deck_number(display, "returned_at");
  char fingerprint[65];
  const char *expected=buffer_deck_text(value,"bedtime_fingerprint");
  card->bedtime_readable=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(value,"bedtime_readable")) &&
      strlen(expected)==64 && buffer_deck_fingerprint(value,fingerprint) && !strcmp(expected,fingerprint);
}

static inline void buffer_deck_init(struct buffer_deck_s *deck, const char *mode,
                                    int64_t now)
{
  memset(deck, 0, sizeof(*deck));
  deck->normal = !strcmp(mode,"normal");
  deck->bedtime = !strcmp(mode,"bedtime");
  deck->now = now;
  time_t seconds = (time_t)(now / 1000);
  struct tm day;
  if (localtime_r(&seconds, &day))
    {
      day.tm_hour = day.tm_min = day.tm_sec = 0;
      day.tm_isdst = -1;
      time_t start = mktime(&day);
      day.tm_mday++;
      day.tm_isdst = -1;
      time_t end = mktime(&day);
      if (start >= 0 && end > start)
        { deck->day_start = (int64_t)start * 1000; deck->day_end = (int64_t)end * 1000; }
    }
}

static inline bool buffer_deck_before(const struct buffer_card_s *a,
                                      const struct buffer_card_s *b)
{
  if (a->returned_at != b->returned_at) return a->returned_at > b->returned_at;
  if (a->created_at != b->created_at) return a->created_at > b->created_at;
  return strcmp(a->card_id, b->card_id) < 0;
}

static inline void buffer_deck_offer(struct buffer_deck_s *deck,
                                     const struct buffer_card_s *card)
{
  if (!card->card_id[0] || !strcmp(card->state, "archived") ||
      !strcmp(card->state, "done") || !strcmp(card->state, "deleted") ||
      !strcmp(card->state, "pending_transcription") ||
      (!strcmp(card->state, "later") && card->hidden_until > 0)) return;
  if (deck->bedtime && (!card->bedtime_readable || !strcmp(card->state,"later") ||
      !strcmp(card->state,"rabbit_hole") || !strcmp(card->state,"rabbit_hole_candidate") ||
      !strcmp(card->state,"research_requested"))) return;
  for (int i = 0; i < deck->count; i++)
    if (!strcmp(deck->recent[i].card_id, card->card_id)) return;
  int group = -1;
  if (deck->normal)
    {
      if (!strcmp(card->kind, "spark_card")) group = 0;
      else if (!strcmp(card->kind, "task_card") && deck->day_end > 0 &&
               card->schedule_start > 0 && card->schedule_end > card->schedule_start &&
               card->schedule_start < deck->day_end && card->schedule_end > deck->day_start) group = 1;
      else if (!strcmp(card->kind, "question_card") || !strcmp(card->kind, "answer_card")) group = 2;
      if (group >= 0 && (!deck->category[group].card_id[0] ||
                        buffer_deck_before(card, &deck->category[group])))
        deck->category[group] = *card;
    }
  int at = 0;
  while (at < deck->count && buffer_deck_before(&deck->recent[at], card)) at++;
  if (at >= BUFFER_CARD_LIMIT) return;
  int end = deck->count < BUFFER_CARD_LIMIT ? deck->count++ : BUFFER_CARD_LIMIT - 1;
  for (int i = end; i > at; i--) deck->recent[i] = deck->recent[i - 1];
  deck->recent[at] = *card;
}

static inline int buffer_deck_finish(const struct buffer_deck_s *deck,
                                     struct buffer_card_s *cards, int maximum)
{
  int count = 0;
  if (maximum > BUFFER_CARD_LIMIT) maximum = BUFFER_CARD_LIMIT;
  for (int i = 0; i < 3 && count < maximum; i++)
    if (deck->normal && deck->category[i].card_id[0]) cards[count++] = deck->category[i];
  for (int i = 0; i < deck->count && count < maximum; i++)
    {
      bool duplicate = false;
      for (int j = 0; j < count; j++)
        if (!strcmp(cards[j].card_id, deck->recent[i].card_id)) duplicate = true;
      if (!duplicate) cards[count++] = deck->recent[i];
    }
  return count;
}
#endif
