#!/usr/bin/env python3
"""The one-command local check (H04): build and unit tests (including API isolation and the add-on example), resource
validation, and the release-artifact check. It needs only Java 17, Python 3 and the checked-in Gradle wrapper. GameTests
(python3 tools/test/run_gametests.py) and the packaged and client suites are separate, longer runs; see docs/Testing.md.
Run: python3 -I tools/test/quick_check.py"""
import pathlib, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import environment, outcome
ROOT = environment.ROOT
steps = []
code, log = environment.gradle('check', 'build')
steps.append(('build and unit tests', code == 0, log))
for script in ('validate_resources.py', 'verify_artifacts.py'):
    if code and script == 'verify_artifacts.py':
        steps.append((script, False, 'not run: the build failed')); continue
    result = subprocess.run([sys.executable, '-I', str(ROOT / 'tools/test' / script)], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end='')
    steps.append((script, result.returncode == 0, ''))
for name, ok, detail in steps: print(f"{'PASS' if ok else 'FAIL'} {name}{' — ' + str(detail) if detail else ''}")
outcome.finish('PASS' if all(ok for _, ok, _ in steps) else 'FAIL', f"{sum(ok for _, ok, _ in steps)} of {len(steps)} steps passed", 'quick-check')
