#!/usr/bin/env python3
"""Run Forge GameTests and reject exit-zero startup failures or incomplete suites.

Gradle runs through environment.gradle: the quiet wrapper named by MCAKINGDOMS_GRADLEW_QUIET when one is configured,
otherwise the project's Gradle wrapper with its log under build/test-logs (F16)."""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import environment
root = environment.ROOT
code, log = environment.gradle('runGameTestServer', *sys.argv[1:])
if code or not log: raise SystemExit(code or 1)
text = pathlib.Path(log).read_text(errors='replace')
completed = re.search(r'(\d+) GAME TESTS COMPLETE', text)
expected = sum(len(re.findall(r'@GameTest\(', p.read_text())) for p in (root / 'src/gameTest/java').rglob('*.java'))
if not completed or int(completed[1]) != expected or not re.search(r'All \d+ required tests passed', text):
    print(f'FAIL runtime: expected {expected} completed tests and required-test pass marker; inspect {log}')
    raise SystemExit(1)
print(f'PASS runtime: {expected} tests completed; all required tests passed')
