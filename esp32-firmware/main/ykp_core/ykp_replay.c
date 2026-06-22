#include "ykp_replay.h"
#include "esp_log.h"

static const char *TAG = "ykp_replay";

void ykp_replay_init(ykp_replay_ctx_t *ctx)
{
    ctx->highest_id  = 0;
    ctx->window_bits = 0;
    ctx->initialised = true;
}

void ykp_replay_reset(ykp_replay_ctx_t *ctx)
{
    ykp_replay_init(ctx);
}

bool ykp_replay_check(ykp_replay_ctx_t *ctx, uint32_t pkt_id)
{
    if (!ctx->initialised) {
        ykp_replay_init(ctx);
    }

    /* Reject packet_id=0 — reserved, never a valid TX packet (fix M1) */
    if (pkt_id == 0) {
        ESP_LOGW(TAG, "packet_id=0 rejected — reserved ID");
        return false;
    }

    /* First packet — accept */
    if (ctx->highest_id == 0 && ctx->window_bits == 0) {
        ctx->highest_id  = pkt_id;
        ctx->window_bits = 1ULL;
        return true;
    }

    if (pkt_id > ctx->highest_id) {
        /* Advance the window */
        uint32_t shift = pkt_id - ctx->highest_id;
        if (shift >= YKP_REPLAY_WINDOW_SIZE) {
            /* Too large a jump — clear window completely */
            ctx->window_bits = 1ULL;
        } else {
            ctx->window_bits <<= shift;
            ctx->window_bits  |= 1ULL;
        }
        ctx->highest_id = pkt_id;
        return true;
    }

    uint32_t diff = ctx->highest_id - pkt_id;
    if (diff >= YKP_REPLAY_WINDOW_SIZE) {
        ESP_LOGW(TAG, "packet_id=%lu too old (highest=%lu) — replay rejected",
                 (unsigned long)pkt_id, (unsigned long)ctx->highest_id);
        return false;
    }

    uint64_t bit = 1ULL << diff;
    if (ctx->window_bits & bit) {
        ESP_LOGW(TAG, "duplicate packet_id=%lu — replay rejected",
                 (unsigned long)pkt_id);
        return false;
    }

    ctx->window_bits |= bit;
    return true;
}
