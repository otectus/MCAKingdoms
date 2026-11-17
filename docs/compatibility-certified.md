# Certified compatibility

What MCA: Kingdoms 0.3.1 was tested with, by exact jar. A combination not listed under **Certified tuples** is not
certified, even when the metadata ranges in `mods.toml` allow it to load. On 10 November 2026 every tuple below was run
again on the 0.3.1 jar (`mcakingdoms-0.3.1.jar`, SHA-256 `1e5e6a7b…`; [Testing 0.3.1](testing/0.3.1/README.md)), except
where a row says its evidence is older. The machine-readable source is
`tools/test/companions.json` (`python3 -I tools/test/verify_companions.py --mod <jar>` checks a jar against it); the
start-up log and `/kingdoms integrations` print the same matrix for a running server, with the capabilities its probes
found.

## Platform

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.23 |
| Java | OpenJDK 17.0.20.1 (the version every check in [Testing 0.3.1](testing/0.3.1/README.md) ran on) |
| NeoForge | Not supported; no port is part of this release. |

## Certified tuples

| Tuple | Contents | Evidence |
| --- | --- | --- |
| Pack | MCA Reborn 7.6.26, Architectury 9.2.14, Townstead 0.7.6, Recruits 1.15.2, MCA Quests 1.7.1 (local contract build), MCA: Crime 0.7.5, Patchouli 1.20.1-85, GeckoLib 4.8.4; MCA Reputation 0.6.1 (local build) in the `migration` phase | Packaged dedicated-server phases `r2`, `r3`, `r3-civilian`, `r4`, `r4-fence`, `military`, `r4-service`, `r4-family`, `r4-extensions`, `r4-crash`, `migration` on 0.3.1 (`build/v031-evidence/runtime/<phase>-1`). MCA Conversations 1.8.0 is validated by contract inspection only. |
| MCA Capitals | MCA Reborn 7.6.26, Architectury 9.2.14, MCA Capitals 1.1.1 | Packaged phase `capitals`: mapping, crown read-only, civic work continuing, release (`build/v031-evidence/runtime/capitals-2`). |
| Kingdoms alone | No companion | Packaged phases `initial`, `politics`, `split-village` (`initial-3`, `politics-3`, `split-village-3`); GameTests. |
| Waystones 14.1.21 | Waystones 14.1.21 (Forge), Balm 7.3.44 | Packaged phases `waystones` and `waystones-restart` (`waystones-6`). Copies of the tested jar with another version and with other bytes were run as unsupported cases (`waystones-unsupported-4`, `waystones-untested-build-4`). See [Waystones integration](Waystones-Integration.md). |
| Townstead 0.8.0 | Townstead 0.8.0+1.20.1, MCA Reborn 7.7.1-beta.5+1.20.1 | Packaged phase `townstead` (first boot, restart, Townstead removed, restored) and `initial` (`townstead-ts080-3`, `initial-ts080-3`). See [Townstead 0.8.0 integration](Townstead-0.8.0-Integration.md). |
| Townstead 0.7.6 | Townstead 0.7.6+1.20.1, MCA Reborn 7.6.26, Architectury 9.2.14, Patchouli 1.20.1-85 | The same `townstead` phase on the legacy backend (`townstead-ts076-2`). |
| MCA Capitals 1.3.7 | MCA Reborn 7.7.1-beta.5, Architectury 9.2.14, MCA Capitals 1.3.7, Townstead 0.8.0 | Packaged phase `capitals`: mapping, crown read-only, founding refused while Capitals governs, release (`capitals-137-2`). |
| Living Villages with MCA 7.7.1 | Recruits 1.15.2, Recruits: Living Villages 1.1.0 (audited jar), MCA Reborn 7.7.1-beta.5 | Packaged phase `lv-mca`: new residents are MCA villagers made by MCA's factory (`lv-mca77-1`). |
| Pack on Townstead 0.8.0 | Recruits 1.15.2, MCA Reborn 7.7.1-beta.5, Architectury 9.2.14, MCA Quests 1.7.1 (local contract build), MCA: Crime 0.7.5, Townstead 0.8.0, Patchouli 1.20.1-85, GeckoLib 4.8.4 | On 0.3.1: packaged phases `r4` and `r3-civilian` (`r4-ts080-1`, `r3-civilian-ts080-1`). Older evidence, on the 0.3.0 tree with the Townstead work: `r2`, `r4-service`, `r4-family`, `r4-extensions`, `r4-crash` and `migration` (with MCA Reputation 0.6.1) in `build/ts080-evidence/runtime/reg-*-ts080-1`. |
| Pack on Townstead 0.8.0 with Waystones | The row above plus Waystones 14.1.21 and Balm 7.3.44 | Packaged phases `r4` and `initial` on 0.3.1 (`r4-full-waystones-1`, `initial-full-waystones-1`). |
| Recruits: Living Villages | Recruits 1.15.2, Recruits: Living Villages 1.1.0 (the audited jar) | Packaged phases `lv`, `lv-restart`, `lv-absent`, `lv-reinstalled`, `lv-future` and `lv-unaudited` (`lv-2`). See [Living Villages integration](Living-Villages-Integration.md). |
| Living Villages with MCA | The above with MCA Reborn 7.6.26 and Architectury 9.2.14 | Packaged phase `lv-mca`: new residents are MCA villagers made by MCA's factory and survive the provider folding the village (`lv-mca-1`). |

