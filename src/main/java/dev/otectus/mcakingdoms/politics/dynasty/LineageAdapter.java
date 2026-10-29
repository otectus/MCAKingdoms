package dev.otectus.mcakingdoms.politics.dynasty;

import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadLifeStageView;
import dev.otectus.mcakingdoms.compat.mca.McaFamilyEvidence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.fml.ModList;

import java.util.*;
import java.util.function.Function;

/**
 * Lineage as the server can verify it now (A1): MCA's family tree for parents, partner, children and recorded death;
 * the loaded villager for its life stage (Townstead's life stages when Townstead is installed and readable, otherwise
 * MCA's age state); players are adults. A person MCA does not know is still a node when they are a known player or a
 * loaded living villager, with no family edges. Anything else is unknown, never guessed: an unloaded villager's
 * stage is {@link Lineage.Stage#UNKNOWN}, and without MCA nobody has family.
 */
public final class LineageAdapter {
    /** Test seam: replaces the source for one server (GameTests); null uses MCA. */
    private static volatile Function<MinecraftServer, Lineage.Source> override;

    private LineageAdapter() {
    }

    public static void overrideForTests(Function<MinecraftServer, Lineage.Source> source) {
        override = source;
    }

    public static Lineage.Source source(MinecraftServer server) {
        var replaced = override;
        if (replaced != null) return replaced.apply(server);
        var cache = new HashMap<UUID, Optional<Lineage.Node>>();
        return id -> cache.computeIfAbsent(id, key -> node(server, key));
    }

    private static Optional<Lineage.Node> node(MinecraftServer server, UUID id) {
        var record = McaFamilyEvidence.record(server, id);
        boolean knownPlayer = server.getPlayerList().getPlayer(id) != null || server.getProfileCache() != null && server.getProfileCache().get(id).isPresent();
        Entity entity = loaded(server, id);
        if (record.isEmpty()) {
            if (knownPlayer) return Optional.of(new Lineage.Node(id, Set.of(), Set.of(), null, false, true, Lineage.Stage.ADULT, playerName(server, id)));
            if (entity instanceof LivingEntity living && living.isAlive())
                return Optional.of(new Lineage.Node(id, Set.of(), Set.of(), null, false, false, stage(server, entity), entity.getName().getString()));
            return Optional.empty();
        }
        var r = record.get();
        boolean player = r.player() || knownPlayer;
        Lineage.Stage stage = player ? Lineage.Stage.ADULT : entity == null ? Lineage.Stage.UNKNOWN : stage(server, entity);
        // A loaded villager that is not alive died since MCA last wrote its record; MCA records the death itself.
        return Optional.of(new Lineage.Node(id, r.parents(), r.children(), r.partner(), r.deceased(), player, stage, r.name().isBlank() && player ? playerName(server, id) : r.name()));
    }

    private static Entity loaded(MinecraftServer server, UUID id) {
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) return player;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }

    /** A loaded villager's verified life stage: Townstead's when installed (unknown if unreadable), else MCA's age state. */
    static Lineage.Stage stage(MinecraftServer server, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return Lineage.Stage.UNKNOWN;
        if (ModList.get() != null && ModList.get().isLoaded("townstead")) {
            var townstead = KingdomsTownsteadApi.get(server);
            if (!townstead.capabilities().contains(TownsteadCapability.READ_VILLAGER) || !townstead.capabilities().contains(TownsteadCapability.READ_ORIGIN))
                return Lineage.Stage.UNKNOWN;
            var view = townstead.villager(entity);
            if (view.isEmpty()) return Lineage.Stage.UNKNOWN;
            String presents = view.get().rootId().flatMap(townstead::origin).stream().flatMap(root -> root.lifeStages().stream())
                    .filter(stage -> stage.id().equalsIgnoreCase(view.get().lifeStage())).map(TownsteadLifeStageView::presentsAs).findFirst().orElse("");
            if (presents.isBlank()) return Lineage.Stage.UNKNOWN;
            return Set.of("adult", "senior").contains(presents.toLowerCase(Locale.ROOT)) ? Lineage.Stage.ADULT : Lineage.Stage.MINOR;
        }
        var age = McaFamilyEvidence.ageState(entity);
        if (age.isPresent()) return switch (age.get()) {
            case "ADULT" -> Lineage.Stage.ADULT;
            case "BABY", "TODDLER", "CHILD", "TEEN" -> Lineage.Stage.MINOR;
            default -> Lineage.Stage.UNKNOWN;
        };
        if (entity instanceof Villager villager) return villager.isBaby() ? Lineage.Stage.MINOR : Lineage.Stage.ADULT;
        return Lineage.Stage.UNKNOWN;
    }

    private static String playerName(MinecraftServer server, UUID id) {
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) return player.getGameProfile().getName();
        return server.getProfileCache() == null ? "" : server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName).orElse("");
    }
}
