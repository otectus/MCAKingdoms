# Configuration presets

Two example configurations for modpacks. They are files to copy by hand: MCA: Kingdoms never applies them, never changes a value you set, and never opts a world into anything. Every key not listed in [Configuration](../Configuration.md) as changed here keeps its shipped default.

To use one: stop the server (or edit while it runs; Forge reloads a saved file), back up `config/`, then copy the preset's files over the matching files in `config/`. Forge refreshes the comment text on the next start; the values stay as copied. Check the result in **Server health** (Kingdom Tasks, operators) or `/kingdoms health`.

## Peaceful pack — `peaceful/`

For packs without armies, or with Recruits installed but not meant for conquest.

| File | Change from the defaults |
| --- | --- |
| `mcakingdoms-warfare-common.toml` | `militaryActions = false`: no campaigns, settlement control agreements or recruit deployments. `civilianContracts`, `worldContext`, `targetProtection` and `protectCapitals` stay on, so civilian recovery work, world sites and routes keep working and recruits still may not attack villagers or allies. |
| `mcakingdoms-factions-common.toml` | None: synchronization stays `SHADOW` (observe only). |

Existing campaigns, accords and deployments stay readable. Turning the whole file's `enabled` off instead would also stop civilian contracts and world context, which is a different choice.

## Warfare pack — `warfare/`

For packs built around Recruits. Military actions need **Recruits exactly 1.15.2** on the server and every client; with another version they stay unavailable and Server health says why.

| File | Change from the defaults |
| --- | --- |
| `mcakingdoms-warfare-common.toml` | None; the defaults already enable every warfare feature. The file is the full list of knobs a pack may want to tune: `campaignNoticeTicks` (warning before a campaign starts), `campaignDurationTicks`, `mobilizationTicks`, `unitsPerCommander`, and the protections `protectCapitals` and `requireDefenderOnline`. |
| `mcakingdoms-factions-common.toml` | None: synchronization stays `SHADOW`. |

## In both presets

- Kingdom standing synchronization starts in `SHADOW`: MCA Reputation changes are read and what they would do is recorded, but standing does not change. Review `/kingdoms faction diagnostics` before choosing `MCA_TO_FACTION` or `BIDIRECTIONAL_SEMANTIC` (MCA Reputation 0.6.1 or later).
- World evolution and world events stay off. No configuration file turns them on: an operator opts each world in with **Configure world evolution** in Kingdom Tasks, and world events are a second, separate choice on that form.
- Resident founding (`politics.openFounding`) and player residence registration (`civicIdentity.playerRegistration`) stay on. A player records their civic residence with Register my residence (their bed-set respawn point inside the settlement, while standing there); an operator or an addon can also record one.
