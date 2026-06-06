#ifndef WIFI_MANAGER_H
#define WIFI_MANAGER_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

typedef enum {
    WIFI_STATE_DISCONNECTED = 0,
    WIFI_STATE_CONNECTING,
    WIFI_STATE_CONNECTED,
    WIFI_STATE_FAILED,
} wifi_state_t;

typedef void (*wifi_connected_cb_t)(const char *ip);
typedef void (*wifi_disconnected_cb_t)(uint8_t reason);

bool wifi_manager_init(void);
bool wifi_manager_connect(const char *ssid, const char *password);
void wifi_manager_connect_async(const char *ssid, const char *password);
void wifi_manager_disconnect(void);
bool wifi_manager_is_connected(void);
wifi_state_t wifi_manager_get_state(void);
uint8_t wifi_manager_get_disconnect_reason(void);
void wifi_manager_register_callbacks(wifi_connected_cb_t on_connect,
                                     wifi_disconnected_cb_t on_disconnect);
int8_t wifi_manager_get_rssi(void);
void wifi_manager_get_ip(char *out, size_t len);

/* Scan for a specific SSID. Returns true if found. Blocks for ~2s. */
bool wifi_manager_scan_for_ssid(const char *target_ssid);

/* Perform full scan and populate json_out. Blocks for ~2s. Returns number of APs found. */
int wifi_manager_scan(char *json_out, size_t max_len);

#endif /* WIFI_MANAGER_H */
