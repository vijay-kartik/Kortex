# Native WhatsApp Multi-Device Client (Kotlin) — Design & Research

> Goal: link WhatsApp via QR during onboarding and ingest incoming messages, implemented
> **entirely on-device in Kotlin/Java** — no Node/Baileys bridge, no server. The linked
> session lives in the Android app itself.

## Honest framing (read first)
- There is **no official documentation** — WhatsApp's multi-device protocol is reverse-engineered.
  The authoritative references are mature OSS clients (below), not WhatsApp.
- This is a **large** effort. whatsmeow/Baileys are years of work. We can get to "receive DMs"
  far faster than full parity, but it's still a multi-layer protocol stack.
- **Ban risk:** linking as an unofficial companion device violates WhatsApp ToS; the linked
  number can be banned (reports cite 2–8 week windows for protocol tools). Use a test number.
- It must be **validated against the live protocol**, which means iterative on-device testing
  (frames change; tokens/versions drift). Treat each task below as "build → test on device → fix".

## Reference implementations to mine (our "documentation")
- **whatsmeow** (Go) — cleanest, most complete multi-device reference. Primary source of truth.
- **Baileys** (TS) — second reference; good for ClientPayload, ADV pairing, WABinary tokens.
- **tgalal/consonance** — WhatsApp's Noise handshake in isolation.
- **signalapp/libsignal** — the Signal protocol (X3DH + Double Ratchet); we reuse, not reimplement.
- WhatsApp Security Whitepaper + Meta multi-device engineering post — high-level model.

## Protocol stack (bottom → top) and Kotlin mapping

| Layer | What it is | Kotlin/JVM approach |
|---|---|---|
| **1. Transport** | Persistent `wss://` WebSocket to WhatsApp (`web.whatsapp.com/ws/chat`), framed length-prefixed | **OkHttp** WebSocket (already in deps via Ktor/OkHttp) |
| **2. Noise handshake** | `Noise_XX_25519_AESGCM_SHA256` w/ WA prologue + server-cert verification → encrypted transport | Port whatsmeow `socket/noisehandshake`. Primitives from **libsignal**/**Tink**/**BouncyCastle** (X25519, AES-GCM, SHA256, HKDF). `noise-java` is a possible base but WA's variant (prologue, cert) is custom |
| **3. WABinary** | WhatsApp's binary node format (tag dictionary compression, packed JIDs, protobuf payloads) | Pure-Kotlin codec ported from whatsmeow `binary/` (+ `binary/token` dictionaries). Node model + encoder/decoder |
| **4. Protobuf (WAProto)** | `.proto` schemas: HandshakeMessage, ClientPayload, ADV*, Message, WebMessageInfo… | **Square Wire** (generates Kotlin) or protobuf-javalite. Schemas from whatsmeow `proto/` |
| **5. Registration & pairing** | Generate device identity keys; QR ref handshake; `pair-success`; device signature; persist creds | Kotlin; keys via libsignal/Tink; QR via **ZXing**; creds in Room + Keystore |
| **6. Signal E2E** | Message bodies are Signal-encrypted (`pkmsg`/`msg`, group sender keys) | **libsignal** (`org.signal:libsignal-client` + `libsignal-android`) with our own SignalStore |
| **7. Session runtime** | Login w/ saved creds, keepalive, reconnect, decrypt → emit messages | Foreground Service + coroutines |

## Pairing (QR) flow — multi-device
1. Generate, once: a long-term **identity key** (Curve25519) + **noise key** (Curve25519) + registration id + an **ADV secret**.
2. Connect → Noise handshake → send a **register** `ClientPayload`.
3. Server replies with a `pair-device` IQ containing **refs**. Build the QR string:
   `ref , base64(noisePub) , base64(identityPub) , base64(advSecret)` and render it; **rotate** as refs expire (~20s each).
