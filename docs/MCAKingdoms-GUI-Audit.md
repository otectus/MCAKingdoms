# MCA: Kingdoms GUI audit — 2026-10-21

Scope: the Forge Minecraft 1.20.1 checkout, including passive UI integrations and HUDs.
The comparison is vanilla Minecraft, not MCA Reborn's interface. Build declarations remain
in `gradle.properties`; no loader, version, gameplay, saved-data or network/API changes are required.

## Inventory and original verdicts

There are six owned screens, one settlement-entry HUD, and one injected blueprint header.
No mod-owned container menu, inventory screen, config screen or additional hub was found.
`McaKingdoms.java:52` and `presentation/Presentation.java:29` register file-based Forge configs,
not config-screen factories.
The K-key task browser is the interaction hub (`client/InteractionClient.java:15`). Item
entry points are `client/BookGuideClient.java:10` and `client/ClientPresentationState.java:67`;
`client/ClientBootstrap.java:15` wires the remaining client receivers.

Paths below are relative to `src/main/java/dev/otectus/mcakingdoms/`. **Before** line numbers
refer to the working-tree snapshot captured before this change in
`build/gui-style-audit-20261021/before/src/main/java/dev/otectus/mcakingdoms/`. This matters because
the checkout already contains the uncommitted namespace rename and village-identity work.

| Surface | Original verdict | Before evidence | Result |
|---|---|---|---|
| Village Ledger: list and settlement details | Close-but-nonstandard | `client/VillageLedgerScreen.java:22` extends `Screen`, but `:66` places a short Tasks button in the title row, `:80` uses a desktop-style close glyph, and `:279`/`:330` paint fixed detail rows without scrolling. At 240 GUI pixels high, details reach the footer. | Standard 20-pixel toolbar/list buttons, bottom Done button, and a focused, wrapped native scrolling detail field. All six detail values and the full settlement name remain available. Current `client/VillageLedgerScreen.java:66`, `:83`, `:85`, `:295`. |
| Book of Kingdoms | Close-but-nonstandard | `client/KingdomGuideScreen.java:14` uses `Screen`, native buttons, an `EditBox` and tooltips; `:81` delegates the article to the custom scroll painter. Topic button titles are permanently shortened at `:73`. | Native scrolling article; full topic labels use vanilla button overflow rendering and tooltips. Search, chapters, topic navigation, last-read state and task links remain. Current `client/KingdomGuideScreen.java:74`, `:80`. |
| Kingdom Tasks: catalogue, selections, text forms, review, result, error/loading | Close-but-nonstandard | `client/KingdomActionsScreen.java:14` uses `Screen`; `:104` defines a custom two-line `OptionRow` that paints button textures and its own text. Detail modes use the shared custom panel at `:35`, `:59`, `:63`. | Ordinary `Button` rows, 20 pixels high, with descriptions and consequential-action notices in vanilla tooltips and narration; native scroll fields for forms/results. Requests, review/apply, check/retry, multi-selection, prefills, drafts and K/Enter/Escape behavior remain. Current `client/KingdomActionsScreen.java:48`, `:60`, `:64`. |
| Guild chapters, membership and services | Close-but-nonstandard | `client/GuildScreen.java:14` uses `Screen`; actions and tooltip behavior are already vanilla, but the record at `:39` uses the bespoke scroll panel. | Uses the redesigned shared native reader. Guild paging, membership, contextual services, refresh, timeout and status tooltips remain. Current `client/GuildScreen.java:39`, `:77`. |
| Kingdom government: Overview, Council, Agreements, Petitions, Institutions, Honors, History | Close-but-nonstandard | `client/politics/KingdomScreen.java:16` uses `Screen` and normal button tabs, but `:59` uses the custom panel. `:68` pages only across server batches while `:91` makes hidden records within a batch depend on the mouse wheel. The status tooltip at `:110` is painted before widgets. | Native detail reader; Previous/Next traverse visible groups before requesting another server batch, allowing keyboard access to every record. Pending tabs/actions reflect request state and status tooltips render after widgets. Current `client/politics/KingdomScreen.java:60`, `:69`, `:92`, `:113`. |
| War Room | Close-but-nonstandard | `client/WarRoomScreen.java:13` uses `Screen` and ordinary action buttons/tooltips; `:35` uses the bespoke text panel. | Uses the shared native reader. Authorized intelligence, six current task links, selected-settlement prefill, refresh and timeout behavior remain. Current `client/WarRoomScreen.java:35`, `:57`. |
| Settlement-entry banner | Custom-styled | `client/SettlementOverlayRenderer.java:41` paints a gradient plaque, gold borders and ornaments; `:81` scales the cream-colored title. | Vanilla advancement-toast texture, normal-size yellow/white text and a 16-pixel heraldry icon. Configured placement, duration/fades, first-visit rules, suppression and packet flow remain. Current `client/SettlementOverlayRenderer.java:16`, `:43`, `:50`. |
| MCA/Townstead blueprint civic header | Vanilla-style (owned header only) | `client/townstead/TownsteadBlueprintHeader.java:108` uses the vanilla panel helper and ordinary unscaled dark text, with no custom input system. | Retained. Inherits neutral panel text color from the helper. The external blueprint screen's layout/style belongs to MCA/Townstead and is outside this mod's redesign. |

