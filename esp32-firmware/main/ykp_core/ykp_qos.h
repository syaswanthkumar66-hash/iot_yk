#ifndef YKP_QOS_H
#define YKP_QOS_H

#include <stdint.h>
#include <stdbool.h>
#include "ykp_constants.h"

#define QOS_MAX_PENDING    32
#define QOS_RETRY_DELAY_MS 500
#define QOS_MAX_RETRIES    3

typedef void (*ykp_send_fn_t)(const uint8_t *data, uint16_t len);

typedef struct {
    uint32_t packet_id;
    uint8_t  data[1400];
    uint16_t data_len;
    ykp_qos_t qos;
    uint8_t  retries;
    int64_t  next_retry_ms;
    int64_t  sent_time_ms;
    bool     waiting_pubrec;   /* QoS2 phase 1 */
    bool     waiting_pubcomp;  /* QoS2 phase 2 */
    bool     active;
} ykp_pending_t;

typedef struct {
    ykp_pending_t  slots[QOS_MAX_PENDING];
    ykp_send_fn_t  send_fn;
    void          *timer;         /* FreeRTOS TimerHandle_t pointer */
} ykp_qos_engine_t;

void ykp_qos_init(ykp_qos_engine_t *engine, ykp_send_fn_t send_fn);
bool ykp_qos_enqueue(ykp_qos_engine_t *engine, uint32_t packet_id,
                     const uint8_t *data, uint16_t len, ykp_qos_t qos);
void ykp_qos_ack(ykp_qos_engine_t *engine, uint32_t packet_id);
void ykp_qos_pubrec(ykp_qos_engine_t *engine, uint32_t packet_id);
void ykp_qos_pubcomp(ykp_qos_engine_t *engine, uint32_t packet_id);
void ykp_qos_tick(ykp_qos_engine_t *engine);

extern uint32_t g_last_rtt_ms;

#endif /* YKP_QOS_H */
