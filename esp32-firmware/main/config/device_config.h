#ifndef DEVICE_CONFIG_H
#define DEVICE_CONFIG_H

/* ═══════════════════════════════════════════════
   YKP Device Hardware Configuration
   Edit GPIO pins here for your board layout
   ═══════════════════════════════════════════════ */

/* ── GPIO Pin Assignments ─────────────────────── */

/* Relay / Switch Output */
#define GPIO_RELAY_OUTPUT       4    /* HIGH=ON, LOW=OFF */
#define GPIO_STATUS_LED         2    /* Built-in LED (active high) */
#define GPIO_BUTTON_INPUT       0    /* Physical button (active low, pull-up) */

/* Sensor Inputs */
#define GPIO_DHT22_DATA         5    /* DHT22 temperature/humidity */
#define GPIO_PIR_MOTION         18   /* PIR motion sensor (active high) */
#define GPIO_LDR_ADC            34   /* Light-dependent resistor (ADC input) */
#define GPIO_GAS_ADC            35   /* MQ gas sensor (ADC input) */

/* Motor Control */
#define GPIO_MOTOR_PWM          15   /* PWM speed control */
#define GPIO_MOTOR_DIR_A        16   /* Direction A (CW) */
#define GPIO_MOTOR_DIR_B        17   /* Direction B (CCW) */

/* Battery ADC */
#define GPIO_BATTERY_ADC        36   /* Battery voltage divider */
#define BATTERY_VOLTAGE_DIVIDER 2.0f /* 100k/100k divider */
#define BATTERY_FULL_MV         4200 /* 4.2V LiPo full */
#define BATTERY_EMPTY_MV        3000 /* 3.0V LiPo empty */

/* ── I2C (for BME280/SSD1306) ─────────────────── */
#define I2C_MASTER_SDA          21
#define I2C_MASTER_SCL          22
#define I2C_MASTER_FREQ_HZ      400000

/* ── UART Debug ───────────────────────────────── */
#define UART_DEBUG_TX           1
#define UART_DEBUG_RX           3

/* ── Network ──────────────────────────────────── */
#define YKP_WS_RECONNECT_MS     5000    /* WebSocket reconnect interval */
#define YKP_WS_PING_INTERVAL_MS 30000   /* WebSocket keepalive ping */
#define YKP_WS_TIMEOUT_MS       10000   /* WebSocket receive timeout */

/* ── Task Stack Sizes ─────────────────────────── */
#define TASK_STACK_WS           8192
#define TASK_STACK_UDP          4096
#define TASK_STACK_HEALTH       3072
#define TASK_STACK_SENSOR       3072
#define TASK_STACK_DISCOVERY    3072
#define TASK_STACK_QOS          3072

/* ── Task Priorities ──────────────────────────── */
#define TASK_PRIO_WS            5
#define TASK_PRIO_UDP           4
#define TASK_PRIO_HEALTH        2
#define TASK_PRIO_SENSOR        2
#define TASK_PRIO_DISCOVERY     1
#define TASK_PRIO_QOS           3

/* ── Firmware Version ─────────────────────────── */
#define YKP_FIRMWARE_VERSION    "1.2.1"
#define YKP_PROTOCOL_VERSION    5

#endif /* DEVICE_CONFIG_H */
