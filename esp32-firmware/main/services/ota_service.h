#ifndef OTA_SERVICE_H
#define OTA_SERVICE_H

#include <stdint.h>
#include <stdbool.h>

typedef void (*ota_send_fn_t)(const uint8_t *data, uint16_t len);

typedef struct {
    char     version[16];
    uint32_t firmware_size;
    uint16_t chunk_size;
    uint16_t chunks_total;
    uint8_t  sha256[32];
    uint8_t  ecdsa_sig[64];
    bool     active;
    uint16_t next_expected_chunk;
} ota_state_t;

void ota_service_init(ota_send_fn_t send_fn);
void ota_service_handle(const uint8_t *payload, uint16_t len,
                         uint8_t action_id);
bool ota_service_is_active(void);

#endif
