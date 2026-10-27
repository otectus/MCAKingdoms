# MCA: Kingdoms — Usability and Visual Guidebook Implementation Plan

**Prepared:** 26 October 2026  
**Repository:** https://github.com/otectus/MCAKingdoms  
**Reviewed baseline:** `main`, commit `6dbb3c7508b38ee82ec778a9fad3639100470311` (24 October 2026)  
**Declared target:** Minecraft 1.20.1, Forge 47.4.23, Java 17; `mod_version=0.1.1`, with 0.1.1 marked unreleased in the changelog.

## 1. Assignment and intended result

Implement a focused usability update that makes MCA: Kingdoms understandable without a wiki, command knowledge, or familiarity with its internal architecture. Prioritize a visual Book of Kingdoms, clearer language, discoverable next steps, and consistent interaction across the existing screens.

A new player should be able to answer these questions directly in the game:

- What does this mod let me do, and where should I start?
- How do I craft the Book of Kingdoms and Village Ledger?
- Where am I, which kingdom does this settlement belong to, and what can I do here?
- What do I need to qualify for a service, and which requirement is missing?
- What will this action change, who must agree, and did it actually finish?
- Which features are available on this server?

Work in the existing Forge project. Keep the established vanilla Minecraft appearance and server-owned rules. This is an implementation handoff, not authorization to redesign gameplay balance, migrate to another loader, or replace companion mods.

### Evidence and limits

This plan comes from inspection of the pinned repository's documentation, guide resources, recipes, translations, UI classes, task protocol, configuration, relevant services, and existing test harnesses. It also uses the primary UX and Forge references in section 15. **Minecraft was not launched for this review.** Code-observed behavior is identified below; visual and human usability expectations are proposed acceptance criteria, not claimed test results.

The repository's October GUI audit records prior screenshots and successful tests, but also leaves spoken narration, real MCA/Townstead header integration, and alternate languages/resource packs unverified. Treat those as historical evidence. Re-run the relevant checks against the implementation produced from this plan.

Before editing, inspect the current branch and any `AGENTS.md` instructions. Compare it with the pinned commit. Preserve intervening work and mark already-completed recommendations as verified rather than implementing duplicates. Select the release version using the project's current release process; this plan does not prescribe a new version number.

## 2. Preserve the existing baseline

The mod already has substantial usability work. Do not spend the update recreating it.

| Existing behavior | Preserve and extend |
| --- | --- |
| Six owned screens: Ledger, Guide, Tasks, Guilds, Kingdom government, War Room | Improve their shared components and navigation; do not add a competing management hub. |
| Native buttons, edit boxes, tooltips, panels and scroll widgets | Retain Minecraft focus, input, resource-pack compatibility and familiar styling. |
| Searchable guide with eight chapters, 65 topics and 38 task-linked topics | Preserve topic identities and subject coverage while restructuring the content. |
| Guide locale fallback, resource-pack replacement, last-read topic | Keep working for existing packs; extend state retention and reload behavior. |
| Server-owned named choices, review pages, request/session identities, stale-state validation and retained results | Build presentation improvements on these safeguards. |
| Operator-only task filtering; viewer-filtered settlements and private records | Keep restricted information out of client payloads. |
| Ledger and War Room task prefills | Generalize context propagation; keep server validation of every prefill. |
| First-login help, a welcome advancement, settlement discovery and entry banners | Improve the existing onboarding instead of adding repeated popups. |
| Working kingdom, civic, political, warfare and evolution systems | Explain existing mechanics accurately; optional features remain optional. |

Read `README.md`, `CURSEFORGE.md`, `CHANGELOG.md`, `docs/Player-Experience-Review.md`, and `docs/MCAKingdoms-GUI-Audit.md` first. Then use `MODMAP.md`, the remediation status at the end of `REVIEW.md`, `docs/mcakingdoms.md`, and R1–R4 documentation for architecture and ownership. Earlier findings in `REVIEW.md` are not automatically still open: its final section marks the review issues addressed. The older design specification also predates the sixth kingdom; current definitions take precedence.

## 3. Confirmed findings and implementation priorities

Paths in this document are relative to the repository. Java paths abbreviated below are under `src/main/java/dev/otectus/mcakingdoms/`.

| ID | Priority | Evidence at the reviewed commit | Required outcome |
| --- | --- | --- | --- |
| UX-01 | P0 | `guide/KingdomGuide.java` stores prose paragraphs and command strings; `client/KingdomGuideScreen.java` renders them through `MenuTextPanel`. Both crafting recipes are described in prose in `00_basics.json`. | Add actual ingredient/output displays backed by active recipes. |
| UX-02 | P0 | `PoliticalTasks.init()` registers `BOOTSTRAP` as operator-only. `GovernmentService` permits a non-operator capital resident founding with themselves as leader when `OPEN_FOUNDING` is enabled; the config defaults to true. | Resolve the service/task-access mismatch without broadening the underlying founding rules. |
| UX-03 | P0 | `political_pages`, `found_a_government`, and `offices_and_permissions` all link to operator-only `politics.diagnose`. | Give ordinary players appropriate reading/action links with permission-aware explanations. |
| UX-04 | P0 | Guide first steps and government instructions describe founding as operator-only. Chapter-establishment prose requires Townstead, although the current MCA building fallback is documented and implemented. Operator guide says three POIs; config defaults to five. | Correct these facts and audit all guide claims against current services/configuration. |
| UX-05 | P1 | `InteractionNetwork.catalogue()` searches English category/title/help/ID using a contiguous substring. It has no goal aliases or explicit category selection. | Improve goal discovery and localized search without filtering only the current server page. |
| UX-06 | P1 | Task descriptions and consequence indicators are primarily tooltips. `ActionRegistry.tasks()` filters operator status, not comprehensive feature availability. Some empty selectors have specific hints; the remaining fallback lists several unrelated possible causes. | Show meaningful descriptions, availability and the next useful step before a long form. |
| UX-07 | P1 | `CivicViews` already reports standing, deeds, next-rank shortfall and reasons, but as flattened text; Guild service buttons become active together when a view exists. | Present separate live requirement checks and per-service status. Preserve the independent-service route. |
| UX-08 | P1 | Task-based join/leave uses review; `GuildScreen` directly sends `CivicNetwork.Action.JOIN/LEAVE` and service actions. | Make consequential behavior consistent across entry points. |
| UX-09 | P1 | Several services/views compose English strings; government rows and War Room intelligence remain strings, and `PlayerWords` only translates recognized whole keys. | Preserve structured, translatable data to the client; do not rely on English sentence parsing. |
| UX-10 | P1 | The ledger has paged rows and a cycling kingdom filter, but no settlement-name search box. Its detail view emphasizes classification metadata. | Add server-filtered search and prioritize useful settlement information. |
| UX-11 | P1 | The guide remembers only a static last-topic ID; article creation calls `setContent`, which resets scroll. Many articles hard-code “Press K.” | Preserve navigation state and display the actual key binding. |
| UX-12 | P2 | Client presentation settings exist in TOML; the current GUI audit found no settings screen. | Add a small accessible client settings screen and a read-only server-feature summary. |

P0 is the first delivery slice. P1 completes the main usability update. P2 is the subsequent polish slice within this plan. Optional work in section 13 must not delay P0/P1.

### P0: repair founding and misleading help links first