All six owned screens now follow the chosen vanilla conventions. The HUD follows vanilla toast
styling; the blueprint header retains its existing vanilla panel styling. None has item slots,
so converting them to `AbstractContainerScreen` or imposing a 176×166 inventory footprint would
introduce an unrelated container model. The management layouts remain responsive and comparable
to vanilla text forms and list screens.

## Shared rendering, navigation and accessibility

- `client/VanillaGui.java:20` retains Minecraft's `textures/gui/demo_background.png` nine-sliced
  panel, preserving pixel-sized bevels and allowing resource packs to replace it. Titles use
  the normal font, dark `0x404040` text without shadows, and Minecraft's ellipsis/visual-order
  handling (`:26`). Semantic error red uses the Minecraft palette.
- Before, `client/MenuTextPanel.java:35` painted a panel, scrollbar track/thumb and focus outline,
  while `:51`/`:55` implemented its own wheel/drag behavior. It now extends the vanilla
  `AbstractScrollWidget` (`client/MenuTextPanel.java:14`) and uses `MultiLineLabel` for wrapped
  text. Minecraft owns the black field, border, clipping, scroll offset and scrollbar. The
  adapter reserves the native scrollbar's eight pixels, supplies read-only content/narration,
  and retains Page Up/Page Down/Home/End in addition to vanilla arrows and wheel/drag input.
  The padded content height and scroll limit agree, preventing a full-track thumb and a native
  divide-by-zero when dragging text that overflows by only a few pixels.
- Standard widgets remain registered through `addRenderableWidget`, so vanilla focus traversal,
  button activation, hover/focus tooltips and narration apply. Parent-screen return and
  non-pausing behavior remain. Task descriptions moved into tooltips are also narrated.
- Existing language entries, guide resources and heraldry textures are retained. No replacement
  font, GUI texture asset or external UI dependency was added. HUD/icon content remains mod data;
  its surrounding presentation now follows vanilla conventions.

