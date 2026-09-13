# next_plan.md — handoff

## Read this first

A feature was designed and fully written, but **not one line of it has ever
been compiled.** The machine it was written on has no Rust toolchain, no
JDK/Android SDK and no Python. Treat every file below as a careful draft, not
as working code. Your first job is to make it build, not to add anything.

Two things *were* verified, by hand:

- `values/strings.xml` and `values-en/strings.xml`: 138 keys each, no missing
  or extra keys, all `%1$s`/`%2$d` placeholders matching (this replicates
  `StringsParityTest`).
- Both strings files and `AndroidManifest.xml` parse as valid XML.

Everything else is unproven.

## What the feature is

Use a phone as a Wii controller for **Dolphin running on a second Android
device**, with no PC involved.

```
BEFORE (PC)   Phone ──PMP/UDP──> PC receiver ──DSU/UDP──> Dolphin
NOW (Android) Phone app: MotionEngine ──JNI──> dsu crate ──DSU/UDP──> Dolphin
                                                0.0.0.0:26760      (device B)
```

The key insight the design rests on: Dolphin's DSU client accepts **any** IP,
not just `127.0.0.1`. So the DSU server moved onto the controller phone and
the whole PMP layer (server, pairing, QR, slots, discovery) was not needed at
all. Nothing gets installed on the Dolphin device.

The second insight: `MotionEngine.sendPacket()` already ends at
`onPacket(packet)`, a `(ByteArray) -> Unit` sink. The new mode swaps that sink
to push the **same golden-vector-tested PMP `INPUT` bytes** across JNI, where
`pmp::parse` decodes them. So there is no second codec, no floats crossing
JNI, and `MotionEngine.kt` was not modified at all.

Scope deliberately shipped: **one emulated Wii Remote, motion + buttons**.
Nunchuk, players 2–4, Cemu/Wii U and pointer mode are out (pointer mode needs
OS cursor injection, impossible on unrooted Android).

Background and the user-facing steps are in `docs/DOLPHIN-ANDROID.md`.

## What changed

| Path | What |
|---|---|
| `dsu/` | **New crate.** `desktop/src/dsu/*` moved here. `mapping.rs` moved with a **zero-line diff**; `server.rs` changed by 9 lines |
| `dsu/src/ini.rs` | New. Dolphin's `WiimoteNew.ini` / `DSUClient.ini` templates, moved out of `dolphin.rs` |
| `dsu-android/` | **New crate.** JNI bridge, `cdylib` → `libpepomote_dsu.so` |
| `desktop/src/dsu.rs` | New 49-line adapter so every existing receiver call site compiles unchanged |
| `desktop/src/dolphin.rs` | −77 lines; now calls `dsu_server::ini` |
| `android/.../net/DsuServer.kt` | JNI bindings + local-IP lookup |
| `android/.../service/DsuForegroundService.kt` | Mirrors `LinkForegroundService`'s lifetime handling |
| `android/.../service/DsuState.kt` | Tiny state holder |
| `android/.../ui/screens/DolphinAndroidScreen.kt` | Setup screen: IP, both INIs, copy/share, live client count |
| `android/app/build.gradle.kts` | `cargoNdkBuild` task wired ahead of `preBuild` |
| `desktop/e2e/dsu_probe.py` | New. Dolphin-shaped DSU client for debugging |
| `.github/workflows/{android,desktop,release}.yml` | Rust toolchain + cargo-ndk + NDK; `dsu` test step |

Three couplings were severed to make `dsu/` standalone: `MAX_PLAYERS` is now
defined in the crate (`net/mod.rs` re-exports it), the single
`shared.dsu_clients` write became an `AtomicUsize` read via `client_count()`,
and `bind_udp` split into `start_with_socket` (desktop keeps its port
eviction) vs `start(bind, port)` (Android binds `0.0.0.0`).

## Step 1 — toolchains

```powershell
winget install --id Rustlang.Rustup -e
winget install --id EclipseAdoptium.Temurin.17.JDK -e
winget install --id Python.Python.3.12 -e
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

Android SDK + NDK via Android Studio, or set `ANDROID_NDK_HOME`. `minSdk` is
26, so the `.so` links against platform 26.

## Step 2 — build in this order

Innermost first; each layer's errors are easier to read without the ones above.

```powershell
cd dsu          ; cargo test                 # start here
cd ..\dsu-android ; cargo build              # then the JNI bridge
cd ..\desktop   ; cargo test --release       # proves the extraction changed nothing
cd ..\android   ; .\gradlew test             # JVM unit tests incl. StringsParityTest
cd ..\android   ; .\gradlew assembleDebug    # triggers cargoNdkBuild
```

`dsu/Cargo.lock` and the `dsu` entries in `desktop/Cargo.lock` were written by
hand. If cargo rewrites them, that is expected and correct — keep the result.

## Step 3 — where the compile errors probably are

Ranked by how likely they are to be wrong, most likely first:

1. **`dsu-android/src/lib.rs`** — the `jni` 0.21 API was written from memory
   with no compiler. Suspect `env.get_string(&x)`, `env.convert_byte_array(&x)`,
   `env.new_string(..)`, `.into_raw()`, the `<'local>` lifetimes, and whether
   these take `&self` or `&mut self`. All `env` params were declared `mut`
   deliberately: an unused `mut` is a warning, a missing one is an error.
