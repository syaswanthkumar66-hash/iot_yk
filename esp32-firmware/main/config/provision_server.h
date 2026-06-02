#ifndef PROVISION_SERVER_H
#define PROVISION_SERVER_H

#include <stdbool.h>

/**
 * @brief Start SoftAP hotspot and HTTP provisioning server.
 *
 * SoftAP SSID:     YKP-Setup-XXXXXX  (last 3 MAC bytes)
 * SoftAP Password: ykpsetup123
 * Device IP:       192.168.4.1
 *
 * Endpoints:
 *   GET  /ping       → {"status":"ok","mode":"provisioning"}
 *   POST /provision  → receives JSON, saves to NVS, restarts device
 *
 * @return true on success
 */
bool provision_server_start(void);

/**
 * @brief Stop HTTP server and SoftAP WiFi.
 */
void provision_server_stop(void);

#endif /* PROVISION_SERVER_H */
