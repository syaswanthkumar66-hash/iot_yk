#ifndef YKP_SESSION_H
#define YKP_SESSION_H

#include <stdint.h>
#include <stdbool.h>
#include "ykp_security.h"
#include "ykp_constants.h"

/* ═══════════════════════════════════════════════
   YKP Session Manager
   Manages auth state machine + packet counter
   ═══════════════════════════════════════════════ */

typedef enum {
    SESSION_STATE_IDLE         = 0,
    SESSION_STATE_HELLO_SENT   = 1,
    SESSION_STATE_ECDH_SENT    = 2,
    SESSION_STATE_ACTIVE       = 3,
    SESSION_STATE_ROTATING     = 4,
    SESSION_STATE_CLOSED       = 5,
} ykp_session_state_t;

typedef struct {
    ykp_session_state_t  state;
    uint32_t             session_id;
    uint32_t             packet_counter;    /* monotonic TX counter */
    uint32_t             rx_counter;        /* last seen RX counter */
    uint32_t             key_rotate_at;     /* rotate after N packets */
    int64_t              session_start_ms;  /* for time-based rotation */
    uint8_t              server_nonce[YKP_NONCE_LEN];
    bool                 needs_key_rotate;
    ykp_security_ctx_t  *sec_ctx;
} ykp_session_t;

/* ── Session API ─────────────────────────────── */

/**
 * @brief Initialise the session manager.
 */
void ykp_session_init(ykp_session_t *session, ykp_security_ctx_t *sec_ctx);

/**
 * @brief Get the next packet ID (monotonically increasing).
 *        Checks if key rotation is needed.
 */
uint32_t ykp_session_next_packet_id(ykp_session_t *session);

/**
 * @brief Mark session as active after ECDH handshake.
 */
void ykp_session_set_active(ykp_session_t *session, uint32_t session_id);

/**
 * @brief Store server nonce received in CHALLENGE.
 */
void ykp_session_store_nonce(ykp_session_t *session,
                              const uint8_t *nonce, uint16_t len);

/**
 * @brief Returns true if session is in ACTIVE state.
 */
bool ykp_session_is_active(const ykp_session_t *session);

/**
 * @brief Reset session (after disconnect).
 */
void ykp_session_reset(ykp_session_t *session);

/**
 * @brief Check if key rotation is due.
 */
bool ykp_session_rotation_due(const ykp_session_t *session);

#endif /* YKP_SESSION_H */
