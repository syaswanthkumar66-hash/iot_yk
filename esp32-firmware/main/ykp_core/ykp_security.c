#include "ykp_security.h"
#include "nvs_config.h"
#include <string.h>
#include <stdlib.h>

#include "esp_log.h"
#include "esp_random.h"

/* mbedTLS headers */
#include "mbedtls/ecdh.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/gcm.h"
#include "mbedtls/sha256.h"
#include "mbedtls/hkdf.h"
#include "mbedtls/md.h"
#include "mbedtls/entropy.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/ecp.h"
#include "mbedtls/pk.h"

static const char *TAG = "ykp_security";

/* ── mbedTLS RNG context (global) ─────────── */
static mbedtls_entropy_context   s_entropy;
static mbedtls_ctr_drbg_context  s_drbg;
static bool s_rng_ready = false;

static bool init_rng(void)
{
    if (s_rng_ready) return true;
    mbedtls_entropy_init(&s_entropy);
    mbedtls_ctr_drbg_init(&s_drbg);

    const char *pers = "ykp_v5";
    int ret = mbedtls_ctr_drbg_seed(&s_drbg,
                                     mbedtls_entropy_func,
                                     &s_entropy,
                                     (const uint8_t *)pers,
                                     strlen(pers));
    if (ret != 0) {
        ESP_LOGE(TAG, "RNG seed failed: -0x%04X", -ret);
        return false;
    }
    s_rng_ready = true;
    return true;
}

/* ════════════════════════════════════════════
   Init — load device keys from NVS
   ════════════════════════════════════════════ */
bool ykp_security_init(ykp_security_ctx_t *ctx)
{
    if (!ctx) return false;
    memset(ctx, 0, sizeof(*ctx));

    if (!init_rng()) return false;

    /* Load device long-term ECDH private key from NVS */
    size_t len = YKP_EC_PRIVKEY_LEN;
    if (!nvs_config_get_blob("private_key", ctx->device_private_key, &len)) {
        ESP_LOGE(TAG, "no private_key in NVS — device not provisioned");
        return false;
    }

    /* Load device public key */
    len = YKP_EC_PUBKEY_LEN;
    nvs_config_get_blob("public_key", ctx->device_public_key, &len);

    /* Load server public key (hardcoded fallback or NVS) */
    len = YKP_EC_PUBKEY_LEN;
    if (!nvs_config_get_blob("server_pub_key", ctx->server_public_key, &len)) {
        ESP_LOGW(TAG, "server_pub_key not in NVS — using hardcoded default");
        /* In production: embed server public key here */
    }

    /* OTA verify key = server signing key */
    len = YKP_EC_PUBKEY_LEN;
    nvs_config_get_blob("ota_verify_key", ctx->ota_verify_key, &len);

    ESP_LOGI(TAG, "security init OK");
    return true;
}

/* ════════════════════════════════════════════
   Generate ephemeral ECDH key pair
   ════════════════════════════════════════════ */
bool ykp_security_gen_ephemeral(ykp_security_ctx_t *ctx)
{
    if (!ctx || !s_rng_ready) return false;

    mbedtls_ecdh_context ecdh;
    mbedtls_ecdh_init(&ecdh);

    int ret = mbedtls_ecdh_setup(&ecdh, MBEDTLS_ECP_DP_SECP256R1);
    if (ret != 0) goto fail;

    ret = mbedtls_ecdh_gen_public(&ecdh.ctx.mbed_ecdh.grp,
                                   &ecdh.ctx.mbed_ecdh.d,
                                   &ecdh.ctx.mbed_ecdh.Q,
                                   mbedtls_ctr_drbg_random, &s_drbg);
    if (ret != 0) goto fail;

    /* Export private key (big-endian 32 bytes) */
    ret = mbedtls_mpi_write_binary(&ecdh.ctx.mbed_ecdh.d,
                                    ctx->ephemeral_private,
                                    YKP_EC_PRIVKEY_LEN);
    if (ret != 0) goto fail;

    /* Export public key (uncompressed 65 bytes: 04 || X || Y) */
    size_t out_len;
    ret = mbedtls_ecp_point_write_binary(&ecdh.ctx.mbed_ecdh.grp,
                                          &ecdh.ctx.mbed_ecdh.Q,
                                          MBEDTLS_ECP_PF_UNCOMPRESSED,
                                          &out_len,
                                          ctx->ephemeral_public,
                                          YKP_EC_PUBKEY_LEN);
    if (ret != 0 || out_len != YKP_EC_PUBKEY_LEN) goto fail;

    mbedtls_ecdh_free(&ecdh);
    ESP_LOGI(TAG, "ephemeral key pair generated");
    return true;

fail:
    ESP_LOGE(TAG, "ephemeral keygen failed: -0x%04X", -ret);
    mbedtls_ecdh_free(&ecdh);
    return false;
}

