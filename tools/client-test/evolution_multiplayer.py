#!/usr/bin/env python3
"""R4 private history and opposing outcomes through two real clients."""
import argparse, hashlib, json, os, pathlib, re, shutil, socket, subprocess, time, uuid
from production_client import allowed
import sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'test')); import environment  # F16: prerequisites from the environment

def _mod_version():
    """mod_version from gradle.properties, so default artifact names follow the build."""
    for line in (pathlib.Path(__file__).resolve().parents[2] / 'gradle.properties').read_text().splitlines():
        if line.startswith('mod_version='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('gradle.properties has no mod_version')


MOD_VERSION = _mod_version()
ROOT=pathlib.Path(__file__).resolve().parents[2]
INSTALL = environment.install()  # MCAKINGDOMS_MC_INSTALL
DEFAULT_XVFB = environment.xvfb(required=False)  # MCAKINGDOMS_XVFB or Xvfb on PATH

def main():
    p=argparse.ArgumentParser();p.add_argument('--work-dir',type=pathlib.Path,required=True)
    p.add_argument('--xvfb',type=pathlib.Path,default=DEFAULT_XVFB,help='Headless X server binary');p.add_argument('--display',default=':97')
    args=p.parse_args()
    if not args.xvfb.is_file():raise SystemExit(f'Headless X server not found: {args.xvfb}')
    work=args.work_dir.resolve()
    if work.exists():raise SystemExit(f'Refusing to reuse {work}')
    work.mkdir(parents=True);server=work/'server';server.mkdir();(server/'mods').mkdir();(server/'libraries').symlink_to(ROOT/'build/forge-server/libraries',target_is_directory=True)
    mod=ROOT/f'build/libs/mcakingdoms-{MOD_VERSION}.jar';harness=ROOT/f'build/test-artifacts/mcakingdoms-{MOD_VERSION}-client-acceptance.jar'
    native=environment.pack_mods()/'recruits-1.20.1-1.15.2.jar'
    acceptance=ROOT/f'build/test-artifacts/mcakingdoms-{MOD_VERSION}-acceptance.jar'
    for jar in [native,acceptance]:shutil.copy2(jar,server/'mods'/jar.name)
    shutil.copy2(mod,server/'mods'/mod.name);environment.eula(server)
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    (server/'server.properties').write_text(f'online-mode=false\nenforce-secure-profile=false\nserver-ip=127.0.0.1\nserver-port={port}\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
    vanilla=json.loads((INSTALL/'versions/1.20.1/1.20.1.json').read_text());forge=json.loads((INSTALL/'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries={}
    for lib in vanilla['libraries']+forge['libraries']:
        if allowed(lib.get('rules')):libraries[':'.join(lib['name'].split(':')[:2])+(':'+lib['name'].split(':')[3] if len(lib['name'].split(':'))>3 else '')]=lib
    cp=[str(INSTALL/'libraries'/lib['downloads']['artifact']['path']) for lib in libraries.values() if lib.get('downloads',{}).get('artifact')]
    cp.append(str(INSTALL/'versions/1.20.1/1.20.1.jar'))
    processes=[];logs=[]
    def spawn(cmd,cwd,log):
        out=log.open('w');logs.append(out);proc=subprocess.Popen(cmd,cwd=cwd,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True,env=os.environ.copy());processes.append(proc);return proc
    def wait_until(test,seconds=180):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if list(work.glob('*-FAIL.txt')):raise RuntimeError('Client failure: '+str(list(work.glob('*-FAIL.txt'))))
            if test():return
            if any(p.poll() not in (None,0) for p in processes):raise RuntimeError('Runtime exited with error; inspect logs')
            time.sleep(.25)
        raise RuntimeError('Timed out; inspect '+str(work))
    displaylog=(work/'xvfb.log').open('w')
    display=subprocess.Popen([str(args.xvfb),args.display,'-screen','0','1600x1000x24','-nolisten','tcp','-ac'],stdout=displaylog,stderr=displaylog)
    os.environ.update(DISPLAY=args.display,LIBGL_ALWAYS_SOFTWARE='1')
    time.sleep(1)
    try:
        slog=server/'console.log';srv=spawn(['java','-Xmx2G','-Dmcakingdoms.acceptance.r4Multiplayer=true','@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt','nogui'],server,slog)
        wait_until(lambda:'Done (' in slog.read_text(errors='replace'))
        for role,name in [('leader','PoliticalLeader'),('stranger','PoliticalOther')]:
            folder=work/role;folder.mkdir();(folder/'mods').mkdir();(folder/'natives').mkdir()
            for jar in [mod,harness,native]:shutil.copy2(jar,folder/'mods'/jar.name)
            values={'auth_player_name':name,'version_name':'forge-47.4.23','game_directory':str(folder),'assets_root':str(INSTALL/'assets'),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)).replace('-',''),'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','natives_directory':str(folder/'natives'),'launcher_name':'KingdomsAcceptance','launcher_version':'1','classpath':':'.join(cp),'classpath_separator':':','library_directory':str(INSTALL/'libraries')}
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
            command=['java','-Xmx2G',f'-Dmcakingdoms.clientTest.output={folder}','-Dmcakingdoms.clientTest.evolutionMultiplayer=true',f'-Dmcakingdoms.clientTest.shared={work}',f'-Dmcakingdoms.clientTest.role={role}',f'-Dmcakingdoms.clientTest.server=127.0.0.1:{port}']+expand(vanilla['arguments']['jvm']+forge['arguments']['jvm'])+[forge['mainClass']]+expand(vanilla['arguments']['game']+forge['arguments']['game'])+['--width','960','--height','640']
            spawn(command,folder,folder/'console.log')
        wait_until(lambda:all(name+' logged in' in slog.read_text(errors='replace') or re.search(name+r'\[.*logged in',slog.read_text(errors='replace')) for name in ['PoliticalLeader','PoliticalOther']))
        srv.stdin.write('r4-fixture setup\n');srv.stdin.flush()
        wait_until(lambda:'R4_MULTIPLAYER_READY' in slog.read_text(errors='replace'))
        wait_until(lambda:all((work/(role+'-READ')).exists() for role in ['leader','stranger']))
        (work/'RACE').write_text('send opposing choices')
        wait_until(lambda:all((work/(role+'-SENT')).exists() for role in ['leader','stranger']))
        time.sleep(2)
        srv.stdin.write('r4-fixture audit\n');srv.stdin.flush()
        wait_until(lambda:'R4_MULTIPLAYER_AUDIT_PASS' in slog.read_text(errors='replace'))
        (work/'STOP').write_text('done')
        wait_until(lambda:all((work/(role+'-PASS.txt')).exists() for role in ['leader','stranger']))
        for proc in processes[1:]:assert proc.wait(timeout=60)==0
        srv.stdin.write('stop\n');srv.stdin.flush()
        try:assert srv.wait(timeout=60)==0
        except subprocess.TimeoutExpired:
            dump=subprocess.run(['jstack',str(srv.pid)],capture_output=True,text=True,timeout=20)
            (server/'shutdown-threads.txt').write_text(dump.stdout+dump.stderr)
            raise
        (work/'artifacts.sha256').write_text(''.join(hashlib.sha256(j.read_bytes()).hexdigest()+'  '+j.name+'\n' for j in [mod,harness]))
        print((work/'leader-PASS.txt').read_text().strip());print((work/'stranger-PASS.txt').read_text().strip());print('Artifacts: '+str(work))
    finally:
        for proc in processes:
            if proc.poll() is None:proc.terminate();proc.wait(timeout=20)
        for out in logs:out.close()
        display.terminate();display.wait(timeout=15);displaylog.close()
if __name__=='__main__':main()
