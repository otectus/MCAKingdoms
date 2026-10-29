package dev.otectus.mcakingdoms.politics.dynasty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * A court rank the crown may grant (A3), defined by data under {@code data/<namespace>/mcakingdoms/noble_ranks/<name>.json}.
 * A rank is ceremonial: it names and orders people at court and grants no permission, office, Recruits command or
 * Crime privilege. {@code landed} ranks are granted for one settlement of the kingdom; {@code court} ranks are court
 * functions. Each kingdom may style a rank its own way ({@code styles}: kingdom id to translation key), so no kingdom
 * is given another culture's titles.
 */
public record NobleRank(ResourceLocation id, int tier, boolean landed, boolean court, String titleKey, String addressKey, Map<String, String> styles) {
    public NobleRank {
        Objects.requireNonNull(id);
        if (tier < 1 || tier > 16) throw new IllegalArgumentException("tier must be 1 to 16");
        key(titleKey, "title"); if (!addressKey.isEmpty()) key(addressKey, "address");
        styles = Map.copyOf(styles);
        if (styles.size() > 64) throw new IllegalArgumentException("styles may name at most 64 kingdoms");
        styles.forEach((kingdom, key) -> { if (ResourceLocation.tryParse(kingdom) == null) throw new IllegalArgumentException("styles: " + kingdom + " is not a kingdom id"); key(key, "styles." + kingdom); });
    }

    /** The translation key this kingdom calls the rank by. */
    public String titleKey(String kingdom) {
        return styles.getOrDefault(kingdom, titleKey);
    }

    private static void key(String value, String field) {
        if (value == null || !value.matches("[a-z0-9_.]{1,128}")) throw new IllegalArgumentException(field + " must be a translation key of lower-case letters, digits, dots and underscores");
    }

    private static final Set<String> FIELDS = Set.of("schema", "tier", "landed", "court", "title", "address", "styles");
    private static volatile Map<ResourceLocation, NobleRank> active = Map.of();

    /** The ranks the server knows now, highest first (lowest tier number), then by id. */
    public static List<NobleRank> all() {
        return active.values().stream().sorted(Comparator.comparingInt(NobleRank::tier).thenComparing(r -> r.id().toString())).toList();
    }

    public static Optional<NobleRank> get(ResourceLocation id) {
        return Optional.ofNullable(active.get(id));
    }

    static void install(Map<ResourceLocation, NobleRank> ranks) {
        active = Map.copyOf(ranks);
    }

    /** For validation commands: parses a rank without installing it; throws with the reason when invalid. */
    public static NobleRank validate(ResourceLocation id, JsonElement json) {
        if (!json.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object");
        return parse(id, json.getAsJsonObject());
    }

    static NobleRank parse(ResourceLocation id, JsonObject json) {
        for (String key : json.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException("unknown field " + key + "; allowed fields: " + new TreeSet<>(FIELDS));
        if (!json.has("schema") || !json.get("schema").isJsonPrimitive() || json.get("schema").getAsInt() != 1) throw new IllegalArgumentException("schema must be 1");
        if (!json.has("tier") || !json.get("tier").isJsonPrimitive() || !json.get("tier").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("tier must be a number");
        var styles = new LinkedHashMap<String, String>();
        if (json.has("styles")) {
            if (!json.get("styles").isJsonObject()) throw new IllegalArgumentException("styles must be an object of kingdom id to translation key");
            for (var entry : json.getAsJsonObject("styles").entrySet()) styles.put(entry.getKey(), string(entry.getValue(), "styles." + entry.getKey()));
        }
        return new NobleRank(id, json.get("tier").getAsInt(), bool(json, "landed"), bool(json, "court"),
                json.has("title") ? string(json.get("title"), "title") : "", json.has("address") ? string(json.get("address"), "address") : "", styles);
    }

    private static String string(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(field + " must be a string");
        return value.getAsString();
    }

    private static boolean bool(JsonObject json, String key) {
        if (!json.has(key)) return false;
        if (!json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be true or false");
        return json.get(key).getAsBoolean();
    }
}
