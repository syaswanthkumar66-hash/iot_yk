#ifndef BLE_PROVISION_H
#define BLE_PROVISION_H

#include <stdbool.h>

/**
 * @brief Start BLE provisioning
 * 
 * Configures the network_provisioning manager with PROV_TRANSPORT_BLE using NimBLE.
 * Registers a custom endpoint "ykp-config" to receive device configuration
 * (server URL, device type) from the mobile app.
 * 
 * @return true if successfully started
 * @return false on error
 */
bool ykp_ble_provision_start(void);

#endif /* BLE_PROVISION_H */
