package dev.otectus.mcakingdoms.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Decides once per JVM whether the Waystones edit observer may be applied (WAY-01): Waystones 14.1.21 whose jar is
 * byte-identical to the tested release. Another build keeps the observer off, so its method can never be missed at
 * runtime; the naming integration then still preserves every name a stone has. The verdict is published as the system
 * property {@link #PROPERTY}.
 */
final class WaystonesGate {
    static final String PROPERTY = "mcakingdoms.waystones.gate";
    static final String TESTED_SHA256 = "fd6fe3c645328ea7be5bc6723d02c881e34650e0cbd4527ba59e42fa4cbe3c80";
    private static volatile String verdict;

    private WaystonesGate() {
    }

    static synchronized String verdict() {
        if (verdict != null) return verdict;
        verdict = decide();
        System.setProperty(PROPERTY, verdict);
        return verdict;
    }

    static boolean applies() {
        return verdict().equals("applied");
    }

    private static String decide() {
        try {
            var mods = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList();
            if (mods == null) return "skipped:loading_list_unavailable";
            var waystones = mods.getMods().stream().filter(m -> m.getModId().equals("waystones")).findFirst();
            if (waystones.isEmpty()) return "skipped:absent";
            String version = waystones.get().getVersion().toString();
            if (!version.equals("14.1.21")) return "skipped:version:" + version;
            Path jar = waystones.get().getOwningFile().getFile().getFilePath();
            if (jar == null || !Files.isRegularFile(jar)) return "skipped:jar_unreadable";
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(jar)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            return hash.equals(TESTED_SHA256) ? "applied" : "skipped:untested_build:" + hash.substring(0, 12);
        } catch (Exception | LinkageError failure) {
            return "skipped:error:" + failure.getClass().getSimpleName();
        }
    }
}
