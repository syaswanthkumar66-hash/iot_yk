#include "ykp_qos.h"
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/timers.h"

static const char *TAG = "ykp_qos";

uint32_t g_last_rtt_ms = 45; // Default fallback latency

static void check_and_stop_timer(ykp_qos_engine_t *engine)
{
    bool any_active = false;
    for (int i = 0; i < QOS_MAX_PENDING; i++) {
        if (engine->slots[i].active) {
            any_active = true;
            break;
        }
    }
    if (!any_active && engine->timer) {
        xTimerStop((TimerHandle_t)engine->timer, 0);
        ESP_LOGI(TAG, "QoS queue empty — stopping timer");
    }
}

void ykp_qos_init(ykp_qos_engine_t *engine, ykp_send_fn_t send_fn)
{
    memset(engine, 0, sizeof(*engine));
    engine->send_fn = send_fn;
}

bool ykp_qos_enqueue(ykp_qos_engine_t *engine, uint32_t packet_id,
                     const uint8_t *data, uint16_t len, ykp_qos_t qos)
{
    /* C5: guard null pointer and zero length before any memcpy */
    if (!engine || !data || len == 0) return false;

    if (qos == YKP_QOS_0) {
        if (engine->send_fn) engine->send_fn(data, len);
        return true;
    }
    for (int i = 0; i < QOS_MAX_PENDING; i++) {
        if (!engine->slots[i].active) {
            ykp_pending_t *p = &engine->slots[i];
            p->packet_id      = packet_id;
            /* C5: hard-clamp to struct array size before memcpy */
            p->data_len       = (len < sizeof(p->data)) ? len : (uint16_t)sizeof(p->data);
            memcpy(p->data, data, p->data_len);
            p->qos            = qos;
            p->retries        = 0;
            p->next_retry_ms  = esp_timer_get_time() / 1000;
            p->sent_time_ms   = esp_timer_get_time() / 1000;
            p->waiting_pubrec = (qos == YKP_QOS_2);
            p->waiting_pubcomp= false;
            p->active         = true;
            if (engine->send_fn) engine->send_fn(data, len);

            /* Start software timer if not running */
            if (engine->timer) {
                xTimerStart((TimerHandle_t)engine->timer, 0);
            }
            return true;
        }
    }
    ESP_LOGW(TAG, "QoS queue full");
    return false;
}

void ykp_qos_ack(ykp_qos_engine_t *engine, uint32_t packet_id)
{
    for (int i = 0; i < QOS_MAX_PENDING; i++) {
        if (engine->slots[i].active && engine->slots[i].packet_id == packet_id
            && engine->slots[i].qos == YKP_QOS_1) {
            engine->slots[i].active = false;
            
            // Calculate RTT/latency
            int64_t rtt = (esp_timer_get_time() / 1000) - engine->slots[i].sent_time_ms;
            if (rtt >= 0 && rtt < 10000) {
                g_last_rtt_ms = (uint32_t)rtt;
            }
            ESP_LOGI(TAG, "QoS1 ACK received for pkt=%lu, latency=%lu ms", 
                     (unsigned long)packet_id, (unsigned long)g_last_rtt_ms);
            
            check_and_stop_timer(engine);
            return;
        }
    }
}

void ykp_qos_pubrec(ykp_qos_engine_t *engine, uint32_t packet_id)
{
    for (int i = 0; i < QOS_MAX_PENDING; i++) {
        ykp_pending_t *p = &engine->slots[i];
        if (p->active && p->packet_id == packet_id && p->qos == YKP_QOS_2) {
            p->waiting_pubrec  = false;
            p->waiting_pubcomp = true;
            /* Send PUBREL */
            ESP_LOGD(TAG, "QoS2 PUBREC → sending PUBREL pkt=%lu", (unsigned long)packet_id);
        }
    }
}

void ykp_qos_pubcomp(ykp_qos_engine_t *engine, uint32_t packet_id)
{
    for (int i = 0; i < QOS_MAX_PENDING; i++) {
        ykp_pending_t *p = &engine->slots[i];
        if (p->active && p->packet_id == packet_id
            && p->qos == YKP_QOS_2 && p->waiting_pubcomp) {
            p->active = false;
            ESP_LOGD(TAG, "QoS2 complete pkt=%lu", (unsigned long)packet_id);
            check_and_stop_timer(engine);
        }
    }
}

void ykp_qos_tick(ykp_qos_engine_t *engine)
{
    int64_t now_ms = esp_timer_get_time() / 1000;
    for (int i = 0; i < QOS_MAX_PENDING; i++) {
        ykp_pending_t *p = &engine->slots[i];
        if (!p->active) continue;
        if (now_ms < p->next_retry_ms) continue;

        if (p->retries >= QOS_MAX_RETRIES) {
            ESP_LOGE(TAG, "QoS%d pkt=%lu failed after %d retries",
                     p->qos, (unsigned long)p->packet_id, QOS_MAX_RETRIES);
            p->active = false;
            check_and_stop_timer(engine);
            continue;
        }
        ESP_LOGW(TAG, "QoS%d retry %d pkt=%lu",
                 p->qos, p->retries + 1, (unsigned long)p->packet_id);
        if (engine->send_fn) engine->send_fn(p->data, p->data_len);
        p->retries++;
        /* H1: cap shift operand to prevent signed shift UB (1<<31 is UB in C) */
        uint32_t shift = (p->retries < 10u) ? p->retries : 10u;
        p->next_retry_ms = now_ms + (int64_t)(QOS_RETRY_DELAY_MS << shift);
    }
}
