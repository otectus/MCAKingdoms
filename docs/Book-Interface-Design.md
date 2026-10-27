# Manuscript book interfaces

The Book of Kingdoms and Village Ledger are physical books carried by the player. They share ivory parchment,
tooled leather, gilt details, dark ink, manuscript headings and native Minecraft item illustrations.
The Book has a crimson binding, the Ledger green, and Kingdom Tasks walnut; each has its own frontispiece.
Kingdom Tasks, War Room, Guilds, government records and Settings reuse these materials and controls in bounded folios.
Compact parchment plaques carry the same identity into the Blueprint header and settlement arrival banner. This
supersedes the earlier GUI audit's vanilla-panel treatment for all Kingdoms-owned interfaces.

## Interface inventory

| Surface | Treatment |
| --- | --- |
| Book of Kingdoms, including every chapter, recipe, search result, saved topic and help entry point | Facing contents/article leaves; a single leaf with Contents/Article switching in compact windows. |
| Village Ledger, settlement index, kingdom picker and settlement records | Facing index/record leaves; field notes occupy the unused record leaf. Compact windows use the existing list/detail navigation. |
| Kingdom Tasks start, catalogue, categories, choices, forms, reviews, results, errors and Getting started | Matching parchment folio with white controls and ink text. The wide start view separates suggestions from recent/favourite tasks; compact windows combine them in one scrollable column. |
| The two inventory items | Angled, closed 16px books with visible page edges: a crowned crimson chronicle and a green ledger with a brass compass and strap. |
| War Room and Guilds | Bounded single-leaf intelligence/service records with manuscript text, scrollable sections, leather navigation and actions. |
| Kingdom government: Overview, Council, Agreements, Petitions, Institutions, Honors and History | A manuscript record browser with selected tabs/rows, local/server paging, contextual Help and task links; compact records stack above their scrollable details. |
| Settings, its cycle controls/sliders and history-clear confirmation | A bounded options folio; drafts survive resize and page changes. Apply/Cancel behavior and the confirmation before clearing local history remain intact. |
| Kingdoms' embedded MCA/Townstead Blueprint header and settlement arrival banner | Compact parchment plaques with heraldry and ink labels; retain the header's host-control exclusion zones and the banner's configured position, visibility and fade timing. |
| Minecraft's Controls/key-binding screen reached from Settings | Minecraft owns this screen; Kingdoms returns to its own manuscript Settings screen afterward. |

## Shared system

`client/book/BookLayout` computes all geometry in Minecraft GUI pixels. At 496 book pixels and above, the book has
facing leaves; narrower viewports use one full-width leaf. The book is bounded to 780 × 470 GUI pixels and retains
screen margins. Text uses Minecraft's own font at its normal size, including Unicode and bidirectional ordering.

`BookSkin` draws nine-sliced leather and parchment, page edges, the gutter, corner tooling and dividers. `BookButton`
extends Minecraft `Button`; `BookSearchBox` extends `EditBox`. Tooltips, keyboard focus, text editing and narration
remain native. Visible symbols retain full localized button labels for narration and tooltips. White control text sits on
dark leather with native shadows. Raised buttons, recessed input fields, green selected entries with a gilt margin tab,
flat grey-brown disabled controls, and inset gold keyboard-focus outlines distinguish interaction states. Every mod-owned
`RichPanel` and `MenuTextPanel` opts into manuscript inks and 11-pixel leading. Gilt scrollbar grips remain in the native
eight-pixel hit area. Input hints fit their field with an ellipsis;
clipped hints and button labels retain full-text tooltips. Explicit help tooltips take precedence.

`BookLayout.folio` bounds operational screens without shrinking the font. `BookCycleButton` delegates value changes,
Shift/wheel cycling, tooltips and narration to a native `CycleButton`; `BookSlider` changes only a native slider's
rendering. `BookConfirmScreen` presents a scrollable localized explanation with explicit Yes/No and treats Escape as No.
Only the Settings callback clears local history, once, after confirmation. The compact `BookSkin.plaque` uses texture
layers so every part of an arrival banner follows the existing shader-alpha fade.

