#include "ota_service.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "ykp_security.h"
#include "nvs_config.h"
#include "device_config.h"
#include <string.h>
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "ota_svc";

static ota_send_fn_t          s_send = NULL;
static ota_state_t            s_state = {0};
static esp_ota_handle_t       s_ota_handle = 0;
static const esp_partition_t *s_ota_partition = NULL;
static char                   s_device_id[9] = {0};

/* SHA-256 context to accumulate hash while writing */
#include "psa/crypto.h"
static psa_hash_operation_t s_sha_ctx;

static void send_ota_pkt(uint8_t action)
{
    ykp_packet_t *pkt = ykp_packet_alloc();
    if (!pkt) return;
    ykp_packet_set_header(pkt, 0, 0, s_device_id, "SERVER",
                          ROUTE_CLOUD, SVC_OTA, action, YKP_QOS_1, false);
    uint8_t raw[64];
    uint16_t len = ykp_packet_serialise(pkt, raw, sizeof(raw));
    if (len && s_send) s_send(raw, len);
    ykp_packet_free(pkt);
}

void ota_service_init(ota_send_fn_t send_fn)
{
    s_send = send_fn;
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));
    ESP_LOGI(TAG, "OTA service init OK");
}

bool ota_service_is_active(void) { return s_state.active; }

