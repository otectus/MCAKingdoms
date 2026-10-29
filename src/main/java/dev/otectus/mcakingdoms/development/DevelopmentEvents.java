package dev.otectus.mcakingdoms.development;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Drives development every second while the mode is above {@code OFF}, and registers the operator commands (D5):
 * {@code /kingdoms development inspect|pause|resume|dryrun|repair|export} and {@code /kingdoms economy inspect|diagnose|reconcile}.
 * Command output is paged to a few lines and names no player; an export writes ids and states only.
 */
public final class DevelopmentEvents {
    private DevelopmentEvents() {
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % DevelopmentService.PERIOD != 11 || DevelopmentMode.current() == DevelopmentMode.OFF) return;
        try { DevelopmentService.get(event.getServer()).maintain(); }
        catch (RuntimeException failure) { LogUtils.getLogger().error("[MCA: Kingdoms] Development maintenance failed; it is retried", failure); }
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms").then(Commands.literal("development").requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect").then(Commands.argument("settlement", StringArgumentType.string()).executes(DevelopmentEvents::inspect)))
                .then(Commands.literal("pause").then(Commands.argument("work", StringArgumentType.word())
                        .executes(c -> run(c, s -> s.pause(c.getSource().getPlayerOrException(), UUID.randomUUID(), work(c))))))
                .then(Commands.literal("resume").then(Commands.argument("work", StringArgumentType.word())
                        .executes(c -> run(c, s -> s.resume(c.getSource().getPlayerOrException(), UUID.randomUUID(), work(c))))))
                .then(Commands.literal("repair").then(Commands.argument("work", StringArgumentType.word())
                        .executes(c -> run(c, s -> s.repair(c.getSource().getPlayerOrException(), UUID.randomUUID(), work(c), false)))
                        .then(Commands.argument("rollback", BoolArgumentType.bool())
                                .executes(c -> run(c, s -> s.repair(c.getSource().getPlayerOrException(), UUID.randomUUID(), work(c), BoolArgumentType.getBool(c, "rollback")))))))
                .then(Commands.literal("dryrun").then(Commands.argument("template", StringArgumentType.string()).executes(DevelopmentEvents::dryrun)))
                .then(Commands.literal("export").executes(DevelopmentEvents::export)))
                .then(Commands.literal("economy").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("inspect").then(Commands.argument("settlement", StringArgumentType.string()).executes(DevelopmentEvents::economy)))
                        .then(Commands.literal("diagnose").executes(DevelopmentEvents::diagnose))
                        .then(Commands.literal("reconcile").executes(DevelopmentEvents::reconcile))));
    }

    private static UUID work(CommandContext<CommandSourceStack> c) {
        try { return UUID.fromString(StringArgumentType.getString(c, "work")); }
        catch (IllegalArgumentException invalid) { throw Keyed.refuse(DevelopmentService.P + "refusal.no_project", "That work is not in the records."); }
    }

    @FunctionalInterface
    private interface Step { String run(DevelopmentService service) throws CommandSyntaxException; }

    private static int run(CommandContext<CommandSourceStack> c, Step step) throws CommandSyntaxException {
        try {
            String result = step.run(DevelopmentService.get(c.getSource().getServer()));
            c.getSource().sendSuccess(() -> Keyed.component(result), true);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            c.getSource().sendFailure(Keyed.component(refused));
            return 0;
        }
    }

    private static void line(CommandContext<CommandSourceStack> c, Component text) { c.getSource().sendSuccess(() -> text, false); }

    private static int inspect(CommandContext<CommandSourceStack> c) {
        var server = c.getSource().getServer();
        var settlement = McaKingdomsApi.get(server).findSettlement(StringArgumentType.getString(c, "settlement"));
        if (settlement.isEmpty()) { c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "refusal.no_settlement", "That settlement is not known.")); return 0; }
        var service = DevelopmentService.get(server);
        line(c, ActionRegistry.tr(DevelopmentService.P + "command.mode", "Development mode: %s", ActionRegistry.enumLabel(DevelopmentMode.current())));
        service.observe(settlement.get()).forEach(text -> line(c, text));
        service.data().records().projects().stream().filter(p -> p.settlement().equals(settlement.get().id())).sorted(Comparator.comparingLong(Project::createdAt).reversed()).limit(8)
                .forEach(p -> line(c, ActionRegistry.tr(DevelopmentService.P + "command.work", "%s · %s · %s (%s)", p.id().toString(), DevelopmentService.templateTitle(p.template()),
                        ActionRegistry.enumLabel(p.state()), Keyed.component(p.reasonKey()))));
        return 1;
    }

    private static int dryrun(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var server = c.getSource().getServer();
        var template = DevelopmentTemplate.get(ResourceLocation.tryParse(StringArgumentType.getString(c, "template")));
        if (template.isEmpty() || !template.get().buildable()) { c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "refusal.no_template", "That work is not defined on this server.")); return 0; }
        var settlement = McaKingdomsApi.get(server).getSettlementAt(player.serverLevel(), player.blockPosition());
        if (settlement.isEmpty()) { c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "refusal.dryrun_outside", "Stand inside a settlement to survey a plot.")); return 0; }
        var origin = template.get().placement() == DevelopmentTemplate.Placement.GROUND ? player.blockPosition().below() : player.blockPosition();
        var survey = PlotSurvey.survey(player.serverLevel(), template.get(), origin, settlement.get(), DevelopmentService.get(server).data().records().homes(), player.getUUID());
        line(c, survey.clear() ? ActionRegistry.tr(DevelopmentService.P + "command.dryrun_clear", "Dry run: %s could be built here (%s blocks). Nothing was recorded or changed.",
                DevelopmentService.templateTitle(template.get()), survey.plan().size())
                : ActionRegistry.tr(DevelopmentService.P + "command.dryrun_blocked", "Dry run: %s cannot be built here. Nothing was recorded or changed.", DevelopmentService.templateTitle(template.get())));
        survey.blockers().stream().limit(8).forEach(blocker -> line(c, Keyed.component(blocker)));
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> c) {
        var server = c.getSource().getServer();
        var records = DevelopmentService.get(server).data().records();
        var json = new JsonObject();
        json.addProperty("mode", DevelopmentMode.current().name());
        json.addProperty("game_time", server.overworld().getGameTime());
        var works = new JsonArray();
        for (var p : records.projects()) {
            var w = new JsonObject();
            w.addProperty("id", p.id().toString()); w.addProperty("settlement", p.settlement().toString()); w.addProperty("kingdom", p.kingdom());
            w.addProperty("template", p.template().toString()); w.addProperty("state", p.state().name()); w.addProperty("reason", p.reasonKey());
            w.addProperty("contributions", p.contributions().size()); w.addProperty("blocks_planned", p.journal().size());
            w.addProperty("blocks_placed", p.journal().stream().filter(Placement::placed).count());
            if (p.origin() != null) { w.addProperty("dimension", p.dimension()); w.addProperty("origin", p.origin().toShortString()); }
            works.add(w);
        }
        json.add("works", works);
        var economies = new JsonArray();
        for (var e : records.economies.values()) {
            var o = new JsonObject();
            o.addProperty("settlement", e.settlement().toString()); o.addProperty("points", e.points()); o.addProperty("tier", e.tier()); o.addProperty("opted_in", e.optedIn());
            var goods = new JsonObject(); e.abstractGoods().forEach(goods::addProperty); o.add("abstract_goods", goods);
            economies.add(o);
        }
        json.add("settlements", economies);
        var metrics = DevelopmentMetrics.snapshot();
        var m = new JsonObject();
        m.addProperty("passes", metrics.passes()); m.addProperty("last_pass_us", metrics.lastMicros()); m.addProperty("max_pass_us", metrics.maxMicros());
        m.addProperty("queue", metrics.queue()); m.addProperty("blocks_placed_this_session", metrics.placed());
        json.add("metrics", m);
        try {
            var dir = server.getWorldPath(LevelResource.ROOT).resolve("mcakingdoms-exports");
            Files.createDirectories(dir);
            var file = dir.resolve("development-" + server.overworld().getGameTime() + ".json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
            line(c, ActionRegistry.tr(DevelopmentService.P + "command.exported", "Exported %s work(s) and %s settlement(s) to %s. Player names are not included.",
                    works.size(), economies.size(), file.getFileName().toString()));
            return 1;
        } catch (java.io.IOException failure) {
            c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "command.export_failed", "The export could not be written: %s", failure.getMessage()));
            return 0;
        }
    }

    private static int economy(CommandContext<CommandSourceStack> c) {
        var server = c.getSource().getServer();
        var settlement = McaKingdomsApi.get(server).findSettlement(StringArgumentType.getString(c, "settlement"));
        if (settlement.isEmpty()) { c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "refusal.no_settlement", "That settlement is not known.")); return 0; }
        var e = DevelopmentService.get(server).data().records().economy(settlement.get().id());
        line(c, ActionRegistry.tr(DevelopmentService.P + "command.economy", "%s: tier %s, %s point(s), construction %s.", settlement.get().displayName(), e.tier(), e.points(),
                Component.translatable(e.optedIn() ? "gui.yes" : "gui.no")));
        e.roles().forEach((role, count) -> line(c, ActionRegistry.tr(DevelopmentService.P + "command.role", "  %s × %s", count,
                ActionRegistry.enumLabel(DevelopmentTemplate.Role.valueOf(role.toUpperCase(Locale.ROOT))))));
        e.abstractGoods().forEach((commodity, amount) -> line(c, ActionRegistry.tr(DevelopmentService.P + "command.goods", "  %s %s (abstract; no such items exist)", amount, commodity)));
        return 1;
    }

    private static int diagnose(CommandContext<CommandSourceStack> c) {
        var records = DevelopmentService.get(c.getSource().getServer()).data().records();
        var metrics = DevelopmentMetrics.snapshot();
        var states = new TreeMap<State, Integer>();
        records.projects().forEach(p -> states.merge(p.state(), 1, Integer::sum));
        line(c, ActionRegistry.tr(DevelopmentService.P + "command.diagnose", "Mode %s; construction paused: %s; deliveries and trade paused: %s.", ActionRegistry.enumLabel(DevelopmentMode.current()),
                Component.translatable(DevelopmentMode.constructionPaused() ? "gui.yes" : "gui.no"), Component.translatable(DevelopmentMode.economyPaused() ? "gui.yes" : "gui.no")));
        line(c, ActionRegistry.tr(DevelopmentService.P + "command.metrics", "%s pass(es); last %s µs, longest %s µs; %s work(s) queued to build; %s block(s) placed this session.",
                metrics.passes(), metrics.lastMicros(), metrics.maxMicros(), metrics.queue(), metrics.placed()));
        states.forEach((state, count) -> line(c, ActionRegistry.tr(DevelopmentService.P + "command.state", "  %s: %s", ActionRegistry.enumLabel(state), count)));
        line(c, ActionRegistry.tr(DevelopmentService.P + "command.refunds", "%s refund(s) owed; %s protected home(s).", records.refunds().size(), records.homes().size()));
        return 1;
    }

    /** Recomputes every settlement's points and roles from its completed works, following merged settlements; idempotent. */
    private static int reconcile(CommandContext<CommandSourceStack> c) {
        var server = c.getSource().getServer();
        var service = DevelopmentService.get(server);
        var data = service.data();
        if (!data.writable()) { c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "refusal.read_only", "The development records are kept read-only on this server, so nothing can change them now.")); return 0; }
        var next = data.transaction();
        int moved = 0;
        for (var p : List.copyOf(next.projects.values())) {
            var canonical = service.settlement(p.settlement());
            if (canonical.isPresent() && !canonical.get().id().equals(p.settlement())) { next.projects.put(p.id(), p.movedTo(canonical.get().id(), next.revision)); moved++; }
        }
        var points = new HashMap<UUID, Integer>(); var roles = new HashMap<UUID, Map<String, Integer>>();
        for (var p : next.projects.values()) {
            if (p.state() != State.COMPLETED) continue;
            var template = DevelopmentTemplate.get(p.template());
            if (template.isEmpty()) continue;
            points.merge(p.settlement(), template.get().tierPoints(), Integer::sum);
            roles.computeIfAbsent(p.settlement(), x -> new TreeMap<>()).merge(template.get().role().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        var settlements = new HashSet<>(next.economies.keySet()); settlements.addAll(points.keySet());
        int changed = 0;
        for (UUID settlement : settlements) {
            var e = next.economy(settlement);
            int p = points.getOrDefault(settlement, 0); var r = roles.getOrDefault(settlement, Map.of());
            if (e.points() != p || !e.roles().equals(r)) { next.economies.put(settlement, new Economy(settlement, p, r, e.optedIn(), e.abstractGoods(), e.lastProduced())); changed++; }
        }
        if (!data.commit(server, next)) { c.getSource().sendFailure(ActionRegistry.tr(DevelopmentService.P + "refusal.save_failed", "The development records could not be saved, so nothing changed.")); return 0; }
        line(c, ActionRegistry.tr(DevelopmentService.P + "command.reconciled", "Reconciled: %s work(s) followed a merged settlement; %s settlement record(s) recomputed.", moved, changed));
        return 1;
    }
}