1. Trace `PoliticalTasks`, `ActionRegistry`, `GovernmentService.residentFounder`, the BOOTSTRAP service branch, and the command entry point together. The task catalogue currently blocks the non-operator path before the service can authorize it.
2. Make the appropriate founding task discoverable to eligible players. For non-operators, derive the candidate leader from the authenticated player and restrict capitals to legitimate, known, eligible choices. Do not trust a client-supplied leader or residence.
3. Keep final checks for first government, matching kingdom/capital, valid charter, provider availability, current civic residence, `openFounding`, and self-leadership. Operators retain their existing authorized setup path. Do not remove the operator flag from unrelated tasks.
4. Explain how the selected server establishes player civic residence. If there is no available legitimate self-service route, say what is missing and when an operator must help. Merely visiting a village must not silently create residence.
5. Link `political_pages` to `politics.read_overview`; link founding to `politics.bootstrap` once its access is corrected. Give offices a council-reading link plus an appropriate appointment link where authorized. Keep diagnostics in administration.
6. Add a check that every shipped guide task target exists. Separately test audience suitability: a valid task ID can still be a poor link for ordinary players. Operators may read the same guide without exposing operator actions to others.

## 4. Plain-language and localization specification

### 4.1 Writing rules

Use short, direct sentences with the player's goal first. Preserve useful setting vocabulary, but explain it on first use. Keep advanced implementation details in an optional technical section.

- Buttons start with a specific action: “View requirements,” “Join guild,” “Send proposal,” “Sign agreement,” “Return to settlement.” Avoid a universal “Apply reviewed action” when the exact action is known.
- Every blocker states the problem and an available next step. Do not present six possible causes when the server knows the cause.
- Introduce one concept per short paragraph. Use numbered steps for a procedure, labeled requirements for eligibility, and a separate consequence section for decisions.
- Name the place, person, guild or kingdom involved. Keep full names available through wrapping/details/narration when a compact row is shortened.
- Distinguish “not known,” “not loaded,” “not installed,” “disabled by this server,” “not permitted,” and “waiting for another player.” They are different states.
- Present player durations in seconds/minutes or game days, and explain whether they advance with game ticks, only while participants are active, or another existing rule. Never imply wall-clock deadlines where the system uses tick budgets.
- Use the current Controls binding in interactive help. “K by default” is suitable for external documentation, not a hard-coded in-game instruction after rebinding.
- Describe a completed result only after the owning service confirms it. A timeout is not proof of failure or success.

Use the following vocabulary consistently. These are presentation changes; preserve registry names, task IDs, saved values and API semantics.

| Existing term | Preferred player-facing wording | Meaning to preserve |
| --- | --- | --- |
| Civic identity / civic kingdom | Settlement's kingdom; “kingdom identity” in detailed help | Different from military control or player citizenship. |
| Organization | Guild where it actually is a guild; otherwise keep its named organization type | Do not call every dynamic organization a guild. |
| Chapter | Local guild branch; introduce “branch (chapter)” once | An established local group, not every settlement. |
| Commission | Guild job / paid workshop job | Preserve reward, provider and eligibility differences. |
| Verified deeds | Different service jobs completed | Repeatable quest rewards and unique guild credit remain separate. |
| Standing | Guild reputation or kingdom reputation, explicitly labeled | Keep the two scores distinct from personal hearts. |
| Mandate | Granted responsibility; show “mandate” in glossary/details | Explicit, scoped political authority. |
| Institution recognition | Recognize a building for civic services | Does not build the structure or guarantee provider availability. |
| Sovereignty accord | Agreement on who governs the settlement | Civilian government and occupation can differ. |
| Mobilization lease | Temporary troop order | Preserve ownership and expiration/restoration rules. |
| Novation | Transfer an accepted job to the new organization | Requires the existing consent/authority; not automatic reassignment. |
| Receipt / evidence | Recorded completion / verified requirement | Durability remains enforced internally. |
| Reconciliation | Repair conflicting records, in operator tools | Keep guarded recovery distinct from normal gameplay. |
| Authored drama | Story event; “authored drama” in advanced help | Existing templates, opt-in and consent still apply. |

Examples of intended copy, with real values supplied by the service:

| Current pattern | Replacement pattern |
| --- | --- |
| “No eligible known choices. Check your location, permissions, prerequisites and installed integrations.” | “No guild contact is in reach. Move within {range} blocks of a known contact and keep them in sight.” Use only when this cause is established. |
| “Record one receipt-backed or authority-backed choice.” | “Choose how you will help. The next page shows what you need and what your choice changes.” |
| “Your latest quest service is waiting for a durable save.” | “Your completed job is being saved. Guild credit will update after saving finishes.” Only if this is the actual pending state. |
| “Requires a kingdom-wide … mandate.” | “You need permission to {action} across {kingdom}. Ask its leader to grant that responsibility.” |
| “The record changed since your review.” | “This agreement changed while you were reviewing it. Review the updated terms before signing.” |
| “The change could not be saved. Please try again.” | For an uncertain mutation: “We could not confirm the result. Check its status before trying again.” Use “Nothing changed” only when guaranteed by the service. |

### 4.2 Audit and correct the entire content surface

Audit all 65 guide topics, not only the opening chapter. Review `en_us.json`, item tooltips, welcome messages/advancement, task titles/help/fields, refusal reasons, civic views, political rows, War Room output, and bundled definition names/descriptions. Include relevant README/CurseForge player instructions.

In addition to the confirmed P0 corrections, check companion-version statements, contact ranges, point-of-interest detection rules, feature defaults, eligibility thresholds, independent service, costs, accepted-work retention, and timers. Do not copy defaults into multiple paragraphs when a live requirement card or one reference table will suffice. Static defaults must say “Default”; the server's actual setting is shown separately.

Keep all six bundled kingdoms, including Shimaguni. Render names and heraldry from definitions. Do not infer territorial ownership from biome colors or invent a capital, historical alliance, generated city, or building recipe.

### 4.3 Technical implementation

1. Use translation keys with typed arguments/components for fixed UI text. Preserve player-created names and text literally. Add concise English fallbacks.
2. Replace concatenated English presentation at its source. Start with `CivicViews`, task field/help text, political row construction, and warfare presentation. Do not add regex replacements that try to translate sentences after the fact.
3. Carry rich text or typed presentation records through the existing packet boundaries. Keep localization on the client and keep client language classes off dedicated-server code paths.
4. Keep logic keyed by stable IDs/enums, never translated labels. Localized search must return original IDs/tokens for server validation.
5. Retain the existing guide language-file model and English fallback. Preserve topic/block IDs across translations so language changes do not break history or links.
6. Extend `LangCoverageTest` to cover new key families, argument placeholders and registered task fields. Its current raw-key checks do not prove all concatenated prose is translated.
7. Test a pseudo-localized language with substantially longer text and a non-Latin font. Translation coverage and layout are separate checks.

**Acceptance:** common player journeys contain no raw keys, UUIDs, provider capability names, revision hashes, Java exceptions, or untranslated fixed English when translations exist. Technical identifiers remain available to operators in an explicit diagnostic view.

## 5. Visual Book of Kingdoms

### 5.1 Keep the native reader; introduce typed content blocks

Extend the current JSON guide rather than introducing a required Patchouli, JEI, REI or other guide dependency. Use a versioned content schema and a reusable mixed-content article renderer. Keep `Screen` for the book: decorative recipe slots do not require a container menu or inventory transaction.

Proposed block types:

