package dev.otectus.mcakingdoms.client;

import dev.otectus.mcakingdoms.api.AssignmentSource;
import dev.otectus.mcakingdoms.client.book.*;
import dev.otectus.mcakingdoms.presentation.KingdomSummary;
import dev.otectus.mcakingdoms.presentation.PresentationConfig;
import dev.otectus.mcakingdoms.presentation.PresentationConfig.TasksStart;
import dev.otectus.mcakingdoms.presentation.SettlementSummary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * MCA: Kingdoms' client settings: the settlement entry banner (with a live preview at the chosen position), what the
 * Kingdom Tasks key opens, the start view's introduction, the Book's recipe animation and local reading history.
 *
 * <p>Changes are held until Apply; Cancel leaves everything as it was; Reset to defaults resets the values on this
 * screen (applied only with Apply). Everything here is stored on this computer in {@code mcakingdoms-client.toml} or the
 * local state file; nothing is sent to a server and no gameplay setting is changed. Also opened from Mods › Config.
 */
public final class KingdomsSettingsScreen extends Screen {
    private final Screen parent;
    private BookLayout folio;
    private int rowStep;
    private int page;
    private boolean showBanner, firstVisitOnly, bordersOnly, intro, reducedMotion;
    private int duration, bannerY;
    private TasksStart openTo;
    private int previewTicks;
    private PresentationConfig.Notices notices;
    private boolean badges;
    private int badgeDistance;

    public KingdomsSettingsScreen(Screen parent) {
        super(Component.translatableWithFallback("menu.mcakingdoms.settings.title", "MCA: Kingdoms settings"));
        this.parent = parent;
        showBanner = PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get();
        firstVisitOnly = PresentationConfig.FIRST_VISIT_ONLY.get();
        bordersOnly = PresentationConfig.KINGDOM_BORDERS_ONLY.get();
        duration = PresentationConfig.OVERLAY_DURATION_TICKS.get();
        bannerY = PresentationConfig.OVERLAY_Y.get();
        openTo = PresentationConfig.TASKS_START.get();
        intro = PresentationConfig.SHOW_INTRO.get();
        reducedMotion = PresentationConfig.REDUCED_MOTION.get();
        notices = PresentationConfig.POLITICAL_NOTICES.get();
        badges = PresentationConfig.OFFICE_BADGES.get();
        badgeDistance = PresentationConfig.OFFICE_BADGE_DISTANCE.get();
    }

    static Component s(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("menu.mcakingdoms.settings." + key, fallback, args);
    }

    private int panelWidth() { return folio.width(); }
    private int left() { return (width - panelWidth()) / 2; }

