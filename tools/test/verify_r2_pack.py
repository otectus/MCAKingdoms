#!/usr/bin/env python3
"""Verify the R2 pack overlay keeps the bounded paid commission's authored terms before staging.

Companion jars are checked separately by verify_companions.py (F16); historical provider-patch exports are compared only
by tools/history/verify_r2_provider_exports.py, which is a record and not a release check."""
import json,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
def main():
    quest=json.loads((ROOT/'pack/r2/kubejs/data/ultima/mcaquests/quests/civic/lamplighters/workshop_lanterns.json').read_text())
    assert quest['id']=='ultima:civic/lamplighters/workshop_lanterns' and quest['institutional_commission'] is True
    assert quest['repeat']['type']=='once' and quest['turn_in']['mode']=='original_giver'
    assert quest['rewards']==[{'type':'mcaquests:item','item':'minecraft:emerald','count':6}]
    assert quest['objectives']==[{'type':'mcaquests:item_delivery','item':'minecraft:lantern','count':4,'consume':True}]
    assert 'conditions' in quest,'Older providers must reject the new required condition'
    print('PASS R2: fixed native commission terms verified (companion contracts: verify_companions.py)')
if __name__=='__main__':main()
