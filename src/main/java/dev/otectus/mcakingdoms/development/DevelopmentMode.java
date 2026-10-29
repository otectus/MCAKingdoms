package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.config.McaKingdomsConfig;

/**
 * How far settlement development and the economy may act (D1), each mode including the ones before it. {@code OFF} is the
 * default for every world, new or upgraded: nothing is saved, planned or built.
 */
public enum DevelopmentMode {
    OFF, OBSERVE, PROJECTS, MANAGED_GROWTH, FULL_ECONOMY;

    public boolean atLeast(DevelopmentMode other) { return ordinal() >= other.ordinal(); }

    private static volatile DevelopmentMode override;

    /** The server's mode now; {@code OFF} before the config loads. */
    public static DevelopmentMode current() {
        var forced = override;
        if (forced != null) return forced;
        return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_MODE.get() : OFF;
    }

    /** Test seam: a mode for GameTests without rewriting the config file; null restores the setting. */
    public static void overrideForTests(DevelopmentMode mode) { override = mode; }

    public static boolean constructionPaused() { return McaKingdomsConfig.SPEC.isLoaded() && McaKingdomsConfig.DEVELOPMENT_CONSTRUCTION_PAUSED.get(); }
    public static boolean economyPaused() { return McaKingdomsConfig.SPEC.isLoaded() && McaKingdomsConfig.DEVELOPMENT_ECONOMY_PAUSED.get(); }
    static int blocksPerPass() { return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_BLOCKS_PER_PASS.get() : 4; }
    static int maxProjects() { return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_MAX_PROJECTS.get() : 64; }
    static int projectsPerSettlement() { return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_PROJECTS_PER_SETTLEMENT.get() : 4; }
    static int tradeCap() { return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_TRADE_CAP.get() : 8; }
    static int homeRadius() { return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_HOME_RADIUS.get() : 8; }
    static java.util.List<? extends String> dimensions() { return McaKingdomsConfig.SPEC.isLoaded() ? McaKingdomsConfig.DEVELOPMENT_DIMENSIONS.get() : java.util.List.of("minecraft:overworld"); }
}
