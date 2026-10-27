#!/usr/bin/env python3
"""Offline packaged client acceptance using installed launcher libraries, isolated saves/mods."""
import json, os, pathlib, shutil, subprocess, sys, uuid, re, hashlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'test')); import environment  # F16: prerequisites from the environment

def _mod_version():
    """mod_version from gradle.properties, so default artifact names follow the build."""
    for line in (pathlib.Path(__file__).resolve().parents[2] / 'gradle.properties').read_text().splitlines():
        if line.startswith('mod_version='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('gradle.properties has no mod_version')


MOD_VERSION = _mod_version()
ROOT=pathlib.Path(__file__).resolve().parents[2]
INSTALL = environment.path('MCAKINGDOMS_MC_INSTALL', required=False, kind='dir')  # checked in main()
PACK=None  # resolved in main() from MCAKINGDOMS_PACK
# Usage: production_client.py [work-suffix] [--xvfb /path/to/Xvfb]. Without --xvfb the client opens a real window.
ARGS=[a for a in sys.argv[1:]]
XVFB=pathlib.Path(ARGS[ARGS.index('--xvfb')+1]) if '--xvfb' in ARGS else None
SUFFIX=[a for i,a in enumerate(ARGS) if a!='--xvfb' and (i==0 or ARGS[i-1]!='--xvfb')]
WORK=ROOT/'build'/('production-client-blueprint'+(('-'+SUFFIX[0]) if SUFFIX else ''))

def allowed(rules):
    answer=not rules
    for rule in rules or []:
        os=rule.get('os',{})
        matches=(os.get('name','linux')=='linux' and os.get('arch','amd64') in ('amd64','x86_64') and not rule.get('features'))
        if matches: answer=rule['action']=='allow'
    return answer

def main():
    global PACK, INSTALL
    PACK=environment.pack_mods()  # MCAKINGDOMS_PACK
    INSTALL=environment.install()  # MCAKINGDOMS_MC_INSTALL
    if WORK.exists(): raise SystemExit(f'Refusing to reuse {WORK}')
    WORK.mkdir(parents=True); (WORK/'mods').mkdir(); (WORK/'natives').mkdir()
    vanilla=json.loads((INSTALL/'versions/1.20.1/1.20.1.json').read_text())
    forge=json.loads((INSTALL/'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries={}
    for lib in vanilla['libraries']+forge['libraries']:
        if allowed(lib.get('rules')):
            libraries[':'.join(lib['name'].split(':')[:2]) + (':'+lib['name'].split(':')[3] if len(lib['name'].split(':'))>3 else '')]=lib
    cp=[]
    for lib in libraries.values():
        artifact=lib.get('downloads',{}).get('artifact')
        if artifact:
            path=INSTALL/'libraries'/artifact['path']
            if not path.exists():raise SystemExit(f'Missing library {path}')
            cp.append(str(path))
    cp.append(str(INSTALL/'versions/1.20.1/1.20.1.jar'))
    jars=[PACK/n for n in ['minecraft-comes-alive-7.6.26+1.20.1-universal.jar','architectury-9.2.14-forge.jar','townstead-0.7.6+1.20.1.jar']]
    jars+=list(PACK.glob('Patchouli-1.20.1-85*.jar'))
    # Companions that bind the renamed mod id, as installed in the pack (copied, never modified), and this build's jar.
    # build/integration-artifacts still holds the pre-rename Ultima Kingdoms promotion and cannot load beside it.
    for pattern in ('mcaconversations-*.jar','mcaquests-*.jar','mcareputation-*.jar'):
        found=sorted(PACK.glob(pattern))
        if len(found)!=1:raise SystemExit(f'Expected one {pattern} in {PACK}, found {found}')
        jars+=found
    jars+=[ROOT/f'build/libs/mcakingdoms-{MOD_VERSION}.jar',ROOT/f'build/test-artifacts/mcakingdoms-{MOD_VERSION}-client-acceptance.jar']
    if len(jars)!=9 or not all(j.is_file() for j in jars):raise SystemExit(f'Expected 9 jars, found {jars}')
    for jar in jars:shutil.copy2(jar,WORK/'mods'/jar.name)
    (WORK/'artifacts.json').write_text(json.dumps({j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in jars},indent=2))
    # Skip the first-run accessibility screen, which would otherwise sit in front of the title screen the harness waits for.
    (WORK/'options.txt').write_text('onboardAccessibility:false\npauseOnLostFocus:false\n')
    values={'auth_player_name':'BlueprintTest','version_name':'forge-47.4.23','game_directory':str(WORK),'assets_root':str(INSTALL/'assets'),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':uuid.uuid3(uuid.NAMESPACE_DNS,'OfflinePlayer:BlueprintTest').hex,'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','natives_directory':str(WORK/'natives'),'launcher_name':'KingdomsAcceptance','launcher_version':'1','classpath':':'.join(cp),'classpath_separator':':','library_directory':str(INSTALL/'libraries')}
    def expand(args):
        result=[]
        for arg in args:
            if isinstance(arg,dict):
                if not allowed(arg.get('rules')):continue
                arg=arg['value']
            for value in arg if isinstance(arg,list) else [arg]:
                expanded=re.sub(r'\$\{([^}]+)\}',lambda m:values[m[1]],value)
                if expanded.startswith('-DignoreList='): expanded+=',1.20.1.jar'
                result.append(expanded)
        return result
    cmd=['java','-Xmx4G',f'-Dmcakingdoms.clientTest.output={WORK}','-Dmcakingdoms.clientTest.blueprint=true']+expand(vanilla['arguments']['jvm']+forge['arguments']['jvm'])+[forge['mainClass']]+expand(vanilla['arguments']['game']+forge['arguments']['game'])+['--width','1280','--height','800']
    (WORK/'launch-command.json').write_text(json.dumps(cmd,indent=2))
    with (WORK/'client.log').open('w') as log:
        env=None;display=None
        if XVFB:
            import os,time
            env=dict(os.environ,DISPLAY=':97',LIBGL_ALWAYS_SOFTWARE='1')
            display=subprocess.Popen([str(XVFB),':97','-screen','0','1600x1000x24','-nolisten','tcp','-ac'],stdout=log,stderr=subprocess.STDOUT);time.sleep(1)
        try:
            result=subprocess.run(cmd,cwd=WORK,stdout=log,stderr=subprocess.STDOUT,timeout=480,env=env)
        finally:
            if display:display.terminate();display.wait(timeout=15)
    print(f'Client exit {result.returncode}; log {WORK}/client.log')
    if result.returncode or not (WORK/'BLUEPRINT_PASS.txt').exists() or not (WORK/'CONVERSATION_ENTRY_PASS.txt').exists():raise SystemExit('Client acceptance did not pass')
    print((WORK/'BLUEPRINT_PASS.txt').read_text())
if __name__=='__main__':main()
