#ifndef YKP_CONSTANTS_H
#define YKP_CONSTANTS_H

#include <stdint.h>

/* ═══════════════════════════════════════════════
   YKP v5 — All Constants, Enums, Sizes
   ═══════════════════════════════════════════════ */

/* ── Magic & Version ──────────────────────── */
#define YKP_MAGIC_0         0x59   /* 'Y' */
#define YKP_MAGIC_1         0x4B   /* 'K' */
#define YKP_VERSION         0x05

/* ── Packet size constants ────────────────── */
#define YKP_HEADER_SIZE     33
#define YKP_AUTH_TAG_SIZE   16
#define YKP_MIN_PACKET      (YKP_HEADER_SIZE + YKP_AUTH_TAG_SIZE)
#define YKP_MAX_PACKET      1400
#define YKP_MAX_PAYLOAD     (YKP_MAX_PACKET - YKP_HEADER_SIZE - YKP_AUTH_TAG_SIZE)
#define YKP_DEVICE_ID_LEN   8

/* ── FLAGS bitmask ───────────────────────── */
#define YKP_FLAG_QOS_MASK   0x03  /* bits 0-1 */
#define YKP_FLAG_ENCRYPTED  0x04  /* bit 2 */
#define YKP_FLAG_FRAGMENT   0x08  /* bit 3 */
#define YKP_FLAG_ACK        0x10  /* bit 4 */

/* ── QoS levels ──────────────────────────── */
typedef enum {
    YKP_QOS_0 = 0,   /* Fire and forget */
    YKP_QOS_1 = 1,   /* ACK required */
    YKP_QOS_2 = 2,   /* Guaranteed, exactly once */
} ykp_qos_t;

/* ── Route types ─────────────────────────── */
typedef enum {
    ROUTE_DIRECT     = 0x01,
    ROUTE_GROUP      = 0x02,
    ROUTE_BROADCAST  = 0x03,
    ROUTE_LOCATION   = 0x04,
    ROUTE_CAPABILITY = 0x05,
    ROUTE_CLOUD      = 0x06,
    ROUTE_LOCAL      = 0x07,
    ROUTE_GATEWAY    = 0x08,
} ykp_route_t;

/* ── Service IDs ─────────────────────────── */
typedef enum {
    SVC_RELAY      = 0x01,
    SVC_SENSOR     = 0x02,
    SVC_MOTOR      = 0x03,
    SVC_HEALTH     = 0x04,
    SVC_OTA        = 0x05,
    SVC_SECURITY   = 0x06,
    SVC_DISCOVERY  = 0x07,
    SVC_GROUP      = 0x08,
    SVC_AUTOMATION = 0x09,
} ykp_service_t;

/* ── Relay actions ───────────────────────── */
typedef enum {
    RELAY_ON     = 0x01,
    RELAY_OFF    = 0x02,
    RELAY_TOGGLE = 0x03,
    RELAY_STATUS = 0x04,
    RELAY_ACK    = 0x05,
} ykp_relay_action_t;

/* ── Sensor actions ──────────────────────── */
typedef enum {
    SENSOR_REPORT    = 0x01,
    SENSOR_ALERT     = 0x02,
    SENSOR_CALIBRATE = 0x03,
    SENSOR_ACK       = 0x04,
} ykp_sensor_action_t;

/* ── Motor actions ───────────────────────── */
typedef enum {
    MOTOR_START     = 0x01,
    MOTOR_STOP      = 0x02,
    MOTOR_SET_SPEED = 0x03,
    MOTOR_SET_DIR   = 0x04,
    MOTOR_STATUS    = 0x05,
    MOTOR_ACK       = 0x06,
} ykp_motor_action_t;

/* ── Health actions ──────────────────────── */
typedef enum {
    HEALTH_REPORT  = 0x01,
    HEALTH_REQUEST = 0x02,
    HEALTH_ALERT   = 0x03,
} ykp_health_action_t;

/* ── OTA actions ─────────────────────────── */
typedef enum {
    OTA_BEGIN    = 0x01,
    OTA_CHUNK    = 0x02,
    OTA_MISSING  = 0x03,
    OTA_COMPLETE = 0x04,
    OTA_VERIFY   = 0x05,
    OTA_INSTALL  = 0x06,
    OTA_REBOOT   = 0x07,
    OTA_FAIL     = 0x08,
} ykp_ota_action_t;

