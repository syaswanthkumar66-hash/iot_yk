# YKP v5 IoT Platform — Complete Project

> Universal IoT protocol for ESP32 devices, deployed on Render + Supabase.

---

## 📁 Project Structure

```
iot_yk/
├── esp32-firmware/          # ESP-IDF firmware (C)
│   ├── CMakeLists.txt
│   ├── partitions.csv
│   ├── sdkconfig.defaults
│   └── main/
│       ├── main.c           # App entry point + dispatcher
│       ├── ykp_core/        # Protocol engine
│       │   ├── ykp_constants.h
│       │   ├── ykp_packet.c/h   # Binary packet + TLV
│       │   ├── ykp_security.c/h # ECDH + AES-256-GCM + ECDSA
│       │   ├── ykp_session.c/h  # Auth state machine
│       │   ├── ykp_replay.c/h   # Replay protection
│       │   └── ykp_qos.c/h      # QoS 0/1/2 engine
│       ├── transport/
│       │   ├── ykp_websocket.c/h  # WebSocket client
│       │   └── ykp_udp.c/h        # Local UDP
│       ├── services/
│       │   ├── relay_service.c/h  # GPIO relay control
│       │   ├── sensor_service.c/h # DHT22/PIR/LDR
│       │   ├── health_service.c/h # CPU/heap/RSSI reports
│       │   └── ota_service.c/h    # OTA with SHA256 verify
│       ├── discovery/
│       │   └── ykp_discovery.c/h  # UDP device discovery
│       ├── config/
│       │   ├── nvs_config.c/h     # NVS persistent storage
│       │   └── device_config.h    # GPIO pin assignments
│       └── wifi/
│           └── wifi_manager.c/h   # WiFi STA + auto-reconnect
│
├── render-backend/          # Node.js + TypeScript (Render.com)
│   ├── src/
│   │   ├── server.ts           # Express + WebSocket server
│   │   ├── packet/
│   │   │   ├── constants.ts    # All YKP enums/constants
│   │   │   ├── parser.ts       # Binary packet parser + TLV
│   │   │   └── builder.ts      # Packet builder + TlvBuilder
│   │   ├── router/
│   │   │   ├── ykp-router.ts   # WS connection handler + dispatcher
│   │   │   ├── session-manager.ts  # Device session state
│   │   │   └── route-engine.ts     # Device→WS routing
│   │   ├── security/
│   │   │   ├── ecdh.ts         # ECDH key derivation + HKDF
│   │   │   ├── aes-gcm.ts      # AES-256-GCM encrypt/decrypt
│   │   │   └── replay-guard.ts # 64-bit sliding window
│   │   ├── services/
│   │   │   ├── relay.service.ts   # Relay command + ACK handler
│   │   │   └── health.service.ts  # Health record + alerts
│   │   ├── presence/
│   │   │   └── presence-engine.ts # Online/offline tracking
│   │   ├── db/
│   │   │   └── supabase.ts     # Supabase client + DB helpers
│   │   └── api/
│   │       ├── devices.ts      # GET/POST /api/devices
│   │       ├── health.ts       # GET /api/health/:id
│   │       └── ota.ts          # POST /api/ota
│   ├── supabase_schema.sql     # Run once in Supabase SQL editor
│   ├── package.json
│   ├── tsconfig.json
│   ├── render.yaml
│   └── .env.example
│
└── web-dashboard/           # React + Vite dashboard (Render Static)
    ├── src/
    │   ├── App.jsx            # React Router
    │   ├── components/Layout.jsx
    │   └── pages/
    │       ├── Dashboard.jsx  # Stats + charts + device table
    │       ├── Firmware.jsx   # Download all firmware variants
    │       ├── Devices.jsx    # Device grid + relay toggle
    │       ├── Health.jsx     # CPU/Heap/RSSI/RTT charts
    │       ├── Groups.jsx     # Group control
    │       ├── Automation.jsx # IF/THEN rules
    │       └── OTA.jsx        # OTA manager
    ├── public/firmware/       # Firmware .bin files served here
    │   └── manifest.json
    └── render.yaml
```

---

## 🚀 Quick Start

### 1. Supabase Setup

