#ifndef RELAY_SERVICE_H
#define RELAY_SERVICE_H

#include <stdbool.h>
#include <stdint.h>
#include "ykp_session.h"

typedef void (*relay_send_fn_t)(const uint8_t *data, uint16_t len);

/* H4 fix: session pointer so button press ACK uses real packet/session IDs */
void relay_service_init(relay_send_fn_t send_fn, ykp_session_t *session);
void relay_service_handle(const uint8_t *payload, uint16_t len,
                           uint8_t action_id, uint32_t packet_id,
                           uint32_t session_id, const char *dest_id);
bool relay_service_get_state(void);

#endif
