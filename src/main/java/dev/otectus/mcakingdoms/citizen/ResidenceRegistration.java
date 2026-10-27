package dev.otectus.mcakingdoms.citizen;

import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import dev.otectus.mcakingdoms.core.KingdomsServiceImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Verified player residence registration (A01). A player declares a settlement their civic home and the server checks
 * evidence it owns: the player's respawn point set by sleeping in a bed (not a command-set spawn), in a chunk that is
 * already loaded, still a bed, inside a recognized settlement, while the player stands in that same settlement. A civic
 * evidence provider that speaks for the player (an authoritative player home) takes precedence over the bed. Client
 * coordinates, the world spawn, discovery and passing through are never evidence, and no chunk is loaded to check.
 *
 * <p>Verifying is separate from registering: sleeping, visiting or trading never moves a residence; only the explicit
 * register or move request does. The first registration also sets the origin when there is none (the existing rule);
 * a move keeps the origin. A move waits {@code residenceMoveCooldown} game ticks after the last residence change, on the
 * overworld clock that is shared by all dimensions and continues across restarts; an operator may override that wait.
 * Registering the settlement already recorded is a no-op. Registering grants no office, land, NPC home, guild
 * membership or war duty, and frozen election electorates and existing mandates are not rewritten.
 */
public final class ResidenceRegistration {
    /** Where a request stands; {@link #READY} allows it, {@link #SAME} is an idempotent no-op. */
    public enum Check {
        READY, SAME, DISABLED, NO_BED, COMMAND_SPAWN, BED_UNLOADED, BED_MISSING, BED_OUTSIDE_SETTLEMENT, NOT_PRESENT,
        COOLDOWN, ALREADY_REGISTERED, NOT_REGISTERED
    }

    public enum Intent { REGISTER, MOVE }

    /** The evidence for one player now: the settlement it names, the bed (or provider) and the first problem found. */
    public record Evidence(Check check, Optional<SettlementView> settlement, Optional<BlockPos> bed,
                           Optional<ResourceKey<Level>> dimension, String source) {
        public boolean found() { return check == Check.READY; }
    }

    /** A request reviewed against the evidence and the current record. */
    public record Review(Intent intent, Evidence evidence, Optional<CivicIdentityView> current, Optional<SettlementView> currentResidence,
                         long cooldownRemaining, boolean override, Check decision) {
        /** The first registration also records the origin, when the person has none yet. */
        public boolean setsOrigin() { return current.flatMap(CivicIdentityView::originSettlement).isEmpty(); }
    }

    private ResidenceRegistration() {
    }

    private static KingdomsServiceImpl service(ServerPlayer player) {
        if (!(McaKingdomsApi.get(player.getServer()) instanceof KingdomsServiceImpl impl))
            throw new IllegalStateException("interaction.mcakingdoms.residence.unavailable");
        return impl;
    }

    /** The evidence for {@code player} now. Reads only loaded chunks. */
    public static Evidence evidence(ServerPlayer player) {
        if (!McaKingdomsConfig.PLAYER_REGISTRATION.get())
            return new Evidence(Check.DISABLED, Optional.empty(), Optional.empty(), Optional.empty(), "");
        var service = service(player);
        Optional<SettlementView> here = service.getSettlementAt(player.serverLevel(), player.blockPosition());
        var provided = service.providerEvidence(player).flatMap(hint -> hint.residenceSettlement()).flatMap(service::getSettlement);
        if (provided.isPresent()) {
            boolean present = here.map(SettlementView::id).equals(provided.map(SettlementView::id));
            return new Evidence(present ? Check.READY : Check.NOT_PRESENT, provided, Optional.empty(), Optional.empty(), "provider");
        }
        BlockPos bed = player.getRespawnPosition();
        ResourceKey<Level> dimension = player.getRespawnDimension();
        if (bed == null) return new Evidence(Check.NO_BED, Optional.empty(), Optional.empty(), Optional.empty(), "bed");
        if (player.isRespawnForced()) return new Evidence(Check.COMMAND_SPAWN, Optional.empty(), Optional.of(bed), Optional.of(dimension), "bed");
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null || !level.hasChunkAt(bed))
            return new Evidence(Check.BED_UNLOADED, Optional.empty(), Optional.of(bed), Optional.of(dimension), "bed");
        if (!(level.getBlockState(bed).getBlock() instanceof BedBlock))
            return new Evidence(Check.BED_MISSING, Optional.empty(), Optional.of(bed), Optional.of(dimension), "bed");
        Optional<SettlementView> home = service.getSettlementAt(level, bed);
        if (home.isEmpty()) return new Evidence(Check.BED_OUTSIDE_SETTLEMENT, Optional.empty(), Optional.of(bed), Optional.of(dimension), "bed");
        boolean present = player.level().dimension().equals(dimension) && here.map(SettlementView::id).equals(home.map(SettlementView::id));
        return new Evidence(present ? Check.READY : Check.NOT_PRESENT, home, Optional.of(bed), Optional.of(dimension), "bed");
    }

    /** Reviews a request without changing anything; {@code override} counts only for operators. */
    public static Review review(ServerPlayer player, Intent intent, boolean override) {
        return review(player, intent, override, () -> player.getServer().overworld().getGameTime(), McaKingdomsConfig.RESIDENCE_MOVE_COOLDOWN.get());
    }

    static Review review(ServerPlayer player, Intent intent, boolean override, LongSupplier clock, long cooldown) {
        var service = service(player);
        boolean operatorOverride = override && player.hasPermissions(2);
        Evidence evidence = evidence(player);
        Optional<CivicIdentityView> current = service.getCivicIdentity(player);
        Optional<SettlementView> residence = service.getResidence(player);
        long remaining = 0;
        if (residence.isPresent()) {
            long since = clock.getAsLong() - current.map(CivicIdentityView::lastResidenceChange).orElse(0L);
            remaining = Math.max(0, cooldown - since);
        }
        Check decision;
        if (evidence.check() == Check.DISABLED) decision = Check.DISABLED;
        else if (intent == Intent.REGISTER && residence.isPresent()) decision = Check.ALREADY_REGISTERED;
        else if (intent == Intent.MOVE && residence.isEmpty()) decision = Check.NOT_REGISTERED;
        else if (!evidence.found()) decision = evidence.check();
        else if (residence.isPresent() && residence.get().id().equals(evidence.settlement().orElseThrow().id())) decision = Check.SAME;
        else if (intent == Intent.MOVE && remaining > 0 && !operatorOverride) decision = Check.COOLDOWN;
        else decision = Check.READY;
        return new Review(intent, evidence, current, residence, remaining, operatorOverride, decision);
    }

    /**
     * Registers or moves the player's residence when the review allows it, checking everything again first. Returns the
     * review the change was made under; a {@link Check#SAME} review changes nothing.
     */
    public static Review apply(ServerPlayer player, Intent intent, boolean override) {
        Review review = review(player, intent, override);
        if (review.decision() == Check.READY) service(player).registerResidence(player, review.evidence().settlement().orElseThrow().id());
        return review;
    }

    /** What a review depends on, for the form's staleness check: the evidence, the record and the cooldown. */
    public static String fingerprint(ServerPlayer player, Intent intent) {
        Review review = review(player, intent, false);
        return review.decision() + "|" + review.evidence().settlement().map(SettlementView::id).orElse(null) + "|"
                + review.evidence().bed().orElse(null) + "|" + review.currentResidence().map(SettlementView::id).orElse(null)
                + "|" + review.current().map(CivicIdentityView::lastResidenceChange).orElse(-1L) + "|" + (review.cooldownRemaining() > 0);
    }
}
