"""Validate recorded production evidence, without certifying physical fit."""
import argparse
import json
import math

def positive(v):
    return type(v) in (int,float) and math.isfinite(v) and v>0

def check(record):
    errors=[]
    for key,value in {'schema_version':1,'units':'mm','axilla_excluded':True}.items():
        if record.get(key)!=value: errors.append('record '+key)
    if record.get('side') not in ('left','right'): errors.append('record arm side')
    for key in ('coverage','seam_location'):
        if not record.get(key): errors.append('record '+key)
    stations=record.get('stations',[])
    if not isinstance(stations,list): stations=[]
    if len(stations)<5: errors.append('record five measured stations')
    previous=-1;names=set()
    for station in stations:
        if not isinstance(station,dict): errors.append('invalid station');continue
        name=station.get('name')
        if not isinstance(name,str) or name in names: errors.append('unique station names required')
        else: names.add(name)
        y=station.get('y_mm')
        if type(y) not in (int,float) or not math.isfinite(y) or y<0 or y<=previous: errors.append('station heights must increase')
        else: previous=y
        if not positive(station.get('circumference_mm')): errors.append('invalid circumference')
        if not station.get('posture'): errors.append('record posture')
    if not {'elbow','wrist'}<=names: errors.append('record elbow and wrist landmarks')
    shoulder=record.get('shoulder') or {}
    if not shoulder.get('fit_method') or not all(positive(shoulder.get(k)) for k in ('attachment_arc_mm','cap_height_mm')): errors.append('record shoulder fitting')
    if not all((record.get('layers') or {}).get(k) for k in ('art','shade','guides')): errors.append('separate art,shade,guides')
    for key in ('registered_master','guide_free_transfer','seam_review','paper_wrap','body_fit','artist_signoff'):
        if (record.get('checks') or {}).get(key) is not True: errors.append('unverified: '+key)
    p=record.get('print') or {}
    if not positive(p.get('dpi')): errors.append('record printer dpi')
    if p.get('scale_percent')!=100: errors.append('calibrated 100 percent required')
    if p.get('calibration_mm')!=100: errors.append('verify 100 mm print test')
    if p.get('mirror_verified') is not True: errors.append('verify orientation')
    return dict(record_complete=not errors,physical_fit_certified=False,blockers=errors)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('record');args=parser.parse_args()
    with open(args.record) as f: result=check(json.load(f))
    print(json.dumps(result,indent=2));raise SystemExit(0 if result['record_complete'] else 1)
