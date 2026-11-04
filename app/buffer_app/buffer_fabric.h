/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_FABRIC_H
#define BUFFER_FABRIC_H
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <errno.h>
#define BUFFER_FABRIC_MAX_MEMBERS 64
struct buffer_fabric_member_s { char device_id[65]; bool selected; };
struct buffer_fabric_status_s
{
  int binding;
  int error;
  bool connected;
  bool active;
  int code;
  bool sync_pending;
  bool sync_complete;
  bool sync_conflict;
  bool adding_member;
  bool member_confirmation;
  char pending_member[65];
  unsigned member_count;
  uint64_t member_revision;
  unsigned member_generation;
};
#ifdef CONFIG_BUFFER_VELA_FABRIC
int buffer_fabric_init(void);
void buffer_fabric_report_error(int error);
void buffer_fabric_request_retry(void);
bool buffer_fabric_take_retry(void);
int buffer_fabric_request_sync(void);
void buffer_fabric_snapshot(struct buffer_fabric_status_s *status);
/* Published public identity pair for the current durable binding. No disk
 * access or radio lock; not a substitute for live operation authorization. */
int buffer_fabric_identity_pair(char local_id[65], char peer_id[65]);
int buffer_fabric_identity_for_peer(const char *peer,char local_id[65]);
/* Return the public, versioned QR locator for an unpaired device. It contains
 * only the Fabric Ed25519 public key; the key seed and pairing authorization
 * never leave the identity backend. */
int buffer_fabric_qr_locator(char *locator, size_t locator_size);
void buffer_fabric_confirm(bool accepted);
/* User actions enqueue onto the transport owner; UI never accesses storage. */
int buffer_fabric_add_member(void);
void buffer_fabric_cancel_add_member(void);
int buffer_fabric_remove_member(const char *device_id);
void buffer_fabric_confirm_member(const char *device_id, int code, unsigned generation, bool accepted);
void buffer_fabric_confirm_epoch(int code,unsigned generation,bool accepted);
size_t buffer_fabric_members(struct buffer_fabric_member_s *out, size_t capacity);
int buffer_fabric_service_start(void *instance);
void buffer_fabric_service_stop(void);
bool buffer_fabric_busy(void);
bool buffer_fabric_accepting_connections(void);
#else
static inline int buffer_fabric_init(void) { return 0; }
static inline int buffer_fabric_identity_pair(char local_id[65], char peer_id[65])
{ if (local_id) local_id[0] = 0; if (peer_id) peer_id[0] = 0; return -ENOTSUP; }
static inline int buffer_fabric_identity_for_peer(const char *peer,char local_id[65])
{(void)peer;if(local_id)local_id[0]=0;return -ENOTSUP;}
static inline int buffer_fabric_request_sync(void) { return -ENOTSUP; }
#endif
#endif