4. User scans in WhatsApp (*Linked devices → Link a device*; biometric gate on their phone).
5. Server sends **`pair-success`** with the assigned **device JID** + an `ADVSignedDeviceIdentity`
   (signed by the primary's account key). Companion **verifies** the account signature, adds its own
   **device signature** (Curve25519), ACKs, and **persists** the full credential set.
6. Reconnect with a **login** `ClientPayload` (no QR) on every subsequent launch.

## End-to-end decryption
- Each device has its **own identity key**; the server maps account → device list (client-fanout:
  a sender encrypts N times for N devices). We are one of those devices.
- Incoming `<message>` nodes carry Signal ciphertext: `pkmsg` (prekey/X3DH session setup) or `msg`
  (Double Ratchet), and **sender keys** for groups. We hand these to **libsignal** with our store
  → plaintext **WAProto `Message`** → extract text/metadata.
- We must upload **prekeys** after pairing so others can start sessions with this device.

## Integration with Kortex (no pipeline changes)
WhatsApp becomes one `MessageSource`. The decryption pipeline emits a Kortex `Signal`
(`SignalSource("net.whatsapp","WhatsApp")`, `senderHandle = Handle(PHONE, <number from JID>)`,
content, timestamp) into `coordinator.onSignal`. Because identity resolution matches the number to
a saved contact, WhatsApp + SMS collapse into one cross-medium `Conversation` automatically. The
gateway pre-filters to saved contacts, so non-contacts never enter the pipeline.

```
WhatsApp ⇄ [OkHttp WS] ⇄ Noise ⇄ WABinary ⇄ {pairing | messages}
                                              messages → libsignal decrypt → WAProto
                                              → WaGateway → Signal → coordinator.onSignal
runs inside a Foreground Service; creds + Signal store in Room/Keystore
```

## Module layout (proposed)
A new module `:wa` (Android library) to isolate the protocol from `:core-agent`:
```
wa/
  net/        OkHttp WS transport, framing
  noise/      Noise XX handshake + transport cipher
  binary/     WABinary node model, encoder/decoder, token dictionaries, JID
  proto/      Wire-generated WAProto (subset)
  signal/     libsignal store impls (sessions, prekeys, sender keys, identities)
  auth/       key generation, QR/pairing, credential persistence
  client/     WAClient: connect/login, node routing, keepalive, reconnect
  ingest/     WaGateway: decrypted message → Kortex Signal
  service/    Foreground Service + onboarding hooks
```

## Task breakdown (small, each independently testable on device)
Status: **T0–T8 + T10 ✅**, **whole project compiles** (`./gradlew assembleDebug` green).
Cert verification real. WhatsApp is wired into the Kortex pipeline + an onboarding QR screen.
**Not yet validated against the live server.** Remaining: T9 (foreground service for a
persistent connection), delivery receipts, and a real device run.

- **T8 — Gateway + app wiring ✅**: `WaStorage` (Room `KeyValueStore`/`CredentialStore` impls),
  `WhatsAppManager` (runs `WAClient`, exposes QR/state), `WaGateway` (decrypted `proto.Message`
  → Kortex `Signal` → `coordinator.onSignal`), `WhatsAppScreen` (ZXing QR onboarding tab).
  `WAClient` now decrypts `message` nodes and emits via `onMessage`.

- **T7 — Signal decryption ✅** (`signal/`, `store/`): libsignal (`signal-protocol-java`),
  `SignalKeys`, `WaSignalStore` (SignalProtocolStore + SenderKeyStore over a `KeyValueStore`
  SPI), `MessageDecryptor` (pkmsg/msg/skmsg → `proto.Message`), one-time **pre-key upload** on
  login (`WAClient.uploadPreKeysIfNeeded`), and **real cert verification** (`WaCertVerifier`
  over `proto.CertChain` + Curve25519). 
- Build note: the vendored Baileys `WAProto.proto` is proto3 but had enums lacking a zero value;
  a synthetic `AUTO_ZERO_n_UNSPECIFIED = 0` was injected into 23 enums so Wire (strict) accepts it.

- **T5 — Pairing ✅** (`auth/`, `client/`): credentials, ClientPayload, ADV HMAC + account/device
  signatures (`AdvSignatures`), and the QR `pair-device` → `pair-success` flow in `WAClient`.
- **T6 — Login/connect loop ✅** (`client/WAClient`): handshake → read loop → node routing;
  reconnect-with-login after pairing; `success`/`failure` handling.

- **T3 — WABinary codec ✅** (`binary/`): Node/Jid model, decoder, encoder, token tables
  (generated from whatsmeow), and `WaBinary.marshal`/`unmarshal` (incl. zlib unpack).
- **T4 — WAProto via Wire ✅**: vendored `WAProto.proto`, Wire generates `proto.*`.
- **T5 — Pairing (in progress)**: `auth/DeviceCredentials` (key gen + XEdDSA signed prekey),
  `CredentialStore` SPI, `auth/ClientPayloadFactory` (registration + login). Remaining: QR ref
  flow, `pair-success` handling, ADV device signature, persistence.

- **T0 — Transport.** OkHttp WS to the WA endpoint; WAConnHeader + 3-byte framing + reassembly
  (`net/OkHttpFrameTransport`). *Done:* socket opens, frames round-trip.
- **T1 — Crypto primitives.** X25519, AES-256-GCM, HKDF-SHA256, SHA256 (`crypto/Crypto.kt`).
- **T2 — Noise XX handshake.** Full XX sequence → transport cipher (`noise/`). Cert verification
  is a pluggable `CertVerifier` (currently `Noop`; real check lands with libsignal in T7).
- **T3 — WABinary codec.** Node model + encode/decode + token dictionaries + JID packing. *Done:* round-trips captured frames.
- **T4 — WAProto via Wire.** Generate the subset we need. *Done:* compiles; sample bytes parse.
- **T5 — Pairing.** Identity/noise keys, register ClientPayload, QR refs + rotation + render, handle `pair-success`, device signature, persist. *Done:* scanning links the device.
- **T6 — Login & keepalive.** Reconnect with saved creds; `success`; presence; pings; reconnect. *Done:* survives app restart without re-scan.
- **T7 — Signal store + decrypt.** libsignal store in Room; upload prekeys; decrypt `pkmsg`/`msg`. *Done:* read plaintext of an incoming DM.
- **T8 — Gateway → pipeline.** Map decrypted message → `Signal`; saved-contact pre-filter; `coordinator.onSignal`. *Done:* a real WhatsApp DM produces a Kortex card/memory.
- **T9 — Foreground Service.** Persistent connection, reconnection, notification, battery/Doze handling.
- **T10 — Onboarding UI.** "Connect WhatsApp" screen: QR, status, re-link.

## Dependencies to add (when we start)
- `com.squareup.okhttp3:okhttp` (WS) — already transitively present.
- `org.signal:libsignal-client` + `org.signal:libsignal-android` (Signal protocol).
- `com.squareup.wire:wire-runtime` + Wire Gradle plugin (protobuf → Kotlin).
- `com.google.zxing:core` (QR render). Crypto: libsignal covers most; Tink/BouncyCastle if needed.

## Open risks / unknowns to resolve during build
- Exact current Noise prologue + WA version header (drift between versions).
- WABinary token dictionary versioning.
- Prekey upload cadence & `ib`/`notification` node handling.
- Doze/battery limits on a long-lived foreground socket.
