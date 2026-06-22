/* ════════════════════════════════════════════════════════
   YKP v5 Firmware — Main Entry Point
   Device: ESP32 | Framework: ESP-IDF
   ════════════════════════════════════════════════════════ */

#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/timers.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_mac.h"
#include "esp_netif_sntp.h"
#include "esp_sntp.h"
#include "esp_netif.h"
#include "esp_event.h"
#include "esp_pm.h"
#include "esp_task_wdt.h"
#include "driver/gpio.h"

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
#include "relay_service.h"
#include "sensor_service.h"
#include "health_service.h"
#include "ota_service.h"
#include "ble_provision.h"
/* C3 fix: single definition of g_conn_mgr_task_handle here */
#include "task_handles.h"
TaskHandle_t g_conn_mgr_task_handle = NULL;

static const char *TAG = "ykp_main";

/* ── Global state ──────────────────────────── */
static ykp_security_ctx_t  g_sec_ctx;
static ykp_session_t       g_session;
static ykp_replay_ctx_t    g_replay;
static ykp_qos_engine_t    g_qos;
static char                g_device_id[9]   = {0};
static char                g_device_type[16] = {0};
static char                g_server_url[128] = {0};
static TimerHandle_t       g_qos_timer = NULL;

/* ── TX send function (used by services) ─────── */
static void ykp_send_packet(const uint8_t *data, uint16_t len)
{
    if (ykp_ws_is_connected()) {
        ykp_ws_send(data, len);
    } else {
        ESP_LOGW(TAG, "cannot send: WS not connected");
    }
}

/* ── QoS timer callback ── */
static void qos_timer_callback(TimerHandle_t xTimer)
{
    ykp_qos_tick(&g_qos);
}

