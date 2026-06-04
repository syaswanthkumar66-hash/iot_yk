#include "ble_provision.h"
#include "nvs_config.h"
#include "esp_log.h"
#include "esp_system.h"
#include "cJSON.h"
#include <string.h>

/* NimBLE Includes */
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "ble_prov";

/* BLE State Variables */
static uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t tx_char_val_handle;
static uint8_t own_addr_type;

/* Nordic UART Service (NUS) UUIDs */
static const ble_uuid128_t gatt_svr_svc_nus_uuid = 
    BLE_UUID128_INIT(0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, 0x93, 0xF3, 0xA3, 0xB5, 0x01, 0x00, 0x40, 0x6E);
static const ble_uuid128_t gatt_svr_chr_nus_rx_uuid = 
    BLE_UUID128_INIT(0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, 0x93, 0xF3, 0xA3, 0xB5, 0x02, 0x00, 0x40, 0x6E);
static const ble_uuid128_t gatt_svr_chr_nus_tx_uuid = 
    BLE_UUID128_INIT(0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, 0x93, 0xF3, 0xA3, 0xB5, 0x03, 0x00, 0x40, 0x6E);

/* Helper to send logs back to the connected app over BLE TX */
static void ble_serial_log(const char* message) {
    ESP_LOGI(TAG, "%s", message);
    if (conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(message, strlen(message));
        ble_gatts_notify_custom(conn_handle, tx_char_val_handle, om);
    }
}

/* GATT Character Access Callback */
static int gatt_svr_chr_access(uint16_t conn_handle, uint16_t attr_handle, 
                               struct ble_gatt_access_ctxt *ctxt, void *arg) 
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        char rx_buffer[256];
        int len = OS_MBUF_PKTLEN(ctxt->om);
        if (len >= sizeof(rx_buffer)) len = sizeof(rx_buffer) - 1;
        os_mbuf_copydata(ctxt->om, 0, len, rx_buffer);
        rx_buffer[len] = '\0';

        ESP_LOGI(TAG, "BLE RX: %s", rx_buffer);

        /* 1. Format: WIFI:ssid,password */
        if (strncmp(rx_buffer, "WIFI:", 5) == 0) {
            char *ssid = strtok(rx_buffer + 5, ",");
            char *password = strtok(NULL, ",");
            if (ssid) {
                nvs_config_set_str("wifi_ssid", ssid);
                ble_serial_log("[CONFIG] Saved wifi_ssid");
            }
            if (password) {
                nvs_config_set_str("wifi_password", password);
                ble_serial_log("[CONFIG] Saved wifi_password");
            } else {
                nvs_config_set_str("wifi_password", "");
                ble_serial_log("[CONFIG] Saved empty wifi_password");
            }

            ble_serial_log("[SUCCESS] Wi-Fi credentials saved. Restarting device...");
            vTaskDelay(pdMS_TO_TICKS(1500));
            esp_restart();
        } 
        /* 2. Format: JSON Configuration Block */
        else if (rx_buffer[0] == '{') {
            cJSON *root = cJSON_Parse(rx_buffer);
            if (!root) {
                ble_serial_log("[ERROR] JSON parsing failed");
            } else {
                bool restart_needed = false;
                
                cJSON *server_url = cJSON_GetObjectItem(root, "server_url");
                if (server_url && server_url->valuestring) {
                    nvs_config_set_str("server_url", server_url->valuestring);
                    ble_serial_log("[CONFIG] Saved server_url");
                    restart_needed = true;
                }
                cJSON *device_type = cJSON_GetObjectItem(root, "device_type");
                if (device_type && device_type->valuestring) {
                    nvs_config_set_str("device_type", device_type->valuestring);
                    ble_serial_log("[CONFIG] Saved device_type");
                    restart_needed = true;
                }
                cJSON *device_id = cJSON_GetObjectItem(root, "device_id");
                if (device_id && device_id->valuestring) {
                    nvs_config_set_str("device_id", device_id->valuestring);
                    ble_serial_log("[CONFIG] Saved device_id");
                    restart_needed = true;
                }
                cJSON *device_name = cJSON_GetObjectItem(root, "device_name");
                if (device_name && device_name->valuestring) {
                    nvs_config_set_str("device_name", device_name->valuestring);
                    ble_serial_log("[CONFIG] Saved device_name");
                    restart_needed = true;
                }
                cJSON *wifi_ssid = cJSON_GetObjectItem(root, "wifi_ssid");
                if (!wifi_ssid) wifi_ssid = cJSON_GetObjectItem(root, "ssid");
                if (wifi_ssid && wifi_ssid->valuestring) {
                    nvs_config_set_str("wifi_ssid", wifi_ssid->valuestring);
                    ble_serial_log("[CONFIG] Saved wifi_ssid");
                    restart_needed = true;
                }
                cJSON *wifi_pass = cJSON_GetObjectItem(root, "wifi_password");
                if (!wifi_pass) wifi_pass = cJSON_GetObjectItem(root, "password");
                if (!wifi_pass) wifi_pass = cJSON_GetObjectItem(root, "pass");
                if (wifi_pass && wifi_pass->valuestring) {
                    nvs_config_set_str("wifi_password", wifi_pass->valuestring);
                    ble_serial_log("[CONFIG] Saved wifi_password");
                    restart_needed = true;
                }
                cJSON_Delete(root);
                
                if (restart_needed) {
                    ble_serial_log("[SUCCESS] Configuration saved. Restarting device...");
                    vTaskDelay(pdMS_TO_TICKS(1500));
                    esp_restart();
                } else {
                    ble_serial_log("[WARNING] JSON parsed but no valid config keys found");
                }
            }
        } else {
            ble_serial_log("[ERROR] Unknown command. Send WIFI:ssid,pass or JSON config.");
        }
    }
    return 0;
}

