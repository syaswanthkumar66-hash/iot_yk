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

struct esp_tls;
bool ykp_ws_validate_tcp(const char *host, uint16_t port);
const char* ykp_ws_validate_ssl(const char *host, uint16_t port, const char *ca_cert);
const char* ykp_ws_validate_ssl_fallback(const char *host, uint16_t port);
void ykp_ws_tls_destroy_safe(struct esp_tls **handle);

#endif /* YKP_WEBSOCKET_H */