/* ── Security actions ────────────────────── */
typedef enum {
    SEC_HELLO          = 0x01,
    SEC_CHALLENGE      = 0x02,
    SEC_ECDH_RESPONSE  = 0x03,
    SEC_SESSION_ACTIVE = 0x04,
    SEC_KEY_ROTATE     = 0x05,
    SEC_KEY_ROTATE_ACK = 0x06,
    SEC_SESSION_CLOSE  = 0x07,
    SEC_ERROR          = 0x08,
} ykp_sec_action_t;

/* ── Discovery actions ───────────────────── */
typedef enum {
    DISC_REQUEST  = 0x01,
    DISC_REPLY    = 0x02,
    DISC_REGISTER = 0x03,
} ykp_disc_action_t;

/* ── TLV Types ───────────────────────────── */
typedef enum {
    TLV_DEVICE_ID    = 0x01,
    TLV_TIMESTAMP    = 0x02,
    TLV_STATE        = 0x03,
    TLV_VALUE_INT    = 0x04,
    TLV_VALUE_FLOAT  = 0x05,
    TLV_VALUE_STR    = 0x06,
    TLV_ERROR_CODE   = 0x07,
    TLV_FIRMWARE_VER = 0x08,
    TLV_PUBLIC_KEY   = 0x09,
    TLV_NONCE        = 0x0A,
    TLV_SIGNATURE    = 0x0B,
    TLV_CHUNK_INDEX  = 0x0C,
    TLV_CHUNK_DATA   = 0x0D,
    TLV_HASH         = 0x0E,
    TLV_GROUP_ID     = 0x0F,
    TLV_RULE_ID      = 0x10,
    TLV_CAPABILITIES = 0x11,
    TLV_RSSI         = 0x12,
    TLV_IP_ADDRESS   = 0x13,
    TLV_DIRECTION    = 0x14,
    TLV_SPEED        = 0x15,
} ykp_tlv_type_t;

/* ── Capability bitmask ──────────────────── */
#define CAP_RELAY      (1 << 0)
#define CAP_SENSOR     (1 << 1)
#define CAP_MOTOR      (1 << 2)
#define CAP_DISPLAY    (1 << 3)
#define CAP_BLE        (1 << 4)
#define CAP_GATEWAY    (1 << 5)
#define CAP_OTA        (1 << 6)
#define CAP_AUTOMATION (1 << 7)

/* ── Error codes ─────────────────────────── */
typedef enum {
    YKP_ERR_NONE             = 0x0000,
    YKP_ERR_INVALID_MAGIC    = 0x0001,
    YKP_ERR_INVALID_VERSION  = 0x0002,
    YKP_ERR_AUTH_FAILED      = 0x0010,
    YKP_ERR_SESSION_INVALID  = 0x0011,
    YKP_ERR_REPLAY_DETECTED  = 0x0012,
    YKP_ERR_DECRYPT_FAILED   = 0x0013,
    YKP_ERR_DEVICE_NOT_FOUND = 0x0020,
    YKP_ERR_SERVICE_UNKNOWN  = 0x0030,
    YKP_ERR_OTA_SIG_INVALID  = 0x0050,
    YKP_ERR_OTA_HASH_MISMATCH= 0x0051,
    YKP_ERR_OTA_PARTITION    = 0x0052,
    YKP_ERR_INTERNAL         = 0xFFFF,
} ykp_error_t;

/* ── Timing constants ────────────────────── */
#define YKP_HEALTH_REPORT_INTERVAL_MS    60000   /* 60 seconds */
#define YKP_SESSION_TIMEOUT_MS           3600000 /* 1 hour */
#define YKP_KEY_ROTATE_PACKET_COUNT      1000
#define YKP_KEY_ROTATE_INTERVAL_MS       1800000 /* 30 minutes */
#define YKP_QOS1_RETRY_DELAY_MS          500
#define YKP_QOS1_MAX_RETRIES             3
#define YKP_QOS2_PHASE_TIMEOUT_MS        1000
#define YKP_REPLAY_WINDOW_SIZE           64
#define YKP_DISCOVERY_PORT               4211
#define YKP_CONTROL_PORT                 4210
#define YKP_WS_PATH                      "/ws"

#endif /* YKP_CONSTANTS_H */
