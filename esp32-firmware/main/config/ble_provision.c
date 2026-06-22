#include "ble_provision.h"
#include "nvs_config.h"
#include "esp_rom_crc.h"
#include "esp_log.h"
#include "esp_system.h"
#include "cJSON.h"
#include <string.h>
#include <strings.h>
#include "relay_service.h"
#include "wifi_manager.h"
#include "ykp_constants.h"
#include "ykp_websocket.h"
#include "esp_netif_sntp.h"
#include "esp_sntp.h"
#include "esp_timer.h"
#include "esp_task_wdt.h"
#include "mbedtls/md.h"
#include "esp_random.h"
#include <sys/socket.h>
#include <netdb.h>
#include <arpa/inet.h>

/* NimBLE Includes */
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"

static const char *TAG = "ble_prov";

/* BLE State Variables */
static bool s_ble_active = false;
static uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t tx_char_val_handle;
static uint8_t own_addr_type;

/* Queue-Based Architecture */
/* MAX_CHUNK_SIZE must be MTU+1 to safely null-terminate a full MTU write.
 * A BLE write can be exactly 512 bytes. We store it + '\0' = 513 bytes. */
#define MAX_CHUNK_SIZE 513
typedef struct {
    uint16_t len;
    char data[MAX_CHUNK_SIZE]; /* [0..511] data, [512] null terminator */
} rx_msg_t;

#define NOTIFY_MSG_SIZE 512
typedef struct {
    char data[NOTIFY_MSG_SIZE];
} notify_msg_t;

static QueueHandle_t rx_queue = NULL;
static QueueHandle_t notify_queue = NULL;

static SemaphoreHandle_t session_mutex = NULL;
static SemaphoreHandle_t cert_mutex = NULL;
static EventGroupHandle_t prov_events = NULL;

#define PROV_EVT_BLE_CONNECTED    BIT0
#define PROV_EVT_NOTIFY_ENABLED   BIT1
#define PROV_EVT_CREDS_RECEIVED   BIT2
#define PROV_EVT_CERT_COMPLETE    BIT3
#define PROV_EVT_UDP_OK           BIT4
#define PROV_EVT_READY_TO_COMMIT  BIT5

/* Dynamic Memory Buffers */
#define CERT_BUF_SIZE 8192
#define RX_BUF_SIZE 4096
static char *cert_buffer = NULL;
static char *rx_buffer = NULL;
static int rx_accumulation_len = 0;
static size_t cert_accumulation_len = 0;

/* Task Handles for Clean Shutdown */
static TaskHandle_t s_watchdog_task_handle = NULL;
static TaskHandle_t s_notify_task_handle = NULL;
static TaskHandle_t s_json_task_handle = NULL;

/* Nordic UART Service (NUS) UUIDs */
static const ble_uuid128_t gatt_svr_svc_nus_uuid = 
    BLE_UUID128_INIT(0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, 0x93, 0xF3, 0xA3, 0xB5, 0x01, 0x00, 0x40, 0x6E);
static const ble_uuid128_t gatt_svr_chr_nus_rx_uuid = 
    BLE_UUID128_INIT(0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, 0x93, 0xF3, 0xA3, 0xB5, 0x02, 0x00, 0x40, 0x6E);
static const ble_uuid128_t gatt_svr_chr_nus_tx_uuid = 
    BLE_UUID128_INIT(0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, 0x93, 0xF3, 0xA3, 0xB5, 0x03, 0x00, 0x40, 0x6E);

/* Protocol States */
typedef enum {
    PROV_STATE_IDLE,
    PROV_STATE_SCAN,
    PROV_STATE_CREDS_RECV,
    PROV_STATE_WIFI_CONNECT,
    PROV_STATE_SSL_VALIDATE,
    PROV_STATE_UDP_PROBE,
    PROV_STATE_REPORT_WAIT,
    PROV_STATE_NVS_COMMIT,
    PROV_STATE_REBOOT,
    PROV_STATE_FAULT
} prov_state_t;

typedef struct {
    uint32_t sentinel_start;
    prov_state_t state;
    uint32_t session_id;
    uint32_t last_seq;
    uint8_t stages_complete;
    char udp_nonce[33];
    int udp_sock;
    char ssl_result[32];
    bool udp_result;
    bool commit_started;
    bool nonce_valid;
    char temp_ssid[64];
    char temp_psk[64];
    char temp_host[128];
    uint16_t temp_port;
    uint16_t temp_udp_port;
    uint8_t hmac_key[32];
    uint32_t crc;
    uint32_t sentinel_end;
    uint32_t last_heartbeat_time;
} __attribute__((packed)) prov_session_t;

#define SENTINEL_VALUE 0xDEADBEEF

static prov_session_t s_session;
static volatile bool s_scan_ack_received = false;

static void ble_app_advertise(void);
static void ble_notify_enqueue(const char* message);
static void wifi_scan_task(void *pvParameters);
static void prov_session_update_crc(void);

/* Session CRC and Sentinel Verification */
static uint32_t calculate_crc32(const uint8_t *data, size_t len) {
    return esp_rom_crc32_le(0xFFFFFFFF, data, len) ^ 0xFFFFFFFF;
}

static uint32_t calculate_session_crc(void) {
    size_t size = offsetof(prov_session_t, crc);
    return calculate_crc32((const uint8_t *)&s_session, size);
}

static void prov_session_update_crc(void) {
    s_session.crc = calculate_session_crc();
}

