/* ════════════════════════════════════════════════════════
   YKP v5 Firmware — Main Entry Point
   Device: ESP32 | Framework: ESP-IDF
   ════════════════════════════════════════════════════════ */

#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "esp_netif.h"
#include "esp_event.h"

/* YKP modules */
#include "nvs_config.h"
#include "device_config.h"
#include "ykp_constants.h"
#include "ykp_packet.h"
#include "ykp_security.h"
#include "ykp_session.h"
#include "ykp_replay.h"
#include "ykp_qos.h"
#include "wifi_manager.h"
#include "ykp_websocket.h"
#include "ykp_udp.h"
#include "relay_service.h"
#include "sensor_service.h"
#include "health_service.h"
#include "ota_service.h"
#include "ykp_discovery.h"
#include "provision_server.h"

static const char *TAG = "ykp_main";

/* ── Global state ──────────────────────────── */
static ykp_security_ctx_t  g_sec_ctx;
static ykp_session_t       g_session;
static ykp_replay_ctx_t    g_replay;
static ykp_qos_engine_t    g_qos;
static char                g_device_id[9]   = {0};
static char                g_device_type[16] = {0};
static char                g_server_url[128] = {0};

/* ── TX send function (used by services) ─────── */
static void ykp_send_packet(const uint8_t *data, uint16_t len)
{
    if (ykp_ws_is_connected()) {
        ykp_ws_send(data, len);
    } else {
        ESP_LOGW(TAG, "cannot send: WS not connected");
    }
}

/* ── Auth handshake ─────────────────────────── */
static void send_hello(void)
{
    /* Generate new ephemeral key pair */
    if (!ykp_security_gen_ephemeral(&g_sec_ctx)) {
        ESP_LOGE(TAG, "ephemeral key gen failed");
        return;
    }

    uint8_t tlv_buf[128];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));
    ykp_tlv_add_string(&b, TLV_DEVICE_ID,   g_device_id);
    ykp_tlv_add_bytes(&b,  TLV_PUBLIC_KEY,
                      g_sec_ctx.ephemeral_public, YKP_EC_PUBKEY_LEN);
    ykp_tlv_add_string(&b, TLV_FIRMWARE_VER, YKP_FIRMWARE_VERSION);

    ykp_packet_t *pkt = ykp_packet_alloc();
    if (!pkt) return;
    ykp_packet_set_header(pkt, ykp_session_next_packet_id(&g_session), 0,
                          g_device_id, "SERVER",
                          ROUTE_CLOUD, SVC_SECURITY, SEC_HELLO,
                          YKP_QOS_1, false);
    ykp_packet_set_payload(pkt, tlv_buf, ykp_tlv_builder_len(&b));

    uint8_t raw[256];
    uint16_t raw_len = ykp_packet_serialise(pkt, raw, sizeof(raw));
    ykp_packet_free(pkt);
    if (raw_len > 0) ykp_ws_send(raw, raw_len);
    g_session.state = SESSION_STATE_HELLO_SENT;
    ESP_LOGI(TAG, "HELLO sent");
}

static void send_ecdh_response(void)
{
    uint8_t signature[YKP_ECDSA_SIG_LEN];
    if (!ykp_security_sign(&g_sec_ctx,
                           g_session.server_nonce,
                           YKP_NONCE_LEN, signature)) {
        ESP_LOGE(TAG, "sign failed");
        return;
    }

    uint8_t tlv_buf[128];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));
    ykp_tlv_add_string(&b, TLV_DEVICE_ID, g_device_id);
    ykp_tlv_add_bytes(&b,  TLV_SIGNATURE, signature, YKP_ECDSA_SIG_LEN);

    ykp_packet_t *pkt = ykp_packet_alloc();
    if (!pkt) return;
    ykp_packet_set_header(pkt, ykp_session_next_packet_id(&g_session), 0,
                          g_device_id, "SERVER",
                          ROUTE_CLOUD, SVC_SECURITY, SEC_ECDH_RESPONSE,
                          YKP_QOS_1, false);
    ykp_packet_set_payload(pkt, tlv_buf, ykp_tlv_builder_len(&b));

    uint8_t raw[256];
    uint16_t raw_len = ykp_packet_serialise(pkt, raw, sizeof(raw));
    ykp_packet_free(pkt);
    if (raw_len > 0) ykp_ws_send(raw, raw_len);
    g_session.state = SESSION_STATE_ECDH_SENT;
    ESP_LOGI(TAG, "ECDH_RESPONSE sent");
}

