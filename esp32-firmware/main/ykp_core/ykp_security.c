#include "ykp_security.h"
#include "nvs_config.h"
#include <string.h>
#include <stdlib.h>
#include "esp_log.h"
#include "esp_random.h"
#include "ykp_constants.h"
#include "psa/crypto.h"
#include "mbedtls/platform.h"

static const char *TAG = "ykp_security";

/* Static isolated memory pool for mbedtls to prevent heap fragmentation */
#define MBEDTLS_POOL_SIZE 8192
static uint8_t s_mbedtls_pool[MBEDTLS_POOL_SIZE];
static size_t s_pool_offset = 0;

static void *mbedtls_pool_calloc(size_t n, size_t size)
{
    size_t total = n * size;
    total = (total + 3) & ~3; // Align to 4 bytes
    if (s_pool_offset + total <= MBEDTLS_POOL_SIZE) {
        void *ptr = &s_mbedtls_pool[s_pool_offset];
        s_pool_offset += total;
        memset(ptr, 0, total);
        return ptr;
    }
    ESP_LOGE(TAG, "Out of memory in static mbedtls pool! (requested %u)", total);
    return NULL;
}

static void mbedtls_pool_free(void *ptr)
{
    // No-op: we reuse the memory by resetting the pool offset on new handshakes
}

static inline void write_u32_be(uint8_t *p, uint32_t v) {
    p[0] = (v >> 24) & 0xFF;
    p[1] = (v >> 16) & 0xFF;
    p[2] = (v >>  8) & 0xFF;
    p[3] =  v        & 0xFF;
}

bool ykp_security_init(ykp_security_ctx_t *ctx)
{
    if (!ctx) return false;
    memset(ctx, 0, sizeof(*ctx));

    // Register mbedtls platform allocator
    mbedtls_platform_set_calloc_free(mbedtls_pool_calloc, mbedtls_pool_free);

    psa_status_t status = psa_crypto_init();
    if (status != PSA_SUCCESS) {
        ESP_LOGE(TAG, "Failed to initialize PSA crypto: %ld", (long)status);
        return false;
    }

    size_t len = YKP_EC_PRIVKEY_LEN;
    if (!nvs_config_get_blob("private_key", ctx->device_private_key, &len)) {
        ESP_LOGW(TAG, "no private_key in NVS - generating new key pair...");
        
        psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
        psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
        psa_set_key_bits(&attr, 256);
        psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_EXPORT | PSA_KEY_USAGE_SIGN_HASH);
        psa_set_key_algorithm(&attr, PSA_ALG_ECDSA(PSA_ALG_SHA_256));

        psa_key_id_t key_id;
        if (psa_generate_key(&attr, &key_id) != PSA_SUCCESS) {
            ESP_LOGE(TAG, "Failed to generate device key");
            return false;
        }

        size_t out_len;
        if (psa_export_key(key_id, ctx->device_private_key, YKP_EC_PRIVKEY_LEN, &out_len) == PSA_SUCCESS) {
            nvs_config_set_blob("private_key", ctx->device_private_key, out_len);
        }
        if (psa_export_public_key(key_id, ctx->device_public_key, YKP_EC_PUBKEY_LEN, &out_len) == PSA_SUCCESS) {
            nvs_config_set_blob("public_key", ctx->device_public_key, out_len);
        }
        psa_destroy_key(key_id);
    }

    len = YKP_EC_PUBKEY_LEN;
    nvs_config_get_blob("public_key", ctx->device_public_key, &len);

    len = YKP_EC_PUBKEY_LEN;
    nvs_config_get_blob("server_pub_key", ctx->server_public_key, &len);

    len = YKP_EC_PUBKEY_LEN;
    nvs_config_get_blob("ota_verify_key", ctx->ota_verify_key, &len);

    ESP_LOGI(TAG, "security init OK");
    return true;
}

bool ykp_security_gen_ephemeral(ykp_security_ctx_t *ctx)
{
    if (!ctx) return false;

    // Reset static mbedtls memory pool offset to reuse memory buffers safely
    s_pool_offset = 0;

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attr, 256);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_EXPORT);
    psa_set_key_algorithm(&attr, PSA_ALG_ECDH);

    psa_key_id_t key_id;
    psa_status_t status = psa_generate_key(&attr, &key_id);
    if (status != PSA_SUCCESS) {
        ESP_LOGE(TAG, "ephemeral keygen failed: %ld", (long)status);
        return false;
    }

    size_t out_len;
    status = psa_export_key(key_id, ctx->ephemeral_private, YKP_EC_PRIVKEY_LEN, &out_len);
    if (status != PSA_SUCCESS) goto fail;

    status = psa_export_public_key(key_id, ctx->ephemeral_public, YKP_EC_PUBKEY_LEN, &out_len);
    if (status != PSA_SUCCESS) goto fail;

    psa_destroy_key(key_id);
    ESP_LOGI(TAG, "ephemeral key pair generated");
    return true;

