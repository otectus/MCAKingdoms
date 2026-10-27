package dev.otectus.mcakingdoms.client.guide;

import dev.otectus.mcakingdoms.client.rich.Pieces;
import dev.otectus.mcakingdoms.client.book.BookPieces;
import dev.otectus.mcakingdoms.client.rich.RichPanel.Piece;
import dev.otectus.mcakingdoms.client.rich.RichPanel.Target;
import dev.otectus.mcakingdoms.guide.GuideRecipe;
import dev.otectus.mcakingdoms.guide.KingdomGuide;
import dev.otectus.mcakingdoms.guide.KingdomGuide.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Turns one guide topic into {@link Piece}s for the reader. Links become in-place actions; recipes are resolved from the
 * recipe manager the connected server synchronized; the key placeholder shows the player's current Kingdom Tasks key.
 */
public final class GuideArticle {
    /** What the article needs from the screen showing it. */
    public interface Reader {
        Optional<Entry> topic(String id);
        void openTopic(String id);
        void openTask(String task);
        boolean operator();
        boolean commandsShown();
        void toggleCommands();
        boolean reducedMotion();
    }

    private GuideArticle() {
    }

    static Component g(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("guide.mcakingdoms." + key, fallback, args);
    }

    /** Article text with {@code {key:tasks}} replaced by the bound key's name (Minecraft says when it is not bound). */
    public static Component text(String raw) {
        Matcher matcher = KingdomGuide.PLACEHOLDER.matcher(raw);
        if (!matcher.find()) return Component.literal(raw);
        MutableComponent out = Component.empty();
        int position = 0;
        do {
            out.append(raw.substring(position, matcher.start()));
            out.append(Component.keybind("key.mcakingdoms.actions"));
            position = matcher.end();
        } while (matcher.find());
        out.append(raw.substring(position));
        return out;
    }

    public static Component taskTitle(String task) {
        return task.equals("*") ? Component.translatable("interaction.mcakingdoms.catalogue.title")
                : Component.translatableWithFallback("task.mcakingdoms." + task + ".title", task);
    }

    public static List<Piece> build(Entry entry, Reader reader, boolean resourceProblems) {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(BookPieces.opening("summary", text(entry.summary())));
        for (Block block : entry.blocks()) {
            if (block instanceof Paragraph p) pieces.add(Pieces.text(p.id(), text(p.content())));
            else if (block instanceof Heading h) pieces.add(Pieces.heading(h.id(), text(h.content())));
            else if (block instanceof Steps s) {
                List<Pieces.Step> steps = new ArrayList<>();
                for (Step step : s.items()) steps.add(new Pieces.Step(text(step.content()), step.link().map(l -> link(l, reader)).orElse(null)));
                pieces.add(Pieces.steps(s.id(), steps));
            } else if (block instanceof RecipeBlock r) recipe(pieces, r, reader.reducedMotion());
            else if (block instanceof ItemBlock i) pieces.add(Pieces.item(i.id(), stack(i.item(), i.count()), text(i.content())));
            else if (block instanceof Callout c) pieces.add(Pieces.callout(c.id(), Pieces.Tone.valueOf(c.tone().name()), c.title().isEmpty() ? null : text(c.title()), text(c.content())));
            else if (block instanceof Image image) pieces.add(Pieces.image(image.id(), image.texture(), image.width(), image.height(), image.textureWidth(), image.textureHeight(),
                    text(image.alt()), image.caption().isEmpty() ? null : text(image.caption())));
            else if (block instanceof Comparison c) pieces.add(Pieces.comparison(c.id(), c.columns().stream().map(column -> new Pieces.Column(text(column.title()),
                    column.item().map(id -> stack(id, 1)).orElse(ItemStack.EMPTY), column.points().stream().map(GuideArticle::text).toList())).toList(), text(c.alt())));
            else if (block instanceof Diagram d) pieces.add(Pieces.diagram(d.id(), d.nodes().stream().map(n -> new Pieces.Node(n.id(), text(n.label()),
                    n.item().map(id -> stack(id, 1)).orElse(ItemStack.EMPTY), n.note().isEmpty() ? null : text(n.note()))).toList(),
                    d.edges().stream().map(e -> new Pieces.Edge(e.from(), e.to(), e.label().isEmpty() ? null : text(e.label()))).toList(), d.vertical(), text(d.alt())));
            else if (block instanceof Requirements r) {
                MutableComponent note = Component.empty();
                if (!r.note().isEmpty()) note.append(text(r.note())).append(" ");
                note.append(g("requirements.static", "These are examples; your own live checks are on the Guilds screen and in Kingdom Tasks."));
                pieces.add(Pieces.requirements(r.id(), r.title().isEmpty() ? null : text(r.title()), r.rows().stream()
                        .map(row -> new Pieces.Row(text(row.label()), Pieces.Check.valueOf(row.state().name()), row.detail().isEmpty() ? null : text(row.detail()))).toList(), note));
            } else if (block instanceof Links l) pieces.add(Pieces.actions(l.id(), l.items().stream().map(link -> link(link, reader)).toList()));
            else if (block instanceof Kingdoms k) {
                GuideKingdoms.ensure();
                Component caption = k.caption().isEmpty() ? GuideKingdoms.status()
                        : Component.empty().append(text(k.caption())).append(" ").append(GuideKingdoms.status());
                pieces.add(Pieces.kingdoms(k.id(), GuideKingdoms::tiles, caption));
            } else if (block instanceof Unavailable u)
                pieces.add(Pieces.text(u.id(), g("unavailable_block", "Part of this topic could not be shown; the game log names the problem."), Pieces.MUTED, 0));
        }
        boolean operatorOnly = entry.operatorTask() && !reader.operator();
        if (operatorOnly) {
            String alternative = entry.playerTask();
            pieces.add(Pieces.callout("operator_note", Pieces.Tone.NOTE, null, alternative.isEmpty() ? g("operator_note", "%s needs operator permission. Ask an operator, or read this topic to see what it does.", taskTitle(entry.task()))
                    : g("operator_note.related", "%s needs operator permission. You can use %s instead.", taskTitle(entry.task()), taskTitle(alternative))));
        }
        if (!entry.commands().isEmpty()) {
            if (entry.technical()) commands(pieces, entry);
            else {
                Component label = reader.commandsShown() ? g("commands.hide", "Hide commands and advanced details")
                        : g("commands.show", "Commands and advanced details (%s)", entry.commands().size());
                pieces.add(Pieces.actions("commands_toggle", List.of(Pieces.button("commands_toggle", label,
                        g("commands.help.hint", "Optional chat equivalents for automation; Kingdom Tasks does everything without them."), reader::toggleCommands, true))));
                if (reader.commandsShown()) commands(pieces, entry);
            }
        }
        if (resourceProblems) pieces.add(Pieces.text("resource_error", g("resource_error", "Some guide resources could not be read. Other topics remain available; check the resource pack and client log."), Pieces.MUTED, 0));
        return pieces;
    }