/* ════════════════════════════════════════════
   Derive session key via ECDH + HKDF
   ════════════════════════════════════════════ */
bool ykp_security_derive_session_key(ykp_security_ctx_t *ctx,
                                      const uint8_t      *server_ephemeral_pub,
                                      const uint8_t      *nonce,
                                      uint16_t            nonce_len,
                                      uint32_t            session_id)
{
    if (!ctx || !server_ephemeral_pub || !nonce) return false;

    mbedtls_ecdh_context  ecdh;
    mbedtls_ecp_group     grp;
    mbedtls_ecp_point     peer_Q;
    mbedtls_mpi           d, z;
    uint8_t               shared_secret[32];
    int ret;

    mbedtls_ecdh_init(&ecdh);
    mbedtls_ecp_group_init(&grp);
    mbedtls_ecp_point_init(&peer_Q);
    mbedtls_mpi_init(&d);
    mbedtls_mpi_init(&z);

    ret = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP256R1);
    if (ret != 0) goto fail;

    /* Load our ephemeral private key */
    ret = mbedtls_mpi_read_binary(&d, ctx->ephemeral_private, YKP_EC_PRIVKEY_LEN);
    if (ret != 0) goto fail;

    /* Load server ephemeral public key */
    ret = mbedtls_ecp_point_read_binary(&grp, &peer_Q,
                                         server_ephemeral_pub,
                                         YKP_EC_PUBKEY_LEN);
    if (ret != 0) goto fail;

    /* ECDH scalar multiplication: z = d * peer_Q */
    ret = mbedtls_ecdh_compute_shared(&grp, &z, &peer_Q, &d,
                                       mbedtls_ctr_drbg_random, &s_drbg);
    if (ret != 0) goto fail;

    /* Export X-coordinate of shared point as shared secret */
    ret = mbedtls_mpi_write_binary(&z, shared_secret, 32);
    if (ret != 0) goto fail;

    /* HKDF-SHA256: key = HKDF(shared_secret, salt=nonce, info="ykp-session-v5") */
    const uint8_t *info     = (const uint8_t *)"ykp-session-v5";
    uint16_t       info_len = strlen((char *)info);

    ret = mbedtls_hkdf(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),
                        nonce, nonce_len,           /* salt */
                        shared_secret, 32,           /* input key material */
                        info, info_len,              /* info */
                        ctx->session.session_key,
                        YKP_AES_KEY_LEN);
    if (ret != 0) goto fail;

    ctx->session.session_id = session_id;
    ctx->session.active     = true;

    memset(shared_secret, 0, sizeof(shared_secret));
    ESP_LOGI(TAG, "session key derived OK, session_id=0x%08lX", (unsigned long)session_id);

    mbedtls_mpi_free(&d);
    mbedtls_mpi_free(&z);
    mbedtls_ecp_point_free(&peer_Q);
    mbedtls_ecp_group_free(&grp);
    mbedtls_ecdh_free(&ecdh);
    return true;

fail:
    ESP_LOGE(TAG, "key derivation failed: -0x%04X", -ret);
    mbedtls_mpi_free(&d);
    mbedtls_mpi_free(&z);
    mbedtls_ecp_point_free(&peer_Q);
    mbedtls_ecp_group_free(&grp);
    mbedtls_ecdh_free(&ecdh);
    return false;
}

