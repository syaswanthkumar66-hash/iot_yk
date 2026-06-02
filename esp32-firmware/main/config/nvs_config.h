#ifndef NVS_CONFIG_H
#define NVS_CONFIG_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/* ═══════════════════════════════════════════════
   NVS Configuration — Persistent Device Storage
   Namespace: "ykp_config"
   ═══════════════════════════════════════════════ */

/**
 * @brief Initialise NVS flash — call once at boot.
 */
bool nvs_config_init(void);

/**
 * @brief Get a string value from NVS.
 */
bool nvs_config_get_str(const char *key, char *out, size_t max_len);

/**
 * @brief Set a string value in NVS.
 */
bool nvs_config_set_str(const char *key, const char *value);

/**
 * @brief Get a binary blob from NVS.
 */
bool nvs_config_get_blob(const char *key, void *out, size_t *len);

/**
 * @brief Set a binary blob in NVS.
 */
bool nvs_config_set_blob(const char *key, const void *data, size_t len);

/**
 * @brief Get a uint32 from NVS.
 */
bool nvs_config_get_u32(const char *key, uint32_t *out);

/**
 * @brief Set a uint32 in NVS.
 */
bool nvs_config_set_u32(const char *key, uint32_t val);

/**
 * @brief Increment restart_count in NVS and return new value.
 */
uint32_t nvs_config_incr_restart_count(void);

/**
 * @brief Check if device is provisioned (has device_id set).
 */
bool nvs_config_is_provisioned(void);

/* ── Device Config Helpers ───────────────────── */

/**
 * @brief Read device_id from NVS (max 8 chars + null).
 */
bool nvs_config_get_device_id(char *out, size_t max_len);

/**
 * @brief Read device_type from NVS.
 */
bool nvs_config_get_device_type(char *out, size_t max_len);

/**
 * @brief Read WiFi SSID from NVS.
 */
bool nvs_config_get_wifi_ssid(char *out, size_t max_len);

/**
 * @brief Read WiFi password from NVS.
 */
bool nvs_config_get_wifi_password(char *out, size_t max_len);

/**
 * @brief Read server WebSocket URL from NVS.
 */
bool nvs_config_get_server_url(char *out, size_t max_len);

#endif /* NVS_CONFIG_H */
