#ifndef YKP_SECURITY_H
#define YKP_SECURITY_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/* ═══════════════════════════════════════════════
   YKP Security — ECDH + AES-256-GCM + ECDSA
   Uses mbedTLS (built into ESP-IDF)
   ═══════════════════════════════════════════════ */

#define YKP_AES_KEY_LEN       32    /* AES-256 */
#define YKP_GCM_IV_LEN        12    /* AES-GCM IV */
#define YKP_EC_PUBKEY_LEN     65    /* Uncompressed P-256 */
#define YKP_EC_PRIVKEY_LEN    32
#define YKP_ECDSA_SIG_LEN     64    /* raw r||s */
#define YKP_NONCE_LEN         16
#define YKP_SHA256_LEN        32
#define YKP_HKDF_SALT_LEN     32

/* ── Session key derived from ECDH ─────────── */
typedef struct {
    uint8_t  session_key[YKP_AES_KEY_LEN];   /* AES-256 key */
    uint32_t session_id;
    bool     active;
} ykp_session_keys_t;

/* ── Security context ───────────────────────── */
typedef struct {
    uint8_t  device_private_key[YKP_EC_PRIVKEY_LEN];
    uint8_t  device_public_key[YKP_EC_PUBKEY_LEN];
    uint8_t  server_public_key[YKP_EC_PUBKEY_LEN];
    uint8_t  ota_verify_key[YKP_EC_PUBKEY_LEN]; /* Server's ECDSA key for OTA */

    /* Ephemeral ECDH key pair (per session) */
    uint8_t  ephemeral_private[YKP_EC_PRIVKEY_LEN];
    uint8_t  ephemeral_public[YKP_EC_PUBKEY_LEN];

    /* Derived session key */
    ykp_session_keys_t session;
} ykp_security_ctx_t;

/* ════════════════════════════════════════════
   Security API
   ════════════════════════════════════════════ */

/**
 * @brief Initialise security context, load device keys from NVS.
 */
bool ykp_security_init(ykp_security_ctx_t *ctx);

/**
 * @brief Generate ephemeral ECDH key pair for a new session.
 */
bool ykp_security_gen_ephemeral(ykp_security_ctx_t *ctx);

/**
 * @brief Perform ECDH with server ephemeral public key.
 *        Derives session AES key using HKDF.
 *        nonce = server nonce from CHALLENGE.
 */
bool ykp_security_derive_session_key(ykp_security_ctx_t *ctx,
                                      const uint8_t      *server_ephemeral_pub,
                                      const uint8_t      *nonce,
                                      uint16_t            nonce_len,
                                      uint32_t            session_id);

/**
 * @brief AES-256-GCM encrypt in-place.
 *        IV = session_id(4) || packet_id(4) || random(4)
 *        AAD = YKP packet header (33 bytes)
 *        Returns true on success, sets auth_tag_out (16 bytes).
 */
bool ykp_security_encrypt(const ykp_session_keys_t *keys,
                           uint32_t                  packet_id,
                           const uint8_t            *aad,
                           uint16_t                  aad_len,
                           uint8_t                  *plaintext,
                           uint16_t                  plaintext_len,
                           uint8_t                  *auth_tag_out);

/**
 * @brief AES-256-GCM decrypt in-place.
 *        Returns true on success (auth_tag verified).
 */
bool ykp_security_decrypt(const ykp_session_keys_t *keys,
                           uint32_t                  packet_id,
                           const uint8_t            *aad,
                           uint16_t                  aad_len,
                           uint8_t                  *ciphertext,
                           uint16_t                  ciphertext_len,
                           const uint8_t            *auth_tag);

/**
 * @brief Sign data with device ECDSA private key.
 *        sig_out must be YKP_ECDSA_SIG_LEN bytes.
 */
bool ykp_security_sign(ykp_security_ctx_t *ctx,
                        const uint8_t      *data,
                        uint16_t            data_len,
                        uint8_t            *sig_out);

/**
 * @brief Verify ECDSA signature using server OTA verify key.
 */
bool ykp_security_verify(ykp_security_ctx_t *ctx,
                          const uint8_t      *data,
                          uint16_t            data_len,
                          const uint8_t      *sig,
                          uint16_t            sig_len);

/**
 * @brief Generate random nonce.
 */
bool ykp_security_random(uint8_t *out, uint16_t len);

/**
 * @brief SHA-256 hash.
 */
bool ykp_security_sha256(const uint8_t *data, uint32_t len, uint8_t *hash_out);

#endif /* YKP_SECURITY_H */
