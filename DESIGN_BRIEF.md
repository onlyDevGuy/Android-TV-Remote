# TV Remote — UI/UX Design Brief

A design prompt for the Android phone app in this repository. The backend is complete and working;
the current UI is a deliberately plain Material 3 scaffold that exists to prove the plumbing. This
document is the handover for the real design.

Everything below is drawn from the shipped code, not from imagination — the state list, the button
inventory and the capability gaps are exact.

---

## 1. The prompt

> Design the UI and UX for **TV Remote**, an Android phone app (Jetpack Compose, Material 3,
> minSdk 28 / Android 9, portrait-first) that replaces a lost Android TV remote.
>
> It controls the TV two ways — over Wi-Fi (ADB) and over Bluetooth (HID) — and the user should
> rarely have to think about which. The app already works; what it needs is a visual and
> interaction design that makes it feel like a real remote rather than a debug tool.
>
> **The single hardest constraint: this app is used in a dark room, one-handed, while looking at
> the TV — not at the phone.** Design for muscle memory and touch, not for visual browsing. A
> layout that is beautiful to look at but requires looking has failed the brief.
>
> Design the four surfaces in §4, every state in §5, and hand back the deliverables in §9.

---

## 2. What this app actually is

The user's Android TV remote is lost, broken, or the batteries are dead. They pick up their phone.
Between deciding to change the volume and the volume changing, the app has to launch, connect, and
register a press.

It is **not** a companion app, a content browser, or a second screen. It has no content, no
account, no feed. It is a remote control. The design should feel closer to a physical object than
to an app.

**Users:** the person who set it up (technical enough to enable ADB debugging once) and everyone
else in the house (who will only ever see the remote screen and must never meet a setting).

**The competition** is the physical remote once it is found again. If the app is slower or less
reliable than getting off the couch, it loses.

---

## 3. The five things that should drive the design

**3.1 Blind operation is the primary use case.**
Buttons must be findable by position and size alone. This means: stable geometry (nothing moves
between states), strong size and shape differentiation between neighbouring buttons, and generous
spacing. A status banner appearing must not shift the D-pad down — that turns into a mis-press in
the dark.

**3.2 Failure is a normal state, not an exception.**
TVs sleep. DHCP leases renew and the IP changes. Some TV boxes silently turn network debugging off
after a reboot. The app already handles all of this with reconnection and fallback — but the user
needs to know, at a glance, whether a press is going to land. **Connection status is permanent
first-class furniture, not a transient toast.** There are 7 connection states and 9 distinct error
types (§5), and each error has a *different* corrective action. The design's job is to make the
right next step obvious without a wall of text.

**3.3 The two transports have asymmetric capabilities, and the user will not read documentation.**

| | Wi-Fi (ADB) | Bluetooth (HID) |
|---|---|---|
| Buttons | yes | yes (all but one) |
| Launch apps | yes | **no** |
| Type text | yes | **no** |
| Works when TV is asleep | **no** | yes |

When a control is unavailable, "why" must be answerable in under a second and without a tap. The
current UI greys things out and puts an explanation in body text — that is the baseline to beat.

**3.4 There is no confirmation that a press landed.**
Key events are sent fire-and-forget for latency reasons; the TV never acknowledges. **The only
feedback the user gets is local** — the press animation and the haptic tick. That makes press
feedback load-bearing rather than decorative. It must feel instant, physical and unambiguous, and
it must read in peripheral vision.

**3.5 Setup is a cliff, and it is where users are lost.**
Before the app works at all, someone must walk a 6-step path through the TV's settings to enable
network debugging, then accept a prompt on the TV. Today that is a collapsible block of text. This
deserves a designed first-run experience — and it must be skippable and re-findable, because the
person doing setup and the people using the remote are different people.

---

## 4. Surfaces to design

**4.1 Remote (primary).** The screen the app opens to and 95% of usage. Everything in §6.

**4.2 Devices & setup.** Discovery (scan with progress, results list, empty state), manual IP
entry, Bluetooth pairing flow, and app settings. Currently one long scrolling screen with three
card sections — restructure freely.

**4.3 First-run / setup guidance.** Currently a collapsible paragraph inside 4.2. Should probably
become its own flow. The 6 steps are in `README.md` under "Setting up the TV".

**4.4 Text entry.** A modal for typing into TV search fields (the TV's own on-screen keyboard is
miserable). Currently a bare `AlertDialog` with one field.

