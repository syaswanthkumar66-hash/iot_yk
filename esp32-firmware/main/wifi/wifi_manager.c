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
static EventGroupHandle_t   s_wifi_event_group;
static wifi_state_t         s_state = WIFI_STATE_DISCONNECTED;
static uint8_t              s_disconnect_reason = 0;
static wifi_connected_cb_t  s_on_connect = NULL;
static wifi_disconnected_cb_t s_on_disconnect = NULL;
static char                 s_ip[20] = {0};
static esp_netif_t         *s_netif = NULL;

static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                                int32_t event_id, void *event_data)
{
    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        // Do nothing automatically, wait for connect request
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *evt = (wifi_event_sta_disconnected_t *)event_data;
        s_disconnect_reason = evt->reason;
        s_state = WIFI_STATE_FAILED;
        xEventGroupSetBits(s_wifi_event_group, WIFI_FAIL_BIT);
        if (s_on_disconnect) s_on_disconnect(s_disconnect_reason);
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)event_data;
        snprintf(s_ip, sizeof(s_ip), IPSTR, IP2STR(&event->ip_info.ip));
        ESP_LOGI(TAG, "IP: %s", s_ip);
        s_state = WIFI_STATE_CONNECTED;
        s_disconnect_reason = 0;
        xEventGroupSetBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
        if (s_on_connect) s_on_connect(s_ip);
    }
}

static bool s_wifi_started = false;

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
    wifi_manager_connect_async(ssid, password);
    EventBits_t bits = xEventGroupWaitBits(s_wifi_event_group,
                                            WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
                                            pdFALSE, pdFALSE,
                                            pdMS_TO_TICKS(30000));
    return (bits & WIFI_CONNECTED_BIT) != 0;
}

void wifi_manager_connect_async(const char *ssid, const char *password)
{
    wifi_config_t wifi_config = {0};
    strncpy((char *)wifi_config.sta.ssid,     ssid,     sizeof(wifi_config.sta.ssid) - 1);
    strncpy((char *)wifi_config.sta.password, password, sizeof(wifi_config.sta.password) - 1);
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;

    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_set_config(WIFI_IF_STA, &wifi_config);
    if (!s_wifi_started) {
        esp_wifi_start();
        s_wifi_started = true;
    }
    esp_wifi_set_ps(WIFI_PS_MIN_MODEM);
    
    xEventGroupClearBits(s_wifi_event_group, WIFI_CONNECTED_BIT | WIFI_FAIL_BIT);
    s_state = WIFI_STATE_CONNECTING;
    s_disconnect_reason = 0;
    
    ESP_LOGI(TAG, "connecting to SSID: %s (modem sleep enabled)", ssid);
    esp_wifi_connect();
}

void wifi_manager_disconnect(void)
{
    esp_wifi_disconnect();
    s_state = WIFI_STATE_DISCONNECTED;
}

bool wifi_manager_is_connected(void)
{
    return s_state == WIFI_STATE_CONNECTED;
}

wifi_state_t wifi_manager_get_state(void)
{
    return s_state;
}

uint8_t wifi_manager_get_disconnect_reason(void)
{
    return s_disconnect_reason;
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

bool wifi_manager_scan_for_ssid(const char *target_ssid)
{
    if (!target_ssid || strlen(target_ssid) == 0) return false;
    
    // Ensure STA mode
    esp_wifi_set_mode(WIFI_MODE_STA);
    if (!s_wifi_started) {
        esp_wifi_start();
        s_wifi_started = true;
    }
    
    wifi_scan_config_t scan_config = {
        .ssid = (uint8_t *)target_ssid,
        .bssid = NULL,
        .channel = 0,
        .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
        .scan_time.active.min = 100,
        .scan_time.active.max = 300,
    };
    
    esp_err_t err = esp_wifi_scan_start(&scan_config, true);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "scan start failed: %s", esp_err_to_name(err));
        return false; // assume not found or busy
    }
    
    uint16_t ap_count = 0;
    esp_wifi_scan_get_ap_num(&ap_count);
    
    // Free the internal list allocated by driver
    if (ap_count > 0) {
        wifi_ap_record_t *ap_list = malloc(sizeof(wifi_ap_record_t) * ap_count);
        if (ap_list) {
            esp_wifi_scan_get_ap_records(&ap_count, ap_list);
            free(ap_list);
        }
    }
    
    return (ap_count > 0);
}

int wifi_manager_scan(char *json_out, size_t max_len)
{
    esp_wifi_set_mode(WIFI_MODE_STA);
    if (!s_wifi_started) {
        esp_wifi_start();
        s_wifi_started = true;
    }
    
    wifi_scan_config_t scan_config = {
        .ssid = NULL,
        .bssid = NULL,
        .channel = 0,
        .show_hidden = true,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
    };
    
    esp_err_t err = esp_wifi_scan_start(&scan_config, true);
    if (err != ESP_OK) {
        snprintf(json_out, max_len, "{\"scan_results\":[]}");
        return 0;
    }
    
    uint16_t ap_count = 0;
    esp_wifi_scan_get_ap_num(&ap_count);
    
    if (ap_count == 0) {
        snprintf(json_out, max_len, "{\"scan_results\":[]}");
        return 0;
    }
    
    if (ap_count > 10) ap_count = 10; // Limit to 10 to save memory
    
    wifi_ap_record_t *ap_list = malloc(sizeof(wifi_ap_record_t) * ap_count);
    if (!ap_list) {
        snprintf(json_out, max_len, "{\"scan_results\":[]}");
        return 0;
    }
    
    esp_wifi_scan_get_ap_records(&ap_count, ap_list);
    
    int offset = snprintf(json_out, max_len, "{\"scan_results\":[");
    for (int i = 0; i < ap_count; i++) {
        int w = snprintf(json_out + offset, max_len - offset,
                         "{\"ssid\":\"%s\",\"rssi\":%d}%s",
                         ap_list[i].ssid, ap_list[i].rssi,
                         (i < ap_count - 1) ? "," : "");
        if (w > 0 && w < max_len - offset) offset += w;
    }
    snprintf(json_out + offset, max_len - offset, "]}");
    
    free(ap_list);
    return ap_count;
}
