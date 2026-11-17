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

## Open
- Whether band writes + TEMP_WRITE (`01 0A`) alone apply live without flash (A/B, feature 11).
