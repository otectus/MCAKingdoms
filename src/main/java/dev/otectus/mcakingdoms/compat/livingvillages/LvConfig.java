package dev.otectus.mcakingdoms.compat.livingvillages;

import dev.otectus.mcakingdoms.integration.IntegrationConfig;
import dev.otectus.mcakingdoms.integration.IntegrationConfig.LivingVillages;
import net.minecraftforge.common.ForgeConfigSpec;

/** The {@code [livingVillages]} settings, read safely: before the config loads (unit tests, early start) each reads its default. */
public final class LvConfig {
    private LvConfig() {
    }

    private static boolean loaded() {
        return IntegrationConfig.SPEC.isLoaded();
    }

    private static boolean flag(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        return loaded() && value != null ? value.get() : fallback;
    }

    private static int number(ForgeConfigSpec.IntValue value, int fallback) {
        return loaded() && value != null ? value.get() : fallback;
    }

    public static boolean enabled() { return flag(LivingVillages.ENABLED, true); }
    public static boolean autoBind() { return flag(LivingVillages.AUTO_BIND, true); }
    public static boolean autoRealms() { return flag(LivingVillages.AUTO_REALMS, true); }
    public static boolean preserveNames() { return flag(LivingVillages.PRESERVE_NAMES, true); }
    public static boolean autoAdopt() { return flag(LivingVillages.AUTO_ADOPT, false); }
    public static boolean aiWars() { return flag(LivingVillages.AI_WARS, true); }
    public static boolean respectCapitals() { return flag(LivingVillages.RESPECT_CAPITALS, true); }
    public static boolean aiTreaties() { return flag(LivingVillages.AI_TREATIES, true); }
    public static boolean arbitration() { return flag(LivingVillages.ARBITRATION, true); }
    public static boolean persistentResidents() { return flag(LivingVillages.PERSISTENT_RESIDENTS, true); }
    public static boolean mcaResidents() { return flag(LivingVillages.MCA_RESIDENTS, true); }
    public static boolean recruitProtected() { return flag(LivingVillages.RECRUIT_PROTECTED, false); }
    public static boolean adoptedConstruction() { return flag(LivingVillages.ADOPTED_CONSTRUCTION, false); }
    public static boolean protectBuildings() { return flag(LivingVillages.PROTECT_BUILDINGS, true); }
    public static boolean companionContext() { return flag(LivingVillages.COMPANION_CONTEXT, true); }
    public static boolean deduplicate() { return flag(LivingVillages.DEDUPLICATE, true); }
    public static boolean allowUnprotected() { return flag(LivingVillages.ALLOW_UNPROTECTED, false); }
    public static int truceTicks() { return number(LivingVillages.TRUCE_TICKS, 24000); }
    public static int batch() { return number(LivingVillages.BATCH, 8); }
    public static int batchInterval() { return number(LivingVillages.BATCH_INTERVAL, 20); }
    public static int reconcileInterval() { return number(LivingVillages.RECONCILE_INTERVAL, 200); }
    public static int matchDistance() { return number(LivingVillages.MATCH_DISTANCE, 48); }

    public static LvPolicy.PlayerTargets playerTargets() {
        return loaded() && LivingVillages.PLAYER_TARGETS != null && LivingVillages.PLAYER_TARGETS.get() == LivingVillages.PlayerTargetPolicy.PROVIDER_ONLY
                ? LvPolicy.PlayerTargets.PROVIDER_ONLY : LvPolicy.PlayerTargets.PROVIDER_AND_KINGDOMS;
    }

    public static LvPolicy.Capitals capitals() {
        return loaded() && LivingVillages.CAPITALS != null && LivingVillages.CAPITALS.get() == LivingVillages.CapitalPolicy.PROTECT_ALL
                ? LvPolicy.Capitals.PROTECT_ALL : LvPolicy.Capitals.ALLOW_UNLESS_PROTECTED;
    }
}
