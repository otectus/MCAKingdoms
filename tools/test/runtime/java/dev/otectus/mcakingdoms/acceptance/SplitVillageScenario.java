package dev.otectus.mcakingdoms.acceptance;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.VillageFootprint;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The split village of appendix A (world 21) as MCA: Kingdoms 0.1.1 saved it: Dawnfield, found by the structure
 * detector, and Peacecross, a POI fragment inside it. The region file is not shipped: a footprint source stands in for
 * the chunk's structure references, and the start-up pass must merge the fragment exactly once.
 */
final class SplitVillageScenario {
    static final UUID DAWNFIELD = UUID.fromString("6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0");
    static final UUID PEACECROSS = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    static final UUID PLAYER = UUID.fromString("5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9");
    static final String KEY = "minecraft:overworld:minecraft:village_plains:-124:82";
    static final BlockPos DAWNFIELD_ANCHOR = new BlockPos(-1977, 128, 1324);
    static final BlockPos PEACECROSS_ANCHOR = new BlockPos(-2029, 110, 1324);
    private static final BoundingBox BOX = new BoundingBox(-2052, 100, 1268, -1903, 156, 1380);

    private SplitVillageScenario() {
    }

    private static void check(boolean ok, String message) {
        IntegrationScenario.check(ok, message);
    }

    /** Before the levels load, on the first boot only: the saved data as 0.1.1 wrote it. */
    static void seed(MinecraftServer server) throws IOException {
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Path settlements = data.resolve("mcakingdoms_settlements.dat");
        if (Files.exists(settlements)) return;
        Files.createDirectories(data);
        CompoundTag root = new CompoundTag();
        root.putInt("Schema", 1);
        root.putLong("Revision", 10);
        ListTag records = new ListTag();
        records.add(record(DAWNFIELD, "Dawnfield", "STRUCTURE", "mcakingdoms:village_structure", KEY, DAWNFIELD_ANCHOR, 75,
                -2052, 1268, -1903, 1380, "regions_unexplored:highland_fields", "minecraft:village_plains", 422, true));
        records.add(record(PEACECROSS, "Peacecross", "POI", "mcakingdoms:poi_cluster", "minecraft:overworld:poi:-127:82",
                PEACECROSS_ANCHOR, 64, -2093, 1260, -1965, 1388, "regions_unexplored:orchard", null, 440, false));
        root.put("Settlements", records);
        root.put("Redirects", new ListTag());
        write(settlements, root);

        CompoundTag knowledge = new CompoundTag();
        knowledge.putInt("Schema", 1);
        knowledge.putBoolean("Initialized", true);
        knowledge.putLong("Revision", 2);
        knowledge.put("Public", new ListTag());
        CompoundTag known = new CompoundTag();
        known.putUUID("Player", PLAYER);
        known.put("Known", ids(DAWNFIELD, PEACECROSS));
        ListTag players = new ListTag();
        players.add(known);
        knowledge.put("Players", players);
        CompoundTag visited = new CompoundTag();
        visited.putUUID("Player", PLAYER);
        visited.put("Settlements", ids(DAWNFIELD, PEACECROSS));
        ListTag visits = new ListTag();
        visits.add(visited);
        knowledge.put("Visits", visits);
        write(data.resolve("mcakingdoms_knowledge.dat"), knowledge);
        System.out.println("Seeded a 0.1.1 settlements file with Dawnfield and its fragment Peacecross");
    }

