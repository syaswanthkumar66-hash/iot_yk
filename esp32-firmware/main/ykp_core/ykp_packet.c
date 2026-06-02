#include "ykp_packet.h"
#include <string.h>
#include <stdlib.h>
#include "esp_log.h"

static const char *TAG = "ykp_packet";

/* ════════════════════════════════════════════
   Byte-order helpers (big-endian)
   ════════════════════════════════════════════ */
static inline void write_u16_be(uint8_t *p, uint16_t v)
{
    p[0] = (v >> 8) & 0xFF;
    p[1] =  v       & 0xFF;
}

static inline void write_u32_be(uint8_t *p, uint32_t v)
{
    p[0] = (v >> 24) & 0xFF;
    p[1] = (v >> 16) & 0xFF;
    p[2] = (v >>  8) & 0xFF;
    p[3] =  v        & 0xFF;
}

static inline uint16_t read_u16_be(const uint8_t *p)
{
    return ((uint16_t)p[0] << 8) | p[1];
}

static inline uint32_t read_u32_be(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) |
           ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] <<  8) |
            (uint32_t)p[3];
}

/* ════════════════════════════════════════════
   Packet alloc / free
   ════════════════════════════════════════════ */
ykp_packet_t *ykp_packet_alloc(void)
{
    ykp_packet_t *pkt = calloc(1, sizeof(ykp_packet_t));
    return pkt;
}

void ykp_packet_free(ykp_packet_t *pkt)
{
    if (!pkt) return;
    if (pkt->payload) {
        free(pkt->payload);
        pkt->payload = NULL;
    }
    free(pkt);
}

/* ════════════════════════════════════════════
   Set header fields
   ════════════════════════════════════════════ */
void ykp_packet_set_header(ykp_packet_t      *pkt,
                            uint32_t           packet_id,
                            uint32_t           session_id,
                            const char        *source_id,
                            const char        *dest_id,
                            ykp_route_t        route_type,
                            ykp_service_t      service_id,
                            uint8_t            action_id,
                            ykp_qos_t          qos,
                            bool               encrypted)
{
    if (!pkt) return;

    ykp_header_t *h = &pkt->header;
    h->magic[0]   = YKP_MAGIC_0;
    h->magic[1]   = YKP_MAGIC_1;
    h->version    = YKP_VERSION;
    h->flags      = (qos & YKP_FLAG_QOS_MASK);
    if (encrypted) h->flags |= YKP_FLAG_ENCRYPTED;

    /* Store as big-endian in the serialised form; keep native in struct */
    h->packet_id  = packet_id;
    h->session_id = session_id;
    h->route_type = (uint8_t)route_type;
    h->service_id = (uint8_t)service_id;
    h->action_id  = action_id;

    memset(h->source_id, 0, YKP_DEVICE_ID_LEN);
    memset(h->dest_id,   0, YKP_DEVICE_ID_LEN);
    if (source_id) strncpy((char *)h->source_id, source_id, YKP_DEVICE_ID_LEN);
    if (dest_id)   strncpy((char *)h->dest_id,   dest_id,   YKP_DEVICE_ID_LEN);
}

/* ════════════════════════════════════════════
   Set payload
   ════════════════════════════════════════════ */
void ykp_packet_set_payload(ykp_packet_t *pkt,
                             const uint8_t *payload,
                             uint16_t       len)
{
    if (!pkt) return;
    if (pkt->payload) free(pkt->payload);

    if (len == 0 || !payload) {
        pkt->payload     = NULL;
        pkt->payload_len = 0;
        pkt->header.payload_len = 0;
        return;
    }

    pkt->payload = malloc(len);
    if (!pkt->payload) {
        ESP_LOGE(TAG, "payload malloc failed (%u bytes)", len);
        return;
    }
    memcpy(pkt->payload, payload, len);
    pkt->payload_len        = len;
    pkt->header.payload_len = len;
}

/* ════════════════════════════════════════════
   Serialise → raw bytes (big-endian wire format)
   ════════════════════════════════════════════ */