/* ════════════════════════════════════════════
   AES-256-GCM Encrypt
   IV = session_id(4B, BE) || packet_id(4B, BE) || random(4B)
   ════════════════════════════════════════════ */
bool ykp_security_encrypt(const ykp_session_keys_t *keys,
                           uint32_t                  packet_id,
                           const uint8_t            *aad,
                           uint16_t                  aad_len,
                           uint8_t                  *plaintext,
                           uint16_t                  plaintext_len,
                           uint8_t                  *auth_tag_out)
{
    if (!keys || !keys->active || !plaintext || !auth_tag_out) return false;

    /* Build 12-byte IV */
    uint8_t iv[YKP_GCM_IV_LEN];
    iv[0]  = (keys->session_id >> 24) & 0xFF;
    iv[1]  = (keys->session_id >> 16) & 0xFF;
    iv[2]  = (keys->session_id >>  8) & 0xFF;
    iv[3]  =  keys->session_id        & 0xFF;
    iv[4]  = (packet_id >> 24) & 0xFF;
    iv[5]  = (packet_id >> 16) & 0xFF;
    iv[6]  = (packet_id >>  8) & 0xFF;
    iv[7]  =  packet_id        & 0xFF;
    esp_fill_random(&iv[8], 4);

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);

    int ret = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES,
                                   keys->session_key, YKP_AES_KEY_LEN * 8);
    if (ret != 0) {
        ESP_LOGE(TAG, "gcm_setkey failed: -0x%04X", -ret);
        mbedtls_gcm_free(&gcm);
        return false;
    }

    ret = mbedtls_gcm_crypt_and_tag(&gcm,
                                     MBEDTLS_GCM_ENCRYPT,
                                     plaintext_len,
                                     iv,  YKP_GCM_IV_LEN,
                                     aad, aad_len,
                                     plaintext,    /* in */
                                     plaintext,    /* out (in-place) */
                                     YKP_AUTH_TAG_SIZE,
                                     auth_tag_out);
    mbedtls_gcm_free(&gcm);

    if (ret != 0) {
        ESP_LOGE(TAG, "gcm_encrypt failed: -0x%04X", -ret);
        return false;
    }
    return true;
}

/* ════════════════════════════════════════════
   AES-256-GCM Decrypt
   ════════════════════════════════════════════ */
bool ykp_security_decrypt(const ykp_session_keys_t *keys,
                           uint32_t                  packet_id,
                           const uint8_t            *aad,
                           uint16_t                  aad_len,
                           uint8_t                  *ciphertext,
                           uint16_t                  ciphertext_len,
                           const uint8_t            *auth_tag)
{
    if (!keys || !keys->active || !ciphertext || !auth_tag) return false;

    /* Reconstruct IV (same derivation as encrypt) */
    uint8_t iv[YKP_GCM_IV_LEN];
    iv[0] = (keys->session_id >> 24) & 0xFF;
    iv[1] = (keys->session_id >> 16) & 0xFF;
    iv[2] = (keys->session_id >>  8) & 0xFF;
    iv[3] =  keys->session_id        & 0xFF;
    iv[4] = (packet_id >> 24) & 0xFF;
    iv[5] = (packet_id >> 16) & 0xFF;
    iv[6] = (packet_id >>  8) & 0xFF;
    iv[7] =  packet_id        & 0xFF;
    /* iv[8..11] was embedded in the packet — extract if needed */

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);

    int ret = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES,
                                   keys->session_key, YKP_AES_KEY_LEN * 8);
    if (ret != 0) { mbedtls_gcm_free(&gcm); return false; }

    ret = mbedtls_gcm_auth_decrypt(&gcm,
                                    ciphertext_len,
                                    iv,  YKP_GCM_IV_LEN,
                                    aad, aad_len,
                                    auth_tag, YKP_AUTH_TAG_SIZE,
                                    ciphertext,
                                    ciphertext);
    mbedtls_gcm_free(&gcm);

    if (ret != 0) {
        ESP_LOGW(TAG, "decrypt/auth failed — possible tamper or replay");
        return false;
    }
    return true;
}