Reference: [Forge 1.20.x screen documentation](https://docs.minecraftforge.net/en/1.20.x/gui/screens/)
for widget registration, focus, narration and tooltip lifecycle. Exact native method signatures,
scrollbar geometry and `AdvancementToast` texture coordinates were checked against the locally cached
Forge 47.4.23 / official Minecraft 1.20.1 classes, rather than newer-version UI APIs.

## Port scope

`build.gradle` compiles local Forge source sets; it does not share the GUI sources with a
NeoForge module. `/home/otectus/Projects/1.21.1 Ports` contains companion ports (Quests,
Reputation, Conversations, Crime, etc.), but no MCAKingdoms checkout. Neither those projects
nor `MCAReputation-neoforge` contains the Kingdoms screen classes inventoried here. They are
out of scope for this pass. A future Kingdoms port must use its version's native widgets and
GUI sprites; the 1.20.1 texture atlas coordinates should not be copied blindly into it.

## Validation

The full build passes: 181 tests, zero failures/errors, one existing optional Townstead binding
skip (`installedPublicApiProbeEnablesPublishedReadCapabilities`). API-jar validation and
reobfuscation pass. `check_mod.py` reports zero errors and zero warnings. Scoped whitespace
checks pass; unrelated pre-existing whitespace in the checkout was left alone.

Commands run from `/home/otectus/Projects/MCAKingdoms`:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms build productionClientTestJar -I tools/client-test/client-test.gradle
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/MCAKingdoms
python3 tools/client-test/interaction_client.py --work-dir build/gui-style-audit-20261021/menus-checked --mod build/libs/mcakingdoms-0.1.1.jar --harness build/test-artifacts/mcakingdoms-0.1.1-client-acceptance.jar --xvfb /home/otectus/Projects/.mcacrime-qa/env/xvfb/Xvfb --display :97 --gui-style
python3 tools/client-test/book_client.py --work-dir build/gui-style-audit-20261021/book-final --mod build/libs/mcakingdoms-0.1.1.jar --harness build/test-artifacts/mcakingdoms-0.1.1-client-acceptance.jar --xvfb /home/otectus/Projects/.mcacrime-qa/env/xvfb/Xvfb --display :98
python3 tools/client-test/interaction_client.py --work-dir build/gui-style-audit-20261021/politics-final --suite politics --mod build/libs/mcakingdoms-0.1.1.jar --harness build/test-artifacts/mcakingdoms-0.1.1-client-acceptance.jar --xvfb /home/otectus/Projects/.mcacrime-qa/env/xvfb/Xvfb --display :98
```

The build log is `/tmp/gradle-MCAKingdoms-build-20261021-185027.log`. The rebuilt runtime
artifact is `build/libs/mcakingdoms-0.1.1.jar`; it is not installed into a player instance.
The acceptance jar in `build/test-artifacts/` is test-only and is not a distributable mod.

- **Guide — passed:** real book use, resource loading, chapter/result navigation, multiword live
  search and cursor/selection retention, 240-pixel-high layout, Home/End/Page Down/wheel scrolling,
  Escape, and last-read persistence on reopening. Six screenshots and `BOOK_GUIDE_PASS.txt` in
  `build/gui-style-audit-20261021/book-final/`.
- **Politics — passed:** actual integrated-server packets, reviewed government founding, council,
  action forms, compact layout, agreement/petition tabs and parent-ledger return. Four screenshots
  and `POLITICS_PASS.txt` in `build/gui-style-audit-20261021/politics-final/`.
- **Menus and entry banner — passed:** 27 GUI captures (nine views at each of three sizes),
  widget bounds/non-overlap checks, native keyboard/wheel/drag scrolling, the short-overflow
  scrollbar regression, keyboard paging within a political batch, form draft retention,
  task create/read/rename, and War Room prefill/review. `INTERACTION_PASS.txt`, five workflow
  screenshots, all 27 GUI screenshots and `client.log` are in
  `build/gui-style-audit-20261021/menus-checked/`. The final banner uses the clean advancement
  frame. Screenshots were visually inspected as well as checked for successful capture.

Tests run with the packaged mod in isolated flat worlds under Xvfb/software rendering, using
Minecraft's default resources. The politics workflow result remains applicable: subsequent changes affect only the
arrival banner frame and native scrollbar sizing, covered by the final menu/reader runs.
Every run records copied artifact hashes in `artifacts.json`; the final menu and guide runs
use the final rebuilt runtime jar. `build/gui-style-audit-20261021/validation.json` records
the final artifact hashes and results, and `files-changed.txt` lists this pass's edits. Visual inspection caught the exclamation mark baked into the system-toast region;
its overlapping icon was removed by using the proper vanilla frame.

Existing client fixtures were updated to observe native scroll state and to expect the already
implemented selected-settlement/capital prefills. The expanded fixture invokes inherited vanilla
methods through vanilla types so packaged Forge method remapping works. These are test changes;
server behavior was not altered to satisfy the fixtures.

## Remaining in-game verification

- Hear the narrator with narration enabled. The host lacks the native `flite` library, so the
  text/narration construction and focus behavior are checked, but spoken output is unverified.
- Check the real MCA/Townstead blueprint header against those mods' controls in the full pack.
  This pass renders the actual header method with a fixture; it does not launch those providers.
- Try the player's resource packs, alternate languages/fonts and preferred display/GUI scale.
  The headless captures cover default resources at scales 2 and 3 and a 240-pixel-high compact
  viewport, not every pack/language or GPU configuration.

The passive banner preview exercises the real renderer; first-visit persistence, dimension
filtering and overlay suppression were not changed or re-tested as gameplay scenarios.

