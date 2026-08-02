# TV Remote

An Android phone app that controls an Android TV over two independent transports: **ADB over
Wi-Fi** and **Bluetooth HID**. The remote screen does not know which one is live — both implement
the same `RemoteTransport` interface, so the UI is written once.

Backend is complete through Phase 6. The visual design is deliberately plain; all colours come from
`ui/theme/Theme.kt`, so restyling touches that file and the composables, nothing below the UI layer.

---

## Build and install

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

Unit tests (JVM, no device needed):

```bash
./gradlew testDebugUnitTest
```

Requirements: JDK 17, Android SDK with platform 34. `local.properties` points at the SDK and is
git-ignored — recreate it if you clone fresh.

---

## CI/CD

Two workflows in `.github/workflows/`:

**`ci.yml`** — on every push to `main`, every PR, and on demand. Runs unit tests, lint, and
`assembleDebug` as separate steps so a failure names itself. Uploads the test reports and lint HTML
(even on failure, which is when you want them) plus the debug APK.

**`release.yml`** — on a `v*` tag. Runs the tests, builds a signed release APK, and publishes it to
a GitHub release with generated notes.

### Release signing

`app/build.gradle.kts` reads the keystore from environment variables, so nothing secret is ever
committed. With the variables absent — i.e. every local build — `assembleRelease` falls back to an
unsigned APK and nothing else changes.

Set four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the `.jks` file, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

To create a keystore and encode it:

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release
```

```bash
base64 -w0 release.jks > release.jks.base64
```

Keep `release.jks` somewhere safe and out of the repo — losing it means you can never ship an update
to an app already installed from this key. Until the secrets are set, the release job still runs and
produces an unsigned APK rather than failing.

### Before the first run

The project is not a git repository yet:

```bash
git init && git add . && git commit -m "TV Remote, phases 0-6"
```

Git on Windows does not track the executable bit, so if CI reports `Permission denied` on
`./gradlew`, fix it once with:

```bash
git update-index --chmod=+x gradlew
```

(The workflows also `chmod +x ./gradlew` defensively, so this should not bite.)

---

## Setting up the TV (do this first — nothing works without it)

The Wi-Fi transport needs network debugging enabled on the TV. This is the single biggest source of
"it does nothing" reports, so the app also shows these steps in-app under **Devices → Before the
first connection**.

1. Settings → Device Preferences → About
2. Tap **Build** seven times until it says you are a developer
3. Settings → Device Preferences → Developer options
4. Turn on **USB debugging** and **Network debugging** (some boxes call it *Wireless debugging* or
   *ADB over network*)
5. Note the TV's IP address under Settings → Network & Internet
6. On the first connect the TV shows **Allow debugging?** — accept it and tick
   **Always allow from this computer**

**Some TV boxes turn network debugging back off after a reboot.** If the remote stops working after
the TV was unplugged, check this before debugging anything else. The app exposes an explicit
**Retry** rather than assuming the setting persists.

---

## Architecture

```
core/           RemoteTransport, RemoteKey, ConnectionState, TransportError  (the seam)
transport/      FakeTransport (Phase 0), TransportManager (Phase 6)
adb/            AdbProtocol, AdbCrypto, AdbKeyStore, AdbConnection, AdbStream,
                AdbClient, AdbTransport, AdbKeyMap                            (Phases 1-3)