fail:
    psa_destroy_key(key_id);
    return false;
}

bool ykp_security_derive_session_key(ykp_security_ctx_t *ctx,
                                      const uint8_t      *server_ephemeral_pub,
                                      const uint8_t      *nonce,
                                      uint16_t            nonce_len,
                                      uint32_t            session_id)
{
    if (!ctx || !server_ephemeral_pub || !nonce) return false;

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attr, 256);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_DERIVE);
    psa_set_key_algorithm(&attr, PSA_ALG_ECDH);

    psa_key_id_t key_id;
    psa_status_t status = psa_import_key(&attr, ctx->ephemeral_private, YKP_EC_PRIVKEY_LEN, &key_id);
    if (status != PSA_SUCCESS) return false;

    uint8_t shared_secret[32];
    size_t out_len;
    status = psa_raw_key_agreement(PSA_ALG_ECDH, key_id, server_ephemeral_pub, YKP_EC_PUBKEY_LEN, shared_secret, sizeof(shared_secret), &out_len);
    psa_destroy_key(key_id);

    if (status != PSA_SUCCESS) {
        ESP_LOGE(TAG, "ECDH failed: %ld", (long)status);
        return false;
    }

    /* HKDF-SHA256: key = HKDF(shared_secret, salt=nonce, info="ykp-session-v5") */
    psa_key_attributes_t hkdf_attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&hkdf_attr, PSA_KEY_TYPE_DERIVE);
    psa_set_key_usage_flags(&hkdf_attr, PSA_KEY_USAGE_DERIVE);
    psa_set_key_algorithm(&hkdf_attr, PSA_ALG_HKDF(PSA_ALG_SHA_256));

    psa_key_id_t base_key;
    status = psa_import_key(&hkdf_attr, shared_secret, sizeof(shared_secret), &base_key);
    memset(shared_secret, 0, sizeof(shared_secret));
    if (status != PSA_SUCCESS) return false;

    psa_key_derivation_operation_t deriv = PSA_KEY_DERIVATION_OPERATION_INIT;
    psa_key_derivation_setup(&deriv, PSA_ALG_HKDF(PSA_ALG_SHA_256));
    psa_key_derivation_input_bytes(&deriv, PSA_KEY_DERIVATION_INPUT_SALT, nonce, nonce_len);
    psa_key_derivation_input_key(&deriv, PSA_KEY_DERIVATION_INPUT_SECRET, base_key);
    psa_key_derivation_input_bytes(&deriv, PSA_KEY_DERIVATION_INPUT_INFO, (const uint8_t *)"ykp-session-v5", 14);

    status = psa_key_derivation_output_bytes(&deriv, ctx->session.session_key, YKP_AES_KEY_LEN);
    psa_key_derivation_abort(&deriv);
    psa_destroy_key(base_key);

    if (status != PSA_SUCCESS) {
        ESP_LOGE(TAG, "HKDF failed: %ld", (long)status);
        return false;
    }

    ctx->session.session_id = session_id;
    ctx->session.key_ver++; // Increment key rotation version
    ctx->session.active     = true;

    ESP_LOGI(TAG, "session key derived OK, session_id=0x%08lX (ver=%lu)", (unsigned long)session_id, (unsigned long)ctx->session.key_ver);
    return true;
}

bool ykp_security_encrypt(const ykp_session_keys_t *keys,
                           uint32_t                  packet_id,
                           const uint8_t            *aad,
                           uint16_t                  aad_len,
                           uint8_t                  *plaintext,
                           uint16_t                  plaintext_len,
                           uint8_t                  *auth_tag_out)
{
    if (!keys || !keys->active || !plaintext || !auth_tag_out) return false;

    /* Build 12-byte IV: key_ver (4B) || packet_id (4B) || 0x00000000 */
    uint8_t iv[YKP_GCM_IV_LEN];
    write_u32_be(iv, keys->key_ver);
    write_u32_be(iv + 4, packet_id);
    memset(iv + 8, 0, 4);

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_AES);
    psa_set_key_bits(&attr, 256);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_ENCRYPT);
    psa_set_key_algorithm(&attr, PSA_ALG_GCM);

    psa_key_id_t key_id;
    if (psa_import_key(&attr, keys->session_key, YKP_AES_KEY_LEN, &key_id) != PSA_SUCCESS) return false;

    /* In-place zero-copy encrypt using stack buffer instead of dynamic malloc heap allocations */
    uint8_t stack_buf[256];
    if (plaintext_len + YKP_AUTH_TAG_SIZE > sizeof(stack_buf)) {
        psa_destroy_key(key_id);
        return false;
    }

    size_t out_len;
    psa_status_t status = psa_aead_encrypt(key_id, PSA_ALG_GCM, iv, YKP_GCM_IV_LEN,
                                           aad, aad_len,
                                           plaintext, plaintext_len,
                                           stack_buf, sizeof(stack_buf),
                                           &out_len);
    psa_destroy_key(key_id);

    if (status != PSA_SUCCESS) {
        ESP_LOGE(TAG, "gcm_encrypt failed: %ld", (long)status);
        return false;
    }

    /* Split ciphertext and tag */
    memcpy(plaintext, stack_buf, plaintext_len);
    memcpy(auth_tag_out, stack_buf + plaintext_len, YKP_AUTH_TAG_SIZE);
    return true;
}