    @Override protected void init() {
        folio = BookLayout.folio(width, height, 480, 350);
        int x = left() + 22, w = panelWidth() - 44, half = (w - 4) / 2;
        int top = folio.y();
        rowStep = Math.min(24, Math.max(20, (folio.footer() - 4 - (top + 54) - 20) / 5));
        int tab = (w - 8) / 3;
        var bannerTab = addRenderableWidget(BookButton.builder(s("tab.banner", "Entry banner"), b -> { page = 0; rebuildWidgets(); }).bounds(x, top + 30, tab, 20).build().selected(page == 0));
        bannerTab.active = page != 0;
        var tasksTab = addRenderableWidget(BookButton.builder(s("tab.tasks", "Tasks and Book"), b -> { page = 1; rebuildWidgets(); }).bounds(x + tab + 4, top + 30, tab, 20).build().selected(page == 1));
        tasksTab.active = page != 1;
        var politicsTab = addRenderableWidget(BookButton.builder(s("tab.politics", "Politics"), b -> { page = 2; rebuildWidgets(); }).bounds(x + 2 * (tab + 4), top + 30, w - 2 * (tab + 4), 20).build().selected(page == 2));
        politicsTab.active = page != 2;
        int y = top + 54;
        if (page == 0) {
            addCycle(CycleButton.onOffBuilder(showBanner).withTooltip(v -> Tooltip.create(Component.empty().append(s("banner.help", "A title naming the settlement and its kingdom when you walk in."))
                            .append("\n").append(combination())))
                    .create(x, y, w, 20, s("banner", "Entry banner"), (button, value) -> { showBanner = value; rebuildWidgets(); }));
            y += rowStep;
            var first = addCycle(CycleButton.booleanBuilder(s("first.only", "First visit only"), s("first.every", "Every entry")).withInitialValue(firstVisitOnly)
                    .withTooltip(v -> Tooltip.create(s("first.help", "Show it only the first time you enter each settlement, or every time.")))
                    .create(x, y, w, 20, s("first", "Show it"), (button, value) -> { firstVisitOnly = value; }));
            first.active = showBanner;
            y += rowStep;
            var borders = addCycle(CycleButton.onOffBuilder(bordersOnly)
                    .withTooltip(v -> Tooltip.create(s("borders.help", "Show it only when the settlement belongs to a different kingdom than the last one you entered.")))
                    .create(x, y, w, 20, s("borders", "Only when the kingdom changes"), (button, value) -> { bordersOnly = value; }));
            borders.active = showBanner;
            y += rowStep;
            var length = addRenderableWidget(new BookSlider(x, y, w, 20, (duration - 20) / 380.0) {
                @Override protected void updateMessage() { setMessage(s("duration", "How long it stays: %s seconds", String.format(java.util.Locale.ROOT, "%.1f", duration / 20.0))); }
                @Override protected void applyValue() { duration = 20 + (int) Math.round(value * 380 / 5.0) * 5; }
            });
            length.active = showBanner;
            y += rowStep;
            int maxY = 1000;
            var position = addRenderableWidget(new BookSlider(x, y, w, 20, bannerY / (double) maxY) {
                @Override protected void updateMessage() { setMessage(s("position", "Distance from the top: %s", bannerY)); }
                @Override protected void applyValue() { bannerY = (int) Math.round(value * maxY); }
            });
            position.active = showBanner;
            y += rowStep;
            var preview = addRenderableWidget(BookButton.builder(s("preview", "Preview the banner"), b -> previewTicks = duration).bounds(x, y, w, 20)
                    .tooltip(Tooltip.create(s("preview.help", "Shows the banner here, at the chosen position and for the chosen time, without saving."))).build());
            preview.active = showBanner;
        } else if (page == 2) {
            addCycle(CycleButton.<PresentationConfig.Notices>builder(value -> switch (value) {
                        case OFF -> s("notices.off", "Off");
                        case CHAT -> s("notices.chat", "Chat line");
                        case BANNER -> s("notices.banner", "Chat line and banner");
                    }).withValues(PresentationConfig.Notices.values()).withInitialValue(notices)
                    .withTooltip(v -> Tooltip.create(s("notices.help", "Foundings, accessions, vacancies and memorials from the server's committed record, once each. Off by default; no sound plays.")))
                    .create(x, y, w, 20, s("notices", "Political notices"), (button, value) -> notices = value));
            y += rowStep;
            addCycle(CycleButton.booleanBuilder(s("badges.shown", "Shown"), s("badges.hidden", "Hidden")).withInitialValue(badges)
                    .withTooltip(v -> Tooltip.create(s("badges.help", "A small line above public office holders near you naming their office, such as Leader of Serenum. Their names are never changed.")))
                    .create(x, y, w, 20, s("badges", "Office badges"), (button, value) -> { badges = value; rebuildWidgets(); }));
            y += rowStep;
            var distance = addRenderableWidget(new BookSlider(x, y, w, 20, (badgeDistance - 4) / 44.0) {
                @Override protected void updateMessage() { setMessage(s("badges.distance", "Badge distance: %s blocks", badgeDistance)); }
                @Override protected void applyValue() { badgeDistance = 4 + (int) Math.round(value * 44); }
            });
            distance.active = badges;
        } else {
            addCycle(CycleButton.<TasksStart>builder(value -> value == TasksStart.START ? s("open.start", "Start view") : s("open.all", "All tasks"))
                    .withValues(TasksStart.values()).withInitialValue(openTo)
                    .withTooltip(v -> Tooltip.create(s("open.help", "What the Kingdom Tasks key shows first: where you are and suggested next steps, or the complete task list.")))
                    .create(x, y, w, 20, s("open", "Kingdom Tasks opens to"), (button, value) -> openTo = value));
            y += rowStep;
            addCycle(CycleButton.booleanBuilder(s("intro.shown", "Shown"), s("intro.hidden", "Hidden")).withInitialValue(intro)
                    .withTooltip(v -> Tooltip.create(s("intro.help", "The short introduction at the top of the start view. Getting started stays available either way.")))
                    .create(x, y, w, 20, s("intro", "Start view introduction"), (button, value) -> intro = value));
            y += rowStep;
            addCycle(CycleButton.booleanBuilder(s("motion.reduced", "Show the first item only"), s("motion.cycle", "Cycle every 2 seconds")).withInitialValue(reducedMotion)
                    .withTooltip(v -> Tooltip.create(s("motion.help", "Recipe ingredients that accept several items. Their tooltip and text list every option either way.")))
                    .create(x, y, w, 20, s("motion", "Recipe alternatives"), (button, value) -> reducedMotion = value));
            y += rowStep;
            addRenderableWidget(BookButton.builder(s("history", "Clear bookmarks and history…"), b -> minecraft.setScreen(new BookConfirmScreen(confirmed -> {
                if (confirmed) ClientState.get().clearHistory();
                minecraft.setScreen(this);
            }, s("history.confirm.title", "Clear bookmarks and history?"), s("history.confirm", "Forgets the Book's bookmarks, recent topics and last-read topic, and your recent and favourite tasks. Nothing on any server changes."))))
                    .bounds(x, y, w, 20).tooltip(Tooltip.create(s("history.help", "Local reading history only; asks before clearing."))).build());
            y += rowStep;
            var features = addRenderableWidget(BookButton.builder(s("features", "Features on this server…"), b -> InteractionClient.open(this, "help.available", ""))
                    .bounds(x, y, w, 20).tooltip(Tooltip.create(s("features.help", "Which systems and companion mods the connected server has, and your own access."))).build());
            features.active = minecraft.player != null;
            y += rowStep;
            addRenderableWidget(BookButton.builder(s("controls", "Controls…"), b -> minecraft.setScreen(new net.minecraft.client.gui.screens.controls.KeyBindsScreen(this, minecraft.options)))
                    .bounds(x, y, w, 20).tooltip(Tooltip.create(s("controls.help", "Minecraft's key bindings; the Kingdom Tasks key is under MCA: Kingdoms."))).build());
        }
        int third = (w - 8) / 3, footer = folio.footer();
        addRenderableWidget(BookButton.builder(s("reset", "Reset to defaults"), b -> { reset(); rebuildWidgets(); }).bounds(x, footer, third, 20)
                .tooltip(Tooltip.create(s("reset.help", "Sets every value on every page to its default. Nothing is saved until you choose Apply."))).build());
        addRenderableWidget(BookButton.builder(s("cancel", "Cancel"), b -> onClose()).bounds(x + third + 4, footer, third, 20)
                .tooltip(Tooltip.create(s("cancel.help", "Leave without saving."))).build());
        addRenderableWidget(BookButton.builder(s("apply", "Apply"), b -> { apply(); onClose(); }).bounds(x + 2 * (third + 4), footer, w - 2 * (third + 4), 20)
                .tooltip(Tooltip.create(s("apply.help", "Save these settings on this computer."))).build());
    }

