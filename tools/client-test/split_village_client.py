#!/usr/bin/env python3
"""Open a copy of an Ultima world saved by 0.1.1, where one village was split in two, in an isolated full-pack
client with the current builds; walk across the village and require one entry title naming the surviving settlement."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, time, uuid
from production_client import allowed
import sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'test')); import environment  # F16: prerequisites from the environment
ROOT=pathlib.Path(__file__).resolve().parents[2]
PROJECTS=ROOT.parent
PACK=environment.pack()  # MCAKINGDOMS_PACK
INSTALL = environment.install()  # MCAKINGDOMS_MC_INSTALL

def hashes(folder):
    return {str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*')) if p.is_file()}

def properties(project):
    return dict(l.split('=',1) for l in (project/'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--work-dir',required=True,type=pathlib.Path)
    parser.add_argument('--source-world',type=pathlib.Path,default=PACK/'saves/New World (21)')
    parser.add_argument('--xvfb',type=pathlib.Path,default=environment.xvfb(required=False))
    args=parser.parse_args()
    work=args.work_dir.resolve()
    if work.exists():raise SystemExit(f'Refusing to reuse {work}')
    if not args.xvfb.is_file():raise SystemExit(f'No Xvfb at {args.xvfb}')
    work.mkdir(parents=True);(work/'natives').mkdir()
    source_hashes=hashes(args.source_world)
    for directory in ['mods','config','defaultconfigs','kubejs','resourcepacks','datapacks','global_packs','paxi']:
        if (PACK/directory).is_dir():shutil.copytree(PACK/directory,work/directory)
    for name in ['options.txt','optionsof.txt']:
        if (PACK/name).is_file():shutil.copy2(PACK/name,work/name)
    shutil.copytree(args.source_world,work/'saves/split-village-world-copy')
    kingdoms,quests=properties(ROOT),properties(PROJECTS/'MCAQuests')
    replacements=[ROOT/'build/libs'/f"{kingdoms['mod_id']}-{kingdoms['mod_version']}.jar",
                  PROJECTS/'MCAQuests/build/libs'/f"{quests['mod_id']}-{quests['mod_version']}.jar",
                  ROOT/'build/test-artifacts'/f"{kingdoms['mod_id']}-{kingdoms['mod_version']}-client-acceptance.jar"]
    for jar in replacements:
        if not jar.is_file():raise SystemExit(f'Missing build artifact {jar}')
    for stale in [*(work/'mods').glob(f"{kingdoms['mod_id']}-*.jar"),*(work/'mods').glob(f"{quests['mod_id']}-*.jar")]:stale.unlink()
    for jar in replacements:shutil.copy2(jar,work/'mods'/jar.name)
    # A new player would first get MCA's character creation, which pauses the integrated world; skip it in the copy only.
    for config in (work/'config').glob('*mca*.json'):
        data=json.loads(config.read_text())
        if 'launchIntoDestiny' in data:
            data['launchIntoDestiny']=False;config.write_text(json.dumps(data,indent=2)+'\n')
            (work/'fixture-overrides.json').write_text(json.dumps({'config':str(config.relative_to(work)),'launchIntoDestiny':False,'purpose':'skip first-login character creation for the walking player'},indent=2)+'\n')
    (work/'artifacts.json').write_text(json.dumps(hashes(work/'mods'),indent=2))
    (work/'source-world-before.json').write_text(json.dumps(source_hashes,indent=2))
    vanilla=json.loads((INSTALL/'versions/1.20.1/1.20.1.json').read_text());forge=json.loads((INSTALL/'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries={}
    for lib in vanilla['libraries']+forge['libraries']:
        if allowed(lib.get('rules')):libraries[':'.join(lib['name'].split(':')[:2]) + (':'+lib['name'].split(':')[3] if len(lib['name'].split(':'))>3 else '')]=lib
    cp=[str(INSTALL/'libraries'/lib['downloads']['artifact']['path']) for lib in libraries.values() if lib.get('downloads',{}).get('artifact')]+[str(INSTALL/'versions/1.20.1/1.20.1.jar')]
    values={'auth_player_name':'SplitVillageTest','version_name':'forge-47.4.23','game_directory':str(work),'assets_root':str(INSTALL/'assets'),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':uuid.uuid3(uuid.NAMESPACE_DNS,'OfflinePlayer:SplitVillageTest').hex,'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','natives_directory':str(work/'natives'),'launcher_name':'KingdomsAcceptance','launcher_version':'1','classpath':':'.join(cp),'classpath_separator':':','library_directory':str(INSTALL/'libraries')}
    def expand(arguments):
        result=[]
        for arg in arguments:
            if isinstance(arg,dict):
                if not allowed(arg.get('rules')):continue
                arg=arg['value']
            for value in arg if isinstance(arg,list) else [arg]:
                value=re.sub(r'\$\{([^}]+)\}',lambda m:values[m[1]],value)
                if value.startswith('-DignoreList='):value+=',1.20.1.jar'
                result.append(value)
        return result
    command=['java','-Xmx8G',f'-Dmcakingdoms.clientTest.output={work}','-Dmcakingdoms.clientTest.splitVillage=true']+expand(vanilla['arguments']['jvm']+forge['arguments']['jvm'])+[forge['mainClass']]+expand(vanilla['arguments']['game']+forge['arguments']['game'])+['--width','1280','--height','800']
    (work/'launch-command.json').write_text(json.dumps(command,indent=2))
    environment=os.environ.copy();environment.pop('WAYLAND_DISPLAY',None);environment.update(DISPLAY=':97',LIBGL_ALWAYS_SOFTWARE='1')
    with (work/'xvfb.log').open('w') as xlog,(work/'client.log').open('w') as log:
        display=subprocess.Popen([str(args.xvfb),':97','-screen','0','1600x1000x24','-nolisten','tcp','-ac'],stdout=xlog,stderr=xlog)
        try:
            time.sleep(1)
            completed=subprocess.run(command,cwd=work,env=environment,stdout=log,stderr=subprocess.STDOUT,timeout=1100)
        finally:
            display.terminate();display.wait(timeout=15)
            after=hashes(args.source_world);(work/'source-world-after.json').write_text(json.dumps(after,indent=2))
            if source_hashes!=after:raise RuntimeError('Source world changed during isolated validation')
    summaries=[line for line in (work/'client.log').read_text(errors='replace').splitlines() if 'Village reconciliation (' in line]
    print('\n'.join(summaries))
    if completed.returncode or not (work/'SPLIT_VILLAGE_PASS.txt').exists():raise SystemExit(f'Split village client run failed: {work}/client.log')
    if not any('(startup): merged 1 split record(s) into 1 village(s)' in line for line in summaries):raise SystemExit('No start-up merge of the split village in the client log')
    print((work/'SPLIT_VILLAGE_PASS.txt').read_text());print(f'Source world unchanged; artifacts {work}')
if __name__=='__main__':main()
