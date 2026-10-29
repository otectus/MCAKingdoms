# MCA Capitals and MCA: Kingdoms together

MCA Capitals (mod id `mcacapitals`) is a monarchy add-on for MCA Reborn. It has its own sovereigns, court, heirs,
nobles, mourning and chronicle. This page states how the two mods share a world (audit item C). The code is
`compat/capitals/` and `politics/Sovereignty`.

**Status in 0.3.0: read-only compatibility.** MCA: Kingdoms reads Capitals and defers to it. It never writes
Capitals, because Capitals publishes no API for another mod to act through. Delegated two-way actions are **blocked
upstream**; the proposal that would unblock them is in [Capitals upstream API proposal](Capitals-Upstream-API-Proposal.md).
Do not describe this release as full feature compatibility.

Tested build: MCA Capitals **1.3.7** for Forge 1.20.1 (Modrinth file `mcacapitals-1.3.7.jar`, SHA-512
`76517e2a…49a`). The bridge checks the accessors it reads by name and type, so another 1.3.x build with the same
accessors is read with an "untested version" note in Server health. Any mismatch makes the bridge **unsupported**, and
every MCA: Kingdoms crown action becomes read-only. It never raises an error or loads Capitals classes when Capitals is
absent.

## Who owns what

| Overlap | Owner when both are installed | What MCA: Kingdoms does |
| --- | --- | --- |
| Settlement identity, aliases, discovery | MCA: Kingdoms | Unchanged. A capital is matched to a settlement through its MCA village, never by coordinates. |
| A kingdom's crown: founding, seat, leader, succession, royal house, elections, regency | MCA Capitals, for each kingdom that holds one of its governing capitals | These actions are refused here with the reason, and the Kingdom page shows Capitals' sovereign, heir and mourning. |
| Civic offices (chancellor, envoy, stewards…), agreements, petitions, institutions, honors | MCA: Kingdoms | They keep working in every kingdom. |
| Guilds, standing, commissions, the Book, the Ledger, Tasks | MCA: Kingdoms | Unchanged. |
| War declarations for a kingdom Capitals governs | MCA Capitals | Kingdoms campaigns for that kingdom are refused, so no war or reward is declared twice. Recruits still owns sieges and claims. |
| MCA people, families, deaths | MCA Reborn | Read once by each mod. Kingdoms does not vacate the leader record of a crown Capitals governs when an NPC dies. |
| Chronicles and announcements | Each mod for its own events | Kingdoms never re-announces a Capitals event. |
| Laws of succession, succession cases, court titles (0.3.0 dynasty) | MCA Capitals, for the kingdoms it governs | A law cannot be adopted, no succession case opens, and no title is granted or answered there; Capitals' own court is shown on the Kingdom page instead. |
| Crown documents: founding and title charters (0.3.0 heraldry) | MCA Capitals, for the kingdoms it governs | Refused, with the reason. Proclamations, appointments to civic offices and treaties can still be sealed. Banners and shields still show the kingdom's arms. |
| Settlement development (0.3.0) | MCA: Kingdoms | Not a crown matter: works, deliveries and construction work the same in every kingdom. |

Kingdoms that hold no Capitals capital keep MCA: Kingdoms governments in full.

## Modes

The setting `capitals.interop` in `mcakingdoms-common.toml` chooses how the two mods share kingdoms:

- `PER_KINGDOM` (default): each kingdom is answered on its own, as below.
- `ALL_READ_ONLY`: every MCA: Kingdoms crown action is read-only while Capitals is installed. This was the only
  behaviour before 0.3.0.

Per kingdom, a kingdom is in one of these states. Each kingdom's page names its state.

