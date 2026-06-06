#include "legacy_udp.h"
#include <stdio.h>
#include <string.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "lwip/sockets.h"
#include "esp_random.h"
#include "psa/crypto.h"
#include "driver/gpio.h"

// Bring in relay service to map commands
#include "../services/relay_service.h"
#include "../ykp_core/ykp_constants.h"
#include "../config/device_config.h"

#define UDP_PORT 3333

// Binary Opcodes from the legacy app
#define CMD_ON    0x01
#define CMD_OFF   0x02
#define CMD_PING  0x03
#define CMD_ACK   0x04
#define CMD_STATE 0x05

static const char *TAG = "legacy_udp";

static unsigned char shared_aes_key[32]; // 256-bit AES Key
static uint32_t current_session_id = 0;  
static uint32_t esp32_tx_counter = 1;    
static uint32_t last_app_rx_counter = 0; 

// ============================================================================
// SECURE NVS KEY STORAGE (Fixes Hardcoded RAM Key)
// ============================================================================
static void load_or_derive_keys(void) {
    psa_status_t status = psa_crypto_init();
    if (status != PSA_SUCCESS) {
        ESP_LOGE(TAG, "[SECURITY] ❌ PSA Crypto Init Failed!");
        return;
    }

    nvs_handle_t my_handle;
    esp_err_t err = nvs_open("storage", NVS_READWRITE, &my_handle);
    size_t required_size = 32;

    err = nvs_get_blob(my_handle, "aes_master", shared_aes_key, &required_size);
    
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "[SECURITY] ✅ Loaded AES Master Key from NVS memory.");
    } else {
        ESP_LOGW(TAG, "[SECURITY] No NVS Key found. Deriving new key via HKDF...");
        
        const unsigned char ikm[] = "Test_Secret_IKM_Phase1_App"; 
        const unsigned char salt[] = "Phase1_Salt";
        const unsigned char info[] = "UDP_AES_GCM_256";

        psa_key_derivation_operation_t operation = PSA_KEY_DERIVATION_OPERATION_INIT;
        psa_key_derivation_setup(&operation, PSA_ALG_HKDF(PSA_ALG_SHA_256));
        
        psa_key_derivation_input_bytes(&operation, PSA_KEY_DERIVATION_INPUT_SALT, salt, strlen((char*)salt));
        psa_key_derivation_input_bytes(&operation, PSA_KEY_DERIVATION_INPUT_SECRET, ikm, strlen((char*)ikm));
        psa_key_derivation_input_bytes(&operation, PSA_KEY_DERIVATION_INPUT_INFO, info, strlen((char*)info));
        psa_key_derivation_output_bytes(&operation, shared_aes_key, 32);
        psa_key_derivation_abort(&operation);

        nvs_set_blob(my_handle, "aes_master", shared_aes_key, 32);
        nvs_commit(my_handle);
        ESP_LOGI(TAG, "[SECURITY] ✅ New AES Master Key saved to NVS.");
    }
    nvs_close(my_handle);
}

