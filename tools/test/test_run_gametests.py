#!/usr/bin/env python3
"""Tests for run_gametests.py (AUD-18): only the resolved run directory's world may change, every Gradle spelling of
runDir resolves the same way, and a startup-only or incomplete suite fails. Run: python3 -I tools/test/test_run_gametests.py"""
import contextlib, io, pathlib, sys, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import environment, run_gametests  # noqa: E402

PASSING = 'GameTestServer: 3 GAME TESTS COMPLETE\nAll 3 required tests passed :)\n'


class Launcher(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name) / 'project root'
        (self.root / 'src/gameTest/java/x').mkdir(parents=True)
        (self.root / 'src/gameTest/java/x/T.java').write_text('@GameTest(a) @GameTest(b) @GameTest(c)')
        self.saved = environment.ROOT, environment.gradle, environment.value
        environment.ROOT = self.root
        environment.value = lambda name: None
        self.calls = []
        self.log_text = PASSING

        def gradle(task, *extra):
            self.calls.append((task, extra))
            log = pathlib.Path(self.tmp.name) / f'gradle-{len(self.calls)}.log'
            log.write_text(self.log_text)
            return 0, log
        environment.gradle = gradle

    def tearDown(self):
        environment.ROOT, environment.gradle, environment.value = self.saved
        self.tmp.cleanup()

    def run_main(self, *args):
        with contextlib.redirect_stdout(io.StringIO()):
            run_gametests.main(list(args))

    def world(self, directory, marker):
        world = directory / 'world'
        world.mkdir(parents=True)
        (world / 'level.dat').write_text(marker)
        return world

    def test_every_gradle_spelling_resolves_the_same_directory(self):
        for args in (['-PrunDir=a b'], ['-P', 'runDir=a b'], ['--project-prop', 'runDir=a b'], ['--project-prop=runDir=a b']):
            value, rest = run_gametests.split_run_dir(args + ['--info'])
            self.assertEqual(value, 'a b', args)
            self.assertEqual(rest, ['--info'], args)
            self.assertEqual(run_gametests.resolve(self.root, value), (self.root / 'a b').resolve())
        absolute = pathlib.Path(self.tmp.name) / 'elsewhere'
        self.assertEqual(run_gametests.resolve(self.root, str(absolute)), absolute.resolve())
        self.assertEqual(run_gametests.split_run_dir(['--info']), (None, ['--info']))

    def test_default_run_is_fresh_and_leaves_run_world_alone(self):
        unrelated = self.world(self.root / 'run', 'unrelated')
        self.run_main()
        self.assertEqual((unrelated / 'level.dat').read_text(), 'unrelated', 'the default run moved run/world')
        task, extra = self.calls[-1]
        self.assertEqual(task, 'runGameTestServer')
        target = pathlib.Path(extra[0][len('-PrunDir='):])
        self.assertTrue(str(target).startswith(str(self.root / 'build/gametest-runs')), target)
        self.run_main()
        second = pathlib.Path(self.calls[-1][1][0][len('-PrunDir='):])
        self.assertNotEqual(target, second, 'two default runs shared one directory')

    def test_custom_directory_with_spaces_keeps_only_its_own_world(self):
        unrelated = self.world(self.root / 'run', 'unrelated')
        target_dir = self.root / 'my runs/game test'
        own = self.world(target_dir, 'previous')
        self.run_main('-PrunDir=my runs/game test')
        self.assertEqual((unrelated / 'level.dat').read_text(), 'unrelated', 'another directory\'s world was moved')
        self.assertFalse(own.exists(), 'the target world was reused')
        kept = [p for p in target_dir.iterdir() if p.name.startswith('world.gametest-residue-')]
        self.assertEqual(len(kept), 1)
        self.assertEqual((kept[0] / 'level.dat').read_text(), 'previous', 'the previous world was not kept intact')
        self.assertEqual(self.calls[-1][1][0], f'-PrunDir={target_dir.resolve()}')

    def test_kept_worlds_never_collide(self):
        target_dir = self.root / 'run'
        self.world(target_dir, 'one')
        first = run_gametests.keep_world_aside(target_dir, stamp='same')
        self.world(target_dir, 'two')
        second = run_gametests.keep_world_aside(target_dir, stamp='same')
        self.assertNotEqual(first, second)
        self.assertEqual((first / 'level.dat').read_text(), 'one')
        self.assertEqual((second / 'level.dat').read_text(), 'two')

    def test_seed_is_pinned_in_the_run_directory_only(self):
        (self.root / 'run').mkdir()
        (self.root / 'run/server.properties').write_text('level-seed=\n')
        self.run_main('--seed', '42')
        target = pathlib.Path(self.calls[-1][1][0][len('-PrunDir='):])
        self.assertIn('level-seed=42', (target / 'server.properties').read_text())
        self.assertEqual((self.root / 'run/server.properties').read_text(), 'level-seed=\n')

    def test_startup_only_and_incomplete_runs_fail(self):
        self.assertTrue(run_gametests.judge(PASSING, 3)[0])
        self.assertFalse(run_gametests.judge('Done (3.1s)! For help, type "help"\n', 3)[0])
        self.assertFalse(run_gametests.judge('0 GAME TESTS COMPLETE\nAll 0 required tests passed\n', 3)[0])
        self.assertFalse(run_gametests.judge('3 GAME TESTS COMPLETE\n1 required test failed\n', 3)[0])
        self.log_text = 'server started and stopped\n'
        with self.assertRaises(SystemExit) as failed:
            self.run_main()
        self.assertEqual(failed.exception.code, 1)


if __name__ == '__main__':
    unittest.main(verbosity=1)
