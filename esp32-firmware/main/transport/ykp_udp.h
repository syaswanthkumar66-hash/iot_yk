#ifndef YKP_UDP_H
#define YKP_UDP_H

#include <stdint.h>
#include <stdbool.h>
#include <netinet/in.h>

typedef void (*ykp_udp_rx_cb_t)(const uint8_t *data, uint16_t len,
                                 struct sockaddr_in *from);

bool ykp_udp_init(uint16_t port);
bool ykp_udp_send(const char *dest_ip, uint16_t dest_port,
                  const uint8_t *data, uint16_t len);
bool ykp_udp_broadcast(uint16_t dest_port, const uint8_t *data, uint16_t len);
void ykp_udp_register_rx_cb(ykp_udp_rx_cb_t cb);
void ykp_udp_start_rx_task(void);
void ykp_udp_stop(void);

#endif /* YKP_UDP_H */