uint16_t ykp_packet_serialise(const ykp_packet_t *pkt,
                               uint8_t            *out,
                               uint16_t            out_size)
{
    if (!pkt || !out) return 0;

    uint16_t total = YKP_HEADER_SIZE + pkt->payload_len + YKP_AUTH_TAG_SIZE;
    if (total > out_size) {
        ESP_LOGE(TAG, "output buffer too small: need %u, have %u", total, out_size);
        return 0;
    }

    uint8_t *p = out;

    /* Magic + version + flags */
    *p++ = YKP_MAGIC_0;
    *p++ = YKP_MAGIC_1;
    *p++ = YKP_VERSION;
    *p++ = pkt->header.flags;

    /* packet_id, session_id (big-endian) */
    write_u32_be(p, pkt->header.packet_id);   p += 4;
    write_u32_be(p, pkt->header.session_id);  p += 4;

    /* source_id, dest_id */
    memcpy(p, pkt->header.source_id, YKP_DEVICE_ID_LEN); p += YKP_DEVICE_ID_LEN;
    memcpy(p, pkt->header.dest_id,   YKP_DEVICE_ID_LEN); p += YKP_DEVICE_ID_LEN;

    /* route, service, action */
    *p++ = pkt->header.route_type;
    *p++ = pkt->header.service_id;
    *p++ = pkt->header.action_id;

    /* payload length */
    write_u16_be(p, pkt->payload_len); p += 2;

    /* payload */
    if (pkt->payload_len && pkt->payload) {
        memcpy(p, pkt->payload, pkt->payload_len);
        p += pkt->payload_len;
    }

    /* auth_tag */
    memcpy(p, pkt->auth_tag, YKP_AUTH_TAG_SIZE);

    return total;
}

/* ════════════════════════════════════════════
   Parse raw bytes → ykp_packet_t
   ════════════════════════════════════════════ */
bool ykp_packet_parse(const uint8_t *raw,
                      uint16_t       raw_len,
                      ykp_packet_t  *pkt_out)
{
    if (!raw || !pkt_out) return false;
    if (!ykp_packet_validate_header(raw, raw_len)) return false;

    const uint8_t *p = raw;
    ykp_header_t  *h = &pkt_out->header;

    h->magic[0] = *p++;
    h->magic[1] = *p++;
    h->version  = *p++;
    h->flags    = *p++;

    h->packet_id  = read_u32_be(p); p += 4;
    h->session_id = read_u32_be(p); p += 4;

    memcpy(h->source_id, p, YKP_DEVICE_ID_LEN); p += YKP_DEVICE_ID_LEN;
    memcpy(h->dest_id,   p, YKP_DEVICE_ID_LEN); p += YKP_DEVICE_ID_LEN;

    h->route_type = *p++;
    h->service_id = *p++;
    h->action_id  = *p++;

    uint16_t payload_len = read_u16_be(p); p += 2;
    h->payload_len = payload_len;

    uint16_t expected = YKP_HEADER_SIZE + payload_len + YKP_AUTH_TAG_SIZE;
    if (raw_len < expected) {
        ESP_LOGE(TAG, "short packet: got %u, need %u", raw_len, expected);
        return false;
    }

    /* Copy payload */
    if (payload_len > 0) {
        pkt_out->payload = malloc(payload_len);
        if (!pkt_out->payload) return false;
        memcpy(pkt_out->payload, p, payload_len);
        p += payload_len;
    } else {
        pkt_out->payload = NULL;
    }
    pkt_out->payload_len = payload_len;

    /* Auth tag */
    memcpy(pkt_out->auth_tag, p, YKP_AUTH_TAG_SIZE);

    return true;
}

bool ykp_packet_validate_header(const uint8_t *raw, uint16_t raw_len)
{
    if (raw_len < YKP_MIN_PACKET) {
        ESP_LOGE(TAG, "packet too short: %u", raw_len);
        return false;
    }
    if (raw[0] != YKP_MAGIC_0 || raw[1] != YKP_MAGIC_1) {
        ESP_LOGE(TAG, "bad magic: %02X %02X", raw[0], raw[1]);
        return false;
    }
    if (raw[2] != YKP_VERSION) {
        ESP_LOGE(TAG, "unsupported version: %u", raw[2]);
        return false;
    }
    return true;
}

/* ════════════════════════════════════════════
   TLV Builder
   ════════════════════════════════════════════ */
void ykp_tlv_builder_init(ykp_tlv_builder_t *b, uint8_t *buf, uint16_t cap)
{
    b->buf = buf;
    b->cap = cap;
    b->len = 0;
}

