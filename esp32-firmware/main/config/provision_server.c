#include "provision_server.h"
#include "nvs_config.h"
#include "device_config.h"
#include <string.h>
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_http_server.h"
#include "esp_netif.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "cJSON.h"

static const char *TAG = "provision";

#define DEVICE_AP_SSID_PREFIX   "YKP-Setup-"
#define DEVICE_AP_PASSWORD      "ykpsetup123"
#define DEVICE_AP_CHANNEL       1
#define DEVICE_AP_MAX_CONN      1

static httpd_handle_t s_server = NULL;

/* ────────────────────────────────────────────────
   GET /ping — health check for app connectivity
   ──────────────────────────────────────────────── */
static esp_err_t ping_handler(httpd_req_t *req)
{
    const char *resp = "{\"status\":\"ok\",\"mode\":\"provisioning\"}";
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_sendstr(req, resp);
    return ESP_OK;
}

/* ────────────────────────────────────────────────
   POST /provision — receive WiFi + device config
   Body (JSON):
   {
     "ssid":        "HomeNetwork",
     "password":    "secret",
     "device_id":   "SW001",
     "device_name": "Living Room Switch",
     "device_type": "switch",
     "server_url":  "wss://ykp-router.onrender.com/ws"
   }
   ──────────────────────────────────────────────── */
static esp_err_t provision_handler(httpd_req_t *req)
{
    /* CORS preflight */
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Headers", "Content-Type");

    if (req->method == HTTP_OPTIONS) {
        httpd_resp_send(req, NULL, 0);
        return ESP_OK;
    }

    /* Read body */
    char buf[512] = {0};
    int  ret, remaining = req->content_len;
    if (remaining >= (int)sizeof(buf)) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Body too large");
        return ESP_OK;
    }
    if ((ret = httpd_req_recv(req, buf, remaining)) <= 0) {
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Read error");
        return ESP_OK;
    }
    buf[ret] = '\0';
    ESP_LOGI(TAG, "provision body: %s", buf);

    /* Parse JSON */
    cJSON *root = cJSON_Parse(buf);
    if (!root) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Invalid JSON");
        return ESP_OK;
    }

    const char *ssid        = cJSON_GetStringValue(cJSON_GetObjectItem(root, "ssid"));
    const char *password    = cJSON_GetStringValue(cJSON_GetObjectItem(root, "password"));
    const char *device_id   = cJSON_GetStringValue(cJSON_GetObjectItem(root, "device_id"));
    const char *device_name = cJSON_GetStringValue(cJSON_GetObjectItem(root, "device_name"));
    const char *device_type = cJSON_GetStringValue(cJSON_GetObjectItem(root, "device_type"));
    const char *server_url  = cJSON_GetStringValue(cJSON_GetObjectItem(root, "server_url"));

    if (!ssid || !password || !device_id) {
        cJSON_Delete(root);
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Missing required fields");
        return ESP_OK;
    }

    /* Save to NVS */
    nvs_config_set_str("wifi_ssid",    ssid);
    nvs_config_set_str("wifi_password", password);
    nvs_config_set_str("device_id",    device_id);
    if (device_name) nvs_config_set_str("device_name", device_name);
    if (device_type) nvs_config_set_str("device_type", device_type);
    if (server_url)  nvs_config_set_str("server_url",  server_url);

    cJSON_Delete(root);

    ESP_LOGI(TAG, "provision saved: device_id=%s ssid=%s", device_id, ssid);

    /* Send success response */
    const char *resp = "{\"success\":true,\"message\":\"Config saved. Restarting...\"}";
    httpd_resp_set_type(req, "application/json");
    httpd_resp_sendstr(req, resp);

    /* Restart after short delay */
    vTaskDelay(pdMS_TO_TICKS(500));
    esp_restart();

    return ESP_OK;
}

static const httpd_uri_t ping_uri = {
    .uri     = "/ping",
    .method  = HTTP_GET,
    .handler = ping_handler,
    .user_ctx = NULL,
};

static const httpd_uri_t provision_uri = {
    .uri     = "/provision",
    .method  = HTTP_POST,
    .handler = provision_handler,
    .user_ctx = NULL,
};

static const httpd_uri_t provision_options_uri = {
    .uri     = "/provision",
    .method  = HTTP_OPTIONS,
    .handler = provision_handler,
    .user_ctx = NULL,
};

/* ════════════════════════════════════════════
   Start SoftAP + HTTP provisioning server
   ════════════════════════════════════════════ */
bool provision_server_start(void)
{
    /* ── SoftAP ─────────────────────────────── */
    esp_netif_create_default_wifi_ap();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&cfg);

    /* Build SSID: "YKP-Setup-" + last 3 bytes of MAC */
    uint8_t mac[6];
    esp_wifi_get_mac(WIFI_IF_AP, mac);

    char ap_ssid[32];
    snprintf(ap_ssid, sizeof(ap_ssid), "%s%02X%02X%02X",
             DEVICE_AP_SSID_PREFIX, mac[3], mac[4], mac[5]);

    wifi_config_t ap_config = {
        .ap = {
            .channel     = DEVICE_AP_CHANNEL,
            .max_connection = DEVICE_AP_MAX_CONN,
            .authmode    = WIFI_AUTH_WPA2_PSK,
        },
    };
    strncpy((char *)ap_config.ap.ssid,     ap_ssid,           sizeof(ap_config.ap.ssid) - 1);
    strncpy((char *)ap_config.ap.password, DEVICE_AP_PASSWORD, sizeof(ap_config.ap.password) - 1);
    ap_config.ap.ssid_len = strlen(ap_ssid);

    esp_wifi_set_mode(WIFI_MODE_AP);
    esp_wifi_set_config(WIFI_IF_AP, &ap_config);
    esp_wifi_start();

    ESP_LOGI(TAG, "SoftAP started: SSID=%s PW=%s", ap_ssid, DEVICE_AP_PASSWORD);

    /* ── HTTP server ─────────────────────────── */
    httpd_config_t http_cfg = HTTPD_DEFAULT_CONFIG();
    http_cfg.server_port  = 80;
    http_cfg.max_uri_handlers = 4;

    if (httpd_start(&s_server, &http_cfg) != ESP_OK) {
        ESP_LOGE(TAG, "httpd_start failed");
        return false;
    }

    httpd_register_uri_handler(s_server, &ping_uri);
    httpd_register_uri_handler(s_server, &provision_uri);
    httpd_register_uri_handler(s_server, &provision_options_uri);

    ESP_LOGI(TAG, "HTTP provision server started on port 80");
    return true;
}

void provision_server_stop(void)
{
    if (s_server) {
        httpd_stop(s_server);
        s_server = NULL;
    }
    esp_wifi_stop();
}
