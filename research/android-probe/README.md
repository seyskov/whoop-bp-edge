# WHOOP 5 signal probe

Separate native Android diagnostic in our own fork. It does not replace or
modify an installed WHOOP/Edge app. It has no Internet permission. Data collection
is foreground-only. Live optical/raw packets stay in RAM. Only shape counters
are written to app-private `files/report.json`; no serial, MAC, absolute capture
timestamp, vital measurement, or waveform is in that report.

## Build

Sibling checkout: `seyskov/whoop-bp-protocol`, full commit from
`protocol-ref.txt` (`dbc6186a1ec6d60890168184b3dcb75c39835a99`). The build
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
4. Read info/flags; optionally start **Live RAW — 45 seconds**. Remain still.
   The rev-2 command carries a strap-side 45-second timeout. STOP is also sent
   on expiry, backgrounding, or normal disconnect. Live records are only counted;
   unknown live wrappers are NOT decoded as historical optical samples.
5. Optionally preview the **first history block, no ACK** for 12 seconds.
   No positive history-result ACK is ever sent, so no flash is trimmed. This
   deliberately cannot scan the whole history. It aborts at the timeout.
6. Stop/disconnect, then reconnect the normal app if wanted.

Read the non-identifying diagnostic summary over USB:

```sh
adb shell run-as org.whoopbp.probe cat files/report.json
```

`control.py` provides USB button control restricted to this package's observed
UI buttons. `connect` refuses if more than one strap is shown. `status` reads
only the diagnostic summary. It never reads the official app's database.
After the experiment, stop/disconnect the probe before opening the normal app.

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

Upstream source reference: `protocol` at
`b4fde6ab5589e53154b808f8b2bbb4d01d9c15b5`. Build and fork commit hashes are
recorded in the feasibility report alongside this directory.