    private void reset() {
        showBanner = PresentationConfig.SHOW_SETTLEMENT_OVERLAY.getDefault();
        firstVisitOnly = PresentationConfig.FIRST_VISIT_ONLY.getDefault();
        bordersOnly = PresentationConfig.KINGDOM_BORDERS_ONLY.getDefault();
        duration = PresentationConfig.OVERLAY_DURATION_TICKS.getDefault();
        bannerY = PresentationConfig.OVERLAY_Y.getDefault();
        openTo = PresentationConfig.TASKS_START.getDefault();
        intro = PresentationConfig.SHOW_INTRO.getDefault();
        reducedMotion = PresentationConfig.REDUCED_MOTION.getDefault();
        notices = PresentationConfig.POLITICAL_NOTICES.getDefault();
        badges = PresentationConfig.OFFICE_BADGES.getDefault();
        badgeDistance = PresentationConfig.OFFICE_BADGE_DISTANCE.getDefault();
    }

    private void apply() {
        PresentationConfig.SHOW_SETTLEMENT_OVERLAY.set(showBanner);
        PresentationConfig.FIRST_VISIT_ONLY.set(firstVisitOnly);
        PresentationConfig.KINGDOM_BORDERS_ONLY.set(bordersOnly);
        PresentationConfig.OVERLAY_DURATION_TICKS.set(Mth.clamp(duration, 20, 400));
        PresentationConfig.OVERLAY_Y.set(Mth.clamp(bannerY, 0, 1000));
        PresentationConfig.TASKS_START.set(openTo);
        PresentationConfig.SHOW_INTRO.set(intro);
        PresentationConfig.REDUCED_MOTION.set(reducedMotion);
        PresentationConfig.POLITICAL_NOTICES.set(notices);
        PresentationConfig.OFFICE_BADGES.set(badges);
        PresentationConfig.OFFICE_BADGE_DISTANCE.set(Mth.clamp(badgeDistance, 4, 48));
        PresentationConfig.SPEC.save();
    }

