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

#endif /* BLE_PROVISION_H */
