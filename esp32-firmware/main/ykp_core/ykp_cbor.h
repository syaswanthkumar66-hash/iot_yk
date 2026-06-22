#ifndef YKP_CBOR_H
#define YKP_CBOR_H

#include <stdint.h>
#include <string.h>
#include <stdbool.h>

/* Master CBOR Integer Keys */
#define CBOR_KEY_DEVICE_ID      0x01
#define CBOR_KEY_TIMESTAMP      0x02
#define CBOR_KEY_STATE          0x03
#define CBOR_KEY_VALUE_INT      0x04
#define CBOR_KEY_VALUE_FLOAT    0x05
#define CBOR_KEY_FIRMWARE_VER   0x08
#define CBOR_KEY_PUBLIC_KEY     0x09
#define CBOR_KEY_NONCE          0x0A
#define CBOR_KEY_SIGNATURE      0x0B
#define CBOR_KEY_RSSI           0x12

typedef struct {
    uint8_t *buf;
    size_t size;
    size_t len;
    bool overflow;
} cbor_encoder_t;

static inline void cbor_encoder_init(cbor_encoder_t *enc, uint8_t *buf, size_t size) {
    enc->buf = buf;
    enc->size = size;
    enc->len = 0;
    enc->overflow = false;
}

static inline void cbor_write_byte(cbor_encoder_t *enc, uint8_t b) {
    if (enc->len < enc->size) {
        enc->buf[enc->len++] = b;
    } else {
        enc->overflow = true;
    }
}

static inline void cbor_write_bytes(cbor_encoder_t *enc, const uint8_t *data, size_t size) {
    if (enc->len + size <= enc->size) {
        memcpy(&enc->buf[enc->len], data, size);
        enc->len += size;
    } else {
        enc->overflow = true;
    }
}

/* Encode CBOR map header */
static inline void cbor_encode_map_header(cbor_encoder_t *enc, size_t pairs) {
    if (pairs < 24) {
        cbor_write_byte(enc, 0xA0 | (uint8_t)pairs);
    } else {
        cbor_write_byte(enc, 0xB8);
        cbor_write_byte(enc, (uint8_t)pairs);
    }
}

/* Encode uint8 key */
static inline void cbor_encode_key(cbor_encoder_t *enc, uint8_t key) {
    if (key < 24) {
        cbor_write_byte(enc, key);
    } else {
        cbor_write_byte(enc, 0x18);
        cbor_write_byte(enc, key);
    }
}

/* Encode unsigned integer */
static inline void cbor_encode_uint(cbor_encoder_t *enc, uint64_t val) {
    if (val < 24) {
        cbor_write_byte(enc, (uint8_t)val);
    } else if (val <= 0xFF) {
        cbor_write_byte(enc, 0x18);
        cbor_write_byte(enc, (uint8_t)val);
    } else if (val <= 0xFFFF) {
        cbor_write_byte(enc, 0x19);
        cbor_write_byte(enc, (val >> 8) & 0xFF);
        cbor_write_byte(enc, val & 0xFF);
    } else if (val <= 0xFFFFFFFFUL) {
        cbor_write_byte(enc, 0x1A);
        cbor_write_byte(enc, (val >> 24) & 0xFF);
        cbor_write_byte(enc, (val >> 16) & 0xFF);
        cbor_write_byte(enc, (val >> 8) & 0xFF);
        cbor_write_byte(enc, val & 0xFF);
    } else {
        cbor_write_byte(enc, 0x1B);
        for (int i = 7; i >= 0; i--) {
            cbor_write_byte(enc, (val >> (i * 8)) & 0xFF);
        }
    }
}

/* Encode signed integer */
static inline void cbor_encode_int(cbor_encoder_t *enc, int64_t val) {
    if (val >= 0) {
        cbor_encode_uint(enc, (uint64_t)val);
    } else {
        uint64_t uv = (uint64_t)(-1 - val);
        if (uv < 24) {
            cbor_write_byte(enc, 0x20 | (uint8_t)uv);
        } else if (uv <= 0xFF) {
            cbor_write_byte(enc, 0x38);
            cbor_write_byte(enc, (uint8_t)uv);
        } else if (uv <= 0xFFFF) {
            cbor_write_byte(enc, 0x39);
            cbor_write_byte(enc, (uv >> 8) & 0xFF);
            cbor_write_byte(enc, uv & 0xFF);
        } else {
            cbor_write_byte(enc, 0x3A);
            cbor_write_byte(enc, (uv >> 24) & 0xFF);
            cbor_write_byte(enc, (uv >> 16) & 0xFF);
            cbor_write_byte(enc, (uv >> 8) & 0xFF);
            cbor_write_byte(enc, uv & 0xFF);
        }
    }
}

/* Encode boolean */
static inline void cbor_encode_bool(cbor_encoder_t *enc, bool val) {
    cbor_write_byte(enc, val ? 0xF5 : 0xF4);
}

/* Encode UTF-8 string */
static inline void cbor_encode_str(cbor_encoder_t *enc, const char *str) {
    size_t len = strlen(str);
    if (len < 24) {
        cbor_write_byte(enc, 0x60 | (uint8_t)len);
    } else {
        cbor_write_byte(enc, 0x78);
        cbor_write_byte(enc, (uint8_t)len);
    }
    cbor_write_bytes(enc, (const uint8_t *)str, len);
}

/* Encode byte string (bytes) */
static inline void cbor_encode_bytes(cbor_encoder_t *enc, const uint8_t *data, size_t len) {
    if (len < 24) {
        cbor_write_byte(enc, 0x40 | (uint8_t)len);
    } else {
        cbor_write_byte(enc, 0x58);
        cbor_write_byte(enc, (uint8_t)len);
    }
    cbor_write_bytes(enc, data, len);
}

/* Encode float32 to float16 (half-precision) */
static inline uint16_t float32_to_float16(float val) {
    uint32_t f;
    memcpy(&f, &val, 4);
    uint32_t sign = (f >> 16) & 0x8000;
    int32_t exponent = ((f >> 23) & 0xFF) - 127;
    uint32_t mantissa = f & 0x007FFFFF;

    if (((f >> 23) & 0xFF) == 0) return sign;
    if (((f >> 23) & 0xFF) == 0xFF) return sign | 0x7C00 | (mantissa ? 0x0200 : 0);

    int32_t new_exp = exponent + 15;
    if (new_exp >= 31) return sign | 0x7C00;
    if (new_exp <= 0) {
        if (new_exp < -10) return sign;
        mantissa = (mantissa | 0x00800000) >> (1 - new_exp);
        return sign | (mantissa >> 13);
    }
    return sign | (new_exp << 10) | (mantissa >> 13);
}

static inline void cbor_encode_float16(cbor_encoder_t *enc, float val) {
    uint16_t f16 = float32_to_float16(val);
    cbor_write_byte(enc, 0xF9);
    cbor_write_byte(enc, (f16 >> 8) & 0xFF);
    cbor_write_byte(enc, f16 & 0xFF);
}

#endif /* YKP_CBOR_H */
