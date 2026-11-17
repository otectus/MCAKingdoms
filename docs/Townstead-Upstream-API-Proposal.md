# Proposal: Townstead contracts for a full civic partnership

**From:** MCA: Kingdoms (otectus). **For:** the Townstead maintainer (AetherianArtificer). **Status:** draft for
discussion, not sent. Sending it, and any issue or pull request, is the project owner's decision. Townstead is
third-party: nothing here is a change MCA: Kingdoms makes or ships.

MCA: Kingdoms 0.3.0 reads Townstead 0.8.0 through its public `api.v1` and never writes Townstead
([integration](Townstead-0.8.0-Integration.md)). That covers village life, buildings, careers, work, news and reactions.
It cannot make the two mods' governments one system: when both keep a local government for the same village, Kingdoms
can only report the conflict and let an operator decide on the Kingdoms side, while Townstead's council keeps running.
This page lists the Townstead contracts that would close that gap and enable the deeper automation, with the evidence
from Townstead's tagged 0.8.0 source (commit `c0c1634c498c0cba192bd1391d1141880c8223fb`; paths are below
`src/main/java/com/aetherianartificer/townstead/`).

## Principles

- Townstead stays the only writer of its records; every operation runs Townstead's own permission and state checks.
- Every contract lives in `api.v1` (or a later generation), names no internal class, and is closeable per server.
- Mutations carry a caller's request id and an expected revision, return a typed result (applied, duplicate, refused
  with a stable reason, unknown), and are idempotent: the same request id never applies twice.
- Events are posted once, after the commit, with an id a consumer can deduplicate.
- Each contract is optional on both sides. Kingdoms keeps its current behaviour wherever a contract is absent.

## 1. External civic provider (government interoperability)

**Today.** `politics/charter/CivicProviders.java:9-50` is Townstead's provider registry. Its `Provider` interface
(lines 10-16) returns the internal network DTO `CharterSnapshotS2CPayload.Civic`, and the only registration is MCA
Capitals', in a static initializer (line 18). `ownsGovernment` is a single yes/no for a whole settlement; MCA Capitals'
implementation (`compat/mcacapitals/CapitalsCivicProvider.java:46-61`) records the answer in
`CharterSavedData.observeGovernment`. `politics/state/PoliticalSavedData.java:163-177` then marks the **whole faction**
external, permanently, as soon as any one of its settlements is owned (`markExternalGovernment`).

**Proposed.** A public, closeable registration:

```java
public interface ExternalGovernments {
    Registration register(MinecraftServer server, ExternalGovernmentProvider provider);   // closed at server stop
}

public interface ExternalGovernmentProvider {
    String id();                                                       // "mcakingdoms"
    /** Per settlement and per domain; empty when the provider has no opinion. */
    Optional<Claim> claim(MinecraftServer server, VillageId village, Domain domain);
    Optional<ExternalGovernmentView> view(MinecraftServer server, VillageId village);  // neutral record, no DTOs
}

public enum Domain { CROWN, LOCAL_GOVERNMENT, NAMING, HERALDRY }
public record Claim(String provider, Domain domain, State state, long revision) { }   // ACTIVE, UNAVAILABLE, RELEASED
```

**Minimum acceptance.** Per-domain answers (a crown owned by MCA Capitals and civic offices owned by Kingdoms in the
same village must both be expressible); explicit conflict and removal behaviour (a provider that disappears leaves its
claims `UNAVAILABLE` with provenance, never silently native again); a multi-settlement faction with mixed ownership is
either supported per settlement or refused as a conflict, never flipped as a whole.

## 2. Provider-aware native political mutations

**Today.** Several native paths create or fill a government without asking any provider:

- `politics/state/PoliticalVillageBootstrap.java:145-155` creates a default council faction and runs
  `FactionUpkeep.run` for it; no external-government check is made in this path.
- `politics/state/FactionUpkeep.java:65` fills generated offices on every upkeep, and `fill` (lines 73-103) seats
  citizens through `FactionBonds.form` (`politics/state/FactionBonds.java:105`), which has no provider check either.
- Only the founding bell (`politics/charter/CharterBellService.java:303`), charter drafting
  (`politics/charter/CharterDrafts.java:48`), memberships (`CharterMemberships.java:75`), accords
  (`CharterAccords.java:53`) and village renames (`politics/charter/VillageRenames.java:17-23`) consult
  `ownsGovernment` or `externalGovernment`.

**Proposed.** Every path that founds, bootstraps, fills, seats, renames, changes legitimacy or runs succession checks
the domain's claim first and refuses (or stays dormant) in an externally owned scope.

**Minimum acceptance.** With a Kingdoms claim on `LOCAL_GOVERNMENT`, no upkeep, bootstrap, charter, seat, rename,
legitimacy or succession path can maintain a rival council in that settlement; with the claim released, Townstead's
council resumes from its own records.

