#include "ykp_websocket.h"
#include "device_config.h"
#include <string.h>
#include "esp_log.h"
#include "esp_websocket_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

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
    esp_websocket_client_config_t ws_cfg = {
        .uri                = server_url,
        .reconnect_timeout_ms = YKP_WS_RECONNECT_MS,
        .network_timeout_ms   = YKP_WS_TIMEOUT_MS,
        .ping_interval_sec    = YKP_WS_PING_INTERVAL_MS / 1000,
        .buffer_size          = 4096,
        .task_stack           = TASK_STACK_WS,
        .task_prio            = TASK_PRIO_WS,
    };

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
