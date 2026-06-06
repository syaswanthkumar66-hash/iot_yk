#include "ble_provision.h"
#include "nvs_config.h"
#include "esp_log.h"
#include "esp_system.h"
#include "cJSON.h"
#include <string.h>
#include "relay_service.h"
#include "wifi_manager.h"
#include "ykp_constants.h"

/* NimBLE Includes */
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "freertos/event_groups.h"

static EventGroupHandle_t ble_prov_event_group;
#define BLE_PROV_SUCCESS_BIT BIT0

static const char *TAG = "ble_prov";

/* BLE State Variables */
static bool s_ble_active = false;
static uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t tx_char_val_handle;
static uint8_t own_addr_type;

#define RX_BUFFER_SIZE 4096
static char rx_accumulation_buffer[RX_BUFFER_SIZE];
static int rx_accumulation_len = 0;

static bool config_received = false;
static bool cert_received = false;

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
        uint16_t mtu = ble_att_mtu(conn_handle);
        int max_payload = mtu - 3; // GATT notification overhead
        
        // Append a newline character to satisfy line-buffered BLE/UART readers
        int msg_len = strlen(message);
        int total_len = msg_len + 1;
        char *temp_msg = malloc(total_len + 1);
        if (temp_msg) {
            snprintf(temp_msg, total_len + 1, "%s\n", message);
            ESP_LOGI(TAG, "Current MTU: %d, Message Length (with newline): %d", mtu, total_len);
            
            if (total_len <= max_payload) {
                // Send as single packet
                struct os_mbuf *om = ble_hs_mbuf_from_flat(temp_msg, total_len);
                int rc = ble_gatts_notify_custom(conn_handle, tx_char_val_handle, om);
                if (rc != 0) {
                    ESP_LOGE(TAG, "Failed to send notification: rc=%d", rc);
                }
            } else {
                // Chunked sending
                int offset = 0;
                while (offset < total_len) {
                    int chunk_size = total_len - offset;
                    if (chunk_size > max_payload) chunk_size = max_payload;
                    
                    struct os_mbuf *om = ble_hs_mbuf_from_flat(temp_msg + offset, chunk_size);
                    int rc = ble_gatts_notify_custom(conn_handle, tx_char_val_handle, om);
                    if (rc != 0) {
                        ESP_LOGE(TAG, "Failed to send chunk notification: rc=%d", rc);
                        break;
                    }
                    offset += chunk_size;
                    vTaskDelay(pdMS_TO_TICKS(30)); // Hardware delay to prevent flooding the queue
                }
            }
            free(temp_msg);
        }
    }
}

/* Public API to send status updates */
void ble_notify_status(const char* status_msg) {
    ble_serial_log(status_msg);
}

static bool s_is_scanning = false;

/* Background task for Wi-Fi scanning (prevents blocking NimBLE thread) */
static void wifi_scan_task(void *pvParameters) {
    char scan_buf[1024];
    wifi_manager_scan(scan_buf, sizeof(scan_buf));
    ble_notify_status(scan_buf);
    s_is_scanning = false;
    vTaskDelete(NULL);
}

/* GATT Character Access Callback */
static void check_success_state(bool this_is_config, bool this_is_cert) {
    if (this_is_config && this_is_cert) {
        config_received = true;
        cert_received = true;
        ble_serial_log("[ACK] PAYLOAD_RECEIVED");
        vTaskDelay(pdMS_TO_TICKS(500));
    } else {
        if (this_is_config) {
            config_received = true;
            ble_serial_log("[ACK] CONFIG_RECEIVED");
            vTaskDelay(pdMS_TO_TICKS(500));
        }
        if (this_is_cert) {
            cert_received = true;
            ble_serial_log("[ACK] CERT_RECEIVED");
            vTaskDelay(pdMS_TO_TICKS(500));
        }
    }
    
    if (config_received && cert_received) {
        if (ble_prov_event_group) {
            xEventGroupSetBits(ble_prov_event_group, BLE_PROV_SUCCESS_BIT);
        }
    }
}

