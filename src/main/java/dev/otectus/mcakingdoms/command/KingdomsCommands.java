package dev.otectus.mcakingdoms.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.KingdomView;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class KingdomsCommands {
    private static final ResourceLocation MANUAL_DETECTOR =
            new ResourceLocation(McaKingdomsApi.MOD_ID, "manual_command");
    private static final int DEFAULT_MANUAL_RADIUS = 64;

    private KingdomsCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("kingdoms")
                .then(Commands.literal("kingdom")
                        .then(Commands.literal("list").executes(KingdomsCommands::kingdomList))
                        .then(Commands.literal("info")
                                .then(Commands.argument("kingdom", ResourceLocationArgument.id())
                                        .suggests(KINGDOM_SUGGESTIONS)
                                        .executes(KingdomsCommands::kingdomInfo))))
                .then(Commands.literal("village")
                        .then(Commands.literal("here").executes(KingdomsCommands::villageHere))
                        .then(Commands.literal("info")
                                .executes(context -> villageInfo(context, null))
                                .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                        .suggests(SETTLEMENT_SUGGESTIONS)
                                        .executes(context -> villageInfo(context,
                                                SettlementSelectorArgument.getSettlement(context, "village")))))
                        .then(Commands.literal("create").requires(source -> source.hasPermission(2))
                                .executes(context -> create(context, DEFAULT_MANUAL_RADIUS, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 512))
                                        .executes(context -> create(context,
                                                IntegerArgumentType.getInteger(context, "radius"), null))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> create(context,
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        StringArgumentType.getString(context, "name"))))))
                        .then(Commands.literal("rename").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(KingdomsCommands::rename))))
                        .then(Commands.literal("setkingdom").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().then(Commands.argument("kingdom", ResourceLocationArgument.id())
                                        .suggests(KINGDOM_SUGGESTIONS).executes(KingdomsCommands::setKingdom))))
                        .then(Commands.literal("reclassify").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().executes(KingdomsCommands::reclassify)))
                        .then(Commands.literal("lock").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().executes(context -> setLocks(context, true))))
                        .then(Commands.literal("unlock").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().executes(context -> setLocks(context, false))))
                        .then(Commands.literal("discover").requires(source -> source.hasPermission(2))
                                .executes(context -> discover(context, 8))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 32))
                                        .executes(context -> discover(context,
                                                IntegerArgumentType.getInteger(context, "radiusChunks")))))
                        .then(Commands.literal("merge").requires(source -> source.hasPermission(2))
                                .then(Commands.argument("source", SettlementSelectorArgument.settlement())
                                        .suggests(SETTLEMENT_SUGGESTIONS)
                                        .then(Commands.argument("target", SettlementSelectorArgument.settlement())
                                                .suggests(SETTLEMENT_SUGGESTIONS)
                                                .executes(KingdomsCommands::merge))))
                        .then(Commands.literal("reconcile").requires(source -> source.hasPermission(2))
                                .executes(context -> reconcile(context, 8))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 32))
                                        .executes(context -> reconcile(context,
                                                IntegerArgumentType.getInteger(context, "radiusChunks")))))
                        .then(Commands.literal("debug").requires(source -> source.hasPermission(2))
                                .executes(context -> debug(context, null))
                                .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                        .suggests(SETTLEMENT_SUGGESTIONS)
                                        .executes(context -> debug(context,
                                                SettlementSelectorArgument.getSettlement(context, "village"))))))
                .then(Commands.literal("citizen").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("info")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .executes(KingdomsCommands::citizenInfo)))
                        .then(Commands.literal("setorigin")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                                .suggests(SETTLEMENT_SUGGESTIONS)
                                                .executes(context -> setCitizenSettlement(context, true)))))
                        .then(Commands.literal("setresidence")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                                .suggests(SETTLEMENT_SUGGESTIONS)
                                                .executes(context -> setCitizenSettlement(context, false))))))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2))
                        .executes(KingdomsCommands::reload)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> settlementArgument() {
        return Commands.argument("village", SettlementSelectorArgument.settlement()).suggests(SETTLEMENT_SUGGESTIONS);
    }

    private static int kingdomList(CommandContext<CommandSourceStack> context) {
        List<KingdomView> kingdoms = service(context).getKingdoms().stream()
                .sorted(Comparator.comparing(view -> view.id().toString())).toList();
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.kingdom.list",
                kingdoms.size()), false);
        kingdoms.forEach(kingdom -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.mcakingdoms.kingdom.entry", Component.translatable(kingdom.translationKey()),
                kingdom.id().toString()), false));
        return kingdoms.size();
    }

    private static int kingdomInfo(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "kingdom");
        Optional<KingdomView> result = service(context).getKingdom(id);
        if (result.isEmpty()) return failure(context, "command.mcakingdoms.error.kingdom_missing", id);
        KingdomView kingdom = result.get();
        long settlements = context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                ? dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer()).count(player, service(context), id)
                : service(context).getSettlements(id).size();
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.kingdom.info",
                Component.translatable(kingdom.translationKey()), kingdom.id().toString(), settlements), false);
        return 1;
    }

    private static int villageHere(CommandContext<CommandSourceStack> context) {
        return villageAtSource(context).map(settlement -> sendSettlement(context, settlement))
                .orElseGet(() -> failure(context, "command.mcakingdoms.error.no_village_here"));
    }

    private static int villageInfo(CommandContext<CommandSourceStack> context, String query) {
        Optional<SettlementView> settlement = query == null ? villageAtSource(context) : find(context, query);
        return settlement.map(value -> sendSettlement(context, value))
                .orElseGet(() -> failure(context, query == null
                        ? "command.mcakingdoms.error.no_village_here"
                        : "command.mcakingdoms.error.village_missing", query == null ? "" : query));
    }

    private static int create(CommandContext<CommandSourceStack> context, int radius, String proposedName) {
        return mutate(context, service -> {
            BlockPos anchor = BlockPos.containing(context.getSource().getPosition());
            String sourceKey = "command:" + UUID.randomUUID();
            SettlementView settlement = service.registerCandidate(context.getSource().getLevel(),
                    SettlementCandidate.manual(context.getSource().getLevel().dimension(), anchor, radius,
                            MANUAL_DETECTOR, sourceKey, proposedName));
            // Inside a recognised village the candidate joins it: one village, one settlement.
            boolean joined = settlement.createdGameTime() != context.getSource().getLevel().getGameTime()
                    || settlement.detectionSource() != dev.otectus.mcakingdoms.api.DetectionSource.MANUAL
                    || proposedName != null && !proposedName.isBlank() && !settlement.displayName().equals(proposedName.strip());
            context.getSource().sendSuccess(() -> Component.translatable(joined ? "command.mcakingdoms.village.joined"
                    : "command.mcakingdoms.village.created", settlement.displayName(), settlement.id().toString()), true);
            return 1;
        });
    }

    private static int rename(CommandContext<CommandSourceStack> context) {
        return mutateSettlement(context, "village", settlement -> {
            String name = StringArgumentType.getString(context, "name");
            SettlementView updated = service(context).rename(settlement.id(), name);
            context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.renamed",
                    updated.displayName()), true);
            return 1;
        });
    }

    private static int setKingdom(CommandContext<CommandSourceStack> context) {
        return mutateSettlement(context, "village", settlement -> {
            ResourceLocation kingdom = ResourceLocationArgument.getId(context, "kingdom");
            if (service(context).getKingdom(kingdom).isEmpty()) {
                return failure(context, "command.mcakingdoms.error.kingdom_missing", kingdom);
            }
            SettlementView updated = service(context).setKingdom(settlement.id(), kingdom);
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.mcakingdoms.village.kingdom_changed", updated.displayName(), kingdom), true);
            return 1;
        });
    }

    private static int reclassify(CommandContext<CommandSourceStack> context) {
        return mutateSettlement(context, "village", settlement -> {
            SettlementView updated = service(context).reclassify(settlement.id());
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.mcakingdoms.village.reclassified", updated.displayName(), updated.kingdomId()), true);
            return 1;
        });
    }

    private static int setLocks(CommandContext<CommandSourceStack> context, boolean locked) {
        return mutateSettlement(context, "village", settlement -> {
            SettlementView updated = service(context).setLocks(settlement.id(), locked, locked);
            context.getSource().sendSuccess(() -> Component.translatable(locked
                    ? "command.mcakingdoms.village.locked" : "command.mcakingdoms.village.unlocked",
                    updated.displayName()), true);
            return 1;
        });
    }

    private static int discover(CommandContext<CommandSourceStack> context, int radiusChunks) {
        return mutate(context, service -> {
            BlockPos center = BlockPos.containing(context.getSource().getPosition());
            Collection<SettlementView> found = service.discover(context.getSource().getLevel(), center, radiusChunks);
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.mcakingdoms.village.discovered", found.size()), true);
            return found.size();
        });
    }

    private static int merge(CommandContext<CommandSourceStack> context) {
        Optional<SettlementView> source = find(context, SettlementSelectorArgument.getSettlement(context, "source"));
        Optional<SettlementView> target = find(context, SettlementSelectorArgument.getSettlement(context, "target"));
        if (source.isEmpty() || target.isEmpty()) {
            return failure(context, "command.mcakingdoms.error.village_missing",
                    source.isEmpty() ? SettlementSelectorArgument.getSettlement(context, "source")
                            : SettlementSelectorArgument.getSettlement(context, "target"));
        }
        return mutate(context, service -> {
            SettlementView merged = service.merge(source.get().id(), target.get().id());
            context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.merged",
                    source.get().displayName(), merged.displayName()), true);
            return 1;
        });
    }

    private static int reconcile(CommandContext<CommandSourceStack> context, int radiusChunks) {
        return mutate(context, service -> {
            if (!(service instanceof dev.otectus.mcakingdoms.core.KingdomsServiceImpl implementation)) {
                throw new IllegalStateException("Village reconciliation is unavailable on this server");
            }
            var level = context.getSource().getLevel();
            BlockPos center = BlockPos.containing(context.getSource().getPosition());
            var report = implementation.reconcileAround(level, center, radiusChunks);
            context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.reconciled",
                    report.merged(), report.villages(), report.unresolved(), report.examined()), true);
            var refused = new java.util.LinkedHashMap<String, dev.otectus.mcakingdoms.core.SettlementReconciler.Refusal>();
            report.refused().forEach(refusal -> refused.putIfAbsent(refusal.source() + ">" + refusal.target(), refusal));
            implementation.refusalsAround(level, center, radiusChunks)
                    .forEach(refusal -> refused.putIfAbsent(refusal.source() + ">" + refusal.target(), refusal));
            refused.values().forEach(refusal -> context.getSource().sendSuccess(() -> Component.translatable(
                    "command.mcakingdoms.village.reconcile_refused", refusal.sourceName(), refusal.targetName(),
                    refusal.reason(), refusal.command()), false));
            return report.merged();
        });
    }

    private static int debug(CommandContext<CommandSourceStack> context, String query) {
        Optional<SettlementView> settlement = query == null ? villageAtSource(context) : find(context, query);
        if (settlement.isEmpty()) return failure(context, query == null
                ? "command.mcakingdoms.error.no_village_here"
                : "command.mcakingdoms.error.village_missing", query == null ? "" : query);
        SettlementView view = settlement.get();
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.debug",
                view.displayName(), view.anchor().toShortString(), view.biomeAtCreation(), view.kingdomId(),
                view.assignmentSource().name(), view.detectionSource().name(), view.revision()), false);
        view.assignmentTrace().forEach(line -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.mcakingdoms.village.trace", line), false));
        var structures = service(context).getSettlementExternalRefs(view.id())
                .getOrDefault(dev.otectus.mcakingdoms.settlement.VillageFootprints.STRUCTURE_REF, java.util.Set.of());
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.debug_structure",
                structures.isEmpty() ? "none" : String.join(", ", structures)), false);
        var level = context.getSource().getServer().getLevel(view.dimension());
        var footprint = level != null && service(context) instanceof dev.otectus.mcakingdoms.core.KingdomsServiceImpl implementation
                ? implementation.villageAt(level, view.anchor()) : Optional.<dev.otectus.mcakingdoms.api.VillageFootprint>empty();
        footprint.ifPresentOrElse(village -> {
            var box = village.box();
            context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.debug_footprint",
                    village.key(), box.minX() + " " + box.minY() + " " + box.minZ(),
                    box.maxX() + " " + box.maxY() + " " + box.maxZ()), false);
        }, () -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.mcakingdoms.village.debug_no_footprint"), false));
        return 1;
    }

    private static int citizenInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        Optional<CivicIdentityView> identity = service(context).getCivicIdentity(entity);
        if (identity.isEmpty()) return failure(context, "command.mcakingdoms.error.no_civic_identity",
                entity.getDisplayName());
        CivicIdentityView view = identity.get();
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.citizen.info",
                entity.getDisplayName(), optional(view.originSettlement()), optional(view.originKingdom()),
                optional(view.residenceSettlement()), optional(view.residenceKingdom()), view.source().name()), false);
        return 1;
    }

    private static int setCitizenSettlement(CommandContext<CommandSourceStack> context, boolean origin)
            throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        Optional<SettlementView> settlement = find(context, SettlementSelectorArgument.getSettlement(context, "village"));
        if (settlement.isEmpty()) return failure(context, "command.mcakingdoms.error.village_missing",
                SettlementSelectorArgument.getSettlement(context, "village"));
        return mutate(context, service -> {
            if (origin) service.setOrigin(entity, settlement.get().id());
            else service.setResidence(entity, settlement.get().id());
            context.getSource().sendSuccess(() -> Component.translatable(origin
                            ? "command.mcakingdoms.citizen.origin_set"
                            : "command.mcakingdoms.citizen.residence_set",
                    entity.getDisplayName(), settlement.get().displayName()), true);
            return 1;
        });
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((ignored, error) ->
                server.execute(() -> {
                    if (error == null) context.getSource().sendSuccess(() -> Component.translatable(
                            "command.mcakingdoms.reload.success"), true);
                    else context.getSource().sendFailure(Component.translatable(
                            "command.mcakingdoms.reload.failure", error.getMessage()));
                }));
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.reload.started"), false);
        return 1;
    }

    private static int sendSettlement(CommandContext<CommandSourceStack> context, SettlementView settlement) {
        if (context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            var knowledge = dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer());
            if (player.level().dimension().equals(settlement.dimension()) && settlement.bounds().contains(player.blockPosition()))
                knowledge.discover(player.getUUID(), settlement.id());
            if (!knowledge.visible(player, settlement.id()))
                return failure(context, "command.mcakingdoms.error.village_missing", "");
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.mcakingdoms.village.info",
                settlement.displayName(), settlement.slug(), settlement.id(), settlement.kingdomId(),
                settlement.dimension().location(), settlement.anchor().toShortString(), settlement.biomeAtCreation(),
                settlement.assignmentSource().name()), false);
        return 1;
    }

    private static Optional<SettlementView> villageAtSource(CommandContext<CommandSourceStack> context) {
        return service(context).getSettlementAt(context.getSource().getLevel(),
                BlockPos.containing(context.getSource().getPosition()));
    }

    private static Optional<SettlementView> find(CommandContext<CommandSourceStack> context, String query) {
        if (context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
            return dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer()).find(player, service(context), query);
        return service(context).findSettlement(query);
    }

    private static KingdomsService service(CommandContext<CommandSourceStack> context) {
        return McaKingdomsApi.get(context.getSource().getServer());
    }

    private static int mutateSettlement(CommandContext<CommandSourceStack> context, String argument,
                                        Function<SettlementView, Integer> operation) {
        Optional<SettlementView> settlement = find(context, SettlementSelectorArgument.getSettlement(context, argument));
        if (settlement.isEmpty()) return failure(context, "command.mcakingdoms.error.village_missing",
                SettlementSelectorArgument.getSettlement(context, argument));
        return mutate(context, ignored -> operation.apply(settlement.get()));
    }

    private static int mutate(CommandContext<CommandSourceStack> context, Function<KingdomsService, Integer> action) {
        try {
            return action.apply(service(context));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return failure(context, "command.mcakingdoms.error.operation", exception.getMessage());
        }
    }

    private static int failure(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendFailure(Component.translatable(key, args));
        return 0;
    }

    private static String optional(Optional<?> value) {
        return value.map(Object::toString).orElse("-");
    }

    private static final SuggestionProvider<CommandSourceStack> KINGDOM_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(service(context).getKingdoms().stream()
                    .map(view -> view.id().toString()), builder);

    private static final SuggestionProvider<CommandSourceStack> SETTLEMENT_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest((context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                    ? dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(player.getServer()).page(player, service(context), Optional.empty(), 0, 64)
                    : service(context).getSettlementPage(Optional.empty(), 0, 64)).stream()
                    .flatMap(view -> java.util.stream.Stream.of(view.slug().toString(), view.id().toString(),
                            StringArgumentType.escapeIfRequired(view.displayName()))), builder);
}