| Block | Purpose and required behavior |
| --- | --- |
| `paragraph` / `heading` | Wrapped, selectable-for-navigation text with a logical reading order. |
| `steps` | Short, numbered procedure; optional internal guide/task links. |
| `recipe` | Active recipe by ID, ingredients, layout, output, counts, station and accessible description. |
| `item` | Actual ItemStack icon/name with tooltip; explanation of its purpose. |
| `callout` | Labeled tip, requirement or consequence; never color alone. |
| `image` | Packaged resource texture, caption and required alt text; bounded dimensions. |
| `comparison` / `diagram` | Small structured visual, such as kingdom identity versus military control; text equivalent required. |
| `requirements` | Separately labeled live status supplied through an authorized read path, or explicitly static educational requirements. |
| `links` | Named related topics and real task entry points; no executable command strings. |

Suggested schema example—this is a new contract to implement, not an existing API:

```json
{
  "schema": 2,
  "entries": [
    {
      "id": "ledger_recipe",
      "category": "basics",
      "title": "Craft the Village Ledger",
      "summary": "Use the ledger to browse settlements you know.",
      "keywords": ["craft", "recipe", "paper", "book", "compass"],
      "blocks": [
        {
          "id": "recipe",
          "type": "recipe",
          "recipe": "mcakingdoms:village_ledger"
        },
        {
          "id": "use",
          "type": "steps",
          "items": [
            "Craft the ledger at a crafting table.",
            "Use it to browse settlements you have discovered.",
            "Select a settlement to see its details."
          ]
        },
        {
          "id": "discovery_help",
          "type": "links",
          "links": [
            {"kind": "guide", "target": "ledger_and_discovery", "label": "How discovery works"},
            {"kind": "task", "target": "settlement.inspect", "label": "Inspect a known settlement"}
          ]
        }
      ],
      "commands": []
    }
  ]
}
```

Continue accepting unversioned legacy entries containing `body`, `commands`, and optional `task`; adapt them internally to text/reference/link blocks. For schema 2, use one documented source of article content; reject ambiguous simultaneous `body` and `blocks` rather than showing duplicate prose. Preserve legacy `task` as a supported shorthand. Keep existing category and topic IDs, including `start_here`.

Implement validation for allowed block types, resource locations, unique block IDs per topic, link targets, image dimensions, nesting depth, and text/collection budgets. Preserve the present file/entry limits unless there is a measured reason to change them. An invalid optional block should produce a readable fallback without losing unrelated topics; an unsupported schema should leave other valid resources usable and identify the failing resource in the log. Follow resource-pack precedence and locale fallback deliberately; do not silently mix two incompatible schema versions of one topic.

Keep remote URLs, scripts, arbitrary command execution and world mutations out of this content system. Task links open the existing server-reviewed workflow.

### 5.2 Required recipe displays

The current repository registers exactly two items and two recipes. Display both accurately before adding optional external recipe examples.

| Item / recipe ID | Current ingredients | Display |
| --- | --- | --- |
| Book of Kingdoms — `mcakingdoms:book_of_kingdoms` | 1 ordinary book + 1 paper, shapeless, output 1 | Item icons in a small crafting layout, “Any arrangement,” output arrow/item, and “Inventory crafting or crafting table.” |
| Village Ledger — `mcakingdoms:village_ledger` | 5 paper + 1 book + 1 compass, shaped, output 1 | Full 3×3 grid with the real pattern, output arrow/item, and “Crafting table.” |

The ledger grid is specified below for acceptance, not as a replacement for the in-game rendered recipe:

| Row | Left | Center | Right |
| --- | --- | --- | --- |
| Top | Paper | Paper | Paper |
| Middle | Paper | Book | Paper |
| Bottom | Empty | Compass | Empty |

Requirements:

1. Resolve the recipe from the connected world's synchronized recipe manager using its ID. Use Forge/Minecraft 1.20.1 types; do not copy 1.21 recipe-holder or item-component APIs.
2. Render real item stacks through Minecraft's item rendering, decorations and tooltips. Show the actual result count and any relevant stack data. Respect resource packs.
3. For shaped recipes, preserve width, height, empty slots and arrangement. For shapeless recipes, state explicitly that position is irrelevant. Determine crafting-area requirements from the resolved recipe, not a separately maintained ingredient sentence.
4. Show a grouped ingredient list beside/below the visual when space permits. Any inventory comparison is informational; it must account for overlapping ingredients correctly or be omitted. A “Craftable” label must not promise that a player can craft at their current location.
5. Tag/alternative ingredients must expose their alternatives by a deliberate interaction or slow preview. Provide a non-animated presentation and a stable narrated name; do not repeat narration as a preview cycles. Empty tags show an unavailable ingredient.
6. For custom ingredients or serializers, use their supported display behavior. Never claim every custom recipe is representable. If it cannot be displayed faithfully, show an honest fallback and optional recipe-viewer integration only when installed.
7. Invalidate caches on recipe synchronization, tag changes, resource reload, language change and world disconnect. Re-resolve on reopening after `/reload`; if supporting live updates while open, preserve the reader's position.
8. If the referenced recipe was removed, show “This recipe is unavailable on this server.” Do not display the shipped recipe as craftable. If the same output now has other recipes, offer explicitly discovered alternatives; do not silently pretend another recipe is the original ID.
9. With no connected world, say current server recipes are unavailable. Any bundled illustration must be visibly labeled as a default example and must never override a live server result.
10. Recipe displays are read-only. Clicking an ingredient may open a guide topic or optional recipe viewer; it must not consume inventory, give items or auto-craft.

**Recipe acceptance:** replace the ledger recipe with a datapack using different ingredients/output count, reload and reopen the guide. The display matches the server. Remove it entirely and the original recipe is no longer presented as available. Both recipes work with no recipe-viewer mod installed.

### 5.3 Useful visuals beyond recipes

Implement these explanatory visuals as part of the update:

- A compact “Two books, two purposes” comparison using the real item icons: Book = learning; Ledger = known places and their services.
- A kingdom gallery using the server's public definitions and existing heraldry, with the six bundled kingdoms as the default content. Keep names alongside colors/icons; accommodate added or removed definitions.
- A guild progress view with separate standing, different-job count and service eligibility, rather than one misleading universal progress bar.
- A small diagram distinguishing a settlement's kingdom, government and current military control. Use labeled boxes/arrows, with a text equivalent; it is explanatory rather than a live territorial map.
- A short annotated guide to the Ledger/Tasks workflow. Prefer diagrams and actual interface elements; if screenshots are used, capture the implementation's real UI and provide localized captions. Do not bake essential English instructions into images.
- A labeled process view for agreement and recruit-transfer consent, showing which stage needs which participant. It must distinguish proposing, consenting and applying.

Do not invent crafting recipes for political institutions, guild branches, campaigns or other abstract records. Explain the real building recognition or setup steps and link to the owning mod where relevant.

### 5.4 Reader navigation and layout