    /** How the three banner options combine, in words, for the current values. */
    private Component combination() {
        if (!showBanner) return s("combined.off", "No entry banner is shown.");
        if (firstVisitOnly && bordersOnly) return s("combined.both", "Shown only on a first visit that crosses into a different kingdom.");
        if (firstVisitOnly) return s("combined.first", "Shown the first time you enter each settlement.");
        if (bordersOnly) return s("combined.borders", "Shown whenever you enter a settlement of a different kingdom than the last one.");
        return s("combined.every", "Shown every time you enter a settlement.");
    }

    @Override public void tick() {
        if (previewTicks > 0) previewTicks--;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        BookSkin.cover(graphics, folio.x(), folio.y(), folio.width(), folio.height(), false, BookSkin.Binding.TASKS);
        BookSkin.title(graphics, font, getTitle(), left() + 22, folio.y() + 16, panelWidth() - 44);
        BookSkin.divider(graphics, left() + 22, folio.footer() - 4, panelWidth() - 44);
        // As many lines as fit above the footer; the same words are in the first option's tooltip.
        Component note = page == 0 ? combination() : s("local", "These settings are kept on this computer and change only how MCA: Kingdoms looks to you.");
        int textTop = folio.y() + 54 + 6 * rowStep + 3, lines = Math.max(0, (folio.footer() - 10 - textTop) / 11);
        var wrapped = font.split(note, panelWidth() - 44);
        for (int i = 0; i < Math.min(lines, wrapped.size()); i++) graphics.drawString(font, wrapped.get(i), left() + 22, textTop + i * 11, BookSkin.MUTED, false);
        super.render(graphics, mouseX, mouseY, partial);
        if (previewTicks > 0) {
            var settlement = new SettlementSummary(new UUID(0, 1), s("preview.name", "Bellmeadow").getString(), new ResourceLocation("mcakingdoms", "preview"),
                    new ResourceLocation("mcakingdoms", "serenum"), Level.OVERWORLD.location(), BlockPos.ZERO, new ResourceLocation("minecraft", "plains"),
                    AssignmentSource.FALLBACK, 0, 0);
            var kingdom = new KingdomSummary(new ResourceLocation("mcakingdoms", "serenum"), "kingdom.mcakingdoms.serenum", new ResourceLocation("mcakingdoms", "serenum"), 0xE6C65C);
            SettlementOverlayRenderer.render(graphics, font, settlement, kingdom, width, height, bannerY, previewTicks, duration, partial);
        }
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() {
        return false;
    }

    private <T> BookCycleButton<T> addCycle(CycleButton<T> cycle) {
        return addRenderableWidget(new BookCycleButton<>(cycle));
    }
}