Kingdom Tasks uses the same bounded geometry as the books, rendered as one folio rather than facing reading leaves.
Its welcome uses an illuminated initial; wide start pages put suggestions on the left and favourite/recent tasks on the
right, with a guild-workshop courtyard above the shortcuts when height permits. Narrow pages merge the content in reading
order. Both columns support keyboard, wheel and scrollbar navigation. Ctrl+F focuses the live search or answer field.
Forms preserve their draft on resize. Reviews, named confirmation, pending-action disabling and server authority are
unchanged; no task operation or network protocol changed for the theme.

The Ledger kingdom picker has local previous/next pages and a position indicator. Home/End jump to the first/last
kingdom page; Page Up/Down and Left/Right page through choices. Its Back button returns without changing the filter.
The settlement index's small scrollbar supports press/drag/release within the current server result page; it does not
request a different server page. The existing arrow buttons and wheel still move through local rows.

`BookPieces` supplies illuminated topic openings. Existing rich pieces continue to handle items, live recipes, comparisons,
callouts, heraldry, requirements, links and diagrams. A narrow diagram can fold into two columns in reading order instead
of becoming an unnecessarily tall stack. Four new localized diagrams explain guild service, founding, evolution and
campaign declaration; their underlying rules and prose are preserved.

The Book's single arrows turn within the current topic; double arrows change topics. Page turns overlap by a line so
a paragraph or tall illustration cannot lose a row at a page boundary. Wheel/drag scrolling remains available. There
is no page-flip animation. Page turns use Minecraft's book sound. The topic and reading-page indicators are distinct.
Roman-numbered leather tabs open chapters directly, and a star ribbon toggles a bookmark. Saved topics, recent topics,
Back history and task entry points retain their existing client-state/server authority boundaries. Following a link out
of the current search or chapter switches to the target chapter; Back restores the earlier context and reading position.

Keyboard: Tab/Shift+Tab traverse widgets and article links, Enter/Space activate them, Page Up/Page Down turn reading
pages, Home/End and arrows scroll the focused article, Ctrl+F focuses search, Alt+Left returns through reading history,
and Escape closes the book. Keyboard focus reveals links that start outside the current viewport.

Recipes still resolve from the client's synchronized server recipe manager. They preserve ingredient alternatives,
empty cells, exact shaped dimensions, shapeless arrangements, counts and unavailable/unsupported states. The visual
grid replaces the redundant written row list on paper; complete row-by-row descriptions remain in narration. Item
hover tooltips remain vanilla. Data-pack replacements and removals refresh the open reader.

## Assets and maintenance

- `src/main/resources/assets/mcakingdoms/textures/gui/book/materials.png`: original deterministic 256px material atlas;
  parchment, three binding colors and four control states are drawn with nine slices. Regenerate with
  `python tools/art/book_materials.py`. Quiet grain stays behind text; stronger wear is confined to page edges.
- `src/main/resources/assets/mcakingdoms/textures/item/{book_of_kingdoms,village_ledger}.png`: original 16px sprites, generated by the same
  script. The existing item ids, recipes and registrations are unchanged.
- `src/main/resources/assets/mcakingdoms/textures/gui/book/kingdom.png`: the Book's original royal landscape.
- `src/main/resources/assets/mcakingdoms/textures/gui/book/ledger.png`: the Ledger's riverside village, mill, bridge and trade boat.
- `src/main/resources/assets/mcakingdoms/textures/gui/book/tasks.png`: the Tasks folio's guild courtyard, workshops and noticeboard.
  All three are original 2172 × 724 illustrations made with the built-in `image_gen` tool and packaged in the mod.
  `BookIllustrations` owns their texture identifiers, dimensions and localized descriptions. Each is cached by Minecraft's
  texture manager; no per-frame image processing or disk reads are introduced. Frontispieces have a fine inset gilt mat;
  ordinary resource-pack images and diagrams keep their original rendering.
  The short Book banner crops centrally to its available aspect ratio instead of stretching the scene.
- New UI strings and the revised navigation guide are supplied in `en_us`, `es_es`, and `zh_cn`; no language key was
  removed. Existing schema 1/2 resource-pack guide content remains supported. Asset paths can be overridden by a resource
  pack. A replacement panorama should retain its 3:1 aspect ratio and declared source dimensions.

