# Migrating to 0.3.0: dynasty, heraldry, MCA Capitals and development

This page covers what the 27 October 2026 audit handoff added to 0.3.0: dynastic succession (A), physical heraldry and
charters (B), MCA Capitals co-install (C), settlement development (D) and the stabilization work (H). The general
upgrade, downgrade and recovery steps are in the [upgrade and recovery runbook](Upgrade-and-Recovery.md); follow it
first. Audience: server operators.

## Nothing changes until you choose it

Upgrading an existing world changes no government owner, law of succession, construction, economy or tax:

| Area | State after upgrading | How to turn it on |
| --- | --- | --- |
| Law of succession | Every government follows **Manual only**: a vacant leadership waits for officials, as in 0.2.0. | A kingdom's leader, or an operator, uses *Adopt a law of succession*. |
| Court titles | None exist. | An official with *Award honors* uses *Grant a court title*. |
| Mourning and memorials | On (`dynasty.mourningTicks` 24,000), shown only on pages and banners when an NPC ruler's death is observed. | Set `dynasty.mourningTicks = 0` to turn mourning off. |
| Physical heraldry | On (`heraldry.enabled`); the new blocks and the charter item exist, but nothing is placed or sealed until players do it. | Set `heraldry.enabled = false` to stop binding and sealing; the items stay so worlds keep loading. |
| MCA Capitals | Without Capitals, nothing. With Capitals and `capitals.interop = PER_KINGDOM`, the crown of a kingdom whose village is a governing Capitals capital becomes read-only here. | Review with `/kingdoms capitals report`; set `ALL_READ_ONLY` for the 0.2.0 behaviour of keeping every crown action read-only. |
| Development and economy | **Off** (`development.mode = OFF`): nothing is planned, saved or built. | Set `development.mode` to `OBSERVE`, `PROJECTS`, `MANAGED_GROWTH` or `FULL_ECONOMY`. Construction also needs each settlement to allow it. |
| Taxes and currency | None exist; no setting adds them. | — |
| Legacy advancement progress | Untouched. | `migration.legacyProgress = true` moves `ultima_kingdoms:` advancement and statistic progress once, before players load, keeping `<file>.ultima-bak`. |

## Before you upgrade

1. Back up the whole world folder and `config/`.
2. Replace the jar on the server **and** every client: 0.3.0 adds blocks and items, so a client without them cannot join.
3. Start once before anyone joins, and check `/kingdoms health` and the start-up log's provider matrix (one line per
   provider: installed version, tested range, probed capabilities, effects on, owner).

## New saved data

Each store is created only when first written and uses the strict atomic writer with a once-per-session `.bak`
([Save durability](Save-Durability.md)). A newer schema or an unreadable file is kept read-only and unchanged, and
Server health says so; nothing is reset.

| File (`world/data/`) | Holds | Written when |
| --- | --- | --- |
| `mcakingdoms_dynasty.dat` | Laws per kingdom, the ruler last seen on each throne, succession cases with the evidence each decision read, claims, renunciations, court titles, mourning, memorials. | A law is adopted, a ruler is observed, or a dynasty decision is made. |
| `mcakingdoms_documents.dat` | Sealed documents and chosen heraldic colours. | A charter is sealed or revoked, or a leader chooses colours. |
| `mcakingdoms_capitals.dat` | MCA Capitals mappings and their status. | Capitals is installed and a capital is mapped, accepted, quarantined or released. |
| `mcakingdoms_development.dat` | Public works, construction journals, settlement development, protected homes, refunds. | Only while `development.mode` is above `OFF`. |

## MCA Capitals ownership transitions

- **Installing Capitals** on a world with Kingdoms governments: a governing capital in a kingdom maps automatically when
  it is the only one there; the kingdom's crown then becomes read-only here and its existing government is kept as
  history, with its civic offices still working. Two governing capitals in one kingdom are quarantined until an
  operator accepts one (`/kingdoms capitals accept <capital>`).
- **Removing Capitals**: its mappings keep those crowns read-only until an operator releases them
  (`/kingdoms capitals release <capital>`); the release returns the crown to MCA: Kingdoms. A capital that still governs
  cannot be released.
- MCA: Kingdoms never writes to Capitals. Details: [Capitals interoperability](Capitals-Interoperability.md).

## Rolling back

- **Turning a feature off** keeps its records: `dynasty.enabled = false`, `heraldry.enabled = false` and
  `development.mode = OFF` stop new work and leave every record readable. Lowering the development mode pauses
  construction; raising it again resumes only after an official reviews each paused work.
- **Undoing construction**: an official's *Repair a public work* with *rollback* (or
  `/kingdoms development repair <work> true`) removes the blocks that work placed where they are unchanged, and cancels it.
- **Downgrading to 0.2.0** is a restore from the backup taken before upgrading: 0.2.0 does not know the new blocks,
  items or stores. Unknown stores are ignored by 0.2.0 but the new blocks would be lost.

## Legacy ids and future schemas

- Stores that existed at the Ultima Kingdoms rename are still migrated from `ultima_kingdoms_*.dat`; the four stores
  above are new and have no legacy name.
- Runtime aliases for the old namespace remain until the companion mods drop them together (see the runbook's
  [old names](Upgrade-and-Recovery.md#old-names-runtime-aliases-and-on-disk-migration)).
- A store written by a newer version is kept read-only and byte-identical; update the mod rather than editing it.
