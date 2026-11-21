# BPEQ

A native Android parametric EQ app for the **CrinEar Protocol Max** USB DAC/amp dongle (USB `3302:43CC`, WalkPlay
chip). It edits, auto-tunes and applies 10-band PEQ profiles to the dongle over USB HID. The EQ is stored on the dongle,
so it keeps working on any device after you unplug it.

Unofficial; not affiliated with or endorsed by CrinEar or WalkPlay.

## Features

- **EQ editor**: up to 10 bands (Peak, Low shelf, High shelf). Drag nodes on the live response graph (log frequency,
  ±12 dB, RBJ biquads) to set frequency/gain, pinch to set Q. Precise sliders (slower when you drag slowly), tap any
  number to type it. Preamp Auto (anti-clipping) or Manual. Undo/redo, 100 steps per profile, kept across restarts.
- **Library**: profiles saved on the phone with a separate saved version and working copy. Create flat, from the
  clipboard, from a `.txt`/`.json` file, or read from the dongle. Rename, duplicate, delete with undo, favorites,
  IEM tags with filter chips and a quick switcher. An **On device** marker shows which profile the dongle holds.
- **Import / export**: Equalizer APO / Peace, AutoEQ `ParametricEQ.txt`, squig.link and graph.hangout.audio text,
  EQ by Ear JSON. Paste, open a file, *Open with BPEQ*, or Share to BPEQ. Export as Equalizer APO / Peace text.
  **Import from link**: an AutoEQ result on GitHub (file or folder) becomes a profile; a squig.link EQ link becomes a
  profile with the IEM and target as graph overlays; a squig.link graph link loads the IEM (and target) into
  Auto-tune; in Auto-tune a target can also come from a link (a squig.link link with a target selected, or a
  target file such as an AutoEQ target CSV on GitHub). Share a link from the browser to BPEQ, or paste it. Sites that refuse downloads by other apps
  (graph.hangout.audio) are reported, not worked around.
  Whole-library backup and restore (one JSON file; restore never overwrites).
- **Auto-tune**: load an IEM frequency response (squig.link / Crinacle / AutoEQ / REW CSV or TXT), pick a target
  (Harman in-ear 2019 with/without bass, AutoEq in-ear, Diffuse field, or your own file), and BPEQ fits up to 10 filters
  with a bounded Levenberg–Marquardt optimizer (20 Hz–16 kHz, less weight above 10 kHz) that respects every device limit.
  Runs in well under a second.
- **Device-safe filters**: a shelf the Protocol Max can't store (e.g. a big low-frequency high-shelf boost, whose
  coefficients overflow the device's fixed-point format) is sent automatically as the opposite shelf with the
  opposite gain: the same shape, quieter, with the preamp making up the level. The rare filters that still don't
  fit (very wide, large treble peak boosts) get *Rebuild for the device*: an equivalent allowed set, with the
  original and rebuilt curves side by side and the largest difference in dB.
- **Graph overlays**: the curve the dongle will really play after rounding/clamping, plus IEM measurement, target and
  predicted result for auto-tuned profiles, and any other profile for comparison.
- **Tone sliders**: bass, treble tilt and ear gain on top of any profile, blended to stay within 10 bands.
- **Plug-in prompt** ("Apply <last profile>?"), a **Quick Settings tile** (each tap applies the next favorite) and a
  **home-screen widget** with up to four favorites.
- **No A/B toggle, on purpose**: on the Protocol Max (firmware 1.4) every band write lands in permanent memory, even
  without a save command, so a quick EQ-on/off switch would wear the flash. To compare, keep a flat profile and switch
  with the tile or widget now and then (see `docs/HARDWARE.md`).
- **Diagnostics**: a log of every USB event and HID report in hex, with Copy and Share, plus a read-only self-test.
  If the app crashes, the error and the log leading up to it are shown in the Log on the next launch.
  A built-in simulated dongle lets you try everything without hardware.

## Install (sideload)

1. Copy `peq-<version>.apk` to the phone (USB file transfer, cloud drive, email...).
2. Open it from a file manager. Allow *Install unknown apps* for that file manager when Android asks.
3. Plug in the Protocol Max. When Android asks *Open BPEQ when Protocol Max is connected?*, tick **Always** so the app
   gets USB access without asking each time.

Official releases install over the previous version and **keep your profiles**. Android refuses an update signed with
a different key (for example, your own build over an official release); uninstalling first would delete your
profiles, so make a **Library → ⋮ → Back up whole library** first if you ever switch.

### Verify a download

Official APKs are signed with this certificate (SHA-256):

`DC:7A:60:DB:BB:5C:39:B8:8B:15:E8:F3:F4:64:44:F4:76:4A:5B:EF:39:C9:EE:BB:A0:21:ED:77:94:3C:B9:2D`

Check it with `apksigner verify --print-certs peq-<version>.apk` (from the Android SDK build-tools).

### First use