/* ════════════════════════════════════════════
   ECDSA Sign (device private key)
   ════════════════════════════════════════════ */
bool ykp_security_sign(ykp_security_ctx_t *ctx,
                        const uint8_t      *data,
                        uint16_t            data_len,
                        uint8_t            *sig_out)
{
    uint8_t hash[YKP_SHA256_LEN];
    if (!ykp_security_sha256(data, data_len, hash)) return false;

    mbedtls_ecdsa_context ecdsa;
    mbedtls_ecp_group     grp;
    mbedtls_mpi           r, s, d;
    mbedtls_ecdsa_init(&ecdsa);
    mbedtls_ecp_group_init(&grp);
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    mbedtls_mpi_init(&d);

    bool ok = false;
    int  ret;

    ret = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP256R1);
    if (ret != 0) goto done;

    ret = mbedtls_mpi_read_binary(&d, ctx->device_private_key, YKP_EC_PRIVKEY_LEN);
    if (ret != 0) goto done;

    ret = mbedtls_ecdsa_sign(&grp, &r, &s, &d, hash, YKP_SHA256_LEN,
                              mbedtls_ctr_drbg_random, &s_drbg);
    if (ret != 0) goto done;

    mbedtls_mpi_write_binary(&r, sig_out,                  32);
    mbedtls_mpi_write_binary(&s, sig_out + 32,             32);
    ok = true;

done:
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    mbedtls_mpi_free(&d);
    mbedtls_ecp_group_free(&grp);
    mbedtls_ecdsa_free(&ecdsa);
    return ok;
}

/* ════════════════════════════════════════════
   ECDSA Verify (server OTA key)
   ════════════════════════════════════════════ */
bool ykp_security_verify(ykp_security_ctx_t *ctx,
                          const uint8_t      *data,
                          uint16_t            data_len,
                          const uint8_t      *sig,
                          uint16_t            sig_len)
{
    if (sig_len < 64) return false;

    uint8_t hash[YKP_SHA256_LEN];
    if (!ykp_security_sha256(data, data_len, hash)) return false;

    mbedtls_ecdsa_context ecdsa;
    mbedtls_ecp_group     grp;
    mbedtls_ecp_point     Q;
    mbedtls_mpi           r, s;

    mbedtls_ecdsa_init(&ecdsa);
    mbedtls_ecp_group_init(&grp);
    mbedtls_ecp_point_init(&Q);
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);

    bool ok = false;
    int  ret;

    ret = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP256R1);
    if (ret != 0) goto done;

    ret = mbedtls_ecp_point_read_binary(&grp, &Q, ctx->ota_verify_key, YKP_EC_PUBKEY_LEN);
    if (ret != 0) goto done;

    ret = mbedtls_mpi_read_binary(&r, sig,      32);
    if (ret != 0) goto done;
    ret = mbedtls_mpi_read_binary(&s, sig + 32, 32);
    if (ret != 0) goto done;

    ret = mbedtls_ecdsa_verify(&grp, hash, YKP_SHA256_LEN, &Q, &r, &s);
    ok  = (ret == 0);

done:
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    mbedtls_ecp_point_free(&Q);
    mbedtls_ecp_group_free(&grp);
    mbedtls_ecdsa_free(&ecdsa);
    if (!ok) ESP_LOGE(TAG, "ECDSA verify failed: -0x%04X", -ret);
    return ok;
}

/* ════════════════════════════════════════════
   RNG & SHA-256
   ════════════════════════════════════════════ */
bool ykp_security_random(uint8_t *out, uint16_t len)
{
    esp_fill_random(out, len);
    return true;
}

bool ykp_security_sha256(const uint8_t *data, uint32_t len, uint8_t *hash_out)
{
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    int ret = mbedtls_sha256_starts(&ctx, 0);    /* 0 = SHA-256 */
    if (ret == 0) ret = mbedtls_sha256_update(&ctx, data, len);
    if (ret == 0) ret = mbedtls_sha256_finish(&ctx, hash_out);
    mbedtls_sha256_free(&ctx);
    return ret == 0;
}
