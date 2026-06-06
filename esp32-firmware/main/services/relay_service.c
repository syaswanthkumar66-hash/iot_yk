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

static void notify_ble_sync(void)
{
    if (ykp_ble_is_connected()) {
        char buf[64];
        snprintf(buf, sizeof(buf), "{\"status\": \"state_changed\", \"relay_state\": %s}", s_state ? "true" : "false");
        ble_notify_status(buf);
    }
}

#include "nvs_flash.h"
#include "esp_system.h"
#include "esp_sleep.h"
#include "driver/rtc_io.h"

static void button_poll_task(void *arg)
{
    bool last_btn_state = true;
    uint32_t press_start_time = 0;
    
    // Tap tracking variables
    uint8_t tap_count = 0;
    uint32_t last_tap_time = 0;

    while (1) {
        bool current_btn_state = gpio_get_level(GPIO_BUTTON_INPUT);

        // 1. Detect Button Press (Falling Edge)
        if (last_btn_state && !current_btn_state) {
            vTaskDelay(pdMS_TO_TICKS(50)); // debounce
            if (gpio_get_level(GPIO_BUTTON_INPUT) == 0) {
                press_start_time = xTaskGetTickCount();
            }
        } 
        // 2. Detect Button Release (Rising Edge)
        else if (!last_btn_state && current_btn_state) {
            vTaskDelay(pdMS_TO_TICKS(50)); // debounce
            if (gpio_get_level(GPIO_BUTTON_INPUT) == 1 && press_start_time > 0) {
                uint32_t press_duration = (xTaskGetTickCount() - press_start_time) * portTICK_PERIOD_MS;
                press_start_time = 0;

                if (press_duration < 5000) {
                    tap_count++;
                    last_tap_time = xTaskGetTickCount();
                    
                    if (tap_count == 1) {
                        // INSTANT SINGLE TAP RESPONSE
                        ESP_LOGI(TAG, "Instant Single Tap. Toggling relay.");
                        s_state = !s_state;
                        gpio_set_level(GPIO_RELAY_OUTPUT, s_state ? 1 : 0);
                        gpio_set_level(GPIO_STATUS_LED,   s_state ? 1 : 0);
                        send_ack(0, 0, "SERVER");
                        notify_ble_sync();
                    }
                }
            }
        } 
        // 3. Detect Long Press while being held down
        else if (!last_btn_state && !current_btn_state) {
            if (press_start_time > 0) {
                uint32_t hold_duration = (xTaskGetTickCount() - press_start_time) * portTICK_PERIOD_MS;
                if (hold_duration >= 5000) {
                    ESP_LOGW(TAG, "5-SECOND LONG PRESS DETECTED! Erasing NVS and Factory Resetting...");
                    for (int i = 0; i < 10; i++) {
                        gpio_set_level(GPIO_STATUS_LED, 1);
                        vTaskDelay(pdMS_TO_TICKS(100));
                        gpio_set_level(GPIO_STATUS_LED, 0);
                        vTaskDelay(pdMS_TO_TICKS(100));
                    }
                    nvs_flash_erase();
                    esp_restart();
                }
            }
        }
        
        // 4. Handle tap timeout (waiting for double tap)
        if (tap_count > 0 && current_btn_state == 1) { // Only if button is released
            uint32_t time_since_last_tap = (xTaskGetTickCount() - last_tap_time) * portTICK_PERIOD_MS;
            
            if (tap_count >= 2) {
                // Double tap executed
                ESP_LOGW(TAG, "DOUBLE TAP DETECTED! Entering Deep Sleep (Shutdown)...");
                tap_count = 0;
                
                // Turn relay off safely before sleeping
                s_state = false;
                gpio_set_level(GPIO_RELAY_OUTPUT, 0);
                gpio_set_level(GPIO_STATUS_LED, 0);
                send_ack(0, 0, "SERVER");
                notify_ble_sync();
                
                vTaskDelay(pdMS_TO_TICKS(500)); // allow network to sync
                
                // Enable wakeup on the exact same button (Active Low)
                rtc_gpio_pullup_en((gpio_num_t)GPIO_BUTTON_INPUT);
                rtc_gpio_pulldown_dis((gpio_num_t)GPIO_BUTTON_INPUT);
                esp_sleep_enable_ext0_wakeup((gpio_num_t)GPIO_BUTTON_INPUT, 0);
                
                // Shutdown device
                esp_deep_sleep_start();
            } 
            else if (time_since_last_tap > 400 && tap_count == 1) {
                // Timeout expired, no second tap came. We already executed Single Tap instantly!
                tap_count = 0;
            }
        }

        last_btn_state = current_btn_state;
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

void relay_service_init(relay_send_fn_t send_fn)
{
    s_send = send_fn;
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));

    // Configure relay output and built-in LED
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

    // Configure button input (pin 0, active low boot button)
    gpio_config_t btn_cfg = {
        .pin_bit_mask = (1ULL << GPIO_BUTTON_INPUT),
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_DISABLE,
    };
    gpio_config(&btn_cfg);

    // Create FreeRTOS polling task
    xTaskCreate(button_poll_task, "btn_poll", 3072, NULL, 3, NULL);

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
    notify_ble_sync();
}

bool relay_service_get_state(void) { return s_state; }
