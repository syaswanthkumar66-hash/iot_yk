// YKP v5 Protocol Constants

export const YKP_MAGIC = Buffer.from([0x59, 0x4B])
export const YKP_VERSION = 0x05
export const YKP_HEADER_SIZE = 33
export const YKP_AUTH_TAG_SIZE = 16
export const YKP_MIN_PACKET = YKP_HEADER_SIZE + YKP_AUTH_TAG_SIZE
export const YKP_DEVICE_ID_LEN = 8

export const FLAGS = {
  QOS_MASK:  0x03,
  ENCRYPTED: 0x04,
  FRAGMENT:  0x08,
  ACK:       0x10,
} as const

export enum QoS {
  QOS_0 = 0,
  QOS_1 = 1,
  QOS_2 = 2,
}

export enum RouteType {
  DIRECT     = 0x01,
  GROUP      = 0x02,
  BROADCAST  = 0x03,
  LOCATION   = 0x04,
  CAPABILITY = 0x05,
  CLOUD      = 0x06,
  LOCAL      = 0x07,
  GATEWAY    = 0x08,
}

export enum ServiceId {
  RELAY      = 0x01,
  SENSOR     = 0x02,
  MOTOR      = 0x03,
  HEALTH     = 0x04,
  OTA        = 0x05,
  SECURITY   = 0x06,
  DISCOVERY  = 0x07,
  GROUP      = 0x08,
  AUTOMATION = 0x09,
}

export enum RelayAction {
  ON     = 0x01,
  OFF    = 0x02,
  TOGGLE = 0x03,
  STATUS = 0x04,
  ACK    = 0x05,
}

export enum HealthAction {
  REPORT  = 0x01,
  REQUEST = 0x02,
  ALERT   = 0x03,
}

export enum SensorAction {
  REPORT    = 0x01,
  ALERT     = 0x02,
  CALIBRATE = 0x03,
  ACK       = 0x04,
}

export enum OtaAction {
  BEGIN    = 0x01,
  CHUNK    = 0x02,
  MISSING  = 0x03,
  COMPLETE = 0x04,
  VERIFY   = 0x05,
  INSTALL  = 0x06,
  REBOOT   = 0x07,
  FAIL     = 0x08,
}

export enum SecAction {
  HELLO          = 0x01,
  CHALLENGE      = 0x02,
  ECDH_RESPONSE  = 0x03,
  SESSION_ACTIVE = 0x04,
  KEY_ROTATE     = 0x05,
  KEY_ROTATE_ACK = 0x06,
  SESSION_CLOSE  = 0x07,
  ERROR          = 0x08,
}

export enum TlvType {
  DEVICE_ID    = 0x01,
  TIMESTAMP    = 0x02,
  STATE        = 0x03,
  VALUE_INT    = 0x04,
  VALUE_FLOAT  = 0x05,
  VALUE_STR    = 0x06,
  ERROR_CODE   = 0x07,
  FIRMWARE_VER = 0x08,
  PUBLIC_KEY   = 0x09,
  NONCE        = 0x0A,
  SIGNATURE    = 0x0B,
  CHUNK_INDEX  = 0x0C,
  CHUNK_DATA   = 0x0D,
  HASH         = 0x0E,
  GROUP_ID     = 0x0F,
  RULE_ID      = 0x10,
  CAPABILITIES = 0x11,
  RSSI         = 0x12,
  IP_ADDRESS   = 0x13,
  DIRECTION    = 0x14,
  SPEED        = 0x15,
}

export const YKP_ERRORS = {
  NONE:             0x0000,
  INVALID_MAGIC:    0x0001,
  INVALID_VERSION:  0x0002,
  AUTH_FAILED:      0x0010,
  SESSION_INVALID:  0x0011,
  REPLAY_DETECTED:  0x0012,
  DECRYPT_FAILED:   0x0013,
  DEVICE_NOT_FOUND: 0x0020,
  DEVICE_OFFLINE:   0x0021,
  SERVICE_UNKNOWN:  0x0030,
  OTA_SIG_INVALID:  0x0050,
  OTA_HASH_MISMATCH:0x0051,
} as const