discovery/      DeviceDiscovery (mDNS + subnet scan)                          (Phase 2)
shortcuts/      KnownApps, ShortcutRepository                                 (Phase 3)
bluetooth/      HidDescriptors, HidReport, HidKeyMap, BluetoothHidTransport   (Phases 4-5)
data/           SettingsRepository (DataStore)
ui/             RemoteControlScreen, ConnectionScreen, RemoteViewModel
```

Everything the UI can do goes through `TransportManager`. Adding a third transport means
implementing `RemoteTransport` and adding one branch to `TransportManager.transportFor`.

---

## Phase notes

### Phase 0 — Skeleton and fake transport
`FakeTransport` logs every press to logcat (`adb logcat -s FakeTransport`) and keeps a rolling
in-memory log. Selectable from the **Demo** pill on the remote screen, so the whole UI can be
exercised with no TV in the room.

### Phase 1 — ADB connection
The ADB client is a from-scratch implementation of the wire protocol — no `adb` binary is shipped
or needed. Two details that are easy to get wrong and are both handled:

- **Signing.** adbd does *not* accept a standard `SHA1withRSA` signature. It expects a raw RSA
  operation over a fixed PKCS#1 v1.5 pad + the 20-byte token, so `AdbCrypto` uses
  `RSA/ECB/NoPadding` with its own padding array.
- **Public key format.** adbd wants Android's `RSAPublicKey` struct (little-endian modulus, a
  Montgomery `n0inv`, and `rr = (2^2048)^2 mod n`), base64'd — not X.509.

**Key persistence** is in `AdbKeyStore`: the RSA identity is generated once into app-private
storage and reused, so the TV's "Always allow" actually sticks. Settings has a
**Forget authorisation** action for the case where Deny was tapped once and the TV then refuses
silently.

**Connection loss** (TV asleep, DHCP lease renewed, daemon restarted) is detected two ways: the read
loop sees EOF / a write fails, and a 30-second keep-alive runs `true` over the shell as a liveness
probe for stacks that leave the socket half-open. Recovery is exponential backoff (1s → 15s, six
attempts) surfaced as `ConnectionState.Reconnecting`.

### Phase 2 — Full keymap and discovery
`AdbKeyMap` covers the D-pad, navigation, power, volume, media, channel, colour and TV keys.
Discovery runs mDNS (`_adb._tcp` and `_adb-tls-connect._tcp`) and a bounded /24 port sweep in
parallel, because neither is reliable alone — plenty of boxes never advertise, and some routers
block client-to-client multicast.

- Scanning is capped at 8 seconds. When it finds nothing, the UI says so explicitly and points at
  manual IP entry rather than showing an empty list.
- Key events are sent **fire-and-forget** (the stream is opened but the empty output is not waited
  for). That removes a round trip per press, which is what keeps the D-pad responsive on a busy
  network. Presses cannot queue up behind each other because each is its own ADB stream.

### Phase 3 — Shortcuts
Package names vary by vendor and OEM skin (Netflix is `com.netflix.ninja` on Android TV but
`com.netflix.mediaclient` elsewhere; YouTube has three). Instead of hardcoding a guess,
`KnownApps` holds *candidate* package lists and `ShortcutRepository.refresh` resolves them against
`pm list packages -3` on the actual TV, caching the result. Launch uses the launcher intent via
`monkey` (LEANBACK first, then LAUNCHER) rather than a hardcoded activity name.

### Phase 4 — Bluetooth HID pairing
`BluetoothHidTransport.register()` hands the report descriptor to the stack; until that succeeds the
phone is invisible in the TV's Bluetooth menu. Things worth knowing when testing on real hardware
(this does not emulate):

- `registerApp` is asynchronous and fails silently. Success only arrives as
  `onAppStatusChanged(registered = true)`, so `register()` waits on that callback rather than the
  return value, with an 8-second timeout and a real error message.
- Only one HID app may be registered per process; re-registering without unregistering fails.
- Everything interesting is logged under `adb logcat -s BluetoothHidTransport`, which is the only
  practical way to debug OEM stack behaviour.

### Phase 5 — Bluetooth keymap
Two report IDs: 1 = keyboard, 2 = consumer control. Which page a button lands on is chosen so that
Android's own input stack produces the *same* `KEYCODE_*` the ADB transport sends — e.g. Back is
keyboard Escape (honoured by every Android build) rather than consumer AC Back (not always). Every
press sends a press report followed by a release report; skipping the release is what causes "one
tap scrolled the entire menu".

`KeyMapParityTest` fails the build if a `RemoteKey` is added without wiring both transports.
`NOTIFICATION` is the one documented gap — it has no HID equivalent.

### Phase 6 — Switching and fallback
`TransportManager` owns all three transports. If a command fails on the active one and the other is
currently usable, the press is retried there, the active transport switches, and the user gets a
one-line snackbar. Auto-fallback is a setting; with it off, failures surface as errors instead.

---

## Known limitations

- **ADB over TLS (Android 11+ wireless debugging) is not supported.** Devices that demand it send
  `STLS` during the handshake; the app detects this and says so rather than hanging. The legacy
  port-5555 toggle still exists on most Android TV builds. Adding TLS means implementing the
  pairing-code flow and a client certificate derived from the ADB key — a self-contained follow-up.
- **Bluetooth cannot launch apps or type text.** No HID usage exists for either. The UI disables
  those controls on that transport instead of failing after the fact.
- **Waking a sleeping TV over Wi-Fi does not work** — the network stack is down. That is the main
  practical reason to keep the Bluetooth transport around.

## On MongoDB

Not needed. Everything this app persists is device-local and tiny: the ADB key pair (app-private
file storage), the last TV address, the preferred transport, and the resolved shortcut packages
(DataStore, in `SettingsRepository`). There is no server component, no multi-user state, and no
data that outlives the install. Adding a database would mean adding a backend to talk to it.

If you later want profiles synced across phones — say, per-room shortcut layouts shared between
household members — that is the point where a store earns its place, and `SettingsRepository` is
the single seam it would plug into.