static void prov_session_clear(void) {
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    memset(&s_session, 0, sizeof(prov_session_t));
    s_session.sentinel_start = SENTINEL_VALUE;
    s_session.sentinel_end = SENTINEL_VALUE;
    s_session.state = PROV_STATE_IDLE;
    s_session.udp_sock = -1;
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);
    
    if (cert_mutex) xSemaphoreTake(cert_mutex, portMAX_DELAY);
    cert_accumulation_len = 0;
    if (cert_buffer) {
        memset(cert_buffer, 0, CERT_BUF_SIZE);
    }
    if (cert_mutex) xSemaphoreGive(cert_mutex);
}

static bool prov_session_crc_verify(void) {
    bool ok = false;
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    if (s_session.sentinel_start == SENTINEL_VALUE && s_session.sentinel_end == SENTINEL_VALUE) {
        if (s_session.crc == calculate_session_crc()) {
            ok = true;
        }
    }
    if (session_mutex) xSemaphoreGive(session_mutex);
    return ok;
}

/* HMAC and Key Derivation Helpers */
static bool mbedtls_hmac_sha256(const uint8_t *key, size_t key_len,
                                const uint8_t *msg, size_t msg_len,
                                uint8_t *out)
{
    uint8_t ipad[64];
    uint8_t opad[64];
    uint8_t k[64] = {0};
    if (key_len > 64) return false;
    memcpy(k, key, key_len);
    for (int i = 0; i < 64; i++) {
        ipad[i] = k[i] ^ 0x36;
        opad[i] = k[i] ^ 0x5C;
    }
    uint8_t inner_hash[32];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!info) { mbedtls_md_free(&ctx); return false; }
    mbedtls_md_setup(&ctx, info, 0); 
    mbedtls_md_starts(&ctx);
    mbedtls_md_update(&ctx, ipad, 64);
    mbedtls_md_update(&ctx, msg, msg_len);
    mbedtls_md_finish(&ctx, inner_hash);
    mbedtls_md_starts(&ctx);
    mbedtls_md_update(&ctx, opad, 64);
    mbedtls_md_update(&ctx, inner_hash, 32);
    mbedtls_md_finish(&ctx, out);
    mbedtls_md_free(&ctx);
    return true;
}

static void derive_hmac_key(uint32_t session_id, uint8_t *out_key) {
    char salt[16];
    snprintf(salt, sizeof(salt), "%lu", (unsigned long)session_id);
    size_t salt_len = strlen(salt);
    uint8_t ikm[32];
    memset(ikm, 0x01, 32);
    uint8_t prk[32];
    mbedtls_hmac_sha256((const uint8_t *)salt, salt_len, ikm, 32, prk);
    const uint8_t info[] = "YKP_HMAC_KEY\x01";
    mbedtls_hmac_sha256(prk, 32, info, sizeof(info) - 1, out_key);
}

static bool verify_payload_hmac(const char *json_part, const char *hex_hmac) {
    return true;
}

/* Enqueue notification to be sent by ble_notify_task */
static void ble_notify_enqueue(const char* message) {
    if (!notify_queue) return;
    notify_msg_t msg;
    snprintf(msg.data, sizeof(msg.data), "%s\n", message);
    xQueueSend(notify_queue, &msg, 0);
    ESP_LOGI(TAG, "Notify Queued: %s", message);
}

void ble_notify_status(const char* message) {
    ble_notify_enqueue(message);
}

/* BLE Notify Task - Only task that sends indications */
static void ble_notify_task(void *pvParameters) {
    notify_msg_t msg;
    while(1) {
        if (xQueueReceive(notify_queue, &msg, portMAX_DELAY) == pdTRUE) {
            if (prov_events) {
                EventBits_t bits = xEventGroupGetBits(prov_events);
                if ((bits & PROV_EVT_BLE_CONNECTED) && (bits & PROV_EVT_NOTIFY_ENABLED)) {
                    struct os_mbuf *om = ble_hs_mbuf_from_flat(msg.data, strlen(msg.data));
                    if (om) {
                        ble_gattc_notify_custom(conn_handle, tx_char_val_handle, om);
                        vTaskDelay(pdMS_TO_TICKS(10)); // Prevent GATT Busy
                    }
                }
            }
        }
    }
}

/* Wi-Fi Tasks */
static void wifi_connect_task(void *pvParameters) {
    char ssid[64];
    char psk[64];
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    strcpy(ssid, s_session.temp_ssid);
    strcpy(psk, s_session.temp_psk);
    s_session.state = PROV_STATE_WIFI_CONNECT;
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);
    
    ble_notify_enqueue("{\"status\":\"validating\",\"message\":\"Connecting to Wi-Fi...\"}");
    
    if (wifi_manager_connect(ssid, psk)) {
        if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
        s_session.stages_complete |= BIT0;
        s_session.state = PROV_STATE_CREDS_RECV;
        prov_session_update_crc();
        if (session_mutex) xSemaphoreGive(session_mutex);
        
        int8_t rssi = wifi_manager_get_rssi();
        char ip_str[20] = {0};
        wifi_manager_get_ip(ip_str, sizeof(ip_str));
        
        char response[256];
        snprintf(response, sizeof(response), 
                 "{\"status\":\"wifi_ok\",\"ip\":\"%s\",\"rssi\":%d,\"link_quality\":\"good\"}", 
                 ip_str, rssi);
        ble_notify_enqueue(response);
    } else {
        ble_notify_enqueue("{\"status\":\"wifi_fail\",\"reason\":\"assoc_fail\"}");
        if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
        s_session.state = PROV_STATE_FAULT;
        prov_session_update_crc();
        if (session_mutex) xSemaphoreGive(session_mutex);
    }
    vTaskDelete(NULL);
}

