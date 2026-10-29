package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.core.BlockPos;

import java.util.*;

/** Immutable copies of provider state. No provider class or live object ever leaves {@link LvAccess} inside one of these. */
public final class LvSnapshots {
    private LvSnapshots() {
    }

    /** One Living Villages village record as read at one moment. {@code capital} is null for a realm's capital. */
    public record Village(UUID id, String name, String team, UUID capital, UUID realmCapital, UUID owner, boolean playerFaction,
                          boolean virtual, BlockPos center, BlockPos ledger, int civilians, int recruits, int treasury, int food,
                          int structures, int builtStructures, String warStage, boolean atWar, boolean besieged,
                          boolean materialized, Optional<UUID> claim) {
        public Village {
            Objects.requireNonNull(id);
            name = name == null ? "" : name;
            team = team == null ? "" : team;
            center = center == null ? BlockPos.ZERO : center.immutable();
            ledger = ledger == null ? null : ledger.immutable();
            warStage = warStage == null ? "" : warStage;
            claim = claim == null ? Optional.empty() : claim;
        }

        public boolean isCapital() {
            return capital == null;
        }
    }

    public record War(UUID attacker, UUID attackerCapital, UUID defender, Set<UUID> attackers, Set<UUID> defenders,
                      int attackerForce, int defenderForce, Set<UUID> participants, Set<UUID> defenceParticipants,
                      UUID initiator, boolean active, boolean undeclared) {
        public War {
            attackers = Set.copyOf(attackers);
            defenders = Set.copyOf(defenders);
            participants = Set.copyOf(participants);
            defenceParticipants = Set.copyOf(defenceParticipants);
        }

        public boolean involves(UUID village) {
            return defender.equals(village) || attackers.contains(village) || defenders.contains(village);
        }
    }

    public record Siege(UUID attacker, UUID defender, int attackerStart, int defenderStart) {
    }

    public record Modifier(String label, int value) {
    }

    /** Reads every village once; virtual player realms are included and flagged. Server thread only. */
    public static List<Village> villages(net.minecraft.server.MinecraftServer server, LvAccess access) {
        var out = new ArrayList<Village>();
        for (Object village : access.villages(server)) out.add(village(server, access, village));
        out.sort(Comparator.comparing(Village::id));
        return List.copyOf(out);
    }

    static Village village(net.minecraft.server.MinecraftServer server, LvAccess access, Object village) {
        UUID id = access.id(village);
        int built = 0;
        var structures = access.structures(village);
        for (Object slot : structures) if (access.slotBuilt(slot)) built++;
        Object capital = access.capitalOf(server, village);
        return new Village(id, access.name(village), access.team(village), access.capitalId(village), access.id(capital),
                access.owner(capital), access.playerFaction(village), access.virtual(village), access.center(village),
                access.ledger(village), access.civilians(village), access.recruits(village), access.treasury(village),
                access.food(village), structures.size(), built, access.warStage(capital), access.atWar(id), access.besieged(id),
                access.materialized(id), access.virtual(village) ? Optional.empty() : access.claimOf(village));
    }
}
