package dev.otectus.mcakingdoms.politics;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.McaKingdoms;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.politics.Politics.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** An isolated Serenum government whose local stewards sit in both settlements, so the preflight refuses merging them. */
public final class MergeVetoFixtures {
    private MergeVetoFixtures() {
    }

    public static GovernmentService stewardsInBoth(ServerLevel level, KingdomsService kingdoms, UUID capital, UUID other) {
        var data = new PoliticalSavedData();
        Set<UUID> known = new HashSet<>();
        var politics = new GovernmentService(level.getServer(), kingdoms, McaKingdoms.POLITICS, data, false, known::contains);
        var leader = new net.minecraftforge.common.util.FakePlayer(level, new GameProfile(UUID.randomUUID(), "VetoLeader")) {
            @Override
            public boolean hasPermissions(int permission) {
                return permission <= 2;
            }
        };
        var steward = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "VetoSteward"));
        known.addAll(List.of(leader.getUUID(), steward.getUUID()));
        require(politics, leader, Action.BOOTSTRAP, "mcakingdoms:serenum_charter", capital, leader);
        require(politics, leader, Action.APPOINT, "mcakingdoms:local_steward", capital, leader);
        require(politics, leader, Action.APPOINT, "mcakingdoms:local_steward", other, steward);
        return politics;
    }

    private static void require(GovernmentService politics, ServerPlayer actor, Action action, String definition, UUID target,
                                ServerPlayer person) {
        Result result = politics.execute(actor, new Request(UUID.randomUUID(), politics.revision(), action, "mcakingdoms:serenum",
                definition, target.toString(), new Person(person.getUUID(), Kind.PLAYER), "", "", "", 0, 0, 0));
        if (!result.success()) throw new IllegalStateException(action + " failed: " + result.message());
    }
}