- Keep Chapters, search and previous/next topic navigation. Add clear Home, Back and related-topic links. “Back” restores the previous topic, search, chapter, list page and article position; “Close” returns to the parent screen.
- Make previous/next topic labels distinct from contents-list pagination. Display topic position when helpful.
- Add bookmarks and recent topics locally. Preserve the existing last-read behavior and clear invalid bookmarks gracefully after pack changes.
- Keep commands collapsed under “Commands and advanced details” on player topics. Operator/reference chapters may expose their technical content directly.
- Rank guide search by title, keywords, summary, then body/reference. Support ordinary synonyms such as job/commission, branch/chapter and treaty/agreement. Preserve multiword search and locale fallback; provide clear reset and no-results help.
- Do not discard article scroll or keyboard focus on unrelated search changes, window resizing or a status refresh. Retain a stable block anchor plus offset, not only an absolute pixel position.
- At wide sizes, use contents plus article. At narrow sizes, offer a contents/article switch or stacked layout. Never shrink text to fit the full desktop arrangement.
- Keep full recipe grids together when possible. On a small viewport, allow normal article scrolling and preserve ingredient/output readability.

**Implementation:** introduce a dedicated mixed-content guide panel built on native scrolling/focus facilities. Keep `MenuTextPanel` for plain text screens unless a shared change is needed. Register interactive recipe/link elements so hover, focus, narration and clipping work together. Decorative slots are not focusable actions; the recipe has a concise narration summary, with optional ingredient details.

## 6. Task discovery and practical onboarding

### 6.1 Extend Kingdom Tasks as the entry point

Keep the existing key mapping and task catalogue. Add a compact starting view within the same screen with:

- The current recognized settlement, if any, and a link to inspect it.
- Up to three relevant next actions derived from authorized current state.
- Clearly labeled access to All tasks, the Guide, Guilds and Settings.
- Optional personal shortcuts for recent/favorite tasks.

Examples of suggestions: inspect the settlement just discovered; view the player's actual guild requirements; review a proposal awaiting their consent. Do not recommend unavailable providers, reveal undiscovered places, or assume everyone wants to govern or fight. The complete authorized catalogue remains one action away. Experienced players may choose to open it directly.

Add explicit category filters, such as Places, Guilds and jobs, Government, Exploration, Conflict, and World events. Keep operator tools separated. Map categories to stable task metadata rather than searching an English category string. Retain familiar wording/aliases for existing categories.

### 6.2 Search that works across the catalogue

The current task search performs one contiguous English substring match. Replace it with token-based, ranked matching over title, aliases, summary and category, with exact/prefix title matches favored. Include sensible player vocabulary: “paid work,” “make peace,” “become ruler,” “why can't I join,” “leave guild,” and “where am I.” Avoid adding an LLM or online dependency for this.

Provide a bounded, authorized catalogue-metadata snapshot for client-localized searching, or another design that searches translated labels across the complete authorized catalogue. Do not search only the current 20-item response. Private choices continue to be fetched through server-filtered endpoints; they are not bulk-exported for convenience.

Debounce remote search and cancel/ignore superseded replies. Keep Enter search and a visible Search control; do not send a request per rendered frame or let old responses overwrite a newer query. Existing 20-requests-per-second throttling is a protection, not the target request rate.

### 6.3 Onboarding without interruption

1. Extend the existing welcome message/advancement with one clear route to getting started and Controls. Do not auto-open a modal when someone joins a multiplayer server.
2. Let the Tasks screen open the first-steps guide without requiring ownership of the book. This prevents requiring the book to learn its own recipe; the crafted book remains a convenient entry point.
3. First steps: understand the two items; craft/use the ledger; discover and inspect a village; optionally explore local jobs. Show recipes at the step where they matter.
4. Keep introductory explanations short, dismissible and replayable. A local tutorial preference must not reset the world's discovery records, advancement progress or legacy welcome migration.
5. Display the actual bound key, an “Unbound” state when appropriate, and a Controls shortcut. Do not hard-code a key into image captions.
6. Audit existing recipe unlocking paths before changing them. Preserve any programmatic unlock behavior; add missing discovery guidance only after tracing it. Do not give items or gameplay rewards merely for opening help.

**Acceptance:** a new ordinary player can reach both recipes, inspect a discovered settlement and find an applicable next step without commands or external documentation. An experienced player can dismiss the onboarding and reach existing tasks immediately.

## 7. Requirements, action forms and recovery

### 7.1 Shared action presentation

Extend `ActionRegistry` and the relevant service adapters with a presentation model instead of putting more prose into one `detail` string. Suggested concepts:

| Presentation data | Required use |
| --- | --- |
| Stable task/category IDs, translated title, summary and search aliases | Catalogue and contextual links. |
| Availability state with a safe reason | Available, choose context first, requirement unmet, disabled, integration unavailable, pending, or unknown. |
| Requirement rows | Label, met/unmet/unknown state, current/required values when appropriate, and a permitted next action. |
| Form progress | Current step, expected step count where meaningful, selected context and editable fields. |
| Review sections | Selected target, changes, cost/reward, timing, required consent, cancellation/recovery terms. |
| Result status | Completed, waiting, blocked, failed before change, or outcome not yet confirmed. |
| Follow-up links | Inspect result, return to source view, view requirements, refresh status or open a relevant guide topic. |

These are display projections, not a second rules engine. Extract shared read-only eligibility/explanation functions from the owning services where necessary. The service that executes the operation remains authoritative and rechecks current state. If a condition cannot be known until a target is selected, say “Choose a settlement to check requirements.” Do not label an unknown check failed or promise an action will succeed.

Separate visibility from availability. Operator-only tasks stay hidden from ordinary players. Publicly explainable unavailable features may appear with a reason and Learn more. A private record the player is not allowed to know must never appear as a disabled option, count, search hint, preview or blocker name.

Availability checks must not force-load chunks, query every villager in the world or perform a mutation. Reuse service snapshots and invalidate cached checks when their relevant state changes. Cache keys must include viewer/context and applicable revisions; avoid stale cross-player results.

### 7.2 Requirements shown where they help

Before a player enters a long workflow, show the known prerequisites. Update context-dependent checks after selection. Use labeled icons plus text, such as “Ready,” “Needed,” or “Could not check.”

For a guild service, present distinct checks for membership or independent qualification, standing, different completed jobs, valid local contact/building, supported quest content/provider, and applicable local restrictions. Show alternative eligibility routes as alternatives; do not imply the player must satisfy both member and independent thresholds. Calculate the active rule from the current organization definition.

For a public political action, show the relevant kingdom, required office/responsibility, target scope and consent requirements. For a nearby contact, use the configured range and line-of-sight rule; known coordinates or names may only be included when the viewer is allowed to receive them.

Every unavailable button needs a readable nearby reason or a focusable “Why unavailable?” control. An inactive vanilla button's hover tooltip alone is not sufficient for keyboard users.

### 7.3 Forms and reviews

- Keep one decision per step where it reduces confusion, but show the task name and selected context throughout. Avoid asking for a settlement again when the parent view has already supplied a valid one.
- Add labeled examples, number ranges and units. Validate numbers before submission and again on the server. Audit `InteractionNetwork`'s generic NUMBER parsing as well as downstream typed reads so malformed text never reaches users as a JDK exception message.
- Review known values as labeled sections. For relevant operations include who/where, what changes, costs or rewards, notice/deadline, required consent, and the point beyond which cancellation is unavailable. Do not invent fees or guarantees the owning service does not provide.
- Replace the generic final button with a task-specific label such as “Join Lamplighters,” “Send agreement proposal,” or “Confirm troop transfer.” The label must describe the current stage: consent is not execution.
- Keep low-risk reading and navigation free of confirmation dialogs. Preserve existing review for consequential actions. Inspect actual side effects before reclassifying “browse” operations: some currently create offers or disclose a new destination.
- A review-page Edit action may return to a field, but must invalidate dependent selections and any previous approval/version snapshot. Changed terms require another review.
- Retain safe drafts and scroll/focus on resize or recoverable validation errors. Never silently restore a previously approved mutation after reconnect or execute it when a screen reopens.
- Escape returns safely; it must never submit. While waiting, users can leave the screen, but closing is not represented as cancelling a request already sent to the server.