The exact panorama prompt (built-in tool, no CLI/API fallback) was:

> Create a production-ready game GUI illustration for MCA: Kingdoms, a Minecraft medieval manuscript guide. A very wide panoramic 4:1 horizontal illustration: an idyllic Minecraft-like kingdom with a small timber and stone village in the left foreground, crop fields and a winding path in the middle, a royal stone castle with modest burgundy banners on a wooded hill to the right, distant blocky mountains and restrained pale sky. Style: premium hand-crafted low-resolution pixel art fused with medieval manuscript illumination, deliberately blocky voxel-inspired geometry, crisp pixel clusters, limited harmonious palette of warm parchment cream, walnut ink outlines, muted sage/forest green, burgundy, dusty slate blue and tiny antique gold highlights. Flat illustrated perspective, ornamental but calm. Fill the image with the scene edge to edge, no book itself, no UI controls, no lettering, no text, no realistic photographic detail, no watermarks. This is a horizontal chapter-opening vignette to sit above dark readable prose on parchment; strong readable silhouettes that remain beautiful at 220 GUI pixels wide.

## Validation

The packaged acceptance harness in `BookGuideClientHarness` and `tools/client-test/book_client.py` checks actual item
use, live recipe replacement/removal/restoration through `/reload`, bookmarks saved locally, Back/Home, search,
keyboard links, task entry points, gallery loading, compact layouts and reopening. The manuscript extension adds page
turning, chapter changes, keyboard shortcuts, every packaged topic at four viewports in all three languages, actual GUI
scale and window changes, and Ledger presentation fixtures with long names and a facing record.

The harness also uses the actual Ledger item against 23 registered server settlements, checking server pagination,
debounced search, distance sorting, kingdom filters, record selection, contextual Help and return/close navigation.
The separate synthetic Ledger records establish long-name presentation. These runs do not claim automatic settlement
discovery or optional companion-mod gameplay acceptance.

Validated on 2026-10-26 against the project's Minecraft/Forge/Java targets:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms build productionClientTestJar -I tools/client-test/client-test.gradle
python /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/MCAKingdoms
python tools/client-test/book_client.py --work-dir build/book-manuscript-verified
git diff --check
```

- Build, API/example checks and packaging passed. JUnit: 229 tests, 228 passed, one existing skip, zero failures/errors.
  Full build log: `build/book-manuscript-verified/build.log`; JUnit report: `build/reports/tests/test/index.html`.
- Asset consistency: zero errors, warnings or notes. Both item sprites and the material atlas reproduce byte-for-byte
  from their generator. The release jar includes every book asset and excludes the acceptance harness.
- Packaged client passed all scenarios above, including the partial-wheel-scroll page-indicator regression and Back
  restoring the actual reading position across a chapter link. All 70 topics in each bundled language were laid out at
  320 × 240, 426 × 240, 640 × 360 and 960 × 540 GUI pixels: **840 topic/locale/viewport combinations**.
- Actual render captures cover 1280 × 800 at GUI scale 1 and 2, a 384 × 240 compact GUI viewport, and a 960 × 600 window.
  All 22 captures were written. Principal layouts, recipes, diagrams, English/Spanish/Chinese rendering and records were
  visually reviewed.
  Runtime log, exact launched artifacts/hashes, narration and pass marker: `build/book-manuscript-verified/`.
- Stable copies of the captures and pass/narration evidence: `/home/otectus/Pictures/MCAKingdoms-manuscript-2026-10-26/`.
- Body, secondary, rubric, success and informational ink have at least 5.6:1 contrast against the base parchment color.
  Narration text and keyboard activation passed. Spoken narrator audio was unavailable because this host lacks the
  native Flite library; narrator sound has not been auditioned. Third-party fonts/resource packs were not exercised.

Release artifact: `build/libs/mcakingdoms-0.2.0.jar`.
Full-overhaul acceptance artifact SHA-256: `defa010e54c8f8db9c28235e87dc2c8c6bef916b09372aedd8a71280ac2f794c`.

Control-text follow-up: book input text, placeholders and button labels now use white with Minecraft text shadows.
`/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms jar` passed;
log: `/tmp/gradle-MCAKingdoms-jar-20261026-154932.log`. The full runtime acceptance above predates this color-only change.

Item-icon follow-up: both native 16px sprites now use diagonal closed-book silhouettes and exposed parchment edges.
The chronicle has a gold crown and red bookmark; the ledger has a brass compass and leather strap. Their editable
pixel templates remain in `tools/art/book_materials.py`. Asset consistency passed with zero errors/warnings;
`jar` passed (log: `/tmp/gradle-MCAKingdoms-jar-20261026-155733.log`). Both packaged PNGs match the source assets,
use only opaque/transparent pixels, and the GUI material atlas is unchanged. These asset checks do not rerun the
earlier gameplay acceptance. Preview: `/home/otectus/Pictures/MCAKingdoms-manuscript-2026-10-26/book-and-ledger-icons.png`.
Release metadata follow-up: version 0.2.0 groups the usability and GUI work in `CHANGELOG.md`.
`/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms build` passed
(log: `/tmp/gradle-MCAKingdoms-build-20261026-160138.log`): 228 JUnit tests passed, one existing skip, no failures/errors.
The main, API and sources jars use 0.2.0; the main jar's Forge metadata and implementation version were verified.

## Kingdom Tasks folio validation

The task folio refinement was validated with:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms build productionClientTestJar -I tools/client-test/client-test.gradle
python /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/MCAKingdoms
python tools/client-test/interaction_client.py --work-dir build/tasks-manuscript-verified --task-style --languages es_es,zh_cn
```

