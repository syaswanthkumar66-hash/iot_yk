#include "ykp_session.h"
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"

static const char *TAG = "ykp_session";

void ykp_session_init(ykp_session_t *s, ykp_security_ctx_t *sec_ctx)
{
    memset(s, 0, sizeof(*s));
    s->sec_ctx        = sec_ctx;
    s->state          = SESSION_STATE_IDLE;
    s->key_rotate_at  = YKP_KEY_ROTATE_PACKET_COUNT;
    /* M1 fix: start at 1 \u2014 pkt_id=0 is reserved and rejected by replay window */
    s->packet_counter = 1;
}

uint32_t ykp_session_next_packet_id(ykp_session_t *s)
{
    s->packet_counter++;

    /* Time-based rotation check */
    int64_t now_ms = esp_timer_get_time() / 1000;
    if (s->session_start_ms > 0 &&
        (now_ms - s->session_start_ms) >= YKP_KEY_ROTATE_INTERVAL_MS) {
        s->needs_key_rotate = true;
    }

    /* Count-based rotation check */
    if (s->packet_counter >= s->key_rotate_at) {
        s->needs_key_rotate = true;
    }

    return s->packet_counter;
}

void ykp_session_set_active(ykp_session_t *s, uint32_t session_id)
{
    s->session_id      = session_id;
    s->state           = SESSION_STATE_ACTIVE;
    s->session_start_ms = esp_timer_get_time() / 1000;
    /* M1 fix: start at 1 after activation — pkt_id=0 is reserved */
    s->packet_counter  = 1;
    s->needs_key_rotate = false;
    ESP_LOGI(TAG, "session ACTIVE id=0x%08lX", (unsigned long)session_id);
}

void ykp_session_store_nonce(ykp_session_t *s,
                              const uint8_t *nonce, uint16_t len)
{
    uint16_t copy_len = len < YKP_NONCE_LEN ? len : YKP_NONCE_LEN;
    memcpy(s->server_nonce, nonce, copy_len);
}

bool ykp_session_is_active(const ykp_session_t *s)
{
    return s && s->state == SESSION_STATE_ACTIVE;
}

void ykp_session_reset(ykp_session_t *s)
{
    ESP_LOGI(TAG, "session RESET");
    s->state          = SESSION_STATE_IDLE;
    s->session_id     = 0;
    /* M1 fix: reset to 1, never 0 */
    s->packet_counter = 1;
    s->needs_key_rotate = false;
    s->session_start_ms = 0;
    /* Wipe stale nonce — prevents reuse across key rotations (fix M2) */
    memset(s->server_nonce, 0, YKP_NONCE_LEN);
    if (s->sec_ctx) {
        s->sec_ctx->session.active = false;
    }
}

bool ykp_session_rotation_due(const ykp_session_t *s)
{
    return s && s->needs_key_rotate;
}
