# BPEQ — plan

Native Android PEQ editor for the CrinEar Protocol Max (USB 3302:43CC, WalkPlay SchemeNo16).

## Environment (checked 2026-11-16)
- JDK 17 (system), Android SDK at `~/Android/Sdk` (platforms 35/36/37, build-tools 36). Nothing needs sudo.
- Stack pinned to the versions Contour ships with on real hardware: AGP 9.4.1, Gradle 9.7.1 (wrapper),
  Kotlin 2.4.20, Compose BOM 2026.09.00, compileSdk 37, targetSdk 36, minSdk 26.
- Signing: `~/peq-signing/bpeq-release.jks` + `~/peq-signing/signing.properties` (outside git).
  Every build is a signed release build. `scripts/build-apk.sh` bumps versionCode, builds, runs all tests,
  copies the APK to `~/peq-builds/peq-<versionName>.apk`.

## Modules
- `:core` — pure Kotlin/JVM, no Android. Unit-tested on the JVM.
  - `model/` Profile, Band, FilterType, Library, undo history
  - `dsp/` RBJ biquads, response evaluation, Q30 fixed-point (device curve), auto preamp
  - `device/` Protocol Max codec (report encode/decode), limits & clamping, `HidChannel` interface,
    `MaxSession` (read all / write → verify → commit), `SimulatedDongle` (a `HidChannel` that behaves like
    the real firmware: register store, RAM vs flash, replies, faults: timeouts, unplug, corrupted replies)
  - `io/` importers (APO/Peace, AutoEQ, squig.link, graph.hangout.audio, EQ by Ear JSON), APO export,
    measurement CSV/TXT parser, backup format
  - `tune/` optimizer (bounded Levenberg–Marquardt), filter rebuilding, tone sliders
- `:app` — Compose UI, Android USB (`UsbHidChannel`), storage, widget, QS tile.

## Protocol (ported from Contour, Apache-2.0 / devicePEQ, 0BSD)
- HID report 0x4B, 64 bytes on the wire. Read: VERSION `80 0C`, bulk slot `80 09`, band i `80 09 00 00 i 00`,
  preamp `80 03`. Band = 5×int32 Q30 biquad (RBJ @ 96 kHz) + freq u16 + Q×256 u16 + gain×256 s16 + type
  (1 LS, 2 PK, 3 HS) + slot byte. Write `01 09 18 …`, preamp `01 03 02 00 g`.
- Commit (devicePEQ, what Contour uses on the Max): `01 05`, `01 17`, `01 0A 04 00 00 FF FF`, `01 01 01`.
- Golden tests: devicePEQ's two Protocol Max captures (firmware 0.7) — our encoder must reproduce the
  captured band-write bytes exactly.
- "Large low-frequency high-shelf boost can't be stored" = biquad coefficient overflows signed Q30 (±2.0).
  Checked per band before anything is sent.

## Hardware safety
1. Clamp to limits, rounding toward quieter (gain toward 0/down, preamp down, whole dB).
2. Read-back first (M2): self-test only reads; decode → re-encode → must match the device bytes.
3. First-ever write: a dialog shows every report in hex; nothing is sent until you tap "Send".
4. Write sequence: bands + preamp (no commit) → read back all 10 bands + preamp → compare every register
   → only then commit to flash → read back again. Mismatch = no commit, clear error, log.
5. Every USB call has a timeout; unplug mid-operation is caught and reported; one USB worker thread.

## Milestones
1. Project skeleton, signing, Diagnostics screen (log, copy, share), simulated dongle, core tests. APK 0.1.0
2. USB: device_filter, permission, attach/detach, descriptor dump, read-only self-test. APK 0.2.0 → **you test**
3. Writing EQ: write → verify → commit, first-write confirmation. APK 0.3.0 → **you test**
4. Full editor, graph, library, undo/redo, import/export, send (features 1–4). APK 0.4.x
5. Auto-tune optimizer, filter rebuilding, overlays (5–7). APK 0.5.x
6. Tone sliders, IEM tags, plug-in prompt, QS tile, widget, A/B investigation, compare, backup (8–13). APK 0.6.x
   - A/B: devicePEQ's capture of the official WalkPlay site shows it applying with `01 0A` (TEMP_WRITE) and no
     flash command; on the Micro that is proven RAM-only. A dedicated test step will check the Max
     (temp write → listen → replug → read back). A/B ships only if it is proven RAM-only.
