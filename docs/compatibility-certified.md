# Certified compatibility

What MCA: Kingdoms 0.3.0 was tested with, by exact jar. A combination not listed under **Certified tuples** is not
certified, even when the metadata ranges in `mods.toml` allow it to load. The machine-readable source is
`tools/test/companions.json` (`python3 -I tools/test/verify_companions.py --mod <jar>` checks a jar against it); the
start-up log and `/kingdoms integrations` print the same matrix for a running server, with the capabilities its probes
found.

## Platform

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.23 |
| Java | OpenJDK 17.0.20.1 (the version every check in [Testing 0.3.0](testing/0.3.0/README.md) ran on) |
| NeoForge | Not supported; no port is part of this release. |

## Certified tuples

| Tuple | Contents | Evidence |
| --- | --- | --- |
| Pack | MCA Reborn 7.6.26, Architectury 9.2.14, Townstead 0.7.6, Recruits 1.15.2, MCA Quests 1.7.1 (local contract build), MCA: Crime 0.7.5, Patchouli 1.20.1-85, GeckoLib 4.8.4; MCA Reputation 0.6.1 (local build) in the `migration` phase | Packaged dedicated-server phases `r2`, `r3`, `r3-civilian`, `r4`, `r4-fence`, `military`, `migration` ([Testing 0.3.0](testing/0.3.0/README.md)). MCA Conversations 1.8.0 is validated by contract inspection only in this release. |
| MCA Capitals | MCA Reborn 7.6.26, Architectury 9.2.14, MCA Capitals 1.1.1 | Packaged phase `capitals`: mapping, crown read-only, civic work continuing, release. |
| Kingdoms alone | No companion | Packaged phases `initial`, `politics`, `split-village`; GameTests. |
| Recruits: Living Villages | Recruits 1.15.2, Recruits: Living Villages 1.1.0 (the audited jar) | Packaged phases `lv`, `lv-restart`, `lv-absent`, `lv-reinstalled`, `lv-future` and `lv-unaudited` (`build/lv-evidence/runtime/lv-13`). See [Living Villages integration](Living-Villages-Integration.md). |
| Living Villages with MCA | The above with MCA Reborn 7.6.26 and Architectury 9.2.14 | Packaged phase `lv-mca`: new residents are MCA villagers made by MCA's factory and survive the provider folding the village (`build/lv-evidence/runtime/lv-mca-2`). |

## Validated jars

| Mod | File | SHA-256 | Source |
| --- | --- | --- | --- |
| mcaquests | `mcaquests-1.7.1.jar` | `b11c9a4d2dab45ae398a4d369f0a98d0a21577a3cc60a532799108f982f6a4c0` | local MCAQuests tree before its 1.7.2 bump (uncommitted) |
| mcareputation | `mcareputation-0.6.1.jar` | `c381709e2f4a6b10ad4acf795ad4b4e2c6e89a0097646873bd84caf8cafc2642` | local MCAReputation tree (earlier adoption rule that forgot a pending old cursor) |
| mcaconversations | `mcaconversations-1.8.0.jar` | `361b0faac7b7ac89c67861ec3dbf0d0da3a2d7231e8163ddda9112d2ce1c3cba` | pack copy |
| mcacrime | `mcacrime-0.7.5.jar` | `aed00eb531cfb1ebdb7f1b63ee62cd4d18bc9e9de14d6d7889e39a004e6e284b` | pack copy |
| recruits | `recruits-1.20.1-1.15.2.jar` | `4b53c1b752e886ba10985aad2df30d668971810230eb77867e07428a21d1af7f` | pack copy |
| townstead | `townstead-0.7.6+1.20.1.jar` | `78ab52d551dfaf2ca3366da565ccb501918f73affe7b48f339ce63b17de1513e` | pack copy |
| mca | `minecraft-comes-alive-7.6.26+1.20.1-universal.jar` | `5dc3ee1f2f74221b3b7b73456451bf9a3f0cc87c8378a64233680f051dbc3d19` | pack copy |
| mca | `architectury-9.2.14-forge.jar` | `218b471d0b8a1f6cda14cfc1beb9eeb0df54304500acc6c5613d9b88ec65d9af` | pack copy (MCA dependency) |
| mcacapitals | `mcacapitals-1.1.1.jar` | `f031c719736ad7a8eb6cfa6a18064988b570515d4ca5e8432ecc07b2c75e09dc` | Modrinth release (SHA-512 checked against Modrinth) |
| mcacapitals | `mcacapitals-1.3.7.jar` | `5f160bd7d56df3c3b0f37457dd43ebcd72d5f4e745fcd93f0ab5af771e3f704c` | Modrinth release (SHA-512 checked against Modrinth) |
| recruitsvillages | `recruitsvillages-1.1.0.jar` | `55a7da1802360bbc18337ad190b826a1a2292f3038c623ab09f926bb0746dda8` | Modrinth release (SHA-1 and SHA-256 checked against Modrinth) |

## Known unsupported or limited

| Combination | Behaviour |
| --- | --- |
| MCA Capitals 1.2.1 to 1.3.7 with MCA Reborn 7.6.26 | Forge refuses to load Capitals: those releases require MCA 7.7.0-beta.2 or later (1.3.4 to 1.3.7 require 7.7.1-alpha.2 or later). The 1.3.7 read API was checked by class inspection only. |
| MCA Capitals, any version | Read-only bridge: Capitals is never written. Delegating crown actions to Capitals is blocked upstream (no public mutation API; see [the proposal](Capitals-Upstream-API-Proposal.md)). |
| Recruits other than 1.15.2 | Loads, but the military bridge stays off (`/kingdoms integrations` says why). |
| Recruits: Living Villages other than the audited 1.1.0 jar | Loads, but the integration's hooks are not applied: it stays off and its records read-only (`/kingdoms livingvillages status` and Server health say why). A world that already has protected residents refuses to start unless `livingVillages.allowUnprotectedResidents` is set. |
| Recruits: Living Villages in a development (deobfuscated) run | Resident preservation is unavailable, because its guards name the provider's calls by their production names; the packaged phases cover it. |
| MCA Quests without the institutional contract | Paid workshop jobs are refused before any offer; ordinary guild deeds work. |
| MCA: Crime without its jurisdiction API (`JurisdictionPolicyApi`, present in 0.7.5) | Jurisdiction policy stays unavailable and is logged so; institutional legality checks still work. |
| JEI, Map Atlases | Not integrated: no plugin-free JEI API exists to hand a recipe to it, and Map Atlases exposes no marker API. The Book's recipes and the Ledger's places sketch work without them. |
