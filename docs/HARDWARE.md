# Protocol Max: what we verified on the owner's unit

Unit: CrinEar Protocol Max, USB 3302:43CC, firmware **1.4**, on a moto g stylus 2025 (Android 16).

## USB (2026-11-16, BPEQ 0.2.0)
- 1 configuration, 7 interfaces. HID = **interface 0**, interrupt IN 0x86 / OUT 0x05, 64 B, interval 8.
  Audio = interfaces 1 (control) and 2 (streaming, alt 1-4). Device class 0xEF (IAD).
- HID report descriptor (74 B): report 0x03 consumer keys (Vol+/-, play), report 0x4B 63 B in/out (PEQ),
  report 0x54 63 B in/out (unused). Same layout as the Protocol Micro.
- Every request gets its reply in ~30 ms. ~1 in 20 requests gets no reply; a resend always works.

## Registers
- Slot byte 0x01 on every band. VERSION reply "1.4". No summary-packet quirk (devicePEQ's 0.7 captures had one).
- Factory flat: PK, 0 dB, Q register 182 (0.711) at 31, 62, ..., 16000 Hz. Biquads RBJ at 96 kHz, Q30, bit-exact
  with our encoder.
- Band 1 originally held 1000 Hz with coefficients computed from unrounded Q 0.71 (another tool); 0 dB = identity.
- Preamp reply: `80 03 02 00 g` (int8); for -1 dB the bytes after g are also 0xFF.

## Writes (2026-11-16, BPEQ 0.3.0)
- Band writes `01 09 18 ...` + preamp `01 03 02 00 g`: **read-back immediately returns the new registers**, before
  any commit, so verify-before-commit is meaningful.
- devicePEQ commit (`01 05`, `01 17`, `01 0A 04 00 00 FF FF`, `01 01 01`) applies the EQ (audible) and saves it:
  the test EQ and preamp -1 dB survived unplug/replug. A very faint click on save.
- 10+ write/verify/commit cycles, all verified, no failures.

## A/B test (2026-11-17, BPEQ 0.6.0)
- Band writes + preamp + TEMP_WRITE (`01 0A 04 00 00 FF FF`) **without** the flash command (`01 01 01`): the EQ
  applied live (flat, preamp kept) **and was still there after unplug/replug**. On this firmware a temporary write is
  not RAM-only (unlike the Protocol Micro, where Contour proved TEMP_WRITE is lost on replug).
- So A/B stays **off**: switching through this path would write the flash on every toggle. The app restored the
  profile with a normal verified write.

## Second A/B run (2026-11-17, BPEQ 1.0.0) - dropped write, and bare writes persist
- The dongle **silently ignored one band write** (band 2 of 10): read-back still showed the old filter. This matches
  the ~1-in-20 dropped replies seen on reads. BPEQ refused to commit and sent no TEMP_WRITE.
- After unplug/replug, the bands that *had* arrived were still there (band 1 flat, band 2 old). So on firmware 1.4
  **plain band writes persist across a power cycle even without TEMP_WRITE or the flash command**; there is no
  "uncommitted" state. A failed verify therefore leaves a half-written EQ unless it is repaired.
- Since 1.0.1, BPEQ resends any band/preamp that doesn't read back (up to 3 rounds) and, if it still fails, writes the
  state it read just before the write back (same retries) and commits it, so the dongle never keeps a mix.

## A/B removed (1.0.2)
Both A/B runs showed writes persist without a commit, so a RAM-only A/B toggle is impossible on firmware 1.4.
The A/B test card and toggle were removed in 1.0.2 (see git history before that version to restore them, e.g. for a
future firmware that behaves like the Protocol Micro).

## A/B back, with wear limits (1.0.7)
At the owner's request A/B returned for firmware 1.4, accepting that each switch is stored permanently. To limit
wear: only bands/preamp that differ from the read-back state are written (`MaxSession.writeChanged`), verified by
reads, applied with TEMP_WRITE, and the commit sequence (05, 17, flash 01) is never sent. Switches and register
writes are counted in the app; past 20 switches a day the user confirms each further block of 20. B state is
persisted so an unplug on B is offered a restore at the next connect. Flash endurance of the dongle is unknown.

