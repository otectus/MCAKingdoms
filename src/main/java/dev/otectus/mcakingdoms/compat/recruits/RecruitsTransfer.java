package dev.otectus.mcakingdoms.compat.recruits;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.politics.GovernmentService;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Team;
import java.lang.reflect.*;
import java.util.*;

/** Version-bound negotiated transfer extension. Native vetoes and native persistence remain authoritative. */
public final class RecruitsTransfer {
    private static final String ROOT = "com.talhanation.recruits.";
    public record Snapshot(UUID unit, UUID owner, UUID recipient, UUID group, UUID recipientGroup,
                           String team, String recipientTeam, String equipment, String entity,
                           int ownerCount, int recipientCount, int paymentTimer, RecruitsMobilization.Orders orders) {
        public Snapshot {
            Objects.requireNonNull(unit); Objects.requireNonNull(owner); Objects.requireNonNull(recipient);
            Objects.requireNonNull(group); Objects.requireNonNull(recipientGroup);
            Objects.requireNonNull(orders);
            if (owner.equals(recipient) || group.equals(recipientGroup) || entity == null || entity.length() > 262144
                    || equipment == null || !equipment.matches("[a-f0-9]{64}") || team == null || team.length() > 128
                    || recipientTeam == null || recipientTeam.length() > 128 || ownerCount < 1 || recipientCount < 0)
                throw Keyed.refuse("compat.mcakingdoms.recruits.invalid_snapshot","The saved transfer record is not valid.");
        }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { return target.getClass().getMethod(name, types).invoke(target, args); }
        catch (ReflectiveOperationException | LinkageError failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.transfer_api", "Recruits' transfer functions are unavailable: %s", name).because(failure); }
    }
    private static Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }
    private static Object manager(String field) {
        try { return Objects.requireNonNull(Class.forName(ROOT + "RecruitEvents").getField(field).get(null)); }
        catch (ReflectiveOperationException | LinkageError failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.transfer_manager","Recruits' transfer manager is unavailable.").because(failure); }
    }
    private static Object groups() { return manager("recruitsGroupsManager"); }
    private static Object counts() { return manager("recruitsPlayerUnitManager"); }
    private static int count(UUID owner) { return (int) call(counts(), "getRecruitCount", new Class<?>[]{UUID.class}, owner); }
    private static Object group(UUID id, UUID owner) {
        Object group = call(groups(), "getGroup", new Class<?>[]{UUID.class}, id);
        if (group == null || !owner.equals(call(group, "getPlayerUUID")) || (boolean) call(group, "isDisabled"))
            throw Keyed.refuse("compat.mcakingdoms.recruits.groups_exist","Both the original and the recipient's Recruits groups must exist and keep their owners.");
        try {
            Object context = group.getClass().getField("disbandContext").get(group);
            if (group.getClass().getField("removed").getBoolean(group) || context != null && context.getClass().getField("disband").getBoolean(context))
                throw Keyed.refuse("compat.mcakingdoms.recruits.group_disbanding","A Recruits group is removed or about to be disbanded.");
        } catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.group_lifecycle","Recruits' group records are unavailable.").because(failure); }
        return group;
    }
    private static Set<UUID> members(Object group) {
        try { return new HashSet<>((Collection<UUID>) group.getClass().getField("members").get(group)); }
        catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.group_membership","Recruits' group membership is unavailable.").because(failure); }
    }
    public static String equipment(Entity unit) { return equipment(unit.saveWithoutId(new CompoundTag())); }
    private static String equipment(CompoundTag tag) {
        var preserved = new CompoundTag();
        for (String key : List.of("Items", "HandItems", "ArmorItems", "HandDropChances", "ArmorDropChances", "Cost", "Xp", "Level", "Kills"))
            if (tag.contains(key)) preserved.put(key, tag.get(key).copy());
        return GovernmentService.hash(preserved.toString());
    }
    public static Snapshot snapshot(Entity unit, ServerPlayer owner, ServerPlayer recipient, UUID recipientGroup) {
        RecruitsMilitary.ready(owner.getServer());
        if (!RecruitsMobilization.ownedBy(unit, owner) || unit.isPassenger() || unit.isVehicle() || !unit.isAlive()
                || unit.level() != owner.level() || unit.level() != recipient.level() || unit.distanceToSqr(owner) > 1024 || unit.distanceToSqr(recipient) > 1024)
            throw Keyed.refuse("compat.mcakingdoms.recruits.together","Both owners and the recruit (alive and not mounted) must be together within 32 blocks.");
        if (dev.otectus.mcakingdoms.warfare.mobilization.MobilizationSavedData.get(owner.getServer()).transferBlocked(unit.getUUID()))
            throw Keyed.refuse("compat.mcakingdoms.recruits.dismiss_first","Dismiss the recruit's deployment and let its orders be restored before transferring it.");
        UUID oldGroup = (UUID) call(unit, "getGroup");
        if (oldGroup == null || !members(group(oldGroup, owner.getUUID())).contains(unit.getUUID()) || members(group(recipientGroup, recipient.getUUID())).contains(unit.getUUID()))
            throw Keyed.refuse("compat.mcakingdoms.recruits.membership_agree","Recruits' group membership must agree before the transfer.");
        String recipientTeam = recipient.getTeam() == null ? "" : recipient.getTeam().getName();
        if (!(boolean) call(counts(), "canPlayerRecruit", new Class<?>[]{String.class, UUID.class}, recipientTeam, recipient.getUUID()))
            throw Keyed.refuse("compat.mcakingdoms.recruits.recipient_limit","The recipient has reached their Recruits recruitment limit.");
        int timer;
        try {
            timer = unit.getClass().getField("paymentTimer").getInt(unit);
            Object value = Class.forName(ROOT + "config.RecruitsServerConfig").getField("RecruitsPayment").get(null);
            if ((boolean) value.getClass().getMethod("get").invoke(value) && timer <= 0)
                throw Keyed.refuse("compat.mcakingdoms.recruits.overdue_wages","Pay the recruit's overdue wages before negotiating a transfer.");
        } catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.wage_policy","Recruits' wage rules are unavailable.").because(failure); }
        var tag = unit.saveWithoutId(new CompoundTag());
        return new Snapshot(unit.getUUID(), owner.getUUID(), recipient.getUUID(), oldGroup, recipientGroup,
                unit.getTeam() == null ? "" : unit.getTeam().getName(), recipientTeam, equipment(tag), tag.toString(), count(owner.getUUID()), count(recipient.getUUID()), timer, RecruitsMobilization.snapshot(unit));
    }
    /** Called only after the snapshot is durably saved. Rejected hire triggers guarded restoration, never a fake release receipt. */
    public static boolean apply(Entity unit, ServerPlayer owner, ServerPlayer recipient, Snapshot before) {
        if (!snapshot(unit, owner, recipient, before.recipientGroup()).equipment().equals(before.equipment()))
            throw Keyed.refuse("compat.mcakingdoms.recruits.equipment_changed","The recruit's equipment changed before the transfer was applied.");
        try (var accounting = TransferAccounting.expect(before.unit(),before.owner(),-1,before.recipient(),1)) {
        call(unit, "disband", new Class<?>[]{Player.class, boolean.class, boolean.class}, owner, false, false);
        if (owner.getUUID().equals(call(unit, "getOwnerUUID"))) return false; // Native dismissal veto.
        if (call(unit, "getOwnerUUID") != null) throw Keyed.refuse("compat.mcakingdoms.recruits.unexpected_owner","Recruits reports an unexpected owner after dismissal.");
        Object destination = group(before.recipientGroup(), recipient.getUUID());
        boolean hired = (boolean) call(unit, "hire", new Class<?>[]{Player.class, destination.getClass(), boolean.class}, recipient, destination, false);
        try { unit.getClass().getField("paymentTimer").setInt(unit, before.paymentTimer()); }
        catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.wage_timer","The recruit's wage timer could not be restored.").because(failure); }
        if (!hired || !matches(unit, before, true) || count(before.owner()) != before.ownerCount() - 1 || count(before.recipient()) != before.recipientCount() + 1) {
            restore(unit, owner, recipient, before, true); return false;
        }
        return true;
        } catch (RuntimeException failure) {
            try { restore(unit, owner, recipient, before, true); } catch (RuntimeException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }
    public static boolean matches(Entity unit, Snapshot before, boolean transferred) {
        return matches(unit,before,transferred,true);
    }
    private static boolean matches(Entity unit, Snapshot before, boolean transferred, boolean accounting) {
        if (!unit.getUUID().equals(before.unit()) || !equipment(unit).equals(before.equipment())
                || accounting && !dev.otectus.mcakingdoms.evolution.RecruitTransferData.get(unit.getServer()).accountingSafe(before.unit())) return false;
        UUID owner = transferred ? before.recipient() : before.owner(); UUID group = transferred ? before.recipientGroup() : before.group();
        String team = transferred ? before.recipientTeam() : before.team();
        return owner.equals(call(unit, "getOwnerUUID")) && group.equals(call(unit, "getGroup")) && (boolean) call(unit, "getIsOwned")
                && count(before.owner()) == before.ownerCount() - (transferred ? 1 : 0)
                && count(before.recipient()) == before.recipientCount() + (transferred ? 1 : 0)
                && Objects.equals(team, unit.getTeam() == null ? "" : unit.getTeam().getName())
                && members(group(group, owner)).contains(unit.getUUID())
                && !members(group(transferred ? before.group() : before.recipientGroup(), transferred ? before.owner() : before.recipient())).contains(unit.getUUID());
    }
    /** Restoration uses the frozen native state, not cancellable hire, and refuses unrelated concurrent changes. */
    public static void restore(Entity unit, ServerPlayer owner, ServerPlayer recipient, Snapshot before) {
        restore(unit, owner, recipient, before, false);
    }
    private static void restore(Entity unit, ServerPlayer owner, ServerPlayer recipient, Snapshot before, boolean immediate) {
        if(!dev.otectus.mcakingdoms.evolution.RecruitTransferData.get(unit.getServer()).accountingSafe(before.unit()))
            throw Keyed.refuse("compat.mcakingdoms.recruits.accounting_changed","Other Recruits counts changed, so the counts were not corrected automatically.");
        UUID current = (UUID) call(unit, "getOwnerUUID");
        if (current != null && !Set.of(before.owner(), before.recipient()).contains(current) || !equipment(unit).equals(before.equipment())
                || !unit.getUUID().equals(before.unit()))
            throw Keyed.refuse("compat.mcakingdoms.recruits.diverged","Recruits' state no longer matches the record, so it was not restored automatically.");
        int sourceCount = count(before.owner()), destinationCount = count(before.recipient());
        boolean baseline = sourceCount == before.ownerCount() && destinationCount == before.recipientCount();
        boolean released = sourceCount == before.ownerCount() - 1 && destinationCount == before.recipientCount();
        boolean transferred = sourceCount == before.ownerCount() - 1 && destinationCount == before.recipientCount() + 1;
        if (!baseline && !released && !transferred)
            throw Keyed.refuse("compat.mcakingdoms.recruits.accounting_outside","Recruits' counts changed outside this transfer; recovery stays pending and no counts are rewritten.");
        group(before.group(), before.owner()); group(before.recipientGroup(), before.recipient());
        var level = (ServerLevel) unit.level();
        nativeTeam(unit, unit.getTeam(), level, false);
        call(groups(), "removeMember", new Class<?>[]{UUID.class, UUID.class, ServerLevel.class}, before.recipientGroup(), before.unit(), level);
        // The frozen, saved baseline distinguishes native accounting stages from entity ownership.
        // This also makes retry safe if an exception occurs between the two counter writes.
        try(var accounting=TransferAccounting.recovery(before.unit(),before.recipient(),transferred?-1:0,before.owner(),baseline?0:1)) {
            if (transferred) call(counts(), "removeRecruits", new Class<?>[]{UUID.class, int.class}, before.recipient(), 1);
            if (!baseline) call(counts(), "addRecruits", new Class<?>[]{UUID.class, int.class}, before.owner(), 1);
        }
        call(unit, "setOwnerUUID", new Class<?>[]{Optional.class}, Optional.of(before.owner()));
        call(unit, "setIsOwned", new Class<?>[]{boolean.class}, true);
        call(unit, "setGroupUUID", new Class<?>[]{UUID.class}, before.group());
        call(groups(), "addMember", new Class<?>[]{UUID.class, UUID.class, ServerLevel.class}, before.group(), before.unit(), level);
        if (immediate) {
            RecruitsMobilization.apply(unit, before.orders());
            try { unit.getClass().getField("paymentTimer").setInt(unit, before.paymentTimer()); }
            catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.wage_recovery","The recruit's wage timer could not be recovered.").because(failure); }
        }
        nativeTeam(unit, before.team().isEmpty() ? null : owner.getServer().getScoreboard().getPlayerTeam(before.team()), level, true);
        if (!matches(unit, before, false)) throw Keyed.refuse("compat.mcakingdoms.recruits.restoration_pending","The restoration is waiting to be verified.");
    }
    private static void nativeTeam(Entity unit, Team team, ServerLevel level, boolean add) {
        if (team == null) return;
        try {
            Class<?> recruit = Class.forName(ROOT + "entities.AbstractRecruitEntity");
            Class.forName(ROOT + "FactionEvents").getMethod(add ? "addRecruitToTeam" : "removeRecruitFromTeam", recruit, Team.class, ServerLevel.class).invoke(null, unit, team, level);
        } catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.team_restoration","The recruit's team could not be restored.").because(failure); }
    }
    private static Object field(Object object, Class<?> owner, Class<?> type) {
        var fields = Arrays.stream(owner.getDeclaredFields()).filter(f -> type.isAssignableFrom(f.getType())).toList();
        if (fields.size() != 1) throw Keyed.refuse("compat.mcakingdoms.recruits.storage_shape","This version of Recruits stores entities in a way this transfer does not support.");
        try { fields.get(0).setAccessible(true); return fields.get(0).get(object); }
        catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.storage_inaccessible","Recruits' entity storage cannot be read.").because(failure); }
    }
    /**
     * Explicit actor-triggered persistence barrier. Reads the existing worker; it never instantiates or loads entities.
     * Its cost is recorded in {@link TransferFenceMetrics} (H07).
     */
    public static boolean saveAndConfirm(Entity unit, Snapshot before, boolean transferred) {
        var server = unit.getServer(); RecruitsMilitary.ready(server);
        long start = System.nanoTime();
        call(groups(), "save", new Class<?>[]{ServerLevel.class}, server.overworld());
        call(counts(), "save", new Class<?>[]{ServerLevel.class}, server.overworld());
        try {
            Object factions = Class.forName(ROOT + "FactionEvents").getField("recruitsFactionManager").get(null);
            call(factions, "save", new Class<?>[]{ServerLevel.class}, server.overworld());
        } catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.faction_persistence","Recruits' faction records cannot be saved.").because(failure); }
        var level = (ServerLevel) unit.level();
        var manager = (PersistentEntitySectionManager<?>) field(level, ServerLevel.class, PersistentEntitySectionManager.class);
        int queued = queuedWrites(manager);
        int entities = flushEntityAndOverworldData(unit, manager);
        boolean confirmed = saved(unit, before, transferred);
        TransferFenceMetrics.record(System.nanoTime() - start, entities, queued);
        return confirmed;
    }
    /**
     * Targeted flush (H07): the recruit's own chunk sections and the overworld files {@link #saved} reads (Recruits'
     * groups, unit counts, factions and teams, and the scoreboard). Until 0.3.0 this saved every loaded entity section of
     * the level and every dirty overworld file of every mod on the server thread; other chunks and other mods' data now
     * wait for the normal autosave, which does not affect this transfer's durability. Returns the entities saved.
     */
    private static int flushEntityAndOverworldData(Entity unit, PersistentEntitySectionManager<?> manager) {
        int entities = storeChunk(manager, unit.chunkPosition().toLong());
        var server = unit.getServer();
        @SuppressWarnings("unchecked")
        var cache = (Map<String, net.minecraft.world.level.saveddata.SavedData>) exactField(server.overworld().getDataStorage(),
                net.minecraft.world.level.storage.DimensionDataStorage.class, Map.class);
        var folder = server.getWorldPath(LevelResource.ROOT).resolve("data");
        for (var entry : List.copyOf(cache.entrySet()))
            if (entry.getKey().startsWith("recruit") || entry.getKey().equals("scoreboard")) entry.getValue().save(folder.resolve(entry.getKey() + ".dat").toFile());
        return entities;
    }
    /** Stores one chunk's entity sections through the manager's own chunk save, as its autosave does for each chunk. */
    private static int storeChunk(PersistentEntitySectionManager<?> manager, long chunk) {
        var candidates = Arrays.stream(PersistentEntitySectionManager.class.getDeclaredMethods())
                .filter(m -> m.getReturnType() == boolean.class && Arrays.equals(m.getParameterTypes(), new Class<?>[]{long.class, java.util.function.Consumer.class})).toList();
        if (candidates.size() != 1) throw Keyed.refuse("compat.mcakingdoms.recruits.storage_shape", "This version of Recruits stores entities in a way this transfer does not support.");
        int[] saved = {0};
        java.util.function.Consumer<Object> counted = entity -> saved[0]++;
        try {
            candidates.get(0).setAccessible(true);
            if (!(boolean) candidates.get(0).invoke(manager, chunk, counted))
                throw Keyed.refuse("compat.mcakingdoms.recruits.chunk_pending", "The recruit's area is still loading; try again in a moment.");
        } catch (ReflectiveOperationException failure) {
            throw Keyed.refuse("compat.mcakingdoms.recruits.storage_inaccessible", "Recruits' entity storage cannot be read.").because(failure);
        }
        return saved[0];
    }
    /** Writes the entity I/O worker still has queued; reported with the fence's cost, or -1 when unknown. */
    private static int queuedWrites(PersistentEntitySectionManager<?> manager) {
        try {
            var storage = field(manager, PersistentEntitySectionManager.class, EntityPersistentStorage.class);
            if (!(storage instanceof EntityStorage)) return -1;
            var worker = field(storage, EntityStorage.class, IOWorker.class);
            return ((Map<?, ?>) exactField(worker, IOWorker.class, Map.class)).size();
        } catch (RuntimeException unknown) { return -1; }
    }
    /** The single field declared exactly as {@code type}; a subtype does not count (IOWorker also holds a fastutil map). */
    private static Object exactField(Object object, Class<?> owner, Class<?> type) {
        var fields = Arrays.stream(owner.getDeclaredFields()).filter(f -> f.getType() == type).toList();
        if (fields.size() != 1) throw Keyed.refuse("compat.mcakingdoms.recruits.storage_shape", "This version of Recruits stores entities in a way this transfer does not support.");
        try { fields.get(0).setAccessible(true); return fields.get(0).get(object); }
        catch (ReflectiveOperationException failure) { throw Keyed.refuse("compat.mcakingdoms.recruits.storage_inaccessible", "Recruits' entity storage cannot be read.").because(failure); }
    }
    public static boolean saved(Entity unit, Snapshot before, boolean transferred) {
        return saved(unit,before,transferred,true);
    }
    private static boolean saved(Entity unit, Snapshot before, boolean transferred, boolean accounting) {
        if (!matches(unit, before, transferred,accounting)) return false;
        try {
            var server = unit.getServer(); var level = (ServerLevel) unit.level();
            var manager = field(level, ServerLevel.class, PersistentEntitySectionManager.class);
            var storage = field(manager, PersistentEntitySectionManager.class, EntityPersistentStorage.class);
            if (!(storage instanceof EntityStorage)) return false;
            var worker = (IOWorker) field(storage, EntityStorage.class, IOWorker.class);
            worker.synchronize(true).join();
            var chunk = worker.loadAsync(unit.chunkPosition()).join().orElse(null); if (chunk == null) return false;
            CompoundTag saved = null;
            var entities = chunk.getList("Entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(entities.size(), 4096); i++) {
                var candidate = entities.getCompound(i);
                if (candidate.hasUUID("UUID") && candidate.getUUID("UUID").equals(unit.getUUID())) { saved = candidate; break; }
            }
            UUID owner = transferred ? before.recipient() : before.owner(); UUID group = transferred ? before.recipientGroup() : before.group();
            if (saved == null || !saved.hasUUID("OwnerUUID") || !saved.getUUID("OwnerUUID").equals(owner)
                    || !saved.getBoolean("isOwned") || !saved.hasUUID("Group") || !saved.getUUID("Group").equals(group) || !equipment(saved).equals(before.equipment())) return false;
            var counts = read(server, "recruit_player_unit_data.dat").getCompound("recruitCounts");
            if (counts.getInt(before.owner().toString()) != count(before.owner()) || counts.getInt(before.recipient().toString()) != count(before.recipient())) return false;
            try { if (!saved.contains("paymentTimer", Tag.TAG_INT) || saved.getInt("paymentTimer") != unit.getClass().getField("paymentTimer").getInt(unit)) return false; }
            catch (ReflectiveOperationException failure) { return false; }
            var groups = read(server, "recruitsGroups.dat").getList("groups", Tag.TAG_COMPOUND);
            boolean expectedMember = false, otherMember = false;
            for (int i = 0; i < groups.size(); i++) {
                var entry = groups.getCompound(i); if (!entry.hasUUID("uuid")) continue;
                var members = entry.getList("members", Tag.TAG_COMPOUND); boolean contains = false;
                for (int j = 0; j < members.size(); j++) { var value = members.getCompound(j); if (value.hasUUID("id") && value.getUUID("id").equals(before.unit())) contains = true; }
                if (entry.getUUID("uuid").equals(group)) expectedMember = contains && entry.hasUUID("playerUUID") && entry.getUUID("playerUUID").equals(owner);
                if (entry.getUUID("uuid").equals(transferred ? before.group() : before.recipientGroup())) otherMember = contains;
            }
            if (!expectedMember || otherMember) return false;
            try {
                var nativeData = (net.minecraft.world.level.saveddata.SavedData) Class.forName(ROOT + "world.RecruitsTeamSaveData")
                        .getMethod("get", ServerLevel.class).invoke(null, server.overworld());
                if (!nativeData.save(new CompoundTag()).equals(read(server, "recruitsTeamSaveData.dat"))) return false;
            } catch (ReflectiveOperationException failure) { return false; }
            String team = transferred ? before.recipientTeam() : before.team(); String found = "";
            var teams = read(server, "scoreboard.dat").getList("Teams", Tag.TAG_COMPOUND);
            for (int i = 0; i < teams.size(); i++) {
                var t = teams.getCompound(i); var players = t.getList("Players", Tag.TAG_STRING);
                for (int j = 0; j < players.size(); j++) if (players.getString(j).equals(unit.getStringUUID())) found = t.getString("Name");
            }
            return found.equals(team);
        } catch (java.io.IOException | RuntimeException failure) { return false; }
    }
    private static CompoundTag read(MinecraftServer server, String file) throws java.io.IOException {
        var path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(file);
        if (java.nio.file.Files.size(path) > 16_000_000) throw new java.io.IOException(Keyed.text("compat.mcakingdoms.recruits.data_budget","Recruits' saved data is too large to check for this transfer."));
        try (var input = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(path)))) {
            return NbtIo.read(input, new NbtAccounter(32_000_000)).getCompound("data");
        }
    }
    public record Review(String fingerprint,String observation) { }
    /** Operator attestation acknowledges existing native state; it never changes ownership or counters. */
    public static Review review(Entity unit,Snapshot original,boolean persist) {
        RecruitsMilitary.ready(unit.getServer());
        UUID current=(UUID)call(unit,"getOwnerUUID");
        boolean transferred=original.recipient().equals(current);
        if(!transferred&&!original.owner().equals(current))throw Keyed.refuse("compat.mcakingdoms.recruits.unrelated_owner","The recruit has an unrelated owner; an operator must reconcile it by hand.");
        int source=count(original.owner()),destination=count(original.recipient());
        var observed=new Snapshot(original.unit(),original.owner(),original.recipient(),original.group(),original.recipientGroup(),original.team(),original.recipientTeam(),original.equipment(),original.entity(),
                source+(transferred?1:0),destination-(transferred?1:0),original.paymentTimer(),original.orders());
        if(!matches(unit,observed,transferred,false))throw Keyed.refuse("compat.mcakingdoms.recruits.review_agree","The recruit, its equipment and its group must agree before an operator reconciles.");
        String detail="owner="+current+"; source count="+source+"; recipient count="+destination+"; group="+call(unit,"getGroup")+"; equipment="+equipment(unit);
        if(persist){
            // The existing save fence flushes all native stores; divergence deliberately prevents ordinary acknowledgment.
            saveAndConfirm(unit,observed,transferred);
            if(!saved(unit,observed,transferred,false))throw Keyed.refuse("compat.mcakingdoms.recruits.review_unverified","The reviewed state could not be confirmed as saved.");
        }
        return new Review(GovernmentService.hash(detail),detail);
    }
    private RecruitsTransfer() { }
}
