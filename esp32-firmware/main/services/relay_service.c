#include "relay_service.h"
#include "device_config.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "nvs_config.h"
#include <string.h>
#include "ble_provision.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/timers.h"
#include "ykp_session.h"

static const char *TAG = "relay_svc";

static bool            s_state   = false;
static relay_send_fn_t s_send    = NULL;
static char            s_device_id[9] = {0};
static TimerHandle_t   s_btn_debounce_timer = NULL;
/* H4 fix: keep reference to session to generate valid packet_id on button press */
static ykp_session_t  *s_session  = NULL;

static void send_ack(uint32_t packet_id, uint32_t session_id, const char *dest_id)
{
    uint8_t tlv_buf[64];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));
    ykp_tlv_add_uint8(&b, TLV_STATE, s_state ? 1 : 0);

    ykp_packet_t *pkt = ykp_packet_alloc();
    if (!pkt) return;

    ykp_packet_set_header(pkt, packet_id, session_id,
                          s_device_id, dest_id,
                          ROUTE_DIRECT, SVC_RELAY, RELAY_ACK,
                          YKP_QOS_1, false);
    ykp_packet_set_payload(pkt, tlv_buf, ykp_tlv_builder_len(&b));

    uint8_t raw[256];
    uint16_t raw_len = ykp_packet_serialise(pkt, raw, sizeof(raw));
    if (raw_len > 0 && s_send) s_send(raw, raw_len);
    ykp_packet_free(pkt);
}

static void notify_ble_sync(void)
{
    if (ykp_ble_is_connected()) {
        char buf[64];
        snprintf(buf, sizeof(buf), "{\"status\": \"state_changed\", \"relay_state\": %s}", s_state ? "true" : "false");
        ble_notify_status(buf);
    }
}

/* Button interrupt and debounce timer callback */
static void IRAM_ATTR button_isr_handler(void *arg)
{
    gpio_intr_disable(GPIO_BUTTON_INPUT);
    BaseType_t xHigherPriorityTaskWoken = pdFALSE;
    xTimerStartFromISR(s_btn_debounce_timer, &xHigherPriorityTaskWoken);
    if (xHigherPriorityTaskWoken) {
        portYIELD_FROM_ISR();
    }
}

static void button_debounce_timer_callback(TimerHandle_t xTimer)
{
    if (gpio_get_level(GPIO_BUTTON_INPUT) == 0) {
        ESP_LOGI(TAG, "Button press verified via ISR -> toggling relay");
        s_state = !s_state;
        gpio_set_level(GPIO_RELAY_OUTPUT, s_state ? 1 : 0);
        gpio_set_level(GPIO_STATUS_LED,   s_state ? 1 : 0);
        /* H4 fix: use session's packet counter and session_id, not hardcoded zeros */
        uint32_t pkt_id  = s_session ? ykp_session_next_packet_id(s_session) : 0;
        uint32_t sess_id = s_session ? s_session->session_id : 0;
        send_ack(pkt_id, sess_id, "SERVER");
        notify_ble_sync();
    }
    gpio_intr_enable(GPIO_BUTTON_INPUT);
}

void relay_service_init(relay_send_fn_t send_fn, ykp_session_t *session)
{
    s_send    = send_fn;
    s_session = session;  /* H4 fix: store session reference */
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));

    /* Configure relay output and built-in LED */
    gpio_config_t io_cfg = {
        .pin_bit_mask = (1ULL << GPIO_RELAY_OUTPUT) | (1ULL << GPIO_STATUS_LED),
        .mode         = GPIO_MODE_OUTPUT,
        .pull_up_en   = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_DISABLE,
    };
    gpio_config(&io_cfg);
    gpio_set_level(GPIO_RELAY_OUTPUT, 0);
    gpio_set_level(GPIO_STATUS_LED,   0);

    /* Configure button input with falling-edge interrupt */
    gpio_config_t btn_cfg = {
        .pin_bit_mask = (1ULL << GPIO_BUTTON_INPUT),
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_NEGEDGE,
    };
    gpio_config(&btn_cfg);

    /* Create debounce timer (50 ms oneshot) */
    s_btn_debounce_timer = xTimerCreate("btn_deb", pdMS_TO_TICKS(50), pdFALSE, NULL, button_debounce_timer_callback);

    /* Register GPIO interrupt handler — use ESP_INTR_FLAG_IRAM for ISR in IRAM */
    gpio_install_isr_service(ESP_INTR_FLAG_IRAM);
    gpio_isr_handler_add(GPIO_BUTTON_INPUT, button_isr_handler, NULL);

    ESP_LOGI(TAG, "relay service (ISR interrupt-driven) init OK");
}

void relay_service_handle(const uint8_t *payload, uint16_t len,
                           uint8_t action_id, uint32_t packet_id,
                           uint32_t session_id, const char *dest_id)
{
    switch ((ykp_relay_action_t)action_id) {
        case RELAY_ON:
            s_state = true;
            gpio_set_level(GPIO_RELAY_OUTPUT, 1);
            gpio_set_level(GPIO_STATUS_LED,   1);
            ESP_LOGI(TAG, "RELAY ON");
            break;

        case RELAY_OFF:
            s_state = false;
            gpio_set_level(GPIO_RELAY_OUTPUT, 0);
            gpio_set_level(GPIO_STATUS_LED,   0);
            ESP_LOGI(TAG, "RELAY OFF");
            break;

        case RELAY_TOGGLE:
            s_state = !s_state;
            gpio_set_level(GPIO_RELAY_OUTPUT, s_state ? 1 : 0);
            gpio_set_level(GPIO_STATUS_LED,   s_state ? 1 : 0);
            ESP_LOGI(TAG, "RELAY TOGGLE → %s", s_state ? "ON" : "OFF");
            break;

        case RELAY_STATUS:
            ESP_LOGI(TAG, "RELAY STATUS requested → %s", s_state ? "ON" : "OFF");
            break;

        default:
            ESP_LOGW(TAG, "unknown relay action: 0x%02X", action_id);
            return;
    }
    send_ack(packet_id, session_id, dest_id);
    notify_ble_sync();
}

bool relay_service_get_state(void) { return s_state; }
