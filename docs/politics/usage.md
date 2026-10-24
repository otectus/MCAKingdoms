# Courts, charters and concords

Open the Village Ledger, select a settlement, and choose **Kingdom**. Overview, Council, Agreements, Petitions, Institutions and Honors are paginated server-authorized views. Select a record before opening Actions to prefill its target. The candidate button selects yourself or the person you were looking at when opening the ledger. For an institution, look at a location in the existing building before opening the ledger. Changes require a current revision; refresh after a conflict.

Political choices come from datapacks. Disabled actions explain missing authority or unavailable integration. A government remains unorganized until explicitly founded; opening the ledger or possessing it grants no authority.

## Founding and offices

An operator can create identity records using existing `/kingdoms village` commands, then constitute a government:

```text
/kingdoms politics bootstrap mcakingdoms:serenum Bellmeadow mcakingdoms:serenum_charter PlayerName
/kingdoms politics appoint mcakingdoms:serenum mcakingdoms:keeper_of_records <entity selector>
/kingdoms politics inspect mcakingdoms:serenum council
```

Settlement arguments accept UUIDs, slugs, names and quoted names containing spaces. Office appointment accepts a player or an eligible, loaded adult NPC with confirmed civic residence. Vanilla adult villagers work without optional mods; the selected Townstead tuple supplies provider life-stage evidence for MCA villagers. Unknown adulthood blocks appointment.

An office adds political information and permissions without renaming an NPC or changing profession, home, family, schedule, needs, clothing or AI. The default limit is two offices per person per government. The leader delegates permissions explicitly:

```text
/kingdoms politics delegate mcakingdoms:serenum PlayerName RATIFY
/kingdoms politics revoke mcakingdoms:serenum PlayerName
/kingdoms politics seat mcakingdoms:serenum ReplacementSettlement
```

Mandates are kingdom-scoped and revocable. Scoped office appointments accept an additional settlement argument. A local steward can recognize institutions only in that settlement. Only the leader can create delegated mandates; delegation itself cannot be redelegated. Operators can initialize a government or repair vacant leadership, but cannot use operator status alone to ratify treaties.

Move the seat before reassigning a capital to another kingdom. Merging a seat follows the canonical settlement redirect. If both settlements have active institution charters, suspend the source charters explicitly first. Conflicting holders of the same local office must also be resolved before merging. Approval and revalidation recheck the settlement’s current kingdom. The `suspend_recognition` and `revalidate` actions retain the original institution identity. Revalidation never silently chooses a nearby replacement.

## Petitions, honors and agreements

The ledger supports institution recognition, honor nominations and diplomatic introduction petitions. Review, approval and rejection require a reason and an independent approver; the requester or award nominee cannot approve their own petition. Institution approval rechecks the original provider building. An honor nomination produces a visibly discretionary award, not fabricated proof of completed work. Commission sponsorship remains disabled without verified provider completion receipts.

Agreement types include diplomatic recognition, hospitality, scholarly exchange, craft/market charters and civic aid. The latter four require active diplomatic recognition. Each proposal freezes its terms and duration. Both governments must sign the same terms. Authority is checked for each signature, including the first signatory's authority when the counterpart signs. Offline signatories do not need to keep an entity loaded.

```text
/kingdoms politics propose mcakingdoms:serenum mcakingdoms:lunari mcakingdoms:diplomatic_recognition Mutual diplomatic recognition
/kingdoms politics inspect mcakingdoms:serenum agreements
/kingdoms politics sign mcakingdoms:serenum <agreement UUID> <terms hash>
/kingdoms politics sign mcakingdoms:lunari <agreement UUID> <same terms hash>
```

The ledger carries the hash automatically after selecting the proposal. Command inspection prints it for command users. Unratified proposals, including withdrawn/declined proposals, remain private to authorized parties. Ratified agreements become public. Timers use simulation ticks: sleep, `/time set`, and calendar changes do not skip their deadlines. A bounded maintenance pass persists expirations; queries and signatures enforce the deadline immediately.

Agreements expose political terms and opportunity entries. They do not create or remove items, reserve beds, bypass hunger, grant combat immunity, or run quests. Released Townstead 0.7.6 has no Chronicles classes; the inspected MCA Quests completion event lacks an instance/receipt identity. Consequently neither automatic narrative ingestion nor commission rewards are claimed. Existing reputation scores are unchanged by honors.

## Continuity and houses

The overview previews a named successor and current eligibility. `name_successor`, `abdicate` and `succeed` provide explicit continuity. Confirmed NPC death vacates offices once; cancellation, unloading, player death/respawn and absence do not automatically install a ruler. Succession rechecks the candidate and preserves the capital, kingdom identity and active treaties. Vacant leadership without a named successor requires an explicit operator appointment. Old delegated mandates do not survive leadership vacancy.

The House action records one political house label, motto and explicitly selected member per government. It does not change surnames, genealogy, marriage or inheritance. Elections and regencies are constitutional transitions adopted per kingdom (see the `transition_rule`, election and regency tasks, and docs/R4-Evolving-World.md); the electorate is the council, or the council plus connected residents when the rule says so, and an elected successor or leader cannot be displaced by appointment. Automatic hereditary selection, multi-house institutions and genetic/family systems are not implemented or advertised.

## Data and limits

Definitions live under `data/<namespace>/mcakingdoms/` in `governments`, `offices`, `agreements`, `petitions`, `honors`, and `institution_charters`. Built-in JSON files are complete examples of schema 1. The definition loader validates the entire political snapshot before publishing it at the reload boundary. Accepted terms stay on records; missing definitions disable affected actions without rewriting old terms.

The sidecar is `world/data/mcakingdoms_politics.dat`, schema 1, separate from settlement and faction data. It stores typed JSON records in an NBT envelope. Unsupported or malformed data stays read-only and is retained intact. Identity queries remain available, but merges/reassignments are blocked when opaque political references cannot be validated.

Pages contain at most 20 records. Defaults allow three concurrent petitions per player/kingdom, one pending/active agreement per type and pair, and 256 operational notices per kingdom. Storage bounds are 128 governments, 4,096 agreements/petitions/recognitions each, 8,192 honors and 8,192 retained request receipts. Capacity exhaustion refuses new mutations with a diagnostic rather than dropping evidence. There is no automatic receipt pruning or destructive repair command. The operational journal is not a narrative archive; no unavailable provider is reported as having accepted an event.

The public `api/politics` contracts contain no optional-mod classes. `PoliticalCommittedEvent` is a versioned server-side post-commit fact, emitted once for successful requests and never replayed during load or receipt replay. It is not a delivery acknowledgment or a cross-mod exactly-once guarantee.
