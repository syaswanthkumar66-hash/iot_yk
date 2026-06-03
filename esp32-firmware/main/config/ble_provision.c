#include "ble_provision.h"
#include "nvs_config.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "network_provisioning/manager.h"
#include "network_provisioning/scheme_ble.h"
#include "cJSON.h"
#include <string.h>

static const char *TAG = "ble_prov";

/* Custom endpoint handler to receive configuration JSON from the app */
static esp_err_t ykp_config_handler(uint32_t session_id, const uint8_t *inbuf, ssize_t inlen,
                                    uint8_t **outbuf, ssize_t *outlen, void *priv_data)
{
    if (inbuf == NULL || inlen <= 0) {
        return ESP_ERR_INVALID_ARG;
    }

    /* Ensure null-terminated string for cJSON */
    char *json_str = malloc(inlen + 1);
    if (!json_str) return ESP_ERR_NO_MEM;
    memcpy(json_str, inbuf, inlen);
    json_str[inlen] = '\0';

    ESP_LOGI(TAG, "Received custom config: %s", json_str);

    cJSON *root = cJSON_Parse(json_str);
    free(json_str);
    if (!root) {
        ESP_LOGE(TAG, "Failed to parse JSON config");
        return ESP_FAIL;
    }

    cJSON *server_url = cJSON_GetObjectItem(root, "server_url");
    if (server_url && server_url->valuestring) {
        nvs_config_set_str("server_url", server_url->valuestring);
        ESP_LOGI(TAG, "Saved server_url: %s", server_url->valuestring);
    }

    cJSON *device_type = cJSON_GetObjectItem(root, "device_type");
    if (device_type && device_type->valuestring) {
        nvs_config_set_str("device_type", device_type->valuestring);
        ESP_LOGI(TAG, "Saved device_type: %s", device_type->valuestring);
    }

    cJSON_Delete(root);

    /* Send empty response back */
    *outbuf = malloc(1);
    if (*outbuf) {
        (*outbuf)[0] = 0;
        *outlen = 1;
    } else {
        *outlen = 0;
    }

    return ESP_OK;
}

/* Event handler for provisioning events */
static void prov_event_handler(void* arg, esp_event_base_t event_base,
                               int32_t event_id, void* event_data)
{
    if (event_base == NETWORK_PROV_EVENT) {
        switch (event_id) {
            case NETWORK_PROV_START:
                ESP_LOGI(TAG, "Provisioning started");
                break;
            case NETWORK_PROV_WIFI_CRED_RECV: {
                wifi_sta_config_t *wifi_sta_cfg = (wifi_sta_config_t *)event_data;
                ESP_LOGI(TAG, "Received Wi-Fi credentials\n\tSSID     : %s\n\tPassword : %s",
                         (const char *) wifi_sta_cfg->ssid,
                         (const char *) wifi_sta_cfg->password);
                
                nvs_config_set_wifi_ssid((const char *)wifi_sta_cfg->ssid);
                nvs_config_set_wifi_password((const char *)wifi_sta_cfg->password);
                break;
            }
            case NETWORK_PROV_WIFI_CRED_FAIL: {
                network_prov_sta_fail_reason_t *reason = (network_prov_sta_fail_reason_t *)event_data;
                ESP_LOGE(TAG, "Provisioning failed!\n\tReason : %s",
                         (*reason == NETWORK_PROV_STA_AUTH_ERROR) ?
                         "Wi-Fi station authentication failed" : "Wi-Fi access-point not found");
                /* Reset provisioning state so the user can try again */
                network_prov_mgr_reset_sm_state_on_failure();
                break;
            }
            case NETWORK_PROV_WIFI_CRED_SUCCESS:
                ESP_LOGI(TAG, "Provisioning successful");
                break;
            case NETWORK_PROV_END:
                /* De-initialize manager once provisioning is finished */
                network_prov_mgr_deinit();
                ESP_LOGI(TAG, "Provisioning ending. Restarting to apply settings...");
                esp_restart(); /* Restart to ensure clean state with new config */
                break;
            default:
                break;
        }
    }
}

bool ykp_ble_provision_start(void)
{
    /* Initialize Wi-Fi driver first since wifi_prov_mgr needs it */
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&cfg);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();

    /* Register the provisioning event handler */
    esp_event_handler_register(NETWORK_PROV_EVENT, ESP_EVENT_ANY_ID, &prov_event_handler, NULL);

    /* Configuration for the provisioning manager */
    network_prov_mgr_config_t config = {
        .scheme = network_prov_scheme_ble,
        .scheme_event_handler = NETWORK_PROV_SCHEME_BLE_EVENT_HANDLER_FREE_BTDM
    };

    /* Initialize provisioning manager with the configuration parameters */
    if (network_prov_mgr_init(config) != ESP_OK) {
        ESP_LOGE(TAG, "Provisioning manager initialization failed");
        return false;
    }

    bool provisioned = false;
    network_prov_mgr_is_wifi_provisioned(&provisioned);

    /* Only start if not already provisioned */
    if (!provisioned) {
        ESP_LOGI(TAG, "Starting BLE provisioning...");

        char device_name[32] = {0};
        char device_id[9] = {0};
        nvs_config_get_device_id(device_id, sizeof(device_id));
        snprintf(device_name, sizeof(device_name), "YKP_PROV_%s", device_id);

        /* Set custom endpoint for configuration */
        network_prov_mgr_endpoint_create("ykp-config");

        /* Define BLE service configuration */
        uint8_t custom_service_uuid[] = {
            /* LSB <---------------------------------------
             * ---------------------------------------> MSB */
            0xb4, 0xdf, 0x5a, 0x1c, 0x3f, 0x6b, 0xf4, 0xbf,
            0xea, 0x4a, 0x82, 0x03, 0x04, 0x90, 0x1a, 0x02,
        };
        network_prov_scheme_ble_set_service_uuid(custom_service_uuid);

        /* Start provisioning service */
        network_prov_mgr_start_provisioning(NETWORK_PROV_SECURITY_1, NULL, device_name, NULL);

        /* Register custom endpoint handler */
        network_prov_mgr_endpoint_register("ykp-config", ykp_config_handler, NULL);
    } else {
        ESP_LOGI(TAG, "Already provisioned, de-initializing manager");
        network_prov_mgr_deinit();
        return true;
    }

    return true;
}
