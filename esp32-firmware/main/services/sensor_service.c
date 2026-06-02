#include "sensor_service.h"
#include "device_config.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "nvs_config.h"
#include <string.h>
#include "esp_log.h"
#include "esp_adc/adc_oneshot.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"

static const char *TAG = "sensor_svc";
static sensor_send_fn_t s_send = NULL;
static char s_device_id[9] = {0};

void sensor_service_init(sensor_send_fn_t send_fn)
{
    s_send = send_fn;
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));

    /* Configure PIR GPIO as input */
    gpio_config_t pir_cfg = {
        .pin_bit_mask = (1ULL << GPIO_PIR_MOTION),
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLDOWN_ENABLE,
        .intr_type    = GPIO_INTR_DISABLE,
    };
    gpio_config(&pir_cfg);
    ESP_LOGI(TAG, "sensor service init OK");
}

bool sensor_service_read(sensor_reading_t *out)
{
    if (!out) return false;
    memset(out, 0, sizeof(*out));

    /* Simulated DHT22 reading (replace with real driver) */
    out->temperature = 24.5f + ((int)(esp_random() % 100) - 50) / 20.0f;
    out->humidity    = 55.0f + ((int)(esp_random() % 100) - 50) / 10.0f;
    out->motion      = (gpio_get_level(GPIO_PIR_MOTION) == 1);
    out->light_raw   = (uint16_t)(esp_random() % 4096);
    out->valid       = true;
    return true;
}

void sensor_service_send_report(void)
{
    sensor_reading_t r;
    if (!sensor_service_read(&r)) return;

    uint8_t tlv_buf[128];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));

    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, r.temperature);
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, r.humidity);
    ykp_tlv_add_uint8(&b,  TLV_STATE,       r.motion ? 1 : 0);
    ykp_tlv_add_uint16(&b, TLV_VALUE_INT,   r.light_raw);
    ykp_tlv_add_uint64(&b, TLV_TIMESTAMP,   (uint64_t)(esp_timer_get_time() / 1000000));
    ykp_tlv_add_string(&b, TLV_DEVICE_ID,   s_device_id);

    ykp_packet_t *pkt = ykp_packet_alloc();
    if (!pkt) return;

    ykp_packet_set_header(pkt, 0, 0,
                          s_device_id, "SERVER",
                          ROUTE_CLOUD, SVC_SENSOR, SENSOR_REPORT,
                          YKP_QOS_1, false);
    ykp_packet_set_payload(pkt, tlv_buf, ykp_tlv_builder_len(&b));

    uint8_t raw[300];
    uint16_t raw_len = ykp_packet_serialise(pkt, raw, sizeof(raw));
    if (raw_len > 0 && s_send) s_send(raw, raw_len);
    ykp_packet_free(pkt);

    ESP_LOGI(TAG, "sensor: temp=%.1f hum=%.1f motion=%d",
             r.temperature, r.humidity, r.motion);
}

static void sensor_task(void *arg)
{
    vTaskDelay(pdMS_TO_TICKS(3000));
    while (1) {
        sensor_service_send_report();
        vTaskDelay(pdMS_TO_TICKS(60000));
    }
}

void sensor_service_start_task(void)
{
    xTaskCreate(sensor_task, "sensor", TASK_STACK_SENSOR, NULL, TASK_PRIO_SENSOR, NULL);
}
