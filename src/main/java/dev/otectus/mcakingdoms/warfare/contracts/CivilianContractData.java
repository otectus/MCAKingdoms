package dev.otectus.mcakingdoms.warfare.contracts;

import com.google.gson.Gson;
import dev.otectus.mcakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;

/** Durable owner ledger. Accepted identities and terminal proofs are never retired implicitly. */
final class CivilianContractData extends SavedData {
    static final String NAME = "mcakingdoms_civilian_contracts";
    static final int CAPACITY = 16_384;
    private static final Gson JSON = new Gson();

    enum Status { OFFERED, ACCEPTED, CANCELLED, COMPLETED }

    record Completion(UUID providerEpoch, UUID receipt, long completedAt) {
        Completion {
            Objects.requireNonNull(providerEpoch); Objects.requireNonNull(receipt);
            if (completedAt < 0) throw new IllegalArgumentException("Invalid completion time");
        }
    }

    record Contract(UUID id, UUID player, UUID giver, UUID institution, UUID settlement,
                    String organization, String kind, String quest, String recognizedKingdom,
                    String nativeController, String autonomy, long controlSequence,
                    long politicalRevision, long institutionRevision, long offeredAt, long offerExpires,
                    Status status, UUID instance, Completion completion, UUID scope) {
        Contract(UUID id, UUID player, UUID giver, UUID institution, UUID settlement,
                 String organization, String kind, String quest, String recognizedKingdom,
                 String nativeController, String autonomy, long controlSequence,
                 long politicalRevision, long institutionRevision, long offeredAt, long offerExpires,
                 Status status, UUID instance, Completion completion) {
            this(id, player, giver, institution, settlement, organization, kind, quest, recognizedKingdom,
                    nativeController, autonomy, controlSequence, politicalRevision, institutionRevision, offeredAt,
                    offerExpires, status, instance, completion, null);
        }
        Contract {
            Objects.requireNonNull(id); Objects.requireNonNull(player); Objects.requireNonNull(giver);
            Objects.requireNonNull(institution); Objects.requireNonNull(settlement); Objects.requireNonNull(status);
            token(organization, 128); token(kind, 32); token(quest, 128); token(recognizedKingdom, 128);
            token(nativeController, 128); token(autonomy, 128);
            CivilianContractKind parsed = CivilianContractKind.parse(kind);
            if (parsed.scoped() != (scope != null)) throw new IllegalArgumentException("Contract scope mismatch");
            if (!parsed.quest().toString().equals(quest) || controlSequence < 0 || politicalRevision < 0
                    || institutionRevision < 0 || offeredAt < 0 || offerExpires <= offeredAt)
                throw new IllegalArgumentException("Invalid frozen civilian contract");
            if (status == Status.OFFERED && (instance != null || completion != null)
                    || status == Status.ACCEPTED && (instance == null || completion != null)
                    || status == Status.CANCELLED && (instance == null || completion != null)
                    || status == Status.COMPLETED && (instance == null || completion == null
                    || !instance.equals(completion.receipt())))
                throw new IllegalArgumentException("Invalid civilian contract lifecycle");
        }

        Contract accepted(UUID value) {
            return new Contract(id, player, giver, institution, settlement, organization, kind, quest,
                    recognizedKingdom, nativeController, autonomy, controlSequence, politicalRevision,
                    institutionRevision, offeredAt, offerExpires, Status.ACCEPTED,
                    Objects.requireNonNull(value), null, scope);
        }

        /** The same contract, recorded against the settlement a merge kept. */
        Contract forSettlement(UUID survivor) {
            return new Contract(id, player, giver, institution, survivor, organization, kind, quest, recognizedKingdom,
                    nativeController, autonomy, controlSequence, politicalRevision, institutionRevision, offeredAt,
                    offerExpires, status, instance, completion, scope);
        }

        Contract cancelled() {
            return new Contract(id, player, giver, institution, settlement, organization, kind, quest,
                    recognizedKingdom, nativeController, autonomy, controlSequence, politicalRevision,
                    institutionRevision, offeredAt, offerExpires, Status.CANCELLED, instance, null, scope);
        }

        Contract completed(UUID epoch, UUID receipt, long now) {
            return new Contract(id, player, giver, institution, settlement, organization, kind, quest,
                    recognizedKingdom, nativeController, autonomy, controlSequence, politicalRevision,
                    institutionRevision, offeredAt, offerExpires, Status.COMPLETED, instance,
                    new Completion(epoch, receipt, now), scope);
        }
    }