2. **`android/app/build.gradle.kts`** — `tasks.register<Exec>`, `buildList`,
   `workingDir`, and the `onlyIf {}` block. Note `onlyIf` reading `System.getenv`
   is not configuration-cache friendly; if the cache complains, that is the spot.
3. **`DolphinAndroidScreen.kt`** — Compose imports and Material 3 signatures.
4. **`MainActivity.kt`** — `DsuControllerRoute` and the two new `Screen` enum
   values; the `when (screen)` must stay exhaustive.
5. **`desktop/src/dsu.rs` + `net/mod.rs`** — the `pub use dsu_server::MAX_PLAYERS`
   re-export is used as an array length in `state.rs`, so it must stay a `const`.

The crate is aliased in `desktop/Cargo.toml` as
`dsu_server = { path = "../dsu", package = "dsu" }` on purpose: the desktop
module is also called `dsu`, and the alias avoids shadowing games.

## Step 4 — verify, in this order

1. `cd dsu && cargo test` — includes a new cross-crate test that runs
   `protocol/vectors/input_motion.hex` through `pmp::parse` →
   `MotionSample::wii` → `pad_data_packet` and asserts the 100 bytes.
2. `cd desktop && cargo test --release` — especially the `dolphin.rs` INI tests
   (`dsu_server_agrega_sin_duplicar_ni_borrar` and the `WiimoteNew.ini` ones).
   These are the regression net for the template move.
3. **`python desktop/e2e/dsu_probe.py <phone-ip>`** — do this before touching
   Dolphin. It speaks DSU exactly like Dolphin does. If PadData moves when you
   move the phone, the Rust path is good and anything that fails later is
   Dolphin configuration. This script is also unrun — expect to fix it too.
4. Real Dolphin on device B: place the two INIs, then Controllers → Wii Remote 1
   → Emulated; `DSUClient/0/PepoMote` must appear in the device list.

## Open risks

- **`PAD_TTL` is 1 s** (`dsu/src/server.rs:25`) and `MotionEngine` sends only on
  gyro events, with no keepalive. Android gyros normally deliver continuously,
  but if a device gates them when perfectly still, Dolphin will flicker the pad
  as disconnected. Fix would be a 1 Hz keepalive push on the Rust side. **This
  is the one to watch during step 4.**
- **The DSU server is a process-lifetime singleton** — `nativeStop` was
  deliberately not implemented. The crate's server loop has no exit condition,
  so stop/start would leak a thread and leave the port bound. Kotlin controls
  the flow by starting/stopping `MotionEngine` instead. If a real stop is ever
  needed, add an `AtomicBool` to `server::run`'s loop (~6 lines) — the 250 ms
  read timeout means it exits promptly.
- **The phone's IP is baked into `DSUClient.ini`.** It breaks silently on a
  DHCP change. The live client-count indicator is the only defence.
- **`ensure_dsu_server` is pinned to the literal `DSU_PORT`**, not `port()`.
  That preserves prior behaviour exactly; using `port()` would change what the
  e2e receiver writes under `PEPOMOTE_DSU_PORT`. Deliberate — don't "fix" it
  without checking `desktop/e2e/e2e_cemu.py`.

## Invariants — do not break these

- `dsu/src/mapping.rs` is the **only** place accel/gyro axis signs and units are
  converted. It was tuned by a calibration campaign against live Dolphin. It
  moved with a zero-line diff; keep it that way.
- `protocol/PROTOCOL.md` beats the code. Three codecs (`pmp`, `PmpCodec.kt`,
  `PmpCodec.swift`) are pinned to `protocol/vectors/*.hex`.
- The Dolphin INI text has exactly one source: `dsu/src/ini.rs`. The PC writes
  it, Android displays it. Never duplicate it in Kotlin.
- Comments, docs and commit messages are in **Spanish**. `README.md` is English,
  `README.es.md` its Spanish twin — keep both in sync.
- UI strings ship ES + EN; `StringsParityTest` enforces matching keys and
  placeholders.
- The version number now lives in **seven** places (see `CLAUDE.md`).

## Do not

- Do not rewrite the DSU server or the mapping in Kotlin. The whole point of the
  Rust/JNI route was to keep one implementation.
- Do not widen scope to Nunchuk / 4 players / Cemu before one Wii Remote works
  end to end on real hardware.
- Do not claim anything passes without pasting the command output.
