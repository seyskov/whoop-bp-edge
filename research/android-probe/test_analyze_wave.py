import numpy as np
import pytest
from analyze_wave import analyze, decode_windows, segments

def capture(n=16, fs=25, frequency=1.5):
    all_samples=np.round(10000+1000*np.sin(2*np.pi*frequency*np.arange(n*25)/fs)).astype(int)
    windows=[]
    for i in range(n):
        y=all_samples[i*25:(i+1)*25]
        windows.append(dict(t_seconds=i*25/fs, record_index_relative=i, rate_hz_declared=fs,
                            first_adc=int(y[0]), deltas=np.diff(y).tolist(), channel_state_word=0,
                            morphology_raw=1))
    refs=[dict(record_index_relative=i, t_seconds=i*25/fs, device_hr_bpm=90) for i in range(n)]
    return dict(record_source="historical_0x2f_only", windows=windows, reference=refs)

def test_known_sinusoid_with_independent_reference_and_no_missing_samples():
    s,_=analyze(capture())
    assert s["longest_stable_state_chain_seconds"]==16
    assert s["timestamp_gap_pairs"]==0
    assert s["spectral_corroboration"][0]["device_hr_absolute_percent_error"]<1
    assert s["bp_suitability_validated"] is False
    assert s["spectral_corroboration"][0]["filtered_pulse_corroboration"]["filtered_peak_error_pct"]<1

def test_gap_not_interpolated_and_state_switch_splits():
    p=capture(16)
    p["windows"][8]["t_seconds"]+=.1
    s,w=analyze(p)
    assert s["timestamp_gap_pairs"]==1 and s["timestamp_overlap_pairs"]==1
    p=capture(16)
    p["windows"][8]["channel_state_word"]=0x100
    assert max(map(len,segments(decode_windows(p))))==8

def test_lossy_rail_tail_not_treated_as_measured():
    p=capture(2)
    p["windows"][0]["deltas"][5]=32767
    s,w=analyze(p)
    assert s["saturated_delta_windows"]==1
    assert w[0]["trusted_prefix"]==6
    assert len(segments(w))==1 and len(segments(w)[0])==1

def test_50hz_count_not_confused_with_25hz_and_no_data_abstains():
    p=capture(4,fs=50)
    s,_=analyze(p)
    assert s["longest_stable_state_chain_seconds"]==2
    p["windows"]=[]
    s,_=analyze(p)
    assert s["status"]=="no_windows" and s["spectral_corroboration"]==[]

def test_malformed_unknown_source_and_identical_flat_data():
    p=capture(2)
    p["record_source"]="live_0x2b"
    with pytest.raises(ValueError):
        analyze(p)
    p=capture(2)
    for w in p["windows"]:
        w["deltas"]=[0]*24
        w["first_adc"]=100
    s,_=analyze(p)
    assert s["flat_windows"]==2 and s["exactly_repeated_sample_shapes"]==1

def test_drift_does_not_hide_synthetic_reference_pulse_and_missing_reference_abstains():
    p=capture(32)
    all_samples=np.round(10000+1000*np.sin(2*np.pi*1.5*np.arange(800)/25)+10000*np.sin(2*np.pi*.1*np.arange(800)/25)).astype(int)
    for i,w in enumerate(p["windows"]):
        y=all_samples[i*25:(i+1)*25]
        w["first_adc"]=int(y[0]);w["deltas"]=np.diff(y).tolist()
    s,_=analyze(p)
    filtered=s["spectral_corroboration"][0]["filtered_pulse_corroboration"]
    assert filtered["filtered_peak_error_pct"]<5
    assert filtered["candidate_interval_error_pct"]<5
    p["reference"]=[]
    s,_=analyze(p)
    assert s["spectral_corroboration"][0]["filtered_pulse_corroboration"]["reference_available"] is False
    p=capture(16)
    for r in p["reference"]:
        r["device_hr_bpm"]=0
    s,_=analyze(p)
    assert s["spectral_corroboration"][0]["device_hr_absolute_percent_error"] is None
    assert s["spectral_corroboration"][0]["filtered_pulse_corroboration"]["reference_available"] is False
