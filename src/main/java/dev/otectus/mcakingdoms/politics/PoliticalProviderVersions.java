package dev.otectus.mcakingdoms.politics;

import java.util.Properties;

/**
 * The Townstead release this build was validated against, expanded from gradle.properties. Reported at
 * start-up beside the installed version; it does not gate anything, because every Townstead binding is
 * probed for the members it reads and fails closed on its own.
 */
public final class PoliticalProviderVersions {
    public static final String TOWNSTEAD = load();
    private PoliticalProviderVersions() { }
    private static String load() {
        try (var stream = PoliticalProviderVersions.class.getResourceAsStream("/mcakingdoms-politics.properties")) {
            if (stream == null) throw new IllegalStateException("Missing political provider contract");
            Properties properties = new Properties(); properties.load(stream);
            String version = properties.getProperty("townstead.read.version");
            if (version == null || version.isBlank() || version.contains("${")) throw new IllegalStateException("Unexpanded political provider contract");
            return version;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read political provider contract", failure); }
    }
}