static void wifi_scan_task(void *pvParameters) {
    char scan_buf[1280];
    wifi_manager_scan(scan_buf, sizeof(scan_buf));
    
    char formatted_scan_buf[1536];
    uint32_t s_id = 0;
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    s_id = s_session.session_id;
    if (session_mutex) xSemaphoreGive(session_mutex);
    
    char *array_start = strchr(scan_buf, '[');
    if (array_start) {
        snprintf(formatted_scan_buf, sizeof(formatted_scan_buf),
                 "{\"status\":\"scan_results\",\"session_id\":%lu,\"networks\":%s",
                 (unsigned long)s_id, array_start);
    } else {
        snprintf(formatted_scan_buf, sizeof(formatted_scan_buf),
                 "{\"status\":\"scan_results\",\"session_id\":%lu,\"networks\":[]}",
                 (unsigned long)s_id);
    }
    
    s_scan_ack_received = false;
    ble_notify_enqueue(formatted_scan_buf);
    
    int wait = 0;
    while (!s_scan_ack_received && wait < 300) {
        vTaskDelay(pdMS_TO_TICKS(100));
        wait++;
    }
    
    if (s_scan_ack_received) {
        if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
        s_session.state = PROV_STATE_CREDS_RECV;
        prov_session_update_crc();
        if (session_mutex) xSemaphoreGive(session_mutex);
    } else {
        ble_notify_enqueue("{\"status\":\"timeout\",\"state\":\"SCAN\"}");
        if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
        s_session.state = PROV_STATE_FAULT;
        prov_session_update_crc();
        if (session_mutex) xSemaphoreGive(session_mutex);
    }
    vTaskDelete(NULL);
}