/* GATT Services and Characteristics Registration */
static const struct ble_gatt_svc_def gatt_svr_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &gatt_svr_svc_nus_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            { 
                .uuid = &gatt_svr_chr_nus_rx_uuid.u, 
                .access_cb = gatt_svr_chr_access, 
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP 
            },
            { 
                .uuid = &gatt_svr_chr_nus_tx_uuid.u, 
                .access_cb = gatt_svr_chr_access, 
                .flags = BLE_GATT_CHR_F_NOTIFY, 
                .val_handle = &tx_char_val_handle 
            },
            { 0 }
        }
    }, 
    { 0 }
};

/* GAP Event Callback */
static int ble_gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            conn_handle = (event->connect.status == 0) ? event->connect.conn_handle : BLE_HS_CONN_HANDLE_NONE;
            if (conn_handle == BLE_HS_CONN_HANDLE_NONE) {
                void ble_app_advertise(void);
                ble_app_advertise();
            } else {
                ble_serial_log("[SYSTEM] BLE Client Connected");
            }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            conn_handle = BLE_HS_CONN_HANDLE_NONE;
            void ble_app_advertise(void);
            ble_app_advertise();
            break;
    }
    return 0;
}

/* BLE Advertising setup */
void ble_app_advertise(void) {
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    
    char adv_name[32] = {0};
    char dev_id[9] = {0};
    if (nvs_config_get_device_id(dev_id, sizeof(dev_id)) && strlen(dev_id) > 0) {
        snprintf(adv_name, sizeof(adv_name), "YKP_PROV_%s", dev_id);
    } else {
        snprintf(adv_name, sizeof(adv_name), "YKP_PROV_SETUP");
    }

    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)adv_name;
    fields.name_len = strlen(adv_name);
    fields.name_is_complete = 1;
    ble_gap_adv_set_fields(&fields);

    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &adv_params, ble_gap_event, NULL);
}

/* Host Synchronization callback */
static void ble_app_on_sync(void) {
    ble_hs_id_infer_auto(0, &own_addr_type);
    ble_app_advertise();
}

/* FreeRTOS NimBLE host task */
void ble_host_task(void *param) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* Entry point to start the custom BLE provisioning manager */
bool ykp_ble_provision_start(void) {
    ESP_LOGI(TAG, "Starting NimBLE custom BLE provisioning...");
    
    nimble_port_init();
    ble_hs_cfg.sync_cb = ble_app_on_sync;
    
    int rc = ble_gatts_count_cfg(gatt_svr_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "Failed to count GATT service configs: %d", rc);
        return false;
    }
    
    rc = ble_gatts_add_svcs(gatt_svr_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "Failed to add GATT services: %d", rc);
        return false;
    }
    
    nimble_port_freertos_init(ble_host_task);
    return true;
}
