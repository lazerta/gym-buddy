#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from pathlib import Path

RELEASE={"incline_dumbbell_press","smith_machine_squat","dumbbell_lateral_raise"}

def load_results(path:Path):
    data=json.loads(path.read_text())
    if not isinstance(data,list): raise ValueError(f"{path}: expected JSON list")
    return data

def verify(session_root:Path, results_path:Path):
    ev=json.loads((session_root/"evidence.json").read_text())
    manifest=json.loads((session_root/"manifest.json").read_text())
    results=load_results(results_path)
    if len(results)!=len(manifest["frames"]):
        raise AssertionError(f"{session_root.name}: result count {len(results)} != frame count {len(manifest['frames'])}")
    rep_ordinals=[]; max_rep_count=0; tracking_failures=0; abstentions=0; pose_frames=0; camera_ready=0
    for frame,result in zip(manifest["frames"],results):
        if result["session_id"]!=manifest["session_id"] or result["frame_id"]!=frame["frame_id"] or result["timestamp_us"]!=frame["timestamp_us"]:
            raise AssertionError(f"{session_root.name}: transport identity mismatch at {frame['frame_id']}")
        a=result.get("analysis") or {}
        max_rep_count=max(max_rep_count,int(a.get("rep_count",0) or 0))
        if int(a.get("pose_count",0))>0: pose_frames+=1
        state=a.get("tracking_state")
        if state not in {"OBSERVABLE","DEGRADED"}: tracking_failures+=1
        if state in {"UNKNOWN","PAUSED"}: abstentions+=1
        if a.get("camera_guidance")=="CAMERA_READY": camera_ready+=1
        for e in a.get("rep_events") or []:
            if e.get("kind")=="COMPLETED" or e.get("classification") is not None:
                rep_ordinals.append(int(e.get("ordinal",len(rep_ordinals)+1)))
    completed=max(max_rep_count,max(rep_ordinals,default=0))
    expected=int(ev.get("expected_reps",ev.get("independent_label",{}).get("expected_reps",1)))
    if pose_frames < max(3,int(len(results)*.55)):
        raise AssertionError(f"{session_root.name}: pose coverage too low {pose_frames}/{len(results)}")
    if ev["evidence_tier"]=="G2_REAL_RGB":
        if completed!=expected:
            raise AssertionError(f"{session_root.name}: expected {expected} rep from independent label, production completed {completed}")
        if camera_ready==0:
            raise AssertionError(f"{session_root.name}: camera setup never became ready")
    elif ev.get("stress_variant")=="degraded":
        if completed!=1:
            raise AssertionError(f"{session_root.name}: degraded observable session lost/added rep: {completed}")
    elif ev.get("stress_variant")=="gap":
        if completed!=0:
            raise AssertionError(f"{session_root.name}: production stitched a rep across a forced mid-rep gap: {completed}")
    return {
        "session_id":session_root.name,"exercise_id":ev["exercise_id"],"evidence_tier":ev["evidence_tier"],
        "stress_variant":ev.get("stress_variant"),"frames":len(results),"pose_frames":pose_frames,
        "tracking_failures":tracking_failures,"abstentions":abstentions,"camera_ready_frames":camera_ready,
        "completed_reps":completed,"expected_reps":expected,
    }

def main():
    p=argparse.ArgumentParser();p.add_argument("--sessions-root",type=Path,required=True);p.add_argument("--results-root",type=Path,required=True);p.add_argument("--output",type=Path,required=True);a=p.parse_args()
    rows=[]
    for session in sorted(x for x in a.sessions_root.iterdir() if x.is_dir() and (x/"evidence.json").exists()):
        result=a.results_root/f"{session.name}.json"
        if not result.exists(): raise FileNotFoundError(result)
        rows.append(verify(session,result))
    g2={r["exercise_id"] for r in rows if r["evidence_tier"]=="G2_REAL_RGB"}
    if g2!=RELEASE: raise AssertionError(f"G2 release exercise coverage mismatch: {g2}")
    degraded={r["exercise_id"] for r in rows if r.get("stress_variant")=="degraded"}
    gaps={r["exercise_id"] for r in rows if r.get("stress_variant")=="gap"}
    if degraded!=RELEASE or gaps!=RELEASE: raise AssertionError("G3 real-RGB stress variants must cover every release exercise")
    out={"schema_version":1,"g2_pass":True,"g3_real_rgb_stress_pass":True,"sessions":rows}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(out,indent=2)+"\n")
    print(json.dumps(out,indent=2))
    print("G2_REAL_RGB_PASS")
    print("G3_REAL_RGB_STRESS_PASS")
if __name__=="__main__": main()
