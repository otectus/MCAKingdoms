package dev.otectus.mcakingdoms.compat.townstead;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The Townstead and MCA pairs this build was validated with (spec 1.1, 16), expanded from {@code gradle.properties}.
 * Reported in diagnostics beside the installed versions; nothing is gated on it, because every binding is probed for the
 * members it uses and fails closed on its own.
 */
final class TownsteadTuples {
    private static final List<String> VALIDATED = load();

    private TownsteadTuples() {
    }

    private static List<String> load() {
        try (var stream = TownsteadTuples.class.getResourceAsStream("/mcakingdoms-politics.properties")) {
            if (stream == null) return List.of();
            Properties properties = new Properties();
            properties.load(stream);
            String value = properties.getProperty("townstead.validated.tuples", "");
            if (value.isBlank() || value.contains("${")) return List.of();
            return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (java.io.IOException unreadable) {
            return List.of();
        }
    }

    /** {@code townstead@mca} pairs, for example {@code 0.8.0+1.20.1@7.7.1-beta.5+1.20.1}. */
    static List<String> validated() {
        return VALIDATED;
    }

    static boolean validated(String townstead, String mca) {
        return VALIDATED.contains(townstead + "@" + mca);
    }
}