1. Create a project at [supabase.com](https://supabase.com)
2. Go to **SQL Editor** → run `render-backend/supabase_schema.sql`
3. Copy your **Project URL** and **service_role key**

### 2. Deploy Render Backend

```bash
# Push render-backend/ to GitHub
# Go to render.com → New Web Service → connect repo
# Root directory: render-backend
# Build:  npm install && npm run build
# Start:  npm start
# Set env vars: SUPABASE_URL, SUPABASE_SERVICE_KEY, SERVER_PRIVATE_KEY
```

### 3. Deploy Dashboard

```bash
# render.com → New Static Site → connect same repo
# Root directory: web-dashboard
# Build:  npm install && npm run build
# Publish: dist/
```

### 4. Flash ESP32 Firmware

```bash
# Prerequisites: ESP-IDF v5.x installed
cd esp32-firmware
idf.py set-target esp32
idf.py menuconfig   # Set device_id, WiFi, server_url in NVS

idf.py build
idf.py -p COM3 flash monitor
```

### 5. Provision Device via NVS

After first flash, set NVS keys:
```c
// In idf.py monitor, use nvs_set commands, or
// Use BLE provisioning app (coming in v2)
nvs_config_set_str("device_id",    "SW001");
nvs_config_set_str("device_type",  "switch");
nvs_config_set_str("wifi_ssid",    "YourSSID");
nvs_config_set_str("wifi_password","YourPass");
nvs_config_set_str("server_url",   "wss://ykp-router.onrender.com/ws");
```

---

## 🔐 Security Architecture

```
Device Boot
    │
    ▼
[1] SEC_HELLO  →  server_url WS
    { device_id, ephemeral_pub_key }
    │
    ▼
[2] SEC_CHALLENGE  ←  Server
    { server_ephemeral_pub, nonce }
    │
    ▼
[3] ECDH: shared = d_device × Q_server
    session_key = HKDF(shared, nonce, "ykp-session-v5")
    │
    ▼
[4] SEC_ECDH_RESPONSE  →  Server
    { device_id, ECDSA_sign(nonce) }
    │
    ▼
[5] SEC_SESSION_ACTIVE  ←  Server
    { session_id }
    │
    ▼  ← All packets now AES-256-GCM encrypted
    Services: Relay / Sensor / Health / OTA
```

---

## 📡 YKP Packet Format

```
┌────────────────────────────────────┐
│ MAGIC         2 bytes  0x59 0x4B  │
│ VERSION       1 byte   0x05       │
│ FLAGS         1 byte   QoS+enc    │
│ PACKET_ID     4 bytes  uint32 BE  │
│ SESSION_ID    4 bytes  uint32 BE  │
│ SOURCE_ID     8 bytes  ASCII      │
│ DEST_ID       8 bytes  ASCII      │
│ ROUTE_TYPE    1 byte              │
│ SERVICE_ID    1 byte              │
│ ACTION_ID     1 byte              │
│ PAYLOAD_LEN   2 bytes  uint16 BE  │
│ PAYLOAD       N bytes  TLV        │
│ AUTH_TAG      16 bytes AES-GCM    │
└────────────────────────────────────┘
```

---

## 🛠️ NVS Keys Reference

| Key            | Type   | Required | Description                          |
|----------------|--------|----------|--------------------------------------|
| `device_id`    | string | ✅ Yes   | e.g. `SW001` (max 8 chars)           |
| `device_type`  | string | ✅ Yes   | `switch`/`sensor`/`motor`/`gateway`  |
| `device_name`  | string | No       | Human-readable name                  |
| `wifi_ssid`    | string | ✅ Yes   | WiFi network name                    |
| `wifi_password`| string | ✅ Yes   | WiFi password                        |
| `server_url`   | string | ✅ Yes   | `wss://ykp-router.onrender.com/ws`   |
| `private_key`  | blob   | ✅ Yes   | Device ECDH private key (32 bytes)   |
| `public_key`   | blob   | ✅ Yes   | Device ECDH public key (65 bytes)    |
| `server_pub_key`| blob  | No       | Server ECDH public key               |
| `ota_verify_key`| blob  | No       | Server ECDSA verify key              |
| `restart_count`| u32    | Auto     | Incremented at each boot             |

---

## 📋 GPIO Pin Assignments (edit `device_config.h`)

| Pin  | Function                    |
|------|-----------------------------|
| 4    | Relay output (HIGH=ON)      |
| 2    | Status LED                  |
| 0    | Physical button (active LOW)|
| 5    | DHT22 data                  |
| 18   | PIR motion sensor           |
| 34   | LDR ADC                     |
| 35   | Gas sensor ADC              |
| 15   | Motor PWM                   |
| 16   | Motor direction A           |
| 17   | Motor direction B           |
| 21   | I2C SDA (BME280)            |
| 22   | I2C SCL (BME280)            |

---

## 📊 REST API Endpoints

| Method | Path                        | Description                |
|--------|----------------------------|----------------------------|
| GET    | `/health`                   | Server health check        |
| GET    | `/api/devices`              | List all devices           |
| GET    | `/api/devices/:id`          | Get single device          |
| POST   | `/api/devices/:id/command`  | Send command to device     |
| GET    | `/api/health/:id`           | Latest health snapshot     |
| GET    | `/api/health/:id/history`   | Health history (1-24h)     |
| GET    | `/api/ota`                  | List OTA jobs              |
| POST   | `/api/ota`                  | Create OTA job             |
| POST   | `/api/ota/:jobId/start`     | Send OTA_BEGIN to device   |

---

## 📝 License

MIT — YKP v5 IoT Platform
