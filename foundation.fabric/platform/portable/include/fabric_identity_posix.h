/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#ifndef FOUNDATION_FABRIC_IDENTITY_POSIX_H
#define FOUNDATION_FABRIC_IDENTITY_POSIX_H
#include "fabric_ble.h"
/* Framework-owned backend, same raw 32-byte fabric-device.key and DeviceId
 * "<hex> trusted|pending|blocked" records as fabric-identity. Public-key files
 * are a cache; only trusted-devices.txt grants trust. No phone.conf/token
 * import. Directory must be private and filesystem must honor mode, fsync and
 * atomic rename. FAT/mode-ignoring mounts fail closed. Encrypted-at-rest
 * protection, if required by device policy, is supplied by the private
 * filesystem host. */
struct fabric_identity_posix {
  const char *directory;
};
/* random callback returns 0 after filling ALL bytes from a real CSPRNG. */
int fabric_identity_posix_load(struct fabric_identity_posix *,
                               int (*random)(void *, uint8_t *, size_t), void *,
                               uint8_t seed[32]);
/* Suitable directly as fabric_persist_peer callback. Existing blocked peers are
 * never unblocked by pairing. No default/automatic trust on key generation. */
int32_t fabric_identity_posix_persist_peer(void *, const uint8_t public_key[32],
                                           const uint8_t device_id[32]);
int fabric_identity_posix_trusted(struct fabric_identity_posix *,
                                  const uint8_t public_key[32]);
/* Fetch public cache only after checking the authoritative trust record. */
int fabric_identity_posix_peer(struct fabric_identity_posix *,
                               const uint8_t device_id[32],
                               uint8_t public_key[32]);
#endif