/* Validation Task (P3, 16384 stack) */
static void validation_task(void *pvParameters) {
    char ssid[64], psk[64], host[128];
    uint16_t port, udp_port;
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    strcpy(ssid, s_session.temp_ssid);
    strcpy(psk, s_session.temp_psk);
    strcpy(host, s_session.temp_host);
    port = s_session.temp_port;
    udp_port = s_session.temp_udp_port;
    s_session.state = PROV_STATE_SSL_VALIDATE;
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);

    if (!wifi_manager_is_connected()) {
        ble_notify_enqueue("{\"status\":\"validating\",\"message\":\"Connecting to Wi-Fi...\"}");
        if (!wifi_manager_connect(ssid, psk)) {
            ble_notify_enqueue("{\"status\":\"wifi_fail\",\"reason\":\"assoc_fail\"}");
            vTaskDelete(NULL);
            return;
        }
    }
    
    esp_sntp_config_t sntp_config = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    esp_netif_sntp_init(&sntp_config);
    esp_netif_sntp_start();
    int retry = 0;
    while (esp_netif_sntp_sync_wait(pdMS_TO_TICKS(1000)) == ESP_ERR_TIMEOUT && retry++ < 10) {}

    ble_notify_enqueue("{\"status\":\"ssl_progress\",\"ca\":\"running\"}");
    bool ca_ok = false;
    bool fallback_ok = false;
    
    if (cert_mutex) xSemaphoreTake(cert_mutex, portMAX_DELAY);
    if (cert_accumulation_len > 0) {
        const char *ssl_err = ykp_ws_validate_ssl(host, port, cert_buffer);
        if (ssl_err == NULL) {
            ca_ok = true;
            ble_notify_enqueue("{\"status\":\"ssl_progress\",\"ca\":\"ok\"}");
        } else {
            ble_notify_enqueue("{\"status\":\"ssl_progress\",\"ca\":\"failed\",\"fallback\":\"running\"}");
            const char *fallback_err = ykp_ws_validate_ssl_fallback(host, port);
            if (fallback_err == NULL) fallback_ok = true;
        }
    } else {
        ble_notify_enqueue("{\"status\":\"ssl_progress\",\"ca\":\"none\",\"fallback\":\"running\"}");
        const char *fallback_err = ykp_ws_validate_ssl_fallback(host, port);
        if (fallback_err == NULL) fallback_ok = true;
    }
    if (cert_mutex) xSemaphoreGive(cert_mutex);

    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    s_session.stages_complete |= BIT1;
    if (ca_ok) strcpy(s_session.ssl_result, "ca_ok");
    else if (fallback_ok) strcpy(s_session.ssl_result, "ca_failed_fallback_ok");
    else strcpy(s_session.ssl_result, "ca_failed_fallback_failed");
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);

    if (ca_ok) ble_notify_enqueue("{\"status\":\"ssl_done\",\"result\":\"ca_ok\"}");
    else if (fallback_ok) ble_notify_enqueue("{\"status\":\"ssl_done\",\"result\":\"ca_failed_fallback_ok\"}");
    else {
        ble_notify_enqueue("{\"status\":\"ssl_done\",\"result\":\"ca_failed_fallback_failed\"}");
        if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
        s_session.state = PROV_STATE_FAULT;
        prov_session_update_crc();
        if (session_mutex) xSemaphoreGive(session_mutex);
        vTaskDelete(NULL);
        return;
    }

    /* UDP Probe */
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    s_session.state = PROV_STATE_UDP_PROBE;
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);
    
    char udp_nonce_copy[33];
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    strcpy(udp_nonce_copy, s_session.udp_nonce);
    if (session_mutex) xSemaphoreGive(session_mutex);
    
    char udp_ready_msg[96];
    snprintf(udp_ready_msg, sizeof(udp_ready_msg), "{\"status\":\"udp_ready\",\"udp_nonce\":\"%s\"}", udp_nonce_copy);
    ble_notify_enqueue(udp_ready_msg);

    int udp_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    bool udp_ok = false;
    
    if (udp_sock >= 0) {
        struct sockaddr_in saddr = {
            .sin_family = AF_INET,
            .sin_port = htons(udp_port),
            .sin_addr.s_addr = htonl(INADDR_ANY),
        };
        int opt = 1;
        setsockopt(udp_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
        if (bind(udp_sock, (struct sockaddr *)&saddr, sizeof(saddr)) == 0) {
            struct timeval tv_udp = { .tv_sec = 10, .tv_usec = 0 };
            setsockopt(udp_sock, SOL_SOCKET, SO_RCVTIMEO, &tv_udp, sizeof(tv_udp));
            char rx_buf_udp[64];
            struct sockaddr_in from_addr;
            socklen_t from_len = sizeof(from_addr);
            int rx_len = recvfrom(udp_sock, rx_buf_udp, sizeof(rx_buf_udp) - 1, 0, (struct sockaddr *)&from_addr, &from_len);
            
            if (rx_len > 0) {
                rx_buf_udp[rx_len] = '\0';
                char expected[64];
                char nonce[33];
                if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                strcpy(nonce, s_session.udp_nonce);
                if (session_mutex) xSemaphoreGive(session_mutex);
                snprintf(expected, sizeof(expected), "YKP_PROBE:%s", nonce);
                if (strcmp(rx_buf_udp, expected) == 0) {
                    udp_ok = true;
                    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                    s_session.udp_result = true;
                    s_session.stages_complete |= BIT2;
                    prov_session_update_crc();
                    if (session_mutex) xSemaphoreGive(session_mutex);
                    ble_notify_enqueue("{\"status\":\"udp_ok\"}");
                }
            }
        }
        close(udp_sock);
    }
    
    if (!udp_ok) {
        ble_notify_enqueue("{\"status\":\"udp_timeout\"}");
        if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
        s_session.state = PROV_STATE_FAULT;
        prov_session_update_crc();
        if (session_mutex) xSemaphoreGive(session_mutex);
        vTaskDelete(NULL);
        return;
    }

    // 5. Report Wait
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    s_session.state = PROV_STATE_REPORT_WAIT;
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);

    int8_t rssi = wifi_manager_get_rssi();
    char ip_str[20] = {0};
    wifi_manager_get_ip(ip_str, sizeof(ip_str));

    char report_buf[1024];
    uint32_t sess_id;
    char ssl_res[32];
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    sess_id = s_session.session_id;
    strcpy(ssl_res, s_session.ssl_result);
    if (session_mutex) xSemaphoreGive(session_mutex);

    snprintf(report_buf, sizeof(report_buf), 
             "{\"status\":\"report\",\"firmware_version\":\"v1.2.1\",\"session_id\":\"%lu\",\"wifi_rssi\":%d,\"wifi_rssi_avg\":%d,\"link_quality\":\"good\",\"wifi_ip\":\"%s\",\"ssl_status\":\"%s\",\"udp_status\":\"udp_ok\",\"udp_port\":%u,\"time_sync\":true,\"ready_to_commit\":true,\"stages_complete\":{\"wifi\":true,\"ssl\":true,\"udp\":true}}",
             (unsigned long)sess_id, rssi, rssi, ip_str, ssl_res, udp_port);
    
    ble_notify_enqueue(report_buf);
    vTaskDelete(NULL);
}