- Build and API/example checks passed: 228 JUnit tests passed, one existing skip, zero failures/errors. Full build log:
  `build/tasks-manuscript-verified/build.log`.
- Asset/language consistency: zero errors, warnings or notes. The folio reuses the books' existing textures and illustration.
- Packaged client: K/start, All tasks, search, record choices, create/read/rename, reviewed named confirmation, compact paging
  and the missing-companion explanation passed. The task request/reply handlers were also compared with the pre-theme
  source and are unchanged.
- The task-only GUI tour rendered forms, Getting started, start and categories at scales 2, 3 and a 240-pixel-high compact
  view in English, Spanish and Simplified Chinese. Visible widgets stayed in bounds without overlapping. Form drafts
  survived resizing; Ctrl+F, Home/End, wheel scrolling and native scrollbar dragging passed, including both start columns.
- All 42 captures (36 layout/locale/scale captures plus six workflow captures) were written. Principal wide/compact,
  review, input and translated renderings were visually reviewed; the compact hint overflow was corrected with clipping
  and an ellipsis. Full hints remain available in tooltips.
- Exact runtime artifacts/hashes, pass marker and log: `build/tasks-manuscript-verified/`. Stable previews and the pass
  evidence: `/home/otectus/Pictures/MCAKingdoms-tasks-2026-10-26/`.

Task-folio acceptance artifact SHA-256: `de2c559041672c56c94bcac702266a0e791aa9fd3a9cd35d4fef6d4ff06d7927`.

## Visual refinement validation