Plus: transient notices (currently a snackbar) for fallback events like
*"Wi-Fi dropped — switched to Bluetooth."*

---

## 5. Every state that must be designed

Do not design only the happy path. The connection state machine is the app's real complexity.

**Connection states** (`ConnectionState`):

| State | Carries | What the user needs |
|---|---|---|
| `Idle` | — | No TV configured. Route to setup. |
| `Connecting` | detail text | Progress, cancellable |
| `AwaitingAuthorization` | detail text | **"Look at your TV and accept the prompt"** — this is a hard context switch away from the phone; the design must make it unmissable |
| `Connected` | device label, address | Calm, minimal, gets out of the way |
| `Reconnecting` | attempt number, retry delay, cause | Automatic recovery in progress — reassure, don't alarm |
| `Disconnected` | reason | Retry affordance |
| `Failed` | a typed error (below) | The specific fix |

**Error types** (`TransportError`), each needing its own treatment:

- `Unreachable` — TV asleep or off-network → offer rescan
- `NetworkDebuggingOff` — port refused → link to the setup steps
- `AuthorizationRejected` — user hit Deny on the TV → "forget authorisation and retry"
- `TlsRequired` — Android 11+ wireless debugging, unsupported → explain, suggest Bluetooth
- `BluetoothUnavailable` — adapter off or stack refused
- `PermissionMissing` — needs a permission grant
- `NotConnected`, `Protocol`, `Unknown` — generic fallbacks

**Other states:** scanning in progress; scan finished with zero results (important — this is the
manual-IP-entry fallback); no paired Bluetooth devices; shortcut list empty (not yet resolved
against the TV); every button disabled because nothing is connected.

---

## 6. Control inventory

**Currently on the remote screen** (17 controls):

- Power, TV Input, Guide, Keyboard (opens text entry)
- D-pad: Up / Down / Left / Right + OK
- Back, Home, Menu
- Volume −, Mute, Volume +
- Rewind, Play/Pause, Fast-forward
- App shortcuts: a horizontal row of pills (Netflix, YouTube, Prime Video, Disney+, Showmax, DStv,
  Spotify, Plex, Kodi, VLC, TV Settings — whichever are actually installed, resolved off the TV)
- Transport switch: three pills (Wi-Fi / Bluetooth / Demo)
- Connection status header

**Supported by the backend but not yet surfaced** (20 more) — deciding which of these earn a place
is part of the design work:

Channel ↑/↓ · Info · Captions · Search · Enter · Delete · Notifications · Sleep · Wake ·
Media Next / Previous / Stop / Play / Pause / Record · Colour keys (red, green, yellow, blue)

Colour keys and Channel ↑/↓ matter for live TV and DStv users; the media keys matter for streaming.
They probably do not all belong on the primary surface — propose a secondary layer (a swipe-up
panel, a second page, an expandable drawer) or argue for leaving them out.

**Behaviour already implemented that the design must respect:**

- **Hold-to-repeat** on D-pad arrows, volume, channel, fast-forward/rewind and delete. 400 ms
  before the first repeat, then every 120 ms. Held buttons need a visibly different state from
  tapped ones.
- **OK, Power and Mute never repeat** — one press per tap, deliberately.
- **Haptic tick on every press**, user-toggleable.
- Current touch targets: 64 dp standard, 84 dp for OK, 56 dp for media. Treat 64 dp as the floor
  for anything used blind — well above the 48 dp accessibility minimum, and intentionally so.

---

## 7. Open design questions

These are genuine decisions, not rhetorical prompts. Push back on any of them.

1. **Reachability.** The status header sits at the top, where a thumb cannot reach it, and the
   D-pad is vertically centred — which pushes media controls into the bottom third. On a modern
   tall phone the top third is effectively unreachable one-handed. Should the whole layout be
   bottom-anchored, with status as a slim always-visible strip?

2. **Gesture pad vs D-pad.** Many TV remote apps offer a swipe surface instead of arrows. It is
   faster for long menu traversals but far worse blind. Offer both? Make it a preference? Or commit
   to the D-pad, which is the muscle-memory-compatible choice?

3. **Light theme.** A bright white phone in a dark living room is genuinely hostile. Options:
   dark-only, dark-by-default with an opt-in light theme, or an auto-dimming "night" mode. Make a
   recommendation.