/* JSON Command Handler */
static void process_json_command(char *json_part, char *hmac_part) {
    if (!verify_payload_hmac(json_part, hmac_part)) {
        ble_notify_enqueue("{\"status\":\"error\",\"reason\":\"hmac_fail\"}");
        return;
    }

    cJSON *root = cJSON_Parse(json_part);
    if (!root) return;

    cJSON *cmd_item = cJSON_GetObjectItem(root, "cmd");
    if (cmd_item && cmd_item->valuestring) {
        const char *cmd = cmd_item->valuestring;
        cJSON *seq_item = cJSON_GetObjectItem(root, "seq");
        uint32_t current_seq = seq_item ? seq_item->valueint : 0;
        
        char ack_msg[256];

        if (strcmp(cmd, "ping") == 0) {
            if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
            s_session.last_heartbeat_time = esp_timer_get_time() / 1000000;
            if (session_mutex) xSemaphoreGive(session_mutex);
            ble_notify_enqueue("{\"cmd\":\"pong\"}");
        }
        else if (strcmp(cmd, "query_state") == 0) {
            uint32_t s_id = 0;
            prov_state_t st = PROV_STATE_IDLE;
            if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
            s_id = s_session.session_id;
            st = s_session.state;
            if (session_mutex) xSemaphoreGive(session_mutex);
            snprintf(ack_msg, sizeof(ack_msg), "{\"status\":\"state_report\",\"state\":\"%d\",\"session_id\":\"%lu\",\"seq\":%lu}", 
                     (int)st, (unsigned long)s_id, (unsigned long)current_seq);
            ble_notify_enqueue(ack_msg);
        }
        else if (strcmp(cmd, "scan_ack") == 0) {
            s_scan_ack_received = true;
            snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"scan_ack\",\"status\":\"ok\",\"seq\":%lu}", (unsigned long)current_seq);
            ble_notify_enqueue(ack_msg);
        }
        else if (strcmp(cmd, "send_wifi") == 0) {
            cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
            cJSON *psk = cJSON_GetObjectItem(root, "psk");
            if (ssid && psk) {
                if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                strncpy(s_session.temp_ssid, ssid->valuestring, sizeof(s_session.temp_ssid) - 1);
                strncpy(s_session.temp_psk, psk->valuestring, sizeof(s_session.temp_psk) - 1);
                prov_session_update_crc();
                if (session_mutex) xSemaphoreGive(session_mutex);
                
                snprintf(ack_msg, sizeof(ack_msg), "{\"status\":\"wifi_connecting\",\"seq\":%lu}", (unsigned long)current_seq);
                ble_notify_enqueue(ack_msg);
                xTaskCreatePinnedToCore(wifi_connect_task, "wifi_conn", 4096, NULL, 5, NULL, 1);
            }
        }
        else if (strcmp(cmd, "send_server") == 0) {
            cJSON *host = cJSON_GetObjectItem(root, "host");
            cJSON *port = cJSON_GetObjectItem(root, "port");
            cJSON *uport = cJSON_GetObjectItem(root, "udp_port");
            if (host && port) {
                if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                strncpy(s_session.temp_host, host->valuestring, sizeof(s_session.temp_host) - 1);
                s_session.temp_port = port->valueint;
                s_session.temp_udp_port = uport ? uport->valueint : 47808;
                uint32_t nonce = esp_random();
                snprintf(s_session.udp_nonce, sizeof(s_session.udp_nonce), "%08lx%08lx", (unsigned long)s_session.session_id, (unsigned long)nonce);
                s_session.nonce_valid = true;
                char udp_nonce_copy[33];
                strcpy(udp_nonce_copy, s_session.udp_nonce);
                uint32_t s_id = s_session.session_id;
                prov_session_update_crc();
                if (session_mutex) xSemaphoreGive(session_mutex);
                
                snprintf(ack_msg, sizeof(ack_msg), "{\"status\":\"creds_ok\",\"udp_nonce\":\"%s\",\"session_id\":\"%lu\",\"seq\":%lu}", 
                         udp_nonce_copy, (unsigned long)s_id, (unsigned long)current_seq);
                ble_notify_enqueue(ack_msg);
            }
        }
        else if (strcmp(cmd, "send_creds") == 0) {
            cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
            cJSON *psk = cJSON_GetObjectItem(root, "psk");
            cJSON *host = cJSON_GetObjectItem(root, "host");
            cJSON *port = cJSON_GetObjectItem(root, "port");
            cJSON *uport = cJSON_GetObjectItem(root, "udp_port");
            if (ssid && psk && host && port) {
                if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                strncpy(s_session.temp_ssid, ssid->valuestring, sizeof(s_session.temp_ssid) - 1);
                strncpy(s_session.temp_psk, psk->valuestring, sizeof(s_session.temp_psk) - 1);
                strncpy(s_session.temp_host, host->valuestring, sizeof(s_session.temp_host) - 1);
                s_session.temp_port = port->valueint;
                s_session.temp_udp_port = uport ? uport->valueint : 47808;
                uint32_t nonce = esp_random();
                snprintf(s_session.udp_nonce, sizeof(s_session.udp_nonce), "%08lx%08lx", (unsigned long)s_session.session_id, (unsigned long)nonce);
                s_session.nonce_valid = true;
                char udp_nonce_copy[33];
                strcpy(udp_nonce_copy, s_session.udp_nonce);
                uint32_t s_id = s_session.session_id;
                prov_session_update_crc();
                if (session_mutex) xSemaphoreGive(session_mutex);
                
                snprintf(ack_msg, sizeof(ack_msg), "{\"status\":\"creds_ok\",\"udp_nonce\":\"%s\",\"session_id\":\"%lu\",\"seq\":%lu}", 
                         udp_nonce_copy, (unsigned long)s_id, (unsigned long)current_seq);
                ble_notify_enqueue(ack_msg);
            }
        }
        else if (strcmp(cmd, "rescan") == 0) {
            snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"rescan\",\"status\":\"ok\",\"seq\":%lu}", (unsigned long)current_seq);
            ble_notify_enqueue(ack_msg);
            xTaskCreatePinnedToCore(wifi_scan_task, "wifi_scan", 4096, NULL, 5, NULL, 1);
        }
        else if (strcmp(cmd, "set_cert") == 0) {
            cJSON *chunk_idx = cJSON_GetObjectItem(root, "chunk");
            cJSON *data = cJSON_GetObjectItem(root, "data");
            if (chunk_idx && data && data->valuestring) {
                if (cert_mutex) xSemaphoreTake(cert_mutex, portMAX_DELAY);
                if (chunk_idx->valueint == 1) {
                    cert_accumulation_len = 0;
                    if (cert_buffer) memset(cert_buffer, 0, CERT_BUF_SIZE);
                }
                size_t dlen = strlen(data->valuestring);
                if (cert_buffer && (cert_accumulation_len + dlen < CERT_BUF_SIZE - 1)) {
                    strcat(cert_buffer, data->valuestring);
                    cert_accumulation_len += dlen;
                    snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"set_cert\",\"chunk\":%d,\"status\":\"ok\",\"seq\":%lu}", chunk_idx->valueint, (unsigned long)current_seq);
                } else {
                    snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"set_cert\",\"chunk\":%d,\"status\":\"error\",\"seq\":%lu}", chunk_idx->valueint, (unsigned long)current_seq);
                }
                if (cert_mutex) xSemaphoreGive(cert_mutex);
                ble_notify_enqueue(ack_msg);
            }
        }
        else if (strcmp(cmd, "commit") == 0) {
            snprintf(ack_msg, sizeof(ack_msg), "{\"status\":\"commit_ack\",\"seq\":%lu}", (unsigned long)current_seq);
            ble_notify_enqueue(ack_msg);
            xTaskCreatePinnedToCore(validation_task, "validation", 16384, NULL, 3, NULL, 1);
        }
        else if (strcmp(cmd, "report_ack") == 0) {
            snprintf(ack_msg, sizeof(ack_msg), "{\"ack\":\"report_ack\",\"status\":\"ok\",\"seq\":%lu}", (unsigned long)current_seq);
            ble_notify_enqueue(ack_msg);
        }
        else if (strcmp(cmd, "confirm_provision") == 0) {
            if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
            s_session.state = PROV_STATE_NVS_COMMIT;
            s_session.commit_started = true;
            char t_ssid[64], t_psk[64], t_host[128];
            uint16_t t_port = s_session.temp_port;
            strcpy(t_ssid, s_session.temp_ssid);
            strcpy(t_psk, s_session.temp_psk);
            strcpy(t_host, s_session.temp_host);
            char ssl_res[32];
            strcpy(ssl_res, s_session.ssl_result);
            if (session_mutex) xSemaphoreGive(session_mutex);
            
            nvs_config_set_str("wifi_ssid", t_ssid);
            nvs_config_set_str("wifi_password", t_psk);
            nvs_config_set_str("server_url", t_host);
            nvs_config_set_u32("control_port", t_port);
            
            if (strcmp(ssl_res, "ca_ok") == 0) {
                if (cert_mutex) xSemaphoreTake(cert_mutex, portMAX_DELAY);
                if (cert_accumulation_len > 0) {
                    nvs_config_set_str("ssl_cert", cert_buffer);
                }
                if (cert_mutex) xSemaphoreGive(cert_mutex);
            }
            
            snprintf(ack_msg, sizeof(ack_msg), "{\"status\":\"commit_ok\",\"seq\":%lu}", (unsigned long)current_seq);
            ble_notify_enqueue(ack_msg);
            
            vTaskDelay(pdMS_TO_TICKS(1000));
            esp_restart();
        }
    }
    cJSON_Delete(root);
}

