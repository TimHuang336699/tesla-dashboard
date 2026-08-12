# BLE Protocol

[中文](BLE-Protocol_zh.md)

## Overview

Tesla Dashboard implements Tesla's vehicle-command BLE protocol for direct vehicle communication. This document describes the protocol implementation.

## Protocol Stack

```
┌─────────────────────────────────────┐
│        Application Layer            │
│  VehicleData / VehicleCommand       │
├─────────────────────────────────────┤
│        Crypto Layer                 │
│  ECDH + AES-GCM + TLV              │
├─────────────────────────────────────┤
│        Message Layer                │
│  RoutableMessage / UnsignedMessage  │
├─────────────────────────────────────┤
│        Transport Layer              │
│  GATT Characteristics (TX/RX)       │
└─────────────────────────────────────┘
```

## BLE GATT Services

| UUID | Name | Purpose |
|------|------|---------|
| `00000211-b2d1-43f0-9b88-960cebf8b91e` | Tesla Service | Main BLE service |
| `00000212-b2d1-43f0-9b88-960cebf8b91e` | TX Characteristic | Client → Vehicle |
| `00000213-b2d1-43f0-9b88-960cebf8b91e` | RX Characteristic | Vehicle → Client (Notify) |

## Vehicle Discovery

Tesla vehicles broadcast their BLE name using a hash of the VIN:

```
Local Name = "S" + SHA1(VIN)[0:8].hex() + "C"
```

Example: VIN `5YJS0000000000000` → `S1a87a5a75f3df858C`

## Dual-Domain Architecture

The protocol uses two separate encrypted sessions over one GATT connection:

### VCSEC Domain (Vehicle Security)

| Feature | Description |
|---------|-------------|
| Domain ID | `2` |
| Purpose | Security operations |
| Commands | Wake, Lock, Unlock, Frunk/Trunk |

### Infotainment Domain

| Feature | Description |
|---------|-------------|
| Domain ID | `3` |
| Purpose | Vehicle data |
| Commands | GetVehicleState |

## Message Format

### RoutableMessage (Top Level)

```protobuf
message RoutableMessage {
    Destination to_destination = 6;
    Destination from_destination = 7;
    bytes protobuf_message_as_bytes = 10;
    SignatureData signature_data = 13;
    SessionInfoRequest session_info_request = 14;
    SessionInfo session_info = 15;
    bytes uuid = 51;
    uint32 flags = 52;
}
```

### SessionInfoRequest (Handshake)

```protobuf
message SessionInfoRequest {
    bytes public_key = 1;  // 65-byte uncompressed EC point
}
```

### SessionInfo (Handshake Response)

```protobuf
message SessionInfo {
    uint32 counter = 1;
    bytes public_key = 2;  // Vehicle's 65-byte public key
    bytes epoch = 3;       // 16-byte epoch ID
    uint32 clock_time = 4;
}
```

## Encryption Flow

### 1. ECDH Key Agreement

```
Client                          Vehicle
  │                               │
  │  SessionInfoRequest           │
  │  { public_key: client_pub }   │
  │──────────────────────────────▶│
  │                               │
  │  SessionInfo                  │
  │  { public_key: vehicle_pub,   │
  │    epoch, counter }           │
  │◀──────────────────────────────│
  │                               │
  shared_key = SHA1(ECDH(client_priv, vehicle_pub))[0:16]
```

### 2. Command Encryption

```
1. Increment counter
2. nonce = epoch[0:8] || counter (4 bytes big-endian)
3. Build TLV AAD:
   - signature_type: AES_GCM_PERSONALIZED (5)
   - domain: target domain
   - personalization: VIN
   - epoch: session epoch
   - expires_at: current_time + 10s
   - counter: current counter
   - flags: 0
4. Encrypt: AES-GCM(key=shared_key, nonce, plaintext, aad)
5. Build SignatureData: { epoch, nonce, counter, expires_at, tag }
6. Send RoutableMessage { ciphertext, signature_data }
```

### 3. Response Decryption

```
1. Extract SignatureData from response
2. Parse AES_GCM_Personalized_Signature_Data:
   - epoch, nonce, counter, tag
3. Build response AAD (similar to command)
4. Decrypt: AES-GCM(key=shared_key, nonce, ciphertext, tag, aad)
5. Parse plaintext protobuf
```

## Message Framing

BLE messages are framed with a 2-byte length prefix:

```
┌──────────────┬────────────────────┐
│ Length (2B)  │ Payload            │
│ Big-endian   │ (protobuf bytes)   │
└──────────────┴────────────────────┘
```

Messages larger than MTU are chunked:

```
Chunk size = MTU - ATT_HEADER_SIZE (3)
```

## Polling Sequence

```
┌─────────────────────────────────────────────┐
│ 1. Load private key from KeyStore           │
├─────────────────────────────────────────────┤
│ 2. Connect (cached address or scan)         │
├─────────────────────────────────────────────┤
│ 3. VCSEC Handshake                          │
│    - Send SessionInfoRequest                │
│    - Receive SessionInfo                    │
│    - Compute shared key                     │
├─────────────────────────────────────────────┤
│ 4. Wake Vehicle                             │
│    - RKE_ACTION_WAKE_VEHICLE (30)           │
├─────────────────────────────────────────────┤
│ 5. Infotainment Handshake                   │
│    - New SessionInfoRequest                 │
│    - New shared key                         │
├─────────────────────────────────────────────┤
│ 6. GetVehicleState                          │
│    - Send empty Action                      │
│    - Receive encrypted response             │
│    - Decrypt and parse                      │
├─────────────────────────────────────────────┤
│ 7. Disconnect                               │
└─────────────────────────────────────────────┘
```

## Vehicle Control Commands

### Unlock

```kotlin
RKE_ACTION_UNLOCK = 0
// UnsignedMessage { rke_action: 0 }
```

### Lock

```kotlin
RKE_ACTION_LOCK = 1
// UnsignedMessage { rke_action: 1 }
```

### Open Frunk

```kotlin
CLOSURE_FRUNK = 1
CLOSURE_ACTION_OPEN = 2
// UnsignedMessage { closure_move_request: { closure: 1, action: 2 } }
```

### Open Trunk

```kotlin
CLOSURE_TRUNK = 2
CLOSURE_ACTION_OPEN = 2
// UnsignedMessage { closure_move_request: { closure: 2, action: 2 } }
```

## Timing Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| Poll interval | 5s | Normal polling |
| Driving interval | 2.5s | When vehicle is moving |
| Connect timeout | 10s | GATT connection |
| Scan timeout | 15s | BLE scan |
| Command timeout | 5s | Single command |
| NFC timeout | 30s | Pairing confirmation |
| Max backoff | 30s | Exponential backoff ceiling |

## Error Handling

### Connection Failures

- Cached address fails → Full scan
- Scan timeout → Emit stale data
- GATT disconnect → Emit stale data

### Crypto Failures

- Public key mismatch → Abort (possible MITM)
- Decryption failure → Log and skip
- Counter replay → Vehicle rejects

### Exponential Backoff

```
Attempt 1: 5s
Attempt 2: 10s
Attempt 3: 20s
Attempt 4+: 30s (ceiling)
```

## Security Features

1. **ECDH Key Agreement** — Shared secret never transmitted
2. **AES-GCM Encryption** — Authenticated encryption with 128-bit key
3. **Counter Anti-Replay** — Monotonically increasing counter
4. **Public Key Pinning** — Vehicle's public key stored locally
5. **Private Key Envelope** — Android Keystore AES-256-GCM protection

See [Security](Security.md) for details.
