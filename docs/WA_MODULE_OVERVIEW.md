# WhatsApp Modules (`:wa`, `:wa-ui`) — Overview & Roadmap

A native WhatsApp Web client written in Kotlin — the same thing libraries like
[whatsmeow](https://github.com/tulir/whatsmeow) (Go) or Baileys (JS) do; the protocol
layers here are largely a faithful port of whatsmeow. The phone stays the "primary"
device; this app pairs as a **linked device** via QR scan, exactly like WhatsApp Web
in a browser.

## What's implemented (layers, bottom to top)

1. **Transport** (`wa/net/`) — a WebSocket connection to WhatsApp's servers (OkHttp),
   with WhatsApp's custom framing (length-prefixed binary frames).

2. **Noise Protocol** (`wa/noise/`) — WhatsApp encrypts the *connection itself*
   (separate from message encryption) using the Noise XX handshake: an elliptic-curve
   key exchange (Curve25519) that ends with both sides holding AES-GCM keys, so every
   frame after the handshake is encrypted.

3. **Binary XML codec** (`wa/binary/`) — WhatsApp doesn't send JSON; it sends XML-like
   "nodes" compressed with a dictionary of ~800 known tokens. Encoder, decoder, and the
   token tables are all implemented here.

4. **QR pairing + auth** (`wa/auth/`, parts of `wa/client/`) — the full linked-device
   dance: generate keys, render QR codes, phone scans, verify/sign the device identity
   (ADV signatures), persist credentials, reconnect as logged-in.

5. **Signal end-to-end encryption** (`wa/signal/`) — the actual message crypto, using
   libsignal: 1-on-1 sessions (X3DH + Double Ratchet), group messages (SenderKey),
   pre-key management, and retry receipts when decryption fails.

6. **Session / lifecycle** (`wa/session/`) — `WhatsAppManager` owns everything: a Room
   database for keys/state, a foreground service so Android doesn't kill the
   connection, reconnect logic, and a clean callback API so the SDK doesn't depend on
   the rest of Kortex (host meaning stays in `:app` behind a callback).

7. **`:wa-ui`** — a single Compose screen (QR display, connection state, message log).

## How hard was this?

Genuinely hard — one of the harder client-protocol projects you can attempt. Nothing
here is documented by WhatsApp; it's all reverse-engineered knowledge (via whatsmeow).
Getting it wrong at *any* layer — one wrong byte in the handshake, one mis-ordered
decrypt — means silent failures. The trickiest parts are already solved: the Noise
handshake, the pairing cryptography, and group decryption ordering (the sender-key
distribution message must be installed before the group `skmsg` in the same packet
is attempted).

## Next tasks

Roughly in priority order:

- [ ] **Sending messages** — the biggest gap: the client is currently receive-only.
      Needs the encrypt side of Signal: fetch recipients' pre-keys, encrypt per-device,
      sender-key distribution for groups.
- [ ] **Server cert verification** — `CertVerifier.Noop` is a TODO; the Noise cert
      chain isn't actually verified yet. This is a real security gap, not a nice-to-have.
- [ ] **Media** — download + decrypt of images, voice notes, documents (media has its
      own encryption keys plus a CDN download flow).
- [ ] **History sync** — currently only messages arriving while connected are seen;
      no import of existing chats.
- [ ] **Smaller items** — presence/read receipts beyond delivery acks, contact and
      group metadata, app-state sync (archived, pinned, muted, etc.).