### 7.4 Feedback and uncertain outcomes

Build on the existing `CHECK`, retained-session and duplicate-apply behavior. Do not replace it with “retry by starting a new action.”

| State | Visible treatment | Safe next step |
| --- | --- | --- |
| Loading read data | Brief loading label; preserve useful prior data with a stale/loading indication | Wait or cancel navigation. |
| Applying a reviewed action | “Sending {action}…”; disable duplicate submit | Retain request identity; allow leaving the view. |
| Waiting for another participant or provider | Specific stage and expected event when known | Check status or an authorized cancellation path. |
| Confirmed completion | What changed, affected target, and relevant follow-up | Inspect the updated record or return to the parent. |
| Known refusal before any change | Specific blocker; safe draft retained | Fix requirement, refresh or edit. |
| Timeout / unknown completion | “No result received yet. Check status before trying again.” | Query the same operation; do not resend under a fresh identity. |
| Stale review | Explain that the terms/target changed | Refresh and review the new state. |

Do not infer these states by parsing English exception messages. Represent service outcomes explicitly where needed; retain readable fallback messages for unexpected faults. A successful packet delivery is not a completed business operation.

Keep the result visible long enough to read and narrate. Prefer inline feedback over chat spam for actions started in a screen. Preserve provider-owned messages/rewards and avoid announcing the same outcome twice. Refresh the source view on return after a confirmed change without losing its selected record or filters.

**Acceptance:** a delayed or duplicate Apply performs the operation at most once; a user can discover its result without repeating it. A stale review cannot be submitted. Ineligible players receive an accurate next step without private information leaking.

## 8. Improve the existing screens

### 8.1 Village Ledger

1. Add settlement-name search across all settlements visible to that player, including supported aliases. Extend the existing paged server query; do not download hidden records or filter only the displayed batch.
2. Replace the cycling kingdom filter with an explicit picker and clear “All kingdoms” state. Use current definitions, translated names and heraldry. Preserve selections across refresh when still valid.
3. Add useful sorting: name and same-dimension distance first. Discovered/visited sorting is appropriate only if the required timestamps exist; do not label creation time as visit time. Show another-dimension locations clearly without a meaningless distance.
4. Prioritize settlement name, kingdom, dimension/location and relevant actions. Put assignment source, detection metadata and other technical information under More details; keep it available for troubleshooting.
5. Add contextual links to the settlement's government, relevant civic services and help. Where data is unavailable or the settlement is undiscovered, retain the existing privacy boundary.
6. Use distinct empty states: no discoveries yet, no filter matches, loading failure, or missing definition. Each has an appropriate action such as visit a village, clear filters or retry the read.
7. Keep selection by stable settlement identity so rename/alias reconciliation does not select the wrong row. Remove or explain a selected record that is no longer visible.

### 8.2 Guilds

Reuse the existing own-player data and service explanations; do not rebuild guild logic on the client.

- Group the view into Membership, Progress, Available services, Nearby contact and Recent service.
- Display standing, different-job count and next rank separately, with exact values from the definition. A progress bar must label the quantity it represents and handle negative/zero thresholds correctly.
- Give every service its own status and requirements link. Show member and independent qualification clearly.
- Make valid contacts easy to identify by permitted name and available location/distance. Avoid treating an unloaded or unknown contact as dead, absent forever or a new reason to spawn one.
- Route consequential join/leave and service actions through the same reviewed workflow used by Tasks, with organization context prefilled and revalidated. Keep simple reads direct. When leaving, show the actual effect on membership and retained accepted work/service history.
- Return cleanly from provider-owned quest menus. The quest provider continues to own job acceptance, items, rewards and cancellation; do not create another quest journal or delivery button.

### 8.3 Kingdom government

- Preserve the current tabs and full keyboard paging, but replace prose dumps with labeled record sections where useful.
- Show the actual translated kingdom name/heraldry, government status, capital, leadership and the viewer's relevant responsibilities. Avoid deriving a display title by stripping a namespace from an ID.
- For no government, offer a factual explanation and the corrected founding path. For governance disabled by MCA: Capitals, explain which installed system owns government and preserve read-only behavior where applicable.
- Show agreements as named parties, purpose, state, signature status and timing. Show petitions as request, requester where visible, decision state and next action.
- Label institutions by building/service purpose and current verification status. Give honors a plain explanation of what was earned; an honor is not a government office.
- Add election/regency summaries using authorized fields. Show whether the viewer voted and aggregate participation when permitted; never reveal another voter's ballot.
- Make History a readable chronological list with event labels and expandable details. It remains historical evidence, not a button to replay a transaction.

### 8.4 War Room and evolving-world tasks

- Divide the War Room into current control, current campaign, temporary orders and civilian options. Keep civilian recovery tasks easy to find alongside military actions.
- Label settlement kingdom, civilian government and military controller separately. An occupation must not visually imply that cultural identity, family, reputation or property was rewritten.
- Show campaign/proposal stage, elapsed/remaining time using the correct clock, and whose action is needed next. Include pause conditions that matter to the player.
- Preserve context in all six existing War Room links. Use the shared requirement/review model for targets, consent and costs instead of a second workflow.
- For world evolution, display “Disabled on this server/world,” “No opportunities known to you,” and “Waiting for participants” distinctly. Existing opt-in and authored scenario rules remain authoritative.
- In recruit-transfer workflows, explain the recipient's destination-sharing step before proposal. Group sharing, consent, notice and ownership transfer are separate stages. Expose only authorized groups and participants.

### 8.5 Common help and navigation

Give every major screen a predictable Help entry linked to its current topic. Help returns to the exact screen state. Use a consistent footer and distinguish return-to-parent from closing to the game. Keep all existing keyboard and mouse-wheel/drag functionality. Add shortcuts only if they are discoverable and do not conflict with ordinary text editing.

## 9. Accessibility and responsive layout

Use the existing Minecraft widgets as the foundation. The primary UX references support clear status, familiar language, error recovery and visible choices. W3C guidance is useful as an accessibility design reference, but CSS pixels are not Minecraft GUI units; do not claim WCAG certification from these checks.

Required behavior:

1. Every operation is possible by keyboard. Tab/Shift+Tab follow a sensible order; Enter/Space activate appropriate controls; lists and articles support documented navigation; no focus traps.
2. Focus is visibly distinguishable from hover, selection and disabled state. Refresh/rebuild preserves focus by stable element identity when possible and announces meaningful changes once.
3. Critical content is visible without hovering. Tooltips supplement labels and remain available through focus/details. Disabled-action explanations are reachable even when the button is inactive.
4. Recipe narration identifies output, quantities, crafting type/station, and shaped arrangement. Diagrams and images have concise text equivalents. Do not narrate a changing ingredient carousel every frame.
5. Narration covers field label, help, current value, errors, step/review status and completed/uncertain outcomes. Test spoken output on a functioning Minecraft narrator; source inspection alone is not sufficient.
6. Avoid meaning conveyed only through color. Pair status colors with a word/icon, heraldry with names, and progress fills with numbers.
7. For owned readable text/panels, target at least 4.5:1 contrast; use 3:1 as a design target for essential boundaries/focus indicators. Keep the vanilla visual style and test actual rendered backgrounds. Text needed to explain a disabled action should remain readable.
8. Keep standard button height at least the existing 20 GUI units, give controls comfortable spacing and enlarge tiny icon targets where feasible. Judge physical usability at supported scales rather than translating a web pixel rule literally.
9. Do not solve overflow by shrinking the font. Wrap, paginate, scroll or switch layout. Bound tooltips and focus indicators to the viewport.
10. Add reduced animation for ingredient previews/new help visuals. Avoid flashing status indicators and mandatory timed reading. Preserve game accessibility options and resource-pack fonts.