/* ── Incoming packet dispatcher ─────────────── */
static void handle_incoming_packet(const uint8_t *raw, uint16_t raw_len)
{
    ykp_packet_t pkt = {0};
    if (!ykp_packet_parse(raw, raw_len, &pkt)) return;

    uint32_t pkt_id = pkt.header.packet_id;

    /* Security service — unencrypted during handshake */
    if (pkt.header.service_id == SVC_SECURITY) {
        switch ((ykp_sec_action_t)pkt.header.action_id) {

            case SEC_CHALLENGE: {
                ESP_LOGI(TAG, "CHALLENGE received");
                /* Extract nonce and server ephemeral public key */
                ykp_tlv_t tlv;
                if (pkt.payload && ykp_tlv_find(pkt.payload, pkt.payload_len,
                                                 TLV_NONCE, &tlv)) {
                    ykp_session_store_nonce(&g_session, tlv.value, tlv.length);
                }
                uint8_t srv_pub[YKP_EC_PUBKEY_LEN];
                if (pkt.payload && ykp_tlv_find(pkt.payload, pkt.payload_len,
                                                 TLV_PUBLIC_KEY, &tlv)) {
                    memcpy(srv_pub, tlv.value, YKP_EC_PUBKEY_LEN);
                    ykp_security_derive_session_key(&g_sec_ctx, srv_pub,
                                                    g_session.server_nonce,
                                                    YKP_NONCE_LEN, pkt_id);
                }
                send_ecdh_response();
                break;
            }

            case SEC_SESSION_ACTIVE: {
                ykp_tlv_t tlv;
                uint32_t session_id = pkt_id;
                if (pkt.payload && ykp_tlv_find(pkt.payload, pkt.payload_len,
                                                 TLV_VALUE_INT, &tlv)) {
                    ykp_tlv_read_uint32(&tlv, &session_id);
                }
                ykp_session_set_active(&g_session, session_id);
                g_sec_ctx.session.session_id = session_id;
                ykp_replay_reset(&g_replay);
                ESP_LOGI(TAG, "SESSION ACTIVE — starting services");
                health_service_start_task();
#ifdef CONFIG_YKP_DEVICE_TYPE_SENSOR
                sensor_service_start_task();
#endif
                break;
            }

            case SEC_KEY_ROTATE:
                ESP_LOGI(TAG, "KEY ROTATE requested");
                ykp_session_reset(&g_session);
                send_hello();
                break;

            default:
                break;
        }
        goto done;
    }

    /* All other services require active session */
    if (!ykp_session_is_active(&g_session)) {
        ESP_LOGW(TAG, "packet received without active session");
        goto done;
    }

    /* Replay check */
    if (!ykp_replay_check(&g_replay, pkt_id)) {
        ESP_LOGW(TAG, "replay attack blocked pkt_id=%lu", (unsigned long)pkt_id);
        goto done;
    }

    /* Decrypt payload if encrypted */
    if ((pkt.header.flags & YKP_FLAG_ENCRYPTED) && pkt.payload) {
        bool ok = ykp_security_decrypt(&g_sec_ctx.session, pkt_id,
                                        raw, YKP_HEADER_SIZE,
                                        pkt.payload, pkt.payload_len,
                                        pkt.auth_tag);
        if (!ok) {
            ESP_LOGE(TAG, "decrypt failed — dropping packet");
            goto done;
        }
    }

    /* Dispatch to service */
    switch ((ykp_service_t)pkt.header.service_id) {
        case SVC_RELAY:
            relay_service_handle(pkt.payload, pkt.payload_len,
                                  pkt.header.action_id, pkt_id,
                                  pkt.header.session_id,
                                  (const char *)pkt.header.source_id);
            break;

        case SVC_SENSOR:
            sensor_service_send_report();
            break;

        case SVC_HEALTH:
            if (pkt.header.action_id == HEALTH_REQUEST)
                health_service_send_report();
            break;

        case SVC_OTA:
            ota_service_handle(pkt.payload, pkt.payload_len,
                                pkt.header.action_id);
            break;

        default:
            ESP_LOGW(TAG, "unhandled service: 0x%02X", pkt.header.service_id);
            break;
    }

done:
    if (pkt.payload) free(pkt.payload);
}

/* ── UDP RX callback ─────────────────────────── */
static void udp_rx_callback(const uint8_t *data, uint16_t len,
                             struct sockaddr_in *from)
{
    /* Check if it's a discovery request */
    if (len >= 3 && data[4] == SVC_DISCOVERY) {
        ykp_discovery_handle(data, len, from);
    } else {
        handle_incoming_packet(data, len);
    }
}

/* ── WS connected callback ───────────────────── */
static void on_ws_connected(void)
{
    ESP_LOGI(TAG, "WS connected — sending HELLO");
    vTaskDelay(pdMS_TO_TICKS(500));
    send_hello();
}

static void on_ws_disconnected(void)
{
    ESP_LOGW(TAG, "WS disconnected — resetting session");
    ykp_session_reset(&g_session);
}

