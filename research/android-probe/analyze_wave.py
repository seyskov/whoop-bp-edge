#!/usr/bin/env python3
"""Local historical R26 integrity analysis. Not a blood-pressure estimator.
Raw ADC and reference HR are read locally; stdout/summary omit individual HR
values and wave samples. No network or imputation. A plot is a local artifact.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np
from scipy import signal

ADC_MIN, ADC_MAX = -524288, 524287
TOLERANCE_SECONDS = 2 / 32768

def distribution(values):
    a = np.asarray(values, dtype=float)
    a = a[np.isfinite(a)]
    return {"n": int(a.size), "median": float(np.median(a)) if a.size else None,
            "p95": float(np.quantile(a, .95)) if a.size else None,
            "max": float(np.max(a)) if a.size else None}

def decode_windows(payload):
    if payload.get("record_source") != "historical_0x2f_only":
        raise ValueError("Expected historical-only capture")
    out = []
    for original in payload.get("windows", []):
        w = dict(original)
        if w.get("rate_hz_declared") not in (25, 50):
            raise ValueError("Unknown declared rate; do not infer from window length")
        deltas = np.asarray(w["deltas"], dtype=np.int64)
        if deltas.shape != (24,) or np.any(deltas < -32768) or np.any(deltas > 32767):
            raise ValueError("Malformed delta window")
        if not np.isfinite(w["t_seconds"]):
            raise ValueError("Invalid timestamp")
        samples = np.r_[int(w["first_adc"]), int(w["first_adc"]) + np.cumsum(deltas)]
        rails = np.flatnonzero((deltas == -32768) | (deltas == 32767))
        trusted = int(rails[0] + 1) if rails.size else 25
        impossible = bool(np.any((samples < ADC_MIN) | (samples > ADC_MAX)))
        w.update(samples=samples, delta_array=deltas, trusted_prefix=trusted,
                 encoding_usable=(trusted == 25 and not impossible),
                 impossible=impossible, fs=int(w["rate_hz_declared"]))
        out.append(w)
    return sorted(out, key=lambda w: (w["t_seconds"], w["record_index_relative"]))

def segments(windows, require_same_state=True):
    """Only timestamp/rate/counter-compatible windows; never fill a gap.
    This is a hypothesis about record timestamps, NOT proof of physical sample timing.
    """
    result = []
    for w in windows:
        if not w["encoding_usable"]:
            continue
        if result:
            previous = result[-1][-1]
            gap = w["t_seconds"] - previous["t_seconds"] - 25 / previous["fs"]
            counter_step = (w["record_index_relative"] - previous["record_index_relative"]) & 0xffffffff
            if (abs(gap) <= TOLERANCE_SECONDS and previous["fs"] == w["fs"] and counter_step == 1
                    and (not require_same_state or previous["channel_state_word"] == w["channel_state_word"])):
                result[-1].append(w)
                continue
        result.append([w])
    return result

def pulse_corroboration(y, fs, reference_hr):
    """Exploratory pulse-content checks; no BP/no clinical quality threshold.
    Device-HR neighborhood selection is reported as such, never an independent
    prediction. The unconstrained peak/interval error remains visible too.
    """
    reference_hr = [h for h in reference_hr if h is not None and np.isfinite(h) and h > 0]
    filtered=signal.sosfiltfilt(signal.butter(4, [.5, 4], fs=fs, btype="bandpass", output="sos"), y)
    trimmed=filtered[2*fs:-2*fs]
    f,p=signal.welch(trimmed, fs=fs, nperseg=min(len(trimmed), fs*16))
    band=(f>=.5)&(f<=4)
    out={"filter":"zero_phase_butterworth_order4_0.5_to_4Hz", "edge_trim_seconds":2,
         "clinical_quality_threshold_applied":False}
    if not reference_hr or np.ptp(trimmed)==0:
        out.update(reference_available=False, filtered_peak_error_pct=None,
                   reference_neighborhood_power_contrast=None, candidate_interval_error_pct=None)
        return out
    reference=float(np.median(reference_hr));ref_hz=reference/60
    resolution=float(f[1]-f[0]);width=max(resolution, .1*ref_hz)
    near=band & (np.abs(f-ref_hz)<=width)
    other=band & (np.abs(f-ref_hz)>2*width)
    peak=f[band][np.argmax(p[band])]
    noise=float(np.median(p[other])) if np.any(other) else 0
    contrast=float(np.max(p[near])/noise) if np.any(near) and noise>0 else None
    expected=fs*60/reference
    peaks,_=signal.find_peaks(trimmed, distance=max(1,int(.5*expected)), prominence=.2*np.std(trimmed))
    intervals=np.diff(peaks)/fs
    period=60/reference
    out.update(reference_available=True, filtered_peak_error_pct=abs(peak*60-reference)/reference*100,
               reference_neighborhood_power_contrast=contrast,
               reference_neighborhood_halfwidth_hz=width,
               reference_neighborhood_power_fraction=float(p[near].sum()/p[band].sum()) if p[band].sum()>0 else None,
               candidate_peaks=len(peaks),
               candidate_interval_error_pct=abs(float(np.median(intervals))-period)/period*100 if len(intervals) else None,
               candidate_intervals_within_20pct_reference_fraction=float(np.mean(np.abs(intervals-period)<=.2*period)) if len(intervals) else None,
               candidate_interval_cv=float(np.std(intervals)/np.mean(intervals)) if len(intervals) and np.mean(intervals)>0 else None,
               peak_finder="distance>=0.5_device_period;prominence>=0.2_filtered_SD;exploratory")
    return out

def analyze(payload):
    windows = decode_windows(payload)
    reference = payload.get("reference", [])
    reference_by_index = {r["record_index_relative"]: r for r in reference}
    summary = {
        "status": "observed" if windows else "no_windows",
        "bp_suitability_validated": False, "clinical_validation_performed": False,
        "timestamp_start_alignment_is_assumed": True, "windows": len(windows),
        "declared_rates_hz": sorted({w["fs"] for w in windows}),
        "flat_windows": sum(int(np.ptp(w["samples"]) == 0) for w in windows),
        "saturated_delta_windows": sum(int(w["trusted_prefix"] < 25) for w in windows),
        "impossible_windows": sum(int(w["impossible"]) for w in windows),
        "absolute_adc_rail_samples": sum(int(np.sum((w["samples"] == ADC_MIN) | (w["samples"] == ADC_MAX))) for w in windows),
        "processing_saturation_flag_windows": sum(int(w["channel_state_word"] & 0xf000 != 0) for w in windows),
        "morphology_pass_windows": sum(int(w["morphology_raw"] == 1) for w in windows),
        "exactly_repeated_sample_shapes": len(windows) - len({tuple(w["samples"]) for w in windows}),
    }
    pairs, jumps, gaps, overlaps, shape_correlations = [], [], [], [], []
    state_changes = 0
    for a, b in zip(windows, windows[1:]):
        gap = b["t_seconds"] - a["t_seconds"] - 25 / a["fs"]
        state_changes += int(a["channel_state_word"] != b["channel_state_word"])
        if gap > TOLERANCE_SECONDS:
            gaps.append(gap)
        elif gap < -TOLERANCE_SECONDS:
            overlaps.append(-gap)
        elif a["fs"] == b["fs"] and a["encoding_usable"] and b["encoding_usable"]:
            step = abs(float(b["samples"][0]) - float(a["samples"][-1]))
            scale = np.quantile(np.r_[np.abs(a["delta_array"]), np.abs(b["delta_array"])], .95)
            jumps.append(step)
            if scale > 0:
                pairs.append(step / scale)
            if np.std(a["samples"]) > 0 and np.std(b["samples"]) > 0:
                shape_correlations.append(float(np.corrcoef(a["samples"], b["samples"])[0, 1]))
    chains = segments(windows)
    temporal_chains = segments(windows, require_same_state=False)
    chain_durations = [len(c) * 25 / c[0]["fs"] for c in chains]
    temporal_durations = [len(c) * 25 / c[0]["fs"] for c in temporal_chains]
    summary.update(
        timestamp_gap_pairs=len(gaps), timestamp_overlap_pairs=len(overlaps),
        gap_seconds=distribution(gaps), overlap_seconds=distribution(overlaps),
        edge_jump_over_adjacent_intra_p95=distribution(pairs),
        adjacent_window_shape_correlation=distribution(shape_correlations),
        state_word_change_pairs=state_changes,
        stable_state_chains=len(chains), longest_stable_state_chain_seconds=max(chain_durations, default=0),
        longest_timestamp_compatible_chain_seconds=max(temporal_durations, default=0),
        matched_device_hr_records=sum(int(w["record_index_relative"] in reference_by_index and
            reference_by_index[w["record_index_relative"]].get("device_hr_bpm") is not None) for w in windows),
    )
    # Spectral corroboration uses only complete, same-rate/state chains >=8s.
    # No BP, notch, vascular age, or mmHg inference is performed.
    periodicity = []
    for chain, duration in zip(chains, chain_durations):
        if duration < 8:
            continue
        y = np.concatenate([w["samples"] for w in chain]).astype(float)
        if np.ptp(y) == 0:
            continue
        fs = chain[0]["fs"]
        frequencies, psd = signal.welch(signal.detrend(y), fs=fs, nperseg=min(len(y), fs * 16))
        cardiac = (frequencies >= .5) & (frequencies <= 4)
        if not np.any(cardiac) or psd[cardiac].sum() <= 0:
            continue
        f = frequencies[cardiac][np.argmax(psd[cardiac])]
        peak_fraction = float(psd[cardiac].max() / psd[cardiac].sum())
        hr = [reference_by_index[w["record_index_relative"]].get("device_hr_bpm") for w in chain
              if w["record_index_relative"] in reference_by_index]
        hr = [h for h in hr if h is not None and np.isfinite(h) and h > 0]
        # Individual device HR or peak-frequency values are kept out of summary.
        error = abs(f * 60 - np.median(hr)) / np.median(hr) * 100 if hr else None
        periodicity.append({"duration_seconds": duration, "resolution_hz": float(frequencies[1]-frequencies[0]),
                            "peak_band_power_fraction": peak_fraction,
                            "device_hr_absolute_percent_error": float(error) if error is not None else None,
                            "reference_count": len(hr),
                            "filtered_pulse_corroboration":pulse_corroboration(y, fs, hr)})
    summary["spectral_corroboration"] = periodicity
    summary["notes"] = [
        "Same-state timestamp compatibility is not proof of sample-level continuity.",
        "State word is not a complete optical AFE/photodiode descriptor.",
        "Device HR is not independent reference ECG; agreement only corroborates cardiac content.",
        "No reference blood-pressure data, model fit, calibration, or clinical validation here.",
    ]
    return summary, windows

def plot(payload, windows, summary, target):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
    fig, axes = plt.subplots(4, 1, figsize=(14, 13), constrained_layout=True)
    fig.suptitle("WHOOP 5.0 · R26 optical windows — local integrity check", fontsize=17)
    if not windows:
        for ax in axes:
            ax.text(.5, .5, "No historical R26 windows observed in this capture", ha="center", va="center", transform=ax.transAxes)
        fig.savefig(target, dpi=160)
        plt.close(fig)
        return
    base = windows[0]["t_seconds"]
    baseline = np.median(np.concatenate([w["samples"][:w["trusted_prefix"]] for w in windows]))
    for i, w in enumerate(windows):
        time = w["t_seconds"] - base + np.arange(25) / w["fs"]
        y = w["samples"].astype(float) - baseline
        y[w["trusted_prefix"]:] = np.nan
        if w["impossible"]:
            y[:] = np.nan
        color = "#235ab6" if i % 2 == 0 else "#00876c"
        if time[0] < 20:
            axes[0].plot(time, y, color=color, lw=1.2, marker=".", ms=2)
            axes[0].axvline(time[0], color="#d4d8e0", lw=.5, zorder=0)
        axes[1].broken_barh([(time[0], 25 / w["fs"])], (.15, .7),
                           facecolors=color if w["encoding_usable"] else "#cb4a4a")
    axes[0].set(title="First 20 seconds · raw scale · windows drawn separately, no gap filling", xlabel="Relative time (s)", ylabel="ADC minus recording median")
    axes[0].set_xlim(0, min(20, windows[-1]["t_seconds"] - base + 25 / windows[-1]["fs"]))
    axes[1].set(title="Record coverage · color alternates at each window; red = unusable encoding",
                xlabel="Relative record time (s)", yticks=[], ylim=(0, 1))
    exemplars = [w for w in windows if w["encoding_usable"] and np.ptp(w["samples"]) > 0][:8]
    for i, w in enumerate(exemplars):
        y = w["samples"].astype(float)
        axes[2].plot(np.arange(25) / w["fs"], (y - np.mean(y)) / np.ptp(y), marker=".", ms=3, lw=1, label=f"window {i+1}")
    axes[2].set(title="Within-window shapes · each centered/scaled for display only; not stitched",
                xlabel="Time within window (s)", ylabel="Centered ADC / window peak-to-peak")
    if exemplars:
        axes[2].legend(ncol=4, fontsize=8)
    # Display the same broad, reference-independent filter used in the audit.
    # Never bridge a missing window or a changed optical state.
    chains = segments(windows)
    chain = max(chains, key=lambda c: len(c) * 25 / c[0]["fs"], default=[])
    if chain and len(chain) * 25 / chain[0]["fs"] >= 8:
        fs = chain[0]["fs"]
        y = np.concatenate([w["samples"] for w in chain]).astype(float)
        filtered = signal.sosfiltfilt(signal.butter(4, [.5, 4], fs=fs, btype="bandpass", output="sos"), y)
        start, end = 2 * fs, min(len(y) - 2 * fs, 14 * fs)
        t = chain[0]["t_seconds"] - base + np.arange(start, end) / fs
        axes[3].plot(t, filtered[start:end], color="#8246a6", lw=1.2, marker=".", ms=3)
        axes[3].set(title="Pulse content after 0.5–4 Hz filtering · 2 s edge trim · not BP morphology validation",
                    xlabel="Relative time (s)", ylabel="Filtered ADC")
    else:
        axes[3].text(.5, .5, "No compatible chain long enough for filtered view", ha="center", transform=axes[3].transAxes)
    fig.savefig(target, dpi=170)
    plt.close(fig)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("capture", type=Path)
    p.add_argument("--output-dir", type=Path, required=True)
    args = p.parse_args()
    payload = json.loads(args.capture.read_text())
    summary, windows = analyze(payload)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    result = args.output_dir / "waveform-quality.json"
    result.write_text(json.dumps(summary, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    preview = args.output_dir / "waveform.png"
    plot(payload, windows, summary, preview)
    print(json.dumps({"summary": str(result), "plot": str(preview), "status": summary["status"], "windows": len(windows)}))

if __name__ == "__main__":
    main()