    private static void commands(List<Piece> pieces, Entry entry) {
        pieces.add(Pieces.heading("commands", g("commands", "Command reference")));
        MutableComponent lines = Component.empty();
        for (int i = 0; i < entry.commands().size(); i++) {
            if (i > 0) lines.append("\n");
            lines.append(entry.commands().get(i));
        }
        pieces.add(Pieces.text("command_list", lines, Pieces.MUTED, 4));
        pieces.add(Pieces.text("command_help", g("command_help", "Replace <placeholders> before typing a command. This book does not run commands."), Pieces.MUTED, 0));
    }

    private static void recipe(List<Piece> pieces, RecipeBlock block, boolean reducedMotion) {
        var mc = Minecraft.getInstance();
        Optional<Item> output = block.output().map(ForgeRegistries.ITEMS::getValue).filter(item -> item != null && item != Items.AIR);
        GuideRecipe model = mc.level == null ? GuideRecipe.noWorld(block.recipe())
                : GuideRecipe.resolve(mc.level.getRecipeManager(), mc.level.registryAccess(), block.recipe(), output);
        Component caption = block.caption().isEmpty() ? null : text(block.caption());
        pieces.add(Pieces.recipe(block.id(), model, caption, reducedMotion));
        for (int i = 0; i < model.alternatives().size(); i++) {
            GuideRecipe alternative = model.alternatives().get(i);
            pieces.add(Pieces.recipe(block.id() + "_alternative_" + (i + 1), alternative,
                    g("recipe.alternative", "Another recipe on this server for the same item (%s)", alternative.id().toString()), reducedMotion));
        }
    }

    static ItemStack stack(ResourceLocation id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    private static Target link(Link link, GuideArticle.Reader reader) {
        if (link.kind() == Link.Kind.GUIDE) {
            Optional<Entry> topic = reader.topic(link.target());
            Component title = !link.label().isEmpty() ? text(link.label()) : topic.<Component>map(e -> Component.literal(e.title())).orElse(Component.literal(link.target()));
            return Pieces.button("guide:" + link.target(), g("link.guide", "Topic: %s", title),
                    topic.isPresent() ? topic.<Component>map(e -> Component.literal(e.summary())).get() : g("link.missing", "This topic is not in the Book on this server."),
                    () -> reader.openTopic(link.target()), topic.isPresent());
        }
        Component title = !link.label().isEmpty() ? text(link.label()) : taskTitle(link.target());
        if (link.operator() && !reader.operator())
            return Pieces.button("task:" + link.target(), g("link.task.operator", "%s (operators only)", title),
                    g("link.task.operator.hint", "This task needs operator permission; ask an operator if you need it."), () -> { }, false);
        boolean connected = Minecraft.getInstance().player != null;
        return Pieces.button("task:" + link.target(), g("link.task", "Task: %s", title),
                connected ? g("link.task.hint", "Opens the task in Kingdom Tasks; the server checks your access and every choice.")
                        : g("link.task.offline", "Kingdom Tasks needs a connected world."),
                () -> reader.openTask(link.target()), connected);
    }
}