    private Map<UUID, Contract> contracts = new LinkedHashMap<>();
    private CompoundTag preserved;

    static CivilianContractData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CivilianContractData::load,
                CivilianContractData::new, NAME);
    }

    static CivilianContractData load(CompoundTag tag) {
        var data = new CivilianContractData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1
                    || !tag.contains("Contracts", Tag.TAG_STRING))
                throw new IllegalArgumentException("Unsupported civilian contract schema");
            Contract[] values = JSON.fromJson(tag.getString("Contracts"), Contract[].class);
            if (values == null || values.length > CAPACITY) throw new IllegalArgumentException("Contract capacity");
            var activeKeys = new HashSet<String>();
            var instances = new HashSet<UUID>();
            var receipts = new HashSet<String>();
            for (Contract decoded : values) {
                Contract value = validate(decoded);
                String active = serviceKey(value);
                if ((value.status() == Status.OFFERED || value.status() == Status.ACCEPTED
                        || value.status() == Status.COMPLETED) && !activeKeys.add(active))
                    throw new IllegalArgumentException("Duplicate active service");
                if (value.instance() != null && !instances.add(value.instance()))
                    throw new IllegalArgumentException("Duplicate native instance");
                if (value.completion() != null && !receipts.add(value.completion().providerEpoch()
                        + "|" + value.completion().receipt()))
                    throw new IllegalArgumentException("Duplicate completion receipt");
                if (data.contracts.putIfAbsent(value.id(), value) != null)
                    throw new IllegalArgumentException("Duplicate contract");
            }
        } catch (RuntimeException failure) {
            data.contracts.clear(); data.preserved = tag.copy();
        }
        return data;
    }

    boolean writable() { return preserved == null; }
    Optional<Contract> get(UUID id) { return Optional.ofNullable(contracts.get(id)); }

    Optional<Contract> active(UUID player, UUID settlement, CivilianContractKind kind, long now) {
        return active(player, settlement, kind, null, now);
    }
    Optional<Contract> active(UUID player, UUID settlement, CivilianContractKind kind, UUID scope, long now) {
        return active(player, settlement, kind, scope, now, UnaryOperator.identity());
    }
    /** {@code resolve} maps a stored settlement id to the one it denotes now; {@code settlement} is already current. */
    Optional<Contract> active(UUID player, UUID settlement, CivilianContractKind kind, UUID scope, long now,
                              UnaryOperator<UUID> resolve) {
        return contracts.values().stream().filter(c -> c.player().equals(player)
                && resolve.apply(c.settlement()).equals(settlement) && c.kind().equals(kind.id())
                && Objects.equals(c.scope(), scope)
                && (c.status() == Status.OFFERED && c.offerExpires() > now
                || c.status() == Status.ACCEPTED || c.status() == Status.COMPLETED))
                .findFirst();
    }

    Optional<Contract> completed(UUID player, UUID settlement, CivilianContractKind kind) {
        return completed(player, settlement, kind, null);
    }
    Optional<Contract> completed(UUID player, UUID settlement, CivilianContractKind kind, UUID scope) {
        return completed(player, settlement, kind, scope, UnaryOperator.identity());
    }
    Optional<Contract> completed(UUID player, UUID settlement, CivilianContractKind kind, UUID scope,
                                 UnaryOperator<UUID> resolve) {
        return contracts.values().stream().filter(c -> c.player().equals(player)
                && resolve.apply(c.settlement()).equals(settlement) && c.kind().equals(kind.id())
                && Objects.equals(c.scope(), scope)
                && c.status() == Status.COMPLETED).findFirst();
    }
    boolean hasUnsettledOrganization(String organization) {
        return !writable() || contracts.values().stream().anyMatch(c -> c.organization().equals(organization) && c.status() == Status.ACCEPTED);
    }

    boolean put(MinecraftServer server, Contract contract) {
        if (!writable()) return false;
        long now = server.overworld().getGameTime();
        var next = preparePut(contracts.values(), contract, now).orElse(null);
        return next != null && commit(server, next);
    }

    /** Replaces only stale, never-accepted offers and preserves every accepted or completed record. */
    static Optional<LinkedHashMap<UUID, Contract>> preparePut(Collection<Contract> current,
                                                               Contract contract, long now) {
        Objects.requireNonNull(current); Objects.requireNonNull(contract);
        if (contract.status() == Status.OFFERED && contract.offerExpires() <= now) return Optional.empty();
        var next = new LinkedHashMap<UUID, Contract>();
        String incomingKey = serviceKey(contract);
        for (Contract existing : current) {
            if (existing.status() == Status.OFFERED && existing.offerExpires() <= now) continue;
            if (!existing.id().equals(contract.id()) && serviceKey(existing).equals(incomingKey)
                    && (existing.status() == Status.OFFERED || existing.status() == Status.ACCEPTED
                    || existing.status() == Status.COMPLETED)) return Optional.empty();
            next.put(existing.id(), existing);
        }
        if (!next.containsKey(contract.id()) && next.size() >= CAPACITY) return Optional.empty();
        next.put(contract.id(), contract);
        return Optional.of(next);
    }

    /** Moves the contracts of a merged settlement to the survivor; throws when the moved ledger cannot be saved. */
    boolean redirectSettlement(MinecraftServer server, UUID source, UUID target) {
        if (!writable()) return false;
        var next = prepareRedirect(contracts.values(), source, target);
        if (next.isEmpty()) return false;
        if (!commit(server, next.get())) throw new IllegalStateException("Civilian contract ledger could not be saved");
        return true;
    }

    /**
     * The ledger with every contract of {@code source} recorded against {@code target}, except one whose move would
     * duplicate an active service the survivor already records: that one keeps the retired id, which still resolves
     * on read. Empty when nothing moves.
     */
    static Optional<LinkedHashMap<UUID, Contract>> prepareRedirect(Collection<Contract> current, UUID source, UUID target) {
        var activeKeys = new HashSet<String>();
        current.stream().filter(CivilianContractData::activeService).forEach(c -> activeKeys.add(serviceKey(c)));
        var next = new LinkedHashMap<UUID, Contract>();
        boolean changed = false;
        for (Contract existing : current) {
            Contract kept = existing;
            if (existing.settlement().equals(source)) {
                Contract moved = existing.forSettlement(target);
                if (!activeService(existing) || activeKeys.add(serviceKey(moved))) {
                    kept = moved;
                    changed = true;
                }
            }
            next.put(kept.id(), kept);
        }
        return changed ? Optional.of(next) : Optional.empty();
    }

    private static boolean activeService(Contract value) {
        return value.status() == Status.OFFERED || value.status() == Status.ACCEPTED || value.status() == Status.COMPLETED;
    }

    private static String serviceKey(Contract value) {
        return value.player() + "|" + value.settlement() + "|" + value.kind() + "|" + value.scope();
    }

    private boolean commit(MinecraftServer server, Map<UUID, Contract> next) {
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve(NAME + ".dat").toFile(), encode(next));
            contracts = next; setDirty(false); return true;
        } catch (IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Civilian contract save failed", failure);
            return false;
        }
    }

    private static CompoundTag encode(Map<UUID, Contract> values) {
        var tag = new CompoundTag(); tag.putInt("Schema", 1);
        tag.putString("Contracts", JSON.toJson(values.values())); return tag;
    }

    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(contracts) : preserved.copy(); }

    private static void token(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid civilian contract token");
    }

    /** Gson may allocate records without invoking their canonical constructor. Rebuild at the boundary. */
    private static Contract validate(Contract value) {
        if (value == null) throw new IllegalArgumentException("Null contract");
        Completion completion = value.completion() == null ? null : new Completion(
                value.completion().providerEpoch(), value.completion().receipt(), value.completion().completedAt());
        return new Contract(value.id(), value.player(), value.giver(), value.institution(), value.settlement(),
                value.organization(), value.kind(), value.quest(), value.recognizedKingdom(), value.nativeController(),
                value.autonomy(), value.controlSequence(), value.politicalRevision(), value.institutionRevision(),
                value.offeredAt(), value.offerExpires(), value.status(), value.instance(), completion, value.scope());
    }
}