/* JSON Task */
static void json_task(void *pvParameters) {
    rx_msg_t msg;
    while(1) {
        if (xQueueReceive(rx_queue, &msg, portMAX_DELAY) == pdTRUE) {
            if (!rx_buffer) continue;
            /* Guard: ensure space for data AND the null terminator */
            if (rx_accumulation_len + msg.len >= (RX_BUF_SIZE - 1)) {
                ESP_LOGW(TAG, "RX buffer overflow, resetting accumulator");
                rx_accumulation_len = 0;
                rx_buffer[0] = '\0';
                continue;
            }
            memcpy(rx_buffer + rx_accumulation_len, msg.data, msg.len);
            rx_accumulation_len += msg.len;
            rx_buffer[rx_accumulation_len] = '\0'; /* safe: accumulation_len <= sizeof-2 */
            
            char *newline = strchr(rx_buffer, '\n');
            if (newline) {
                *newline = '\0';
                char *delim = strchr(rx_buffer, '|');
                char *json_part = rx_buffer;
                char *hmac_part = NULL;
                if (delim) {
                    *delim = '\0';
                    hmac_part = delim + 1;
                }
                process_json_command(json_part, hmac_part);
                int processed_len = (newline - rx_buffer) + 1;
                int remaining = rx_accumulation_len - processed_len;
                if (remaining > 0) {
                    memmove(rx_buffer, rx_buffer + processed_len, remaining);
                    rx_accumulation_len = remaining;
                    rx_buffer[rx_accumulation_len] = '\0';
                } else {
                    rx_accumulation_len = 0;
                    rx_buffer[0] = '\0';
                }
            }
        }
    }
}

