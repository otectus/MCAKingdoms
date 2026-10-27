package dev.otectus.mcakingdoms.knowledge;

import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Sparse discovery records. Existing globally published settlements stay public on first adoption. Visits are kept
 * apart from discovery, because a player may know of a settlement without ever having entered it.
 */
public final class SettlementKnowledge extends SavedData {
    public static final String NAME = "mcakingdoms_knowledge";
    private static final int LIMIT = 100_000;
    private final Set<UUID> legacyPublic = new HashSet<>();
    private final Map<UUID, Set<UUID>> discovered = new HashMap<>();
    private final Map<UUID, Set<UUID>> visited = new HashMap<>();
    private int entries;
    private int visits;
    private boolean initialized;
    private long revision;
    private CompoundTag preserved;
    private String diagnostic = "";

    public static SettlementKnowledge get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Discovery requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(SettlementKnowledge::load,
                SettlementKnowledge::new, NAME);
    }

    /** Called once before discovery resumes. Never visits chunks or rewrites civic records. */
    public void adopt(MinecraftServer server, KingdomsService kingdoms) {
        if (initialized || !writable()) return;
        Set<UUID> existing = new HashSet<>();
        kingdoms.getKingdoms().forEach(k -> kingdoms.getSettlements(k.id()).forEach(s -> existing.add(s.id())));
        if (existing.size() > LIMIT) throw new IllegalStateException("Legacy settlement discovery exceeds capacity");
        legacyPublic.addAll(existing);
        initialized = true;
        revision++;
        setDirty();
        save(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve(NAME + ".dat").toFile());
        if (isDirty()) {
            // Startup policy: an unwritable discovery file never stops the server. Discovery becomes read-only, the
            // ledger and selectors show this diagnostic, and the file on disk is left exactly as it was found.
            CompoundTag snapshot = save(new CompoundTag());
            legacyPublic.clear(); discovered.clear(); visited.clear(); entries = 0; visits = 0; initialized = false;
            preserved = snapshot;
            diagnostic = "Settlement discovery is read-only: " + NAME + ".dat could not be written at startup";
            com.mojang.logging.LogUtils.getLogger().error("{}; check the world data directory permissions and free space", diagnostic);
        }
    }
    /** Every settlement the viewer may see in ledger order, bounded, for selectors that need the whole list once. */
    public List<SettlementView> all(ServerPlayer viewer, KingdomsService kingdoms, Optional<ResourceLocation> kingdom, int limit) {
        if (limit < 1 || limit > 1_000_000) throw new IllegalArgumentException("Invalid discovery limit");
        if (viewer.hasPermissions(2)) {
            var out = new ArrayList<SettlementView>();
            for (int offset = 0; offset < limit; offset += 64) { var page = kingdoms.getSettlementPage(kingdom, offset, Math.min(64, limit - offset)); out.addAll(page); if (page.size() < 64) break; }
            return out;
        }
        return known(viewer, kingdoms, kingdom).limit(limit).toList();
    }
    private java.util.stream.Stream<SettlementView> known(ServerPlayer viewer, KingdomsService kingdoms, Optional<ResourceLocation> kingdom) {
        Set<UUID> ids = new HashSet<>(legacyPublic);
        ids.addAll(discovered.getOrDefault(viewer.getUUID(), Set.of()));
        if (!writable()) ids.clear();
        return ids.stream().map(kingdoms::getSettlement).flatMap(Optional::stream)
                .filter(s -> kingdom.isEmpty() || kingdom.get().equals(s.kingdomId()))
                .sorted(Comparator.comparing(SettlementView::displayName).thenComparing(s -> s.id().toString()));
    }

    public boolean writable() { return preserved == null; }
    public static dev.otectus.mcakingdoms.persistence.StoreStatus status(MinecraftServer server) { var data = get(server); return dev.otectus.mcakingdoms.persistence.StoreStatus.of(NAME, data.writable(), data.diagnostic()); }
    public String diagnostic() { return diagnostic; }
    public long revision() { return revision; }
    public boolean knows(UUID player, UUID settlement) {
        return writable() && (legacyPublic.contains(settlement)
                || discovered.getOrDefault(player, Set.of()).contains(settlement));
    }

    public boolean discover(UUID player, UUID settlement) {
        Objects.requireNonNull(player); Objects.requireNonNull(settlement);
        if (!initialized || !writable() || knows(player, settlement)) return false;
        if (entries >= LIMIT || (!discovered.containsKey(player) && discovered.size() >= LIMIT)) return false;
        Set<UUID> known = discovered.computeIfAbsent(player, ignored -> new HashSet<>());
        if (known.size() >= LIMIT || !known.add(settlement)) return false;
        entries++;
        revision++;
        setDirty();
        return true;
    }

    /**
     * Records that the player entered the settlement and returns true unless an earlier visit is on record. A visit that
     * cannot be kept (read-only data or full capacity) also returns true, so a lost record never hides the entry title.
     * Visits do not change what the ledger shows, so the revision stays put.
     */
    public boolean recordVisit(UUID player, UUID settlement) {
        Objects.requireNonNull(player); Objects.requireNonNull(settlement);
        if (!writable()) return true;
        Set<UUID> seen = visited.get(player);
        if (seen != null && seen.contains(settlement)) return false;
        if (visits >= LIMIT || (seen == null && visited.size() >= LIMIT)) return true;
        visited.computeIfAbsent(player, ignored -> new HashSet<>()).add(settlement);
        visits++;
        setDirty();
        return true;
    }

    /** Stored ids from before a merge resolve first: the merge moved what the player knows to the survivor. */
    public boolean visible(ServerPlayer viewer, UUID settlement) {
        if (viewer.hasPermissions(2)) return true;
        UUID current = settlement;
        try {
            current = dev.otectus.mcakingdoms.api.McaKingdomsApi.get(viewer.getServer()).resolveSettlementId(settlement);
        } catch (IllegalStateException unavailable) {
            // No live Kingdoms service (server starting or stopping): the stored id is all there is to compare.
        }
        return knows(viewer.getUUID(), current);
    }

    public List<SettlementView> page(ServerPlayer viewer, KingdomsService kingdoms,
                                     Optional<ResourceLocation> kingdom, int offset, int limit) {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 64)
            throw new IllegalArgumentException("Invalid discovery page");
        if (viewer.hasPermissions(2)) return kingdoms.getSettlementPage(kingdom, offset, limit);
        return known(viewer, kingdoms, kingdom).skip(offset).limit(limit).toList();
    }

    /** Ledger order: by name, or by distance from the viewer with other dimensions after, by name. */
    public enum Sort { NAME, DISTANCE }

    /** One ledger page and how many settlements the viewer may see match the search (never more than the viewer may know). */
    public record SearchPage(List<SettlementView> settlements, int total) { }

    /** The most settlements one ledger search considers; a larger operator world shows the first matches in order. */
    static final int SEARCH_CAP = 20_000;

    /**
     * The ledger's search: the viewer's visible settlements (discovered or public; every one for operators), filtered by
     * kingdom and by words of the name or its former names, sorted, then paged. Hidden records never reach the page or
     * the count. Reads saved records only; no chunk is loaded.
     */
    public SearchPage search(ServerPlayer viewer, KingdomsService kingdoms, Optional<ResourceLocation> kingdom, String query,
                             Sort sort, int offset, int limit) {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 64) throw new IllegalArgumentException("Invalid discovery page");
        var tokens = dev.otectus.mcakingdoms.interaction.SearchText.tokens(query == null ? "" : query);
        java.util.stream.Stream<SettlementView> visible;
        if (viewer.hasPermissions(2)) {
            var all = new ArrayList<SettlementView>();
            for (int from = 0; from < SEARCH_CAP; from += 64) {
                var page = kingdoms.getSettlementPage(kingdom, from, 64);
                all.addAll(page);
                if (page.size() < 64) break;
            }
            visible = all.stream();
        } else visible = known(viewer, kingdoms, kingdom);
        var matching = visible.limit(SEARCH_CAP).filter(s -> tokens.isEmpty() || dev.otectus.mcakingdoms.interaction.SearchText.matches(tokens,
                dev.otectus.mcakingdoms.interaction.SearchText.normalize(s.displayName() + " | " + String.join(" | ", s.aliases()) + " | " + s.slug().getPath().replace('_', ' '))));
        Comparator<SettlementView> byName = Comparator.comparing((SettlementView s) -> s.displayName().toLowerCase(Locale.ROOT)).thenComparing(s -> s.id().toString());
        Comparator<SettlementView> order = byName;
        if (sort == Sort.DISTANCE) {
            var here = viewer.level().dimension();
            var position = viewer.blockPosition();
            order = Comparator.comparing((SettlementView s) -> !s.dimension().equals(here))
                    .thenComparingDouble(s -> s.dimension().equals(here) ? s.anchor().distSqr(position) : 0)
                    .thenComparing(byName);
        }
        var sorted = matching.sorted(order).toList();
        int from = Math.min(offset, sorted.size());
        return new SearchPage(sorted.subList(from, Math.min(sorted.size(), from + limit)), sorted.size());
    }

    public long count(ServerPlayer viewer, KingdomsService kingdoms, ResourceLocation kingdom) {
        if (viewer.hasPermissions(2)) return kingdoms.getSettlements(kingdom).size();
        Set<UUID> ids = new HashSet<>(legacyPublic);
        ids.addAll(discovered.getOrDefault(viewer.getUUID(), Set.of()));
        if (!writable()) return 0;
        return ids.stream().map(kingdoms::getSettlement).flatMap(Optional::stream)
                .filter(s -> s.kingdomId().equals(kingdom)).count();
    }

    public Optional<SettlementView> find(ServerPlayer viewer, KingdomsService kingdoms, String query) {
        if (viewer.hasPermissions(2)) return kingdoms.findSettlement(query);
        try { return kingdoms.getSettlement(UUID.fromString(query)).filter(s -> visible(viewer, s.id())); }
        catch (IllegalArgumentException ignored) { }
        Set<UUID> ids = new HashSet<>(legacyPublic);
        ids.addAll(discovered.getOrDefault(viewer.getUUID(), Set.of()));
        if (!writable()) return Optional.empty();
        String slug = query.contains(":") ? dev.otectus.mcakingdoms.persistence.LegacyRename.rewrite(query)
                : dev.otectus.mcakingdoms.api.McaKingdomsApi.MOD_ID + ":" + query.toLowerCase(Locale.ROOT);
        List<SettlementView> matches = ids.stream().map(kingdoms::getSettlement).flatMap(Optional::stream)
                .filter(s -> s.displayName().equalsIgnoreCase(query) || s.slug().toString().equals(slug)
                        || s.aliases().contains(query) || s.aliases().contains(slug)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public void merge(UUID source, UUID target) {
        if (!writable()) return;
        boolean changed = false;
        if (legacyPublic.remove(source)) { legacyPublic.add(target); changed = true; }
        for (Set<UUID> ids : discovered.values()) {
            if (ids.remove(source)) { if (!ids.add(target)) entries--; changed = true; }
        }
        boolean visitsChanged = false;
        for (Set<UUID> ids : visited.values()) {
            if (ids.remove(source)) { if (!ids.add(target)) visits--; visitsChanged = true; }
        }
        if (changed) revision++;
        if (changed || visitsChanged) setDirty();
    }

    public static SettlementKnowledge load(CompoundTag tag) {
        SettlementKnowledge data = new SettlementKnowledge();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1
                    || !tag.contains("Initialized", Tag.TAG_BYTE) || !tag.contains("Revision", Tag.TAG_LONG)
                    || !tag.contains("Public", Tag.TAG_LIST) || !tag.contains("Players", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported discovery data");
            data.initialized = tag.getBoolean("Initialized"); data.revision = tag.getLong("Revision");
            if (data.revision < 0) throw new IllegalArgumentException("Negative discovery revision");
            readIds(compoundList(tag, "Public"), data.legacyPublic);
            ListTag players = compoundList(tag, "Players");
            if (players.size() > LIMIT) throw new IllegalArgumentException("Discovery player capacity exceeded");
            for (Tag entry : players) {
                CompoundTag player = (CompoundTag) entry;
                UUID id = player.getUUID("Player"); Set<UUID> known = new HashSet<>();
                if (!player.contains("Known", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing discoveries");
                readIds(compoundList(player, "Known"), known);
                data.entries += known.size();
                if (data.entries > LIMIT) throw new IllegalArgumentException("Total discovery capacity exceeded");
                if (data.discovered.putIfAbsent(id, known) != null) throw new IllegalArgumentException("Duplicate discovery player");
            }
            if (tag.contains("Visits")) { // absent from files written by earlier builds, which kept no visits
                ListTag visitors = compoundList(tag, "Visits");
                if (visitors.size() > LIMIT) throw new IllegalArgumentException("Visit player capacity exceeded");
                for (Tag entry : visitors) {
                    CompoundTag player = (CompoundTag) entry;
                    UUID id = player.getUUID("Player"); Set<UUID> seen = new HashSet<>();
                    if (!player.contains("Settlements", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing visits");
                    readIds(compoundList(player, "Settlements"), seen);
                    data.visits += seen.size();
                    if (data.visits > LIMIT) throw new IllegalArgumentException("Total visit capacity exceeded");
                    if (data.visited.putIfAbsent(id, seen) != null) throw new IllegalArgumentException("Duplicate visit player");
                }
            }
        } catch (RuntimeException failure) {
            data.legacyPublic.clear(); data.discovered.clear(); data.visited.clear(); data.preserved = tag.copy();
            data.diagnostic = "Settlement discovery is read-only: " + failure.getMessage();
            com.mojang.logging.LogUtils.getLogger().warn("{}; preserving {}.dat without player location disclosure", data.diagnostic, NAME);
        }
        return data;
    }

    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid discovery list: " + key);
        return list;
    }

    private static void readIds(ListTag list, Set<UUID> target) {
        if (list.size() > LIMIT) throw new IllegalArgumentException("Discovery capacity exceeded");
        for (Tag entry : list) if (!target.add(((CompoundTag) entry).getUUID("Id")))
            throw new IllegalArgumentException("Duplicate discovery");
    }

    private static ListTag ids(Set<UUID> values) {
        ListTag list = new ListTag();
        values.stream().sorted().forEach(id -> { CompoundTag tag = new CompoundTag(); tag.putUUID("Id", id); list.add(tag); });
        return list;
    }

    @Override public void save(java.io.File destination) {
        if (!isDirty()) return;
        try {
            dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter.write(destination, save(new CompoundTag()));
            setDirty(false);
        } catch (java.io.IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Could not save settlement discovery; data remains dirty", failure);
        }
    }

    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) {
        if (!writable()) return preserved.copy();
        tag.putInt("Schema", 1); tag.putBoolean("Initialized", initialized); tag.putLong("Revision", revision);
        tag.put("Public", ids(legacyPublic)); ListTag players = new ListTag();
        discovered.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag player = new CompoundTag(); player.putUUID("Player", entry.getKey());
            player.put("Known", ids(entry.getValue())); players.add(player);
        });
        tag.put("Players", players); ListTag visitors = new ListTag();
        visited.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag player = new CompoundTag(); player.putUUID("Player", entry.getKey());
            player.put("Settlements", ids(entry.getValue())); visitors.add(player);
        });
        tag.put("Visits", visitors); return tag;
    }
}
