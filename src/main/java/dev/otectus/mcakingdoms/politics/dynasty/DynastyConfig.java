package dev.otectus.mcakingdoms.politics.dynasty;

import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/** The {@code dynasty} settings, with their defaults before the config loads (unit tests, early start). */
public final class DynastyConfig {
    private DynastyConfig() {
    }

    private static boolean loaded() {
        return McaKingdomsConfig.SPEC.isLoaded();
    }

    private static int value(ForgeConfigSpec.IntValue setting, int fallback) {
        return loaded() ? setting.get() : fallback;
    }

    public static boolean enabled() { return !loaded() || McaKingdomsConfig.DYNASTY.get(); }
    public static boolean announcements() { return !loaded() || McaKingdomsConfig.DYNASTY_ANNOUNCEMENTS.get(); }
    public static long acceptanceTicks() { return value(McaKingdomsConfig.DYNASTY_ACCEPTANCE, 72_000); }
    public static long mourningTicks() { return value(McaKingdomsConfig.DYNASTY_MOURNING, 24_000); }
    public static long claimCooldownTicks() { return value(McaKingdomsConfig.DYNASTY_CLAIM_COOLDOWN, 24_000); }

    /** The legitimacy weights: law, election, council, lineage, appointment, recognition, governance, contest. */
    public record Weights(int law, int election, int council, int lineage, int appointment, int recognition, int governance, int contest) {
        public static final Weights DEFAULT = new Weights(2, 2, 1, 1, 1, 1, 1, 2);
    }

    public static Weights weights() {
        if (!loaded()) return Weights.DEFAULT;
        return new Weights(McaKingdomsConfig.LEGITIMACY_LAW.get(), McaKingdomsConfig.LEGITIMACY_ELECTION.get(), McaKingdomsConfig.LEGITIMACY_COUNCIL.get(),
                McaKingdomsConfig.LEGITIMACY_LINEAGE.get(), McaKingdomsConfig.LEGITIMACY_APPOINTMENT.get(), McaKingdomsConfig.LEGITIMACY_RECOGNITION.get(),
                McaKingdomsConfig.LEGITIMACY_GOVERNANCE.get(), McaKingdomsConfig.LEGITIMACY_CONTEST.get());
    }
}