| State | When | Crown actions here |
| --- | --- | --- |
| Managed by MCA: Kingdoms | No governing Capitals capital lies in the kingdom and no mapping is active | Allowed |
| Managed by MCA Capitals | A mapped capital governs it | Refused; use Capitals |
| Awaiting the mapping | A governing capital is in the kingdom but its mapping is not written yet (at most 5 seconds) | Refused |
| Not synchronized | Two governing capitals in one kingdom, or a capital whose village moved to another kingdom | Refused until an operator accepts one |
| Mapped capital no longer governs | The mapped capital stopped governing, or Capitals was removed | Refused until an operator releases the mapping |
| Unsupported Capitals version | The installed Capitals cannot be read safely | Refused in every kingdom |

## How a capital is mapped

1. Every 100 ticks, MCA: Kingdoms reads Capitals' capitals. A capital that is founded or active, and whose people did
   not reject the monarchy, is *governing*.
2. A capital names its MCA village (id and dimension). The settlement recorded for that MCA village is the evidence,
   and that settlement's kingdom is the kingdom whose crown Capitals governs.
3. One governing capital in a kingdom is mapped automatically. The mod yields the crown, which is the safe direction.
   Two in one kingdom are quarantined, so neither silently wins. A capital whose village changed kingdom (a merge, a
   split, a reassignment) is quarantined too.
4. A mapping never ends by itself. When Capitals is removed or a capital stops governing, the kingdom stays read-only
   until an operator releases the mapping. Two rulers therefore never return at once.

Mappings are kept in `<world>/data/mcakingdoms_capitals.dat`, with the evidence, the reason and who decided. An existing
MCA: Kingdoms government of a mapped kingdom is kept as history, and its civic offices keep working. No office,
founding or record is erased, transferred or recreated.

## For operators

- **Review MCA Capitals mappings** (Kingdom Tasks, Administration) or `/kingdoms capitals report`: a dry run listing
  each governing capital, its village and kingdom, the mapping now and the one the evidence supports, any existing
  MCA: Kingdoms government, and what becomes read-only. It changes nothing.
- **Accept an MCA Capitals capital as a kingdom's crown**, or `/kingdoms capitals accept <capital id>`: settles a
  quarantined kingdom. Other capitals in it stay quarantined.
- **Release an MCA Capitals mapping**, or `/kingdoms capitals release <capital id>`: returns the crown to MCA: Kingdoms.
  It is refused while the capital still governs, so end the capital in Capitals first.
- Server health shows the bridge (version, tested or not, governing capitals, delegated actions: none) and every mapping.

## What was tested

- Unit tests: `GovernmentActionPolicyTest` covers which actions are crown actions and the refusal keys.
- GameTest: `CapitalsInteropGameTests` uses a test seam in place of Capitals. It covers automatic mapping, crown actions
  refused while civic offices are still appointed, an unaffected second kingdom, quarantine of two capitals, operator
  acceptance, refused release while governing, and release after the capital stops.
- GameTest `DynastyGameTests.aCrownGovernedElsewhereIsNeverDecidedHere`: no law and no succession case for a crown
  governed elsewhere.
- Packaged co-install (phase `capitals`) with the real Capitals **1.1.1** jar, MCA Reborn 7.6.26 and Architectury 9.2.14:
  PASS. Capitals 1.2.1 and later cannot load with MCA 7.6.26 (they require MCA 7.7.x), so 1.3.7's read API was checked
  by class inspection only. Results: [Testing 0.3.0](testing/0.3.0/README.md); tuples:
  [Certified compatibility](compatibility-certified.md).

Not delivered, and why:

- **Delegated actions** (naming an heir, abdicating, granting a title in Capitals from MCA: Kingdoms screens) need a
  public Capitals operation API. None exists. Driving Capitals' private services or item handlers by reflection would
  bypass its permission checks, and the handoff forbids that.
- **Event subscription**: Capitals posts no events for other mods. Mappings follow its records by polling, and its
  chronicle stays its own.
- **KINGDOMS_PRIMARY** (Capitals observing MCA: Kingdoms' crown) needs Capitals to offer an observer mode. None exists,
  so this mode is not offered.

No Capitals code, textures, sounds or text is copied. The bridge calls Capitals' public accessors by name.
