#include "ykp_packet.h"
#include "ykp_cbor.h"
#include <string.h>
#include <stdlib.h>
#include "esp_log.h"

static const char *TAG = "ykp_packet";

/* C1 fix: static payload receive buffer — no heap alloc on packet parse */
#define YKP_STATIC_PAYLOAD_BUF 512
static uint8_t s_rx_payload_buf[YKP_STATIC_PAYLOAD_BUF];

/* ── Byte-order helpers ── */
static inline void write_u16_be(uint8_t *p, uint16_t v) {
    p[0] = (v >> 8) & 0xFF;
    p[1] =  v       & 0xFF;
}

static inline void write_u32_be(uint8_t *p, uint32_t v) {
    p[0] = (v >> 24) & 0xFF;
    p[1] = (v >> 16) & 0xFF;
    p[2] = (v >>  8) & 0xFF;
    p[3] =  v        & 0xFF;
}

static inline uint16_t read_u16_be(const uint8_t *p) {
    return ((uint16_t)p[0] << 8) | p[1];
}

static inline uint32_t read_u32_be(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) |
           ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] <<  8) |
            (uint32_t)p[3];
}

/* ── Packet allocation ── */
ykp_packet_t *ykp_packet_alloc(void) {
    ykp_packet_t *pkt = calloc(1, sizeof(ykp_packet_t));
    return pkt;
}

void ykp_packet_free(ykp_packet_t *pkt) {
    if (!pkt) return;
    /* C1 guard: s_rx_payload_buf is static — never call free() on it */
    if (pkt->payload && pkt->payload != s_rx_payload_buf) {
        free(pkt->payload);
    }
    pkt->payload = NULL;
    free(pkt);
}

/**
 * F1 fix: Safe payload-only release for stack-allocated ykp_packet_t
 * (i.e. the pkt on the stack in handle_incoming_packet).
 * TX path: payload is heap-allocated by set_payload — free it.
 * RX path: payload points to s_rx_payload_buf (static) — skip free.
 */
void ykp_packet_free_payload(ykp_packet_t *pkt) {
    if (!pkt) return;
    if (pkt->payload && pkt->payload != s_rx_payload_buf) {
        free(pkt->payload);
    }
    pkt->payload = NULL;
}

