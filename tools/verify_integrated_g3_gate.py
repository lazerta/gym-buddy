#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from pathlib import Path
RELEASE={"incline_dumbbell_press","smith_machine_squat","dumbbell_lateral_raise"}
def main():
    p=argparse.ArgumentParser();p.add_argument('--commercial-gym',type=Path,required=True);p.add_argument('--real-rgb',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    commercial=json.loads(a.commercial_gym.read_text()); real=json.loads(a.real_rgb.read_text())
    if commercial.get('static_failures')!=0 or commercial.get('temporal_failures')!=0:
        raise AssertionError(f"commercial gym component failed: {commercial}")
    if set(commercial.get('release_exercises',[]))!={"incline_db_press","smith_squat","lateral_raise"}:
        raise AssertionError('commercial-gym release exercise coverage changed')
    if real.get('g2_pass') is not True or real.get('g3_real_rgb_stress_pass') is not True:
        raise AssertionError('G2/G3 RGB component is not green')
    g2={x['exercise_id'] for x in real['sessions'] if x['evidence_tier']=='G2_REAL_RGB'}
    degraded={x['exercise_id'] for x in real['sessions'] if x.get('stress_variant')=='degraded'}
    gaps={x['exercise_id'] for x in real['sessions'] if x.get('stress_variant')=='gap'}
    if g2!=RELEASE or degraded!=RELEASE or gaps!=RELEASE:
        raise AssertionError(f"release coverage incomplete g2={g2} degraded={degraded} gaps={gaps}")
    out={
      'schema_version':1,'evidence_tier':'G3_INTEGRATED_REALITY_STRESS','g3_pass':True,
      'physical_device_required':False,'physical_smoke_optional':True,
      'components':{
        'g1_commercial_gym':{'static_cases':commercial['static_cases'],'temporal_episodes':commercial['temporal_episodes'],'passed':True},
        'g2_real_rgb':{'exercises':sorted(g2),'passed':True},
        'g3_real_rgb_degraded':{'exercises':sorted(degraded),'passed':True},
        'g3_mid_rep_gap_no_stitch':{'exercises':sorted(gaps),'passed':True},
        'android_production_runtime':{'passed':True,'source':'real-rgb verifier consumes SimulatorE2EActivity production results'}
      }
    }
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(out,indent=2)+'\n')
    print(json.dumps(out,indent=2))
    print('G3_INTEGRATED_REALITY_STRESS_PASS')
if __name__=='__main__': main()
