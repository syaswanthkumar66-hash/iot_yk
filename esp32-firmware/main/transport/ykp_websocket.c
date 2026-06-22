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
#include "esp_timer.h"
#include "esp_task_wdt.h"

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

static const char *FALLBACK_CA_PEM =
    "-----BEGIN CERTIFICATE-----\n"
    "MIICCjCCAZGgAwIBAgIQbkepyIuUtui7OyrYorLBmTAKBggqhkjOPQQDAzBHMQsw\n"
    "CQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZpY2VzIExMQzEU\n"
    "MBIGA1UEAxMLR1RTIFJvb3QgUjQwHhcNMTYwNjIyMDAwMDAwWhcNMzYwNjIyMDAw\n"
    "MDAwWjBHMQswCQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZp\n"
    "Y2VzIExMQzEUMBIGA1UEAxMLR1RTIFJvb3QgUjQwdjAQBgcqhkjOPQIBBgUrgQQA\n"
    "IgNiAATzdHOnaItgrkO4NcWBMHtLSZ37wWHO5t5GvWvVYRg1rkDdc/eJkTBa6zzu\n"
    "hXyiQHY7qca4R9gq55KRanPpsXI5nymfopjTX15YhmUPoYRlBtHci8nHc8iMai/l\n"
    "xKvRHYqjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EBTADAQH/MB0GA1Ud\n"
    "DgQWBBSATNbrdP9JNqPV2Py1PsVq8JQdjDAKBggqhkjOPQQDAwNnADBkAjBqUFJ0\n"
    "CMRw3J5QdCHojXohw0+WbhXRIjVhLfoIN+4Zba3bssx9BzT1YBkstTTZbyACMANx\n"
    "sbqjYAuG7ZoIapVon+Kz4ZNkfF6Tpt95LY2F45TPI11xzPKwTdb+mciUqXWi4w==\n"
    "-----END CERTIFICATE-----\n"
    "-----BEGIN CERTIFICATE-----\n"
    "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\n"
    "-----END CERTIFICATE-----\n"
    "-----BEGIN CERTIFICATE-----\n"
    "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\n"
    "-----END CERTIFICATE-----\n"
    "-----BEGIN CERTIFICATE-----\n"
    "MIIB4TCCAYegAwIBAgIRKjikHJYKBN5CsiilC+g0mAIwCgYIKoZIzj0EAwIwUDEk\n"
    "MCIGA1UECxMbR2xvYmFsU2lnbiBFQ0MgUm9vdCBDQSAtIFI0MRMwEQYDVQQKEwpH\n"
    "bG9iYWxTaWduMRMwEQYDVQQDEwpHbG9iYWxTaWduMB4XDTEyMTExMzAwMDAwMFoX\n"
    "DTM4MDExOTAzMTQwN1owUDEkMCIGA1UECxMbR2xvYmFsU2lnbiBFQ0MgUm9vdCBD\n"
    "QSAtIFI0MRMwEQYDVQQKEwpHbG9iYWxTaWduMRMwEQYDVQQDEwpHbG9iYWxTaWdu\n"
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuMZ5049sJQ6fLjkZHAOkrprlOQcJ\n"
    "FspjsbmG+IpXwVfOQvpzofdlQv8ewQCybnMO/8ch5RikqtlxP6jUuc6MHaNCMEAw\n"
    "DgYDVR0PAQH/BAQDAgEGMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFFSwe61F\n"
    "uOJAf/sKbvu+M8k8o4TVMAoGCCqGSM49BAMCA0gAMEUCIQDckqGgE6bPA7DmxCGX\n"
    "kPoUVy0D7O48027KqGx2vKLeuwIgJ6iFJzWbVsaj8kfSt24bAgAXqmemFZHe+pTs\n"
    "ewv4n4Q=\n"
    "-----END CERTIFICATE-----\n";

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
            ESP_LOGE(TAG, "Corrupted/Truncated SSL Certificate found in NVS! Discarding.");
            has_custom_cert = false;
        } else {
            // Strictly validate using mbedtls before trusting it
            mbedtls_x509_crt test_crt;
            mbedtls_x509_crt_init(&test_crt);
            int ret = mbedtls_x509_crt_parse(&test_crt, (const unsigned char *)cleaned_cert, strlen(cleaned_cert) + 1);
            mbedtls_x509_crt_free(&test_crt);
            
            if (ret != 0) {
                ESP_LOGE(TAG, "Strict mbedtls parse failed (-0x%04X). Cert is corrupted! Discarding.", -ret);
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

    // Combine custom certificate (if present) with built-in fallbacks
    static char combined_cert_buf[8192];
    combined_cert_buf[0] = '\0';

    if (has_custom_cert) {
        strncpy(combined_cert_buf, ssl_cert_buf, sizeof(combined_cert_buf) - 1);
        combined_cert_buf[sizeof(combined_cert_buf) - 1] = '\0';
        
        // Ensure trailing newline
        int len = strlen(combined_cert_buf);
        if (len > 0 && combined_cert_buf[len - 1] != '\n' && len < sizeof(combined_cert_buf) - 2) {
            combined_cert_buf[len] = '\n';
            combined_cert_buf[len + 1] = '\0';
        }
    }
    
    // Always append built-in fallback certificates to guarantee connection
    strncat(combined_cert_buf, FALLBACK_CA_PEM, sizeof(combined_cert_buf) - strlen(combined_cert_buf) - 1);
    ws_cfg.cert_pem = combined_cert_buf;

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

#include <sys/socket.h>
#include <netdb.h>
#include <arpa/inet.h>
#include "esp_tls.h"

bool ykp_ws_validate_tcp(const char *host, uint16_t port)
{
    ESP_LOGI(TAG, "[VAL] TCP connecting to %s:%u...", host, port);
    
    struct addrinfo hints = {
        .ai_family = AF_INET,
        .ai_socktype = SOCK_STREAM,
    };
    struct addrinfo *res;
    char port_str[6];
    snprintf(port_str, sizeof(port_str), "%u", port);
    
    int err = getaddrinfo(host, port_str, &hints, &res);
    if (err != 0 || res == NULL) {
        ESP_LOGE(TAG, "[VAL] DNS lookup failed for %s", host);
        return false;
    }
    
    int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) {
        freeaddrinfo(res);
        return false;
    }
    
    // Set 3 seconds timeout
    struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    
    int ret = connect(sock, res->ai_addr, res->ai_addrlen);
    close(sock);
    freeaddrinfo(res);
    
    if (ret != 0) {
        ESP_LOGE(TAG, "[VAL] TCP connection FAILED to %s:%u", host, port);
        return false;
    }
    
    ESP_LOGI(TAG, "[VAL] TCP connection OK");
    return true;
}

const char* ykp_ws_validate_ssl(const char *host, uint16_t port, const char *ca_cert)
{
    ESP_LOGI(TAG, "[VAL] SSL connecting to %s:%u...", host, port);
    
    esp_tls_cfg_t cfg = {
        .cacert_buf = (const unsigned char *)ca_cert,
        .cacert_bytes = strlen(ca_cert) + 1,
        .timeout_ms = 8000,
        .non_block = true,
    };
    
    esp_tls_t *tls = esp_tls_init();
    if (!tls) {
        return "cert_invalid";
    }
    
    int ret = 0;
    int64_t start = esp_timer_get_time();
    while (1) {
        ret = esp_tls_conn_new_sync(host, strlen(host), port, &cfg, tls);
        if (ret == 1) {
            break;
        } else if (ret == -1) {
            break;
        }
        
        esp_task_wdt_reset();
        
        if ((esp_timer_get_time() - start) > 8000000) {
            ESP_LOGE(TAG, "[VAL] SSL Handshake timeout!");
            ret = -1;
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    
    if (ret == 1) {
        ykp_ws_tls_destroy_safe(&tls);
        ESP_LOGI(TAG, "[VAL] SSL handshake SUCCESSFUL!");
        return NULL; // success
    }
    
    ykp_ws_tls_destroy_safe(&tls);
    
    // Read current system time to diagnose if clock is unsynced
    time_t now;
    time(&now);
    if (now < 1000000000) {
        ESP_LOGE(TAG, "[VAL] SSL handshake failed — system time is unsynced!");
        return "cert_expired";
    }
    
    ESP_LOGE(TAG, "[VAL] SSL handshake failed — cert invalid / wrong CA!");
    return "cert_invalid";
}

const char* ykp_ws_validate_ssl_fallback(const char *host, uint16_t port)
{
    return ykp_ws_validate_ssl(host, port, FALLBACK_CA_PEM);
}

void ykp_ws_tls_destroy_safe(struct esp_tls **handle)
{
    if (handle && *handle) {
        esp_tls_conn_destroy(*handle);
        *handle = NULL;
    }
}


