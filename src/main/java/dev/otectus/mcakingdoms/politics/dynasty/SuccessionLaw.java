package dev.otectus.mcakingdoms.politics.dynasty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * A government's law of succession (A2). Five laws are built in; a data pack may add more under
 * {@code data/<namespace>/mcakingdoms/succession_laws/<name>.json}, built on one of the two lineage laws (see
 * {@link DynastyDefinitions}). Every
 * government starts with {@code mcakingdoms:manual_only}, the behaviour before 0.3.0, and changes only when its leader
 * (or an operator) adopts another law. An elective law never replaces an elected office by lineage.
 */
public record SuccessionLaw(ResourceLocation id, Mode mode, String titleKey, boolean designatedFirst, boolean lineage, Confirmation confirmation,
                            boolean minorRegency, boolean playersEligible, boolean npcsEligible, Set<Lineage.Relation> relations) {
    public enum Mode { MANUAL_ONLY, DESIGNATED_HEIR_THEN_LINEAGE, EQUAL_PRIMOGENITURE, ELECTIVE_MONARCHY, COUNCIL_CONFIRMATION, CUSTOM_DATAPACK }
    public enum Confirmation { NONE, COUNCIL }

    public SuccessionLaw {
        Objects.requireNonNull(id); Objects.requireNonNull(mode); Objects.requireNonNull(confirmation);
        relations = relations.isEmpty() ? Set.of() : EnumSet.copyOf(relations);
        if (titleKey == null || !titleKey.matches("[a-z0-9_.]{1,128}")) throw new IllegalArgumentException("Invalid succession law title key");
    }

    public static final ResourceLocation MANUAL = new ResourceLocation("mcakingdoms", "manual_only");
    private static final Set<Lineage.Relation> FAMILY = EnumSet.of(Lineage.Relation.CHILD, Lineage.Relation.GRANDCHILD, Lineage.Relation.SIBLING, Lineage.Relation.RECOGNIZED);
    private static final Map<ResourceLocation, SuccessionLaw> BUILT_IN = new LinkedHashMap<>();
    static {
        for (var law : List.of(
                new SuccessionLaw(MANUAL, Mode.MANUAL_ONLY, "dynasty.mcakingdoms.law.manual_only", false, false, Confirmation.NONE, false, true, true, Set.of()),
                new SuccessionLaw(new ResourceLocation("mcakingdoms", "designated_then_lineage"), Mode.DESIGNATED_HEIR_THEN_LINEAGE, "dynasty.mcakingdoms.law.designated_then_lineage", true, true, Confirmation.NONE, true, true, true, FAMILY),
                new SuccessionLaw(new ResourceLocation("mcakingdoms", "equal_primogeniture"), Mode.EQUAL_PRIMOGENITURE, "dynasty.mcakingdoms.law.equal_primogeniture", false, true, Confirmation.NONE, true, true, true, FAMILY),
                new SuccessionLaw(new ResourceLocation("mcakingdoms", "elective_monarchy"), Mode.ELECTIVE_MONARCHY, "dynasty.mcakingdoms.law.elective_monarchy", false, false, Confirmation.NONE, false, true, true, Set.of()),
                new SuccessionLaw(new ResourceLocation("mcakingdoms", "council_confirmation"), Mode.COUNCIL_CONFIRMATION, "dynasty.mcakingdoms.law.council_confirmation", true, true, Confirmation.COUNCIL, true, true, true, FAMILY)))
            BUILT_IN.put(law.id(), law);
    }
    private static volatile Map<ResourceLocation, SuccessionLaw> custom = Map.of();

    /** Every law the server knows now, built-in first. */
    public static Map<ResourceLocation, SuccessionLaw> all() {
        var out = new LinkedHashMap<>(BUILT_IN);
        custom.forEach(out::putIfAbsent);
        return out;
    }

    public static Optional<SuccessionLaw> get(ResourceLocation id) {
        return Optional.ofNullable(all().get(id));
    }

    /** Parses one data-pack law; every field is checked and anything unknown is refused with the file named. */
    static SuccessionLaw parse(ResourceLocation id, JsonObject json) {
        for (String key : json.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException(id + ": unknown field " + key + "; allowed fields: " + new TreeSet<>(FIELDS));
        if (!json.has("schema") || !json.get("schema").isJsonPrimitive() || json.get("schema").getAsInt() != 1) throw new IllegalArgumentException(id + ": schema must be 1");
        String base = string(json, "base", "");
        boolean designated = switch (base) {
            case "designated_then_lineage" -> true;
            case "equal_primogeniture" -> false;
            default -> throw new IllegalArgumentException(id + ": base must be designated_then_lineage or equal_primogeniture");
        };
        String title = string(json, "title", "");
        if (!title.matches("[a-z0-9_.]{1,128}")) throw new IllegalArgumentException(id + ": title must be a translation key of lower-case letters, digits, dots and underscores");
        String confirmation = string(json, "confirmation", "none");
        if (!Set.of("none", "council").contains(confirmation)) throw new IllegalArgumentException(id + ": confirmation must be none or council");
        var relations = EnumSet.noneOf(Lineage.Relation.class);
        if (!json.has("relations") || !json.get("relations").isJsonArray()) throw new IllegalArgumentException(id + ": relations must be an array");
        var array = json.getAsJsonArray("relations");
        if (array.size() > 8) throw new IllegalArgumentException(id + ": relations may list at most 8 entries");
        for (JsonElement value : array) {
            try { relations.add(Lineage.Relation.valueOf(value.getAsString().toUpperCase(Locale.ROOT))); }
            catch (RuntimeException unknown) { throw new IllegalArgumentException(id + ": unknown relation " + value); }
        }
        if (relations.contains(Lineage.Relation.ELECTED) || relations.contains(Lineage.Relation.CLAIMANT) || relations.contains(Lineage.Relation.DESIGNATED))
            throw new IllegalArgumentException(id + ": relations may name only child, grandchild, sibling and recognized");
        if (relations.stream().noneMatch(Lineage.Relation::lineage)) throw new IllegalArgumentException(id + ": relations must name child, grandchild or sibling");
        return new SuccessionLaw(id, Mode.CUSTOM_DATAPACK, title, designated, true, confirmation.equals("council") ? Confirmation.COUNCIL : Confirmation.NONE,
                bool(json, "minor_regency", true), bool(json, "players_eligible", true), bool(json, "npcs_eligible", true), relations);
    }

    private static final Set<String> FIELDS = Set.of("schema", "base", "title", "confirmation", "relations", "minor_regency", "players_eligible", "npcs_eligible");

    private static String string(JsonObject json, String key, String fallback) {
        if (!json.has(key)) return fallback;
        if (!json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        return json.get(key).getAsString();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        if (!json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be true or false");
        return json.get(key).getAsBoolean();
    }

    /** Installs the data-pack laws a reload accepted; built-in ids were refused before this point. */
    static void install(Map<ResourceLocation, SuccessionLaw> laws) {
        custom = Map.copyOf(laws);
    }

    static boolean builtIn(ResourceLocation id) {
        return BUILT_IN.containsKey(id);
    }

    /** For validation commands: whether {@code id} names a built-in law. */
    public static boolean builtInId(ResourceLocation id) { return builtIn(id); }

    /** For validation commands: parses a data-pack law without installing it; throws with the reason when invalid. */
    public static SuccessionLaw validate(ResourceLocation id, JsonElement json) {
        if (!json.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object");
        return parse(id, json.getAsJsonObject());
    }
}
