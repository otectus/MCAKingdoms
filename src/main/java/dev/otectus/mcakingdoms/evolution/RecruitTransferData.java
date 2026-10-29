package dev.otectus.mcakingdoms.evolution;

import com.google.gson.*;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsTransfer.Snapshot;
import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;

public final class RecruitTransferData extends dev.otectus.mcakingdoms.persistence.DurableSavedData {
    public static final String NAME = "mcakingdoms_recruit_transfers";
    static final Gson JSON = new Gson();
    /**
     * {@code EXPIRED} (0.3.0, F10): an offer that passed its deadline before any native change. Terminal, like
     * {@code CANCELLED}; a build that predates it reads such a record as malformed and keeps the store read-only and
     * unchanged rather than guessing.
     */
    public enum Phase { OFFERED, CONSENTED, APPLYING, CONFIRMING, RESTORING, COMPLETE, CANCELLED, RECONCILED, EXPIRED }
    public record Reconciliation(UUID actor,String fingerprint,String reason,long at,String observation) {
        public Reconciliation { Objects.requireNonNull(actor);EvolutionState.text(reason,256);EvolutionState.text(observation,512);
            if(reason.isBlank()||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")||at<0)throw new IllegalArgumentException("Invalid reconciliation receipt"); }
    }
    public record Transfer(UUID id, UUID unit, UUID owner, UUID recipient, UUID recipientGroup,
                           String equipment, boolean family, long noticeUntil, long deadline, long revision,
                           Phase phase, Snapshot snapshot, String detail, boolean accountingDiverged, Reconciliation reconciliation) {
        public Transfer(UUID id, UUID unit, UUID owner, UUID recipient, UUID recipientGroup, String equipment, boolean family,
                        long noticeUntil, long deadline, long revision, Phase phase, Snapshot snapshot, String detail,boolean accountingDiverged) {
            this(id,unit,owner,recipient,recipientGroup,equipment,family,noticeUntil,deadline,revision,phase,snapshot,detail,accountingDiverged,null);
        }
        public Transfer(UUID id, UUID unit, UUID owner, UUID recipient, UUID recipientGroup, String equipment, boolean family,
                        long noticeUntil, long deadline, long revision, Phase phase, Snapshot snapshot, String detail) {
            this(id,unit,owner,recipient,recipientGroup,equipment,family,noticeUntil,deadline,revision,phase,snapshot,detail,false);
        }
        public Transfer {
            Objects.requireNonNull(id); Objects.requireNonNull(unit); Objects.requireNonNull(owner); Objects.requireNonNull(recipient); Objects.requireNonNull(recipientGroup);
            Objects.requireNonNull(phase); EvolutionState.text(detail, dev.otectus.mcakingdoms.interaction.Keyed.LIMIT);
            if((phase==Phase.RECONCILED)!=(reconciliation!=null))throw new IllegalArgumentException("Reconciliation receipt required");
            if (owner.equals(recipient) || equipment == null || !equipment.matches("[a-f0-9]{64}") || noticeUntil < 0 || deadline <= noticeUntil || revision < 1
                    || (phase == Phase.APPLYING || phase == Phase.CONFIRMING || phase == Phase.RESTORING || phase == Phase.COMPLETE || phase == Phase.RECONCILED) && snapshot == null
                    || snapshot != null && (!snapshot.unit().equals(unit) || !snapshot.owner().equals(owner) || !snapshot.recipient().equals(recipient)
                    || !snapshot.recipientGroup().equals(recipientGroup) || !snapshot.equipment().equals(equipment))) throw new IllegalArgumentException("Invalid transfer terms");
        }
        public boolean terminal() { return phase == Phase.COMPLETE || phase == Phase.CANCELLED || phase == Phase.RECONCILED || phase == Phase.EXPIRED; }
        /** Overdue and untouched: offered or consented, past its deadline, with no native snapshot or accounting divergence. */
        public boolean expirable(long now) {
            return (phase == Phase.OFFERED || phase == Phase.CONSENTED) && now >= deadline && snapshot == null && !accountingDiverged;
        }
        public Transfer phase(Phase next, Snapshot proof, String reason) {
            return new Transfer(id, unit, owner, recipient, recipientGroup, equipment, family, noticeUntil, deadline, revision + 1, next, proof, reason, accountingDiverged);
        }
        public Transfer consent(UUID actor, long expected, long now) {
            if (phase != Phase.OFFERED || expected != revision || !recipient.equals(actor) || now >= deadline) throw new IllegalArgumentException("Transfer changed or recipient consent unavailable");
            return phase(Phase.CONSENTED, null, "Recipient consented to the frozen unit, equipment and native group");
        }
    }
    private Map<UUID, Transfer> transfers = new LinkedHashMap<>();
    private CompoundTag preserved;
    public static RecruitTransferData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Transfer requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(RecruitTransferData::load, RecruitTransferData::new, NAME);
    }
    public static RecruitTransferData load(CompoundTag tag) {
        var data = new RecruitTransferData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1 || !tag.contains("Transfers", Tag.TAG_STRING)
                    || tag.getString("Transfers").length() > 16_000_000) throw new IllegalArgumentException("Unsupported transfer payload");
            Transfer[] values = JSON.fromJson(tag.getString("Transfers"), Transfer[].class);
            if (values == null || values.length > LIMIT) throw new IllegalArgumentException("Transfer capacity exceeded");
            Set<UUID> active = new HashSet<>();
            for (var value : values) if (value == null || data.transfers.putIfAbsent(value.id(), value) != null || !value.terminal() && !active.add(value.unit()))
                throw new IllegalArgumentException("Duplicate transfer intent");
        } catch (RuntimeException failure) { data.transfers.clear(); data.preserved = tag.copy(); }
        return data;
    }
    public boolean writable() { return preserved == null; }
    /** Every transfer, finished ones included, counts towards this bound until an operator archives finished ones (F12). */
    public static final int LIMIT = 1024;
    public static java.util.List<dev.otectus.mcakingdoms.persistence.Capacity> capacity(MinecraftServer server) {
        var data = get(server);
        return java.util.List.of(new dev.otectus.mcakingdoms.persistence.Capacity(NAME, "transfers", data.transfers.size(), LIMIT, dev.otectus.mcakingdoms.persistence.Capacity.Relief.COMPACT_TRANSFERS));
    }
    public static dev.otectus.mcakingdoms.persistence.StoreStatus status(MinecraftServer server) { var data = get(server); return dev.otectus.mcakingdoms.persistence.StoreStatus.of(NAME, data.writable(), ""); }
    public boolean accountingSafe(UUID unit) { return writable() && transfers.values().stream().noneMatch(t -> t.unit().equals(unit) && !t.terminal() && t.accountingDiverged()); }
    /** Called before native accounting changes, including unrelated deaths and administrative recounts. */
    public void accountingChanging(MinecraftServer server, UUID owner, UUID expectedUnit) {
        for (var t : List.copyOf(transfers.values())) if (!t.terminal() && t.snapshot()!=null && !t.accountingDiverged()
                && !t.unit().equals(expectedUnit) && (t.owner().equals(owner)||t.recipient().equals(owner))) {
            var changed=new Transfer(t.id(),t.unit(),t.owner(),t.recipient(),t.recipientGroup(),t.equipment(),t.family(),t.noticeUntil(),t.deadline(),t.revision()+1,t.phase(),t.snapshot(),"Unrelated native accounting changed; automatic recovery requires operator reconciliation",true);
            if(!put(server,changed)) throw new IllegalStateException("Cannot preserve pending transfer accounting before native mutation");
        }
    }
    Optional<Transfer> get(UUID id) { return Optional.ofNullable(transfers.get(id)); }

    /** Finished transfers are archived only this long after their deadline: three Minecraft days. */
    public static final long ARCHIVE_AFTER = 72_000L;

    /**
     * Finished transfers an operator may archive (F12): complete, cancelled or expired, past the deadline by
     * {@link #ARCHIVE_AFTER}, never accounting-diverged. Reconciled transfers stay as operator evidence; anything not
     * finished keeps its recovery fence. Nothing here frees a recruit whose native state is uncertain.
     */
    public List<Transfer> archivable(long now) {
        return transfers.values().stream().filter(t -> (t.phase() == Phase.COMPLETE || t.phase() == Phase.CANCELLED || t.phase() == Phase.EXPIRED)
                && !t.accountingDiverged() && now - t.deadline() >= ARCHIVE_AFTER).toList();
    }

    /**
     * Archives {@code archivable(now)} to a file, then removes those records in one durable commit. Returns the archive
     * file and how many records moved, or empty when there was nothing to archive. Throws when the archive or the store
     * cannot be written; an archive written before a failed commit only duplicates records that are still active.
     */
    public Optional<java.util.Map.Entry<java.nio.file.Path, Integer>> compact(MinecraftServer server, long now) throws java.io.IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Transfer requires server thread");
        if (!writable()) throw new IllegalStateException("Transfer records are read-only; nothing can be archived");
        var archived = archivable(now);
        if (archived.isEmpty()) return Optional.empty();
        var file = dev.otectus.mcakingdoms.persistence.Archive.write(server.getWorldPath(LevelResource.ROOT), NAME, now,
                "Finished recruit transfers past their deadline by " + ARCHIVE_AFTER + " ticks", JSON.toJsonTree(archived));
        var next = new LinkedHashMap<>(transfers);
        archived.forEach(t -> next.remove(t.id()));
        AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
        transfers = next; setDirty(false);
        return Optional.of(java.util.Map.entry(file, archived.size()));
    }
    Collection<Transfer> all() { return List.copyOf(transfers.values()); }
    boolean put(MinecraftServer server, Transfer transfer) {
        if (!server.isSameThread()) throw new IllegalStateException("Transfer requires server thread");
        var previous = transfers.get(transfer.id());
        if(previous != null && previous.accountingDiverged() && !transfer.accountingDiverged()) return false;
        if (!writable() || !transfers.containsKey(transfer.id()) && transfers.size() >= LIMIT
                || transfers.values().stream().anyMatch(t -> !t.id().equals(transfer.id()) && !t.terminal() && t.unit().equals(transfer.unit()))) return false;
        var next = new LinkedHashMap<>(transfers); next.put(transfer.id(), transfer);
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            transfers = next; setDirty(false); return true;
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Transfer intent save refused", failure); return false; }
    }
    private static CompoundTag encode(Map<UUID, Transfer> values) {
        String payload = JSON.toJson(values.values());
        if (payload.length() > 16_000_000) throw new IllegalArgumentException("Transfer storage budget reached");
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Transfers", payload); return tag;
    }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(transfers) : preserved.copy(); }
}
