package dev.otectus.mcakingdoms.client.rich;

import dev.otectus.mcakingdoms.client.HeraldryRenderer;
import dev.otectus.mcakingdoms.client.VanillaGui;
import dev.otectus.mcakingdoms.client.rich.RichPanel.Hover;
import dev.otectus.mcakingdoms.client.rich.RichPanel.Piece;
import dev.otectus.mcakingdoms.client.rich.RichPanel.Target;
import dev.otectus.mcakingdoms.guide.GuideRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The pieces {@link RichPanel} lays out. Every visual has a text equivalent for narration; colour is always paired with
 * a word (Ready, Needed, Tip…) so meaning never depends on colour alone. Item icons are Minecraft's own item rendering
 * with its tooltips, so resource packs apply.
 */
public final class Pieces {
    public static final int TEXT = 0xFFFFFF;
    public static final int MUTED = 0xC0C0C0;
    public static final int HEADING = 0xFFE080;
    public static final int GOOD = 0x55FF55;
    public static final int BAD = 0xFF7070;
    public static final int UNKNOWN = 0xFFFF55;
    static final ResourceLocation CRAFTING = new ResourceLocation("minecraft", "textures/gui/container/crafting_table.png");

    private Pieces() {
    }

    static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("menu.mcakingdoms.rich." + key, fallback, args);
    }

    /** The tooltip a hovered item shows: Minecraft's own item tooltip lines. */
    static List<FormattedCharSequence> itemTooltip(ItemStack stack) {
        var lines = Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
        return lines.stream().map(Component::getVisualOrderText).toList();
    }

    static List<FormattedCharSequence> textTooltip(Component text) {
        return Minecraft.getInstance().font.split(text, 220);
    }

    // ---------------------------------------------------------------- text

    /** Wrapped text with an optional left indent and colour. */
    public static Piece text(String anchor, Component text) {
        return new Text(anchor, text, TEXT, 0);
    }

    public static Piece text(String anchor, Component text, int color, int indent) {
        return new Text(anchor, text, color, indent);
    }

    public static Piece heading(String anchor, Component text) {
        return new Text(anchor, text.copy().withStyle(ChatFormatting.BOLD), HEADING, 0);
    }

    public static Piece spacer(int height) {
        return new Piece("") {
            @Override protected int layout(Font font, int width) { return height; }
            @Override protected void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, Hover hover) { }
            @Override protected Component narration() { return Component.empty(); }
        };
    }

    static final class Text extends Piece {
        private final Component text;
        private final int color, indent;
        private List<FormattedCharSequence> lines = List.of();

        Text(String anchor, Component text, int color, int indent) {
            super(anchor);
            this.text = text;
            this.color = color;
            this.indent = indent;
        }

        @Override protected int layout(Font font, int width) {
            lines = text.getString().isEmpty() ? List.of() : font.split(text, Math.max(16, width - indent));
            return lines.size() * lineHeight(font) + (manuscript && color == HEADING ? 4 : 0);
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x + indent, y + i * lineHeight(font), color, false);
            if (manuscript && color == HEADING) g.fill(x, y + lines.size() * lineHeight(font), x + Math.min(width, 64), y + lines.size() * lineHeight(font) + 1, 0xFFBA9A66);
        }

        @Override protected Component narration() { return text; }
    }

    // ---------------------------------------------------------------- steps

    /** One numbered step and its optional action (a guide link or a task). */
    public record Step(Component text, Target action) { }

    public static Piece steps(String anchor, List<Step> steps) {
        return new Steps(anchor, steps);
    }

    static final class Steps extends Piece {
        private final List<Step> steps;
        private final List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
        private final List<Integer> tops = new ArrayList<>();
        private int numberWidth;

        Steps(String anchor, List<Step> steps) {
            super(anchor);
            this.steps = List.copyOf(steps);
        }

        @Override protected int layout(Font font, int width) {
            wrapped.clear();
            tops.clear();
            numberWidth = font.width(steps.size() + ". ") + 2;
            int y = 0;
            for (Step step : steps) {
                tops.add(y);
                var lines = font.split(step.text(), Math.max(16, width - numberWidth));
                wrapped.add(lines);
                y += lines.size() * lineHeight(font);
                if (step.action() != null) {
                    int w = Math.min(width - numberWidth, Math.max(80, font.width(step.action().label) + 24));
                    target(step.action().at(numberWidth, y + 2, w, 20));
                    y += 24;
                }
                y += 3;
            }
            return Math.max(0, y - 3);
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < steps.size(); i++) {
                int top = y + tops.get(i);
                drawText(g, font, (i + 1) + ".", x, top, HEADING, false);
                var lines = wrapped.get(i);
                for (int l = 0; l < lines.size(); l++) drawText(g, font, lines.get(l), x + numberWidth, top + l * lineHeight(font), TEXT, false);
            }
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty();
            for (int i = 0; i < steps.size(); i++) out.append(t("step", "Step %s: %s", i + 1, steps.get(i).text())).append(". ");
            return out;
        }
    }

    // ---------------------------------------------------------------- items

    /** A real item icon (with its tooltip) beside wrapped text. */
    public static Piece item(String anchor, ItemStack stack, Component text) {
        return new ItemRow(anchor, stack, text);
    }

    static final class ItemRow extends Piece {
        private final ItemStack stack;
        private final Component text;
        private List<FormattedCharSequence> lines = List.of();

        ItemRow(String anchor, ItemStack stack, Component text) {
            super(anchor);
            this.stack = stack;
            this.text = text;
        }

        @Override protected int layout(Font font, int width) {
            lines = font.split(text, Math.max(16, width - 22));
            return Math.max(18, lines.size() * lineHeight(font));
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(font, stack, x + 1, y + 1);
            for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x + 22, y + i * lineHeight(font) + (lines.size() == 1 ? 5 : 0), TEXT, false);
            if (mx >= 0 && mx < 18 && my >= 0 && my < 18 && !stack.isEmpty()) hover.set(itemTooltip(stack));
        }

        @Override protected Component narration() {
            return stack.isEmpty() ? text : Component.empty().append(stack.getHoverName()).append(": ").append(text);
        }
    }

    // ---------------------------------------------------------------- callouts

    public enum Tone { TIP, REQUIREMENT, CONSEQUENCE, NOTE, WARNING }

    /** A labelled callout: the label is a word ("Tip:"), the bar colour only repeats it. */
    public static Piece callout(String anchor, Tone tone, Component title, Component text) {
        return new Callout(anchor, tone, title, text);
    }

    static Component toneLabel(Tone tone) {
        return switch (tone) {
            case TIP -> t("tone.tip", "Tip");
            case REQUIREMENT -> t("tone.requirement", "Requirement");
            case CONSEQUENCE -> t("tone.consequence", "What changes");
            case NOTE -> t("tone.note", "Note");
            case WARNING -> t("tone.warning", "Caution");
        };
    }

    static int toneColor(Tone tone) {
        return switch (tone) {
            case TIP -> GOOD;
            case REQUIREMENT -> 0x55FFFF;
            case CONSEQUENCE -> 0xFFAA00;
            case NOTE -> MUTED;
            case WARNING -> BAD;
        };
    }

    static final class Callout extends Piece {
        private final Tone tone;
        private final Component title, text;
        private List<FormattedCharSequence> lines = List.of();

        Callout(String anchor, Tone tone, Component title, Component text) {
            super(anchor);
            this.tone = tone;
            this.title = title;
            this.text = text;
        }

        private Component full() {
            MutableComponent label = toneLabel(tone).copy();
            if (title != null && !title.getString().isEmpty()) label = t("tone.titled", "%s — %s", label, title).copy();
            return Component.empty().append(label.withStyle(ChatFormatting.BOLD).withStyle(s -> s.withColor(toneColor(tone)))).append(": ").append(text);
        }

        @Override protected int layout(Font font, int width) {
            lines = font.split(full(), Math.max(16, width - 8));
            return lines.size() * lineHeight(font) + 4;
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            g.fill(x, y, x + 2, y + lines.size() * lineHeight(font) + 4, 0xFF000000 | ink(toneColor(tone)));
            for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x + 7, y + 2 + i * lineHeight(font), TEXT, false);
        }

        @Override protected Component narration() { return full(); }
    }

    // ---------------------------------------------------------------- images

    /** A packaged texture scaled to bounded GUI units; {@code alt} is narrated, the caption shown. */
    public static Piece image(String anchor, ResourceLocation texture, int width, int height, int textureWidth, int textureHeight, Component alt, Component caption) {
        return new ImagePiece(anchor, texture, width, height, textureWidth, textureHeight, alt, caption, false);
    }

    /** Decorative frontispieces opt into a mat; resource-pack diagrams and tiny icons retain every source pixel. */
    public static Piece framedImage(String anchor, ResourceLocation texture, int width, int height, int textureWidth, int textureHeight, Component alt) {
        return new ImagePiece(anchor, texture, width, height, textureWidth, textureHeight, alt, null, true);
    }

    static final class ImagePiece extends Piece {
        private final ResourceLocation texture;
        private final int w, h, tw, th;
        private final Component alt, caption;
        private final boolean framed;
        private List<FormattedCharSequence> lines = List.of();
        private int shownWidth, shownHeight;

        ImagePiece(String anchor, ResourceLocation texture, int w, int h, int tw, int th, Component alt, Component caption, boolean framed) {
            super(anchor);
            this.texture = texture;
            this.w = w;
            this.h = h;
            this.tw = tw;
            this.th = th;
            this.alt = alt;
            this.caption = caption;
            this.framed = framed;
        }

        /** Whether the texture exists; a resource pack that removed it gets the description in its place, never a missing-texture pattern. */
        private Boolean present;
        private List<FormattedCharSequence> fallback = List.of();

        @Override protected int layout(Font font, int width) {
            if (present == null) present = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
            lines = caption == null || caption.getString().isEmpty() ? List.of() : font.split(caption, width);
            if (!present) {
                fallback = font.split(t("image.missing", "[Picture: %s]", alt), width);
                shownWidth = width; shownHeight = fallback.size() * lineHeight(font);
                return shownHeight + (lines.isEmpty() ? 0 : 3 + lines.size() * lineHeight(font));
            }
            shownWidth = Math.min(w, width);
            shownHeight = h * shownWidth / Math.max(1, w);
            return shownHeight + (lines.isEmpty() ? 0 : 3 + lines.size() * lineHeight(font));
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            if (Boolean.FALSE.equals(present)) {
                for (int i = 0; i < fallback.size(); i++) drawText(g, font, fallback.get(i), x, y + i * lineHeight(font), MUTED, false);
                for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x, y + shownHeight + 3 + i * lineHeight(font), MUTED, false);
                return;
            }
            g.blit(texture, x, y, shownWidth, shownHeight, 0, 0, tw, th, tw, th);
            if (manuscript && framed) dev.otectus.mcakingdoms.client.book.BookSkin.illustrationFrame(g, x, y, shownWidth, shownHeight);
            for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x, y + shownHeight + 3 + i * lineHeight(font), MUTED, false);
            if (mx >= 0 && mx < shownWidth && my >= 0 && my < shownHeight) hover.set(textTooltip(alt));
        }

        @Override protected Component narration() {
            return caption == null || caption.getString().isEmpty() ? t("image", "Image: %s", alt) : t("image.caption", "Image: %s. %s", alt, caption);
        }
    }

    // ---------------------------------------------------------------- comparison

    public record Column(Component title, ItemStack icon, List<Component> points) { }

    /** Side-by-side columns, stacked when the field is too narrow for them. */
    public static Piece comparison(String anchor, List<Column> columns, Component alt) {
        return new Comparison(anchor, columns, alt);
    }

    static final class Comparison extends Piece {
        private final List<Column> columns;
        private final Component alt;
        private final List<List<FormattedCharSequence>> titles = new ArrayList<>(), bodies = new ArrayList<>();
        private boolean stacked;
        private int columnWidth;
        private final List<Integer> heights = new ArrayList<>();

        Comparison(String anchor, List<Column> columns, Component alt) {
            super(anchor);
            this.columns = List.copyOf(columns);
            this.alt = alt;
        }

        @Override protected int layout(Font font, int width) {
            titles.clear();
            bodies.clear();
            heights.clear();
            stacked = width / columns.size() < 110;
            columnWidth = stacked ? width : (width - 6 * (columns.size() - 1)) / columns.size();
            int total = 0, tallest = 0;
            for (Column column : columns) {
                int textWidth = columnWidth - 8 - (column.icon().isEmpty() ? 0 : 20);
                var title = font.split(column.title().copy().withStyle(ChatFormatting.BOLD), Math.max(16, textWidth));
                var body = new ArrayList<FormattedCharSequence>();
                for (Component point : column.points()) body.addAll(font.split(t("bullet", "• %s", point), Math.max(16, columnWidth - 8)));
                titles.add(title);
                bodies.add(body);
                int h = 4 + Math.max(column.icon().isEmpty() ? 0 : 18, title.size() * lineHeight(font)) + 3 + body.size() * lineHeight(font) + 4;
                heights.add(h);
                total += h + 4;
                tallest = Math.max(tallest, h);
            }
            return stacked ? total - 4 : tallest;
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            int cx = x, cy = y;
            int tallest = heights.stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                int h = stacked ? heights.get(i) : tallest;
                g.renderOutline(cx, cy, columnWidth, h, manuscript ? 0xFFBA9A66 : 0xFF808080);
                int textX = cx + 4;
                if (!column.icon().isEmpty()) {
                    g.renderItem(column.icon(), cx + 4, cy + 4);
                    if (mx - (cx - x) >= 4 && mx - (cx - x) < 20 && my - (cy - y) >= 4 && my - (cy - y) < 20) hover.set(itemTooltip(column.icon()));
                    textX += 20;
                }
                var title = titles.get(i);
                int titleTop = cy + 4 + (column.icon().isEmpty() || title.size() > 1 ? 0 : 4);
                for (int l = 0; l < title.size(); l++) drawText(g, font, title.get(l), textX, titleTop + l * lineHeight(font), HEADING, false);
                int bodyTop = cy + 4 + Math.max(column.icon().isEmpty() ? 0 : 18, title.size() * lineHeight(font)) + 3;
                var body = bodies.get(i);
                for (int l = 0; l < body.size(); l++) drawText(g, font, body.get(l), cx + 4, bodyTop + l * lineHeight(font), TEXT, false);
                if (stacked) cy += h + 4;
                else cx += columnWidth + 6;
            }
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty().append(alt);
            for (Column column : columns) {
                out.append(". ").append(column.title()).append(": ");
                for (Component point : column.points()) out.append(point).append("; ");
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- diagram

    public record Node(String id, Component label, ItemStack icon, Component note) { }

    public record Edge(String from, String to, Component label) { }

    /**
     * Labelled boxes joined by arrows, in a row when it fits and otherwise in a column. Edge labels are listed in words
     * below the boxes, so the relationships stay readable at any width and without seeing the arrows.
     */
    public static Piece diagram(String anchor, List<Node> nodes, List<Edge> edges, boolean vertical, Component alt) {
        return new Diagram(anchor, nodes, edges, vertical, alt);
    }

    static final class Diagram extends Piece {
        private final List<Node> nodes;
        private final List<Edge> edges;
        private final boolean preferVertical;
        private final Component alt;
        private boolean vertical;
        private boolean folded;
        private int boxWidth, boxHeight;
        private final List<List<FormattedCharSequence>> labels = new ArrayList<>(), notes = new ArrayList<>();
        private List<FormattedCharSequence> edgeLines = List.of();
        private static final int ARROW = 18;

        Diagram(String anchor, List<Node> nodes, List<Edge> edges, boolean vertical, Component alt) {
            super(anchor);
            this.nodes = List.copyOf(nodes);
            this.edges = List.copyOf(edges);
            this.preferVertical = vertical;
            this.alt = alt;
        }

        private boolean joined(int i) {
            String a = nodes.get(i).id(), b = nodes.get(i + 1).id();
            return edges.stream().anyMatch(e -> e.from().equals(a) && e.to().equals(b) || e.from().equals(b) && e.to().equals(a));
        }

        @Override protected int layout(Font font, int width) {
            int n = nodes.size();
            int rowBox = (width - ARROW * (n - 1)) / n;
            vertical = preferVertical || rowBox < 72;
            folded = vertical && !preferVertical && width >= 220 && n > 2;
            boxWidth = folded ? (width - ARROW) / 2 : vertical ? Math.min(width, 220) : rowBox;
            labels.clear();
            notes.clear();
            int tallest = 0;
            for (Node node : nodes) {
                int textWidth = Math.max(16, boxWidth - 8 - (node.icon().isEmpty() ? 0 : 18));
                var label = font.split(node.label().copy().withStyle(ChatFormatting.BOLD), textWidth);
                var note = node.note() == null || node.note().getString().isEmpty() ? List.<FormattedCharSequence>of() : font.split(node.note(), Math.max(16, boxWidth - 8));
                labels.add(label);
                notes.add(note);
                tallest = Math.max(tallest, 6 + Math.max(node.icon().isEmpty() ? 0 : 16, label.size() * lineHeight(font)) + (note.isEmpty() ? 0 : 2 + note.size() * lineHeight(font)) + 4);
            }
            boxHeight = tallest;
            MutableComponent words = Component.empty();
            boolean first = true;
            for (Edge edge : edges) {
                if (edge.label() == null || edge.label().getString().isEmpty()) continue;
                if (!first) words.append("\n");
                first = false;
                words.append(t("edge", "%s → %s: %s", label(edge.from()), label(edge.to()), edge.label()));
            }
            edgeLines = first ? List.of() : splitLines(font, words, width);
            int rows = folded ? (n + 1) / 2 : vertical ? n : 1;
            int shape = rows * boxHeight + (rows - 1) * ARROW;
            return shape + (edgeLines.isEmpty() ? 0 : 4 + edgeLines.size() * lineHeight(font));
        }

        private Component label(String id) {
            return nodes.stream().filter(n -> n.id().equals(id)).findFirst().map(Node::label).orElse(Component.literal(id));
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < nodes.size(); i++) {
                int row = folded ? i / 2 : vertical ? i : 0;
                int column = folded ? (row % 2 == 0 ? i % 2 : 1 - i % 2) : vertical ? 0 : i;
                int bx = x + column * (boxWidth + ARROW), by = y + row * (boxHeight + ARROW);
                g.fill(bx, by, bx + boxWidth, by + boxHeight, manuscript ? 0x18A17B42 : 0xFF202020);
                g.renderOutline(bx, by, boxWidth, boxHeight, manuscript ? 0xFFBA9A66 : 0xFFA0A0A0);
                Node node = nodes.get(i);
                int textX = bx + 4;
                if (!node.icon().isEmpty()) {
                    g.renderItem(node.icon(), bx + 3, by + 4);
                    textX += 18;
                }
                var label = labels.get(i);
                for (int l = 0; l < label.size(); l++) drawText(g, font, label.get(l), textX, by + 5 + l * lineHeight(font), HEADING, false);
                var note = notes.get(i);
                int noteTop = by + 5 + Math.max(node.icon().isEmpty() ? 0 : 16, label.size() * lineHeight(font)) + 2;
                for (int l = 0; l < note.size(); l++) drawText(g, font, note.get(l), bx + 4, noteTop + l * lineHeight(font), MUTED, false);
                if (i + 1 < nodes.size() && joined(i)) {
                    boolean down = folded ? i % 2 == 1 : vertical;
                    boolean left = folded && !down && row % 2 == 1;
                    String arrow = down ? "↓" : left ? "←" : "→";
                    int ax = down ? bx + boxWidth / 2 - font.width(arrow) / 2 : left ? bx - (ARROW + font.width(arrow)) / 2 : bx + boxWidth + (ARROW - font.width(arrow)) / 2;
                    int ay = down ? by + boxHeight + (ARROW - 8) / 2 : by + boxHeight / 2 - 4;
                    drawText(g, font, arrow, ax, ay, TEXT, false);
                }
            }
            int rows = folded ? (nodes.size() + 1) / 2 : vertical ? nodes.size() : 1;
            int shape = rows * boxHeight + (rows - 1) * ARROW;
            for (int l = 0; l < edgeLines.size(); l++) drawText(g, font, edgeLines.get(l), x, y + shape + 4 + l * lineHeight(font), MUTED, false);
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty().append(alt);
            for (Edge edge : edges) if (edge.label() != null && !edge.label().getString().isEmpty())
                out.append(". ").append(t("edge", "%s → %s: %s", label(edge.from()), label(edge.to()), edge.label()));
            return out;
        }
    }

    static List<FormattedCharSequence> splitLines(Font font, Component text, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Component line : split(text)) out.addAll(font.split(line, Math.max(16, width)));
        return out;
    }

    /** Splits a component at its newline characters, keeping each part's style. */
    public static List<Component> split(Component component) {
        List<Component> lines = new ArrayList<>();
        MutableComponent[] line = {Component.empty()};
        component.visit((style, piece) -> {
            String[] parts = piece.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) { lines.add(line[0]); line[0] = Component.empty(); }
                if (!parts[i].isEmpty()) line[0].append(Component.literal(parts[i]).withStyle(style));
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        lines.add(line[0]);
        return lines;
    }

    // ---------------------------------------------------------------- requirements and fields

    /** {@code NOTE} is information beside the conditions (which route applies, another way to qualify): no status word. */
    public enum Check { MET, UNMET, UNKNOWN, EXAMPLE, NOTE }

    public record Row(Component label, Check check, Component detail) { }

    /** "Ready", "Needed", "Could not check" or "Example", each with a mark and a colour, before every requirement. */
    public static Component checkWord(Check check) {
        return switch (check) {
            case MET -> t("check.met", "✓ Ready");
            case UNMET -> t("check.unmet", "✗ Needed");
            case UNKNOWN -> t("check.unknown", "? Could not check");
            case EXAMPLE -> t("check.example", "• Example");
            case NOTE -> Component.literal("•");
        };
    }

    static int checkColor(Check check) {
        return switch (check) {
            case MET -> GOOD;
            case UNMET -> BAD;
            case UNKNOWN -> UNKNOWN;
            case EXAMPLE, NOTE -> MUTED;
        };
    }

    public static Piece requirements(String anchor, Component title, List<Row> rows, Component note) {
        return new Requirements(anchor, title, rows, note);
    }

    static final class Requirements extends Piece {
        private final Component title, note;
        private final List<Row> rows;
        private final List<FormattedCharSequence> lines = new ArrayList<>();
        private final List<Integer> colors = new ArrayList<>();

        Requirements(String anchor, Component title, List<Row> rows, Component note) {
            super(anchor);
            this.title = title;
            this.rows = List.copyOf(rows);
            this.note = note;
        }

        @Override protected int layout(Font font, int width) {
            lines.clear();
            colors.clear();
            if (title != null && !title.getString().isEmpty()) add(font.split(title.copy().withStyle(ChatFormatting.BOLD), width), HEADING);
            for (Row row : rows) {
                MutableComponent line = Component.empty().append(checkWord(row.check()).copy().withStyle(s -> s.withColor(checkColor(row.check())).withBold(true)))
                        .append(" ").append(row.label());
                add(font.split(line, width), TEXT);
                if (row.detail() != null && !row.detail().getString().isEmpty()) {
                    for (FormattedCharSequence detail : font.split(row.detail(), Math.max(16, width - 12))) { lines.add(detail); colors.add(-MUTED); }
                }
            }
            if (note != null && !note.getString().isEmpty()) add(font.split(note, width), MUTED);
            return lines.size() * lineHeight(font);
        }

        private void add(List<FormattedCharSequence> value, int color) {
            for (FormattedCharSequence line : value) { lines.add(line); colors.add(color); }
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < lines.size(); i++) {
                int color = colors.get(i);
                drawText(g, font, lines.get(i), x + (color < 0 ? 12 : 0), y + i * lineHeight(font), Math.abs(color), false);
            }
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty();
            if (title != null) out.append(title).append(". ");
            for (Row row : rows) {
                if (row.check() == Check.NOTE) out.append(row.label());
                else out.append(checkWord(row.check())).append(": ").append(row.label());
                if (row.detail() != null && !row.detail().getString().isEmpty()) out.append(", ").append(row.detail());
                out.append(". ");
            }
            if (note != null) out.append(note);
            return out;
        }
    }

    public record Field(Component label, Component value) { }

    /** Labelled values ("State: Proposed"), one per line, wrapped under their label when long. */
    public static Piece fields(String anchor, List<Field> fields) {
        return new Fields(anchor, fields);
    }

    static final class Fields extends Piece {
        private final List<Field> fields;
        private final List<FormattedCharSequence> lines = new ArrayList<>();

        Fields(String anchor, List<Field> fields) {
            super(anchor);
            this.fields = List.copyOf(fields);
        }

        @Override protected int layout(Font font, int width) {
            lines.clear();
            for (Field field : fields) {
                MutableComponent line = Component.empty().append(field.label().copy().withStyle(s -> s.withColor(MUTED).withBold(true))).append(": ").append(field.value());
                for (Component part : split(line)) lines.addAll(font.split(part, Math.max(16, width)));
            }
            return lines.size() * lineHeight(font);
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x, y + i * lineHeight(font), TEXT, false);
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty();
            for (Field field : fields) out.append(field.label()).append(": ").append(field.value()).append(". ");
            return out;
        }
    }

    // ---------------------------------------------------------------- progress

    /**
     * A labelled bar with its numbers: {@code label} names the quantity, {@code current} of {@code target} is written out
     * and the bar only repeats it. A target of zero or less is met by any non-negative value; a negative value is empty.
     */
    public static Piece progress(String anchor, Component label, long current, long target, Component detail) {
        return new Progress(anchor, label, current, target, detail);
    }

    static final class Progress extends Piece {
        private final Component label, detail;
        private final long current, target;
        private List<FormattedCharSequence> lines = List.of(), details = List.of();

        Progress(String anchor, Component label, long current, long target, Component detail) {
            super(anchor);
            this.label = label;
            this.current = current;
            this.target = target;
            this.detail = detail;
        }

        private Component line() {
            return t("progress", "%s: %s of %s", label, current, target);
        }

        double fraction() {
            if (current < 0) return 0;
            if (target <= 0) return 1;
            return Math.min(1.0, (double) current / target);
        }

        @Override protected int layout(Font font, int width) {
            lines = font.split(line(), width);
            details = detail == null || detail.getString().isEmpty() ? List.of() : font.split(detail, width);
            return lines.size() * lineHeight(font) + 2 + 6 + (details.isEmpty() ? 0 : 2 + details.size() * lineHeight(font));
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < lines.size(); i++) drawText(g, font, lines.get(i), x, y + i * lineHeight(font), TEXT, false);
            int barTop = y + lines.size() * lineHeight(font) + 2, barWidth = Math.min(width, 200);
            g.fill(x, barTop, x + barWidth, barTop + 6, manuscript ? 0xFFD7C39B : 0xFF555555);
            if (manuscript) g.renderOutline(x, barTop, barWidth, 6, 0xFF92703B);
            int filled = (int) Math.round(fraction() * (barWidth - 2));
            if (filled > 0) g.fill(x + 1, barTop + 1, x + 1 + filled, barTop + 5,
                    manuscript ? fraction() >= 1 ? 0xFF355D35 : 0xFF285865 : fraction() >= 1 ? 0xFF55FF55 : 0xFF55AAFF);
            for (int i = 0; i < details.size(); i++) drawText(g, font, details.get(i), x, barTop + 8 + i * lineHeight(font), MUTED, false);
        }

        @Override protected Component narration() {
            return detail == null ? line() : Component.empty().append(line()).append(". ").append(detail);
        }
    }

    // ---------------------------------------------------------------- actions

    /** In-place actions (links, task entry points), one per row, each a vanilla-looking button inside the field. */
    public static Piece actions(String anchor, List<Target> actions) {
        return new Actions(anchor, actions);
    }

    static final class Actions extends Piece {
        private final List<Target> actions;

        Actions(String anchor, List<Target> actions) {
            super(anchor);
            this.actions = List.copyOf(actions);
        }

        @Override protected int layout(Font font, int width) {
            int y = 0;
            for (Target action : actions) {
                int w = Math.min(width, Math.max(120, font.width(action.label) + 24));
                target(action.at(0, y, w, 20));
                y += 22;
            }
            return Math.max(0, y - 2);
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (Target action : actions) if (action.hint != null && !action.hint.getString().isBlank() && action.contains(mx, my)) hover.set(textTooltip(action.hint));
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty();
            for (Target action : actions) out.append(t("action", "Action: %s", action.label)).append(". ");
            return out;
        }
    }

    public static Target button(String key, Component label, Component hint, Runnable action, boolean active) {
        return new Target(key, label, hint, action, active, Target.Style.BUTTON);
    }

    // ---------------------------------------------------------------- kingdoms

    public record KingdomTile(ResourceLocation heraldry, int color, Component name, Component detail) { }

    /** Heraldry tiles with names beside them; the name always accompanies the colour and emblem. */
    public static Piece kingdoms(String anchor, Supplier<List<KingdomTile>> tiles, Component status) {
        return new Kingdoms(anchor, tiles, status);
    }

    static final class Kingdoms extends Piece {
        private final Supplier<List<KingdomTile>> source;
        private final Component status;
        private List<KingdomTile> tiles = List.of();
        private int columns, tileWidth;
        private List<FormattedCharSequence> statusLines = List.of();
        private static final int TILE_HEIGHT = 34;

        Kingdoms(String anchor, Supplier<List<KingdomTile>> tiles, Component status) {
            super(anchor);
            this.source = tiles;
            this.status = status;
        }

        @Override protected int layout(Font font, int width) {
            tiles = source.get();
            columns = Math.max(1, Math.min(3, width / 130));
            tileWidth = (width - 4 * (columns - 1)) / columns;
            statusLines = status == null || status.getString().isEmpty() ? List.of() : font.split(status, width);
            int rows = (tiles.size() + columns - 1) / columns;
            return statusLines.size() * lineHeight(font) + rows * (TILE_HEIGHT + 4);
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            for (int i = 0; i < statusLines.size(); i++) drawText(g, font, statusLines.get(i), x, y + i * lineHeight(font), MUTED, false);
            int top = y + statusLines.size() * lineHeight(font);
            for (int i = 0; i < tiles.size(); i++) {
                KingdomTile tile = tiles.get(i);
                int tx = x + (i % columns) * (tileWidth + 4), ty = top + (i / columns) * (TILE_HEIGHT + 4);
                g.renderOutline(tx, ty, tileWidth, TILE_HEIGHT, 0xFF000000 | tile.color());
                if (manuscript) HeraldryRenderer.renderInBook(g, tile.heraldry(), tx + 6, ty + 6, 22);
                else HeraldryRenderer.render(g, tile.heraldry(), tile.color(), tx + 6, ty + 6, 22);
                var name = Language.getInstance().getVisualOrder(font.ellipsize(tile.name().copy().withStyle(ChatFormatting.BOLD), tileWidth - 40));
                drawText(g, font, name, tx + 34, ty + 7, HEADING, false);
                var detail = Language.getInstance().getVisualOrder(font.ellipsize(tile.detail(), tileWidth - 40));
                drawText(g, font, detail, tx + 34, ty + 19, MUTED, false);
                if (mx >= tx - x && mx < tx - x + tileWidth && my >= ty - y && my < ty - y + TILE_HEIGHT)
                    hover.set(textTooltip(Component.empty().append(tile.name()).append("\n").append(tile.detail())));
            }
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty();
            if (status != null) out.append(status).append(". ");
            for (KingdomTile tile : source.get()) out.append(tile.name()).append(", ").append(tile.detail()).append(". ");
            return out;
        }
    }

    // ---------------------------------------------------------------- recipes

    /**
     * A recipe as Minecraft draws a crafting grid: real item icons in vanilla slots, the arrow and the result with its
     * count, followed by its station, arrangement and grouped ingredients in words. A choice between several items shows
     * one at a time slowly (or only the first with reduced motion) and lists every option in its tooltip and in the text.
     */
    public static Piece recipe(String anchor, GuideRecipe recipe, Component caption, boolean reducedMotion) {
        return new Recipe(anchor, recipe, caption, reducedMotion);
    }

    static final class Recipe extends Piece {
        private final GuideRecipe recipe;
        private final Component caption;
        private final boolean reducedMotion;
        private List<FormattedCharSequence> words = List.of();
        private int columns, rows, graphicWidth, graphicHeight;
        private static final int PAD = 5;

        Recipe(String anchor, GuideRecipe recipe, Component caption, boolean reducedMotion) {
            super(anchor);
            this.recipe = recipe;
            this.caption = caption;
            this.reducedMotion = reducedMotion;
        }

        private boolean drawn() {
            return recipe.status() == GuideRecipe.Status.AVAILABLE;
        }

        @Override protected int layout(Font font, int width) {
            if (drawn()) {
                columns = recipe.layout() == GuideRecipe.Layout.SHAPED ? recipe.width() : recipe.layout() == GuideRecipe.Layout.SINGLE ? 1 : Math.min(3, recipe.slots().size());
                rows = recipe.layout() == GuideRecipe.Layout.SHAPED ? recipe.height() : (recipe.slots().size() + columns - 1) / columns;
                graphicWidth = PAD + columns * 18 + 8 + 22 + 8 + 26 + PAD;
                graphicHeight = PAD + Math.max(rows * 18, 26) + PAD;
            } else {
                graphicWidth = 0;
                graphicHeight = 0;
            }
            MutableComponent text = Component.empty();
            boolean first = true;
            if (caption != null && !caption.getString().isEmpty()) { text.append(caption.copy().withStyle(ChatFormatting.BOLD)); first = false; }
            List<Component> description = recipe.describe();
            for (int i = 0; i < description.size(); i++) {
                // The grid carries shaped placement visually. Its complete row-by-row equivalent stays in narration.
                if (manuscript && drawn() && recipe.layout() == GuideRecipe.Layout.SHAPED && i >= 3 && i < 3 + recipe.height()) continue;
                Component line = description.get(i);
                if (!first) text.append("\n");
                first = false;
                text.append(line);
            }
            words = splitLines(font, text, width);
            return (graphicHeight > 0 ? graphicHeight + 4 : 0) + words.size() * lineHeight(font);
        }

        private ItemStack shown(List<ItemStack> options) {
            if (options.isEmpty()) return ItemStack.EMPTY;
            if (options.size() == 1 || reducedMotion) return options.get(0);
            return options.get((int) ((Util.getMillis() / 2000L) % options.size()));
        }

        @Override protected void render(GuiGraphics g, Font font, int x, int y, int width, int mx, int my, Hover hover) {
            if (drawn()) {
                if (manuscript) { g.fill(x, y, x + graphicWidth, y + graphicHeight, 0x25B08A50); g.renderOutline(x, y, graphicWidth, graphicHeight, 0xFFBA9A66); }
                else VanillaGui.panel(g, x, y, graphicWidth, graphicHeight);
                int gridTop = y + PAD + Math.max(0, (26 - rows * 18) / 2), gridLeft = x + PAD;
                for (int i = 0; i < columns * rows; i++) {
                    int sx = gridLeft + (i % columns) * 18, sy = gridTop + (i / columns) * 18;
                    g.blit(CRAFTING, sx, sy, 29, 16, 18, 18);
                    if (i >= recipe.slots().size()) continue;
                    GuideRecipe.Slot slot = recipe.slots().get(i);
                    if (slot.unavailable()) {
                        drawText(g, font, "?", sx + 6, sy + 5, 0xAA0000, false);
                        if (inside(mx, my, sx - x, sy - y, 18)) hover.set(textTooltip(GuideRecipe.name(List.of(), true)));
                        continue;
                    }
                    ItemStack stack = shown(slot.options());
                    if (!stack.isEmpty()) {
                        g.renderItem(stack, sx + 1, sy + 1);
                        if (slot.options().size() > 1) drawText(g, font, "*", sx + 12, sy, 0xFFFF55, true);
                    }
                    if (inside(mx, my, sx - x, sy - y, 18) && !slot.empty()) {
                        if (slot.options().size() > 1) hover.set(textTooltip(GuideRecipe.name(slot.options(), false)));
                        else hover.set(itemTooltip(stack));
                    }
                }
                int arrowX = gridLeft + columns * 18 + 8, middle = y + graphicHeight / 2;
                g.blit(CRAFTING, arrowX, middle - 8, 90, 35, 22, 15);
                int resultX = arrowX + 22 + 8, resultY = middle - 13;
                g.blit(CRAFTING, resultX, resultY, 119, 30, 26, 26);
                g.renderItem(recipe.result(), resultX + 5, resultY + 5);
                g.renderItemDecorations(font, recipe.result(), resultX + 5, resultY + 5);
                if (inside(mx, my, resultX - x, resultY - y, 26)) hover.set(itemTooltip(recipe.result()));
            }
            int textTop = y + (graphicHeight > 0 ? graphicHeight + 4 : 0);
            for (int i = 0; i < words.size(); i++) drawText(g, font, words.get(i), x, textTop + i * lineHeight(font), TEXT, false);
        }

        private static boolean inside(int mx, int my, int x, int y, int size) {
            return mx >= x && mx < x + size && my >= y && my < y + size;
        }

        @Override protected Component narration() {
            MutableComponent out = Component.empty();
            if (caption != null && !caption.getString().isEmpty()) out.append(caption).append(". ");
            for (Component line : recipe.describe()) out.append(line).append(" ");
            return out;
        }
    }
}
