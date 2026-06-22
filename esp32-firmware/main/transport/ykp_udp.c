#include "ykp_udp.h"
#include "device_config.h"
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "ykp_udp";

static int              s_sock       = -1;
static uint16_t         s_port       = 4210;
static ykp_udp_rx_cb_t  s_rx_cb      = NULL;
static bool             s_rx_running = false;

bool ykp_udp_init(uint16_t port)
{
    s_port = port;
    s_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s_sock < 0) {
        ESP_LOGE(TAG, "socket create failed");
        return false;
    }

    /* Allow broadcast */
    int bcast = 1;
    setsockopt(s_sock, SOL_SOCKET, SO_BROADCAST, &bcast, sizeof(bcast));

    /* Bind to port */
    struct sockaddr_in addr = {
        .sin_family      = AF_INET,
        .sin_addr.s_addr = htonl(INADDR_ANY),
        .sin_port        = htons(port),
    };
    if (bind(s_sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        ESP_LOGE(TAG, "bind failed on port %u", port);
        close(s_sock);
        s_sock = -1;
        return false;
    }

    ESP_LOGI(TAG, "UDP bound on port %u", port);
    return true;
}

bool ykp_udp_send(const char *dest_ip, uint16_t dest_port,
                  const uint8_t *data, uint16_t len)
{
    if (s_sock < 0) return false;
    struct sockaddr_in dest = {
        .sin_family = AF_INET,
        .sin_port   = htons(dest_port),
    };
    inet_aton(dest_ip, &dest.sin_addr);
    int sent = sendto(s_sock, data, len, 0, (struct sockaddr *)&dest, sizeof(dest));
    return sent == len;
}

bool ykp_udp_broadcast(uint16_t dest_port, const uint8_t *data, uint16_t len)
{
    return ykp_udp_send("255.255.255.255", dest_port, data, len);
}

void ykp_udp_register_rx_cb(ykp_udp_rx_cb_t cb)
{
    s_rx_cb = cb;
}

static void udp_rx_task(void *arg)
{
    uint8_t buf[1400];
    struct sockaddr_in from;
    socklen_t from_len = sizeof(from);

    while (s_rx_running) {
        int len = recvfrom(s_sock, buf, sizeof(buf), 0,
                           (struct sockaddr *)&from, &from_len);
        if (len > 0 && s_rx_cb) {
            s_rx_cb(buf, (uint16_t)len, &from);
        }
    }
    vTaskDelete(NULL);
}

#include "health_service.h"

void ykp_udp_start_rx_task(void)
{
    s_rx_running = true;
    xTaskCreatePinnedToCore(udp_rx_task, "udp_rx", TASK_STACK_UDP, NULL, TASK_PRIO_UDP, NULL, 1);
}

void ykp_udp_stop(void)
{
    s_rx_running = false;
    if (s_sock >= 0) { close(s_sock); s_sock = -1; }
}
