package dev.otectus.mcakingdoms.compat.townstead;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Capability selection at server attach (spec 5.2): Townstead's api.v1 when it is present, reports generation 1 and the
 * integration may use it; otherwise the 0.7.x facade; MCA's own building list when Townstead is absent or switched off.
 * The v1 backend is constructed by reflection so that no class loaded here links to a Townstead type when Townstead is
 * absent (spec 5.1; {@code TownsteadIsolationTest}).
 */
final class TownsteadBackends {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String API_V1 = "com.aetherianartificer.townstead.api.v1.TownsteadApiV1";
    private static final String V1_BACKEND = "dev.otectus.mcakingdoms.compat.townstead.v1.TownsteadV1Backend";

    private TownsteadBackends() {
    }

    /** The selected backend and, separately, the one that answers building reads for this whole session. */
    record Selection(TownsteadBackend backend, TownsteadBackend buildings, boolean installed) {
    }

    static Selection select(boolean installed, boolean enabled, boolean usePublicApi, boolean internalFallback, String version) {
        TownsteadBackend mcaOnly = new TownsteadLegacyBackend(TownsteadBinding.resolve(false, false), false, version,
                installed ? "Townstead integration switched off by config" : "Townstead is not installed");
        if (!installed || !enabled) return new Selection(mcaOnly, mcaOnly, installed);
        String reason = "Townstead api.v1 not present (0.7.x)";
        if (!usePublicApi) {
            reason = "Townstead api.v1 not used: usePublicApi = false";
        } else if (present(API_V1)) {
            try {
                Object bound = Class.forName(V1_BACKEND).getMethod("bind").invoke(null);
                if (bound instanceof TownsteadBackend v1) {
                    // One building backend per session: a failed v1 list never silently turns into MCA's raw list mid-run (5.2.6).
                    TownsteadBackend buildings = v1.capabilities().contains(dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_BUILDINGS)
                            ? v1 : mcaOnly;
                    if (buildings == mcaOnly) LOGGER.warn("[MCA: Kingdoms] Townstead api.v1 has no usable building list; MCA's own list is used for this session");
                    return new Selection(v1, buildings, true);
                }
                reason = String.valueOf(bound);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                reason = "Townstead api.v1 backend failed to load: " + failure;
            }
            LOGGER.warn("[MCA: Kingdoms] {}; using Townstead's 0.7.x facade", reason);
        }
        TownsteadBackend legacy = new TownsteadLegacyBackend(TownsteadBinding.resolve(true, internalFallback), true, version, reason);
        return new Selection(legacy, legacy, true);
    }

    static boolean present(String className) {
        try {
            Class.forName(className, false, TownsteadBackends.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }
}