Minimum QA matrix: 1280×720, 1920×1080 and 2560×1440 where available; GUI scales 1–4 and Auto where selectable; the existing 240-GUI-pixel-high compact case; long names; long translations; non-Latin font; vanilla and at least one materially different resource pack. Use actual scaled viewport dimensions when deciding which cases are feasible.

All owned screens continue to be non-pausing unless a separate gameplay decision changes that rule. A discreet, contextual reminder in onboarding is enough; avoid covering every screen with repeated warnings.

## 10. Settings, feature status and helpful diagnostics

### 10.1 Small client settings screen

Expose existing presentation settings using their existing config keys and ranges. Include entry-banner enablement, first-visit behavior, kingdom-border behavior, duration and position with a preview. Explain how combined options behave.

Add only the new settings needed by this implementation: onboarding visibility/replay, starting view preference, reduced guide animation, and bookmark/recent-topic reset. Use Apply, Cancel and Reset to defaults with clear scope. Keep gameplay configuration separate from client preferences.

Register the appropriate Forge config-screen entry point for 1.20.1, and link it from Tasks. No new required settings library is necessary. Store local preferences through an appropriate existing client config or bounded local preference file; do not put them in shared settlement SavedData.

### 10.2 “Features on this server”

Add a player-readable, server-derived summary of relevant features and integrations: usable, disabled, missing required support, or temporarily unavailable. Prefer “Guild jobs need compatible MCA Quests content” over an opaque capability enum. Detect actual service availability; the client's mod list alone is insufficient.

A player-facing summary is not the operator diagnostics endpoint. Return only sanitized capability/status information and the viewer's own applicable requirements. Do not expose private cases, ballots, hidden settlements, other players' membership or raw configuration paths.

Offer a manual Copy support details action for the current error/feature. Include mod/game versions, relevant capability status and a diagnostic/request reference where safe. Do not automatically upload or post it. Detailed traces remain in logs/operator tools.

## 11. Engineering approach and file ownership

### 11.1 Recommended structure

Use the following as implementation boundaries, not a requirement to create a framework larger than the mod needs:

| Area | Existing anchors | Change |
| --- | --- | --- |
| Guide model | `guide/KingdomGuide.java` | Versioned blocks, legacy adapter, validation, search metadata and link identities. Keep model parsing independent of client rendering. |
| Guide UI | `client/KingdomGuideScreen.java`, `client/MenuTextPanel.java`, `client/VanillaGui.java` | Mixed-content reader, navigation state, recipe widgets, focus/narration. Keep plain reader behavior intact. |
| Recipe display | Proposed client-only `client/guide/GuideRecipeResolver` and renderer | Resolve active recipes and display models; cache lifecycle and fallbacks. |
| Guide content | `src/main/resources/assets/mcakingdoms/guide/en_us/*.json` | Correct facts, shorter procedures, visual references, synonyms and contextual links. |
| Language | `src/main/resources/assets/mcakingdoms/lang/en_us.json`, definition labels | Consistent vocabulary, typed text, placeholders, accessible labels. |
| Task metadata and forms | `interaction/ActionRegistry.java`, `InteractionNetwork.java`, `*Tasks.java` | Availability, aliases, typed fields/results, contextual help and task-specific confirmation labels. |
| Task UI and input | `client/KingdomActionsScreen.java`, `InteractionClient.java` | Starting view, categories, localized search, progress, drafts and result navigation. |
| Ledger | `client/VillageLedgerScreen.java`, `network/LedgerRequestPacket.java`, `LedgerPagePacket.java`, `NetworkHandler.java` | Server-filtered query/sort, informative empty states and stable selection. |
| Guilds | `civic/CivicViews.java`, `CivicNetwork.java`, `client/GuildScreen.java`, `interaction/CivicTasks.java` | Requirement projection, structured progress and consistent action entry points. |
| Government | `politics/GovernmentService.java`, `PoliticalNetwork.java`, `interaction/PoliticalTasks.java`, `client/politics/KingdomScreen.java` | Founding access repair, proper labels and structured record sections. |
| Warfare and world systems | `client/WarRoomScreen.java`, `interaction/WarfareTasks.java`, `EvolutionTasks.java`, owning services | Typed status/time/consent presentation, privacy-safe context. |
| Settings/onboarding | `McaKingdoms.java`, `presentation/PresentationConfig.java`, `client/ClientBootstrap.java`, welcome resources | Existing welcome refinement, client options and current key-binding text. |
| Tests and docs | `src/test`, `src/gameTest`, `src/clientTest`, `tools/client-test`, `tools/test`, review docs | Add targeted cases and record evidence for the actual build. Verify current test source layout before editing. |

Keep serialized presentation models small and explicit. Do not turn the task protocol into arbitrary remote UI execution. Resource-pack text can identify approved guide topics/task IDs; it cannot supply authority or bypass a service.

### 11.2 Compatibility and performance requirements

- Keep the `mcakingdoms` namespace, item/recipe IDs, task IDs and existing save migration. Do not rename persisted concepts to match new display language.
- The static guide/rendering slice should not require a gameplay save-schema migration. Local bookmarks/preferences may have their own simple versioning and safe fallback.
- New packet fields require deliberate protocol compatibility handling. At this baseline the interaction and politics channels use protocol `2`, and civic uses `3`; inspect all affected channels before changing them. Bump changed contracts and keep both sides consistent. Do not retain a version string while silently changing its wire contract.
- Preserve payload caps, bounded pages, rate limits, session ownership, stale state checks, permission revalidation and duplicate-operation behavior. Add bounds for any new catalogue, requirement or rich-content data.
- Preserve visibility checks before serialization. Counts, sort keys, autocomplete, disabled options and recommendation badges can leak records just as full details can.
- Clear world-specific data on disconnect/server change, and refresh it after permissions or definitions change. Persist only appropriate local preferences and public guide/task identifiers; private task contents must not become a cross-server “recent item” cache.
- Recipe display, guide parsing and localized search indexes must not rebuild every rendered frame. Render only visible content and bound long lists. Avoid loading large textures at arbitrary sizes.
- No guide operation force-loads chunks, scans all entities or calls external services. Read status from existing services with bounded refresh; avoid broad background polling while screens are closed.
- Keep client widgets, textures and Minecraft client classes out of common/server and public API artifacts. Run the existing API-jar checks.
- Optional recipe viewers or map integrations remain isolated optional adapters. No new provider dependency may become required for basic guide/ledger play.

## 12. Implementation sequence and deliverables

Do these steps in order, with a runnable checkpoint after each slice. Do not replace the current UI wholesale before the visual guide works.