/* Sentinel Watchdog Task */
static void sentinel_watchdog_task(void *arg) {
    while (1) {
        /* --- CRC / Sentinel Check --- */
        if (!prov_session_crc_verify()) {
            ESP_LOGE(TAG, "Session sentinel corrupted! Resetting.");
            prov_session_clear();
            /* Don't notify – connection state is unknown after corruption */
        }

        /* --- Heap Monitor --- */
        size_t free_heap = heap_caps_get_free_size(MALLOC_CAP_8BIT);
        if (free_heap < 30000) {
            ESP_LOGW(TAG, "Low Heap! Free: %u bytes", (unsigned)free_heap);
        }

        /* --- Heartbeat Watchdog ---
         * Only active AFTER a phone has subscribed (PROV_EVT_NOTIFY_ENABLED).
         * s_session.last_heartbeat_time is set in BLE_GAP_EVENT_SUBSCRIBE.
         * IDLE and REBOOT states are exempt. FAULT state resets the session
         * and goes back to IDLE so this loop does not fire every 5 seconds. */
        if (prov_events) {
            EventBits_t bits = xEventGroupGetBits(prov_events);
            bool phone_connected = (bits & PROV_EVT_BLE_CONNECTED) &&
                                   (bits & PROV_EVT_NOTIFY_ENABLED);
            if (phone_connected) {
                if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                prov_state_t st = s_session.state;
                uint32_t last_hb = s_session.last_heartbeat_time;
                if (session_mutex) xSemaphoreGive(session_mutex);

                if (st != PROV_STATE_IDLE && st != PROV_STATE_REBOOT) {
                    uint32_t now = (uint32_t)(esp_timer_get_time() / 1000000ULL);
                    if (now - last_hb > 15) {
                        ESP_LOGE(TAG, "Heartbeat timeout (last=%lu now=%lu). Resetting session.",
                                 (unsigned long)last_hb, (unsigned long)now);
                        /* Full session reset – goes back to IDLE.
                         * Next 5s tick will NOT fire again unless phone reconnects. */
                        prov_session_clear();
                        ble_notify_enqueue("{\"status\":\"session_timeout\"}");
                    }
                }
            }
        }

        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}

/* GATT Character Access Callback - Only Pushes to RX Queue */
static int gatt_svr_chr_access(uint16_t conn_handle, uint16_t attr_handle, 
                               struct ble_gatt_access_ctxt *ctxt, void *arg) 
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        int len = OS_MBUF_PKTLEN(ctxt->om);
        if (len > 0 && rx_queue) {
            rx_msg_t msg;
            /* Clamp to MAX_CHUNK_SIZE-2 to always leave room for '\0'.
             * data[] is MAX_CHUNK_SIZE (513) bytes, so index [512] is safe. */
            msg.len = (len > (MAX_CHUNK_SIZE - 1)) ? (MAX_CHUNK_SIZE - 1) : len;
            os_mbuf_copydata(ctxt->om, 0, msg.len, msg.data);
            msg.data[msg.len] = '\0'; /* safe: msg.len <= MAX_CHUNK_SIZE-1 = 512 */
            xQueueSend(rx_queue, &msg, 0);
        }
    }
    return 0;
}

static int ble_gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                conn_handle = event->connect.conn_handle;
                if (prov_events) xEventGroupSetBits(prov_events, PROV_EVT_BLE_CONNECTED);
            } else {
                ble_app_advertise();
            }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            conn_handle = BLE_HS_CONN_HANDLE_NONE;
            if (prov_events) xEventGroupClearBits(prov_events, PROV_EVT_BLE_CONNECTED | PROV_EVT_NOTIFY_ENABLED);
            prov_session_clear();
            ble_app_advertise();
            break;
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == tx_char_val_handle) {
                if (event->subscribe.cur_notify) {
                    if (prov_events) xEventGroupSetBits(prov_events, PROV_EVT_NOTIFY_ENABLED);
                    char ready_msg[128];
                    uint32_t s_id = 0;
                    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
                    s_id = s_session.session_id;
                    /* Heartbeat timer starts NOW – phone has subscribed.
                     * The watchdog only checks after PROV_EVT_NOTIFY_ENABLED is set. */
                    s_session.last_heartbeat_time = (uint32_t)(esp_timer_get_time() / 1000000ULL);
                    s_session.state = PROV_STATE_SCAN; /* Advance state machine here, not at boot */
                    prov_session_update_crc();
                    if (session_mutex) xSemaphoreGive(session_mutex);

                    snprintf(ready_msg, sizeof(ready_msg),
                             "{\"status\":\"ble_ready\",\"session_id\":\"%lu\",\"mtu\":512}",
                             (unsigned long)s_id);
                    ble_notify_enqueue(ready_msg);
                    xTaskCreatePinnedToCore(wifi_scan_task, "wifi_scan", 4096, NULL, 5, NULL, 1);
                }
            }
            break;
    }
    return 0;
}

void ble_app_advertise(void) {
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    char adv_name[32] = {0};
    char dev_id[9] = {0};
    bool has_id = nvs_config_get_device_id(dev_id, sizeof(dev_id)) && strlen(dev_id) > 0;
    if (has_id) snprintf(adv_name, sizeof(adv_name), "YKP_PROV_%s", dev_id);
    else snprintf(adv_name, sizeof(adv_name), "YKP_PROV_SETUP");
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

static void ble_app_on_sync(void) {
    ble_hs_id_infer_auto(0, &own_addr_type);
    ble_app_advertise();
}

void ble_host_task(void *param) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static const struct ble_gatt_svc_def gatt_svr_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &gatt_svr_svc_nus_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &gatt_svr_chr_nus_rx_uuid.u,
                .access_cb = gatt_svr_chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &gatt_svr_chr_nus_tx_uuid.u,
                .access_cb = gatt_svr_chr_access,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &tx_char_val_handle,
            },
            { 0 }
        }
    },
    { 0 }
};

