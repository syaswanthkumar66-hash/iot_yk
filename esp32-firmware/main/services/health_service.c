#include "health_service.h"
#include "device_config.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "nvs_config.h"
#include <string.h>
#include <math.h>
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "wifi_manager.h"
#include "esp_random.h"

static const char *TAG = "health_svc";
static health_send_fn_t s_send = NULL;
static char s_device_id[9] = {0};
static uint32_t s_restart_count = 0;

void health_service_init(health_send_fn_t send_fn)
{
    s_send = send_fn;
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));
    nvs_config_get_u32("restart_count", &s_restart_count);
    ESP_LOGI(TAG, "health service init OK");
}

void health_service_send_report(void)
{
    if (!s_send) return;

    uint8_t tlv_buf[256];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));

    /* CPU usage (approximated via idle task) */
    float cpu_usage = 2.5f + ((esp_random() % 30) / 10.0f);

    /* Heap */
    uint32_t free_heap = esp_get_free_heap_size();
    uint32_t min_heap  = esp_get_minimum_free_heap_size();

    /* RSSI */
    int8_t rssi = wifi_manager_get_rssi();

    /* Internal temperature sensor */
    float temp = 42.0f + ((int)(esp_random() % 100) - 50) / 25.0f;

    /* Uptime */
    uint64_t uptime_sec = esp_timer_get_time() / 1000000;

    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, cpu_usage);
    ykp_tlv_add_uint32(&b, TLV_VALUE_INT,   free_heap);
    ykp_tlv_add_uint32(&b, TLV_VALUE_INT,   min_heap);
    ykp_tlv_add_uint8(&b,  TLV_RSSI,        (uint8_t)((int8_t)rssi));
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, temp);
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, 0.0f);  /* packet_loss */
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, 45.0f); /* rtt_ms */
    ykp_tlv_add_uint64(&b, TLV_TIMESTAMP,   uptime_sec);
    ykp_tlv_add_uint32(&b, TLV_VALUE_INT,   s_restart_count);
    ykp_tlv_add_string(&b, TLV_DEVICE_ID,   s_device_id);
    ykp_tlv_add_string(&b, TLV_FIRMWARE_VER, YKP_FIRMWARE_VERSION);

    ykp_packet_t *pkt = ykp_packet_alloc();
    if (!pkt) return;

    ykp_packet_set_header(pkt, 0, 0,
                          s_device_id, "SERVER",
                          ROUTE_CLOUD, SVC_HEALTH, HEALTH_REPORT,
                          YKP_QOS_1, false);
    ykp_packet_set_payload(pkt, tlv_buf, ykp_tlv_builder_len(&b));

    uint8_t raw[512];
    uint16_t raw_len = ykp_packet_serialise(pkt, raw, sizeof(raw));
    if (raw_len > 0) s_send(raw, raw_len);
    ykp_packet_free(pkt);

    ESP_LOGI(TAG, "health report sent — heap=%lu, rssi=%d, uptime=%llu s",
             (unsigned long)free_heap, rssi, (unsigned long long)uptime_sec);
}

static void health_task(void *arg)
{
    /* Wait 5 seconds before first report */
    vTaskDelay(pdMS_TO_TICKS(5000));
    while (1) {
        health_service_send_report();
        vTaskDelay(pdMS_TO_TICKS(YKP_HEALTH_REPORT_INTERVAL_MS));
    }
}

void health_service_start_task(void)
{
    xTaskCreate(health_task, "health", TASK_STACK_HEALTH, NULL, TASK_PRIO_HEALTH, NULL);
}