4. **Transport switching.** Three equal-weight pills currently spend prime screen real estate on a
   control most users touch once ever — and one of the three ("Demo") is a developer affordance
   that should probably not be visible in a release build at all. Should this collapse into the
   status element?

5. **Secondary controls.** See §6 — where do the other 20 keys live?

6. **Orientation and size.** Portrait is the primary case. Landscape must not break (the activity
   handles rotation itself and will not be recreated). Large screens and tablets: stretch, or
   centre a phone-width column?

7. **Identity.** The app currently has a placeholder launcher icon and no visual identity at all.
   Naming, icon and a splash/launch treatment are in scope if you want them.

---

## 8. Constraints and non-negotiables

**Technical:**
- Jetpack Compose + Material 3. Colours come from `MaterialTheme`; the entire palette lives in
  `app/src/main/java/com/sizwe/tvremote/ui/theme/Theme.kt`, which is a placeholder to be replaced.
- minSdk 28 (Android 9) — no Material You dynamic colour dependency unless it degrades gracefully.
- Edge-to-edge is enabled; design to insets.
- `material-icons-extended` is available. Custom vector icons are fine (`ImageVector` / drawable).
- No custom fonts are bundled yet. Specify what you want and it will be added.
- No network images, no runtime asset downloads. Everything ships in the APK.

**Accessibility (hard requirements):**
- Every control already carries a `contentDescription`; keep semantic labels meaningful for
  TalkBack. A blind user and a user-in-a-dark-room want the same things.
- Contrast: WCAG AA minimum against the surface behind each control.
- Never rely on colour alone for connection state — the green/amber/red dot must be paired with
  text or shape.
- Respect the system font-scale; the layout must survive 200% text without clipping.
- Honour "reduce motion".

**Product:**
- **Time-to-first-press is the headline metric.** Do not gate the remote behind a splash screen,
  an onboarding carousel, or a connection modal. The app must reconnect in the background while
  the remote is already on screen and pressable.
- No accounts, no analytics screens, no content browsing, no ads.
- Nothing about this app should look like a developer tool. Words like "ADB", "transport" and
  "HID" are implementation details — the user's vocabulary is "Wi-Fi", "Bluetooth", "my TV".

---

## 9. Deliverables

1. **Visual direction** — one or two directions, with rationale. Show the remote screen in the
   `Connected` and `Failed` states, minimum.
2. **The four surfaces** in §4, laid out at a standard phone size (e.g. 393×852).
3. **Every state** in §5 — at minimum: Connected, Connecting, AwaitingAuthorization, Reconnecting,
   Failed, and scan-found-nothing.
4. **A component sheet** covering: the standard round button (default / pressed / held / disabled),
   the OK button, the pill button, the D-pad cluster, the status element, the shortcut chip, and
   the transport control.
5. **Design tokens** — colour (light + dark), type scale, spacing scale, corner radii, elevation.
   Expressed so they map onto a Material 3 `ColorScheme` and `Typography`; they will be
   transcribed directly into `Theme.kt`.
6. **Motion notes** — press feedback timing above all, plus state transitions and the scanning
   indicator. Keep press feedback under 100 ms; it is the only signal the user gets.
7. **Icon set** — for the controls in §6, plus a launcher icon if you are taking on identity.

---

## 10. What already works and should not be redesigned away

- The transport abstraction means the remote screen never knows which transport is live. Do not
  design two different remote layouts.
- Hold-to-repeat, haptics, and the 64 dp floor are the result of the interaction requirements in
  §3.1 and §3.4. Change them with a reason, not by default.
- Errors are already typed and each carries a human-readable message with the corrective action.
  The design should surface those messages, not replace them with a generic "Something went wrong".
- The setup instructions are accurate and load-bearing. They can be restructured and made
  beautiful; they cannot be shortened into uselessness.

---

## Appendix: reference points

- `app/src/main/java/com/sizwe/tvremote/ui/` — current screens
- `app/src/main/java/com/sizwe/tvremote/core/ConnectionState.kt` — the state and error types in §5
- `app/src/main/java/com/sizwe/tvremote/core/RemoteKey.kt` — the full 37-key vocabulary in §6
- `app/src/main/java/com/sizwe/tvremote/ui/theme/Theme.kt` — the palette to replace
- `README.md` — the TV setup steps referenced in §3.5
