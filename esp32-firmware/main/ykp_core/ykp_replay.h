#ifndef YKP_REPLAY_H
#define YKP_REPLAY_H

#include <stdint.h>
#include <stdbool.h>
#include "ykp_constants.h"

/* ═══════════════════════════════════════════════
   YKP Replay Protection — Sliding Window
   Window size: 64 packets
   ═══════════════════════════════════════════════ */

typedef struct {
    uint32_t highest_id;               /* Highest packet_id seen */
    uint64_t window_bits;              /* Bitmask of last 64 IDs */
    bool     initialised;
} ykp_replay_ctx_t;

/**
 * @brief Initialise replay context.
 */
void ykp_replay_init(ykp_replay_ctx_t *ctx);

/**
 * @brief Check and register a packet_id.
 *        Returns true if packet is VALID (not replay, not too old).
 *        Returns false if duplicate or out of window.
 */
bool ykp_replay_check(ykp_replay_ctx_t *ctx, uint32_t packet_id);

/**
 * @brief Reset replay context (on new session).
 */
void ykp_replay_reset(ykp_replay_ctx_t *ctx);

#endif /* YKP_REPLAY_H */
