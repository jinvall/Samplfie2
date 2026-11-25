# APIDACS Phase 0 — Repository Audit & Initial Baseline

**Repository Root:** `/app/applet` (`Samplfie2`)  
**Audit Date:** 2026-11-24  
**Scope:** Complete repository inspection prior to APIDACS implementation (read-only on application source code).

---

## 1. Repository & Language Inventory

Samplfie2 is a dual-target multi-module Kotlin workstation project (`:app` for Android Jetpack Compose and `:desktop` for Linux Java2D/Swing).

| Category | Count | Lines of Code / Notes |
|---|---:|---|
| `:app` Main Kotlin (`app/src/main/java/com/example/**/*.kt`) | 57 | 27,446 LOC |
| `:app` Unit/Robolectric Tests (`app/src/test/java/com/example/**/*.kt`) | 16 | 2,544 LOC (9 pure JUnit4, 7 Robolectric) |
| `:app` Instrumented Tests (`app/src/androidTest/**/*.kt`) | 1 | `ExampleInstrumentedTest.kt` |
| `:desktop` Main Kotlin (`desktop/src/main/kotlin/com/samplfie/desktop/**/*.kt`) | 40 | 19,056 LOC |
| `:desktop` Unit Tests (`desktop/src/test/kotlin/com/samplfie/desktop/**/*.kt`) | 8 | 1,672 LOC (all pure JUnit4) |
| **Total Kotlin Source** | **122** | **50,718 LOC** |
| Gradle Kotlin DSL (`.gradle.kts`) | 4 | `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `desktop/build.gradle.kts` |
| Android XML (`AndroidManifest.xml`, `res/**/*.xml`) | 11 | Manifest, launcher icons, backup/extraction rules, MIDI XML, values |
| Documentation (`.md`) | 8 | `README.md`, `docs/*.md` (4), `APIDACS/*.md` (3) |
| Scripts / Desktop Entries | 2 | `scripts/launch-linux-studio.sh`, `scripts/samplfie2-desktop.desktop` |
| Version Catalog & Build Config | 5 | `gradle/libs.versions.toml`, `gradle.properties`, `app/proguard-rules.pro`, `.env.example`, `metadata.json` |
| Binary Assets (`.png`, `.jpg`, `.ttf`, `.jar`) | 15 | 10 mipmap PNGs, 1 drawable JPG, 3 TTF fonts, 1 `gradle-wrapper.jar` |

---

## 2. Programming Languages, Frameworks & Build System

- **Primary Language:** Kotlin `2.4.21` (`kotlin-compose` plugin for `:app`, `kotlin-jvm` plugin for `:desktop`, JVM target `11`).
- **Build System:** Gradle `9.3.1` with Android Gradle Plugin `9.1.1` and Version Catalog (`gradle/libs.versions.toml`). Note: Gradle wrapper `./gradlew` is not used in this container; system `gradle` (`/opt/gradle/gradle-9.3.1/bin/gradle`) is active.
- **Android UI & Runtime (`:app`):** Jetpack Compose BOM `2024.09.00`, Material 3, Coroutines `1.10.2`, DataStore Preferences `1.1.7`, AndroidX Activity/Lifecycle `2.8.7`, `de.sciss:jump3r:1.0.5` (MP3 encoding), Android `AudioRecord`/`AudioTrack`/`MediaExtractor`/`MediaCodec`/`MidiManager`, Secrets Gradle Plugin `2.0.1` (`.env` -> `BuildConfig`).
- **Desktop UI & Runtime (`:desktop`):** Java Swing / Java2D custom theme (`StudioTheme`), `javax.sound.sampled` (ALSA/PulseAudio/PipeWire), `javax.sound.midi`, Coroutines Core `1.10.2`, `de.sciss:jump3r:1.0.5`.
- **SQLite / Python Available Locally:** `python3` (`3.11.2`) with built-in `sqlite3` (`3.40.1`) and `tomllib`; `/opt/android/sdk/platform-tools/sqlite3`; `/usr/bin/git`; `java`.

---

## 3. Test Infrastructure Inventory

### `:desktop` Unit Tests (`gradle :desktop:test` — 8 suites, all pure JVM/JUnit4)
1. `DesktopStudioTest.kt`
2. `DesktopStemsAndSecretsTest.kt`
3. `DesktopCollabAndVolumeTest.kt`
4. `DesktopDrumKitTest.kt`
5. `DesktopUiScaleTest.kt`
6. `DesktopToolsAndPluginsTest.kt`
7. `DesktopFxAndLockTest.kt`
8. `DesktopLiveMonitorTest.kt`

### `:app` Pure JUnit4 Tests (runnable offline via `gradle :app:testDebugUnitTest --tests ...` — 9 suites)
1. `ExampleUnitTest.kt`
2. `audio/AudioCoreTest.kt`
3. `audio/LiveMonitorToDeckTest.kt`
4. `dsp/DspEngineTest.kt`
5. `dsp/FxChainAndLockParityTest.kt`
6. `drums/CustomDrumKitEngineTest.kt`
7. `stems/DisbandErrorPayloadTest.kt`
8. `tools/ToolsAndPluginsTest.kt`
9. `VmcAndHandoffTest.kt`

### `:app` Robolectric / Compose UI Tests (7 suites — blocked offline when SDK 36 Robolectric artifact cache is absent)
1. `ExampleRobolectricTest.kt`
2. `CollabVolumeAndImportTest.kt`
3. `drums/CustomDrumKitUiTest.kt`
4. `stems/DisbandStemsAndSecretsTest.kt`
5. `studio/Stage4StudioNetworkExportTest.kt`
6. `ui/Stage5ComposeConsoleTest.kt`
7. `ui/Stage6EqualPeerTest.kt`

### `:app` Instrumented Tests (1 suite — requires emulator/device; ADB unavailable in container)
- `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt`

---

## 4. Existing Static-Analysis & AST Capabilities

| Language / Artifact | Available Local Tooling | Analysis Capability |
|---|---|---|
| Kotlin (`.kt`, `.kts`) | `kotlin-compiler-embeddable-2.4.21.jar` & `2.2.21.jar` in local Gradle cache + `gradle` compiler + Android Lint (`lint_applet`) | Full **Kotlin PSI AST** (`KotlinCoreEnvironment`, `PsiFileFactory`, `KtTreeVisitorVoid`) for exact declarations, types, parameters, call sites, dot-qualified reads/writes, annotations, imports, and lambda handlers, combined with real `kotlinc`/Gradle warning ingestion |
| XML (`AndroidManifest.xml`, `res/**/*.xml`) | Python stdlib `xml.etree.ElementTree` | Exact XML DOM inspection of permissions, activities, services, intents, and string/theme resources |
| TOML / Gradle Catalog (`libs.versions.toml`) | Python 3.11 `tomllib` | Exact dependency & plugin catalog parsing |
| Shell (`scripts/*.sh`) | `bash -n` + POSIX parser | Syntax check and command tracing |
| Git & CI | `/usr/bin/git` installed; **no `.git` directory or `.github/workflows` currently present** | Incremental scanning must support SHA-256 + mtime fallback when `.git` is absent, and Git/CI setup can be initialized/validated in Phase 5–6 |

- **No third-party linters (`detekt`, `ktlint`)** are pre-installed locally.
- **Generated & Third-Party Sources:** `app/build/`, `desktop/build/`, `.gradle/`, `.kotlin/`, `.build-outputs/`, `gradle/wrapper/gradle-wrapper.jar` (all excluded from application source scanning except build output inspection).

---

## 5. Current Application Architecture Summary

Samplfie2 uses an **Equal Hub-and-Spoke Architecture** across `:app` and `:desktop`:
- **Audio & DSP Core:** `AudioBuffer` / `DesktopAudioBuffer`, `WavCodec` / `DesktopWavCodec`, `AudioImporter` (SAF + MediaCodec), `MicCaptureEngine` / `LinuxSoundEngine` (Hold-to-Record + Continuous Live Monitor Mode -> Deck), `AudioPlaybackEngine` (hot-swappable loop persistence), `DspModules`/`DspChainEngine`/`DesktopDspProcessor` (14 DSP effects + Octave + Analog/Reverse Delays + Stompbox macro processor), `AudioToolsEngine`/`DesktopAudioToolsEngine` (Spectrogram, Spectral Delete, Invert, Audio Diff, Mix/Mono/Stereo, Rate/Format, Plugin Host), `DrumKitManager`/`DesktopDrumKitManager` (Acoustic/Electric 7-piece kits + mounted add-ons).
- **Studio & Sequencing:** `StudioEngine` / `DesktopArrangementEngine` (4-slot Looper with sample locking, Loop Refiner, 16-step Sequencer with lane locking, 4-track Timeline, Global Synced Playhead).
- **Network, VMC, Stems & Secrets:** `Smp2NetworkNode` / `NetworkAudioTransmitter` (`SMP2`, `VMC2`, `SMH2`/`SMHR`, `SMDP`/`SMCL`/`SMRC` 4-device collab), `DisbandApiClient` / `DesktopDisbandClient` (with MP3 compaction, Reserved Upload 413 fallback, and full error diagnostics), `SecretsVaultRepository` / `DesktopSecretsVault`, `RigConfig` / `DesktopRigConfig`.
- **UI Spokes:** `MainActivity` + `SamplfieViewModel` + Compose screens/components on `:app`; `SamplfieDesktopFrame` + `DesktopStudioController` + Swing panels on `:desktop`.

---

## 6. Initial Compiler, Lint & Suppression Baseline

### 6.1 Kotlin Compiler Warnings (`gradle :desktop:compileKotlin :desktop:compileTestKotlin :app:compileDebugKotlin --rerun-tasks`)
**Errors:** `0` | **Warnings:** `7`

1. `desktop/src/main/kotlin/com/samplfie/desktop/audio/LinuxSoundEngine.kt:611:50` — `Unnecessary safe call on a non-null receiver of type 'SourceDataLine'.`
2. `desktop/src/main/kotlin/com/samplfie/desktop/audio/LinuxSoundEngine.kt:611:68` — `Unnecessary safe call on a non-null receiver of type 'SourceDataLine'.`
3. `desktop/src/main/kotlin/com/samplfie/desktop/audio/LinuxSoundEngine.kt:611:85` — `Unnecessary safe call on a non-null receiver of type 'SourceDataLine'.`
4. `app/src/main/java/com/example/audio/AudioSampleFormat.kt:54:17` — `'when' is exhaustive so 'else' is redundant here.`
5. `app/src/main/java/com/example/audio/MicCaptureEngine.kt:500:41` — `Unnecessary safe call on a non-null receiver of type 'AudioTrack'.`
6. `app/src/main/java/com/example/audio/MicCaptureEngine.kt:501:41` — `Unnecessary safe call on a non-null receiver of type 'AudioTrack'.`
7. `app/src/main/java/com/example/audio/MicCaptureEngine.kt:502:41` — `Unnecessary safe call on a non-null receiver of type 'AudioTrack'.`

### 6.2 Android Lint Findings (`gradle :app:lint`)
**Errors:** `0` | **Warnings:** `38`

- `AndroidGradlePluginVersion` (2): Gradle `9.3.1` -> `9.9.2`, AGP `9.1.1` -> `9.5.2` in `gradle/wrapper/gradle-wrapper.properties` & `gradle/libs.versions.toml`
- `GradleDependency` (23): Newer versions available for AndroidX/Firebase/Credentials libraries in `gradle/libs.versions.toml`
- `NewerVersionAvailable` (8): Newer versions available for Coroutines/Retrofit/OkHttp/Moshi/Robolectric in `gradle/libs.versions.toml`
- `IconLauncherShape` (5): Legacy `mipmap-*/ic_launcher.png` square coverage advisory across `hdpi`, `mdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`

### 6.3 Existing Diagnostic Suppressions (`@Suppress` / `@SuppressLint`)
**Total:** `10` annotations across `5` files:
- `app/src/main/java/com/example/audio/MicCaptureEngine.kt:183, 368` — `@SuppressLint("MissingPermission")`
- `app/src/main/java/com/example/midi/MidiControllerManager.kt:94, 97, 108` — `@Suppress("DEPRECATION")`
- `app/src/test/java/com/example/CollabVolumeAndImportTest.kt:79, 320` — `@Suppress("DEPRECATION")`, `@Suppress("UNCHECKED_CAST")`
- `app/src/test/java/com/example/stems/DisbandStemsAndSecretsTest.kt:127, 138` — `@Suppress("UNCHECKED_CAST")`
- `desktop/src/main/kotlin/com/samplfie/desktop/ui/DesktopFxModuleCards.kt:160` — `@Suppress("UNCHECKED_CAST")`

---

## 7. Phase 0 Known Limitations & Constraints

1. **No `.git` repository or CI workflow initially present:** `.git` and `.github/workflows/` do not exist yet in the workspace.
2. **Offline Robolectric dependency gap:** 7 Robolectric UI tests in `:app` require Android SDK 36 runtime jars not pre-cached in the offline container; pure-JUnit4 suites (`:desktop:test` + 9 `:app` JUnit4 suites) run reliably offline.
3. **No Emulator / ADB:** `androidTest/` suites cannot be executed in this container (`DEVICE_TESTING` / `UI_INSTRUMENTED` must be flagged explicitly as environment-unavailable rather than simulated as passed).
4. **Cross-module semantic equivalence (`:app` vs `:desktop`):** Both modules mirror DSP, MIDI, network, and UI concepts independently; dependency/feature tracking must map both workstations cleanly.
