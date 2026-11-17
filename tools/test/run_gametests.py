#!/usr/bin/env python3
"""Run Forge GameTests in an isolated run directory and reject exit-zero startup failures or incomplete suites.

The run directory is resolved before anything on disk is touched, with the Gradle property's own semantics
(`-PrunDir=<dir>`, `-P runDir=<dir>` or `--project-prop runDir=<dir>`; relative to the project root). Without one, every
run gets a fresh directory under build/gametest-runs/, so no earlier world, config or seed leaks into the result (AUD-18).
Only the resolved directory's own world is ever moved, never another directory's, and never deleted: an existing world
there is kept under a collision-safe name first.

  python3 tools/test/run_gametests.py                      # fresh build/gametest-runs/<stamp>
  python3 tools/test/run_gametests.py -PrunDir=run         # the classic run/ (its world is kept aside first)
  python3 tools/test/run_gametests.py --seed -7539419663294119207   # pin the world seed of a fresh directory

Gradle runs through environment.gradle: the quiet wrapper named by MCAKINGDOMS_GRADLEW_QUIET when one is configured,
otherwise the project's Gradle wrapper with its log under build/test-logs (F16). The EULA is written only from the
acceptance environment.py already knows (MCAKINGDOMS_ACCEPT_EULA or MCAKINGDOMS_EULA); nothing is assumed."""
import pathlib, re, sys, time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def split_run_dir(args):
    """(runDir value or None, the remaining Gradle arguments without it), accepting every form Gradle accepts."""
    value, rest, i = None, [], 0
    while i < len(args):
        arg = args[i]
        if arg.startswith('-PrunDir='):
            value = arg[len('-PrunDir='):]
        elif arg in ('-P', '--project-prop') and i + 1 < len(args) and args[i + 1].startswith('runDir='):
            value = args[i + 1][len('runDir='):]
            i += 1
        elif arg.startswith('--project-prop=runDir='):
            value = arg[len('--project-prop=runDir='):]
        else:
            rest.append(arg)
        i += 1
    return value, rest


def resolve(root, value):
    """The directory Gradle's `project.file(runDir)` names: absolute as given, otherwise under the project root."""
    path = pathlib.Path(value).expanduser()
    return (path if path.is_absolute() else root / path).resolve()


def fresh_directory(root, stamp=None):
    """A new, empty directory under build/gametest-runs/ that no earlier run used."""
    base = root / 'build/gametest-runs'
    stamp = stamp or time.strftime('%Y%m%d-%H%M%S')
    candidate, n = base / stamp, 1
    while candidate.exists():
        n += 1
        candidate = base / f'{stamp}-{n}'
    candidate.mkdir(parents=True)
    return candidate


def keep_world_aside(run_dir, stamp=None):
    """Moves <run_dir>/world to a free world.gametest-residue-<stamp>[-n] beside it; returns the new path or None."""
    world = run_dir / 'world'
    if not world.exists():
        return None
    stamp = stamp or time.strftime('%Y%m%d-%H%M%S')
    kept, n = run_dir / f'world.gametest-residue-{stamp}', 1
    while kept.exists():
        n += 1
        kept = run_dir / f'world.gametest-residue-{stamp}-{n}'
    world.rename(kept)
    return kept


def pin_seed(run_dir, seed):
    """Writes level-seed into the run directory's server.properties (created when absent)."""
    props = run_dir / 'server.properties'
    lines = props.read_text().splitlines() if props.exists() else []
    lines = [line for line in lines if not line.startswith('level-seed=')] + [f'level-seed={seed}']
    props.write_text('\n'.join(lines) + '\n')


def expected_tests(root):
    return sum(len(re.findall(r'@GameTest\(', p.read_text())) for p in (root / 'src/gameTest/java').rglob('*.java'))


def judge(text, expected):
    """(passed, reason) from a GameTest server log: every annotated test must have completed and the pass marker shown."""
    completed = re.search(r'(\d+) GAME TESTS COMPLETE', text)
    if not completed:
        return False, 'no "GAME TESTS COMPLETE" line: the server stopped before running the suite'
    if int(completed[1]) != expected:
        return False, f'{completed[1]} tests completed, {expected} expected'
    if not re.search(r'All \d+ required tests passed', text):
        return False, 'the required-test pass marker is missing'
    return True, f'{expected} tests completed; all required tests passed'


def main(argv):
    import environment, outcome
    root = environment.ROOT
    seed = None
    if '--seed' in argv:
        at = argv.index('--seed')
        if at + 1 >= len(argv):
            outcome.finish('FAIL', '--seed needs a value', 'gametests')
        seed = argv[at + 1]
        argv = argv[:at] + argv[at + 2:]
    value, gradle_args = split_run_dir(argv)
    if value is None:
        run_dir = fresh_directory(root)
        print(f'run directory: {run_dir} (fresh)')
    else:
        run_dir = resolve(root, value)
        run_dir.mkdir(parents=True, exist_ok=True)
        print(f'run directory: {run_dir} (given by -PrunDir)')
        # GameTests need a fresh world: a reused one fails settlement tests. Only this directory's world is kept aside.
        kept = keep_world_aside(run_dir)
        if kept:
            print(f'kept the previous world as {kept}')
    if seed is not None:
        pin_seed(run_dir, seed)
        print(f'level-seed pinned to {seed} in {run_dir / "server.properties"}')
    if not (run_dir / 'eula.txt').exists() and (environment.value('MCAKINGDOMS_ACCEPT_EULA') or environment.value('MCAKINGDOMS_EULA')):
        environment.eula(run_dir)
    code, log = environment.gradle('runGameTestServer', f'-PrunDir={run_dir}', *gradle_args)
    print(f'gradle log: {log}\nserver logs: {run_dir / "logs"}')
    if code or not log:
        outcome.finish('FAIL', f'Gradle exited with {code}; inspect {log}', 'gametests')
    passed, reason = judge(pathlib.Path(log).read_text(errors='replace'), expected_tests(root))
    if not passed:
        outcome.finish('FAIL', f'{reason}; inspect {log}', 'gametests')
    print(f'PASS: gametests: {reason} (run directory {run_dir})')


if __name__ == '__main__':
    main(sys.argv[1:])
