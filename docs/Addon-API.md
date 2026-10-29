# Addon API guide

MCA: Kingdoms publishes a classes-only API jar, `mcakingdoms-<version>-api.jar`, for addons. Compile against it and run with the main `mcakingdoms-<version>.jar` installed; never put the API jar in a game's `mods` folder. The API contains only `dev.otectus.mcakingdoms.api` classes and no MCA, Townstead, Recruits or other provider types (`./gradlew check` verifies this). The Javadoc in that package is authoritative for signatures and per-method contracts; this guide explains how the pieces fit.

A [compile-checked example](../examples/addon/README.md) shows everything below; `./gradlew check` compiles it against the API jar alone.

## Setting up

In the addon's `build.gradle` (ForgeGradle 6, Forge 47.x, Java 17):

```groovy
dependencies {
    minecraft "net.minecraftforge:forge:1.20.1-<forge version>"
    compileOnly files("libs/mcakingdoms-<version>-api.jar")
}
```

Use the Forge version and MCA: Kingdoms version you target (this build's are `forge_version` and `mod_version` in its `gradle.properties`), and the API jar from the same build as the main jar.

For development runs, put the main `mcakingdoms-<version>.jar` in `run/mods`. Declare the dependency in your `mods.toml` (`mandatory = false` if your addon also works without MCA: Kingdoms), and keep every class that mentions an MCA: Kingdoms type out of code paths that run when the mod is absent — the example registers its listener only after `ModList.get().isLoaded("mcakingdoms")`.

## Lifetime and threading

- `McaKingdomsApi.get(server)` returns the server's `KingdomsService`. MCA: Kingdoms attaches it while the server starts; it is ready from `ServerStartedEvent` (handlers of `ServerStartingEvent` may run before it is attached) and is detached after the server stops. Before then, or for a server it never attached to, `get` throws `IllegalStateException`. Do not keep a service past its server.
- Call every service method on that server's thread. Forge runs server events there; from any other thread, hand the work back with `server.execute(...)`. Off-thread calls throw `IllegalStateException`.
- `registerSettlementDetector`, `registerKingdomResolver`, `registerCivicEvidenceProvider` and `registerVillageFootprintSource` return a `Registration`. Close it (on the server thread) when your addon is done, at the latest in `ServerStoppingEvent`. One owner id may hold one registration of each kind; registrations do not survive a restart.

The other services follow the same pattern: `KingdomsPoliticsApi.get`, `KingdomsFactionsApi.get`, `OrganizationApi.get` and `KingdomsTownsteadApi.get` return services attached at server start, while `WarfareApi.get` and `WorldContextApi.get` return `Optional` providers that may be absent. `KingdomGateApi` evaluates kingdom gates for quest and conversation integrations.

## Reading

- Views (`SettlementView`, `KingdomView`, `CivicIdentityView`, …) are immutable snapshots of the moment you asked. `revision()` grows whenever settlement records or definitions change; compare it to know when a cached view is stale.
- `getKingdom` and `getKingdoms` include kingdoms that saved settlements still reference after their definition was removed; those report `defined()` false.
- `getSettlementPage(kingdom, offset, limit)` takes a limit from 1 to 64 in a stable order. `getSettlements(kingdom)` is unbounded; prefer pages for listings.
- `findSettlement` accepts a UUID, slug, display name or historical alias, ignoring case and spacing, and refuses an ambiguous name.
- `getSettlementAt` uses saved bounds and loads nothing; `discover` examines only loaded chunks (0 to 32 chunks around a point).
- `CivicIdentityView.source()` says how the current record was made: `AUTOMATIC`, `MCA`, `ADDON`, `COMMAND`, `MIGRATION`, or, since 0.3.0, `REGISTERED` (declared by the player and verified by the server). Keep a default branch for values a later version may add; builds before 0.3.0 read a saved `REGISTERED` as `MIGRATION`.

- **Recruits: Living Villages** (0.3.0): the settlement of a bound village carries the external reference
  `recruitsvillages:village` with the Living Villages village UUID (`getSettlementByExternalRef`). Its faction is a
  *realm*, not a kingdom: `api/politics/PoliticalPartyRef` names a side of an agreement as `Kingdom(id)` or `Realm(uuid)`,
  written by `format()` as `kingdom:<id>` or `realm:<uuid>` and read back by `parse`. Same-culture realms are separate
  parties; the government API stays keyed by kingdom id, and a kingdom represents a realm only through an operator
  association. For an entity with a civic identity that stands in an integrated village, `getContext` adds
  `mcakingdoms.realm`, `mcakingdoms.realm_recognized`, `mcakingdoms.realm_occupied`, `mcakingdoms.realm_war` and, during a
  war, `mcakingdoms.realm_enemy`.

**Stored ids and merges.** A merge retires the merged record's id: from then on it resolves to the surviving record. If you store settlement ids, pass them through `resolveSettlementId` before comparing or looking them up, and update stored copies on `SettlementMergedEvent`. A stored id can also stop resolving at all; treat an empty lookup as normal.

**Visibility.** The service is a trusted server API and returns every record, including settlements a given player has not discovered and other players' civic data. It is not a player-filtered UI API: before showing anything to a player, filter it by what that player may know. MCA: Kingdoms' own screens, tasks and commands do that for their players.

## Changing

- Settlement mutations (`rename`, `setKingdom`, `reclassify`, `setLocks`, `merge`) and civic mutations (`setOrigin`, `setResidence`) apply immediately and return the new view. Repeating a call with the current values changes nothing. A refusal throws `IllegalArgumentException` and leaves the records unchanged: an unknown record, a locked name or kingdom, a name in use, a bound out of range, or a provider preflight.
- Other mods can refuse settlement changes through `SettlementMutationPreflightEvent` (it is cancelable, and runs on the server thread). A listener must only decide; mutating settlements during a preflight throws.
- `registerCandidate` returns the existing settlement when the candidate belongs to one, so it is safe to repeat.
- A `CivicEvidenceProvider` hint whose `residenceSettlement()` names a settlement for a **player** is used by Register my residence (0.3.0) as that player's home evidence, ahead of the bed check. The player must still be standing in that settlement, and only their explicit request records it: providers never move a player's residence by themselves. A provider that throws counts as no evidence.
- **Political requests** (`PoliticalService.execute`) carry a request id and the political revision you read. Keep the same request id when retrying: the same request returns the original result instead of applying twice, a reused id with different content is refused, and a stale revision is refused with "Political state changed; refresh before retrying". The actor is the authenticated player making the request, and their current permissions are checked again when it runs.

## Events are notifications

The Forge events under `dev.otectus.mcakingdoms.api.event` (settlement created, discovered, renamed, kingdom changed, merged; residents joined and left; civic identity changed; definitions reloaded) and `PoliticalCommittedEvent` and `OrganizationCommittedEvent` report changes after they are made. They are not durable receipts: an event does not prove that a save completed, that a quest reward was delivered, that a troop handoff happened, or that another listener succeeded. When your addon needs proof, read the owning record (or the provider's own receipt) instead.

## Capabilities and optional providers

Missing provider, unsupported provider, disabled feature, missing content and a negative answer are different results; the typed result objects (for example `ProgressionResult.Status`, `TownsteadCapability`, `FactionStandingResult`) keep them apart, so do not collapse them into true or false. A display lookup should never load distant chunks or entities, and dedicated servers must not load client classes: keep client-only code in client-only classes.
