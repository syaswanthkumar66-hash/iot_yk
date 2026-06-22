#ifndef YKP_PACKET_H
#define YKP_PACKET_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include "ykp_constants.h"

/* ═══════════════════════════════════════════════
   YKP Packet Structure & TLV Builder/Parser
   ═══════════════════════════════════════════════

   Binary layout (all big-endian):
   ┌────────────────────────────────────┐
   │ MAGIC         2 bytes  0x59 0x4B  │
   │ VERSION       1 byte   0x05       │
   │ FLAGS         1 byte   bitmask    │
   │ PACKET_ID     4 bytes  uint32     │
   │ SESSION_ID    4 bytes  uint32     │
   │ SOURCE_ID     8 bytes  ASCII      │
   │ DEST_ID       8 bytes  ASCII      │
   │ ROUTE_TYPE    1 byte              │
   │ SERVICE_ID    1 byte              │
   │ ACTION_ID     1 byte              │
   │ PAYLOAD_LEN   2 bytes  uint16     │
   │ PAYLOAD       N bytes  enc TLV    │
   │ AUTH_TAG      16 bytes AES-GCM    │
   └────────────────────────────────────┘
*/

/* ── Packet header struct ─────────────────── */
typedef struct __attribute__((packed)) {
    uint8_t  magic[2];           /* 0x59, 0x4B */
    uint8_t  version;            /* 0x05       */
    uint8_t  flags;
    uint32_t packet_id;          /* big-endian */
    uint32_t session_id;         /* big-endian */
    uint8_t  source_id[8];
    uint8_t  dest_id[8];
    uint8_t  route_type;
    uint8_t  service_id;
    uint8_t  action_id;
    uint16_t payload_len;        /* big-endian */
} ykp_header_t;

/* ── Full packet ────────────────────────────── */
typedef struct {
    ykp_header_t  header;
    uint8_t      *payload;       /* encrypted TLV bytes (heap-allocated) */
    uint8_t       auth_tag[YKP_AUTH_TAG_SIZE];
    uint16_t      payload_len;   /* plaintext payload length */
} ykp_packet_t;

/* ── TLV entry ──────────────────────────────── */
typedef struct {
    uint8_t  type;
    uint16_t length;
    uint8_t *value;              /* points into raw buffer — do not free separately */
} ykp_tlv_t;

/* ── TLV buffer builder ──────────────────────── */
typedef struct {
    uint8_t *buf;
    uint16_t cap;
    uint16_t len;
} ykp_tlv_builder_t;

/* ════════════════════════════════════════════
   Packet API
   ════════════════════════════════════════════ */

/**
 * @brief Allocate and initialise a new YKP packet.
 *        Caller must call ykp_packet_free() when done.
 */
ykp_packet_t *ykp_packet_alloc(void);

/**
 * @brief Free packet and its payload buffer.
 */
void ykp_packet_free(ykp_packet_t *pkt);

/**
 * @brief F1 fix: Release only the payload of a stack-allocated packet.
 *        Safe for both heap-allocated (TX) and static-buf (RX) payloads.
 */
void ykp_packet_free_payload(ykp_packet_t *pkt);

/**
 * @brief Set the fixed header fields.
 */
void ykp_packet_set_header(ykp_packet_t      *pkt,
                            uint32_t           packet_id,
                            uint32_t           session_id,
                            const char        *source_id,
                            const char        *dest_id,
                            ykp_route_t        route_type,
                            ykp_service_t      service_id,
                            uint8_t            action_id,
                            ykp_qos_t          qos,
                            bool               encrypted);

/**
 * @brief Set the plaintext payload (will be encrypted by ykp_security).
 */
void ykp_packet_set_payload(ykp_packet_t *pkt,
                             const uint8_t *payload,
                             uint16_t       len);

/**
 * @brief Serialise packet to raw bytes (payload must already be encrypted).
 *        Returns bytes written, 0 on error.
 */
uint16_t ykp_packet_serialise(const ykp_packet_t *pkt,
                               uint8_t            *out_buf,
                               uint16_t            out_buf_size);

/**
 * @brief Parse raw bytes into a ykp_packet_t.
 *        Returns true on success. Allocates pkt->payload — caller must free.
 */
bool ykp_packet_parse(const uint8_t *raw,
                      uint16_t       raw_len,
                      ykp_packet_t  *pkt_out);

/**
 * @brief Validate magic bytes and version.
 */
bool ykp_packet_validate_header(const uint8_t *raw, uint16_t raw_len);

/* ════════════════════════════════════════════
   TLV Builder API
   ════════════════════════════════════════════ */

void     ykp_tlv_builder_init(ykp_tlv_builder_t *b, uint8_t *buf, uint16_t cap);
bool     ykp_tlv_add_uint8(ykp_tlv_builder_t *b, uint8_t type, uint8_t val);
bool     ykp_tlv_add_uint16(ykp_tlv_builder_t *b, uint8_t type, uint16_t val);
bool     ykp_tlv_add_uint32(ykp_tlv_builder_t *b, uint8_t type, uint32_t val);
bool     ykp_tlv_add_int32(ykp_tlv_builder_t *b, uint8_t type, int32_t val);
bool     ykp_tlv_add_float(ykp_tlv_builder_t *b, uint8_t type, float val);
bool     ykp_tlv_add_uint64(ykp_tlv_builder_t *b, uint8_t type, uint64_t val);
bool     ykp_tlv_add_bytes(ykp_tlv_builder_t *b, uint8_t type,
                            const uint8_t *data, uint16_t len);
bool     ykp_tlv_add_string(ykp_tlv_builder_t *b, uint8_t type, const char *str);
/* C2 fix: NOT const — function writes 0xFF break byte into the buffer */
uint16_t ykp_tlv_builder_len(ykp_tlv_builder_t *b);

/* ════════════════════════════════════════════
   TLV Parser API
   ════════════════════════════════════════════ */

/**
 * @brief Find first TLV with the given type in a buffer.
 *        Returns true if found, fills tlv_out (value points into buf).
 */
bool ykp_tlv_find(const uint8_t *buf, uint16_t buf_len,
                  uint8_t type, ykp_tlv_t *tlv_out);

bool     ykp_tlv_read_uint8(const ykp_tlv_t *tlv, uint8_t *out);
bool     ykp_tlv_read_uint16(const ykp_tlv_t *tlv, uint16_t *out);
bool     ykp_tlv_read_uint32(const ykp_tlv_t *tlv, uint32_t *out);
bool     ykp_tlv_read_int32(const ykp_tlv_t *tlv, int32_t *out);
bool     ykp_tlv_read_float(const ykp_tlv_t *tlv, float *out);
bool     ykp_tlv_read_uint64(const ykp_tlv_t *tlv, uint64_t *out);

#endif /* YKP_PACKET_H */