    /** At server start, before the first tick, so the start-up pass sees the village. */
    static void run(MinecraftServer server, String phase, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) {
        var kingdoms = McaKingdomsApi.get(server);
        kingdoms.registerVillageFootprintSource(new ResourceLocation("mcakingdoms_acceptance", "split_village"),
                (level, pos, margin) -> level.dimension().equals(Level.OVERWORLD) && BOX.inflatedBy(margin).isInside(pos)
                        ? List.of(new VillageFootprint(Level.OVERWORLD, new ResourceLocation("minecraft", "village_plains"),
                        new ChunkPos(-124, 82), BOX)) : List.of());
        schedule.accept(80L, () -> {
            try {
                var level = server.overworld();
                check(kingdoms.getSettlement(PEACECROSS).orElseThrow().id().equals(DAWNFIELD), "Peacecross resolves to Dawnfield");
                check(kingdoms.getSettlementAt(level, DAWNFIELD_ANCHOR).orElseThrow().id().equals(DAWNFIELD)
                        && kingdoms.getSettlementAt(level, PEACECROSS_ANCHOR).orElseThrow().id().equals(DAWNFIELD),
                        "both former anchors lie in the one village");
                check(kingdoms.findSettlement("Peacecross").orElseThrow().id().equals(DAWNFIELD), "the retired name is an alias");
                check(kingdoms.getSettlement(DAWNFIELD).orElseThrow().displayName().equals("Dawnfield"), "the structure record keeps its name");
                check(!SettlementKnowledge.get(server).recordVisit(PLAYER, DAWNFIELD), "the player gets no second first-visit title");
                if (phase.equals("split-village-restart")) {
                    check(!((dev.otectus.mcakingdoms.core.KingdomsServiceImpl) kingdoms).beginStartupReconciliation(),
                            "a second start does not reconcile again");
                }
                server.saveEverything(false, true, true);
                // Read back from disk: SavedData#save is renamed in a production jar, so it is not called from here.
                Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
                check(visits(NbtIo.readCompressed(data.resolve("mcakingdoms_knowledge.dat").toFile()).getCompound("data"))
                        .equals(Set.of(DAWNFIELD)), "the player's two visits collapsed to one");
                CompoundTag file = NbtIo.readCompressed(data.resolve("mcakingdoms_settlements.dat").toFile()).getCompound("data");
                check(file.getInt("Schema") == 1, "the settlements file stays at schema 1");
                check(file.getInt("Reconciled") == 1, "the start-up pass is recorded as done");
                check(file.getList("Settlements", Tag.TAG_COMPOUND).size() == 1, "one settlement record remains");
                check(file.getList("Redirects", Tag.TAG_COMPOUND).size() == 1, "the retired id redirects");
                finish.accept("PASS integration split village " + phase + ": one village, one settlement, visits carried over");
            } catch (Throwable failure) {
                failure.printStackTrace();
                finish.accept("FAIL " + failure);
            }
        });
    }

    private static Set<UUID> visits(CompoundTag knowledge) {
        Set<UUID> seen = new HashSet<>();
        for (Tag entry : knowledge.getList("Visits", Tag.TAG_COMPOUND)) {
            CompoundTag player = (CompoundTag) entry;
            if (!player.getUUID("Player").equals(PLAYER)) continue;
            for (Tag id : player.getList("Settlements", Tag.TAG_COMPOUND)) seen.add(((CompoundTag) id).getUUID("Id"));
        }
        return seen;
    }

    private static CompoundTag record(UUID id, String name, String detection, String sourceId, String sourceKey, BlockPos anchor,
                                      int radius, int minX, int minZ, int maxX, int maxZ, String biome, String style,
                                      long created, boolean structure) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", 1);
        tag.putUUID("Id", id);
        tag.putString("Dimension", "minecraft:overworld");
        tag.putLong("Anchor", anchor.asLong());
        tag.putInt("Radius", radius);
        tag.putInt("MinX", minX);
        tag.putInt("MinZ", minZ);
        tag.putInt("MaxX", maxX);
        tag.putInt("MaxZ", maxZ);
        tag.putString("Kingdom", "mcakingdoms:serenum");
        tag.putString("DisplayName", name);
        tag.putString("Slug", "mcakingdoms:" + name.toLowerCase(java.util.Locale.ROOT));
        tag.putString("Biome", biome);
        if (style != null) tag.putString("Style", style);
        tag.putString("AssignmentSource", "EXACT_BIOME");
        tag.putString("DetectionSource", detection);
        tag.putBoolean("NameLocked", false);
        tag.putBoolean("KingdomLocked", false);
        tag.putLong("Created", created);
        tag.putLong("LastObserved", created);
        CompoundTag refs = new CompoundTag();
        ListTag refValues = new ListTag();
        if (structure) {
            refs.putString("minecraft:structure", sourceKey);
            refValues.add(entry("minecraft:structure", sourceKey));
        }
        tag.put("ExternalRefs", refs);
        tag.put("ExternalRefValues", refValues);
        tag.put("Aliases", new ListTag());
        tag.put("RetiredSlugs", new ListTag());
        tag.put("AssignmentTrace", new ListTag());
        tag.putString("SourceId", sourceId);
        tag.putString("SourceKey", sourceKey);
        ListTag detectors = new ListTag();
        detectors.add(entry(sourceId, sourceKey));
        tag.put("DetectorIdentities", detectors);
        ListTag strong = new ListTag();
        if (structure) strong.add(entry(sourceId, sourceKey));
        tag.put("StrongStructureIdentities", strong);
        tag.putLong("Revision", structure ? 4 : 6);
        return tag;
    }

    private static CompoundTag entry(String key, String value) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Key", key);
        ListTag values = new ListTag();
        values.add(StringTag.valueOf(value));
        entry.put("Values", values);
        return entry;
    }

    private static ListTag ids(UUID... values) {
        ListTag list = new ListTag();
        for (UUID value : values) {
            CompoundTag id = new CompoundTag();
            id.putUUID("Id", value);
            list.add(id);
        }
        return list;
    }

    private static void write(Path file, CompoundTag data) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        NbtIo.writeCompressed(root, file.toFile());
    }
}
