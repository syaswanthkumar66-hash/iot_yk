#ifndef YKP_WEBSOCKET_H
#define YKP_WEBSOCKET_H

#include <stdint.h>
#include <stdbool.h>

typedef void (*ykp_ws_rx_cb_t)(const uint8_t *data, uint16_t len);
typedef void (*ykp_ws_connected_cb_t)(void);
typedef void (*ykp_ws_disconnected_cb_t)(void);

bool ykp_ws_init(const char *server_url);
bool ykp_ws_connect(void);
bool ykp_ws_send(const uint8_t *data, uint16_t len);
bool ykp_ws_is_connected(void);
void ykp_ws_disconnect(void);
void ykp_ws_register_rx_cb(ykp_ws_rx_cb_t cb);
void ykp_ws_register_connected_cb(ykp_ws_connected_cb_t cb);
void ykp_ws_register_disconnected_cb(ykp_ws_disconnected_cb_t cb);

#endif /* YKP_WEBSOCKET_H */
