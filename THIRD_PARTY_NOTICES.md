# Third-party notices

## Code and knowledge
| What | From | Licence |
|---|---|---|
| WalkPlay HID protocol, Q30 biquad encoding, write/commit sequence (`core/.../device/MaxProtocol.kt`) | [Contour](https://github.com/Iwakura9/contour) `WalkPlay.kt`, ported from [devicePEQ](https://github.com/jeromeof/devicePEQ) `walkplayHidHandler.js` | Apache-2.0 / 0BSD |
| RBJ biquad formulas in devicePEQ's shelf convention (`core/.../dsp/Biquad.kt`) | devicePEQ via Contour | 0BSD / Apache-2.0 |
| Equalizer APO / AutoEQ / squig.link text parser (`core/.../io/EqText.kt`) | Contour `TextCodecs.kt` | Apache-2.0 |
| Protocol Max USB captures used as golden tests | devicePEQ `tests/captures` | 0BSD |

## Data
| What | From | Licence |
|---|---|---|
| Target curves: Harman in-ear 2019 (with/without bass), AutoEq in-ear, Diffuse field ISO 11904-1 | [AutoEQ](https://github.com/jaakkopasanen/AutoEq) `targets/` | MIT, (c) 2018-2022 Jaakko Pasanen |

## Libraries in the app (all Apache-2.0)
Kotlin standard library, kotlinx.coroutines, kotlinx.serialization (JetBrains); Jetpack Compose (ui, foundation,
material3, material-icons-core), androidx.activity, androidx.core, androidx.lifecycle (The Android Open Source Project).
