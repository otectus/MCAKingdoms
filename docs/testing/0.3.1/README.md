# Testing 0.3.1 (review brief of 2026-11-09)

Commands, results and logs for the 0.3.1 work of 9–10 November 2026: the review brief
`MCAKingdoms_Review_and_Implementation_Brief_2026-11-09.md` (AUD-01 to AUD-18, WAY-01 Waystones names, UX-01, VAL-01).
The 0.3.0 record stays in [Testing 0.3.0](../0.3.0/README.md). Logs named below are kept locally under
`build/v031-evidence/` (git-ignored): `logs/` for Gradle and check output, `runtime/<label>-<n>/` for each packaged run
(with its own `artifacts.sha256`), `client/` for client runs. Every failure found is reported, and every check that was
not run is listed [at the end](#not-executed).

Environment: Linux (CachyOS), OpenJDK 17.0.20.1, Minecraft 1.20.1, Forge 47.4.23, Gradle 8.8 run `--offline` through
`/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh`. Headless clients ran under Xvfb from the CachyOS
`xorg-server-xvfb-21.1.25-2.1` package (signature checked against CachyOS key `882DCFE4…8DB35A47`).

## Build, unit tests and resources

| Check | Command | Result |
| --- | --- | --- |
| Unit tests | `gradlew-quiet.sh . test --offline` | PASS. 432 unit tests, 0 failures, 1 skipped (pre-existing). Log `logs/gradle-MCAKingdoms-test-20261110-184513.log`. |
| Build, API and acceptance jars | `gradlew-quiet.sh . build productionTestJar -I tools/test/production-tests.gradle --offline` | PASS (`logs/gradle-MCAKingdoms-build-20261110-184546.log`); unit tests up to date from the row above. |
| Client acceptance jar | `gradlew-quiet.sh . productionClientTestJar -I tools/client-test/client-test.gradle --offline` | PASS (`logs/gradle-MCAKingdoms-productionClientTestJar-20261110-184602.log`), after the harness fix below. |
| Resources and languages | `python3 -I tools/test/validate_resources.py` | PASS: 4,846 keys in each of `en_us`, `es_es`, `zh_cn`; placeholders match; every keyed message matches `en_us` (`logs/validate-resources-final.txt`). |
| Assets | `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py .` | 0 errors, 0 warnings; the one note is the charter's "sealed" override model, as in 0.3.0 (`logs/check-mod-final.txt`). |
| GameTest launcher | `python3 -I -m unittest discover -s tools/test -p test_run_gametests.py` | PASS, 6 tests (`logs/test_run_gametests-final.txt`). |

## GameTests

`python3 tools/test/run_gametests.py --offline`: a fresh run directory under `build/gametest-runs/`, the seed pinned there,
and the result judged from that run's own log (AUD-18).

| Run | Result | Log |
| --- | --- | --- |
| 3 (before the last Townstead, development and Waystones changes) | PASS: 129 tests completed, all required passed | `logs/gradle-MCAKingdoms-runGameTestServer-20261110-175404.log`, run `build/gametest-runs/20261110-175404` |
| 4 | PASS: 129 tests completed, all required passed | `logs/gradle-MCAKingdoms-runGameTestServer-20261110-181408.log`, run `build/gametest-runs/20261110-181408` |
| 5 (machine also running a client and packaged servers) | FAIL: 1 required test, `loadedStructureStartsDiscoveredLazily` ("did not preserve two nearby starts: 1") | `logs/gradle-MCAKingdoms-runGameTestServer-20261110-183325.log` |
| 6 (same load; first run with the new capability GameTest) | FAIL: 2 required tests, the same one and `aVillageBuiltInLoadedChunksIsRecognizedFromItsBlocks` ("not recognized yet") | `logs/gradle-MCAKingdoms-runGameTestServer-20261110-183833.log` |
| 7 (final source, machine otherwise idle) | PASS: 130 tests completed, all required passed | `logs/gradle-MCAKingdoms-runGameTestServer-20261110-184617.log`, run `build/gametest-runs/20261110-184617` |
| 8 (final source, alongside the final packaged and client runs) | PASS: 130 tests completed, all required passed | `logs/gradle-MCAKingdoms-runGameTestServer-20261110-184802.log`, run `build/gametest-runs/20261110-184802` |

Both failures were in the test fixtures, not the product. `loadedStructureStartsDiscoveredLazily` read only the first
page of 64 settlements, and 0.3.1's new GameTests made a run hold more than 64, so one record could sort onto page 2; it
now reads every page. `DiscoveryGameTests` built its village at sea level wherever the test landed; adding a GameTest
moves later tests to other terrain, and a cluster more than 24 blocks below the surface is refused by design. That is
the likely cause (the run did not log the test's position, so the terrain there was not checked); the fixture now
builds on the surface. Runs 7 and 8 include both fixes.

## Packaged dedicated-server acceptance

`python3 build/v031-evidence/scripts/run_packaged.py <label…>` runs `tools/test/integration_runtime.py` once per label in
a fresh directory `build/v031-evidence/runtime/<label>-<n>`, with the jars it used in that directory's `artifacts.sha256`
and one line per run in `runtime/summary.txt`. Every run below used `mcakingdoms-0.3.1.jar` `1e5e6a7b…` and the
acceptance harness `28a26d9a…` (section "final sequence on 1e5e6a7b", 18:47 to 19:13 on 10 November). Companion jars are
the validated ones in `tools/test/companions.json`, by SHA-256.

| Label (phase) | Companions | Result | Seconds | Directory |
| --- | --- | --- | ---: | --- |
| `waystones` (`waystones`, `waystones-restart`) | Waystones 14.1.21, Balm 7.3.44 | PASS: 27 checks on first boot (generated, placed and numbered names; outside a settlement; sharestone; observed player edit; backfill; a freed number reused; same-text edit; broken stone frees its name; settlement rename leaves names; late village, and a waiting stone its player named meanwhile), then 10 after restart | 90 | `waystones-6` |
| `waystones-unsupported` | the tested Waystones jar reporting version 14.1.99 (bytes otherwise unchanged), Balm 7.3.44 | PASS: 7 checks (reported unsupported, observer not applied, native names, nothing recorded, backfill refused) | 50 | `waystones-unsupported-4` |
| `waystones-untested-build` | Waystones 14.1.21 with one added file (different SHA-256), Balm 7.3.44 | PASS: 12 checks (naming on, edits not observed, the player's rename kept and settled at the next save) | 47 | `waystones-untested-build-4` |
| `initial` | none | PASS | 23 | `initial-3` |
| `politics` | none | PASS, 3 boots | 87 | `politics-3` |
| `split-village` | none | PASS | 42 | `split-village-3` |
| `townstead-ts080` (`townstead`) | MCA 7.7.1-beta.5, Townstead 0.8.0 | PASS: first boot, restart, Townstead removed (store byte-identical), restored | 110 | `townstead-ts080-3` |
| `initial-ts080` (`initial`) | MCA 7.7.1-beta.5, Townstead 0.8.0 | PASS | 21 | `initial-ts080-3` |
| `townstead-ts076` (`townstead`) | MCA 7.6.26, Architectury 9.2.14, Townstead 0.7.6, Patchouli | PASS, the same four boots | 80 | `townstead-ts076-2` |
| `capitals` | MCA 7.6.26, Architectury, MCA Capitals 1.1.1 | PASS | 20 | `capitals-2` |
| `capitals-137` (`capitals`) | MCA 7.7.1-beta.5, Architectury, MCA Capitals 1.3.7, Townstead 0.8.0 | PASS | 22 | `capitals-137-2` |
| `lv` | Recruits 1.15.2, Living Villages 1.1.0 (audited jar) | PASS: import, restart, provider absent (store byte-identical), reinstalled, future schema, unaudited jar refused | 180 | `lv-2` |
| `lv-mca`, `lv-mca77` (`lv-mca`) | the above with MCA 7.6.26 and Architectury, or MCA 7.7.1-beta.5 | PASS | 23, 30 | `lv-mca-1`, `lv-mca77-1` |
| `r2` | MCA 7.6.26, Architectury, MCA Quests 1.7.1 (local contract build), MCA: Crime 0.7.5, Townstead 0.7.6, Patchouli, GeckoLib | PASS | 52 | `r2-1` |
| `r3`, `military`, `r4-fence` | Recruits 1.15.2 | PASS (r3: 5 boots) | 74, 30, 14 | `r3-1`, `military-1`, `r4-fence-1` |
| `r3-civilian`, `r4`, `r4-service`, `r4-family`, `r4-extensions`, `r4-crash` | full pack set: Recruits, MCA 7.6.26, Architectury, MCA Quests 1.7.1, MCA: Crime 0.7.5, Townstead 0.7.6, Patchouli, GeckoLib | PASS (r4-crash: killed and restarted) | 33, 95, 15, 15, 13, 36 | `<label>-1` |
| `migration` | MCA 7.6.26, Architectury, MCA Reputation 0.6.1 (local build) | PASS | 46 | `migration-1` |
| `r4-ts080`, `r3-civilian-ts080` (`r4`, `r3-civilian`) | the full pack set on MCA 7.7.1-beta.5 and Townstead 0.8.0 | PASS | 96, 39 | `<label>-1` |
| `r4-full-waystones` (`r4`), `initial-full-waystones` (`initial`) | the full pack set on MCA 7.7.1-beta.5 and Townstead 0.8.0, plus Waystones 14.1.21 and Balm 7.3.44 | PASS | 96, 15 | `<label>-1` |

Twenty-nine runs, none failed. Earlier runs on superseded jars (`506ad0c1`, `54de2c91`, `9775bdf0`) are kept in the same
folder; among them `waystones-4` failed on a scenario check that was then removed (see below), and `capitals-137-1` was
refused by Forge because its companion set lacked Architectury.

Companion manifest: `python3 -I tools/test/verify_companions.py --mod <waystones jar> --mod <balm jar>`: PASS, contract
ok for both (`logs/verify-companions-waystones.txt`).

## Client checks

Headless clients under Xvfb, one fresh directory each under `client/`, each recording the jars it ran
(`artifacts.json` or `artifacts.sha256`): `mcakingdoms-0.3.1.jar` `1e5e6a7b…` and the client harness `662a5658…`.

| Check | Command | Result |
| --- | --- | --- |
| GUI tour | `python3 tools/client-test/interaction_client.py --work-dir build/v031-evidence/client/gui-tour-3 --gui-style --languages es_es,zh_cn --xvfb /tmp/kd-xvfb/usr/bin/Xvfb --display :95` | PASS: 162/162 style checks (every widget inside the screen, none overlapping, at GUI scales 2 and 3 and the compact view, in `en_us`, `es_es` and `zh_cn`) and the interaction pass (K key, start view, All tasks, search, create/read/rename with review, compact paging, War Room). 168 screenshots. `gui-3-start.png` shows *Where you are* and its actions above the introduction at GUI scale 3; `00-start-view.png` shows the outside-a-settlement state with *Why not here?* and *Look for a settlement around me*. |
| Book client | `python3 tools/client-test/book_client.py --work-dir build/v031-evidence/client/book-2 --xvfb /tmp/kd-xvfb/usr/bin/Xvfb --display :96` | PASS, 1,533 checks: all 87 topics (including the new Waystones topic and the Living Villages and Townstead topics) laid out at 320x240, 426x240, 640x360 and 960x540 in three languages; recipes, `/reload`, keyboard links, bookmarks, the Ledger. What the narrator would read is written to `BOOK_NARRATION.txt`; nobody listened to it. |
| Two clients: guilds | `python3 tools/client-test/civic_multiplayer.py --work-dir build/v031-evidence/client/civic-mp-2 --xvfb /tmp/kd-xvfb/usr/bin/Xvfb --display :97` | PASS for both clients: Guild screen join, leave and rejoin through reviewed tasks by two players on one server (the scenario that once refused one player's join as stale because the other had joined), refusals shown as refused. |

The earlier client runs on superseded jars (`gui-tour-2`, `book-1`, `civic-mp-1` on `9775bdf0`) also passed. The client
logs' expected environment exceptions are offline authentication and the missing `flite` narrator library.

## Final build

Built after the last source change that reaches the jars (`build/v031-evidence/final-jars.sha256`):

| Artifact | SHA-256 |
| --- | --- |
| `build/libs/mcakingdoms-0.3.1.jar` | `1e5e6a7bce5b73c989d57d37805195082e87a9f763848dc920febc69064bbf79` |
| `build/libs/mcakingdoms-0.3.1-api.jar` | `66c0c096cd46f325075dd331297e76302f487f2f867682a167f3069af72eca51` |
| `build/libs/mcakingdoms-0.3.1-sources.jar` | `bae4442d4b11c86a93ec19d91546b9ea78a59afed1abc9abb059176cbc7d26e9` |
| `build/test-artifacts/mcakingdoms-0.3.1-acceptance.jar` | `28a26d9a3db16b9c02a2822ab1d5c3f78fa506b87eac3da8a2f161a44da41e9f` |
| `build/test-artifacts/mcakingdoms-0.3.1-client-acceptance.jar` | `662a565873bb941da55a0eb7ce541ecb22b8c09c03f74a3de717a0094ac43424` |

Built by `gradlew-quiet.sh . build productionTestJar -I tools/test/production-tests.gradle --offline` (PASS, unit tests
432/0/1 up to date from `logs/gradle-MCAKingdoms-test-20261110-184513.log`; log `logs/gradle-MCAKingdoms-build-20261110-184546.log`)
and `gradlew-quiet.sh . productionClientTestJar -I tools/client-test/client-test.gradle --offline`
(`logs/gradle-MCAKingdoms-productionClientTestJar-20261110-184602.log`).

## Found and fixed during validation

- `tools/test/validate_resources.py` could not resolve a key prefix defined as another class's constant
  (`P = DevelopmentService.P;`, or `B = DevelopmentService.P + "blocker."` when several classes have a `P`), so 56 keyed
  messages in the new development code went unchecked. It now looks the class up by name; all 56 match `en_us`.
- The client acceptance harness no longer compiled against the Townstead civic header record widened by the uncommitted
  Townstead 0.8.0 work; it now passes the authority key and settlement.
- `/kingdoms waystones backfill` read the whole Waystones registry once per stone. It now builds one index of names
  per pass (`WaystoneNames.assign` with a predicate; `WaystoneNamesTest.oneIndexServesManyAssignmentsInOrder`).
- A naming record of a stone broken without silk touch kept reserving its name, so later stones got higher numbers. Such
  records now expire at the next save or restart (packaged check "the broken stone's name is free again").
- Townstead baselines were labelled as pruned automatically, but nothing pruned them. Baselines of settlements that no
  longer exist are now dropped at server start.
- A full register of public works was reported as "could not be saved". It is now refused as full.
- At GUI scale 3, the start view's *Where you are* sat below the fold behind the introduction. It now comes first.
- The two GameTest fixtures described under GameTests.
- The Book client's pass record said "all 70 topics" as fixed text; it now reports the topics it laid out (87).
- A packaged silk-touch re-placement check did not reproduce Waystones' real pick-up path (the record was removed with
  the block). It was dropped rather than asserted on an approximation; see Not executed.
- The first companion set for the Capitals 1.3.7 run lacked Architectury, which Capitals requires; Forge refused to
  load it (`runtime/capitals-137-1`). The corrected set passed.

## Regression evidence by item

GameTests are in `src/gameTest/java/dev/otectus/mcakingdoms/`, unit tests in `src/test/java/dev/otectus/mcakingdoms/`, packaged
scenarios in `tools/test/runtime/java/dev/otectus/mcakingdoms/acceptance/`.

| Item | Change and schema | Tests that show the old failure is gone |
| --- | --- | --- |
| AUD-18 | `tools/test/run_gametests.py` resolves the run directory Gradle uses, starts fresh, pins the seed there and judges that run's log. | `tools/test/test_run_gametests.py`: 6 tests (every Gradle spelling of the directory, fresh default run, custom directory with spaces, kept worlds never collide, seed pinned only in the run directory, startup-only and incomplete runs fail). |
| AUD-01 | Root commit of `mcakingdoms_settlements.dat` before events; durable identity outbox (optional key, schema stays 1); idempotent consumers retried at start and every minute. | `core/IdentityCommitGameTests`: a new settlement is on disk before it is announced; a failed root write refuses the merge and announces nothing; a store that cannot follow stays pending across a restart. `core/IdentityOperationTest`: 3. |
| AUD-02 | Inventory operations journaled with player-side markers; confirmed by the player's save; settled at login. Development schema 2. | `development/DevelopmentGameTests`: a delivery lost in a crash is rolled back; a refund into a full inventory keeps the rest owed; a cancelled work refunds its deliveries once. |
| AUD-03 | Placement stages PLANNED, INTENT, APPLIED, SAVED, VERIFIED (two saves), settled from the world after a crash. | `DevelopmentGameTests`: a funded lamp post is built once across a restart; a refused placement is not counted; a failed journal write after placement counts once; a block removed after placement is not rebuilt. |
| AUD-04 | Current policy before every block; persisted work kind; structured blockers. | `DevelopmentGameTests`: current policy stops the next block; a survey refuses a plot beside a bed; lowering the mode pauses and re-enabling does not resume. |
| AUD-05 | Full block-state comparison with neighbour-shape normalization; exact-cell ownership; rollback stage with rechecks and retained cells. | `DevelopmentGameTests`: a changed block stops construction and rollback removes only ours; a player's block is never adopted. |
| AUD-06 | Development identity consumer; authority from the current kingdom; overflow kept; conservative consent. | `DevelopmentGameTests`: a merge carries development once. `development/DevelopmentStoreTest`: a merge carries everything once with conservative consent; consent is conservative when only one settlement allowed construction; a kingdom change pauses construction for review. |
| AUD-07 | Productive assets apart from history; retention only once effects are kept elsewhere; server-issued, time-stamped request ids with a watermark; "register full" refusal. | `DevelopmentStoreTest`: retention keeps productive works and anything still owed; retention refuses rather than drop live work; evicted receipts raise the replay watermark; request ids carry their issue time; a schema 1 file migrates without losing placed blocks or completed works; a newer file is kept read-only and unchanged. `DevelopmentGameTests`: an expired request is refused. |
| AUD-08 | Intent matrix across development, dynasty and documents; services honour it. | `DevelopmentGameTests`: recovery stays available while development is off; the default mode changes nothing. `politics/DocumentGameTests`: revocation and recovery work while heraldry is off. `interaction/TaskIntentTest`: development, document and court recovery need no creation switch; new work keeps the gate its service applies. |
| AUD-09 | Charter deliveries (optional key, schema stays 1); recovery onto a blank charter from the same record. | `DocumentGameTests`: a charter lost before its save is recovered. |
| AUD-10 | Review fingerprints of named parts; stale notices name the part; typed outcomes with probes. | `interaction/ReviewTest`: 4. |
| AUD-11 | Bounded, fair, deduplicated discovery queue; rescans on village block changes; player sweep; *Look for a settlement around me*. | `core/DiscoveryGameTests`: a village built in loaded chunks is recognized from its blocks; a player can ask for their surroundings to be scanned. `core/DiscoveryQueueTest`: 5. Packaged `waystones` (late village recognized without reloading chunks). |
| AUD-12 | `SettlementBounds.MAX_SPAN`, overflow-safe spans, candidate refusal, isolated and large settlements in the index. | `core/SettlementGeometryTest`: 4. |
| AUD-13 | Townstead mappings staged and committed at the reload boundary. | `compat/townstead/TownsteadMappingsTest`: mappings become current only when the reload commits. |
| AUD-14 | Shared capability checks with an *unknown* state for every summary and gate. | `interaction/CapabilityFactsTest`: a check that fails is unknown, never available. `interaction/CapabilityGameTests`: the summary promises nothing its tasks refuse. |
| AUD-15 | Explicit groups; named career choices; tier only for professions; "cannot check" distinct from "not yet". | `TownsteadMappingsTest`: a skill requirement takes no tier. Packaged `townstead-ts080`. |
| AUD-16 | Pinned task status forgotten on disconnect. | No automated test; the client code path is `client/PinnedTask.forgetServer` from `ClientPresentationState.onLogout`. |
| AUD-17 | Current guidance corrected; Book and task text in three languages. | The documentation verification pass below. |
| WAY-01 | `compat/waystones/`, `mixin/WaystonesEditMixin`; new store `mcakingdoms_waystones.dat` (schema 1). | `compat/waystones/WaystoneNamesTest`: 7. Packaged `waystones`, `waystones-restart`, `waystones-unsupported`, `waystones-untested-build` (tables above). |
| UX-01 | Contextual start-view actions; *Look for a settlement around me*. | Packaged GUI tour (client checks above). Real first-time players: not executed. |
| Capacity | Townstead baselines of settlements that no longer exist are dropped at start. | `compat/townstead/TownsteadSavedDataTest`: baselines of settlements that no longer exist are dropped. |

## Not executed

These are not passed; they were not run:

- **Five sessions with real first-time players** (UX-01, 6.5): no participants were available in this environment.
  Next: run the five goal-based sessions on the plain mod and on the companion pack, recording time to the first
  useful action, wrong turns, misread terms and any need for commands or identifiers.
- **Audible narration**: this machine's client has no `flite` library, so the narrator cannot speak. The narrated text
  is checked by the Book client only.
- **Two-client runs of development, charters, dynasty and the native Waystones screens**: no harness exists for these
  flows. The existing two-client harnesses cover politics, evolution, warfare and guilds; the guild one was rerun.
- **The performance matrix** (VAL-01, 7.3: 10, 50 and 200 settlements; 1, 10 and 50 works; queued scans; a large
  Waystones registry; partially unloaded construction; two clients during maintenance): no harness exists, and no
  numbers are claimed. The one performance change is structural: backfill no longer reads the registry per stone.
  Next: a packaged scenario that samples the server's tick times under each load.
- **A client with the full target pack** (overlapping keybindings, activation and menu order): the client harnesses
  install a fixed subset of mods.
- **Waystones rows of the brief's 5.7 not covered**: silk-touched re-placement; a process kill between an assignment
  and the save; a read-only naming ledger; a full waiting list (256); warp plates; a missing provider member (only a
  changed version and changed bytes were run); the same coordinates in two dimensions in a packaged run (unit-tested
  only); two clients, reconnects and Waystones' own screens converging.
- **AUD-16 across two servers**: the pinned task's status is cleared on disconnect in client code; no automated test
  joins two servers.
- **Paid workshop jobs on a public Quests/Crime tuple**: the validated MCA Quests 1.7.1 jar is a local contract build,
  and no public release carries `institutionalCompletionConsumer()`. Paid workshop jobs stay refused without it, as in
  0.3.0.
- **MCA Capitals 1.2.1 to 1.3.6**: not run.
- **GUI-scale, shader and texture profiling of new blocks**: 0.3.1 adds no blocks; not rerun.
