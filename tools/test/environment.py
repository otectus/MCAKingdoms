#!/usr/bin/env python3
"""Locates the external prerequisites of the acceptance tooling, so a clean checkout runs on any machine (F16).

Nothing here assumes a path on the maintainer's machine. Each prerequisite is read, in order, from an environment
variable or from tools/test/local-environment.json (git-ignored; copy local-environment.example.json). A missing one
stops the calling script with a message naming the variable to set. Licence acceptance is never assumed: the server
EULA must be a file you have accepted yourself.

  MCAKINGDOMS_EULA            an eula.txt containing eula=true, copied into each fresh server folder
  MCAKINGDOMS_MC_INSTALL      a Minecraft launcher install root (versions/, libraries/, assets/), for client harnesses
  MCAKINGDOMS_PACK            a modpack instance folder with mods/, for full-pack runs (MCA, Townstead, Recruits, ...)
  MCAKINGDOMS_XVFB            an Xvfb binary for headless client runs (defaults to Xvfb on PATH)
  MCAKINGDOMS_JAVA            a Java 17 binary (defaults to $JAVA_HOME/bin/java, then java on PATH)
  MCAKINGDOMS_GRADLEW_QUIET   an optional quiet Gradle wrapper; without it ./gradlew runs with its log under build/

Run `python3 -I tools/test/environment.py` to see which prerequisites are found.
"""
import json, os, pathlib, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCAL = ROOT / 'tools/test/local-environment.json'

PREREQUISITES = {
    'MCAKINGDOMS_EULA': 'an eula.txt you accepted (eula=true) for the Minecraft server EULA',
    'MCAKINGDOMS_MC_INSTALL': 'a Minecraft launcher install root with versions/, libraries/ and assets/',
    'MCAKINGDOMS_PACK': 'a modpack instance folder containing mods/ with the provider jars',
    'MCAKINGDOMS_XVFB': 'an Xvfb binary for headless clients',
    'MCAKINGDOMS_JAVA': 'a Java 17 binary',
    'MCAKINGDOMS_GRADLEW_QUIET': 'an optional quiet Gradle wrapper script',
}


def _local():
    try:
        return json.loads(LOCAL.read_text())
    except (OSError, ValueError):
        return {}


def value(name):
    """The configured value of one prerequisite, or None."""
    found = os.environ.get(name) or _local().get(name)
    return found or None


def fail(name, detail=''):
    raise SystemExit(f'Missing prerequisite {name}: {PREREQUISITES.get(name, name)}. {detail}'
                     f'Set the environment variable or add it to {LOCAL.relative_to(ROOT)} '
                     f'(see local-environment.example.json).')


def path(name, required=True, kind='any'):
    raw = value(name)
    if raw is None:
        if required: fail(name)
        return None
    found = pathlib.Path(raw).expanduser()
    if kind == 'file' and not found.is_file() or kind == 'dir' and not found.is_dir() or kind == 'any' and not found.exists():
        if required: fail(name, f'{found} does not exist. ')
        return None
    return found


def eula(destination):
    """Copies the accepted EULA into a fresh server folder; refuses one that does not say eula=true."""
    source = path('MCAKINGDOMS_EULA', kind='file')
    text = source.read_text(errors='replace')
    if not any(line.strip().lower() == 'eula=true' for line in text.splitlines()):
        fail('MCAKINGDOMS_EULA', f'{source} does not contain eula=true; accept the EULA yourself first. ')
    shutil.copy2(source, pathlib.Path(destination) / 'eula.txt')


def install():
    return path('MCAKINGDOMS_MC_INSTALL', kind='dir')


def pack():
    return path('MCAKINGDOMS_PACK', kind='dir')


def pack_mods():
    mods = pack() / 'mods'
    if not mods.is_dir(): fail('MCAKINGDOMS_PACK', f'{mods} is not a folder. ')
    return mods


def xvfb(required=True):
    configured = path('MCAKINGDOMS_XVFB', required=False, kind='file')
    if configured: return configured
    found = shutil.which('Xvfb')
    if found: return pathlib.Path(found)
    if required: fail('MCAKINGDOMS_XVFB')
    return None


def java():
    configured = path('MCAKINGDOMS_JAVA', required=False, kind='file')
    if configured: return str(configured)
    home = os.environ.get('JAVA_HOME')
    if home and (pathlib.Path(home) / 'bin/java').is_file(): return str(pathlib.Path(home) / 'bin/java')
    found = shutil.which('java')
    if found: return found
    fail('MCAKINGDOMS_JAVA')


def gradle(task, *extra):
    """Runs a Gradle task; returns (exit code, log path). Uses the quiet wrapper when one is configured."""
    quiet = path('MCAKINGDOMS_GRADLEW_QUIET', required=False, kind='file')
    if quiet:
        result = subprocess.run([str(quiet), str(ROOT), task, *extra], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        print(result.stdout, end='')
        log = next((line[5:] for line in result.stdout.splitlines() if line.startswith('log: ')), None)
        return result.returncode, pathlib.Path(log) if log else None
    logs = ROOT / 'build/test-logs'
    logs.mkdir(parents=True, exist_ok=True)
    log = logs / f'gradle-{task}.log'
    with log.open('w') as out:
        code = subprocess.run(['./gradlew', task, '--console=plain', *extra], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT).returncode
    print(('PASS' if code == 0 else 'FAIL') + f' - {task}\nlog: {log}')
    return code, log


def report():
    """Prints each prerequisite and whether it was found."""
    for name, meaning in PREREQUISITES.items():
        if name == 'MCAKINGDOMS_XVFB': found = xvfb(required=False)
        elif name == 'MCAKINGDOMS_JAVA':
            try: found = java()
            except SystemExit: found = None
        else: found = path(name, required=False, kind={'MCAKINGDOMS_EULA': 'file', 'MCAKINGDOMS_GRADLEW_QUIET': 'file'}.get(name, 'dir'))
        print(f'{"found  " if found else "missing"} {name:28} {found or meaning}')


if __name__ == '__main__':
    report()
