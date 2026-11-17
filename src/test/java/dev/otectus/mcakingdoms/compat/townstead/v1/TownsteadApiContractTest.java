package dev.otectus.mcakingdoms.compat.townstead.v1;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compile-only Townstead api.v1 jar against the published Townstead 0.8.0 artifact (spec 5.1, 14 A3, 15.1): every
 * Townstead member MCA: Kingdoms links against exists in the released jar with the same descriptor, the bind-time
 * manifest covers every member the backend calls, and no class outside {@code compat/townstead/v1} names a Townstead type
 * (so nothing links to Townstead when it is absent, and the public API never exposes it).
 */
class TownsteadApiContractTest {
    private static final String API = "com/aetherianartificer/townstead/api/v1/";
    private static final String TOWNSTEAD = "com/aetherianartificer/townstead/";
    private static final String V1 = "dev/otectus/mcakingdoms/compat/townstead/v1/";

    private static Path published() {
        String path = System.getProperty("mcakingdoms.townstead.published", "");
        assertFalse(path.isBlank(), "the published Townstead jar is passed by the build (townsteadPublished)");
        return Path.of(path);
    }

    private static Map<String, ClassFiles.Info> main() {
        String classes = System.getProperty("mcakingdoms.mainClasses", "");
        assertFalse(classes.isBlank(), "the main classes are passed by the build");
        return ClassFiles.directories(classes);
    }

    @Test
    void thePublishedJarIsTheReleaseThisBuildPins() throws IOException, java.security.NoSuchAlgorithmException {
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(published())));
        assertEquals(System.getProperty("mcakingdoms.townstead.publishedSha1"), sha1, "maven.modrinth:townstead is the Modrinth release b8VnGFDu");
    }

    @Test
    void theCompileJarMatchesThePublishedApiMemberForMember() {
        Map<String, ClassFiles.Info> vendored = ClassFiles.jar(Path.of(System.getProperty("mcakingdoms.townstead.apiJar")), API);
        Map<String, ClassFiles.Info> released = ClassFiles.jar(published(), API);
        assertFalse(vendored.isEmpty());
        assertEquals(new TreeSet<>(released.keySet()), new TreeSet<>(vendored.keySet()), "the same api.v1 classes");
        for (var entry : vendored.entrySet()) {
            ClassFiles.Info ours = entry.getValue();
            ClassFiles.Info theirs = released.get(entry.getKey());
            assertEquals(theirs.methods(), ours.methods(), entry.getKey() + " methods");
            assertEquals(theirs.fields(), ours.fields(), entry.getKey() + " fields");
            assertEquals(theirs.superName(), ours.superName(), entry.getKey() + " super type");
            assertEquals(theirs.interfaces(), ours.interfaces(), entry.getKey() + " interfaces");
        }
    }

    @Test
    void everyTownsteadMemberThisModLinksAgainstExistsInTheRelease() {
        Map<String, ClassFiles.Info> released = ClassFiles.jar(published(), API);
        Set<String> missing = new TreeSet<>();
        int checked = 0;
        for (var info : main().values()) {
            for (var ref : info.references()) {
                if (!ref.owner().startsWith(TOWNSTEAD)) continue;
                checked++;
                if (!ref.owner().startsWith(API)) missing.add(info.name() + " -> internal " + ref.owner() + "#" + ref.name());
                else if (!ClassFiles.resolves(released, ref)) missing.add(info.name() + " -> " + ref.owner() + "#" + ref.name() + ref.descriptor());
            }
        }
        assertTrue(checked > 100, "the v1 backend's references were found (" + checked + ")");
        assertTrue(missing.isEmpty(), "members absent from Townstead 0.8.0: " + missing);
    }

    @Test
    void onlyTheV1PackageNamesATownsteadType() {
        Set<String> leaks = new TreeSet<>();
        for (var info : main().values()) {
            if (info.name().startsWith(V1)) continue;
            boolean names = info.classes().stream().anyMatch(type -> type.contains(TOWNSTEAD))
                    || info.references().stream().anyMatch(ref -> ref.owner().startsWith(TOWNSTEAD) || ref.descriptor().contains("L" + TOWNSTEAD));
            if (names) leaks.add(info.name());
        }
        assertTrue(leaks.isEmpty(), "classes that would link to Townstead when it is absent: " + leaks);
    }

    /** The backend probes, at bind time, every Townstead member it later calls, so a missing one fails closed. */
    @Test
    void theBindManifestCoversEveryMemberTheBackendCalls() {
        Set<String> probed = new LinkedHashSet<>();
        TownsteadV1Backend.MANIFEST.values().forEach(members -> members.forEach(m -> probed.add(m.owner().getName().replace('.', '/') + "#" + m.name())));
        TownsteadV1Backend.REGISTRIES.forEach(m -> probed.add(m.owner().getName().replace('.', '/') + "#" + m.name()));
        // Probed by bind() itself, or inherited platform members, or the event records copied by TownsteadV1Events.
        Set<String> boundElsewhere = Set.of("get", "getApiVersion", "getApiRevision", "getModVersion", "<init>", "values", "valueOf",
                "equals", "hashCode", "toString");
        Set<String> uncovered = new TreeSet<>();
        for (var info : main().values()) {
            if (!info.name().equals(V1 + "TownsteadV1Backend") && !info.name().startsWith(V1 + "TownsteadV1Backend$")) continue;
            for (var ref : info.references()) {
                if (!ref.owner().startsWith(API) || ref.field() || boundElsewhere.contains(ref.name())) continue;
                if (!probed.contains(ref.owner() + "#" + ref.name())) uncovered.add(ref.owner() + "#" + ref.name());
            }
        }
        assertTrue(uncovered.isEmpty(), "called but never probed: " + uncovered);
    }
}
