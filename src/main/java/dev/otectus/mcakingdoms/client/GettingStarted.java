package dev.otectus.mcakingdoms.client;

import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * The Getting started text in Kingdom Tasks: available before either book is crafted, written for a first session
 * with no operator permission. It describes; it never grants items, residence or authority.
 */
final class GettingStarted {
    private GettingStarted() {
    }

    private static Component help(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("interaction.mcakingdoms.help." + key, fallback, args);
    }

    static List<Component> paragraphs() {
        // MCA must be installed on both sides, so the client's own mod list tells which explanation applies.
        boolean mca = ModList.get().isLoaded("mca");
        return List.of(
                help("intro", "Kingdom Tasks is where you read records and act. Press %s at any time; change the key under Controls, in the MCA: Kingdoms category.",
                        Component.keybind("key.mcakingdoms.actions")),
                help("first_reads", "Start with the three read-only buttons below. Read this settlement names the place you are standing in. Your known settlements lists the places you have discovered. Read a kingdom describes a kingdom and how many of its settlements you know."),
                help("recognition", "Settlements are recognized from village structures and from clusters of village beds, bells and workstations in loaded chunks. Walk into a village: the first time, a banner names it and its kingdom. If nothing is recognized yet, walk further in and try Read this settlement again."),
                help("books", "Two books help. The Book of Kingdoms explains every system: craft it from one book and one paper, in any arrangement. The Village Ledger lists the settlements you have discovered, plus any the server made public, by kingdom: paper across the top row, then paper, book, paper in the middle row, and a compass in the bottom middle."),
                mca ? help("mca", "MCA Reborn is installed: a villager's confirmed home records where they live, and that becomes their civic residence. Their MCA names, families and homes are never rewritten.")
                        : help("no_mca", "MCA Reborn is not installed: settlements, kingdoms, discovery, the Ledger and the built-in records all work without it. MCA would only add villagers' home evidence."),
                help("guild_jobs", "Guild jobs, such as the Lamplighters' service, are quests from your modpack's content and need MCA Quests. If no jobs appear, ask your server operator whether that content is installed; the Book's Help the Lamplighters topic explains it."),
                help("review", "Tasks that change records end on a review page, and nothing happens until you choose Apply reviewed action. Read-only tasks show their result at once."),
                help("optional", "Governments, guild chapters, warfare and world events are optional and depend on this server's settings. Search What is available here? to see which ones it enables."));
    }
}