static bool tlv_write(ykp_tlv_builder_t *b, uint8_t type,
                      const uint8_t *data, uint16_t len)
{
    uint16_t need = 1 + 2 + len;  /* type + length(2) + value */
    if (b->len + need > b->cap) {
        ESP_LOGW(TAG, "TLV buffer full");
        return false;
    }
    b->buf[b->len++] = type;
    write_u16_be(&b->buf[b->len], len);
    b->len += 2;
    if (len && data) {
        memcpy(&b->buf[b->len], data, len);
        b->len += len;
    }
    return true;
}

bool ykp_tlv_add_uint8(ykp_tlv_builder_t *b, uint8_t type, uint8_t val)
{
    return tlv_write(b, type, &val, 1);
}

bool ykp_tlv_add_uint16(ykp_tlv_builder_t *b, uint8_t type, uint16_t val)
{
    uint8_t tmp[2];
    write_u16_be(tmp, val);
    return tlv_write(b, type, tmp, 2);
}

bool ykp_tlv_add_uint32(ykp_tlv_builder_t *b, uint8_t type, uint32_t val)
{
    uint8_t tmp[4];
    write_u32_be(tmp, val);
    return tlv_write(b, type, tmp, 4);
}

bool ykp_tlv_add_int32(ykp_tlv_builder_t *b, uint8_t type, int32_t val)
{
    return ykp_tlv_add_uint32(b, type, (uint32_t)val);
}

bool ykp_tlv_add_float(ykp_tlv_builder_t *b, uint8_t type, float val)
{
    uint8_t tmp[4];
    memcpy(tmp, &val, 4);
    return tlv_write(b, type, tmp, 4);
}

bool ykp_tlv_add_uint64(ykp_tlv_builder_t *b, uint8_t type, uint64_t val)
{
    uint8_t tmp[8];
    for (int i = 7; i >= 0; i--) { tmp[i] = val & 0xFF; val >>= 8; }
    return tlv_write(b, type, tmp, 8);
}

bool ykp_tlv_add_bytes(ykp_tlv_builder_t *b, uint8_t type,
                        const uint8_t *data, uint16_t len)
{
    return tlv_write(b, type, data, len);
}

bool ykp_tlv_add_string(ykp_tlv_builder_t *b, uint8_t type, const char *str)
{
    uint16_t len = str ? (uint16_t)strlen(str) : 0;
    return tlv_write(b, type, (const uint8_t *)str, len);
}

uint16_t ykp_tlv_builder_len(const ykp_tlv_builder_t *b)
{
    return b->len;
}

/* ════════════════════════════════════════════
   TLV Parser
   ════════════════════════════════════════════ */
bool ykp_tlv_find(const uint8_t *buf, uint16_t buf_len,
                  uint8_t type, ykp_tlv_t *out)
{
    uint16_t i = 0;
    while (i + 3 <= buf_len) {
        uint8_t  t = buf[i];
        uint16_t l = read_u16_be(&buf[i + 1]);
        if (i + 3 + l > buf_len) break;
        if (t == type) {
            out->type   = t;
            out->length = l;
            out->value  = (uint8_t *)&buf[i + 3];
            return true;
        }
        i += 3 + l;
    }
    return false;
}

bool ykp_tlv_read_uint8(const ykp_tlv_t *tlv, uint8_t *out)
{
    if (!tlv || tlv->length < 1) return false;
    *out = tlv->value[0];
    return true;
}

bool ykp_tlv_read_uint16(const ykp_tlv_t *tlv, uint16_t *out)
{
    if (!tlv || tlv->length < 2) return false;
    *out = read_u16_be(tlv->value);
    return true;
}

bool ykp_tlv_read_uint32(const ykp_tlv_t *tlv, uint32_t *out)
{
    if (!tlv || tlv->length < 4) return false;
    *out = read_u32_be(tlv->value);
    return true;
}

bool ykp_tlv_read_int32(const ykp_tlv_t *tlv, int32_t *out)
{
    return ykp_tlv_read_uint32(tlv, (uint32_t *)out);
}

bool ykp_tlv_read_float(const ykp_tlv_t *tlv, float *out)
{
    if (!tlv || tlv->length < 4) return false;
    memcpy(out, tlv->value, 4);
    return true;
}

bool ykp_tlv_read_uint64(const ykp_tlv_t *tlv, uint64_t *out)
{
    if (!tlv || tlv->length < 8) return false;
    *out = 0;
    for (int i = 0; i < 8; i++) { *out = (*out << 8) | tlv->value[i]; }
    return true;
}