/* ── Auth handshake ─────────────────────────── */
static void send_hello(void)
{
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

static void dump_decrypted_packet(const ykp_packet_t *pkt)
{
    char src[9] = {0};
    char dst[9] = {0};
    memcpy(src, pkt->header.source_id, 8);
    memcpy(dst, pkt->header.dest_id, 8);

    printf("[YKP RX] Packet ID: %lu, Source: %s, Dest: %s, Service: %d, Action: %d, Payload Len: %d\n",
           (unsigned long)pkt->header.packet_id, src, dst,
           pkt->header.service_id, pkt->header.action_id, pkt->payload_len);
    fflush(stdout);
}

/* ── Incoming packet dispatcher ─────────────── */
static void handle_incoming_packet(const uint8_t *raw, uint16_t raw_len)
{
    ykp_packet_t pkt = {0};
    if (!ykp_packet_parse(raw, raw_len, &pkt)) return;

    uint32_t pkt_id = pkt.header.packet_id;

    if (pkt.header.flags & YKP_FLAG_ACK) {
        ykp_qos_ack(&g_qos, pkt_id);
        goto done;
    }

    if (pkt.header.service_id == SVC_SECURITY) {
        switch ((ykp_sec_action_t)pkt.header.action_id) {

            case SEC_CHALLENGE: {
                ESP_LOGI(TAG, "CHALLENGE received");
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
                ESP_LOGI(TAG, "SESSION ACTIVE — services initialized (polling threads offloaded)");
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

    if (!ykp_session_is_active(&g_session)) {
        ESP_LOGW(TAG, "packet received without active session");
        goto done;
    }

    if (!ykp_replay_check(&g_replay, pkt_id)) {
        ESP_LOGW(TAG, "replay attack blocked pkt_id=%lu", (unsigned long)pkt_id);
        goto done;
    }

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

    dump_decrypted_packet(&pkt);

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
            ota_service_handle(pkt.payload, pkt.payload_len, pkt.header.action_id);
            break;

        default:
            ESP_LOGW(TAG, "unhandled service: 0x%02X", pkt.header.service_id);
            break;
    }

done:
    /* F1 fix: ykp_packet_parse may set pkt.payload = s_rx_payload_buf (static).
       ykp_packet_free already guards this, but raw free() here does not — use the helper. */
    if (pkt.payload) ykp_packet_free_payload(&pkt);
}

/* ── WS callbacks ── */
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
    ESP_LOGI(TAG, "WiFi connected, IP=%s — starting SNTP sync", ip);
    
    esp_sntp_config_t sntp_config = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    esp_netif_sntp_init(&sntp_config);
    if (esp_netif_sntp_start() == ESP_OK) {
        ESP_LOGI(TAG, "Waiting for time sync...");
        int retry = 0;
        const int retry_count = 10;
        while (esp_netif_sntp_sync_wait(pdMS_TO_TICKS(1000)) == ESP_ERR_TIMEOUT && ++retry < retry_count) {}
    }
    ykp_ws_connect();
}

static void on_wifi_disconnected(uint8_t reason)
{
    ESP_LOGW(TAG, "WiFi disconnected (reason: %d)", reason);
}

static void connection_manager_task(void *arg)
{
    char ssid[64] = {0}, password[64] = {0};
    nvs_config_get_wifi_ssid(ssid, sizeof(ssid));
    nvs_config_get_wifi_password(password, sizeof(password));

    /* F3 fix: watchdog registered so we can detect hangs */
    esp_task_wdt_add(NULL);

    wifi_manager_connect_async(ssid, password);

    /* F3 fix: count loops; restart after ~3 min if WiFi never connects */
    uint32_t fail_count = 0;
    const uint32_t MAX_FAILS = 90; /* 90 * 2s = 180s = 3 min */
    while (1) {
        esp_task_wdt_reset();
        vTaskDelay(pdMS_TO_TICKS(2000));

        if (!ykp_ws_is_connected()) {
            fail_count++;
            if (fail_count >= MAX_FAILS) {
                ESP_LOGE("conn_mgr", "WiFi/WS not connected after %lu attempts — restarting",
                         (unsigned long)fail_count);
                esp_restart();
            }
        } else {
            fail_count = 0; /* reset counter on successful connect */
        }
    }
}

/* ════════════════════════════════════════════════
   app_main
   ════════════════════════════════════════════════ */
void app_main(void)
{
    ESP_LOGI(TAG, "═══════════════════════════════════════");
    ESP_LOGI(TAG, "  YKP v5 Firmware (Lightweight Audited)");
    ESP_LOGI(TAG, "═══════════════════════════════════════");

    if (!nvs_config_init()) {
        ESP_LOGE(TAG, "NVS init failed — halting");
        esp_restart();
    }

    uint32_t restarts = nvs_config_incr_restart_count();
    ESP_LOGI(TAG, "Boot count: %lu", (unsigned long)restarts);

    char temp_ssid[64] = {0};
    char temp_password[64] = {0};
    bool has_id = nvs_config_get_device_id(g_device_id, sizeof(g_device_id));
    bool has_ssid = nvs_config_get_wifi_ssid(temp_ssid, sizeof(temp_ssid));
    bool has_pass = nvs_config_get_wifi_password(temp_password, sizeof(temp_password));
    bool is_provisioned = has_id && has_ssid && has_pass;

    wifi_manager_init();
    wifi_manager_register_callbacks(on_wifi_connected, on_wifi_disconnected);

    if (!is_provisioned) {
        ESP_LOGW(TAG, "Device not provisioned — BLE mode");
        if (ykp_ble_provision_start()) {
            ykp_ble_provision_wait();
            esp_restart();
        }
    }

    nvs_config_get_device_id(g_device_id, sizeof(g_device_id));
    nvs_config_get_device_type(g_device_type, sizeof(g_device_type));
    nvs_config_get_server_url(g_server_url, sizeof(g_server_url));

    /* Initialize security, sessions, and timers */
    if (!ykp_security_init(&g_sec_ctx)) {
        ESP_LOGE(TAG, "security init failed");
        esp_restart();
    }

    ykp_session_init(&g_session, &g_sec_ctx);
    ykp_replay_init(&g_replay);
    ykp_qos_init(&g_qos, ykp_send_packet);

    /* Allocate QoS software timer in place of background thread */
    g_qos_timer = xTimerCreate("qos_timer", pdMS_TO_TICKS(500), pdTRUE, NULL, qos_timer_callback);
    g_qos.timer = g_qos_timer;

    ykp_ws_init(g_server_url);
    ykp_ws_register_rx_cb(handle_incoming_packet);
    ykp_ws_register_connected_cb(on_ws_connected);
    ykp_ws_register_disconnected_cb(on_ws_disconnected);

    /* Services — pass session context so button ACK has valid IDs (H4 fix) */
    relay_service_init(ykp_send_packet, &g_session);
    sensor_service_init(ykp_send_packet);
    health_service_init(ykp_send_packet);
    ota_service_init(ykp_send_packet);

    /* Start connection manager task */
    esp_task_wdt_config_t wdt_config = {
        .timeout_ms = 15000,
        .idle_core_mask = 0,
        .trigger_panic = false,
    };
    esp_task_wdt_init(&wdt_config);
    xTaskCreatePinnedToCore(connection_manager_task, "conn_mgr", 6144, NULL, 6, &g_conn_mgr_task_handle, 1);

    /* Main loop — Key rotation and 1-minute health reports */
    uint32_t health_loop_counter = 0;
    while (1) {
        if (ykp_session_rotation_due(&g_session) && ykp_ws_is_connected()) {
            ESP_LOGI(TAG, "key rotation due — re-authenticating");
            ykp_session_reset(&g_session);
            send_hello();
        }

        health_loop_counter++;
        if (health_loop_counter >= 12) { // 12 loops * 5s = 60s
            health_loop_counter = 0;
            if (ykp_session_is_active(&g_session)) {
                ESP_LOGI(TAG, "Main loop executing 1-minute health report");
                health_service_send_report();
            }
        }

        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}
