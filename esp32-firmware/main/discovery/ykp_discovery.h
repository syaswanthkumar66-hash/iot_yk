#ifndef YKP_DISCOVERY_H
#define YKP_DISCOVERY_H

#include <stdint.h>
#include <stdbool.h>
#include "lwip/sockets.h"

void ykp_discovery_init(void);
void ykp_discovery_start_task(void);
void ykp_discovery_handle(const uint8_t *data, uint16_t len,
                           struct sockaddr_in *from);

#endif