Run directories without a path are under `build/v031-evidence/runtime/`. Earlier evidence for the same tuples, from 0.3.0
and the Townstead and Living Villages work, stays where [Testing 0.3.0](testing/0.3.0/README.md) and the integration pages
name it.

## Validated jars

| Mod | File | SHA-256 | Source |
| --- | --- | --- | --- |
| mcaquests | `mcaquests-1.7.1.jar` | `b11c9a4d2dab45ae398a4d369f0a98d0a21577a3cc60a532799108f982f6a4c0` | local MCAQuests tree before its 1.7.2 bump (uncommitted) |
| mcareputation | `mcareputation-0.6.1.jar` | `c381709e2f4a6b10ad4acf795ad4b4e2c6e89a0097646873bd84caf8cafc2642` | local MCAReputation tree (earlier adoption rule that forgot a pending old cursor) |
| mcaconversations | `mcaconversations-1.8.0.jar` | `361b0faac7b7ac89c67861ec3dbf0d0da3a2d7231e8163ddda9112d2ce1c3cba` | pack copy |
| mcacrime | `mcacrime-0.7.5.jar` | `aed00eb531cfb1ebdb7f1b63ee62cd4d18bc9e9de14d6d7889e39a004e6e284b` | pack copy |
| recruits | `recruits-1.20.1-1.15.2.jar` | `4b53c1b752e886ba10985aad2df30d668971810230eb77867e07428a21d1af7f` | pack copy |
| townstead | `townstead-0.7.6+1.20.1.jar` | `78ab52d551dfaf2ca3366da565ccb501918f73affe7b48f339ce63b17de1513e` | pack copy (MCA 7.6.26 tuple; needs Patchouli) |
| townstead | `townstead-0.8.0+1.20.1.jar` | `bc7e292397bf769ae334a63bfcc84abbcfeb54ab65cbe50794a022acb33c1035` | Modrinth release `b8VnGFDu` (SHA-1 `b64727e8…` checked against Modrinth); the pack's `townstead-0.8.0-1.20.1.jar` is the same file |
| mca | `minecraft-comes-alive-reborn-7.7.1-beta.5+1.20.1.jar` | `0661e8d59918143f1d6c89d279dbdd7c2772b64ac51806394b2ba291c1449771` | Modrinth release `KJixRMB5` (SHA-1 `66955aac…` checked against Modrinth); the pack's `minecraft-comes-alive-7.7.1-beta.5+1.20.1-universal.jar` is the same file. Townstead 0.8.0 tuple; MCA 7.7.1 needs no Architectury |
| mca | `minecraft-comes-alive-7.6.26+1.20.1-universal.jar` | `5dc3ee1f2f74221b3b7b73456451bf9a3f0cc87c8378a64233680f051dbc3d19` | pack copy |
| mca | `architectury-9.2.14-forge.jar` | `218b471d0b8a1f6cda14cfc1beb9eeb0df54304500acc6c5613d9b88ec65d9af` | pack copy (MCA dependency) |
| mcacapitals | `mcacapitals-1.1.1.jar` | `f031c719736ad7a8eb6cfa6a18064988b570515d4ca5e8432ecc07b2c75e09dc` | Modrinth release (SHA-512 checked against Modrinth) |
| mcacapitals | `mcacapitals-1.3.7.jar` | `5f160bd7d56df3c3b0f37457dd43ebcd72d5f4e745fcd93f0ab5af771e3f704c` | Modrinth release (SHA-512 checked against Modrinth) |
| recruitsvillages | `recruitsvillages-1.1.0.jar` | `55a7da1802360bbc18337ad190b826a1a2292f3038c623ab09f926bb0746dda8` | Modrinth release (SHA-1 and SHA-256 checked against Modrinth) |
| waystones | `waystones-forge-1.20.1-14.1.21.jar` | `fd6fe3c645328ea7be5bc6723d02c881e34650e0cbd4527ba59e42fa4cbe3c80` | pack copy of the public release; contract read from its class files |
| balm | `balm-forge-1.20.1-7.3.44.jar` | `ad11d968770bb1ce73377626b6f1dcd4ae6f8be1ef6f37cee9b872b8176aebd3` | pack copy of the public release |

