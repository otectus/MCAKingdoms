package dev.otectus.mcakingdoms.interaction;

import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Review fingerprints made of named parts (AUD-10). A task's version names exactly what its decision reads (the chosen
 * record and its revision, the player's authority over it, the mode it needs, the items it would take), so unrelated
 * activity on the server no longer sends a player back to review, and when a relevant part did change the form can say
 * which. The owning service still checks everything again when the decision applies; a fingerprint only decides when a
 * review must be repeated.
 */
public final class Review {
    private static final char PART = '\u001f', VALUE = '\u001e';

    private Review() {
    }

    /** A fingerprint from name/value pairs, in order: {@code of("work", id, "state", state)}. */
    public static String of(String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Review parts come in name/value pairs");
        var out = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) out.append(PART);
            out.append(pairs[i]).append(VALUE).append(pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return out.toString();
    }

    /** The parts of a fingerprint by name; empty for a version string that was not made by {@link #of}. */
    static Map<String, String> parts(String version) {
        var out = new LinkedHashMap<String, String>();
        if (version == null || version.indexOf(VALUE) < 0) return out;
        for (String part : version.split(String.valueOf(PART), -1)) {
            int at = part.indexOf(VALUE);
            if (at < 0) return new LinkedHashMap<>();
            out.put(part.substring(0, at), part.substring(at + 1));
        }
        return out;
    }

    /** The names of the parts that differ between two fingerprints, or empty when either is not a named fingerprint. */
    static List<String> changed(String before, String after) {
        var a = parts(before);
        var b = parts(after);
        if (a.isEmpty() || b.isEmpty()) return List.of();
        var out = new ArrayList<String>();
        var names = new LinkedHashSet<String>(); names.addAll(a.keySet()); names.addAll(b.keySet());
        for (String name : names) if (!Objects.equals(a.get(name), b.get(name))) out.add(name);
        return out;
    }

    /** The words for one changed part, in the player's language. */
    static Component label(String part) {
        return Component.translatableWithFallback("interaction.mcakingdoms.review.part." + part, switch (part) {
            case "work" -> "the work itself";
            case "state" -> "its state";
            case "authority" -> "whether you may decide this";
            case "mode" -> "the server's development mode";
            case "inventory" -> "what you carry";
            case "settlement" -> "the settlement";
            case "kingdom" -> "the settlement's kingdom";
            case "consent" -> "whether the settlement allows construction";
            case "place" -> "where you stand";
            case "owed" -> "what you are owed";
            case "waiting" -> "a delivery or refund being confirmed";
            case "bed" -> "your bed";
            case "protect" -> "your choice";
            case "document" -> "the document";
            case "record" -> "the record it proves";
            case "hand" -> "what you hold";
            case "press" -> "the seal press nearby";
            case "stone" -> "the waystone";
            default -> ActionRegistry.words(part);
        });
    }
}
