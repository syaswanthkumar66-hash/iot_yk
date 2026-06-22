#include "ykp_discovery.h"
#include "ykp_packet.h"
#include "ykp_constants.h"
#include "ykp_udp.h"
#include "nvs_config.h"
#include "device_config.h"
#include "wifi_manager.h"
#include <string.h>
#include <arpa/inet.h>
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "discovery";
static char s_device_id[9]   = {0};
static char s_device_type[16] = {0};
static char s_device_name[32] = {0};

void ykp_discovery_init(void)
{
    nvs_config_get_device_id(s_device_id, sizeof(s_device_id));
    nvs_config_get_device_type(s_device_type, sizeof(s_device_type));
    if (!nvs_config_get_str("device_name", s_device_name, sizeof(s_device_name))) {
        snprintf(s_device_name, sizeof(s_device_name), "YKP-%s", s_device_id);
    }
    ESP_LOGI(TAG, "discovery init: id=%s type=%s", s_device_id, s_device_type);
}

void ykp_discovery_handle(const uint8_t *data, uint16_t len,
                           struct sockaddr_in *from)
{
    /* Validate it's a DISCOVER request */
    ykp_packet_t pkt = {0};
    if (!ykp_packet_parse(data, len, &pkt)) return;
    if (pkt.header.service_id != SVC_DISCOVERY ||
        pkt.header.action_id  != DISC_REQUEST) {
        if (pkt.payload) free(pkt.payload);
        return;
    }
    if (pkt.payload) free(pkt.payload);

    /* Build DISC_REPLY packet */
    char ip[20] = {0};
    wifi_manager_get_ip(ip, sizeof(ip));

    uint8_t tlv_buf[200];
    ykp_tlv_builder_t b;
    ykp_tlv_builder_init(&b, tlv_buf, sizeof(tlv_buf));
    ykp_tlv_add_string(&b, TLV_DEVICE_ID,    s_device_id);
    ykp_tlv_add_string(&b, TLV_VALUE_STR,    s_device_name);
    ykp_tlv_add_string(&b, TLV_VALUE_STR,    s_device_type);
    ykp_tlv_add_string(&b, TLV_FIRMWARE_VER, YKP_FIRMWARE_VERSION);
    uint32_t ip_raw;
    inet_pton(AF_INET, ip, &ip_raw);
    ykp_tlv_add_uint32(&b, TLV_IP_ADDRESS,   ip_raw);
    ykp_tlv_add_uint8(&b,  TLV_CAPABILITIES, CAP_RELAY | CAP_OTA);

    ykp_packet_t *reply = ykp_packet_alloc();
    if (!reply) return;
    ykp_packet_set_header(reply, 0, 0,
                          s_device_id, "DISC",
                          ROUTE_DIRECT, SVC_DISCOVERY, DISC_REPLY,
                          YKP_QOS_0, false);
    ykp_packet_set_payload(reply, tlv_buf, ykp_tlv_builder_len(&b));

    uint8_t raw[300];
    uint16_t raw_len = ykp_packet_serialise(reply, raw, sizeof(raw));
    ykp_packet_free(reply);

    if (raw_len > 0) {
        char from_ip[20];
        inet_ntop(AF_INET, &from->sin_addr, from_ip, sizeof(from_ip));
        ykp_udp_send(from_ip, ntohs(from->sin_port), raw, raw_len);
        ESP_LOGI(TAG, "sent DISC_REPLY to %s", from_ip);
    }
}

static void discovery_task(void *arg)
{
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(60000));
    }
}

#include "health_service.h"

void ykp_discovery_start_task(void)
{
    xTaskCreatePinnedToCore(discovery_task, "discovery", TASK_STACK_DISCOVERY, NULL, TASK_PRIO_DISCOVERY, NULL, 1);
}