| Slice | Work | Completion evidence |
| --- | --- | --- |
| A — Correctness and copy | Repair founding access, fix the three misleading political links, correct guide/config/provider claims, define vocabulary | Tests for ordinary/operator founding and guide link validation; reviewed copy inventory. |
| B — Visual guide | Versioned blocks, native recipe rendering, legacy fallback, navigation/history, initial diagrams | Both live recipes, modified/removed recipe fixtures, old resource-pack guide fixture, screenshots at normal/compact sizes. |
| C — Action clarity | Requirement/result model, categories/aliases/localized search, contextual forms, reliable result handling | Player can find and complete supported journeys; stale/duplicate/delayed-action tests remain green. |
| D — Screen integration | Ledger search, structured Guild/government/War Room views, consistent guild reviews, help links | Real client workflow screenshots and multiplayer/provider checks. |
| E — Accessibility and polish | Narration, long text/fonts, client settings, feature summary, onboarding | Keyboard walkthrough, spoken narration evidence, settings persistence and layout matrix. |
| F — Release preparation | Packaged checks, documentation, user-facing changelog and upgrade notes | Validation report names exact artifacts/hashes, tested providers, failures/skips and remaining limitations. |

Deliver source changes, guide resources and any packaged image assets, targeted automated tests, refreshed player documentation, and a concise implementation/validation report. Keep the existing audit history; add dated results rather than rewriting old test claims as current.

The report must separate implemented, automatically tested, manually tested and still unverified. Do not declare a feature fully accessible because narration strings exist or declare provider compatibility solely because compilation passes.

## 13. Optional enhancements after the core update

These are useful follow-ons, not prerequisites for the recipe/language update:

- Optional JEI/REI handoff for unsupported external recipes, after checking the exact installed 1.20.1 APIs. The built-in recipe displays remain sufficient for Kingdoms' own items.
- A compact discovered-places schematic or optional map marker handoff, using only server-authorized known locations. Do not build terrain mapping, reveal undiscovered settlements or infer live borders from biomes.
- Expanded political history filters and a lightweight timeline once structured history presentation is stable. Prefer readable event cards before introducing a dense relationship graph.
- A pin for one chosen personal objective or requirement checklist. Make it opt-in and removable; derive state from existing services instead of creating duplicate quest progression.

Defer a new custom GUI theme, cinematic tutorial, mandatory guidebook dependency, general-purpose graph editor, translation of all languages by guesswork, and unrelated new gameplay systems. They increase maintenance without resolving the confirmed usability problems first.

## 14. Verification and release acceptance

### 14.1 Automated cases

Extend existing tests where they already cover the same contract. Add new behavioral tests for the changed logic rather than snapshots that only duplicate implementation details.

| Test ID | Case | Required result |
| --- | --- | --- |
| A01 | Legacy and schema-2 guide resources, locale override, duplicate/invalid IDs, unknown block/schema | Valid content loads; fallback/error behavior is bounded and understandable. |
| A02 | Shaped/shapeless recipes, quantities, empty slots, alternatives/tags | Display model matches real resolved recipes and has a text equivalent. |
| A03 | Recipe replaced/removed, tag/resource reload, disconnect then another server | Display refreshes correctly; no stale recipe presented as current. |
| A04 | Every shipped guide task/topic link; normal player versus operator | IDs resolve, ordinary help avoids operator-only dead ends, restricted actions stay protected. |
| A05 | Eligible resident founding with `openFounding=true`; false; no residence; another capital/leader; existing government; operator | GUI visibility and final service checks agree; forged selections cannot broaden authority. |
| A06 | Guild member and independent thresholds; contact range/LOS; missing provider/content; suspended institution | Separate, accurate requirements; unknown state stays unknown; no provider emulation. |
| A07 | Task search synonyms, noncontiguous words, translated titles and results beyond one page | Relevant authorized results found across the catalogue. |
| A08 | Ledger search/filter/sort with aliases, same-name settlements, multiple dimensions and >20 results | Correct stable selection and authorized pagination; no hidden records in results/counts. |
| A09 | Back/edit after dependency changes, resize, validation failure and resource reload | Safe drafts/focus/anchors retained; dependent review invalidated. |
| A10 | Duplicate Apply, delayed reply, CHECK after timeout, permission loss, expired session and forged token | No duplicate mutation; truthful result; original authorization guarantees preserved. |
| A11 | Guild quick action versus equivalent task | Same review semantics and owning-service result; parent view updates. |
| A12 | Long translation/parameters, user-generated names and missing translations | No raw keys, bad placeholders or unintended server-language localization. |
| A13 | Private petition/ballot/transfer/group and undiscovered settlement through new UI paths | No leakage through summaries, suggestions, status reasons or cached metadata. |
| A14 | Dedicated-server startup and API jar verification | No client-only class linkage or accidental provider requirement. |

Use unit tests for parsing, search ranking and display-model logic; existing GameTests for authoritative world/session behavior; packaged client harnesses for actual widget/rendering/navigation behavior. Do not treat a mock recipe alone as proof that synchronized datapack recipes work.

### 14.2 In-game journeys

Run these with the exact packaged mod. At least the core journey must work with MCA: Kingdoms alone; integration journeys use supported companion/content combinations documented for that run.

1. **New player:** join, find help, craft both items from the displayed recipes, discover a village, inspect it, and return through guide/task navigation.
2. **Ordinary resident:** eligible non-operator finds founding, reviews and creates the first government; ineligible cases give accurate explanations. Repeat with open founding disabled and with governance owned by Capitals.
3. **Guild contributor:** view progress, distinguish membership from qualification, understand the next missing requirement, complete/turn in a genuine provider-owned job, and observe confirmed credit. Test independent access and leaving with accepted work.
4. **Diplomacy:** find the appropriate action, review parties/terms, obtain the required other participant's consent, observe current status and inspect history.
5. **Recruit transfer:** share one eligible destination, propose, consent, wait for the actual notice rule and complete or cancel using supported paths. Verify no unrelated groups are shown.
6. **Unavailable features:** remove/disable a relevant integration in an isolated fixture, or use supported stub capability states. The UI distinguishes unavailable features from personal ineligibility without damaging an existing player's real world.
7. **Network uncertainty:** delay a reply through the harness, leave/reopen the view, check the result and verify the operation occurred no more than once.
8. **Accessibility:** complete recipe reading, task search, requirement inspection, form review and return navigation using keyboard and narrator; repeat at compact scale with long text.

For formative usability testing, recruit several players unfamiliar with the mod if possible. Proposed targets: identify both books' purposes and find a recipe within one minute; find a supported action within three meaningful navigation steps or a direct search; explain a visible blocker and its next step without an operator translating the message. Treat these as goals to validate, not measured claims. Use failures to refine labels/navigation.

### 14.3 Build and harness use

Start with the repository's Gradle wrapper and Java 17:

```sh
./gradlew test
./gradlew build productionClientTestJar -I tools/client-test/client-test.gradle
python3 tools/test/run_gametests.py -PrunDir=build/usability-gametests
git diff --check
```

Use the existing packaged-client scripts after verifying their environment paths. At this baseline, `book_client.py` still has 0.1.0 artifact defaults and a maintainer-specific Minecraft installation path. Pass the actual built artifacts explicitly and parameterize installation paths when needed; do not accidentally test an older jar.

