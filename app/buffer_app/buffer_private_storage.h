/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_PRIVATE_STORAGE_H
#define BUFFER_PRIVATE_STORAGE_H
/* Mount/recover only; never formats, erases or adopts a different volume. */
int buffer_private_storage_prepare(void);
/* Explicit local factory provisioning. Only fully erased, dedicated media is
 * accepted. Existing or corrupt nonblank volumes require separate recovery. */
int buffer_private_storage_provision(const char *confirmation);
#endif
