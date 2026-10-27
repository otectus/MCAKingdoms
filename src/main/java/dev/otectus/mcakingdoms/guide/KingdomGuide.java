package dev.otectus.mcakingdoms.guide;

import com.google.gson.*;
import dev.otectus.mcakingdoms.interaction.SearchText;
import net.minecraft.resources.ResourceLocation;

import java.io.Reader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Static, resource-pack-overridable knowledge. Never reads player or world state.
 *
 * <p>Two resource formats are accepted. A legacy (schema 1) entry carries {@code body} paragraphs, {@code commands} and an
 * optional {@code task}; it is adapted to paragraph blocks. A schema 2 file ({@code "schema": 2}) describes each article as
 * typed {@link Block}s: text, numbered steps, live recipes, items, labelled callouts, images, comparisons, diagrams,
 * static requirements, links and the kingdom gallery. An entry gives either {@code body} or {@code blocks}, never both.
 *
 * <p>Nothing here executes: links name guide topics or Kingdom Tasks ids, which the reader opens through the server's
 * reviewed workflow. Remote URLs, commands and world changes cannot be expressed.
 */
public final class KingdomGuide {
    public static final List<String> CATEGORIES = List.of("basics", "settlements", "civic", "politics", "warfare", "evolution", "operators", "reference");
    /** The newest resource format this reader understands. */
    public static final int SCHEMA = 2;
    /** Chapters whose technical content (commands) is shown directly; elsewhere it sits under "Commands and advanced details". */
    public static final Set<String> TECHNICAL_CATEGORIES = Set.of("operators", "reference");

    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,80}");
    static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_]{1,40}");
    static final Pattern TASK = Pattern.compile("[a-z0-9_.]{1,64}");
    /** {@code {key:tasks}} in article text shows the player's current Kingdom Tasks key; no other placeholder exists. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{key:([a-z_]+)}");
    public static final Set<String> KEY_PLACEHOLDERS = Set.of("tasks");
    static final int MAX_BLOCKS = 64;

    /**
     * A named guide topic or Kingdom Tasks entry point; {@code label} may be blank, in which case the target names itself.
     * {@code operator} marks a task that needs permission level 2: other readers see why it is unavailable instead of a
     * dead end. The server checks access again whenever a task opens.
     */
    public record Link(Kind kind, String target, String label, boolean operator) {
        public enum Kind { GUIDE, TASK }
        public Link(Kind kind, String target, String label) { this(kind, target, label, false); }
        public Link {
            if (operator && kind != Kind.TASK) throw new IllegalArgumentException("Only task links can be operator-only");
            Objects.requireNonNull(kind, "kind");
            label = label == null ? "" : label;
            boolean valid = kind == Kind.GUIDE ? ID.matcher(target == null ? "" : target).matches()
                    : "*".equals(target) || TASK.matcher(target == null ? "" : target).matches();
            if (!valid) throw new IllegalArgumentException("Invalid " + kind.name().toLowerCase(Locale.ROOT) + " link target: " + target);
            if (!label.isEmpty()) text(label, 80, "link label");
        }
    }

    /** One article part. {@link #text()} is its plain-text equivalent: what search reads and narration speaks. */
    public sealed interface Block permits Paragraph, Heading, Steps, RecipeBlock, ItemBlock, Callout, Image, Comparison, Diagram, Requirements, Links, Kingdoms, Unavailable {
        String id();
        List<String> text();
        default List<Link> links() { return List.of(); }
    }

    public record Paragraph(String id, String content) implements Block {
        public List<String> text() { return List.of(content); }
    }

    public record Heading(String id, String content) implements Block {
        public List<String> text() { return List.of(content); }
    }

    public record Step(String content, Optional<Link> link) { }

    /** A short numbered procedure. */
    public record Steps(String id, List<Step> items) implements Block {
        public Steps { items = List.copyOf(items); }
        public List<String> text() { return items.stream().map(Step::content).toList(); }
        public List<Link> links() { return items.stream().flatMap(s -> s.link().stream()).toList(); }
    }

    /**
     * An active recipe looked up by id on the connected server; the reader shows what that server actually has.
     * {@code output}, when given, lets a removed recipe point to other recipes for the same item, each under its own id.
     */
    public record RecipeBlock(String id, ResourceLocation recipe, Optional<ResourceLocation> output, String caption) implements Block {
        public List<String> text() { return caption.isEmpty() ? List.of() : List.of(caption); }
    }

    public record ItemBlock(String id, ResourceLocation item, int count, String content) implements Block {
        public List<String> text() { return List.of(content); }
    }

    /** A labelled tip, requirement or consequence. The label is a word, never colour alone. */
    public record Callout(String id, Tone tone, String title, String content) implements Block {
        public enum Tone { TIP, REQUIREMENT, CONSEQUENCE, NOTE, WARNING }
        public List<String> text() { return title.isEmpty() ? List.of(content) : List.of(title, content); }
    }

    /** A packaged texture with required alternative text; {@code width}/{@code height} are GUI units, bounded. */
    public record Image(String id, ResourceLocation texture, int width, int height, int textureWidth, int textureHeight,
                        String alt, String caption) implements Block {
        public List<String> text() { return caption.isEmpty() ? List.of(alt) : List.of(alt, caption); }
    }

    public record Column(String title, Optional<ResourceLocation> item, List<String> points) {
        public Column { points = List.copyOf(points); }
    }

    /** Side-by-side columns (for example the two books); stacked when narrow. {@code alt} is the text equivalent. */
    public record Comparison(String id, List<Column> columns, String alt) implements Block {
        public Comparison { columns = List.copyOf(columns); }
        public List<String> text() {
            var out = new ArrayList<String>();
            out.add(alt);
            columns.forEach(c -> { out.add(c.title()); out.addAll(c.points()); });
            return out;
        }
    }

    public record Node(String id, String label, Optional<ResourceLocation> item, String note) { }

    public record Edge(String from, String to, String label) { }

    /** Labelled boxes and arrows with a required text equivalent; explanatory, never a live map. */
    public record Diagram(String id, List<Node> nodes, List<Edge> edges, boolean vertical, String alt) implements Block {
        public Diagram { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
        public List<String> text() {
            var out = new ArrayList<String>();
            out.add(alt);
            nodes.forEach(n -> { out.add(n.label()); if (!n.note().isEmpty()) out.add(n.note()); });
            edges.forEach(e -> { if (!e.label().isEmpty()) out.add(e.label()); });
            return out;
        }
    }

    public enum State { MET, UNMET, UNKNOWN, EXAMPLE }

    public record Requirement(String label, State state, String detail) { }

    /** Educational requirements, explicitly static; live checks belong to the Guilds screen and Kingdom Tasks. */
    public record Requirements(String id, String title, List<Requirement> rows, String note) implements Block {
        public Requirements { rows = List.copyOf(rows); }
        public List<String> text() {
            var out = new ArrayList<String>();
            if (!title.isEmpty()) out.add(title);
            rows.forEach(r -> { out.add(r.label()); if (!r.detail().isEmpty()) out.add(r.detail()); });
            if (!note.isEmpty()) out.add(note);
            return out;
        }
    }

    public record Links(String id, List<Link> items) implements Block {
        public Links { items = List.copyOf(items); }
        public List<String> text() { return items.stream().map(Link::label).filter(l -> !l.isEmpty()).toList(); }
        public List<Link> links() { return items; }
    }

    /** The server's public kingdom definitions with their heraldry, read when the article opens. */
    public record Kingdoms(String id, String caption) implements Block {
        public List<String> text() { return caption.isEmpty() ? List.of() : List.of(caption); }
    }

    /** Readable stand-in for an optional block this reader could not use; the rest of the article still shows. */
    public record Unavailable(String id, String problem) implements Block {
        public List<String> text() { return List.of(); }
    }

    /** How well a topic matches a search; lower is better. */
    public enum Rank { TITLE, KEYWORDS, SUMMARY, BODY, NONE }

    /**
     * One topic. {@code task} is an optional Kingdom Tasks id this topic leads to; "*" opens the catalogue.
     * {@code operatorTask} marks a task that needs permission level 2; players without it are offered {@code playerTask}
     * (a related read task) when set, or an explanation. The server checks access again whenever a task opens.
     */
    public record Entry(String id, String category, String title, String summary, List<String> keywords, List<Block> blocks,
                        List<String> commands, String task, boolean operatorTask, String playerTask, int schema) {
        public Entry(String id, String category, String title, String summary, List<String> body, List<String> commands) {
            this(id, category, title, summary, body, commands, "");
        }
        public Entry(String id, String category, String title, String summary, List<String> body, List<String> commands, String task) {
            this(id, category, title, summary, body, commands, task, false, "");
        }
        /** A legacy entry: each body paragraph becomes a paragraph block. */
        public Entry(String id, String category, String title, String summary, List<String> body, List<String> commands,
                     String task, boolean operatorTask, String playerTask) {
            this(id, category, title, summary, List.of(), paragraphs(body), commands, task, operatorTask, playerTask, 1);
        }
        public Entry {
            if (id == null || !ID.matcher(id).matches() || !CATEGORIES.contains(category)) throw new IllegalArgumentException("Invalid guide identity");
            text(title, 100, "title");
            text(summary, 400, "summary");
            keywords = List.copyOf(keywords);
            if (keywords.size() > 16) throw new IllegalArgumentException("Too many keywords");
            keywords.forEach(k -> text(k, 40, "keyword"));
            blocks = List.copyOf(blocks);
            commands = List.copyOf(commands);
            task = task == null ? "" : task;
            if (task.length() > 64 || !task.matches("[a-z0-9_.*]*")) throw new IllegalArgumentException("Invalid guide task link");
            playerTask = playerTask == null ? "" : playerTask;
            if (playerTask.length() > 64 || !playerTask.matches("[a-z0-9_.]*")) throw new IllegalArgumentException("Invalid guide player task link");
            if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS || commands.size() > 80) throw new IllegalArgumentException("Invalid guide length");
            var blockIds = new HashSet<String>();
            for (Block block : blocks) if (!blockIds.add(block.id())) throw new IllegalArgumentException("Duplicate block id " + block.id());
            commands.forEach(c -> {
                text(c, 1000, "command");
                if (!c.startsWith("/")) throw new IllegalArgumentException("Guide command must begin with /");
            });
            if (schema != 1 && schema != SCHEMA) throw new IllegalArgumentException("Unsupported guide schema " + schema);
        }

        /** The plain-text equivalent of every block, in reading order (legacy entries: their paragraphs). */
        public List<String> body() {
            return blocks.stream().flatMap(b -> b.text().stream()).map(KingdomGuide::plain).toList();
        }

        /** Every guide and task link named inside the article, in reading order (the primary {@link #task()} excluded). */
        public List<Link> links() {
            return blocks.stream().flatMap(b -> b.links().stream()).toList();
        }

        /** Recipe ids this article displays. */
        public List<ResourceLocation> recipes() {
            return blocks.stream().filter(RecipeBlock.class::isInstance).map(b -> ((RecipeBlock) b).recipe()).toList();
        }

        /** Every query word, or a synonym of it, must occur; case, accents and spacing are ignored. */
        public boolean matches(String query) {
            return rank(SearchText.tokens(query)) != Rank.NONE;
        }

        /** Title first, then keywords, the summary, and finally the body and command reference. */
        public Rank rank(List<String> tokens) {
            if (tokens.isEmpty()) return Rank.TITLE;
            String title = SearchText.normalize(this.title);
            if (SearchText.matches(tokens, title)) return Rank.TITLE;
            String withKeywords = title + " | " + SearchText.normalize(String.join(" | ", keywords));
            if (SearchText.matches(tokens, withKeywords)) return Rank.KEYWORDS;
            String withSummary = withKeywords + " | " + SearchText.normalize(summary);
            if (SearchText.matches(tokens, withSummary)) return Rank.SUMMARY;
            String all = withSummary + " | " + SearchText.normalize(String.join(" ", body()) + " " + String.join(" ", commands));
            return SearchText.matches(tokens, all) ? Rank.BODY : Rank.NONE;
        }

        /** Commands are reference material: shown directly in technical chapters, otherwise collapsed by the reader. */
        public boolean technical() {
            return TECHNICAL_CATEGORIES.contains(category);
        }
    }

    /** The entries of one resource and the readable reason for each part that could not be used. */
    public record ReadResult(int schema, List<Entry> entries, List<String> problems) {
        public ReadResult { entries = List.copyOf(entries); problems = List.copyOf(problems); }
    }

    /** Strict reading for shipped resources and tests: any problem, even in one optional block, is an error. */
    public static List<Entry> read(Reader reader) {
        ReadResult result = parse(reader);
        if (!result.problems().isEmpty()) throw new IllegalArgumentException(String.join("; ", result.problems()));
        return result.entries();
    }

    /**
     * Lenient reading for the in-game reader. A malformed file or an unsupported schema fails the whole resource (the
     * caller names it in the log and keeps every other resource); an invalid entry is skipped; an invalid optional block
     * becomes an {@link Unavailable} stand-in so the rest of its article still reads.
     */
    public static ReadResult parse(Reader reader) {
        JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
        int schema = 1;
        if (object.has("schema")) {
            JsonElement value = object.get("schema");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Guide schema must be a number");
            schema = value.getAsInt();
        }
        if (schema != 1 && schema != SCHEMA) throw new IllegalArgumentException("Unsupported guide schema " + schema + "; this reader understands 1 and " + SCHEMA);
        var entries = object.getAsJsonArray("entries");
        if (entries == null || entries.size() > 128) throw new IllegalArgumentException("Guide file has no entries list or more than 128 entries");
        var result = new ArrayList<Entry>();
        var problems = new ArrayList<String>();
        var ids = new HashSet<String>();
        int index = 0;
        for (var value : entries) {
            index++;
            String where = "entry " + index;
            try {
                if (!value.isJsonObject()) throw new IllegalArgumentException("not an object");
                var e = value.getAsJsonObject();
                if (e.has("id") && e.get("id").isJsonPrimitive()) where = "entry " + e.get("id").getAsString();
                var blockProblems = new ArrayList<String>();
                Entry entry = entry(e, schema, blockProblems);
                if (!ids.add(entry.id())) throw new IllegalArgumentException("duplicate id in this file");
                blockProblems.forEach(p -> problems.add(entry.id() + ": " + p));
                result.add(entry);
            } catch (RuntimeException failure) {
                problems.add(where + ": " + failure.getMessage());
            }
        }
        return new ReadResult(schema, result, problems);
    }

    private static Entry entry(JsonObject e, int schema, List<String> problems) {
        String task = optionalText(e, "task");
        boolean operator = e.has("operator") && e.get("operator").isJsonPrimitive() && e.get("operator").getAsBoolean();
        String playerTask = optionalText(e, "player_task");
        boolean hasBody = e.has("body"), hasBlocks = e.has("blocks");
        if (hasBody && hasBlocks) throw new IllegalArgumentException("gives both body and blocks; use one source of article content");
        if (schema == 1) {
            if (hasBlocks) throw new IllegalArgumentException("blocks need \"schema\": 2");
            return new Entry(required(e, "id"), required(e, "category"), required(e, "title"), required(e, "summary"),
                    strings(e, "body"), strings(e, "commands"), task, operator, playerTask);
        }
        List<Block> blocks;
        if (hasBody) blocks = paragraphs(strings(e, "body"));
        else {
            var array = e.getAsJsonArray("blocks");
            if (array == null) throw new IllegalArgumentException("missing blocks");
            if (array.size() > MAX_BLOCKS) throw new IllegalArgumentException("more than " + MAX_BLOCKS + " blocks");
            blocks = new ArrayList<>();
            var blockIds = new HashSet<String>();
            int position = 0;
            for (JsonElement element : array) {
                position++;
                String fallbackId = "block_" + position;
                try {
                    if (!element.isJsonObject()) throw new IllegalArgumentException("not an object");
                    Block block = block(element.getAsJsonObject());
                    if (!blockIds.add(block.id())) throw new IllegalArgumentException("duplicate block id " + block.id());
                    blocks.add(block);
                } catch (RuntimeException failure) {
                    String id = element.isJsonObject() && element.getAsJsonObject().has("id") && element.getAsJsonObject().get("id").isJsonPrimitive()
                            ? element.getAsJsonObject().get("id").getAsString() : fallbackId;
                    if (!BLOCK_ID.matcher(id).matches() || blockIds.contains(id)) id = fallbackId;
                    while (!blockIds.add(id)) id = id + "_";
                    problems.add("block " + id + ": " + failure.getMessage());
                    blocks.add(new Unavailable(id, failure.getMessage()));
                }
            }
        }
        return new Entry(required(e, "id"), required(e, "category"), required(e, "title"), required(e, "summary"),
                e.has("keywords") ? strings(e, "keywords") : List.of(), blocks,
                e.has("commands") ? strings(e, "commands") : List.of(), task, operator, playerTask, schema);
    }

    private static Block block(JsonObject b) {
        String id = required(b, "id");
        if (!BLOCK_ID.matcher(id).matches()) throw new IllegalArgumentException("invalid block id " + id);
        String type = required(b, "type");
        return switch (type) {
            case "paragraph" -> new Paragraph(id, body(required(b, "text"), 4000));
            case "heading" -> new Heading(id, body(required(b, "text"), 200));
            case "steps" -> {
                var items = array(b, "items", 1, 20);
                var steps = new ArrayList<Step>();
                for (JsonElement item : items) {
                    if (item.isJsonPrimitive()) steps.add(new Step(body(item.getAsString(), 600), Optional.empty()));
                    else if (item.isJsonObject()) {
                        var o = item.getAsJsonObject();
                        steps.add(new Step(body(required(o, "text"), 600), o.has("link") ? Optional.of(link(object(o, "link"))) : Optional.empty()));
                    } else throw new IllegalArgumentException("invalid step");
                }
                yield new Steps(id, steps);
            }
            case "recipe" -> new RecipeBlock(id, location(required(b, "recipe")),
                    b.has("output") ? Optional.of(location(required(b, "output"))) : Optional.empty(), optionalBody(b, "caption", 200));
            case "item" -> {
                int count = b.has("count") ? integer(b, "count", 1, 64) : 1;
                yield new ItemBlock(id, location(required(b, "item")), count, body(required(b, "text"), 600));
            }
            case "callout" -> {
                Callout.Tone tone;
                try { tone = Callout.Tone.valueOf(required(b, "tone").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException unknown) { throw new IllegalArgumentException("unknown callout tone"); }
                yield new Callout(id, tone, optionalBody(b, "title", 100), body(required(b, "text"), 1000));
            }
            case "image" -> {
                ResourceLocation texture = location(required(b, "texture"));
                if (!texture.getPath().startsWith("textures/") || !texture.getPath().endsWith(".png")) throw new IllegalArgumentException("image texture must be a packaged textures/*.png resource");
                int width = integer(b, "width", 1, 256), height = integer(b, "height", 1, 256);
                int textureWidth = b.has("texture_width") ? integer(b, "texture_width", 1, 512) : width;
                int textureHeight = b.has("texture_height") ? integer(b, "texture_height", 1, 512) : height;
                yield new Image(id, texture, width, height, textureWidth, textureHeight, body(required(b, "alt"), 300), optionalBody(b, "caption", 300));
            }
            case "comparison" -> {
                var columns = new ArrayList<Column>();
                for (JsonElement column : array(b, "columns", 2, 4)) {
                    if (!column.isJsonObject()) throw new IllegalArgumentException("invalid column");
                    var o = column.getAsJsonObject();
                    var points = new ArrayList<String>();
                    for (JsonElement point : array(o, "points", 1, 8)) points.add(body(point.getAsString(), 200));
                    columns.add(new Column(body(required(o, "title"), 80), o.has("item") ? Optional.of(location(required(o, "item"))) : Optional.empty(), points));
                }
                yield new Comparison(id, columns, body(required(b, "alt"), 600));
            }
            case "diagram" -> {
                var nodes = new ArrayList<Node>();
                var nodeIds = new HashSet<String>();
                for (JsonElement node : array(b, "nodes", 2, 8)) {
                    if (!node.isJsonObject()) throw new IllegalArgumentException("invalid node");
                    var o = node.getAsJsonObject();
                    String nodeId = required(o, "id");
                    if (!nodeId.matches("[a-z0-9_]{1,24}") || !nodeIds.add(nodeId)) throw new IllegalArgumentException("invalid or duplicate node id " + nodeId);
                    nodes.add(new Node(nodeId, body(required(o, "label"), 60), o.has("item") ? Optional.of(location(required(o, "item"))) : Optional.empty(),
                            optionalBody(o, "note", 120)));
                }
                var edges = new ArrayList<Edge>();
                if (b.has("edges")) for (JsonElement edge : array(b, "edges", 0, 12)) {
                    if (!edge.isJsonObject()) throw new IllegalArgumentException("invalid edge");
                    var o = edge.getAsJsonObject();
                    String from = required(o, "from"), to = required(o, "to");
                    if (!nodeIds.contains(from) || !nodeIds.contains(to)) throw new IllegalArgumentException("edge names an unknown node");
                    edges.add(new Edge(from, to, optionalBody(o, "label", 60)));
                }
                String layout = b.has("layout") ? required(b, "layout") : "row";
                if (!layout.equals("row") && !layout.equals("column")) throw new IllegalArgumentException("layout must be row or column");
                yield new Diagram(id, nodes, edges, layout.equals("column"), body(required(b, "alt"), 600));
            }
            case "requirements" -> {
                var rows = new ArrayList<Requirement>();
                for (JsonElement row : array(b, "rows", 1, 10)) {
                    if (!row.isJsonObject()) throw new IllegalArgumentException("invalid requirement");
                    var o = row.getAsJsonObject();
                    State state;
                    try { state = State.valueOf(required(o, "state").toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException unknown) { throw new IllegalArgumentException("unknown requirement state"); }
                    rows.add(new Requirement(body(required(o, "label"), 100), state, optionalBody(o, "detail", 300)));
                }
                if (b.has("source") && !required(b, "source").equals("static")) throw new IllegalArgumentException("requirements in the Book are static examples; live checks belong to the Guilds screen and Kingdom Tasks");
                yield new Requirements(id, optionalBody(b, "title", 100), rows, optionalBody(b, "note", 300));
            }
            case "links" -> {
                var links = new ArrayList<Link>();
                for (JsonElement link : array(b, "links", 1, 8)) {
                    if (!link.isJsonObject()) throw new IllegalArgumentException("invalid link");
                    links.add(link(link.getAsJsonObject()));
                }
                yield new Links(id, links);
            }
            case "kingdoms" -> new Kingdoms(id, optionalBody(b, "caption", 200));
            default -> throw new IllegalArgumentException("unknown block type " + type);
        };
    }

    private static Link link(JsonObject o) {
        String kind = required(o, "kind");
        Link.Kind parsed = switch (kind) {
            case "guide" -> Link.Kind.GUIDE;
            case "task" -> Link.Kind.TASK;
            default -> throw new IllegalArgumentException("link kind must be guide or task");
        };
        boolean operator = o.has("operator") && o.get("operator").isJsonPrimitive() && o.get("operator").getAsBoolean();
        return new Link(parsed, required(o, "target"), optionalText(o, "label"), operator);
    }

    static List<Block> paragraphs(List<String> body) {
        if (body.isEmpty() || body.size() > 80) throw new IllegalArgumentException("Invalid guide length");
        var blocks = new ArrayList<Block>();
        for (int i = 0; i < body.size(); i++) blocks.add(new Paragraph("p" + (i + 1), body(body.get(i), 4000)));
        return blocks;
    }

    /** Text as a reader sees it, without the key placeholder syntax (used for search and narration fallbacks). */
    public static String plain(String text) {
        return PLACEHOLDER.matcher(text).replaceAll("$1");
    }

    private static String body(String value, int limit) {
        text(value, limit, "text");
        var matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) if (!KEY_PLACEHOLDERS.contains(matcher.group(1))) throw new IllegalArgumentException("unknown placeholder " + matcher.group());
        return value;
    }

    private static String optionalBody(JsonObject object, String key, int limit) {
        if (!object.has(key)) return "";
        return body(required(object, key), limit);
    }

    private static ResourceLocation location(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("invalid resource location " + value);
        return id;
    }

    private static JsonArray array(JsonObject object, String key, int min, int max) {
        if (!(object.get(key) instanceof JsonArray array)) throw new IllegalArgumentException("missing " + key);
        if (array.size() < min || array.size() > max) throw new IllegalArgumentException(key + " needs " + min + " to " + max + " items");
        return array;
    }

    private static JsonObject object(JsonObject object, String key) {
        if (!(object.get(key) instanceof JsonObject value)) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static int integer(JsonObject object, String key, int min, int max) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("missing number " + key);
        int number = value.getAsInt();
        if (number < min || number > max) throw new IllegalArgumentException(key + " must be " + min + " to " + max);
        return number;
    }

    private static String optionalText(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static String required(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Missing text: " + key);
        return value.getAsString();
    }

    private static List<String> strings(JsonObject object, String key) {
        var values = object.getAsJsonArray(key);
        if (values == null) throw new IllegalArgumentException("Missing " + key);
        var out = new ArrayList<String>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid text in " + key);
            out.add(value.getAsString());
        }
        return out;
    }

    static void text(String value, int limit, String what) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid guide " + what);
    }

    private KingdomGuide() {
    }
}
