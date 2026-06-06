#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initializes the legacy AES-GCM UDP server on port 3333.
 * It will load or derive the `aes_master` key using HKDF and start the UDP listener task.
 */
void legacy_udp_start(void);

#ifdef __cplusplus
}
#endif
