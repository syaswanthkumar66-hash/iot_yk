#include "nvs_config.h"
#include <string.h>
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"

static const char *TAG = "nvs_config";
static const char *NVS_NAMESPACE = "ykp_config";

bool nvs_config_init(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS partition truncated, erasing...");
        nvs_flash_erase();
        err = nvs_flash_init();
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_flash_init failed: %s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "NVS initialised OK");
    return true;
}

static nvs_handle_t open_nvs(nvs_open_mode_t mode)
{
    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NAMESPACE, mode, &h);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open failed: %s", esp_err_to_name(err));
        return 0;
    }
    return h;
}

bool nvs_config_get_str(const char *key, char *out, size_t max_len)
{
    nvs_handle_t h = open_nvs(NVS_READONLY);
    if (!h) return false;
    size_t len = max_len;
    esp_err_t err = nvs_get_str(h, key, out, &len);
    nvs_close(h);
    return err == ESP_OK;
}

bool nvs_config_set_str(const char *key, const char *value)
{
    nvs_handle_t h = open_nvs(NVS_READWRITE);
    if (!h) return false;
    esp_err_t err = nvs_set_str(h, key, value);
    if (err == ESP_OK) nvs_commit(h);
    nvs_close(h);
    return err == ESP_OK;
}

bool nvs_config_get_blob(const char *key, void *out, size_t *len)
{
    nvs_handle_t h = open_nvs(NVS_READONLY);
    if (!h) return false;
    esp_err_t err = nvs_get_blob(h, key, out, len);
    nvs_close(h);
    return err == ESP_OK;
}

bool nvs_config_set_blob(const char *key, const void *data, size_t len)
{
    nvs_handle_t h = open_nvs(NVS_READWRITE);
    if (!h) return false;
    esp_err_t err = nvs_set_blob(h, key, data, len);
    if (err == ESP_OK) nvs_commit(h);
    nvs_close(h);
    return err == ESP_OK;
}

bool nvs_config_get_u32(const char *key, uint32_t *out)
{
    nvs_handle_t h = open_nvs(NVS_READONLY);
    if (!h) return false;
    esp_err_t err = nvs_get_u32(h, key, out);
    nvs_close(h);
    return err == ESP_OK;
}

bool nvs_config_set_u32(const char *key, uint32_t val)
{
    nvs_handle_t h = open_nvs(NVS_READWRITE);
    if (!h) return false;
    esp_err_t err = nvs_set_u32(h, key, val);
    if (err == ESP_OK) nvs_commit(h);
    nvs_close(h);
    return err == ESP_OK;
}

uint32_t nvs_config_incr_restart_count(void)
{
    uint32_t count = 0;
    nvs_config_get_u32("restart_count", &count);
    count++;
    nvs_config_set_u32("restart_count", count);
    return count;
}

bool nvs_config_is_provisioned(void)
{
    char buf[16];
    return nvs_config_get_str("device_id", buf, sizeof(buf));
}

bool nvs_config_get_device_id(char *out, size_t max_len)
{
    return nvs_config_get_str("device_id", out, max_len);
}

bool nvs_config_get_device_type(char *out, size_t max_len)
{
    return nvs_config_get_str("device_type", out, max_len);
}

bool nvs_config_get_wifi_ssid(char *out, size_t max_len)
{
    return nvs_config_get_str("wifi_ssid", out, max_len);
}

bool nvs_config_get_wifi_password(char *out, size_t max_len)
{
    return nvs_config_get_str("wifi_password", out, max_len);
}

bool nvs_config_get_server_url(char *out, size_t max_len)
{
    if (!nvs_config_get_str("server_url", out, max_len)) {
        strncpy(out, "wss://ykp-router.onrender.com/ws", max_len - 1);
    }
    return true;
}
