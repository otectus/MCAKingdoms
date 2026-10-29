#!/usr/bin/env python3
"""Fresh packaged-server integration checks; optional source/target process-crash replay."""
import argparse, json, gzip, hashlib, pathlib, shutil, subprocess, sys, time
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import environment

def main():
    p=argparse.ArgumentParser();p.add_argument('--work-dir',required=True,type=pathlib.Path);p.add_argument('--mod',action='append',default=[],type=pathlib.Path);p.add_argument('--crash-replay',action='store_true');p.add_argument('--phase',default='initial');p.add_argument('--startup-only',action='store_true');p.add_argument('--seed-world',type=pathlib.Path);args=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2];work=args.work_dir.resolve()
    if work.exists():raise SystemExit(f'Refusing to reuse {work}')
    work.mkdir(parents=True);(work/'mods').mkdir();(work/'libraries').symlink_to(root/'build/forge-server/libraries',target_is_directory=True)
    props=dict(l.split('=',1) for l in (root/'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
    jars=[*args.mod] if args.startup_only else [root/'build/libs'/f"{props['mod_id']}-{props['mod_version']}.jar",root/'build/test-artifacts'/f"{props['mod_id']}-{props['mod_version']}-acceptance.jar",*args.mod]
    for jar in jars:shutil.copy2(jar,work/'mods'/jar.name)
    (work/'artifacts.sha256').write_text(''.join(hashlib.sha256(j.read_bytes()).hexdigest()+'  '+j.name+'\n' for j in jars))
    environment.eula(work)  # an EULA you accepted (MCAKINGDOMS_EULA); acceptance is never assumed
    (work/'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    if args.seed_world:shutil.copytree(args.seed_world,work/'world')
    if args.phase=='named':
        pack=work/'world/datapacks/integration';folder=pack/'data/mcakingdoms_acceptance/mcakingdoms/kingdom_gates';folder.mkdir(parents=True)
        (pack/'pack.mcmeta').write_text(json.dumps({'pack':{'pack_format':15,'description':'Named gate runtime fixture'}}))
        (folder/'named.json').write_text(json.dumps({'subject':'giver_residence','include':['mcakingdoms:serenum']}))
    if args.phase in ('r2','r2-legacy'):
        shutil.copy2(root/'pack/r2/kubejs/data/ultima/mcaquests/quests/civic/lamplighters/workshop_lanterns.json',work/'workshop_lanterns.json')
        (work/'config').mkdir(exist_ok=True)
        (work/'config/mcakingdoms-civic-common.toml').write_text('[regional_civic_network]\nenabled = true\n')
    if args.phase=='civic-loop':
        shutil.copy2(root/'pack/r1/kubejs/data/ultima/mcaquests/quests/civic/lamplighters/fire_in_poor_hands.json',work/'fire_in_poor_hands.json')
    if args.phase=='r3':
        (work/'config').mkdir(exist_ok=True)
        # The observation fixture bypasses campaign policy, so military actions are off from launch; toggling the
        # setting at runtime raced Forge's config watcher (F16).
        (work/'config/mcakingdoms-warfare-common.toml').write_text('[political_warfare]\nmilitaryActions = false\n')
    def boot(phase,crash=False):
        log=work/f'{phase}.log'
        with log.open('w') as out:
            proc=subprocess.Popen([environment.java(),'-Xmx2G',f'-Dmcakingdoms.acceptance.integration={phase}','@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt','nogui'],cwd=work,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True)
            try:
                deadline=time.monotonic()+(1500 if args.phase=='politics-cycle' else 180)
                while proc.poll() is None and time.monotonic()<deadline:
                    body=log.read_text(errors='replace')
                    if args.startup_only and 'Done (' in body:
                        proc.stdin.write('stop\n');proc.stdin.flush();assert proc.wait(timeout=60)==0;print(f'PASS optional companion startup without MCA: Kingdoms: {log}',flush=True);return
                    if crash and 'CRASH_READY integration' in body:
                        proc.kill();proc.wait(timeout=20);print(f'PASS process killed after durable source/target/ack: {log}',flush=True);return
                    time.sleep(.25)
                if proc.poll() is None:raise RuntimeError(f'Timeout: {log}')
                result=work/'mca-lifecycle-results.txt'
                if proc.returncode!=0 or not result.exists() or not result.read_text().startswith('PASS integration'):raise RuntimeError(f'Integration failed: {log}')
                print(result.read_text().strip()+f'; log: {log}',flush=True);result.unlink()
                if crash:raise RuntimeError(f'Expected crash checkpoint never reached: {log}')
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    try:proc.wait(timeout=20)
                    except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=20)
    boot(args.phase,args.crash_replay or args.phase=='r4-crash')
    if args.phase=='r4-crash':boot('r4-crash-restart')
    if args.phase=='r4':
        boot('r4-restart')
        # Vanilla discards unknown provider entities when their chunks are loaded without the mod.
        # Test sidecar absence on a copy; reinstall against the retained native world snapshot.
        native_world=work/'world-with-native-provider';shutil.copytree(work/'world',native_world)
        native=next((work/'mods').glob('recruits-*.jar'));retained=work/native.name
        native.rename(retained)
        try:boot('r4-absent')
        finally:retained.rename(native)
        (work/'world').rename(work/'world-provider-absent');native_world.rename(work/'world')
        boot('r4-reinstalled')
        saves=[work/'world/data'/f'{name}.dat' for name in ('mcakingdoms_evolution','mcakingdoms_recruit_transfers')]
        for save in saves:
            raw=gzip.decompress(save.read_bytes());marker=b'\x03\x00\x06Schema\x00\x00\x00\x01';assert marker in raw
            save.write_bytes(gzip.compress(raw.replace(marker,b'\x03\x00\x06Schema\x00\x00\x00\x63',1)))
        before=[hashlib.sha256(save.read_bytes()).hexdigest() for save in saves];boot('r4-future')
        assert before==[hashlib.sha256(save.read_bytes()).hexdigest() for save in saves]
        print('PASS R4 future sidecars unchanged across save/shutdown',flush=True)
    if args.phase=='military':boot('military-restart')
    if args.phase=='lv':
        # Recruits: Living Villages: restart, provider absent, reinstalled, future schema, then an unaudited jar.
        boot('lv-restart')
        store=work/'world/data/mcakingdoms_living_villages.dat'
        lv=next((work/'mods').glob('recruitsvillages-*.jar'));retained=work/lv.name
        before=hashlib.sha256(store.read_bytes()).hexdigest()
        lv.rename(retained)
        try:boot('lv-absent')
        finally:retained.rename(lv)
        assert hashlib.sha256(store.read_bytes()).hexdigest()==before,'bridge store changed while Living Villages was absent'
        print('PASS Living Villages bridge store byte-identical while the provider was absent',flush=True)
        boot('lv-reinstalled')
        original=store.read_bytes()
        raw=gzip.decompress(original);marker=b'\x03\x00\x06Schema\x00\x00\x00\x01';assert marker in raw
        store.write_bytes(gzip.compress(raw.replace(marker,b'\x03\x00\x06Schema\x00\x00\x00\x63',1)))
        future=hashlib.sha256(store.read_bytes()).hexdigest();boot('lv-future')
        assert hashlib.sha256(store.read_bytes()).hexdigest()==future,'future-schema bridge store was rewritten'
        print('PASS Living Villages future-schema store unchanged across save/shutdown',flush=True)
        store.write_bytes(original)
        # The same version string with different bytes is not the audited jar: a world with protected residents refuses to start.
        import zipfile
        patched=work/'mods'/lv.name
        with zipfile.ZipFile(patched,'a') as jar:jar.writestr('mcakingdoms-acceptance-marker.txt','unaudited')
        log=work/'lv-unaudited.log'
        with log.open('w') as out:
            proc=subprocess.Popen([environment.java(),'-Xmx2G','-Dmcakingdoms.acceptance.integration=lv-unaudited','@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt','nogui'],cwd=work,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True)
            try:proc.wait(timeout=240)
            except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=20);raise RuntimeError(f'Unaudited start did not stop: {log}')
        body=log.read_text(errors='replace')
        assert 'Living Villages would discard and respawn them' in body,f'no refusal message: {log}'
        assert hashlib.sha256(store.read_bytes()).hexdigest()==hashlib.sha256(original).hexdigest(),'bridge store changed on the refused start'
        print(f'PASS unaudited Living Villages jar refused before any world change: {log}',flush=True)
    if args.phase=='r3-civilian':boot('r3-civilian-restart')
    if args.phase=='r3':
        boot('r3-restart')
        native=next((work/'mods').glob('recruits-*.jar'))
        retained=work/native.name
        native.rename(retained)
        try:boot('r3-absent')
        finally:retained.rename(native)
        boot('r3-reinstalled')
        save=work/'world/data/mcakingdoms_control.dat'
        raw=gzip.decompress(save.read_bytes());marker=b'\x03\x00\x06Schema\x00\x00\x00\x01';assert marker in raw
        save.write_bytes(gzip.compress(raw.replace(marker,b'\x03\x00\x06Schema\x00\x00\x00\x63',1)))
        before=hashlib.sha256(save.read_bytes()).hexdigest();boot('r3-future')
        assert hashlib.sha256(save.read_bytes()).hexdigest()==before
        print('PASS future control schema file unchanged across save/shutdown',flush=True)
    if args.phase=='civic-loop':
        boot('civic-loop-restart')
    if args.phase=='r2':
        boot('r2-restart')
    if args.phase=='r1':
        boot('r1-restart')
    if args.phase=='politics':
        boot('politics-restart')
        save=work/'world/data/mcakingdoms_politics.dat'
        raw=gzip.decompress(save.read_bytes());marker=b'\x03\x00\x06Schema\x00\x00\x00\x01';assert marker in raw
        save.write_bytes(gzip.compress(raw.replace(marker,b'\x03\x00\x06Schema\x00\x00\x00\x63',1)))
        before=hashlib.sha256(save.read_bytes()).hexdigest();boot('politics-future')
        assert hashlib.sha256(save.read_bytes()).hexdigest()==before
        print('PASS political future-schema file remains byte-identical across save/shutdown',flush=True)
    if args.phase=='migration':
        boot('migration-restart')
        boot('loops')
    if args.phase=='split-village':
        def summaries(phase):
            return [line for line in (work/f'{phase}.log').read_text(errors='replace').splitlines() if 'Village reconciliation (' in line]
        first=summaries('split-village')
        assert len(first)==1 and 'Village reconciliation (startup): merged 1 split record(s) into 1 village(s)' in first[0],first
        save=work/'world/data/mcakingdoms_settlements.dat';before=hashlib.sha256(save.read_bytes()).hexdigest()
        boot('split-village-restart')
        assert summaries('split-village-restart')==[],'restart reconciled again'
        assert hashlib.sha256(save.read_bytes()).hexdigest()==before,'settlements file changed across restart'
        print('PASS split village: one start-up summary line, file unchanged and one record after restart',flush=True)
    if args.crash_replay:
        boot('restart')
        boot('regressions')
        config=work/'config/mcakingdoms-factions-common.toml'
        config.write_text(config.read_text().replace('mode = \"MCA_TO_FACTION\"','mode = \"SHADOW\"'))
        boot('shadow')
        save=work/'world/data/mcakingdoms_factions.dat';raw=gzip.decompress(save.read_bytes());marker=b'\x03\x00\x06Schema\x00\x00\x00\x01';assert marker in raw
        save.write_bytes(gzip.compress(raw.replace(marker,b'\x03\x00\x06Schema\x00\x00\x00\x63')))
        before=hashlib.sha256(save.read_bytes()).hexdigest();boot('future');assert hashlib.sha256(save.read_bytes()).hexdigest()==before
        print('PASS future faction schema remains byte-identical after server save/shutdown',flush=True)
    print(f'Artifacts: {work}',flush=True)
if __name__=='__main__':main()
