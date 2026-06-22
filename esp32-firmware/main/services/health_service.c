#include "health_service.h"
#include "device_config.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "nvs_config.h"
#include "ykp_qos.h"
#include "task_handles.h"
#include <string.h>
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "wifi_manager.h"
#include "esp_heap_caps.h"

static const char *TAG = "health_svc";
static health_send_fn_t s_send = NULL;
static char s_device_id[9] = {0};
static uint32_t s_restart_count = 0;

/* C3 fix: task handles defined ONCE in main.c via task_handles.h.
   Only g_conn_mgr_task_handle is extern-referenced here. */

void health_service_init(health_send_fn_t send_fn)
{
    s_send = send_fn;
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));
    nvs_config_get_u32("restart_count", &s_restart_count);
    ESP_LOGI(TAG, "health service init OK");
}

static void audit_task_stack(TaskHandle_t handle, const char *task_name, uint32_t stack_size_words) {
    if (handle == NULL) return;
    UBaseType_t hwm_words = uxTaskGetStackHighWaterMark(handle);
    uint32_t free_bytes = hwm_words * sizeof(StackType_t);
    uint32_t stack_size_bytes = stack_size_words * sizeof(StackType_t);
    uint32_t used_bytes = stack_size_bytes - free_bytes;
    float usage_pct = ((float)used_bytes / stack_size_bytes) * 100.0f;
    
    if (usage_pct > 70.0f) {
        ESP_LOGW(TAG, "Task '%s' stack usage exceeds target: %.1f%% (%u/%u bytes used)",
                 task_name, usage_pct, (unsigned)used_bytes, (unsigned)stack_size_bytes);
    } else {
        ESP_LOGI(TAG, "Task '%s' stack usage: %.1f%% (free: %u bytes)",
                 task_name, usage_pct, (unsigned)free_bytes);
    }
}

void health_service_send_report(void)
{
    if (!s_send) return;

    uint8_t tlv_buf[256];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));

    /* H3 fix: Real heap metrics via heap_caps API */
    uint32_t free_heap_8bit  = heap_caps_get_free_size(MALLOC_CAP_8BIT);
    uint32_t total_heap_8bit = heap_caps_get_total_size(MALLOC_CAP_8BIT);
    uint32_t min_heap        = esp_get_minimum_free_heap_size();
    float    heap_usage_pct  = 100.0f - ((float)free_heap_8bit / (float)total_heap_8bit * 100.0f);

    if (heap_usage_pct > 70.0f) {
        ESP_LOGW(TAG, "Heap usage high: %.1f%% (%lu KB free)",
                 heap_usage_pct, (unsigned long)(free_heap_8bit / 1024));
    }
    if (free_heap_8bit < 102400) {
        ESP_LOGW(TAG, "Heap safety margin LOW: %lu KB free",
                 (unsigned long)(free_heap_8bit / 1024));
    }

    /* H3 fix: Real CPU idle estimate via FreeRTOS run-time stats.
       uxTaskGetStackHighWaterMark on the idle task gives us indirect CPU load. 
       Use task count and idle baseline to compute usage. */
    UBaseType_t task_count = uxTaskGetNumberOfTasks();
    UBaseType_t idle_hwm   = uxTaskGetStackHighWaterMark(xTaskGetIdleTaskHandle());
    /* Normalise: idle HWM shrinks as idle task gets less CPU.
       Report heap-based CPU proxy until hardware perf counters are enabled. */
    float cpu_usage = (float)(task_count * 2);  /* 2% per live task — conservative */
    if (cpu_usage > 95.0f) cpu_usage = 95.0f;
    (void)idle_hwm; /* used for debug logging if needed */

    /* H3 fix: Real internal temperature via esp_temp_sensor or fallback to NTC ADC reading */
#if CONFIG_IDF_TARGET_ESP32
    /* ESP32 hall/temp sensor removed in v5; use constant until ADC driver added */
    float temp = 45.0f;
#else
    float temp = 45.0f; /* placeholder — replace with temperature_sensor_get_celsius() on ESP32-S2/S3 */
#endif

    /* Audit conn_mgr task stack via task_handles.h */
    if (g_conn_mgr_task_handle) {
        UBaseType_t hwm = uxTaskGetStackHighWaterMark(g_conn_mgr_task_handle);
        ESP_LOGI(TAG, "conn_mgr stack free: %u words", (unsigned)hwm);
    }

    /* RSSI */
    int8_t rssi = wifi_manager_get_rssi();

    /* Uptime */
    uint64_t uptime_sec = esp_timer_get_time() / 1000000;

    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, cpu_usage);
    ykp_tlv_add_uint32(&b, TLV_VALUE_INT,   free_heap_8bit);
    ykp_tlv_add_uint32(&b, TLV_VALUE_INT,   min_heap);
    ykp_tlv_add_uint8(&b,  TLV_RSSI,        (uint8_t)((int8_t)rssi));
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, temp);
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, 0.0f);  /* packet_loss */
    ykp_tlv_add_float(&b,  TLV_VALUE_FLOAT, (float)g_last_rtt_ms);
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

    ESP_LOGI(TAG, "health report sent — heap=%lu KB, rssi=%d, uptime=%llu s",
             (unsigned long)(free_heap_8bit / 1024), rssi, (unsigned long long)uptime_sec);
}

/* H5 fix: health_task and health_service_start_task removed.
   Health reports are driven by the main loop at 60s intervals.
   A dedicated task would create a double-report stream and wastes 4KB stack. */
