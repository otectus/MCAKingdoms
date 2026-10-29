package dev.otectus.mcakingdoms.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Decides once per JVM whether the Recruits: Living Villages hooks may be applied: Recruits 1.15.2, Living Villages
 * 1.1.0, and the Living Villages jar byte-identical to the audited release (a version string alone does not rule out a
 * patched jar). The verdict is published as the system property {@link #PROPERTY} so the game layer can report it
 * whichever class loader loaded this class.
 */
final class LvGate {
    static final String PROPERTY = "mcakingdoms.livingvillages.gate";
    static final String AUDITED_SHA256 = "55a7da1802360bbc18337ad190b826a1a2292f3038c623ab09f926bb0746dda8";
    private static volatile String verdict;

    private LvGate() {
    }

    /** "applied", or "skipped:<reason>". */
    static synchronized String verdict() {
        if (verdict != null) return verdict;
        verdict = decide();
        System.setProperty(PROPERTY, verdict);
        return verdict;
    }

    static boolean applies() {
        return verdict().equals("applied");
    }

    /** Redirects that name Minecraft methods by their SRG names only match the production (reobfuscated) runtime. */
    static boolean production() {
        try {
            return net.minecraftforge.fml.loading.FMLLoader.isProduction();
        } catch (RuntimeException | LinkageError unknown) {
            return false;
        }
    }

    private static String decide() {
        try {
            var mods = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList();
            if (mods == null) return "skipped:loading_list_unavailable";
            var recruits = mods.getMods().stream().filter(m -> m.getModId().equals("recruits")).findFirst();
            var villages = mods.getMods().stream().filter(m -> m.getModId().equals("recruitsvillages")).findFirst();
            if (villages.isEmpty()) return "skipped:absent";
            if (recruits.isEmpty() || !recruits.get().getVersion().toString().equals("1.15.2")) return "skipped:recruits_version";
            String version = villages.get().getVersion().toString();
            if (!version.equals("1.1.0")) return "skipped:version:" + version;
            Path jar = villages.get().getOwningFile().getFile().getFilePath();
            if (jar == null || !Files.isRegularFile(jar)) return "skipped:jar_unreadable";
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(jar)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            System.setProperty("mcakingdoms.livingvillages.sha256", hash);
            return hash.equals(AUDITED_SHA256) ? "applied" : "skipped:unaudited_build:" + hash.substring(0, 12);
        } catch (Exception | LinkageError failure) {
            return "skipped:error:" + failure.getClass().getSimpleName();
        }
    }
}
