/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_DEVICE_SETTINGS_H
#define BUFFER_DEVICE_SETTINGS_H
enum buffer_device_reset { BUFFER_RESET_RESTART=1, BUFFER_RESET_CONNECTIONS, BUFFER_RESET_FACTORY };
/* Worker-only. Destructive cleanup runs on the next boot, before any writer
 * starts; its durable marker survives interrupted cleanup and is retried. */
int buffer_device_reset_request(enum buffer_device_reset kind);
int buffer_device_reset_apply_boot(void);
#endif
