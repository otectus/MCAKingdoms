#!/usr/bin/env python3
"""Prepares build/forge-server, the dedicated-server libraries the packaged suites link to (H04). Downloads the Forge
installer named by gradle.properties from the Forge Maven, checks it against the published SHA-1, and runs
--installServer. Does nothing when the libraries are already there. Run: python3 -I tools/test/forge_server.py"""
import hashlib, pathlib, subprocess, sys, urllib.request
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import environment, outcome
ROOT = environment.ROOT
props = dict(l.split('=', 1) for l in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
version = f"{props['minecraft_version']}-{props['forge_version']}"
target = ROOT / 'build/forge-server'
if (target / f'libraries/net/minecraftforge/forge/{version}/unix_args.txt').is_file():
    outcome.finish('PASS', f'Forge {version} server libraries already present in {target.relative_to(ROOT)}', 'forge-server')
base = f'https://maven.minecraftforge.net/net/minecraftforge/forge/{version}/forge-{version}-installer.jar'
target.mkdir(parents=True, exist_ok=True)
installer = target / 'installer.jar'
try:
    data = urllib.request.urlopen(base, timeout=120).read()
    expected = urllib.request.urlopen(base + '.sha1', timeout=60).read().decode().strip().split()[0]
except OSError as failure:
    outcome.finish('SKIPPED', f'could not download the Forge installer ({failure})', 'forge-server')
if hashlib.sha1(data).hexdigest() != expected:
    outcome.finish('FAIL', f'the downloaded installer does not match its published SHA-1 {expected}', 'forge-server')
installer.write_bytes(data)
log = target / 'install-console.log'
with log.open('w') as out:
    code = subprocess.run([environment.java(), '-jar', str(installer), '--installServer', str(target)], cwd=target, stdout=out, stderr=subprocess.STDOUT).returncode
if code: outcome.finish('FAIL', f'the Forge installer exited {code}; see {log}', 'forge-server')
outcome.finish('PASS', f'Forge {version} server installed in {target.relative_to(ROOT)}', 'forge-server')