The final refinement keeps the white control text while improving its background contrast, material detail, focus and
selection feedback, local scrolling, and the three screens' visual identities. Commands run on 2026-10-26:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms build productionClientTestJar -I tools/client-test/client-test.gradle
python /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/MCAKingdoms
python tools/client-test/book_client.py --work-dir build/visual-refine-book-verified
python tools/client-test/interaction_client.py --work-dir build/visual-refine-guis-verified --gui-style --languages es_es,zh_cn
git diff --check
```

- Final Gradle log: `build/visual-refine-book-verified/build.log` (also copied to the GUI run). Gradle reported
  `BUILD SUCCESSFUL`: API/example checks and packaging completed, 228 JUnit tests passed, one existing skip, no failures
  or errors. The launcher session returned 143 after Gradle had completed; both subsequent clients used the completed
  artifacts and passed. Asset/language checks returned zero errors, warnings or notes; diff whitespace checks passed.
- The atlas and item sprites reproduce byte-for-byte from `tools/art/book_materials.py`. Packaged PNGs match source,
  all three frontispieces have distinct content, and the normal release jar excludes the acceptance harness.
- Body/secondary ink contrast against the base parchment is 5.99:1 or greater. White labels against the base control
  states range from 7.47:1 to 12.75:1. These are calculated palette contrasts; native text shadows are additional.
- Book/Ledger acceptance passed real item use, live recipe replacement/removal/restoration, saved navigation, keyboard
  links, all 840 topic/locale/viewport combinations, scale/window changes and real server search/sort/filter/record flows.
  New assertions exercised an 18-kingdom compact picker, reaching/selecting the final kingdom, Home/End/Page Up/Page Down,
  focus after rebuilding, Back preserving the filter, no overlapping controls, and dragging to the final local settlement
  without changing the pending request or server page. A harness-only inherited-field reobfuscation error in the first
  run was corrected before this final passing run.
- The full GUI tour passed at scales 2, 3 and a 240-pixel-high compact viewport in English, Spanish and Simplified Chinese.
  It also checked distinct packaged illustration bytes and localized descriptions, input drafts, keyboard/wheel/drag
  navigation, task create/read/rename reviews and confirmations, and the missing-companion explanation.
- There are **154 final captures**: 22 Book/Ledger captures and 132 interaction/layout/locale captures. Wide and compact
  layouts, recipes, input fields, selected records and translated text were visually reviewed. Stable copies:
  `/home/otectus/Pictures/MCAKingdoms-refinement-2026-10-26/`. The `gui-2-guide.png`, `gui-2-ledger.png` and
  `gui-2-start.png` captures show the three distinct illustrated surfaces without synthetic fixture labels.
- Exact launch hashes, pass markers, screenshots and logs are in `build/visual-refine-book-verified/` and
  `build/visual-refine-guis-verified/`. Both runs used the delivered jar below. Narrator text and keyboard behavior were
  checked; spoken audio remains untested because this host lacks Flite. Third-party font/resource packs were not exercised.

Artifact at this validation step: `build/libs/mcakingdoms-0.2.0.jar`.
Historical SHA-256: `cef96645d880c7f2df2a28458ffa0e59511db756064a4596e8d049130600ad85`.

## Remaining screens: implementation and validation

The follow-up on 2026-10-26 completes the shared treatment for War Room, Guilds, all seven government tabs,
both Settings pages, the history-clear confirmation, heraldry frames, the embedded Blueprint header and the
settlement arrival banner. It reuses the material atlas and adds no image files. Government details now receive
wheel events over their own pane; compact stacked layouts no longer page the record list while reading details.
War Room/Guild request handlers and Settings save/reset behavior were compared with pre-theme copies and retained.

Commands run:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms build productionClientTestJar -I tools/client-test/client-test.gradle
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms productionClientTestJar -I tools/client-test/client-test.gradle
python /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/MCAKingdoms
python tools/client-test/interaction_client.py --work-dir build/all-manuscript-guis-final --gui-style --languages es_es,zh_cn --display :96
python tools/client-test/interaction_client.py --work-dir build/all-manuscript-politics --suite politics --display :94
python tools/client-test/production_client.py all-manuscript --xvfb /tmp/kd-xvfb/usr/bin/Xvfb
git diff --check
```

- Build, API/example checks and packaging passed: **228 JUnit tests passed, one existing skip, no failures/errors**.
  Resource validation returned zero errors, warnings or notes. Build logs are in `build/all-manuscript-evidence/`.
- The full packaged GUI tour passed **144 layout captures** (16 surfaces × three GUI sizes × English, Spanish and
  Simplified Chinese), plus six interaction captures. Sizes include native scales 2/3 and a 240-GUI-pixel-high compact
  viewport. Visible widgets stayed in bounds without overlapping. Long Guild, government and War Room records were
  exercised with keyboard/wheel scrolling; Guild/War Room scrollbar dragging and the War Room Help round-trip passed.
- Settings checks passed native keyboard cycling, disabled dependencies, slider dragging, unsaved drafts after resize,
  Cancel preserving configuration, both tabs, and the history dialog. Escape/No preserved bookmarks, favourites and
  history; a disposable Yes callback fired exactly once. No player's history was cleared by acceptance.
