#!/usr/bin/env python3
"""Check companion jars against the 0.3.0 companion manifest (tools/test/companions.json) before acceptance (F16).

For each jar: is it a validated artifact (by SHA-256), and does it carry the contract MCA: Kingdoms needs (by reading the
named class files for the named members)? This replaces comparing historical provider-patch exports, which are a
record under docs/history and must not be applied. Nothing is installed or changed.

  python3 -I tools/test/verify_companions.py --mod path/to/mcaquests.jar [--mod ...]
  python3 -I tools/test/verify_companions.py --pack     (every companion in MCAKINGDOMS_PACK/mods)

Exit status 1 when a jar named on the command line lacks the contract; an unvalidated but contract-carrying jar passes
with a warning, because validation is evidence, not a requirement.
"""
import argparse, hashlib, json, pathlib, sys, zipfile
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import environment

MANIFEST = pathlib.Path(__file__).resolve().parent / 'companions.json'


def mod_id(jar):
    with zipfile.ZipFile(jar) as archive:
        try:
            text = archive.read('META-INF/mods.toml').decode('utf-8', 'replace')
        except KeyError:
            return None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith('modId'):
            return line.split('=', 1)[1].strip().strip('"')
    return None


def carries(jar, classes):
    missing = []
    with zipfile.ZipFile(jar) as archive:
        for name, members in classes.items():
            try:
                data = archive.read(name)
            except KeyError:
                missing.append(name)
                continue
            missing += [f'{name}#{m}' for m in members if m.encode() not in data]
    return missing


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mod', action='append', default=[], type=pathlib.Path)
    parser.add_argument('--pack', action='store_true', help='check the companions in MCAKINGDOMS_PACK/mods')
    args = parser.parse_args()
    manifest = {entry['mod']: entry for entry in json.loads(MANIFEST.read_text())['companions']}
    jars = list(args.mod)
    if args.pack: jars += sorted(environment.pack_mods().glob('*.jar'))
    if not jars: raise SystemExit('Name jars with --mod, or use --pack')
    failed = False
    for jar in jars:
        identity = mod_id(jar)
        entry = manifest.get(identity)
        if entry is None:
            if args.mod and jar in args.mod: print(f'skip    {jar.name}: {identity or "no mods.toml"} is not a Kingdoms companion')
            continue
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        validated = next((v for v in entry.get('validated', []) if v['sha256'] == digest), None)
        missing = carries(jar, entry['contract'].get('classes', {}))
        state = 'contract ok' if not missing else 'CONTRACT MISSING: ' + ', '.join(missing)
        print(f'{"PASS" if not missing else "FAIL":7} {jar.name} ({identity}) sha256 {digest[:16]}… '
              f'{"validated: " + validated["builtFrom"] if validated else "not a validated artifact"}; {state}')
        if missing:
            print(f'        without it: {entry["contract"].get("without_it", "the dependent feature is unavailable")}')
            failed = True
    raise SystemExit(1 if failed else 0)


if __name__ == '__main__':
    main()