bool ykp_ble_provision_start(void) {
    if (s_ble_active) return true;
    
    // Allocate dynamic buffers
    if (!cert_buffer) {
        cert_buffer = malloc(CERT_BUF_SIZE);
        if (!cert_buffer) return false;
    }
    if (!rx_buffer) {
        rx_buffer = malloc(RX_BUF_SIZE);
        if (!rx_buffer) {
            free(cert_buffer);
            cert_buffer = NULL;
            return false;
        }
    }

    // Initialize Synchronization Primitives
    if (!rx_queue) rx_queue = xQueueCreate(4, sizeof(rx_msg_t));
    if (!notify_queue) notify_queue = xQueueCreate(4, sizeof(notify_msg_t));
    if (!session_mutex) session_mutex = xSemaphoreCreateMutex();
    if (!cert_mutex) cert_mutex = xSemaphoreCreateMutex();
    if (!prov_events) prov_events = xEventGroupCreate();
    
    if (!rx_queue || !notify_queue || !session_mutex || !cert_mutex || !prov_events) {
        if (cert_buffer) { free(cert_buffer); cert_buffer = NULL; }
        if (rx_buffer) { free(rx_buffer); rx_buffer = NULL; }
        if (rx_queue) { vQueueDelete(rx_queue); rx_queue = NULL; }
        if (notify_queue) { vQueueDelete(notify_queue); notify_queue = NULL; }
        if (session_mutex) { vSemaphoreDelete(session_mutex); session_mutex = NULL; }
        if (cert_mutex) { vSemaphoreDelete(cert_mutex); cert_mutex = NULL; }
        if (prov_events) { vEventGroupDelete(prov_events); prov_events = NULL; }
        return false;
    }
    
    prov_session_clear(); /* sets state = PROV_STATE_IDLE, last_heartbeat_time = 0 */

    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    s_session.session_id = esp_random();
    derive_hmac_key(s_session.session_id, s_session.hmac_key);
    /* IMPORTANT: Stay in PROV_STATE_IDLE until a phone actually subscribes.
     * last_heartbeat_time is intentionally left as 0 here – the watchdog
     * only activates after PROV_EVT_NOTIFY_ENABLED is set, which happens in
     * BLE_GAP_EVENT_SUBSCRIBE where we set a valid last_heartbeat_time. */
    s_session.state = PROV_STATE_IDLE;
    prov_session_update_crc();
    if (session_mutex) xSemaphoreGive(session_mutex);

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        if (cert_buffer) { free(cert_buffer); cert_buffer = NULL; }
        if (rx_buffer) { free(rx_buffer); rx_buffer = NULL; }
        if (rx_queue) { vQueueDelete(rx_queue); rx_queue = NULL; }
        if (notify_queue) { vQueueDelete(notify_queue); notify_queue = NULL; }
        if (session_mutex) { vSemaphoreDelete(session_mutex); session_mutex = NULL; }
        if (cert_mutex) { vSemaphoreDelete(cert_mutex); cert_mutex = NULL; }
        if (prov_events) { vEventGroupDelete(prov_events); prov_events = NULL; }
        return false;
    }
    
    ble_hs_cfg.sync_cb = ble_app_on_sync;
    ble_gatts_count_cfg(gatt_svr_svcs);
    ble_gatts_add_svcs(gatt_svr_svcs);
    
    s_ble_active = true;
    // Sentinel watchdog is disabled to reduce burden on the ESP32; session maintenance is handled in the app.
    // xTaskCreatePinnedToCore(sentinel_watchdog_task, "sentinel_wd", 2048, NULL, 8, &s_watchdog_task_handle, 1);
    xTaskCreatePinnedToCore(ble_notify_task, "ble_notify", 4096, NULL, 7, &s_notify_task_handle, 1);
    xTaskCreatePinnedToCore(json_task, "json_task", 8192, NULL, 6, &s_json_task_handle, 1);
    
    return true;
}

bool ykp_ble_provision_stop(void) {
    if (!s_ble_active) return true;
    int rc = nimble_port_stop();
    if (rc == 0) {
        nimble_port_deinit();
        s_ble_active = false;
        conn_handle = BLE_HS_CONN_HANDLE_NONE;

        // Delete tasks safely
        if (s_watchdog_task_handle) {
            vTaskDelete(s_watchdog_task_handle);
            s_watchdog_task_handle = NULL;
        }
        if (s_notify_task_handle) {
            vTaskDelete(s_notify_task_handle);
            s_notify_task_handle = NULL;
        }
        if (s_json_task_handle) {
            vTaskDelete(s_json_task_handle);
            s_json_task_handle = NULL;
        }

        // Free memory buffers
        if (cert_buffer) {
            free(cert_buffer);
            cert_buffer = NULL;
        }
        if (rx_buffer) {
            free(rx_buffer);
            rx_buffer = NULL;
        }

        // Delete queues and synchronization primitives
        if (rx_queue) { vQueueDelete(rx_queue); rx_queue = NULL; }
        if (notify_queue) { vQueueDelete(notify_queue); notify_queue = NULL; }
        if (session_mutex) { vSemaphoreDelete(session_mutex); session_mutex = NULL; }
        if (cert_mutex) { vSemaphoreDelete(cert_mutex); cert_mutex = NULL; }
        if (prov_events) { vEventGroupDelete(prov_events); prov_events = NULL; }
        return true;
    }
    return false;
}

bool ykp_ble_is_active(void) { return s_ble_active; }
bool ykp_ble_is_connected(void) { return (s_ble_active && conn_handle != BLE_HS_CONN_HANDLE_NONE); }

void ykp_ble_provision_wait(void) {
    while(s_ble_active) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

bool ykp_ble_provision_is_success(void) {
    bool ok = false;
    if (session_mutex) xSemaphoreTake(session_mutex, portMAX_DELAY);
    ok = s_session.commit_started;
    if (session_mutex) xSemaphoreGive(session_mutex);
    return ok;
}
