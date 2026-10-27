package dev.otectus.mcakingdoms.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.otectus.mcakingdoms.interaction.ServerFacts;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /kingdoms}, {@code /kingdoms help [topic]} and {@code /kingdoms health}. Help prints syntax only: clicking a line
 * suggests it in the chat box and never runs it. Existing command trees and their parsing are unchanged.
 */
public final class KingdomsHelpCommands {
    private record Topic(String summary, List<String> syntax, boolean operator) { }

    /** Topic name to its syntax lines. Names with spaces are quoted; a new name at the end is typed without quotes. */
    private static final Map<String, Topic> TOPICS = topics();

    private KingdomsHelpCommands() {
    }

    private static Map<String, Topic> topics() {
        Map<String, Topic> topics = new LinkedHashMap<>();
        topics.put("quoting", new Topic("Quote a settlement or record name that contains spaces. A new name at the end of create or rename takes the rest of the line and is not quoted.",
                List.of("/kingdoms village info \"Old Bellmeadow\"", "/kingdoms village rename \"Old Bellmeadow\" New Bellmeadow"), false));
        topics.put("village", new Topic("Read settlements; operators also register, rename, reassign, lock, discover, merge, reconcile and inspect them.",
                List.of("/kingdoms village here", "/kingdoms village info [\"settlement name\"]", "/kingdoms village create [radius] [new name]",
                        "/kingdoms village rename \"settlement name\" <new name>", "/kingdoms village setkingdom \"settlement name\" <kingdom id>",
                        "/kingdoms village reclassify \"settlement name\"", "/kingdoms village lock|unlock \"settlement name\"",
                        "/kingdoms village discover [radiusChunks]", "/kingdoms village merge \"record to retire\" \"record to keep\"",
                        "/kingdoms village reconcile [radiusChunks]", "/kingdoms village debug [\"settlement name\"]"), false));
        topics.put("kingdom", new Topic("List the kingdoms and read one of them.", List.of("/kingdoms kingdom list", "/kingdoms kingdom info <kingdom id>"), false));
        topics.put("citizen", new Topic("Inspect or correct a person's civic origin and residence (operators).",
                List.of("/kingdoms citizen info <entity>", "/kingdoms citizen setorigin <entity> \"settlement name\"", "/kingdoms citizen setresidence <entity> \"settlement name\""), true));
        topics.put("politics", new Topic("Read governments; act through offices and mandates. Kingdom Tasks offers every action with named choices and a review.",
                List.of("/kingdoms politics inspect \"kingdom\" [tab]", "/kingdoms politics bootstrap \"kingdom\" \"capital\" \"constitution\" [leader]",
                        "/kingdoms politics appoint \"kingdom\" \"office\" <person> [\"settlement\"]", "/kingdoms politics propose \"kingdom\" \"other kingdom\" \"agreement\" <terms>",
                        "/kingdoms politics sign \"kingdom\" \"agreement\"", "/kingdoms politics election_open \"kingdom\" <first> <second>"), false));
        topics.put("guild", new Topic("Guild membership, requirements and chapter services.",
                List.of("/kingdoms guild", "/kingdoms guild join <organization id>", "/kingdoms guild explain <organization id> <permission id>",
                        "/kingdoms guild introduction|commissions|hospitality|workshop <organization id>", "/kingdoms guild chapters"), false));
        topics.put("faction", new Topic("Kingdom standing maintenance (operators).", List.of("/kingdoms faction diagnostics", "/kingdoms faction get <player> <kingdom id>",
                "/kingdoms faction migration dry_run"), true));
        topics.put("warfare", new Topic("Military control, campaigns and accords; needs Recruits 1.15.2 for military actions.",
                List.of("/kingdoms warfare here", "/kingdoms contract …", "/kingdoms mobilization …", "/kingdoms world …"), false));
        topics.put("evolution", new Topic("World evolution, protection pacts, recruit transfers, world events and organization history.",
                List.of("/kingdoms evolution …", "/kingdoms protection …", "/kingdoms transfer …", "/kingdoms drama …"), false));
        topics.put("reload", new Topic("Reload data packs and report each definition domain as accepted or rejected (operators).", List.of("/kingdoms reload"), true));
        topics.put("health", new Topic("Server health: switches, providers, saved data, last reload, migration and reconciliation (operators).",
                List.of("/kingdoms health"), true));
        return Map.copyOf(topics);
    }

    /** Topic name to its English summary, for coverage checks of the bundled translations. */
    static Map<String, String> topicSummaries() {
        Map<String, String> summaries = new LinkedHashMap<>();
        TOPICS.forEach((name, topic) -> summaries.put(name, topic.summary()));
        return summaries;
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdoms")
                .executes(context -> overview(context.getSource()))
                .then(Commands.literal("help")
                        .executes(context -> overview(context.getSource()))
                        .then(Commands.argument("topic", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(TOPICS.keySet(), builder))
                                .executes(KingdomsHelpCommands::topic)))
                .then(Commands.literal("health").requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerFacts.asComponents(ServerFacts.forOperator(context.getSource().getServer()))
                                    .forEach(line -> context.getSource().sendSuccess(() -> line, false));
                            return 1;
                        })));
    }

    private static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("command.mcakingdoms.help." + key, fallback, args);
    }

    private static int overview(CommandSourceStack source) {
        source.sendSuccess(() -> t("title", "MCA: Kingdoms commands. Kingdom Tasks (K by default) does everything here with named choices and a review page.")
                .copy().withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> t("topics", "Topics: %s. Use /kingdoms help <topic>.", String.join(", ", TOPICS.keySet().stream().sorted().toList())), false);
        source.sendSuccess(() -> t("quoting", "Quote names with spaces: /kingdoms village info \"Old Bellmeadow\". A new name at the end is not quoted: /kingdoms village rename \"Old Bellmeadow\" New Bellmeadow"), false);
        return 1;
    }

    private static int topic(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "topic").toLowerCase(Locale.ROOT);
        Topic topic = TOPICS.get(name);
        if (topic == null) {
            context.getSource().sendFailure(t("unknown", "No help topic %s. Topics: %s.", name, String.join(", ", TOPICS.keySet().stream().sorted().toList())));
            return 0;
        }
        context.getSource().sendSuccess(() -> t("topic." + name, topic.summary()).copy().withStyle(ChatFormatting.GOLD), false);
        if (topic.operator() && !context.getSource().hasPermission(2))
            context.getSource().sendSuccess(() -> t("operator_only", "These commands need operator permission (level 2)."), false);
        for (String line : topic.syntax()) {
            // Clicking copies the line into the chat box to be completed; it never runs a command.
            context.getSource().sendSuccess(() -> Component.literal("  " + line).withStyle(Style.EMPTY
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, line.replaceAll(" [<\\[\"].*$", " ")))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, t("suggest", "Click to put this in the chat box; nothing runs until you send it.")))), false);
        }
        return 1;
    }
}
