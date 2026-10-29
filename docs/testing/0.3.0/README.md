# Testing 0.3.0 (audit handoff)

Commands, results and logs for the 27 October 2026 audit handoff work (A dynasty, B heraldry, C MCA Capitals, D
development, H01–H12, P08). The earlier review handoff's evidence is in the [completion ledger](../../Completion-Ledger.md).
Logs named below are kept locally under `build/v030-evidence/audit/` (git-ignored), with each run's own working
directory under `build/v030-evidence/audit/runtime/`. Every failure is reported as a failure, and every check that was
not run is listed [at the end](#not-executed).

Environment: Linux (CachyOS), OpenJDK 17.0.20.1, Minecraft 1.20.1, Forge 47.4.23. Gradle runs through the quiet
wrapper named by `MCAKINGDOMS_GRADLEW_QUIET`.

## Build, unit tests and resources

| Check | Command | Result |
| --- | --- | --- |
| Build, unit tests, API jar, acceptance jar | `gradlew check build productionTestJar -I tools/test/production-tests.gradle` | PASS. 323 unit tests, 0 failures, 1 skipped (pre-existing). Logs `logs/gradle-.-check-20261028-010740.log` (the jars of the long packaged runs) and `logs/gradle-.-check-20261028-020040.log` (final). |
| New unit tests | (part of the above) | `SuccessionResolverTest` 14, `DynastyDefinitionsTest` 6, `DynastySavedDataTest` 3, `ExamplePackDynastyTest` 1, `DevelopmentLedgerTest` 5, `OrganizationDefinitionTest.theExamplePackInstitutionParses`. |
| Resources and languages | `python3 -I tools/test/validate_resources.py` | PASS: 3,956 keys in each of `en_us`, `es_es`, `zh_cn`; placeholders match; every keyed message (including those built from a file's key prefix) matches `en_us`. Log `logs/final-resource-checks-20261028-021759.txt`, with the next two rows. |
| Fallback drift | `python3 -I build/v030-evidence/audit/h03/check_fallbacks.py .` | missing 0, mismatched fallbacks 0, placeholder drift 0. |
| Assets | `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py .` | 0 errors, 0 warnings; one note: `kingdom_charter_sealed.json` is the charter's "sealed" override model, not a leftover. |
| Companion manifest | `python3 -I tools/test/verify_companions.py --mod <jar>` for MCA Capitals 1.1.1 and 1.3.7 | PASS for both, contract ok. |

Jars tested by the packaged runs below: `mcakingdoms-0.3.0.jar` SHA-256 `88968992426639749d4e3549f7f32490b3b435a3960721d9a99410c56ac8d3a3`,
acceptance harness `834411135ce4dee11d7c9dca5f6065042e27d316b423453f635d524719771de1`. The final jar's hash is in
[Final build](#final-build).

## GameTests

`python3 tools/test/run_gametests.py` (moves `run/world` aside first).

| Run | Result | Log |
| --- | --- | --- |
| After A, B and C | 106/106 | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-001950.log` |
| After D | 112/112 | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-004731.log` |
| After H10, P08 and H01 changes | 112/112 | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-012024.log` |
| Final source, while two packaged servers ran | 111/112: `VillageIdentityGameTests.startupPassReconcilesOnceAndRecordsIt` failed (see below) | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-020144.log` |
| Rerun | not run: Gradle could not resolve Mojang's hosts for a moment (`UnknownHostException: piston-meta.mojang.com`) | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-020804.log` |
| Rerun | 112/112 | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-020857.log` |
| Final source with the GameTest village override | 112/112 | `logs/gradle-MCAKingdoms-runGameTestServer-20261028-021456.log` |

New GameTests: `DynastyGameTests` (5: acceptance across a restart, elections never displaced, a minor heir under a
guardian regency, an observed death with one memorial and mourning, a crown governed elsewhere), `DocumentGameTests`
(2: official, forged, historical, revoked, reused request, ledger reload; a placed banner's kingdom),
`DevelopmentGameTests` (6: off by default, a lamp post built once across a restart and a simulated crash, a plot beside a
bed refused, a changed block stopping construction and a rollback removing only the work's blocks, refunds once, a
lowered mode pausing construction), `CapitalsInteropGameTests` (1).

Defects these runs found and that were fixed: a survey that shape-updated template blocks against the unbuilt world
(the lantern was planned as air); the Capitals GameTest's village ids colliding with another test's range and its
16-block spacing merging its settlements; a residence GameTest that depended on the layout to have its chunk loaded;
player heirs judged by the server's player list instead of the government's known-player test; a law-driven regency
that broke the political store's rule that a regency preserves the named successor.

**The one GameTest failure** (20261028-020144) was a test-isolation flaw that had existed since the village identity
work, not a product defect. The village identity tests place their scenarios at random coordinates, and the GameTest
world generates structures. In that run a naturally generated plains village (start chunk 12787, 22611) lay two chunks
from the test's Peacecross record, so the start-up pass correctly reconciled Peacecross with that real village instead of
the test's synthetic one. The saved world shows it: Peacecross took the real village's structure key, and that chunk holds a
`minecraft:village_plains` start (`audit/region_starts.py`, `audit/scan_villages.py`). It is the only failure of this test in the
36 complete GameTest runs whose logs are kept (18 to 28 October 2026). Fix: `src/gameTest/resources/data/minecraft/worldgen/structure_set/villages.json`
gives vanilla's village set a placement frequency of 0 in GameTest worlds only; the GameTest source set's resources never
reach a release or acceptance jar. Tests that need a village start plant one, as before. After the fix, the GameTest world
of the 021456 run held only the two village starts `KingdomGameTests` plants (12,297 chunks scanned).

## Packaged dedicated-server acceptance

`python3 build/v030-evidence/audit/run_packaged.py`, which runs
`python3 tools/test/integration_runtime.py --work-dir <new dir> --phase <phase> --mod <jar>…` for each phase in a fresh
directory. Every boot must end with `PASS integration`. Summary files: `runtime/final-summary.txt`, and
`runtime/extra-summary.txt` for `r3-civilian` and `military` (run with the same command line by hand).

| Phase | Companions | Result | Seconds |
| --- | --- | --- | --- |
| `initial` | none | PASS | 13 |
| `politics` | none | PASS, 3 boots | 73 |
| `split-village` | none | PASS | 39 |
| `capitals` | MCA 7.6.26, Architectury 9.2.14, MCA Capitals 1.1.1 | PASS | 15 |
| `r4-fence` | Recruits 1.15.2 | PASS (profile below) | 32 |
| `r4` | Recruits, MCA, Architectury, MCA Quests 1.7.1, MCA: Crime 0.7.5, Townstead 0.7.6, Patchouli, GeckoLib | PASS, 5 boots plus future sidecars | 138 |
| `r2` | MCA, Architectury, MCA Quests, MCA: Crime, Townstead, Patchouli, GeckoLib | PASS | 67 |
| `migration` | MCA, Architectury, MCA Reputation 0.6.1 | PASS | 63 |
| `r3-civilian` | Recruits, MCA, Architectury, MCA Quests, MCA: Crime, Townstead, Patchouli, GeckoLib | PASS, 2 boots (jurisdiction policy through Crime's `JurisdictionPolicyApi`, civilian relief, once-only proof) | 53 |
| `military` | Recruits 1.15.2 | FAIL at "native defense muster applied": the scenario still expected the pre-0.3.0 English result "Mobilized…". Fixed in the scenario (it now reads the deployment record); rerun under [Final build](#final-build). | 22 |

### H07 transfer fence profile

Phase `r4-fence`, one server with 400–500 loaded entities, transferring 1, 5, 20 and 100 units. "Broad" is the old global
save; "targeted" is the 0.3.0 check of the unit's own chunk and Recruits' named files.

| Units | Broad save ms (min / median / max) | Targeted check ms (p50 / p95 / p99 / max) | Entities in the unit's chunk |
| --- | --- | --- | --- |
| 1 | 255.2 / 430.3 / 614.2 | 9.3 / 9.3 / 9.3 / 9.3 | 5 |
| 5 | 584.9 / 597.5 / 598.9 | 7.0 / 7.3 / 7.3 / 7.3 | 9 |
| 20 | 602.6 / 614.2 / 619.6 | 7.9 / 11.4 / 12.8 / 12.8 | 24 |
| 100 | 657.1 / 669.5 / 827.4 | 9.8 / 16.2 / 21.0 / 22.8 | 104 |

No write was left queued after any check (`queued=0`).

### H01 R3 repeated

`python3 build/v030-evidence/audit/run_packaged.py 20` ran `r3` (with Recruits 1.15.2) 20 times in a row after the
phases above, each in a fresh directory (`runtime/final-r3-1` … `final-r3-20`) on the jar pair listed above. Each run
boots five times: the scenario, a restart, Recruits removed, Recruits reinstalled, and a newer control schema that must
stay byte-identical.

| Runs | Result | Boots per run | Duration |
| --- | --- | --- | --- |
| 20 | 20 PASS, 0 FAIL (exit 0, 54 `PASS` lines each) | 5 | 90–242 s, median 116 s |

"saved native siege appears in history", the check that failed intermittently on 2026-10-01, passed in all 20 runs. The
slower runs overlapped other work on the machine (compiles and the `r3-civilian` and `military` phases). Summary:
`runtime/final-summary.txt`.

## Client checks

Headless clients under Xvfb (`/tmp/kd-xvfb`, the CachyOS `xorg-server-xvfb` package, signature checked), one fresh
directory each under `audit/client/`, each recording the jars it ran (`artifacts.json` or `artifacts.sha256`).
`build/v030-evidence/audit/run_clients.py` ran the checks on the final jars in sequence; summary `client/client-summary.txt`.

| Check | Command | Result |
| --- | --- | --- |
| GUI tour 1 (jar 88968992) | `python3 tools/client-test/interaction_client.py --work-dir build/v030-evidence/audit/client/gui-tour-1 --gui-style --languages es_es,zh_cn --xvfb /tmp/kd-xvfb/usr/bin/Xvfb --display :95` | PASS: 162/162 style checks (18 screens × GUI scales 2, 3 and a 240-pixel-high compact view × `en_us`, `es_es`, `zh_cn`: every widget inside the screen, none overlapping), and the interaction pass (K key, All tasks, search, create/read/rename with review, compact paging, War Room). Its screenshots showed a defect the checks cannot see: with nine tabs, the compact Kingdom page used three tab rows, leaving the record about one line. |
| GUI tour 2 (final jars) | the same, `--work-dir …/gui-tour-2` | PASS: 162/162 and the interaction pass, 367 s. Short pages now show one "Section" picker (`gui-compact-kingdom.png`, `es_es-gui-compact-kingdom.png`); wide pages keep the tabs, five to a row where they fit (`gui-2-kingdom.png`); Chinese labels fit five to a row even in the compact view (`zh_cn-gui-compact-kingdom.png`). |
| Book client (final jars) | `python3 tools/client-test/book_client.py --work-dir …/book-1 --xvfb /tmp/kd-xvfb/usr/bin/Xvfb --display :96` | PASS, 1,425 checks in 100 s: all 78 topics load and are laid out in `en_us`, `es_es` and `zh_cn` at four window sizes, including the six new 0.3.0 topics and those that gained diagrams; live recipes, data-pack replacement and removal on `/reload`, keyboard links, bookmarks; "Recipe narration reads the arrangement and ingredients" (narration checked as text). |
| Two clients: politics (final jars) | `python3 tools/client-test/politics_multiplayer.py --work-dir …/politics-mp-1` with a private Xvfb display | PASS: the authorized client sees a private proposal and the unauthorized client receives none. Its wait now matches the 0.3.0 result wording. |
| Two clients: evolution (final jars) | `python3 tools/client-test/evolution_multiplayer.py --work-dir …/evolution-mp-1 --xvfb … --display :97` | PASS for both clients (private history packet, competing outcome race) and the server audit (`R4_MULTIPLAYER_AUDIT_PASS`), whose checks now read the scenario record instead of page text. |
| Two clients: warfare (final jars) | `python3 tools/client-test/warfare_multiplayer.py --work-dir …/warfare-mp-1 --xvfb … --display :97` | PASS for both clients: snapshot privacy and native diplomacy packets. |
| Two clients: guilds (final jars) | `python3 tools/client-test/civic_multiplayer.py --work-dir …/civic-mp-1 --xvfb … --display :97` | PASS for both clients: Guild screen join, leave and rejoin through reviewed tasks, refusals shown as refused, normal and compact screenshots. |

These two-client tests cover features from before 0.3.0. No two-client test covers the dynasty, charters or development (see
[Not executed](#not-executed)).
The environment's own exceptions in client logs are offline authentication (no network login) and the missing `flite`
library (narrator), both expected on this machine.

## Final build

The jars below were built after the last source change that reaches them: the scenario fixes, the Section picker,
the herald and development changes and the operator-output fix. Hashes: `audit/final-jars.sha256`.

| Artifact | SHA-256 |
| --- | --- |
| `build/libs/mcakingdoms-0.3.0.jar` | `6b7e8d4d6c6f6b01ed5095a4b4d092da2a9719db7825c571ec879908434f165c` |
| `build/libs/mcakingdoms-0.3.0-api.jar` | `6e8f56b0323fc6fd0aa49b0149a49c4397278b79c5da948d1e0977428c1dc821` |
| `build/libs/mcakingdoms-0.3.0-sources.jar` | `51de25528a9ae820f8d92f7bbac338212f1f21ca4a62a885039d4408f30edce9` |
| `build/test-artifacts/mcakingdoms-0.3.0-acceptance.jar` | `346bb4f91939fa51603b5262652567a322001e27cd2c4da50cb54898492aaf31` |
| `build/test-artifacts/mcakingdoms-0.3.0-client-acceptance.jar` | `4c304b47504b7cb87cd0e7bd67af9680ac26b6d950d893fa9388492c2bc6b07d` |

Built by `gradlew check build productionTestJar -I tools/test/production-tests.gradle` (PASS, unit tests 323/0/1, log
`logs/gradle-.-check-20261028-020040.log`) and `gradlew productionClientTestJar -I tools/client-test/client-test.gradle`
(PASS, `logs/gradle-.-productionClientTestJar-20261028-020122.log`). The GameTest village override added afterwards
is a GameTest resource, which no jar contains.

Packaged phases on these jars: `python3 build/v030-evidence/audit/run_after_rebuild.py <phase…>`, one fresh directory
each (`runtime/post-<phase>-1`), summary `runtime/post-summary.txt`. The phases are those whose code or scenario changed
after the long runs, plus a smoke run of each feature area. `r4-service`, `r4-family`, `r4-extensions`, `r4-crash` and
`politics-cycle` had not run during this audit before.

| Phase | Companions | Result | Seconds |
| --- | --- | --- | --- |
| `initial` | none | PASS | 30 |
| `politics` | none | PASS, 3 boots | 96 |
| `capitals` | MCA, Architectury, MCA Capitals 1.1.1 | PASS | 19 |
| `r3` | Recruits 1.15.2 | PASS, 5 boots | 95 |
| `military` | Recruits 1.15.2 | PASS, 2 boots (declaration, muster through the task screens, restart) | 42 |
| `r4-service` | full pack set | PASS | 25 |
| `r4-family` | full pack set | PASS (private introduction, invalidation, unloaded-spouse refusal) | 30 |
| `r4-extensions` | full pack set | PASS | 19 |
| `r4-crash` | full pack set | PASS, crash and restart | 45 |
| `politics-cycle` | MCA, Architectury, Townstead 0.7.6, Patchouli | PASS: a full 24,000-tick Townstead work and rest cycle; office queries answer throughout, profession and shifts unchanged | 1,231 |

"Full pack set": Recruits, MCA, Architectury, MCA Quests 1.7.1, MCA: Crime 0.7.5, Townstead 0.7.6, Patchouli, GeckoLib.

## Not executed

These are not passed; they were not run:

- Five new-player sessions with real people (H05).
- Narrator audio with a working system voice: this machine's client has no `flite` library, so the narrator cannot speak
  (H05). What the narrator would read is checked as text by the Book client test (see Client checks); nobody listened to it.
- Two-client, full-pack runs of the 0.3.0 features (dynasty, charters, development) (H05, A5, D7).
- A full-pack client walkthrough from founding to a memorial (A5).
- GUI-scale, shader and texture-memory profiling of the new blocks (B4).
- The D performance matrix at 10/50/200 settlements and 1/10/50 works (D6, D7).
- MCA Capitals 1.2.1–1.3.7 in a packaged run: they cannot load with the pack's MCA 7.6.26.
- The Ledger's places sketch and direction in a client tour: the tour does not select a settlement.
