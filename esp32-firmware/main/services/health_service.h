#ifndef HEALTH_SERVICE_H
#define HEALTH_SERVICE_H

#include <stdint.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

typedef void (*health_send_fn_t)(const uint8_t *data, uint16_t len);

void health_service_init(health_send_fn_t send_fn);
void health_service_send_report(void);
/* H5 fix: health_service_start_task removed — reports driven by main loop */

#endif
