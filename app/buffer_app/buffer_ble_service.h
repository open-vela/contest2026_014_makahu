/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_BLE_SERVICE_H
#define BUFFER_BLE_SERVICE_H
#include <stdbool.h>
#include <stddef.h>
int buffer_ble_service_start(void *instance);
void buffer_ble_service_stop(void);
bool buffer_ble_service_busy(void);
bool buffer_ble_service_claim_lan(void);
void buffer_ble_service_release_lan(void);
void buffer_ble_service_process(void);
void buffer_ble_service_code(char *code, size_t size);
#endif