## Known unsupported or limited

| Combination | Behaviour |
| --- | --- |
| MCA Capitals 1.2.1 to 1.3.7 with MCA Reborn 7.6.26 | Forge refuses to load Capitals: those releases require MCA 7.7.0-beta.2 or later (1.3.4 to 1.3.7 require 7.7.1-alpha.2 or later). Capitals 1.3.7 is certified with MCA 7.7.1-beta.5 instead (tuple above). |
| Townstead 0.8.0 with MCA Reborn 7.6.26 | Forge refuses to load Townstead: its metadata requires exactly MCA `7.7.1-beta.5+1.20.1`. Recorded as a clean loader refusal, not a tested tuple. |
| Townstead, any version | Read only: Townstead is never written. Political delegation is unavailable, because Townstead 0.8.0 publishes no contract for an external government; conflicts are reported and decided by operators on the MCA: Kingdoms side ([integration](Townstead-0.8.0-Integration.md), [proposal](Townstead-Upstream-API-Proposal.md)). |
| Townstead other than 0.7.6 and 0.8.0 | Loads within `[0.7.6,0.9)`; each capability is probed member by member, an unknown `api.v1` generation is not used, and `/kingdoms compat townstead status` says what bound. |
| MCA Capitals, any version | Read-only bridge: Capitals is never written. Delegating crown actions to Capitals is blocked upstream (no public mutation API; see [the proposal](Capitals-Upstream-API-Proposal.md)). |
| Recruits other than 1.15.2 | Loads, but the military bridge stays off (`/kingdoms integrations` says why). |
| Waystones other than 14.1.21 with Balm 7.3.44 | Loads; settlement names stay off and every waystone keeps Waystones' own name. Server health and `/kingdoms waystones status` say why. Packaged with a copy of the tested jar reporting 14.1.99. |
| Waystones 14.1.21 with other bytes than the tested jar | Naming works, but players' edits in Waystones' screen are not observed, so a stone with a name is never renamed by the integration. Packaged with a copy of the tested jar with one file added. |
| Recruits: Living Villages other than the audited 1.1.0 jar | Loads, but the integration's hooks are not applied: it stays off and its records read-only (`/kingdoms livingvillages status` and Server health say why). A world that already has protected residents refuses to start unless `livingVillages.allowUnprotectedResidents` is set. |
| Recruits: Living Villages in a development (deobfuscated) run | Resident preservation is unavailable, because its guards name the provider's calls by their production names; the packaged phases cover it. |
| MCA Quests without the institutional contract | Paid workshop jobs are refused before any offer; ordinary guild deeds work. |
| MCA: Crime without its jurisdiction API (`JurisdictionPolicyApi`, present in 0.7.5) | Jurisdiction policy stays unavailable and is logged so; institutional legality checks still work. |
| JEI, Map Atlases | Not integrated: no plugin-free JEI API exists to hand a recipe to it, and Map Atlases exposes no marker API. The Book's recipes and the Ledger's places sketch work without them. |