// ============================================================================
// SECURE UDP SERVER 
// ============================================================================
static void udp_server_task(void *pvParameters) {
    char rx_buffer[128]; 
    struct sockaddr_in dest_addr;
    dest_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    dest_addr.sin_family = AF_INET;
    dest_addr.sin_port = htons(UDP_PORT);

    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    int broadcast_enable = 1;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &broadcast_enable, sizeof(broadcast_enable));
    bind(sock, (struct sockaddr *)&dest_addr, sizeof(dest_addr));

    ESP_LOGI(TAG, "[SYSTEM] 🔒 SECURE UDP Server Online. Listening on %d...", UDP_PORT);

    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT);
    psa_set_key_algorithm(&attributes, PSA_ALG_GCM);
    psa_set_key_type(&attributes, PSA_KEY_TYPE_AES);
    psa_set_key_bits(&attributes, 256);

    psa_key_id_t key_id;
    psa_import_key(&attributes, shared_aes_key, 32, &key_id);

    uint32_t packet_count = 0;
    uint32_t last_rate_check_ms = pdTICKS_TO_MS(xTaskGetTickCount());
    uint32_t last_activity_ms = last_rate_check_ms;

    while (1) {
        struct sockaddr_storage source_addr;
        socklen_t socklen = sizeof(source_addr);
        int len = recvfrom(sock, rx_buffer, sizeof(rx_buffer) - 1, 0, (struct sockaddr *)&source_addr, &socklen);

        if (len > 0) {
            uint32_t now_ms = pdTICKS_TO_MS(xTaskGetTickCount());
            
            if (now_ms - last_rate_check_ms > 1000) {
                packet_count = 0;
                last_rate_check_ms = now_ms;
            }
            if (packet_count > 20) continue; 
            packet_count++;

            if (now_ms - last_activity_ms > 300000) {
                current_session_id = esp_random();
                last_app_rx_counter = 0;
                ESP_LOGI(TAG, "[SEC] Session expired. New Session generated: 0x%08" PRIx32, current_session_id);
                last_activity_ms = now_ms;
            }

            if (len == 1 && rx_buffer[0] == CMD_PING) {
                uint8_t ack_packet[5];
                ack_packet[0] = CMD_ACK;
                memcpy(&ack_packet[1], &current_session_id, 4);
                sendto(sock, ack_packet, 5, 0, (struct sockaddr *)&source_addr, sizeof(source_addr));
                last_activity_ms = now_ms;
                continue;
            }

            if (len != 25) continue;

            uint32_t incoming_session_id;
            uint32_t incoming_counter;
            memcpy(&incoming_session_id, rx_buffer, 4);
            memcpy(&incoming_counter, rx_buffer + 4, 4);
            
            if (incoming_session_id != current_session_id) continue;
            if (incoming_counter <= last_app_rx_counter && last_app_rx_counter != 0) continue;

            unsigned char iv[12] = {0};
            memcpy(iv, rx_buffer, 8);

            unsigned char plaintext_opcode;
            size_t plaintext_len;

            psa_status_t auth_status = psa_aead_decrypt(
                key_id, PSA_ALG_GCM,
                iv, 12,
                NULL, 0,
                (uint8_t *)(rx_buffer + 8), 17, 
                &plaintext_opcode, 1, &plaintext_len
            );
            
            if (auth_status != PSA_SUCCESS) continue;

            last_app_rx_counter = incoming_counter;
            last_activity_ms = now_ms; 
            
            ESP_LOGI(TAG, "[UDP] 🔓 Decrypted CMD: 0x%02X", plaintext_opcode);

            unsigned char tx_plaintext = 0;
            int prepare_tx = 0;

            switch (plaintext_opcode) {
                case CMD_ON: 
                    relay_service_handle(NULL, 0, RELAY_ON, esp32_tx_counter, current_session_id, "APP");
                    tx_plaintext = CMD_ACK; 
                    prepare_tx = 1; 
                    break;
                case CMD_OFF: 
                    relay_service_handle(NULL, 0, RELAY_OFF, esp32_tx_counter, current_session_id, "APP");
                    tx_plaintext = CMD_ACK; 
                    prepare_tx = 1; 
                    break;
                case CMD_STATE: 
                    tx_plaintext = relay_service_get_state() ? CMD_ON : CMD_OFF; 
                    prepare_tx = 1; 
                    break;
            }

            if (prepare_tx) {
                unsigned char tx_packet[25]; 
                unsigned char tx_iv[12] = {0};
                
                memcpy(tx_packet, &current_session_id, 4);
                memcpy(tx_packet + 4, &esp32_tx_counter, 4);
                memcpy(tx_iv, tx_packet, 8);

                size_t ciphertext_len;
                psa_status_t enc_status = psa_aead_encrypt(
                    key_id, PSA_ALG_GCM,
                    tx_iv, 12,
                    NULL, 0,
                    &tx_plaintext, 1,
                    tx_packet + 8, 17, 
                    &ciphertext_len
                );

                if (enc_status == PSA_SUCCESS) {
                    sendto(sock, tx_packet, 25, 0, (struct sockaddr *)&source_addr, sizeof(source_addr));
                    esp32_tx_counter++; 
                }
            }
        }
    }
}

void legacy_udp_start(void) {
    load_or_derive_keys();
    xTaskCreate(udp_server_task, "legacy_udp", 4096, NULL, 5, NULL);
}