1. **Device → Run device self-test (read-only)**: reads the firmware version, all 10 bands and the preamp, and checks
   that BPEQ's encoding matches the dongle bit for bit. Nothing is written.
2. The first time you send anything, BPEQ shows every byte it will write and waits for **Send**.
3. Every write is read back and compared value by value. Only if everything matches is it saved to the dongle's
   memory, and then it is read back again.

## Privacy

BPEQ has no accounts, analytics or ads. It uses the internet only when you import a link, and then only to download
that file (plus, for squig.link, the site's `config.js` and IEM list). Everything else stays on the phone.

## Safety design

- Every value is clamped to the Protocol Max limits before sending (10 bands, gain −10..+10 dB, Q 0.1..10,
  20 Hz..20 kHz, preamp −30..0 dB in whole dB); rounding goes toward quieter (gain and preamp round down).
- Filters whose coefficients don't fit the device are never sent: shelves are flipped to an exact equivalent,
  anything else needs *Rebuild*. A manual preamp that would let the output clip is warned about.
- Write order: bands + preamp (not saved) → read back all 10 bands, slot byte, preamp and coefficients → only on an
  exact match: commit to flash → read back again. A mismatch is reported and nothing is saved.
- One USB worker thread; every USB call has a timeout and bounded retries; unplugging mid-operation is reported as an
  error, never a crash.
- What was verified on real hardware is recorded in [`docs/HARDWARE.md`](docs/HARDWARE.md).

## Build from source

Requirements: Linux/macOS/Windows with **JDK 17**, the **Android SDK** with platform 37 and build-tools 36 (Android
Studio's SDK manager or `sdkmanager "platforms;android-37" "build-tools;36.0.0"`). Gradle comes from the wrapper.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew :core:test                                   # unit tests (protocol, DSP, importers, optimizer, simulator)
scripts/build-apk.sh 0.7.0                             # signed release APK -> ~/peq-builds/peq-0.7.0.apk
```

`scripts/build-apk.sh [versionName]` bumps `versionCode` in `version.properties`, runs all unit tests, builds the minified, signed release APK, prints the signing certificate and copies
the APK to `~/peq-builds/peq-<versionName>.apk`.

### Signing your own build

Signed builds need your own keystore and a `signing.properties` file with `storeFile`, `storePassword`, `keyAlias`
and `keyPassword`. The build reads `~/peq-signing/signing.properties` by default, or the file given with
`-Pbpeq.signing=<path>`. A build signed with your own key can't install over an official release (see *Install*).

### Project layout

```
core/   pure Kotlin/JVM, no Android; everything here is unit-tested
  model/    Band, Eq, Tone, Profile (saved + working copy + undo history)
  dsp/      RBJ biquads at the firmware's 96 kHz design rate, response evaluation
  device/   MaxProtocol (HID reports, Q30 coefficients), MaxLimits (clamping), MaxSession (read / write-verify-commit),
            Planner (EQ -> exact device registers, auto preamp), SelfTest, SimulatedDongle
  io/       Equalizer APO / AutoEQ / squig.link / EQ by Ear import, APO export, library backup
  tune/     Curve (FR parsing), Targets (bundled), AutoTune + Fitter (bounded LM), Rebuild, ToneBlend
app/    Android: Compose UI (Material 3), USB HID transport, library storage, tile, widget
scripts/build-apk.sh   signed build;  scripts/ui.sh   emulator UI helper used for smoke tests
docs/HARDWARE.md       protocol facts verified on real hardware (firmware 1.4)
```

Golden tests reproduce real Protocol Max USB traffic byte for byte (devicePEQ's captures and a read-out of a real unit).

## Troubleshooting

- **No device / No permission**: replug the dongle and accept the USB prompt (tick *Always*), or tap *Allow USB*.
- **Something failed**: **Log → Copy** (or Share) and include it in the report; it holds every USB report in hex.
- **A send was refused**: the EQ tab explains why (e.g. more than 10 enabled bands, or a filter the device can't
  store, with a *Rebuild* button).

## Credits

- **[Contour](https://github.com/Iwakura9/contour)** (Apache-2.0): the Protocol Max protocol implementation, biquad
  maths and the APO/AutoEQ text parser were adapted from it, with its documented hardware findings.
- **[devicePEQ](https://github.com/jeromeof/devicePEQ)** by Jerome O'Flaherty (0BSD): the original WalkPlay HID
  protocol and coefficient code that Contour ported, and the Protocol Max USB captures used as golden tests.
- **[AutoEQ](https://github.com/jaakkopasanen/AutoEq)** by Jaakko Pasanen (MIT): the bundled target curves.
- Kotlin, kotlinx.coroutines, kotlinx.serialization, Jetpack Compose and AndroidX (Apache-2.0).

See [`NOTICE`](NOTICE), [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the licence texts in
[`LICENSES/`](LICENSES). CrinEar, Protocol Max, WalkPlay and Harman are trademarks of their owners.
