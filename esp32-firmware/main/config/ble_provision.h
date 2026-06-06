#ifndef BLE_PROVISION_H
#define BLE_PROVISION_H

#include <stdbool.h>

/**
 * @brief Start BLE provisioning
 * 
 * Configures a direct NimBLE (NUS-style) service to receive Wi-Fi credentials
 * and custom device configurations (server URL, device type, device ID, etc.).
 * 
 * @return true if successfully started
 * @return false on error
 */
bool ykp_ble_provision_start(void);

/**
 * @brief Wait for the provisioning process to complete (credentials saved)
 */
void ykp_ble_provision_wait(void);

void ble_notify_status(const char* status_msg);

bool ykp_ble_provision_stop(void);
bool ykp_ble_is_active(void);
bool ykp_ble_is_connected(void);

bool ykp_ble_provision_is_success(void);

#endif /* BLE_PROVISION_H */
