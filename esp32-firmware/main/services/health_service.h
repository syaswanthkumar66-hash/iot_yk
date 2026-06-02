#ifndef HEALTH_SERVICE_H
#define HEALTH_SERVICE_H

#include <stdint.h>
#include <stdbool.h>

typedef void (*health_send_fn_t)(const uint8_t *data, uint16_t len);

void health_service_init(health_send_fn_t send_fn);
void health_service_start_task(void);
void health_service_send_report(void);

#endif
