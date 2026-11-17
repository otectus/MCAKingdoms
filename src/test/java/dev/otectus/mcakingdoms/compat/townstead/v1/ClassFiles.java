package dev.otectus.mcakingdoms.compat.townstead.v1;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** A minimal class-file reader for the contract tests: member references, declared members and the type hierarchy. */
final class ClassFiles {
    record Ref(String owner, String name, String descriptor, boolean field) {
    }

    record Info(String name, String superName, List<String> interfaces, Set<String> methods, Set<String> fields, Set<Ref> references,
                Set<String> classes) {
    }

    private ClassFiles() {
    }

    static Info read(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) throw new IllegalArgumentException("not a class file");
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Object[] pool = new Object[count];
            int[][] links = new int[count][];
            int[] tags = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    case 7, 8, 16, 19, 20 -> links[i] = new int[]{in.readUnsignedShort()};
                    case 9, 10, 11, 12, 17, 18 -> links[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> { in.readUnsignedByte(); links[i] = new int[]{in.readUnsignedShort()}; }
                    default -> throw new IllegalArgumentException("unknown constant tag " + tag);
                }
            }
            Set<Ref> references = new LinkedHashSet<>();
            Set<String> classes = new LinkedHashSet<>();
            for (int i = 1; i < count; i++) {
                if (tags[i] == 7) classes.add((String) pool[links[i][0]]);
                if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                    String owner = (String) pool[links[links[i][0]][0]];
                    int[] nameAndType = links[links[i][1]];
                    references.add(new Ref(owner, (String) pool[nameAndType[0]], (String) pool[nameAndType[1]], tags[i] == 9));
                }
            }
            in.readUnsignedShort();
            String name = (String) pool[links[in.readUnsignedShort()][0]];
            int superIndex = in.readUnsignedShort();
            String superName = superIndex == 0 ? null : (String) pool[links[superIndex][0]];
            List<String> interfaces = new ArrayList<>();
            for (int i = in.readUnsignedShort(); i > 0; i--) interfaces.add((String) pool[links[in.readUnsignedShort()][0]]);
            Set<String> fields = members(in, pool);
            Set<String> methods = members(in, pool);
            return new Info(name, superName, interfaces, methods, fields, references, classes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Set<String> members(DataInputStream in, Object[] pool) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            in.readUnsignedShort();
            String name = (String) pool[in.readUnsignedShort()];
            String descriptor = (String) pool[in.readUnsignedShort()];
            out.add(name + descriptor);
            for (int a = in.readUnsignedShort(); a > 0; a--) {
                in.readUnsignedShort();
                in.skipBytes(in.readInt());
            }
        }
        return out;
    }

    /** Every class of a jar under {@code prefix}, by internal name. */
    static Map<String, Info> jar(Path jar, String prefix) {
        Map<String, Info> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix) || !entry.getName().endsWith(".class")) continue;
                Info info = read(zip.getInputStream(entry).readAllBytes());
                out.put(info.name(), info);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return out;
    }

    /** Every compiled class under the directories of {@code classpath} (path-separated), by internal name. */
    static Map<String, Info> directories(String classpath) {
        Map<String, Info> out = new HashMap<>();
        for (String root : classpath.split(java.io.File.pathSeparator)) {
            Path base = Path.of(root);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> files = Files.walk(base)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    Info info = read(Files.readAllBytes(file));
                    out.put(info.name(), info);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return out;
    }

    /** Whether {@code ref} resolves in {@code types}: declared by its owner, a supertype within them, or java.lang. */
    static boolean resolves(Map<String, Info> types, Ref ref) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(List.of(ref.owner()));
        while (!queue.isEmpty()) {
            String type = queue.remove(0);
            if (!seen.add(type)) continue;
            Info info = types.get(type);
            if (info == null) {
                // A member inherited from the platform (Object, Record, Enum) is always there.
                if (type.startsWith("java/")) return true;
                continue;
            }
            if (ref.field() ? info.fields().contains(ref.name() + ref.descriptor()) : info.methods().contains(ref.name() + ref.descriptor())) return true;
            if (info.superName() != null) queue.add(info.superName());
            queue.addAll(info.interfaces());
        }
        return false;
    }
}
