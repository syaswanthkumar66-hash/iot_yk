#ifndef WIFI_MANAGER_H
#define WIFI_MANAGER_H

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    WIFI_STATE_DISCONNECTED = 0,
    WIFI_STATE_CONNECTING,
    WIFI_STATE_CONNECTED,
    WIFI_STATE_FAILED,
} wifi_state_t;

typedef void (*wifi_connected_cb_t)(const char *ip);
typedef void (*wifi_disconnected_cb_t)(void);

bool wifi_manager_init(void);
bool wifi_manager_connect(const char *ssid, const char *password);
bool wifi_manager_is_connected(void);
wifi_state_t wifi_manager_get_state(void);
void wifi_manager_register_callbacks(wifi_connected_cb_t on_connect,
                                     wifi_disconnected_cb_t on_disconnect);
int8_t wifi_manager_get_rssi(void);
void wifi_manager_get_ip(char *out, size_t len);

#endif /* WIFI_MANAGER_H */
