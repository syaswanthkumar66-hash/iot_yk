#include "relay_service.h"
#include "device_config.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "nvs_config.h"
#include <string.h>
#include "driver/gpio.h"
#include "esp_log.h"

static const char *TAG = "relay_svc";

static bool           s_state   = false;
static relay_send_fn_t s_send   = NULL;
static char            s_device_id[9] = {0};

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

void relay_service_init(relay_send_fn_t send_fn)
{
    s_send = send_fn;
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));

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
    ESP_LOGI(TAG, "relay service init OK");
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
}

bool relay_service_get_state(void) { return s_state; }