## 3. External government snapshot

**Today.** `api/impl/v1/PoliticsImpl.java:189-193` reports a governing provider id on the governance snapshot, taken from
the first settlement of the faction that has one; offices and holders shown beside it are still Townstead's own.

**Proposed.** `politics().externalGovernment(server, VillageId)` returning the provider's neutral view (ruler and offices
as the provider reports them, availability, provenance), so Townstead's screens and third-party mods never label an
external ruler as a native Townstead value, and keep provenance after the provider is removed.

## 4. Supported rename transaction

**Today.** Renames are allowed or refused by `VillageRenames.allowed` (lines 17-23) at the Blueprint; there is no API to
request a rename, and a mod could only edit MCA's village record directly.

**Proposed.** `villages().rename(server, VillageId, String name, RequestId, expectedRevision)` that checks Townstead's
authority and locks, keeps the previous name as an alias, and carries the request's source id so the requesting mod does
not echo its own change back. MCA: Kingdoms would then keep a settlement's civic name and its village name in step.

## 5. Heraldry and livery provider

**Today.** `politics/heraldry/GuardHeraldry.java:22-71` paints a guard's plain shield with the nearest proclaimed arms
(settlement, then faction, then parent factions); an undecorated shield is one without block-entity data (lines 62-70).
There is no hook for another mod's design.

**Proposed.** A provider consulted after Townstead's own arms, with an explicit precedence (personal and local first),
a neutral read of the arms in force, and a safe fallback when a design is missing. MCA: Kingdoms' current fallback paints
only plain shields through an equipment event and steps aside when Townstead's faction has arms; a provider hook would
make the order explicit and cover Townstead's other displays.

## 6. UI extension and navigation seam

**Today.** MCA: Kingdoms adds its civic header and a Kingdom Tasks button to the Blueprint screen through Forge's screen
events, drawing over the screen's plaque area; there is no stable slot or navigation API.

**Proposed.** A small client API for a named header slot and navigation entries on the Blueprint and Charter screens,
without global screen replacement or private-field access.

## 7. Idempotent external Chronicle events

**Today.** `api/impl/v1/ChroniclesImpl.java:154-167`: `emit` needs a living actor (line 157), adds the trigger's counter
(line 159) **before** looking for a matching template (lines 160-161), and takes no source id. An emission with no
matching template therefore returns empty after counting, so a caller cannot retry safely, and actorless civic events
(a treaty concluded remotely, an approval by an offline official) cannot be told at all.

**Proposed.** `chronicles().emit(server, ExternalEvent)` where `ExternalEvent` carries a source event id, an explicit
scope (village), optional participants, magnitude and parameters; the result is `RECORDED`, `DUPLICATE`, `NO_TEMPLATE`
or `UNAVAILABLE`, and the counter moves only with a recorded story. MCA: Kingdoms currently claims each event before
emitting and never retries, and tells only stories with a player present.

## 8. Stable order and inventory transactions

**Today.** `api/impl/v1/WorkImpl.java:94-113`: `countItem` and `countTag` read stores by numeric village id against the
level they are given, ignoring `VillageId.dimension()`, and report a failure as 0. Work orders are readable but have no
stable id for an external fulfilment.

**Proposed.** Stable order ids; reservations; an authorized delivery that returns an exact committed receipt (items,
counts, order, request id). Counts alone cannot pay or reserve anything, so MCA: Kingdoms shows them as advisory only
(and checks the dimension itself) until receipts exist.

## 9. Idempotent career reward

**Proposed.** `professions().reward(player, profession, xp or skill, RequestId)` returning the actually applied result;
the same originating receipt returns `DUPLICATE` without another award. MCA: Kingdoms makes no career award until then.

## 10. Needs helper and aggregation corrections

**Today.** `api/v1/model/NeedsSnapshot.java:43-55` (`primaryNeed`) and `57-62` (`inCrisis`) include needs whose
`enabled` flag is false. `village/ResidentRegister.java:207-274` (`summary`) does skip disabled levels (line 248), but
bands each need by its mean fraction (line 270), which for body temperature lets heat and cold cancel out, and counts
every register entry still filed under the village (lines 234-244), so `known` can exceed the current roll.

**Proposed.** Skip disabled needs in the helpers; report thermal distress as two sides (too hot, too cold); count only
residents on the current roll, with live home changes reflected. MCA: Kingdoms applies all three corrections itself
today (it filters, counts crisis per side, and caps coverage with an "inconsistent counts" flag).

## Fixtures for the political contract

To accept contracts 1-3, both mods' packaged jars should run together with: a native Townstead council; an adopted
Kingdoms government; an MCA Capitals crown with Kingdoms civic offices; an unavailable provider; and a multi-settlement
Townstead faction with mixed ownership, either supported per settlement or refused as a conflict.