7. README (build, signing, sideload, credits), final polish. APK 1.0.0

## Status (keep updated)
- M1 done (0.1.0). M2 done, hardware-verified read (0.2.0). M3 done, hardware-verified write/verify/commit/persist (0.3.0).
- M4 done (0.4.0), owner-tested OK.
- M5 done (0.5.0), owner-tested OK.
- M6 built (0.6.0): tone sliders, IEM filter/quick switcher, plug-in prompt, QS tile, favorites widget, A/B test + toggle
  (enabled only after the owner's replug test proves RAM-only), profile compare overlay, library backup. Owner-tested OK.
- M7 done (1.0.0): README (build, signing, sideload, credits), LICENSES/, About card, smooth tone sliders.
- 1.0.1: resend dropped band writes, restore previous state on persistent verify failure (see docs/HARDWARE.md).
- 1.0.2: A/B removed (impossible on firmware 1.4: every write persists) (versionCode 12).
- 1.0.3: treble tone >= +2 dB was unstorable (HS at 2.5 kHz overflows b1); now sent as LS -g + level (preamp absorbs).
  Rebuild crashed when the unstorable band was a tone band (index past the user bands); it now plans the bands alone.
- 1.0.4: crash log persisted to files/last_crash.txt and shown in the Log on next launch; unstorable shelves are sent
  automatically as the opposite shelf (Planner.flipShelf, Result.levelDb) so Rebuild only handles wide treble peaks;
  sweep test of every single band; tone bands named in messages; tone merge reported; manual preamp clipping warning;
  preamp shown/stored relative to the drawn curve. Fixed Profile timestamps lost on JSON round trip (same-ms default).
- 1.0.5: Import from link (core io/LinkImport + HttpFetch; INTERNET permission, used only on a link import).
  AutoEQ GitHub blob/tree -> profile; squig.link eqFilters (base64url "v2;" rows) -> profile + Tuning overlays;
  squig.link share= -> Tune (DIR from config.js, "<stem> L/R.txt" averaged, "<name> Target.txt"; phone_book.json for
  display names). graph.hangout.audio returns 403 for data files: reported, not bypassed. autoeq.app is client-side:
  explained. Live test: BPEQ_LIVE=1 ./gradlew :core:test --tests '*LinkImportTest*live*'.
- 1.0.6: Tune target from a link (LinkImport.importTarget: squig share=..._Target / eqTarget, or a curve file such as
  AutoEQ targets/*.csv on GitHub). A target-only squig link in the measurement import points to it.
- 1.0.7: A/B on the Max with wear limits (owner's choice): MaxSession.writeChanged writes only differing bands, no
  commit; Applier counts switches/writes, 20/day allowance with confirm, B persisted + restore offer on connect.
  DeviceManager.withSession is NonCancellable (a tab change mid-write cancelled the A/B job after the dongle changed).
- 1.0.8: A/B UI replaced by a "Compare  [EQ On | EQ Off]" toggle above the send bar (ui/AbButton.kt EqOnOffToggle),
  with a caption naming the kept preamp; status "On device · EQ Off"; Send shows "Sent" while compared off.
- 1.0.9: themes (ui/Theme.kt ThemeMode + ThemePrefs): System / Light / Dark / AMOLED, chosen in Device -> Settings.
  Palette colours are snapshot state switched by BpeqTheme (dark/light sets); node labels use onPrimary; system bar
  icons follow the app theme. The home-screen widget stays dark.
  Current release: 1.0.9 (versionCode 19).

## Next (planned 2026-11-17)
- Owner is buying a **CrinEar Protocol Micro** (3302:C20F, WalkPlay SchemeNo11) to add multi-device support.
  Contour's research/protocol/PROTOCOL.md documents the Micro: 8 bands, no native high shelf (Contour emulates HS as
  LS + preamp), devicePEQ write/commit sequence, TEMP_WRITE proven RAM-only on the Micro (so A/B may be possible
  there; re-check with a replug test before enabling). Plan: device registry + capability-driven UI, ship Micro
  read-only first, owner runs self-test, then enable writes; then reconsider A/B for the Micro only.
- Hardware facts: docs/HARDWARE.md. Dev smoke tests: emulator `Medium_Phone_API_37.0` + `scripts/ui.sh`
  (simulated dongle in the Device tab). Build: `scripts/build-apk.sh <versionName>`.
