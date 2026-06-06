#include "ykp_websocket.h"
#include "device_config.h"
#include <string.h>
#include "esp_log.h"
#include "esp_websocket_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_crt_bundle.h"
#include "nvs_config.h"
#include "mbedtls/x509_crt.h"
#include "mbedtls/error.h"

static const char *TAG = "ykp_ws";

static esp_websocket_client_handle_t s_ws_client = NULL;
static bool                          s_connected  = false;
static ykp_ws_rx_cb_t                s_rx_cb      = NULL;
static ykp_ws_connected_cb_t         s_conn_cb    = NULL;
static ykp_ws_disconnected_cb_t      s_disc_cb    = NULL;

static void ws_event_handler(void *arg, esp_event_base_t event_base,
                              int32_t event_id, void *event_data)
{
    esp_websocket_event_data_t *data = (esp_websocket_event_data_t *)event_data;

    switch (event_id) {
        case WEBSOCKET_EVENT_CONNECTED:
            ESP_LOGI(TAG, "WebSocket connected");
            s_connected = true;
            if (s_conn_cb) s_conn_cb();
            break;

        case WEBSOCKET_EVENT_DISCONNECTED:
            ESP_LOGW(TAG, "WebSocket disconnected");
            s_connected = false;
            if (s_disc_cb) s_disc_cb();
            break;

        case WEBSOCKET_EVENT_DATA:
            if (data->op_code == 0x02 /* binary */ && data->data_len > 0) {
                ESP_LOGD(TAG, "RX %d bytes", data->data_len);
                if (s_rx_cb) {
                    s_rx_cb((const uint8_t *)data->data_ptr, data->data_len);
                }
            }
            break;

        case WEBSOCKET_EVENT_ERROR:
            ESP_LOGE(TAG, "WebSocket error");
            break;

        default:
            break;
    }
}

bool ykp_ws_init(const char *server_url)
{
    static char ssl_cert_buf[2048];
    bool has_custom_cert = nvs_config_get_str("ssl_cert", ssl_cert_buf, sizeof(ssl_cert_buf));
    if (has_custom_cert && strlen(ssl_cert_buf) > 0) {
        // Sanitize certificate: replace literal "\n" strings with real 0x0A newlines
        char cleaned_cert[2048] = {0};
        int clean_idx = 0;
        int len = strlen(ssl_cert_buf);
        for (int i = 0; i < len && clean_idx < sizeof(cleaned_cert) - 2; i++) {
            if (ssl_cert_buf[i] == '\\' && i + 1 < len && ssl_cert_buf[i+1] == 'n') {
                cleaned_cert[clean_idx++] = '\n';
                i++; // Skip the 'n'
            } else {
                cleaned_cert[clean_idx++] = ssl_cert_buf[i];
            }
        }
        
        // mbedTLS strongly requires PEM files to end with a newline
        if (clean_idx > 0 && cleaned_cert[clean_idx - 1] != '\n') {
            cleaned_cert[clean_idx++] = '\n';
        }
        cleaned_cert[clean_idx] = '\0';
        
        // Copy back to static buffer
        strncpy(ssl_cert_buf, cleaned_cert, sizeof(ssl_cert_buf));
        
        // Validate that the certificate is actually complete!
        if (strstr(ssl_cert_buf, "-----END CERTIFICATE-----") == NULL) {
            ESP_LOGE(TAG, "Corrupted/Truncated SSL Certificate found in NVS! Discarding and using default bundle.");
            has_custom_cert = false;
        } else {
            // Strictly validate using mbedtls before trusting it
            mbedtls_x509_crt test_crt;
            mbedtls_x509_crt_init(&test_crt);
            int ret = mbedtls_x509_crt_parse(&test_crt, (const unsigned char *)cleaned_cert, strlen(cleaned_cert) + 1);
            mbedtls_x509_crt_free(&test_crt);
            
            if (ret != 0) {
                ESP_LOGE(TAG, "Strict mbedtls parse failed (-0x%04X). Cert is corrupted! Falling back to default bundle.", -ret);
                has_custom_cert = false;
                nvs_config_set_str("ssl_cert", ""); // Erase corrupt cert from NVS
            } else {
                ESP_LOGI(TAG, "Loaded custom SSL certificate from NVS (len: %d) and passed strict validation.", (int)strlen(ssl_cert_buf));
            }
        }
    } else {
        has_custom_cert = false;
    }

    esp_websocket_client_config_t ws_cfg = {
        .uri                = server_url,
        .reconnect_timeout_ms = YKP_WS_RECONNECT_MS,
        .network_timeout_ms   = YKP_WS_TIMEOUT_MS,
        .ping_interval_sec    = YKP_WS_PING_INTERVAL_MS / 1000,
        .buffer_size          = 4096,
        .task_stack           = TASK_STACK_WS,
        .task_prio            = TASK_PRIO_WS,
    };

    // Use the custom ssl_cert provided via BLE if available (the user updated the app to send the correct Root CA!)
    // Otherwise, fall back to the built-in ESP-IDF root CA bundle (or skip verification if configured in sdkconfig).
    if (has_custom_cert) {
        ws_cfg.cert_pem = ssl_cert_buf;
    } else {
#if CONFIG_ESP_TLS_SKIP_SERVER_CERT_VERIFY
        ws_cfg.crt_bundle_attach = NULL;
        ws_cfg.skip_cert_common_name_check = true;
#else
        ws_cfg.crt_bundle_attach = esp_crt_bundle_attach;
#endif
    }

    s_ws_client = esp_websocket_client_init(&ws_cfg);
    if (!s_ws_client) {
        ESP_LOGE(TAG, "websocket client init failed");
        return false;
    }

    esp_websocket_register_events(s_ws_client, WEBSOCKET_EVENT_ANY,
                                   ws_event_handler, NULL);
    ESP_LOGI(TAG, "WS init OK: %s", server_url);
    return true;
}

bool ykp_ws_connect(void)
{
    if (!s_ws_client) return false;
    esp_err_t err = esp_websocket_client_start(s_ws_client);
    return err == ESP_OK;
}

bool ykp_ws_send(const uint8_t *data, uint16_t len)
{
    if (!s_ws_client || !s_connected) return false;
    int sent = esp_websocket_client_send_bin(s_ws_client, (const char *)data, len,
                                             pdMS_TO_TICKS(3000));
    return sent == len;
}

bool ykp_ws_is_connected(void)
{
    return s_connected && esp_websocket_client_is_connected(s_ws_client);
}

void ykp_ws_disconnect(void)
{
    if (s_ws_client) {
        esp_websocket_client_stop(s_ws_client);
        esp_websocket_client_destroy(s_ws_client);
        s_ws_client = NULL;
    }
    s_connected = false;
}

void ykp_ws_register_rx_cb(ykp_ws_rx_cb_t cb)         { s_rx_cb    = cb; }
void ykp_ws_register_connected_cb(ykp_ws_connected_cb_t cb) { s_conn_cb = cb; }
void ykp_ws_register_disconnected_cb(ykp_ws_disconnected_cb_t cb) { s_disc_cb = cb; }
