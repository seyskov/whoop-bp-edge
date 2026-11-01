# WHOOP 5 signal probe

Separate native Android diagnostic in our own fork. It does not replace or
modify an installed WHOOP/Edge app and has no Internet permission. Data collection
is foreground-only. **Live optical/raw packets stay in RAM.** Only diagnostic
statistics are written to `files/report.json`; no serial, MAC, absolute capture
timestamp, individual HR, or waveform is in that summary. Requested historical
R26 windows and matching R18 references can separately be saved to the app-private
`historical-windows.json` for local analysis. Never commit either user's capture.

## Build

Sibling checkout: `seyskov/whoop-bp-protocol`, full commit from
`protocol-ref.txt`. The build
checks the commit and rejects uncommitted decoder changes. Its research Java module is the decoder source;
no extra decode path is added to the Flutter app or its analytics.

```sh
python3 research/android-probe/build.py
```

Requires JDK 17 and Android SDK (the script defaults to the SDK/JDK found on
the development Mac). Set `PROBE_JDK`, `ANDROID_SDK_ROOT`, `PROBE_PROTOCOL` to
override. No Flutter, Gradle, Firebase, signing-account or cloud credential.
The APK is test-signed with an ignored local key, package `org.whoopbp.probe`.

## Use

1. Close WHOOP and Edge so they release the BLE connection. Do not unpair or
   reset the strap. If not discoverable, put WHOOP into blue-LED pairing mode.
2. Install `build/whoop-signal-probe.apk`, open it and allow Nearby Devices.
3. Tap **Find WHOOP 5**, then tap the device button. Successful HELLO is required
   before any probe starts. A blue-LED bond prompt may need confirmation.
4. Read info/flags. A successful HELLO records the device's own worn state;
   the report also carries the age of that observation. Flag values are read
   only after checking success, revision, echoed key and ASCII boolean encoding.
5. **Live RAW — 45 seconds** explicitly turns on the compact HR stream (0x03),
   optical live (0x6C) and bounded rev-2 RAW (0x51). The rev-2 command carries a
   strap-side 45-second timeout. STOP (0x52), optical OFF and HR OFF are sent
   on expiry, backgrounding, or normal disconnect. No 0x6B history-save toggle,
   persistent 0x99 optical save or NVM/config write. Unknown live wrappers are
   never decoded as historical samples.
6. Optionally run the **R26 shape audit** for 120 seconds. HELLO is polled every
   15 seconds during this capture. No positive history-result ACK is sent by
   this probe. It cannot scan the whole history and aborts at the timeout.
   Historical R26 storage is bounded to 2048 windows, deduplicated and keeps
   saturated-delta ambiguity. File writing uses a worker. A capture ID prevents
   the USB exporter from returning a stale previous session's file.
7. Stop/disconnect, then reconnect the normal app if wanted.

Read the non-identifying diagnostic summary over USB:

```sh
adb shell run-as org.whoopbp.probe cat files/report.json
```

`control.py` provides USB button control restricted to this package's observed
UI buttons. `connect` refuses if more than one strap is shown. `status` reads
only the diagnostic summary. It never reads the official app's database.
After the experiment, stop/disconnect the probe before opening the normal app.

For a local **historical** R26 capture (never a live export):

```sh
python3 research/android-probe/control.py wave --output research/android-probe/captures/r26.json
python3 research/android-probe/analyze_wave.py research/android-probe/captures/r26.json --output-dir research/android-probe/captures/analysis
```

`analyze_wave.py` leaves gaps empty, separates changed-rate/config windows and
does not return a BP estimate. It compares broad 0.5–4 Hz filtered pulse content
with matched device HR, reports heuristic interval consistency, and explicitly
does not validate notch/upstroke morphology or pressure accuracy. Device HR is
not an independent ECG reference. A zero-phase filter and two-second edge trim
are shown in the local plot; filtering can change morphology. Its plots stay local. Live type-20 inspection
instead runs entirely in RAM on the phone: strict 2128-byte geometry, per-path
samples/timing, clipping, flatness, changing AFE descriptors, boundary-step
ratios and spectral corroboration against the separate compact HR stream.
Spectral work runs on a worker after STOP. Reports contain no sample arrays.
The local phone chart draws buffers separately, not a continuous interpolated line.

The optical live toggle matters: bounded RAW alone did not yield a live optical
stream in the initial experiment. Do not mistake an accepted START_RAW reply
for proof that optical notifications are enabled.

## What the result means

- R18 is a per-second summary. Its declared 25/50 Hz sensor-rate flag is NOT a
  continuous waveform in the exported record.
- R20 is an optical buffer with explicit rate, 5 front-end blocks, 2 detector
  paths per block and current/range/offset metadata. Check active paths, gaps,
  clipping and configuration changes before considering any model.
- R26 PIP is episodic: one absolute ADC code + 24 **saturated deltas**, giving
  a 25-sample window, not a 24 Hz stream. The rate flag says 25 or 50 Hz. A
  saturated delta makes all later samples ambiguous. The probe counts those
  windows and impossible reconstructions; it never joins them into a wave.
- Missing optical packets here means **not observed in this bounded preview**.
  It does not mean this hardware cannot export them. Deep-buffer enabling is a
  separate persistent and partly irreversible operation, absent from this APK.
- PPG present does not establish suitability for BP estimation, and certainly
  does not validate an absolute mmHg number. No BP model is included.

The decoder's JVM tests replay the existing upstream golden capture and cover
fragmentation, both saturation rails, corrupt CRCs, unknown revisions, duplicate
history and forbidden write/trim/ACK commands.
Additional waveform tests cover missing seconds, changed rates/states, flat
data, known sinusoid/reference agreement, unknown/truncated records and the
RAM-only boundary. Python analysis tests use synthetic captures, never user data.

Upstream source reference: `protocol` at
`b4fde6ab5589e53154b808f8b2bbb4d01d9c15b5`. Build and fork commit hashes are
recorded in the feasibility report alongside this directory.
