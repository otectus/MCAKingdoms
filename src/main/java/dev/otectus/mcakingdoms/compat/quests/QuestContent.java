package dev.otectus.mcakingdoms.compat.quests;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;

/**
 * Whether the quest content a guild names is in the server's loaded data packs. MCA Quests reads quests only from
 * {@code data/<namespace>/mcaquests/quests/<path>.json}, so a missing file is a fact about content, not a guess; a
 * present file may still be rejected by MCA Quests, which only it can report.
 */
public final class QuestContent {
    private QuestContent() {
    }

    public static boolean present(MinecraftServer server, ResourceLocation quest) {
        return server.getResourceManager().getResource(new ResourceLocation(quest.getNamespace(),
                "mcaquests/quests/" + quest.getPath() + ".json")).isPresent();
    }

    /** How many of {@code quests} have a quest file in the loaded data packs. */
    public static long installed(MinecraftServer server, Collection<ResourceLocation> quests) {
        return quests.stream().filter(quest -> present(server, quest)).count();
    }
}