bool ykp_security_decrypt(const ykp_session_keys_t *keys,
                           uint32_t                  packet_id,
                           const uint8_t            *aad,
                           uint16_t                  aad_len,
                           uint8_t                  *ciphertext,
                           uint16_t                  ciphertext_len,
                           const uint8_t            *auth_tag)
{
    if (!keys || !keys->active || !ciphertext || !auth_tag) return false;

    /* Reconstruct 12-byte IV: key_ver (4B) || packet_id (4B) || 0x00000000 */
    uint8_t iv[YKP_GCM_IV_LEN];
    write_u32_be(iv, keys->key_ver);
    write_u32_be(iv + 4, packet_id);
    memset(iv + 8, 0, 4);

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_AES);
    psa_set_key_bits(&attr, 256);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_DECRYPT);
    psa_set_key_algorithm(&attr, PSA_ALG_GCM);

    psa_key_id_t key_id;
    if (psa_import_key(&attr, keys->session_key, YKP_AES_KEY_LEN, &key_id) != PSA_SUCCESS) return false;

    /* Combine ciphertext and tag on a temporary stack buffer to avoid heap fragmentation */
    uint8_t stack_buf[256];
    if (ciphertext_len + YKP_AUTH_TAG_SIZE > sizeof(stack_buf)) {
        psa_destroy_key(key_id);
        return false;
    }
    memcpy(stack_buf, ciphertext, ciphertext_len);
    memcpy(stack_buf + ciphertext_len, auth_tag, YKP_AUTH_TAG_SIZE);

    size_t out_len;
    psa_status_t status = psa_aead_decrypt(key_id, PSA_ALG_GCM, iv, YKP_GCM_IV_LEN,
                                           aad, aad_len,
                                           stack_buf, ciphertext_len + YKP_AUTH_TAG_SIZE,
                                           ciphertext, ciphertext_len,
                                           &out_len);
    psa_destroy_key(key_id);

    if (status != PSA_SUCCESS) {
        ESP_LOGW(TAG, "decrypt/auth failed — possible tamper or replay");
        return false;
    }
    return true;
}

bool ykp_security_sign(ykp_security_ctx_t *ctx,
                        const uint8_t      *data,
                        uint16_t            data_len,
                        uint8_t            *sig_out)
{
    uint8_t hash[YKP_SHA256_LEN];
    if (!ykp_security_sha256(data, data_len, hash)) return false;

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attr, 256);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_SIGN_HASH);
    psa_set_key_algorithm(&attr, PSA_ALG_ECDSA(PSA_ALG_SHA_256));

    psa_key_id_t key_id;
    if (psa_import_key(&attr, ctx->device_private_key, YKP_EC_PRIVKEY_LEN, &key_id) != PSA_SUCCESS) return false;

    size_t sig_len;
    psa_status_t status = psa_sign_hash(key_id, PSA_ALG_ECDSA(PSA_ALG_SHA_256), hash, YKP_SHA256_LEN, sig_out, 64, &sig_len);
    psa_destroy_key(key_id);

    return status == PSA_SUCCESS;
}

bool ykp_security_verify(ykp_security_ctx_t *ctx,
                          const uint8_t      *data,
                          uint16_t            data_len,
                          const uint8_t      *sig,
                          uint16_t            sig_len)
{
    if (sig_len < 64) return false;

    uint8_t hash[YKP_SHA256_LEN];
    if (!ykp_security_sha256(data, data_len, hash)) return false;

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_PUBLIC_KEY(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attr, 256);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_VERIFY_HASH);
    psa_set_key_algorithm(&attr, PSA_ALG_ECDSA(PSA_ALG_SHA_256));

    psa_key_id_t key_id;
    if (psa_import_key(&attr, ctx->ota_verify_key, YKP_EC_PUBKEY_LEN, &key_id) != PSA_SUCCESS) return false;

    psa_status_t status = psa_verify_hash(key_id, PSA_ALG_ECDSA(PSA_ALG_SHA_256), hash, YKP_SHA256_LEN, sig, sig_len);
    psa_destroy_key(key_id);

    return status == PSA_SUCCESS;
}

bool ykp_security_random(uint8_t *out, uint16_t len)
{
    esp_fill_random(out, len);
    return true;
}

bool ykp_security_sha256(const uint8_t *data, uint32_t len, uint8_t *hash_out)
{
    size_t hash_len;
    psa_status_t status = psa_hash_compute(PSA_ALG_SHA_256, data, len, hash_out, YKP_SHA256_LEN, &hash_len);
    return status == PSA_SUCCESS;
}