void ykp_packet_set_header(ykp_packet_t      *pkt,
                            uint32_t           packet_id,
                            uint32_t           session_id,
                            const char        *source_id,
                            const char        *dest_id,
                            ykp_route_t        route_type,
                            ykp_service_t      service_id,
                            uint8_t            action_id,
                            ykp_qos_t          qos,
                            bool               encrypted) {
    if (!pkt) return;
    ykp_header_t *h = &pkt->header;
    h->magic[0]   = YKP_MAGIC_0;
    h->magic[1]   = YKP_MAGIC_1;
    h->version    = YKP_VERSION;
    h->flags      = (qos & YKP_FLAG_QOS_MASK);
    if (encrypted) h->flags |= YKP_FLAG_ENCRYPTED;

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

void ykp_packet_set_payload(ykp_packet_t *pkt, const uint8_t *payload, uint16_t len) {
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

uint16_t ykp_packet_serialise(const ykp_packet_t *pkt, uint8_t *out, uint16_t out_size) {
    if (!pkt || !out) return 0;
    uint16_t total = YKP_HEADER_SIZE + pkt->payload_len + YKP_AUTH_TAG_SIZE;
    if (total > out_size) {
        ESP_LOGE(TAG, "output buffer too small: need %u, have %u", total, out_size);
        return 0;
    }

    uint8_t *p = out;
    *p++ = YKP_MAGIC_0;
    *p++ = YKP_MAGIC_1;
    *p++ = YKP_VERSION;
    *p++ = pkt->header.flags;

    write_u32_be(p, pkt->header.packet_id);   p += 4;
    write_u32_be(p, pkt->header.session_id);  p += 4;

    memcpy(p, pkt->header.source_id, YKP_DEVICE_ID_LEN); p += YKP_DEVICE_ID_LEN;
    memcpy(p, pkt->header.dest_id,   YKP_DEVICE_ID_LEN); p += YKP_DEVICE_ID_LEN;

    *p++ = pkt->header.route_type;
    *p++ = pkt->header.service_id;
    *p++ = pkt->header.action_id;

    write_u16_be(p, pkt->payload_len); p += 2;

    if (pkt->payload_len && pkt->payload) {
        memcpy(p, pkt->payload, pkt->payload_len);
        p += pkt->payload_len;
    }

    memcpy(p, pkt->auth_tag, YKP_AUTH_TAG_SIZE);
    return total;
}

bool ykp_packet_parse(const uint8_t *raw, uint16_t raw_len, ykp_packet_t *pkt_out) {
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

    if (payload_len > 0) {
        /* C1 fix: use static buffer — zero heap alloc on every receive */
        if (payload_len > YKP_STATIC_PAYLOAD_BUF) {
            ESP_LOGE(TAG, "payload too large: %u > %u", payload_len, YKP_STATIC_PAYLOAD_BUF);
            return false;
        }
        memcpy(s_rx_payload_buf, p, payload_len);
        pkt_out->payload = s_rx_payload_buf;
        p += payload_len;
    } else {
        pkt_out->payload = NULL;
    }
    pkt_out->payload_len = payload_len;

    memcpy(pkt_out->auth_tag, p, YKP_AUTH_TAG_SIZE);
    return true;
}

bool ykp_packet_validate_header(const uint8_t *raw, uint16_t raw_len) {
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

/* ── CBOR Mapping for TLV Builder API ── */
void ykp_tlv_builder_init(ykp_tlv_builder_t *b, uint8_t *buf, uint16_t cap) {
    b->buf = buf;
    b->cap = cap;
    b->len = 1;
    b->buf[0] = 0xBF; // Indefinite map start
}

static inline cbor_encoder_t tlv_to_cbor(ykp_tlv_builder_t *b) {
    cbor_encoder_t enc = {
        .buf = b->buf,
        .size = b->cap,
        .len = b->len,
        .overflow = false
    };
    return enc;
}

static inline void cbor_to_tlv(ykp_tlv_builder_t *b, const cbor_encoder_t *enc) {
    b->len = enc->len;
}

bool ykp_tlv_add_uint8(ykp_tlv_builder_t *b, uint8_t type, uint8_t val) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_uint(&enc, val);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_uint16(ykp_tlv_builder_t *b, uint8_t type, uint16_t val) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_uint(&enc, val);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_uint32(ykp_tlv_builder_t *b, uint8_t type, uint32_t val) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_uint(&enc, val);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_int32(ykp_tlv_builder_t *b, uint8_t type, int32_t val) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_int(&enc, val);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_float(ykp_tlv_builder_t *b, uint8_t type, float val) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_float16(&enc, val); // Encode float as float16 half-precision
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_uint64(ykp_tlv_builder_t *b, uint8_t type, uint64_t val) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_uint(&enc, val);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_bytes(ykp_tlv_builder_t *b, uint8_t type, const uint8_t *data, uint16_t len) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_bytes(&enc, data, len);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

bool ykp_tlv_add_string(ykp_tlv_builder_t *b, uint8_t type, const char *str) {
    cbor_encoder_t enc = tlv_to_cbor(b);
    cbor_encode_key(&enc, type);
    cbor_encode_str(&enc, str);
    cbor_to_tlv(b, &enc);
    return !enc.overflow;
}

/* C2 fix: remove const — function mutates buf by writing 0xFF break byte */
uint16_t ykp_tlv_builder_len(ykp_tlv_builder_t *b) {
    /* M3 fix: always reserve 1 byte for 0xFF break; if no room, truncate gracefully */
    if (b->len < b->cap) {
        b->buf[b->len] = 0xFF; /* CBOR indefinite map break */
        return b->len + 1;
    }
    /* Buffer exactly full — overwrite last byte with break to ensure valid CBOR */
    b->buf[b->cap - 1] = 0xFF;
    ESP_LOGW("ykp_packet", "CBOR builder full — break byte overwrote last field");
    return b->cap;
}

/* ── CBOR Parser ── */
bool ykp_tlv_find(const uint8_t *buf, uint16_t buf_len, uint8_t type, ykp_tlv_t *out) {
    if (buf_len < 2) return false;
    uint16_t i = 0;
    uint8_t header = buf[i++];
    bool indefinite = (header == 0xBF);
    uint32_t pairs = 0;

    if (!indefinite) {
        if ((header & 0xE0) == 0xA0) {
            pairs = header & 0x1F;
            if (pairs == 0x18) {
                pairs = buf[i++];
            }
        } else {
            return false;
        }
    }

    while (i < buf_len) {
        if (indefinite && buf[i] == 0xFF) {
            break;
        }

        uint8_t key = 0;
        uint8_t key_hdr = buf[i++];
        if (key_hdr < 24) {
            key = key_hdr;
        } else if (key_hdr == 0x18) {
            key = buf[i++];
        } else {
            return false;
        }

        uint16_t val_start = i;
        uint8_t val_hdr = buf[i];
        uint16_t val_len = 0;
        uint8_t major = val_hdr & 0xE0;
        uint8_t info = val_hdr & 0x1F;
        i++;

        if (major == 0x00 || major == 0x20) {
            if (info < 24) val_len = 0;
            else if (info == 0x18) { i += 1; val_len = 0; }
            else if (info == 0x19) { i += 2; val_len = 0; }
            else if (info == 0x1A) { i += 4; val_len = 0; }
            else if (info == 0x1B) { i += 8; val_len = 0; }
        } else if (major == 0x40 || major == 0x60) {
            uint32_t str_len = 0;
            if (info < 24) str_len = info;
            else if (info == 0x18) str_len = buf[i++];
            else if (info == 0x19) { str_len = read_u16_be(&buf[i]); i += 2; }
            i += str_len;
            val_len = str_len;
        } else if (major == 0xE0) {
            if (info == 25) { i += 2; val_len = 2; }
            else if (info == 26) { i += 4; val_len = 4; }
            else if (info == 27) { i += 8; val_len = 8; }
            else { val_len = 0; }
        }

        if (key == type) {
            out->type = key;
            out->length = i - val_start;
            out->value = (uint8_t *)&buf[val_start];
            return true;
        }
    }
    return false;
}

bool ykp_tlv_read_uint8(const ykp_tlv_t *tlv, uint8_t *out) {
    if (!tlv || tlv->length < 1) return false;
    uint8_t val_hdr = tlv->value[0];
    if ((val_hdr & 0xE0) == 0x00) {
        uint8_t info = val_hdr & 0x1F;
        if (info < 24) {
            *out = info;
            return true;
        } else if (info == 0x18) {
            *out = tlv->value[1];
            return true;
        }
    }
    return false;
}

bool ykp_tlv_read_uint16(const ykp_tlv_t *tlv, uint16_t *out) {
    if (!tlv || tlv->length < 1) return false;
    uint8_t val_hdr = tlv->value[0];
    if ((val_hdr & 0xE0) == 0x00) {
        uint8_t info = val_hdr & 0x1F;
        if (info < 24) {
            *out = info;
            return true;
        } else if (info == 0x18) {
            *out = tlv->value[1];
            return true;
        } else if (info == 0x19) {
            *out = read_u16_be(&tlv->value[1]);
            return true;
        }
    }
    return false;
}

bool ykp_tlv_read_uint32(const ykp_tlv_t *tlv, uint32_t *out) {
    if (!tlv || tlv->length < 1) return false;
    uint8_t val_hdr = tlv->value[0];
    if ((val_hdr & 0xE0) == 0x00) {
        uint8_t info = val_hdr & 0x1F;
        if (info < 24) { *out = info; return true; }
        else if (info == 0x18) { *out = tlv->value[1]; return true; }
        else if (info == 0x19) { *out = read_u16_be(&tlv->value[1]); return true; }
        else if (info == 0x1A) { *out = read_u32_be(&tlv->value[1]); return true; }
    }
    return false;
}

bool ykp_tlv_read_int32(const ykp_tlv_t *tlv, int32_t *out) {
    if (!tlv || tlv->length < 1) return false;
    uint8_t val_hdr = tlv->value[0];
    uint8_t major = val_hdr & 0xE0;
    uint8_t info = val_hdr & 0x1F;
    if (major == 0x00) {
        uint32_t uval = 0;
        if (ykp_tlv_read_uint32(tlv, &uval)) { *out = (int32_t)uval; return true; }
    } else if (major == 0x20) {
        uint64_t uv = 0;
        if (info < 24) uv = info;
        else if (info == 0x18) uv = tlv->value[1];
        else if (info == 0x19) uv = read_u16_be(&tlv->value[1]);
        else if (info == 0x1A) uv = read_u32_be(&tlv->value[1]);
        *out = (int32_t)(-1 - (int64_t)uv);
        return true;
    }
    return false;
}

bool ykp_tlv_read_float(const ykp_tlv_t *tlv, float *out) {
    if (!tlv || tlv->length < 1) return false;
    uint8_t hdr = tlv->value[0];
    if (hdr == 0xF9) { // float16 decoding to float32
        uint16_t f16 = ((uint16_t)tlv->value[1] << 8) | tlv->value[2];
        uint32_t sign = (f16 & 0x8000) << 16;
        int32_t exponent = (f16 & 0x7C00) >> 10;
        uint32_t mantissa = f16 & 0x03FF;
        uint32_t f32;

        if (exponent == 0) {
            if (mantissa == 0) {
                f32 = sign;
            } else {
                exponent = 127 - 15;
                while (!(mantissa & 0x0400)) {
                    mantissa <<= 1;
                    exponent--;
                }
                mantissa &= 0x03FF;
                f32 = sign | (exponent << 23) | (mantissa << 13);
            }
        } else if (exponent == 31) {
            f32 = sign | 0x7F800000 | (mantissa << 13);
        } else {
            f32 = sign | ((exponent - 15 + 127) << 23) | (mantissa << 13);
        }
        memcpy(out, &f32, 4);
        return true;
    } else if (hdr == 0xFA) { // float32
        memcpy(out, &tlv->value[1], 4);
        return true;
    }
    return false;
}

bool ykp_tlv_read_uint64(const ykp_tlv_t *tlv, uint64_t *out) {
    if (!tlv || tlv->length < 1) return false;
    uint8_t val_hdr = tlv->value[0];
    if ((val_hdr & 0xE0) == 0x00) {
        uint8_t info = val_hdr & 0x1F;
        if (info < 24) { *out = info; return true; }
        else if (info == 0x18) { *out = tlv->value[1]; return true; }
        else if (info == 0x19) { *out = read_u16_be(&tlv->value[1]); return true; }
        else if (info == 0x1A) { *out = read_u32_be(&tlv->value[1]); return true; }
        else if (info == 0x1B) {
            *out = 0;
            for (int i = 0; i < 8; i++) { *out = (*out << 8) | tlv->value[1 + i]; }
            return true;
        }
    }
    return false;
}