- Real integrated-server political acceptance passed capital founding, Overview/Council, action handoff, compact forms,
  Petitions and return to the Ledger. The real MCA/Townstead client passed its Blueprint mixin/header, request/response,
  live reassignment and standing refresh, catalog/map input, compact layouts and close cleanup; conversation entry passed
  too. This run used MCA 7.6.26, Townstead 0.7.6, Quests 1.7.1, Reputation 0.6.1 and Conversations 1.8.0.
- Final runtime logs, pass markers and exact artifact hashes are under `build/all-manuscript-guis-final/`,
  `build/all-manuscript-politics/` and `build/production-client-blueprint-all-manuscript/`. All three used the same
  production jar below. The normal jar excludes the acceptance harness.
- **159 final captures** comprise 150 GUI/interaction, four political and five real Blueprint captures. Wide/compact
  War Room, Guilds, government, Settings, confirmation, arrival banner and translated layouts were visually reviewed.
  Stable copies and six curated previews: `/home/otectus/Pictures/MCAKingdoms-all-manuscript-2026-10-26/`.
- An initial harness compile/remapping issue was corrected. A subsequent GUI launcher was terminated with exit 143
  before finishing, without a harness failure marker; the final independently launched tour completed with exit 0.
  These corrections affected only the test harness, so the successful political/Blueprint checks remained valid.
  Spoken narrator audio remains untested on this host without Flite; third-party fonts/resource packs were not exercised.

Current artifact: `build/libs/mcakingdoms-0.2.0.jar`.
SHA-256: `61ef38a1344e4b426ac878df266f8a28d96762729ae8fc994e8f1afb3dc4da39`.

## Illustration refinement prompts

Both new illustrations used the built-in `image_gen` tool (no CLI/API fallback), with the user's attached landscape as a
style reference. The existing Book panorama was retained. The original generated outputs were copied unchanged into
`textures/gui/book/ledger.png` and `textures/gui/book/tasks.png` above.

Ledger prompt:

> Use case: illustration-story. Asset type: production game GUI frontispiece for the Village Ledger in MCA: Kingdoms. Create a new very wide 3:1 panoramic medieval pixel-art illustration. The attached image is STYLE REFERENCE ONLY; create a clearly different scene, not a copy. Scene: a welcoming riverside settlement seen from a low hill, timber and stone houses, a modest watermill, stone bridge and gardens, a small harbor with one moored trading boat, rolling cultivated hills. No royal castle or snowy mountains; the subject is village life and exploration. Style: handcrafted pixel-art manuscript illumination with Minecraft-inspired blocky geometry, crisp intentional pixel clusters and readable silhouettes at 250 GUI pixels wide, muted forest greens, teal river, warm russet timber, cream parchment sky, antique gold light. Calm morning light, horizontal composition filled edge-to-edge, strongest landmarks separated and legible rather than tiny clutter. Match the reference's restrained historic fantasy atmosphere. No letters, numbers, words, UI, frame, book, modern objects, photographs, gradients or watermark. Output a single wide landscape illustration, approximately 3:1.

Kingdom Tasks prompt:

> Use case: illustration-story. Asset type: production game GUI frontispiece for Kingdom Tasks in MCA: Kingdoms. Create a new very wide 3:1 panoramic medieval pixel-art illustration. The attached image is STYLE REFERENCE ONLY; invent a distinctly different scene. Scene: an intimate sunlit guild courtyard within a medieval town, work rather than sweeping countryside. Stone cobbled square, open carpenter's workshop with workbench and stacked timber on one side, a small smithy with warm forge on the other, an arched guildhall doorway at the back, a wooden noticeboard holding blank parchment notices and a modest delivery cart in the foreground, a few small Minecraft-like villagers going about practical work. No royal castle, river, mountain panorama or modern objects. Style: premium handcrafted pixel-art manuscript illumination, Minecraft-inspired blocky buildings and tiny blocky figures, crisp intentional pixel clusters, strong readable silhouettes at 250 GUI pixels wide. Warm walnut wood, muted ochre and terracotta, burgundy cloth, little sage ivy, cream stone and pale sky. Calm late afternoon light. Horizontal composition filled edge to edge, a few legible workshop shapes rather than many tiny props. No words, letters, numbers, UI, frame, book, photorealism, watermark. Output a single wide landscape illustration, approximately 3:1.

