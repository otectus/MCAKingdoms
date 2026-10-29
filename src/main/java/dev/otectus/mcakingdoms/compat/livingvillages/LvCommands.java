package dev.otectus.mcakingdoms.compat.livingvillages;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.command.SettlementSelectorArgument;
import dev.otectus.mcakingdoms.interaction.Keyed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.function.Function;

/**
 * {@code /kingdoms livingvillages}: operator diagnostics and repair (status, inspect, reconcile, migration, conflicts, recover,
 * bind, separate, associate, adopt, rename) and the player actions that also exist as Kingdom Tasks (village, realm, open,
 * growth, supply, treaty, quiet). Every action goes through the bridge services, which check authority and provider state.
 */
public final class LvCommands {
    private LvCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var op = Commands.literal("livingvillages");
        op.then(Commands.literal("status").requires(s -> s.hasPermission(2)).executes(c -> lines(c, LvPresentation.status(c.getSource().getServer()))));
        op.then(Commands.literal("inspect").requires(s -> s.hasPermission(2)).then(Commands.argument("settlement", SettlementSelectorArgument.settlement())
                .executes(c -> run(c, rt -> lines(c, LvPresentation.inspect(rt, settlement(c).id()))))));
        op.then(Commands.literal("reconcile").requires(s -> s.hasPermission(2)).executes(c -> run(c, rt -> {
            for (Object village : rt.access.villages(rt.server)) rt.touch(rt.access.id(village));
            LvBindings.batch(rt);
            return reply(c, LvText.text("reconciled", "Queued every known village for reconciliation; %s are checked per batch.", LvConfig.batch()));
        })).then(Commands.argument("settlement", SettlementSelectorArgument.settlement()).executes(c -> run(c, rt -> {
            var binding = rt.view().bindingForSettlement(settlement(c).id()).orElseThrow(() -> LvText.refuse("not_bound", "That settlement is not a Living Villages village."));
            rt.touch(binding.village());
            LvBindings.batch(rt);
            return lines(c, LvPresentation.inspect(rt, settlement(c).id()));
        }))));
        op.then(Commands.literal("migration").requires(s -> s.hasPermission(2))
                .then(Commands.literal("preview").executes(c -> run(c, rt -> lines(c, LvPresentation.preview(rt)))))
                .then(Commands.literal("apply").executes(c -> run(c, rt -> {
                    var next = rt.data.snapshot();
                    next.migration.complete = false;
                    next.migration.cursor = null;
                    next.migration.started = rt.now();
                    if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
                    return reply(c, LvText.text("migration_started", "The import runs again in bounded batches; completed bindings are left as they are."));
                }))));
        op.then(Commands.literal("conflicts").requires(s -> s.hasPermission(2)).executes(c -> run(c, rt -> lines(c, LvPresentation.conflicts(rt)))));
        op.then(Commands.literal("recover").requires(s -> s.hasPermission(2)).then(Commands.argument("operation", StringArgumentType.word())
                .executes(c -> run(c, rt -> reply(c, recover(rt, StringArgumentType.getString(c, "operation"), operator(c)))))));
        op.then(Commands.literal("bind").requires(s -> s.hasPermission(2)).then(Commands.argument("village", StringArgumentType.word())
                .then(Commands.argument("settlement", SettlementSelectorArgument.settlement()).executes(c -> run(c, rt -> reply(c,
                        LvBindings.bindTo(rt, uuid(c, "village"), settlement(c).id(), operator(c))))))));
        op.then(Commands.literal("separate").requires(s -> s.hasPermission(2)).then(Commands.argument("village", StringArgumentType.word())
                .executes(c -> run(c, rt -> reply(c, LvBindings.createSeparate(rt, uuid(c, "village"), operator(c)))))));
        op.then(Commands.literal("associate").requires(s -> s.hasPermission(2)).then(Commands.argument("realm", StringArgumentType.word())
                .then(Commands.argument("kingdom", StringArgumentType.string()).then(Commands.argument("scope", StringArgumentType.word())
                        .executes(c -> run(c, rt -> reply(c, LvDiplomacy.associate(rt, uuid(c, "realm"), StringArgumentType.getString(c, "kingdom"),
                                StringArgumentType.getString(c, "scope").toUpperCase(Locale.ROOT), operator(c)))))))));
        op.then(Commands.literal("dissociate").requires(s -> s.hasPermission(2)).then(Commands.argument("realm", StringArgumentType.word())
                .executes(c -> run(c, rt -> reply(c, LvDiplomacy.dissociate(rt, uuid(c, "realm")))))));
        op.then(Commands.literal("adopt").requires(s -> s.hasPermission(2))
                .then(Commands.literal("preview").then(Commands.argument("settlement", SettlementSelectorArgument.settlement()).executes(c -> run(c, rt -> {
                    var preview = LvAdoption.preview(rt, settlement(c).id());
                    var out = new ArrayList<Component>();
                    preview.blockers().forEach(b -> out.add(LvText.tr("adopt.blocker", "✗ %s", b)));
                    preview.effects().forEach(e -> out.add(LvText.tr("adopt.effect", "• %s", e)));
                    return lines(c, out);
                }))))
                .then(Commands.literal("apply").then(Commands.argument("settlement", SettlementSelectorArgument.settlement()).executes(c -> run(c, rt ->
                        reply(c, LvAdoption.apply(rt, settlement(c).id(), operator(c), UUID.randomUUID())))))));
        op.then(Commands.literal("rename").requires(s -> s.hasPermission(2)).then(Commands.argument("settlement", SettlementSelectorArgument.settlement())
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, rt -> reply(c,
                        LvBindings.rename(rt, settlement(c).id(), StringArgumentType.getString(c, "name"), LvState.ActorKind.ADMINISTRATOR, operator(c))))))));
        // Player actions.
        op.then(Commands.literal("village").executes(c -> run(c, rt -> {
            var player = c.getSource().getPlayerOrException();
            var here = rt.kingdoms().getSettlementAt(player.serverLevel(), player.blockPosition())
                    .orElseThrow(() -> LvText.refuse("none_here", "No settlement here."));
            return lines(c, LvPresentation.settlement(rt, player, here.id()));
        })));
        op.then(Commands.literal("realm").executes(c -> run(c, rt -> {
            var player = c.getSource().getPlayerOrException();
            var realm = LvPresentation.realmFor(rt, player).orElseThrow(() -> LvText.refuse("no_realm", "You belong to no realm and stand in no Living Villages village."));
            return lines(c, LvPresentation.realm(rt, player, realm));
        })));
        op.then(Commands.literal("open").executes(c -> run(c, rt -> reply(c, LvActions.open(rt, c.getSource().getPlayerOrException())))));
        op.then(Commands.literal("growth").executes(c -> run(c, rt -> {
            var player = c.getSource().getPlayerOrException();
            var here = rt.kingdoms().getSettlementAt(player.serverLevel(), player.blockPosition()).orElseThrow(() -> LvText.refuse("none_here", "No settlement here."));
            if (!dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(rt.server).visible(player, here.id()))
                throw LvText.refuse("unknown_settlement", "That settlement is not available.");
            return lines(c, LvEconomy.growth(rt, here.id()));
        })));
        op.then(Commands.literal("supply").then(Commands.argument("kind", StringArgumentType.string()).then(Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                .executes(c -> run(c, rt -> {
                    var player = c.getSource().getPlayerOrException();
                    var here = rt.kingdoms().getSettlementAt(player.serverLevel(), player.blockPosition()).orElseThrow(() -> LvText.refuse("none_here", "No settlement here."));
                    return reply(c, LvEconomy.supply(rt, player, here.id(), StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "amount"), UUID.randomUUID()));
                })))));
        op.then(Commands.literal("treaty")
                .then(Commands.literal("propose").then(Commands.argument("proposer", StringArgumentType.string()).then(Commands.argument("counterpart", StringArgumentType.string())
                        .then(Commands.argument("term", StringArgumentType.word()).then(Commands.argument("ticks", LongArgumentType.longArg(1200, 2_400_000))
                                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> run(c, rt -> reply(c, LvDiplomacy.propose(rt,
                                        c.getSource().getPlayerOrException(), UUID.randomUUID(), StringArgumentType.getString(c, "proposer"), StringArgumentType.getString(c, "counterpart"),
                                        term(c), LongArgumentType.getLong(c, "ticks"), StringArgumentType.getString(c, "reason")))))))))))
                .then(Commands.literal("sign").then(Commands.argument("treaty", StringArgumentType.word()).executes(c -> run(c, rt ->
                        reply(c, LvDiplomacy.sign(rt, c.getSource().getPlayerOrException(), uuid(c, "treaty")))))))
                .then(Commands.literal("reject").then(Commands.argument("treaty", StringArgumentType.word()).executes(c -> run(c, rt ->
                        reply(c, LvDiplomacy.reject(rt, c.getSource().getPlayerOrException(), uuid(c, "treaty")))))))
                .then(Commands.literal("end").then(Commands.argument("treaty", StringArgumentType.word()).executes(c -> run(c, rt ->
                        reply(c, LvDiplomacy.end(rt, c.getSource().getPlayerOrException(), uuid(c, "treaty"))))))));
        op.then(Commands.literal("quiet").then(Commands.literal("on").executes(c -> run(c, rt -> reply(c, LvNotices.quiet(rt, c.getSource().getPlayerOrException(), true)))))
                .then(Commands.literal("off").executes(c -> run(c, rt -> reply(c, LvNotices.quiet(rt, c.getSource().getPlayerOrException(), false))))));
        event.getDispatcher().register(Commands.literal("kingdoms").then(op));
    }

    @FunctionalInterface
    private interface Action { int run(LvRuntime rt) throws CommandSyntaxException; }

    private static int run(CommandContext<CommandSourceStack> c, Action action) {
        var rt = LvRuntime.get(c.getSource().getServer()).orElse(null);
        if (rt == null) {
            var snapshot = LvCompat.snapshot(c.getSource().getServer());
            c.getSource().sendFailure(LvText.tr("inactive", "The Recruits: Living Villages integration is not active here: %s.",
                    snapshot.of(LvCompat.Capability.STABLE_BINDING).why()));
            return 0;
        }
        try {
            return action.run(rt);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            c.getSource().sendFailure(Keyed.component(refused));
            return 0;
        } catch (CommandSyntaxException syntax) {
            c.getSource().sendFailure(Component.literal(syntax.getMessage()));
            return 0;
        }
    }

    private static int lines(CommandContext<CommandSourceStack> c, List<Component> lines) {
        lines.forEach(line -> c.getSource().sendSuccess(() -> line, false));
        return lines.size();
    }

    private static int reply(CommandContext<CommandSourceStack> c, String encoded) {
        c.getSource().sendSuccess(() -> Keyed.component(encoded), true);
        return 1;
    }

    private static SettlementView settlement(CommandContext<CommandSourceStack> c) {
        String query = SettlementSelectorArgument.getSettlement(c, "settlement");
        var kingdoms = dev.otectus.mcakingdoms.api.McaKingdomsApi.get(c.getSource().getServer());
        Optional<SettlementView> found = c.getSource().getEntity() instanceof ServerPlayer player
                ? dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer()).find(player, kingdoms, query) : kingdoms.findSettlement(query);
        return found.orElseThrow(() -> LvText.refuse("unknown_settlement", "That settlement is not available."));
    }

    private static UUID uuid(CommandContext<CommandSourceStack> c, String name) {
        try {
            return UUID.fromString(StringArgumentType.getString(c, name));
        } catch (IllegalArgumentException malformed) {
            throw LvText.refuse("bad_id", "That is not a valid id.");
        }
    }

    private static UUID operator(CommandContext<CommandSourceStack> c) {
        return c.getSource().getEntity() == null ? new UUID(0, 0) : c.getSource().getEntity().getUUID();
    }

    private static LvState.Term term(CommandContext<CommandSourceStack> c) {
        try {
            return LvState.Term.valueOf(StringArgumentType.getString(c, "term").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw LvText.refuse("term_unknown", "The term must be peace, ceasefire, alliance or cede.");
        }
    }

    /** Operation-specific recovery after a fresh check; never a blind replay. */
    static String recover(LvRuntime rt, String target, UUID operator) {
        var next = rt.data.snapshot();
        if (target.equalsIgnoreCase("provider")) {
            next.provenance.regressedAt = 0;
            next.provenance.highestElapsedSteps = rt.access.elapsedSteps(rt.server);
            next.record(LvState.EventKind.RECOVERY, rt.now(), null, null, null, null, "Operator accepted the current Living Villages data as the baseline", false);
            if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
            return LvText.text("recovered_provider", "The current Living Villages data is now the baseline; control is reconciled from it without rewards.");
        }
        UUID id;
        try { id = UUID.fromString(target); } catch (IllegalArgumentException malformed) { throw LvText.refuse("bad_id", "That is not a valid id."); }
        var conflict = next.conflicts.get(id);
        if (conflict != null) {
            if (conflict.phase() != LvState.Phase.RECOVERY_REQUIRED && conflict.phase() != LvState.Phase.SUSPENDED)
                throw LvText.refuse("recover_not_needed", "That record does not need recovery.");
            boolean live = rt.access.atWar(conflict.defenderVillage());
            next.conflicts.put(id, conflict.to(live ? LvState.Phase.ACTIVE_LIVE : LvState.Phase.CANCELLED, conflict.path(), rt.now(), "operator_recovery"));
            next.record(LvState.EventKind.RECOVERY, rt.now(), conflict.settlement(), conflict.attackerRealm(), conflict.defenderRealm(), id,
                    live ? "resumed: the provider still fights it" : "closed: the provider no longer fights it", false);
            if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
            return LvText.text("recovered_conflict", "War record %s is %s after checking Living Villages' current state.", id, live ? "resumed" : "closed");
        }
        var op = next.journal.get(id);
        if (op == null) throw LvText.refuse("recover_unknown", "No war or operation has that id.");
        if (op.state() != LvState.OperationState.SUSPENDED && op.state() != LvState.OperationState.INTENT)
            throw LvText.refuse("recover_not_needed", "That record does not need recovery.");
        var village = rt.access.village(rt.server, op.subject()).orElse(null);
        String beforeTeam = op.before().contains("\"team\":\"") ? op.before().replaceAll(".*\"team\":\"([^\"]*)\".*", "$1") : null;
        boolean applied = op.kind() == LvState.OperationKind.CONQUEST && village != null && beforeTeam != null && !beforeTeam.equals(rt.access.team(village));
        next.journal.put(id, op.state(applied ? LvState.OperationState.ACKNOWLEDGED : LvState.OperationState.ABANDONED, op.after(), rt.now()));
        next.record(LvState.EventKind.RECOVERY, rt.now(), LvBindings.settlementOf(next, op.subject()), null, null, op.conflict(),
                applied ? "operator acknowledged an applied operation" : "operator closed an operation the provider did not apply", false);
        if (!rt.commit(next)) throw LvText.refuse("save_failed", "The change could not be saved, so nothing changed.");
        return LvText.text("recovered_operation", "Operation %s: %s; nothing was repeated or paid again.", id, applied ? "found applied and acknowledged" : "not applied, closed");
    }
}
