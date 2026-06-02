#ifndef SENSOR_SERVICE_H
#define SENSOR_SERVICE_H

#include <stdint.h>
#include <stdbool.h>

typedef void (*sensor_send_fn_t)(const uint8_t *data, uint16_t len);

typedef struct {
    float    temperature;  /* Celsius */
    float    humidity;     /* Percent */
    bool     motion;       /* PIR */
    uint16_t light_raw;    /* ADC 0-4095 */
    bool     valid;
} sensor_reading_t;

void sensor_service_init(sensor_send_fn_t send_fn);
void sensor_service_start_task(void);
bool sensor_service_read(sensor_reading_t *out);
void sensor_service_send_report(void);

#endif
