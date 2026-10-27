#!/usr/bin/env python3
"""Run the packaged Book of Kingdoms client acceptance with only the mod and test harness."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import time
import uuid

from production_client import allowed

def _mod_version():
    """mod_version from gradle.properties, so default artifact names follow the build."""
    for line in (pathlib.Path(__file__).resolve().parents[2] / 'gradle.properties').read_text().splitlines():
        if line.startswith('mod_version='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('gradle.properties has no mod_version')


MOD_VERSION = _mod_version()

ROOT = pathlib.Path(__file__).resolve().parents[2]
INSTALL = pathlib.Path(os.environ.get('MCAKINGDOMS_MC_INSTALL', '/home/otectus/Documents/curseforge/minecraft/Install'))
DEFAULT_MOD = ROOT / f'build/libs/mcakingdoms-{MOD_VERSION}.jar'
DEFAULT_HARNESS = ROOT / f'build/test-artifacts/mcakingdoms-{MOD_VERSION}-client-acceptance.jar'
DEFAULT_XVFB = pathlib.Path('/tmp/kd-xvfb/usr/bin/Xvfb')
SCREENSHOTS = [
    '01-book-start.png', '02-recipe-ledger.png', '03-recipe-replaced.png', '04-recipe-removed.png', '05-recipe-book.png',
    '06-saved.png', '07-search-synonym.png', '08-kingdom-gallery.png', '09-commands.png', '10-book-compact.png',
    '11-book-narrow-contents.png', '12-book-narrow-article.png', '13-book-reopened.png',
    '14-manuscript-spread.png', '15-ledger-spread.png', '16-ledger-record.png', '17-book-scale-one.png',
    '18-book-spanish.png', '19-book-chinese.png', '20-book-small-window.png', '21-ledger-compact.png', '22-ledger-live-record.png',
]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expand(arguments, values):
    result = []
    for argument in arguments:
        if isinstance(argument, dict):
            if not allowed(argument.get('rules')):
                continue
            argument = argument['value']
        for value in argument if isinstance(argument, list) else [argument]:
            value = re.sub(r'\$\{([^}]+)\}', lambda match: values[match[1]], value)
            if value.startswith('-DignoreList='):
                value += ',1.20.1.jar'
            result.append(value)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', required=True, type=pathlib.Path)
    parser.add_argument('--install', type=pathlib.Path, default=None,
                        help='Minecraft launcher install with versions/, libraries/ and assets/ (default: $MCAKINGDOMS_MC_INSTALL or the maintainer path)')
    parser.add_argument('--mod', type=pathlib.Path, default=DEFAULT_MOD)
    parser.add_argument('--harness', type=pathlib.Path, default=DEFAULT_HARNESS)
    parser.add_argument('--xvfb', type=pathlib.Path, default=DEFAULT_XVFB)
    parser.add_argument('--display', default=':96')
    args = parser.parse_args()
    global INSTALL
    if args.install is not None:
        INSTALL = args.install.resolve()

    work = args.work_dir.resolve()
    mod = args.mod.resolve()
    harness = args.harness.resolve()
    xvfb = args.xvfb.resolve()
    if work.exists():
        raise SystemExit(f'Refusing to reuse {work}')
    for artifact in (mod, harness, xvfb):
        if not artifact.is_file():
            raise SystemExit(f'Missing required file {artifact}')

    work.mkdir(parents=True)
    (work / 'mods').mkdir()
    (work / 'natives').mkdir()
    copied = []
    for artifact in (mod, harness):
        target = work / 'mods' / artifact.name
        shutil.copy2(artifact, target)
        copied.append(target)
    (work / 'artifacts.json').write_text(json.dumps({path.name: sha256(path) for path in copied}, indent=2) + '\n')

    vanilla = json.loads((INSTALL / 'versions/1.20.1/1.20.1.json').read_text())
    forge = json.loads((INSTALL / 'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries = {}
    for library in vanilla['libraries'] + forge['libraries']:
        if allowed(library.get('rules')):
            pieces = library['name'].split(':')
            key = ':'.join(pieces[:2]) + (':' + pieces[3] if len(pieces) > 3 else '')
            libraries[key] = library
    classpath = []
    for library in libraries.values():
        artifact = library.get('downloads', {}).get('artifact')
        if not artifact:
            continue
        path = INSTALL / 'libraries' / artifact['path']
        if not path.is_file():
            raise SystemExit(f'Missing launcher library {path}')
        classpath.append(str(path))
    client_jar = INSTALL / 'versions/1.20.1/1.20.1.jar'
    if not client_jar.is_file():
        raise SystemExit(f'Missing Minecraft client {client_jar}')
    classpath.append(str(client_jar))

    values = {
        'auth_player_name': 'BookGuideTest',
        'version_name': 'forge-47.4.23',
        'game_directory': str(work),
        'assets_root': str(INSTALL / 'assets'),
        'assets_index_name': vanilla['assetIndex']['id'],
        'auth_uuid': uuid.uuid3(uuid.NAMESPACE_DNS, 'OfflinePlayer:BookGuideTest').hex,
        'auth_access_token': '0',
        'clientid': '',
        'auth_xuid': '',
        'user_type': 'legacy',
        'version_type': 'release',
        'natives_directory': str(work / 'natives'),
        'launcher_name': 'KingdomsAcceptance',
        'launcher_version': '1',
        'classpath': ':'.join(classpath),
        'classpath_separator': ':',
        'library_directory': str(INSTALL / 'libraries'),
    }
    command = [
        'java', '-Xmx4G', f'-Dmcakingdoms.clientTest.output={work}', '-Dmcakingdoms.clientTest.bookGuide=true',
        *expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'], values),
        forge['mainClass'],
        *expand(vanilla['arguments']['game'] + forge['arguments']['game'], values),
        '--width', '1280', '--height', '800',
    ]
    (work / 'launch-command.json').write_text(json.dumps(command, indent=2) + '\n')

    environment = os.environ.copy()
    environment.update(DISPLAY=args.display, LIBGL_ALWAYS_SOFTWARE='1')
    completed = None
    with (work / 'xvfb.log').open('w') as display_log, (work / 'client.log').open('w') as client_log:
        display = subprocess.Popen(
            [str(xvfb), args.display, '-screen', '0', '1600x1000x24', '-nolisten', 'tcp', '-ac'],
            stdout=display_log, stderr=display_log,
        )
        try:
            time.sleep(1)
            completed = subprocess.run(
                command, cwd=work, env=environment, stdout=client_log, stderr=subprocess.STDOUT, timeout=480,
            )
        finally:
            display.terminate()
            display.wait(timeout=15)

    marker = work / 'BOOK_GUIDE_PASS.txt'
    failure = work / 'BOOK_GUIDE_FAIL.txt'
    if completed.returncode != 0 or not marker.is_file():
        detail = failure.read_text() if failure.is_file() else 'No harness failure marker was written.'
        raise SystemExit(f'Book guide client acceptance failed; inspect {work}/client.log\n{detail}')
    missing = [name for name in SCREENSHOTS if not (work / 'screenshots' / name).is_file()
               or (work / 'screenshots' / name).stat().st_size == 0]
    if missing:
        raise SystemExit(f'Book guide acceptance omitted screenshots: {missing}')
    print(marker.read_text().strip())
    print(f'Artifacts, log, and screenshots: {work}')


if __name__ == '__main__':
    main()