```sh
python3 tools/client-test/book_client.py \
  --work-dir build/usability-book \
  --mod "$USABILITY_MOD_JAR" \
  --harness "$USABILITY_CLIENT_HARNESS_JAR" \
  --xvfb "$USABILITY_XVFB" --display :96

python3 tools/client-test/interaction_client.py \
  --work-dir build/usability-menus --gui-style \
  --mod "$USABILITY_MOD_JAR" \
  --harness "$USABILITY_CLIENT_HARNESS_JAR" \
  --xvfb "$USABILITY_XVFB" --display :97

python3 tools/client-test/interaction_client.py \
  --work-dir build/usability-politics --suite politics \
  --mod "$USABILITY_MOD_JAR" \
  --harness "$USABILITY_CLIENT_HARNESS_JAR" \
  --xvfb "$USABILITY_XVFB" --display :98
```

The variables above must point to verified local artifacts/tools; they are not assumed to exist. Run the appropriate civic/multiplayer/integration scripts for changed provider seams. Retain artifact hashes, relevant logs and screenshots of the recipe pages, requirements, review/result, empty/error states and compact layouts. Inspect screenshots visually; file existence is not a layout test. Document any environment blocker instead of claiming a pass.

### 14.4 Definition of done

- [ ] Both crafting recipes are rendered as actual items/grids from active server recipes, with quantities, station, alternatives and narration.
- [ ] Existing guide packs still load; all original topics remain covered and searchable.
- [ ] Founding access and the three political guide links are corrected with permissions preserved.
- [ ] Known outdated guide claims are corrected; defaults and live settings are distinguished.
- [ ] First steps, settings and contextual help are discoverable without commands.
- [ ] Requirement displays distinguish qualification, missing support, disabled features and unknown state.
- [ ] Task/ledger search operates across the authorized result set, including localization and aliases.
- [ ] Guild quick actions and Tasks follow consistent consequential-action review.
- [ ] Results, waits and timeouts tell the truth and cannot encourage duplicate execution.
- [ ] Keyboard, actual spoken narration, compact layout, long text and resource-pack checks are recorded.
- [ ] Existing privacy, session safety, saved identities, provider ownership and API packaging checks pass.
- [ ] README, guide, changelog and dated validation report describe the implemented behavior and remaining limits accurately.

## 15. Source and design references

### Repository evidence

All repository links below are pinned to the reviewed commit. Re-check changed files against the implementation branch before applying conclusions.

- [Build declarations][repo-build] and [changelog][repo-changelog]: target and development version.
- [README][repo-readme], [player-experience review][repo-experience] and [GUI audit][repo-gui-audit]: existing functionality, prior visual work and outstanding verification.
- [Review remediation status][repo-review]: historical issues already addressed.
- [Guide model][repo-guide-model] and [guide screen][repo-guide-screen]: current paragraph-only article contract, locale fallback, search, links and reader state.
- [Getting-started guide][repo-guide-basics], [civic guide][repo-guide-civic], [political guide][repo-guide-politics] and [operator guide][repo-guide-operators]: confirmed copy/link discrepancies.
- [Book recipe][repo-book-recipe] and [Ledger recipe][repo-ledger-recipe]: exact current crafting data.
- [Political task registration][repo-political-tasks], [government service][repo-government-service] and [common config][repo-config]: founding policy/task mismatch and POI default.
- [Task registry][repo-task-registry], [interaction protocol][repo-interactions] and [task screen][repo-tasks-screen]: task filtering, search, forms, review and retained outcomes.
- [Guild screen][repo-guild-screen], [civic views][repo-civic-views] and [civic protocol][repo-civic-network]: existing progress text and direct action path.
- [Ledger screen][repo-ledger-screen], [Kingdom screen][repo-kingdom-screen] and [War Room][repo-war-room]: screen-specific presentation findings.
- [Presentation config][repo-presentation-config] and [input mapping][repo-input]: settings and keybinding integration.
- [Guide tests][repo-guide-tests], [language coverage tests][repo-lang-tests] and [book client harness][repo-book-harness]: existing validation hooks and harness limitations.

### Primary external references

- [Nielsen Norman Group: 10 Usability Heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/). Basis for understandable wording, visible system status, recognition, consistent controls and useful recovery. The particular screen designs and priorities in this plan are recommendations derived from this repository review.
- [Forge 1.20.1: Screens](https://docs.minecraftforge.net/en/1.20.1/gui/screens/). Native widget registration, focus, narration, GUI scaling and rendering responsibilities. Verify signatures in the project's exact mapped sources.
- [Forge 1.20.1: Recipes](https://docs.minecraftforge.net/en/1.20.1/resources/server/recipes/). Server recipe data and recipe-manager integration; do not hard-code an illustration as active crafting truth.
- [Forge 1.20.1: Internationalization](https://docs.minecraftforge.net/en/1.20.1/concepts/internationalization/). Preserve language-neutral translation keys until client rendering; do not localize all clients using the server's locale.
- [Forge 1.20.1: Key Mappings](https://docs.minecraftforge.net/en/1.20.1/misc/keymappings/). Rebindable input and client-side registration.
- [Forge 1.20.1: SimpleImpl](https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/). Protocol versioning and correct-thread packet handling.
- [W3C: Use of Color](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html), [Contrast Minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html), and [Target Size Minimum](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html). Accessibility design references for redundant visual cues, readable text and usable controls; web-specific units are not directly interchangeable with game GUI units.


[repo-build]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/gradle.properties
[repo-changelog]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/CHANGELOG.md
[repo-readme]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/README.md
[repo-experience]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/docs/Player-Experience-Review.md
[repo-gui-audit]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/docs/MCAKingdoms-GUI-Audit.md
[repo-review]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/REVIEW.md
[repo-guide-model]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/guide/KingdomGuide.java
[repo-guide-screen]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/KingdomGuideScreen.java
[repo-guide-basics]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/resources/assets/mcakingdoms/guide/en_us/00_basics.json
[repo-guide-civic]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/resources/assets/mcakingdoms/guide/en_us/02_civic.json
[repo-guide-politics]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/resources/assets/mcakingdoms/guide/en_us/03_politics.json
[repo-guide-operators]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/resources/assets/mcakingdoms/guide/en_us/04_operators.json
[repo-book-recipe]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/resources/data/mcakingdoms/recipes/book_of_kingdoms.json
[repo-ledger-recipe]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/resources/data/mcakingdoms/recipes/village_ledger.json
[repo-political-tasks]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/interaction/PoliticalTasks.java
[repo-government-service]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/politics/GovernmentService.java
[repo-config]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/config/McaKingdomsConfig.java
[repo-task-registry]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/interaction/ActionRegistry.java
[repo-interactions]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/interaction/InteractionNetwork.java
[repo-tasks-screen]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/KingdomActionsScreen.java
[repo-guild-screen]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/GuildScreen.java
[repo-civic-views]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/civic/CivicViews.java
[repo-civic-network]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/civic/CivicNetwork.java
[repo-ledger-screen]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/VillageLedgerScreen.java
[repo-kingdom-screen]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/politics/KingdomScreen.java
[repo-war-room]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/WarRoomScreen.java
[repo-presentation-config]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/presentation/PresentationConfig.java
[repo-input]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/main/java/dev/otectus/mcakingdoms/client/InteractionClient.java
[repo-guide-tests]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/test/java/dev/otectus/mcakingdoms/guide/KingdomGuideTest.java
[repo-lang-tests]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/src/test/java/dev/otectus/mcakingdoms/interaction/LangCoverageTest.java
[repo-book-harness]: https://github.com/otectus/MCAKingdoms/blob/6dbb3c7508b38ee82ec778a9fad3639100470311/tools/client-test/book_client.py
