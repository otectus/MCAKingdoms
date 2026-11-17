# Waystones integration

Applies to MCA: Kingdoms 0.3.1. Audience: server operators and pack makers.

With [Waystones](https://www.curseforge.com/minecraft/mc-mods/waystones) installed, a normal waystone inside a recognized
settlement takes the settlement's name the first time it needs a name. Players do not need a command, an identifier or a
rename for each new stone. Naming happens once: MCA: Kingdoms never renames an established stone by itself, and every
name a player chooses is kept. It changes nothing else about waystones: travel, discovery, owners, the global flag,
activations, permissions, costs, cooldowns and Waystones' own menus all stay Waystones'.

Everything here is optional. Without Waystones (or Balm), MCA: Kingdoms loads and plays as before. Players' explanations
are in the Book of Kingdoms under *Waystones*.

## Supported versions

| Component | Supported | How it is checked |
| --- | --- | --- |
| Waystones (Forge) | `14.1.21` | Exact version, read from the loaded mod list at common setup. |
| Balm (Forge) | `7.3.44` | Exact version, as above. |
| Waystones edit observer | the `waystones-forge-1.20.1-14.1.21.jar` release, SHA-256 `fd6fe3c6…be3c80` | `mixin/WaystonesGate` applies the observer only to that exact jar. |

The tested Balm jar is `balm-forge-1.20.1-7.3.44.jar`, SHA-256 `ad11d968…6aebd3`. `mods.toml` accepts Waystones
`[14.1.21,)` and Balm `[7.3.44,)`, so newer releases launch. With any other pair, naming stays off, no stone is touched,
and Server health and `/kingdoms waystones status` say why. Waystones ships the events used here in its public API
package; the few non-API members (the waystone manager, its update broadcast, its edit permission check, a proxy's backing
record and the edit message's stone id) are resolved by exact name and signature in `compat/waystones/WaystonesAccess`
and `WaystonesIntegration`. A missing member keeps the integration off.

## What gets named

| Situation | What happens |
| --- | --- |
| A generated waystone (in a village or the wild) is first activated inside a settlement | It is named after the settlement before Waystones shows the activation message and the destination list. |
| A newly placed, unnamed waystone inside a settlement | It is named before its settings screen opens; the player can replace the name right there. |
| A waystone outside every recognized settlement | Waystones' own behavior. No nearest settlement is guessed. |
| A second stone would get the same name | The first keeps it; the next gets the smallest free number, such as `Oakfield (2)`. Names are compared ignoring case, against the current name of every registered waystone and every name this integration gave and still manages. |
| A waystone that already has a name, including one picked up and placed again | Its name is kept. A name that looks generated is not treated as safe to replace. |
| A player renames a stone | The player's name wins for good. It is replaced only if someone with Waystones' edit permission asks for the settlement name again. |
| The settlement is renamed or merged later | Established stones keep their names. There is no follow mode. |
| Sharestones, warp plates and portstones | Unchanged. |

The settlement's display name is used as written, never translated per client. Control characters are removed,
whitespace is collapsed, and the name is shortened to fit Waystones' 255-character limit with room for a number. A
settlement whose name is empty after that leaves Waystones' default in place.

### A settlement recognized a little later

A stone placed where a settlement is about to be recognized (a scan is queued there, or village beds, bells or
workstations stand nearby) waits instead of being given up. The integration asks for a scan of the loaded area through
the ordinary discovery queue; it never loads a chunk. When the settlement is recognized, the waiting stone is named if it
is still unnamed and nobody edited it. A stone waits at most `waystones.pendingTicks` (five minutes by default); after
that it is left as Waystones leaves it. At most 256 stones wait at once, and a stone that finds the list full is left
as Waystones leaves it straight away.

### Players' renames

On the tested Waystones jar, an observer runs after Waystones has accepted a player's edit in its settings screen
(after Waystones' own permission and distance checks). It records that stone as the player's, even when the player
submitted the same text. On any other jar the observer is off, and the integration never renames a stone that already
has a name, so a rename it cannot see is still never overwritten.

### Saves and restarts

Each name given is recorded before Waystones receives it. When the world is saved, every recorded name the stone still
carries is confirmed. A stone that by then carries another name was renamed by something the integration did not see,
and is never touched again. A named stone that Waystones no longer has (broken without silk touch) gives its name back
for the next stone. A silk-touched stone placed again is the same stone, with the same name; nothing is assigned again.

After a crash, names the previous run gave but no save confirmed are settled from the stones as Waystones loaded them:

- the recorded name: it was saved, and is confirmed;
- no name: the name never reached Waystones' save, so the same name and number are given again;
- no such stone in Waystones' records: nothing is renamed, and the name is free again;
- any other name: it cannot be attributed, so it is left as it is and reported as **unresolved**.

A restart never picks a new number for a stone, and never renames a stone twice.

## For players

Two Kingdom Tasks, under **Settlements**:

- **Why this waystone has its name** (read-only): explains the waystone within 8 blocks: whether it carries its
  settlement's name, why it keeps another, and how to change it.
- **Name this waystone after the settlement**: for a waystone within 8 blocks that Waystones lets the player edit. It
  replaces the current name with the settlement's (numbered if needed), because the player asked. The owner,
  activations and global flag do not change.

## For operators

- **Server health** (Kingdom Tasks) shows a Waystones line: the installed versions, whether naming is on, whether
  players' edits are observed, and how many stones are named, confirmed, waiting, kept by players and unresolved.
- `/kingdoms waystones status` (permission level 2) gives the same state with the reason when the integration is off.
- `/kingdoms waystones backfill` names stones in an existing world. Without `apply` it is a dry run: it counts every
  registered waystone by reason and changes nothing. With `apply` it names at most 64 eligible stones per run; run it
  again for more. Only **unnamed** normal stones inside a settlement are eligible. A stone that already has a name is
  never eligible, because an unknown historical name is treated as a player's.

The records live in `world/data/mcakingdoms_waystones.dat`: one entry per stone the integration named or is waiting for,
and per stone a player renamed. A name is set on Waystones' own record of the stone, Waystones' manager is marked for
saving, and players get Waystones' usual update (during activation, Waystones sends it itself). A damaged file, or one written by a newer version, is kept read-only and unchanged:
nothing is named while it is, and each stone keeps its name. Bounds and retention are in
[Capacity and maintenance](Capacity-and-Maintenance.md).

## Settings

`waystones.settlementNames` turns naming on or off, live; `waystones.pendingTicks` sets how long a new stone waits. See
the [configuration reference](Configuration.md#031-additions-waystones).

## Removing Waystones or MCA: Kingdoms

- Without Waystones, the naming store is left untouched. Install Waystones again and it is used as before.
- Without MCA: Kingdoms, every waystone keeps the name it has. Nothing in Waystones' records refers to MCA: Kingdoms.
- MCA: Kingdoms 0.3.0 does not know the naming store and leaves it on disk unchanged; 0.3.1 reads it again.
