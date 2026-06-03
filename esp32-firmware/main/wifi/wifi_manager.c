#include "wifi_manager.h"
#include <string.h>
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"

static const char *TAG = "wifi_manager";

#define WIFI_CONNECTED_BIT  BIT0
#define WIFI_FAIL_BIT       BIT1
#define WIFI_MAX_RETRIES    5

static EventGroupHandle_t   s_wifi_event_group;
static wifi_state_t         s_state = WIFI_STATE_DISCONNECTED;
static int                  s_retry_count = 0;
static wifi_connected_cb_t  s_on_connect = NULL;
static wifi_disconnected_cb_t s_on_disconnect = NULL;
static char                 s_ip[20] = {0};
static esp_netif_t         *s_netif = NULL;

static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                                int32_t event_id, void *event_data)
{
    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
        s_state = WIFI_STATE_CONNECTING;
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        s_state = WIFI_STATE_DISCONNECTED;
        if (s_on_disconnect) s_on_disconnect();
        if (s_retry_count < WIFI_MAX_RETRIES) {
            s_retry_count++;
            ESP_LOGI(TAG, "retry %d/%d", s_retry_count, WIFI_MAX_RETRIES);
            vTaskDelay(pdMS_TO_TICKS(2000 * s_retry_count));
            esp_wifi_connect();
            s_state = WIFI_STATE_CONNECTING;
        } else {
            ESP_LOGE(TAG, "max retries reached");
            xEventGroupSetBits(s_wifi_event_group, WIFI_FAIL_BIT);
            s_state = WIFI_STATE_FAILED;
        }
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)event_data;
        snprintf(s_ip, sizeof(s_ip), IPSTR, IP2STR(&event->ip_info.ip));
        ESP_LOGI(TAG, "IP: %s", s_ip);
        s_retry_count = 0;
        s_state = WIFI_STATE_CONNECTED;
        xEventGroupSetBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
        if (s_on_connect) s_on_connect(s_ip);
    }
}

bool wifi_manager_init(void)
{
    s_wifi_event_group = xEventGroupCreate();
    esp_netif_init();
    esp_event_loop_create_default();
    s_netif = esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&cfg);

    esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                         &wifi_event_handler, NULL, NULL);
    esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                         &wifi_event_handler, NULL, NULL);
    ESP_LOGI(TAG, "wifi manager initialised");
    return true;
}

bool wifi_manager_connect(const char *ssid, const char *password)
{
    wifi_config_t wifi_config = {0};
    strncpy((char *)wifi_config.sta.ssid,     ssid,     sizeof(wifi_config.sta.ssid) - 1);
    strncpy((char *)wifi_config.sta.password, password, sizeof(wifi_config.sta.password) - 1);
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;

    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_set_config(WIFI_IF_STA, &wifi_config);
    esp_wifi_start();
    esp_wifi_set_ps(WIFI_PS_MIN_MODEM);

    ESP_LOGI(TAG, "connecting to SSID: %s (modem sleep enabled)", ssid);

    EventBits_t bits = xEventGroupWaitBits(s_wifi_event_group,
                                            WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
                                            pdFALSE, pdFALSE,
                                            pdMS_TO_TICKS(30000));
    return (bits & WIFI_CONNECTED_BIT) != 0;
}

bool wifi_manager_is_connected(void)
{
    return s_state == WIFI_STATE_CONNECTED;
}

wifi_state_t wifi_manager_get_state(void)
{
    return s_state;
}

void wifi_manager_register_callbacks(wifi_connected_cb_t on_connect,
                                     wifi_disconnected_cb_t on_disconnect)
{
    s_on_connect    = on_connect;
    s_on_disconnect = on_disconnect;
}

int8_t wifi_manager_get_rssi(void)
{
    wifi_ap_record_t ap;
    if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) return ap.rssi;
    return 0;
}

void wifi_manager_get_ip(char *out, size_t len)
{
    strncpy(out, s_ip, len - 1);
}
