package dev.otectus.mcakingdoms.development;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * A public work a settlement can undertake (D3), defined by data under
 * {@code data/<namespace>/mcakingdoms/development_templates/<name>.json}: its role, the deliveries it needs, and, for
 * works that can be built, a small structure of at most 64 vanilla-safe blocks inside a 5 x 5 x 5 box. A structure may
 * not contain containers or other block entities (the court noticeboard is this mod's own and holds nothing), beds,
 * doors, redstone components or point-of-interest blocks, so building one never creates storage, a home or a workstation.
 * Works without a structure are funded milestones and are never built.
 */
public record DevelopmentTemplate(ResourceLocation id, Role role, String titleKey, Set<String> kingdoms, DevelopmentMode minimumMode,
                                  List<Demand> demand, List<Block> blocks, Placement placement, int tierPoints, Map<String, Integer> abstractOutput) {
    public enum Role { ROAD, NOTICEBOARD, LIGHTING, WORKSHOP, MARKET, WALL, GARDEN, CIVIC_HALL }
    /** {@code AIR}: every block goes where there is air or a replaceable plant; {@code GROUND}: each block replaces natural ground with air above. */
    public enum Placement { AIR, GROUND }
    /** A delivery the work needs: an item id, or a {@code #tag} that any item of it satisfies. */
    public record Demand(String ingredient, int count) {
        public Demand {
            if (count < 1 || count > 256) throw new IllegalArgumentException("demand count must be 1 to 256");
            String id = ingredient.startsWith("#") ? ingredient.substring(1) : ingredient;
            if (ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("demand " + ingredient + " is not an item id or #tag");
        }
    }
    public record Block(BlockPos offset, BlockState state, String text) { }

    public DevelopmentTemplate {
        demand = List.copyOf(demand); blocks = List.copyOf(blocks); kingdoms = Set.copyOf(kingdoms); abstractOutput = Map.copyOf(abstractOutput);
    }

    public boolean buildable() { return !blocks.isEmpty(); }

    public boolean allowedIn(String kingdom) { return kingdoms.isEmpty() || kingdoms.contains(kingdom); }

    private static final Set<String> FIELDS = Set.of("schema", "role", "title", "kingdoms", "minimum_mode", "demand", "blocks", "placement", "tier_points", "abstract_output");
    static final int MAX_BLOCKS = 64, MAX_SPAN = 5;

    /** For validation commands: parses a template without installing it; throws with the reason when invalid. */
    public static DevelopmentTemplate validate(ResourceLocation id, JsonElement json) {
        if (!json.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object");
        return parse(id, json.getAsJsonObject());
    }

    static DevelopmentTemplate parse(ResourceLocation id, JsonObject json) {
        for (String key : json.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException("unknown field " + key + "; allowed fields: " + new TreeSet<>(FIELDS));
        if (!json.has("schema") || !json.get("schema").isJsonPrimitive() || json.get("schema").getAsInt() != 1) throw new IllegalArgumentException("schema must be 1");
        Role role = enumValue(Role.class, string(json, "role"), "role");
        String title = string(json, "title");
        if (!title.matches("[a-z0-9_.]{1,128}")) throw new IllegalArgumentException("title must be a translation key");
        var kingdoms = new TreeSet<String>();
        if (json.has("kingdoms")) for (JsonElement value : array(json, "kingdoms", 16)) {
            String kingdom = value.getAsString();
            if (ResourceLocation.tryParse(kingdom) == null) throw new IllegalArgumentException("kingdoms: " + kingdom + " is not a kingdom id");
            kingdoms.add(kingdom);
        }
        DevelopmentMode minimum = json.has("minimum_mode") ? enumValue(DevelopmentMode.class, string(json, "minimum_mode"), "minimum_mode") : DevelopmentMode.PROJECTS;
        if (!minimum.atLeast(DevelopmentMode.PROJECTS)) throw new IllegalArgumentException("minimum_mode must be projects, managed_growth or full_economy");
        var demand = new ArrayList<Demand>();
        for (JsonElement value : array(json, "demand", 8)) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("demand entries must be objects");
            var o = value.getAsJsonObject();
            String ingredient = o.has("item") ? string(o, "item") : o.has("tag") ? "#" + string(o, "tag") : "";
            if (ingredient.isEmpty()) throw new IllegalArgumentException("a demand needs item or tag");
            if (!ingredient.startsWith("#") && !BuiltInRegistries.ITEM.containsKey(new ResourceLocation(ingredient))) throw new IllegalArgumentException("unknown item " + ingredient);
            demand.add(new Demand(ingredient, o.has("count") ? o.get("count").getAsInt() : 1));
        }
        if (demand.isEmpty()) throw new IllegalArgumentException("demand must name at least one delivery");
        var blocks = new ArrayList<Block>();
        if (json.has("blocks")) for (JsonElement value : array(json, "blocks", MAX_BLOCKS)) {
            var o = value.getAsJsonObject();
            var offset = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
            if (Math.abs(offset.getX()) >= MAX_SPAN || offset.getY() < 0 || offset.getY() >= MAX_SPAN || Math.abs(offset.getZ()) >= MAX_SPAN)
                throw new IllegalArgumentException("block offsets must stay within the 5 x 5 x 5 box");
            String text = string(o, "state");
            BlockState state;
            try { state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState(); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { throw new IllegalArgumentException("state " + text + ": " + invalid.getMessage()); }
            String refusal = unsafe(state);
            if (refusal != null) throw new IllegalArgumentException("state " + text + " " + refusal);
            if (blocks.stream().anyMatch(b -> b.offset().equals(offset))) throw new IllegalArgumentException("two blocks at " + offset.toShortString());
            blocks.add(new Block(offset, state, text));
        }
        Placement placement = json.has("placement") ? enumValue(Placement.class, string(json, "placement"), "placement") : Placement.AIR;
        int points = json.has("tier_points") ? json.get("tier_points").getAsInt() : 1;
        if (points < 0 || points > 10) throw new IllegalArgumentException("tier_points must be 0 to 10");
        var output = new TreeMap<String, Integer>();
        if (json.has("abstract_output")) for (var entry : json.getAsJsonObject("abstract_output").entrySet()) {
            if (!entry.getKey().matches("[a-z_]{1,32}")) throw new IllegalArgumentException("abstract_output commodity " + entry.getKey() + " must be a lower-case word");
            int amount = entry.getValue().getAsInt();
            if (amount < 1 || amount > 16) throw new IllegalArgumentException("abstract_output amounts must be 1 to 16 per day");
            output.put(entry.getKey(), amount);
        }
        return new DevelopmentTemplate(id, role, title, kingdoms, minimum, demand, blocks, placement, points, output);
    }

    /** Why a block may never be part of a template, or null when it may. */
    static String unsafe(BlockState state) {
        var block = state.getBlock();
        boolean ownNoticeboard = BuiltInRegistries.BLOCK.getKey(block).equals(new ResourceLocation("mcakingdoms", "court_noticeboard"));
        if (block instanceof EntityBlock && !ownNoticeboard) return "has a block entity (storage or machinery)";
        if (state.is(BlockTags.BEDS)) return "is a bed";
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) return "is a door or gate";
        if (state.isSignalSource() || block instanceof net.minecraft.world.level.block.RedStoneWireBlock || block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)
            return "is a redstone component";
        if (net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(state).isPresent()) return "is a point of interest (a workstation, bed or bell)";
        if (state.isAir() || !state.getFluidState().isEmpty()) return "is air or a fluid";
        return null;
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) throw new IllegalArgumentException(key + " must be a string");
        return json.get(key).getAsString();
    }

    private static com.google.gson.JsonArray array(JsonObject json, String key, int limit) {
        if (!json.has(key) || !json.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        var array = json.getAsJsonArray(key);
        if (array.size() > limit) throw new IllegalArgumentException(key + " may list at most " + limit + " entries");
        return array;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { throw new IllegalArgumentException(field + " " + value + " is not one of " + Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT)); }
    }

    // --- loaded templates -----------------------------------------------------------------------------------------

    private static volatile Map<ResourceLocation, DevelopmentTemplate> active = Map.of();

    public static List<DevelopmentTemplate> all() {
        return active.values().stream().sorted(Comparator.comparing(t -> t.id().toString())).toList();
    }

    public static Optional<DevelopmentTemplate> get(ResourceLocation id) { return Optional.ofNullable(active.get(id)); }

    static void install(Map<ResourceLocation, DevelopmentTemplate> templates) { active = Map.copyOf(templates); }

    /** For tests: installs templates directly. */
    public static void installForTests(Map<ResourceLocation, DevelopmentTemplate> templates) { install(templates); }
}