static int gatt_svr_chr_access(uint16_t conn_handle, uint16_t attr_handle, 
                               struct ble_gatt_access_ctxt *ctxt, void *arg) 
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        int len = OS_MBUF_PKTLEN(ctxt->om);
        if (len <= 0) {
            return 0;
        }

        // Check buffer space
        if (rx_accumulation_len + len >= RX_BUFFER_SIZE) {
            ble_serial_log("[ERROR] BLE RX buffer overflow. Resetting buffer.");
            rx_accumulation_len = 0;
            memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));
            return BLE_ATT_ERR_INSUFFICIENT_RES;
        }

        char temp_chunk[512];
        int chunk_len = len;
        if (chunk_len >= sizeof(temp_chunk)) chunk_len = sizeof(temp_chunk) - 1;
        os_mbuf_copydata(ctxt->om, 0, chunk_len, temp_chunk);
        temp_chunk[chunk_len] = '\0';

        char *payload_start = temp_chunk;
        int idx = 0, total = 0, header_len = 0;
        bool is_indexed = false;
        
        // Parse indexed chunk header e.g., "1/3:"
        if (sscanf(temp_chunk, "%d/%d:%n", &idx, &total, &header_len) == 2) {
            is_indexed = true;
            payload_start = temp_chunk + header_len;
            chunk_len -= header_len;
            
            if (idx == 1) {
                // First chunk, clear accumulation buffer
                rx_accumulation_len = 0;
                memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));
                ESP_LOGI(TAG, "Received chunk 1/%d. Cleared buffer.", total);
            }
        }

        // Append to rx_accumulation_buffer, skipping any null bytes
        for (int i = 0; i < chunk_len; i++) {
            if (payload_start[i] != '\0') {
                if (rx_accumulation_len < RX_BUFFER_SIZE - 1) {
                    rx_accumulation_buffer[rx_accumulation_len++] = payload_start[i];
                }
            }
        }
        rx_accumulation_buffer[rx_accumulation_len] = '\0';

        ESP_LOGI(TAG, "BLE RX Chunk (len=%d, total=%d)", len, rx_accumulation_len);
        if (is_indexed) {
            ESP_LOGI(TAG, "Indexed Chunk %d/%d received.", idx, total);
            if (idx < total) {
                // Wait for the rest of the chunks before parsing
                return 0;
            }
        }
        ESP_LOGI(TAG, "Buffer ready for parsing: %s", rx_accumulation_buffer);

        // Check if we should process it.
        // 1. Check if the buffer starts with "WIFI:" (legacy command).
        if (strncmp(rx_accumulation_buffer, "WIFI:", 5) == 0) {
            char *comma = strchr(rx_accumulation_buffer, ',');
            if (comma) {
                char temp_buf[512];
                strncpy(temp_buf, rx_accumulation_buffer, sizeof(temp_buf) - 1);
                temp_buf[sizeof(temp_buf) - 1] = '\0';
                char *ssid = strtok(temp_buf + 5, ",");
                char *password = strtok(NULL, ",\r\n");
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

                // Reset buffer
                rx_accumulation_len = 0;
                memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));

                ble_serial_log("[SUCCESS] Wi-Fi credentials saved.");
                if (ble_prov_event_group) {
                    xEventGroupSetBits(ble_prov_event_group, BLE_PROV_SUCCESS_BIT);
                }
            }
        } 
        // 2. Check if the buffer starts with '{' (JSON Block)
        else if (rx_accumulation_buffer[0] == '{') {
            cJSON *root = cJSON_Parse(rx_accumulation_buffer);
            if (root) {
                ESP_LOGI(TAG, "Successfully parsed BLE JSON command.");
                
                cJSON *cmd_item = cJSON_GetObjectItem(root, "cmd");
                if (cmd_item && cmd_item->valuestring) {
                    const char *cmd = cmd_item->valuestring;
                    char ack_msg[256];
                    
                    if (strcmp(cmd, "get_status") == 0) {
                        snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"get_status\",\"state\":\"ready\",\"version\":\"v1.2.1\"}");
                        ble_serial_log(ack_msg);
                    }
                    else if (strcmp(cmd, "set_wifi") == 0) {
                        cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
                        cJSON *pass = cJSON_GetObjectItem(root, "pass");
                        if (ssid && ssid->valuestring) {
                            nvs_config_set_str("wifi_ssid", ssid->valuestring);
                            if (pass && pass->valuestring) {
                                nvs_config_set_str("wifi_password", pass->valuestring);
                            } else {
                                nvs_config_set_str("wifi_password", "");
                            }
                            config_received = true;
                            snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"set_wifi\",\"status\":\"ok\"}");
                            ble_serial_log(ack_msg);
                        }
                    }
                    else if (strcmp(cmd, "set_config") == 0) {
                        cJSON *dev_id = cJSON_GetObjectItem(root, "device_id");
                        cJSON *server_url = cJSON_GetObjectItem(root, "server_url");
                        if (dev_id && dev_id->valuestring) nvs_config_set_str("device_id", dev_id->valuestring);
                        if (server_url && server_url->valuestring) nvs_config_set_str("server_url", server_url->valuestring);
                        snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"set_config\",\"status\":\"ok\"}");
                        ble_serial_log(ack_msg);
                    }
                    else if (strcmp(cmd, "provision") == 0) {
                        bool this_is_config = false;
                        bool this_is_cert = false;

                        cJSON *server_url = cJSON_GetObjectItem(root, "server_url");
                        if (server_url && server_url->valuestring) {
                            nvs_config_set_str("server_url", server_url->valuestring);
                            this_is_config = true;
                        }
                        cJSON *device_type = cJSON_GetObjectItem(root, "device_type");
                        if (device_type && device_type->valuestring) {
                            nvs_config_set_str("device_type", device_type->valuestring);
                            this_is_config = true;
                        }
                        cJSON *device_id = cJSON_GetObjectItem(root, "device_id");
                        if (device_id && device_id->valuestring) {
                            nvs_config_set_str("device_id", device_id->valuestring);
                            this_is_config = true;
                        }
                        cJSON *device_name = cJSON_GetObjectItem(root, "device_name");
                        if (device_name && device_name->valuestring) {
                            nvs_config_set_str("device_name", device_name->valuestring);
                            this_is_config = true;
                        }
                        cJSON *wifi_ssid = cJSON_GetObjectItem(root, "wifi_ssid");
                        if (!wifi_ssid) wifi_ssid = cJSON_GetObjectItem(root, "ssid");
                        if (wifi_ssid && wifi_ssid->valuestring) {
                            nvs_config_set_str("wifi_ssid", wifi_ssid->valuestring);
                            this_is_config = true;
                        }
                        cJSON *wifi_pass = cJSON_GetObjectItem(root, "wifi_password");
                        if (!wifi_pass) wifi_pass = cJSON_GetObjectItem(root, "password");
                        if (!wifi_pass) wifi_pass = cJSON_GetObjectItem(root, "pass");
                        if (wifi_pass && wifi_pass->valuestring) {
                            nvs_config_set_str("wifi_password", wifi_pass->valuestring);
                            this_is_config = true;
                        }
                        cJSON *ssl_cert = cJSON_GetObjectItem(root, "ssl_cert");
                        if (!ssl_cert) ssl_cert = cJSON_GetObjectItem(root, "cert_pem");
                        if (ssl_cert && ssl_cert->valuestring) {
                            nvs_config_set_str("ssl_cert", ssl_cert->valuestring);
                            this_is_cert = true;
                        }
                        cJSON *port_item = cJSON_GetObjectItem(root, "control_port");
                        if (!port_item) port_item = cJSON_GetObjectItem(root, "port");
                        if (port_item) {
                            uint32_t port_val = 0;
                            if (port_item->type == cJSON_Number) {
                                port_val = port_item->valueint;
                            } else if (port_item->type == cJSON_String && port_item->valuestring) {
                                port_val = atoi(port_item->valuestring);
                            }
                            if (port_val > 0 && port_val <= 65535) {
                                nvs_config_set_u32("control_port", port_val);
                                this_is_config = true;
                            }
                        }

                        if (!this_is_config && !this_is_cert) {
                            ble_serial_log("[WARNING] JSON parsed but no valid config keys found");
                        }
                        
                        check_success_state(this_is_config, this_is_cert);

                        ESP_LOGI(TAG, "Explicit provision command received. Exiting BLE manager.");
                        if (!this_is_cert) { 
                            // Force sending PAYLOAD_RECEIVED for Scenario B so the Mobile App disconnects
                            ble_serial_log("[ACK] PAYLOAD_RECEIVED");
                            vTaskDelay(pdMS_TO_TICKS(500));
                        }
                        if (ble_prov_event_group) {
                            xEventGroupSetBits(ble_prov_event_group, BLE_PROV_SUCCESS_BIT);
                        }
                    }
                    else if (strcmp(cmd, "set_cert") == 0) {
                        cJSON *chunk_idx = cJSON_GetObjectItem(root, "chunk");
                        cJSON *data = cJSON_GetObjectItem(root, "data");
                        if (chunk_idx && data && data->valuestring) {
                            // Fetch existing cert, append this chunk, save it back
                            char *existing_cert = malloc(2048);
                            if (existing_cert) {
                                if (chunk_idx->valueint == 1) {
                                    // First chunk, overwrite
                                    strcpy(existing_cert, data->valuestring);
                                } else {
                                    // Append chunk
                                    nvs_config_get_str("ssl_cert", existing_cert, 2048);
                                    strncat(existing_cert, data->valuestring, 2048 - strlen(existing_cert) - 1);
                                }
                                nvs_config_set_str("ssl_cert", existing_cert);
                                free(existing_cert);
                                cert_received = true;
                                snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"set_cert\",\"chunk\":%d,\"status\":\"ok\"}", chunk_idx->valueint);
                                ble_serial_log(ack_msg);
                            }
                        }
                    }
                    else if (strcmp(cmd, "commit") == 0) {
                        snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"commit\",\"status\":\"rebooting\"}");
                        ble_serial_log(ack_msg);
                        vTaskDelay(pdMS_TO_TICKS(500));
                        if (ble_prov_event_group) {
                            xEventGroupSetBits(ble_prov_event_group, BLE_PROV_SUCCESS_BIT);
                        }
                    }
                    else if (strcmp(cmd, "control") == 0) {
                        cJSON *action = cJSON_GetObjectItem(root, "action");
                        if (action && action->valuestring) {
                            if (strcmp(action->valuestring, "toggle") == 0) {
                                relay_service_handle(NULL, 0, RELAY_TOGGLE, 0, 0, "BLE");
                            } else if (strcmp(action->valuestring, "on") == 0) {
                                relay_service_handle(NULL, 0, RELAY_ON, 0, 0, "BLE");
                            } else if (strcmp(action->valuestring, "off") == 0) {
                                relay_service_handle(NULL, 0, RELAY_OFF, 0, 0, "BLE");
                            } else if (strcmp(action->valuestring, "scan_wifi") == 0) {
                                if (!s_is_scanning) {
                                    s_is_scanning = true;
                                    ble_notify_status("{\"status\": \"scanning\", \"message\": \"Please wait...\"}");
                                    xTaskCreate(wifi_scan_task, "ble_scan_task", 4096, NULL, 5, NULL);
                                }
                            }
                            snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"control\",\"status\":\"success\"}");
                            ble_serial_log(ack_msg);
                        }
                    }
                    else if (strcmp(cmd, "scan") == 0) {
                        cJSON *action = cJSON_GetObjectItem(root, "action");
                        if (action && action->valuestring && strcmp(action->valuestring, "scan_wifi") == 0) {
                            // Always ACK first to satisfy the app's command-response queue!
                            snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"scan\",\"status\":\"success\"}");
                            ble_serial_log(ack_msg);
                            vTaskDelay(pdMS_TO_TICKS(100));
                            
                            if (!s_is_scanning) {
                                s_is_scanning = true;
                                ble_notify_status("{\"status\": \"scanning\", \"message\": \"Please wait...\"}");
                                xTaskCreate(wifi_scan_task, "ble_scan_task", 4096, NULL, 5, NULL);
                            } else {
                                ble_notify_status("{\"status\": \"scanning\", \"message\": \"Please wait...\"}");
                            }
                        }
                    }
                } else {
                    // Fallback for legacy explicit "action" format (without "cmd")
                    cJSON *action_item = cJSON_GetObjectItem(root, "action");
                    if (action_item && action_item->valuestring) {
                        if (strcmp(action_item->valuestring, "toggle") == 0) {
                            relay_service_handle(NULL, 0, RELAY_TOGGLE, 0, 0, "BLE");
                        } else if (strcmp(action_item->valuestring, "scan_wifi") == 0) {
                            // Always ACK first to satisfy the app's command-response queue!
                            ble_serial_log("{\"ack\":\"scan\",\"status\":\"success\"}");
                            vTaskDelay(pdMS_TO_TICKS(100));
                            
                            if (!s_is_scanning) {
                                s_is_scanning = true;
                                ble_notify_status("{\"status\": \"scanning\"}");
                                xTaskCreate(wifi_scan_task, "ble_scan_task", 4096, NULL, 5, NULL);
                            } else {
                                ble_notify_status("{\"status\": \"scanning\"}");
                            }
                        }
                    }
                }
                
                cJSON_Delete(root);
                rx_accumulation_len = 0;
                memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));
            } else {
                // Not a complete JSON yet, or malformed. Wait for more chunks.
                ESP_LOGD(TAG, "JSON parse failed. Waiting for more chunks to complete the command.");
            }
        } else {
            // Buffer contains data but doesn't start with WIFI: or '{'
            if (rx_accumulation_len > 10 && rx_accumulation_buffer[0] != '{' && strncmp(rx_accumulation_buffer, "WIFI:", 5) != 0) {
                ble_serial_log("[ERROR] Invalid command prefix. Resetting buffer.");
                rx_accumulation_len = 0;
                memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));
            }
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
                rx_accumulation_len = 0;
                memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));
                config_received = false;
                cert_received = false;
                ble_serial_log("[SYSTEM] BLE Client Connected");
            }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            conn_handle = BLE_HS_CONN_HANDLE_NONE;
            rx_accumulation_len = 0;
            memset(rx_accumulation_buffer, 0, sizeof(rx_accumulation_buffer));
            config_received = false;
            cert_received = false;
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
    bool has_id = nvs_config_get_device_id(dev_id, sizeof(dev_id)) && strlen(dev_id) > 0;
    
    // Determine the prefix based on whether Wi-Fi credentials exist (indicating a fallback instead of initial setup)
    char temp_ssid[64] = {0};
    bool has_ssid = nvs_config_get_wifi_ssid(temp_ssid, sizeof(temp_ssid));
    
    if (has_id && has_ssid) {
        snprintf(adv_name, sizeof(adv_name), "YKP_WFC_%s", dev_id);
    } else if (has_id) {
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
    if (s_ble_active) return true;
    ESP_LOGI(TAG, "Starting NimBLE custom BLE provisioning...");
    
    if (!ble_prov_event_group) {
        ble_prov_event_group = xEventGroupCreate();
    }
    xEventGroupClearBits(ble_prov_event_group, BLE_PROV_SUCCESS_BIT);

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to init NimBLE port: %d", err);
        if (ble_prov_event_group) {
            vEventGroupDelete(ble_prov_event_group);
            ble_prov_event_group = NULL;
        }
        return false;
    }
    
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
    
    s_ble_active = true;
    nimble_port_freertos_init(ble_host_task);
    return true;
}

bool ykp_ble_provision_stop(void) {
    if (!s_ble_active) return true;
    ESP_LOGI(TAG, "Stopping NimBLE...");
    int rc = nimble_port_stop();
    if (rc == 0) {
        nimble_port_deinit();
        s_ble_active = false;
        conn_handle = BLE_HS_CONN_HANDLE_NONE;
        return true;
    }
    return false;
}

bool ykp_ble_is_active(void) {
    return s_ble_active;
}

bool ykp_ble_is_connected(void) {
    return (s_ble_active && conn_handle != BLE_HS_CONN_HANDLE_NONE);
}

void ykp_ble_provision_wait(void) {
    if (ble_prov_event_group) {
        xEventGroupWaitBits(ble_prov_event_group, BLE_PROV_SUCCESS_BIT, pdTRUE, pdFALSE, portMAX_DELAY);
    }
}

bool ykp_ble_provision_is_success(void) {
    if (ble_prov_event_group) {
        EventBits_t bits = xEventGroupGetBits(ble_prov_event_group);
        return (bits & BLE_PROV_SUCCESS_BIT) != 0;
    }
    return false;
}