static void on_wifi_connected(const char *ip)
{
    ESP_LOGI(TAG, "WiFi connected, IP=%s — connecting WebSocket", ip);
    ykp_ws_connect();
}

static void on_wifi_disconnected(void)
{
    ESP_LOGW(TAG, "WiFi disconnected");
}

/* ── Main QoS tick task ──────────────────────── */
static void qos_tick_task(void *arg)
{
    while (1) {
        ykp_qos_tick(&g_qos);
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}

/* ════════════════════════════════════════════════
   app_main
   ════════════════════════════════════════════════ */
void app_main(void)
{
    ESP_LOGI(TAG, "═══════════════════════════════════════");
    ESP_LOGI(TAG, "  YKP v5 Firmware %s", YKP_FIRMWARE_VERSION);
    ESP_LOGI(TAG, "═══════════════════════════════════════");

    /* 1. NVS init */
    if (!nvs_config_init()) {
        ESP_LOGE(TAG, "NVS init failed — halting");
        esp_restart();
    }

    /* 2. Increment restart counter */
    uint32_t restarts = nvs_config_incr_restart_count();
    ESP_LOGI(TAG, "Boot count: %lu", (unsigned long)restarts);

    /* 3. Load device identity and check provisioning */
    char temp_ssid[64] = {0};
    char temp_password[64] = {0};
    bool has_id = nvs_config_get_device_id(g_device_id, sizeof(g_device_id));
    bool has_ssid = nvs_config_get_wifi_ssid(temp_ssid, sizeof(temp_ssid));
    bool has_pass = nvs_config_get_wifi_password(temp_password, sizeof(temp_password));

    if (!has_id || !has_ssid || !has_pass) {
        ESP_LOGW(TAG, "Device not fully provisioned (has_id=%d, has_ssid=%d, has_pass=%d)", has_id, has_ssid, has_pass);
        ESP_LOGW(TAG, "Starting SoftAP + HTTP provisioning server...");
        esp_netif_init();
        esp_event_loop_create_default();
        if (provision_server_start()) {
            ESP_LOGI(TAG, "Waiting for provisioning via mobile app or web dashboard...");
            while (1) {
                vTaskDelay(pdMS_TO_TICKS(1000));
            }
        } else {
            ESP_LOGE(TAG, "Failed to start provisioning server. Restarting...");
            vTaskDelay(pdMS_TO_TICKS(2000));
            esp_restart();
        }
    }

    nvs_config_get_device_type(g_device_type, sizeof(g_device_type));
    nvs_config_get_server_url(g_server_url, sizeof(g_server_url));
    ESP_LOGI(TAG, "device_id=%s type=%s", g_device_id, g_device_type);
    ESP_LOGI(TAG, "server=%s", g_server_url);

    /* 4. Security init */
    if (!ykp_security_init(&g_sec_ctx)) {
        ESP_LOGE(TAG, "security init failed");
        esp_restart();
    }

    /* 5. Session + replay init */
    ykp_session_init(&g_session, &g_sec_ctx);
    ykp_replay_init(&g_replay);
    ykp_qos_init(&g_qos, ykp_send_packet);

    /* 6. WiFi */
    char ssid[64] = {0}, password[64] = {0};
    nvs_config_get_wifi_ssid(ssid, sizeof(ssid));
    nvs_config_get_wifi_password(password, sizeof(password));

    wifi_manager_init();
    wifi_manager_register_callbacks(on_wifi_connected, on_wifi_disconnected);

    /* 7. WebSocket */
    ykp_ws_init(g_server_url);
    ykp_ws_register_rx_cb(handle_incoming_packet);
    ykp_ws_register_connected_cb(on_ws_connected);
    ykp_ws_register_disconnected_cb(on_ws_disconnected);

    /* 8. UDP */
    ykp_udp_init(YKP_CONTROL_PORT);
    ykp_udp_register_rx_cb(udp_rx_callback);
    ykp_udp_start_rx_task();

    /* 9. Services */
    relay_service_init(ykp_send_packet);
    sensor_service_init(ykp_send_packet);
    health_service_init(ykp_send_packet);
    ota_service_init(ykp_send_packet);
    ykp_discovery_init();
    ykp_discovery_start_task();

    /* 10. QoS tick task */
    xTaskCreate(qos_tick_task, "qos_tick", TASK_STACK_QOS, NULL, TASK_PRIO_QOS, NULL);

    /* 11. Connect WiFi (blocks until connected or fails) */
    if (!wifi_manager_connect(ssid, password)) {
        ESP_LOGE(TAG, "WiFi failed — will retry on reconnect event");
    }

    /* Main loop — watchdog feed */
    while (1) {
        if (ykp_session_rotation_due(&g_session) && ykp_ws_is_connected()) {
            ESP_LOGI(TAG, "key rotation due — re-authenticating");
            ykp_session_reset(&g_session);
            send_hello();
        }
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}