void ota_service_handle(const uint8_t *payload, uint16_t len, uint8_t action_id)
{
    ykp_tlv_t tlv;

    switch ((ykp_ota_action_t)action_id) {

        case OTA_BEGIN: {
            ESP_LOGI(TAG, "OTA BEGIN received");
            memset(&s_state, 0, sizeof(s_state));

            /* Parse firmware metadata */
            if (ykp_tlv_find(payload, len, TLV_FIRMWARE_VER, &tlv))
                memcpy(s_state.version, tlv.value, tlv.length < 15 ? tlv.length : 15);
            if (ykp_tlv_find(payload, len, TLV_VALUE_INT, &tlv))
                ykp_tlv_read_uint32(&tlv, &s_state.firmware_size);
            /* M4 fix: reject if hash field is not exactly 32 bytes */
            if (ykp_tlv_find(payload, len, TLV_HASH, &tlv)) {
                if (tlv.length != 32) {
                    ESP_LOGE(TAG, "OTA FAIL: TLV_HASH length=%u, expected 32", tlv.length);
                    send_ota_pkt(OTA_FAIL);
                    return;
                }
                memcpy(s_state.sha256, tlv.value, 32);
            }
            if (ykp_tlv_find(payload, len, TLV_SIGNATURE, &tlv))
                memcpy(s_state.ecdsa_sig, tlv.value, tlv.length < 64 ? tlv.length : 64);

            s_state.chunk_size   = 4096;
            s_state.chunks_total = (s_state.firmware_size + 4095) / 4096;

            /* Open OTA partition */
            s_ota_partition = esp_ota_get_next_update_partition(NULL);
            if (!s_ota_partition) {
                ESP_LOGE(TAG, "no OTA partition found");
                send_ota_pkt(OTA_FAIL);
                return;
            }

            esp_err_t err = esp_ota_begin(s_ota_partition,
                                           OTA_WITH_SEQUENTIAL_WRITES,
                                           &s_ota_handle);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "ota_begin failed: %s", esp_err_to_name(err));
                send_ota_pkt(OTA_FAIL);
                return;
            }

            psa_crypto_init();
            s_sha_ctx = psa_hash_operation_init();
            psa_hash_setup(&s_sha_ctx, PSA_ALG_SHA_256);

            s_state.active = true;
            s_state.next_expected_chunk = 0;
            ESP_LOGI(TAG, "OTA started: v%s, %lu bytes, %u chunks",
                     s_state.version,
                     (unsigned long)s_state.firmware_size,
                     s_state.chunks_total);
            break;
        }

        case OTA_CHUNK: {
            if (!s_state.active) return;

            uint16_t chunk_idx = 0;
            if (ykp_tlv_find(payload, len, TLV_CHUNK_INDEX, &tlv))
                ykp_tlv_read_uint16(&tlv, &chunk_idx);

            if (chunk_idx != s_state.next_expected_chunk) {
                ESP_LOGW(TAG, "chunk out of order: got %u, expected %u",
                         chunk_idx, s_state.next_expected_chunk);
                /* Request missing chunk */
                uint8_t tlv_buf[8];
                ykp_tlv_builder_t b;
                ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));
                ykp_tlv_add_uint16(&b, TLV_CHUNK_INDEX, s_state.next_expected_chunk);
                /* C4 fix: check alloc before dereferencing pointer */
                ykp_packet_t *pkt = ykp_packet_alloc();
                if (!pkt) { ESP_LOGE(TAG, "OTA MISSING: packet alloc failed"); return; }
                ykp_packet_set_header(pkt, 0, 0, s_device_id, "SERVER",
                                      ROUTE_CLOUD, SVC_OTA, OTA_MISSING,
                                      YKP_QOS_1, false);
                ykp_packet_set_payload(pkt, tlv_buf, ykp_tlv_builder_len(&b));
                uint8_t raw[80];
                uint16_t rlen = ykp_packet_serialise(pkt, raw, sizeof(raw));
                if (rlen && s_send) s_send(raw, rlen);
                ykp_packet_free(pkt);
                return;
            }

            if (ykp_tlv_find(payload, len, TLV_CHUNK_DATA, &tlv)) {
                esp_err_t err = esp_ota_write(s_ota_handle, tlv.value, tlv.length);
                if (err != ESP_OK) {
                    ESP_LOGE(TAG, "ota_write failed: %s", esp_err_to_name(err));
                    send_ota_pkt(OTA_FAIL);
                    s_state.active = false;
                    return;
                }
                psa_hash_update(&s_sha_ctx, tlv.value, tlv.length);
                s_state.next_expected_chunk++;
                ESP_LOGD(TAG, "chunk %u/%u written",
                         chunk_idx + 1, s_state.chunks_total);
            }
            break;
        }

        case OTA_COMPLETE: {
            if (!s_state.active) return;
            ESP_LOGI(TAG, "OTA COMPLETE — verifying...");

            /* Verify SHA256 */
            uint8_t computed_hash[32];
            size_t hash_len = 0;
            psa_hash_finish(&s_sha_ctx, computed_hash, sizeof(computed_hash), &hash_len);
            psa_hash_abort(&s_sha_ctx);

            if (memcmp(computed_hash, s_state.sha256, 32) != 0) {
                ESP_LOGE(TAG, "SHA256 mismatch — OTA FAIL");
                esp_ota_abort(s_ota_handle);
                send_ota_pkt(OTA_FAIL);
                s_state.active = false;
                return;
            }
            ESP_LOGI(TAG, "SHA256 OK");

            /* Finalise OTA write */
            esp_err_t err = esp_ota_end(s_ota_handle);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "ota_end failed: %s", esp_err_to_name(err));
                send_ota_pkt(OTA_FAIL);
                s_state.active = false;
                return;
            }

            send_ota_pkt(OTA_VERIFY);
            break;
        }

        case OTA_INSTALL: {
            ESP_LOGI(TAG, "OTA INSTALL — setting boot partition");
            esp_err_t err = esp_ota_set_boot_partition(s_ota_partition);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "set_boot_partition failed: %s", esp_err_to_name(err));
                send_ota_pkt(OTA_FAIL);
                return;
            }
            send_ota_pkt(OTA_REBOOT);
            vTaskDelay(pdMS_TO_TICKS(500));
            esp_restart();
            break;
        }

        default:
            ESP_LOGW(TAG, "unknown OTA action: 0x%02X", action_id);
            break;
    }
}
