package dev.otectus.mcakingdoms.compat.capitals;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData.Binding;
import dev.otectus.mcakingdoms.compat.capitals.CapitalsSavedData.Status;
import dev.otectus.mcakingdoms.politics.Sovereignty;
import net.minecraft.server.MinecraftServer;

import java.util.*;

/**
 * MCA Capitals co-install (C1-C3): maps each governing Capitals capital to the kingdom of the settlement holding its MCA
 * village, and answers who governs each kingdom's crown. The evidence is Capitals' own village reference, matched through
 * the settlement's recorded MCA village; coordinates alone never map anything. One governing capital in a kingdom maps
 * automatically (Kingdoms yields the crown, the safe direction); two in one kingdom, or a capital whose settlement moved
 * to another kingdom, are quarantined until an operator accepts one. A mapping is never ended automatically: after
 * Capitals is removed, or a capital stops governing, the kingdom's crown actions stay read-only until an operator
 * releases the mapping, so two rulers never come back at once. Every answer reads Capitals' live records, so a capital
 * founded a moment ago already counts before the next reconciliation writes its mapping.
 */
public final class CapitalsInterop {
    private static final Map<MinecraftServer, CapitalsInterop> RUNTIMES = new WeakHashMap<>();
    static final int INTERVAL = 100;
    private final MinecraftServer server;
    private long readAt = Long.MIN_VALUE;
    private List<CapitalsBridge.CapitalView> capitals = List.of();

    private CapitalsInterop(MinecraftServer server) {
        this.server = server;
    }

    public static CapitalsInterop get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Capitals interop requires the server thread");
        return RUNTIMES.computeIfAbsent(server, CapitalsInterop::new);
    }

    public static void clear(MinecraftServer server) {
        RUNTIMES.remove(server);
    }

    /** The legacy switch: every crown action read-only whenever Capitals is installed, as before 0.3.0. */
    private static boolean legacyAllReadOnly() {
        return dev.otectus.mcakingdoms.config.McaKingdomsConfig.CAPITALS_INTEROP.get() == dev.otectus.mcakingdoms.config.McaKingdomsConfig.CapitalsInterop.ALL_READ_ONLY;
    }

    /** Capitals' capitals, read at most once per tick. */
    public List<CapitalsBridge.CapitalView> capitals() {
        long now = server.getTickCount();
        if (now != readAt) { capitals = CapitalsBridge.capitals(server); readAt = now; }
        return capitals;
    }

    /** Reads Capitals again at the next question instead of reusing this tick's reading (tests). */
    public void refresh() {
        readAt = Long.MIN_VALUE;
    }

    /** The settlement and kingdom holding a capital's MCA village, when MCA: Kingdoms has recognized that village. */
    public Optional<SettlementView> settlement(CapitalsBridge.CapitalView capital) {
        return McaKingdomsApi.get(server).getSettlementForMcaVillage(capital.dimension(), capital.villageId());
    }

    /** Who governs {@code kingdom}'s crown now (C2). */
    public Sovereignty sovereignty(String kingdom) {
        var capabilities = CapitalsBridge.capabilities();
        if (capabilities.availability() != CapitalsBridge.Availability.ABSENT && legacyAllReadOnly())
            return new Sovereignty(Sovereignty.Mode.UNSUPPORTED, "politics.mcakingdoms.sovereignty.capitals_all_read_only");
        if (capabilities.availability() == CapitalsBridge.Availability.UNSUPPORTED)
            return new Sovereignty(Sovereignty.Mode.UNSUPPORTED, "politics.mcakingdoms.sovereignty.capitals_unsupported");
        var bindings = CapitalsSavedData.get(server).bindings().values().stream().filter(b -> b.kingdom().equals(kingdom) && b.status() != Status.RELEASED).toList();
        // A capital an operator accepted settles the kingdom even while its rivals stay quarantined.
        boolean accepted = bindings.stream().anyMatch(b -> b.status() == Status.MAPPED && b.decidedBy() != null);
        if (!accepted && bindings.stream().anyMatch(b -> b.status() == Status.QUARANTINED))
            return new Sovereignty(Sovereignty.Mode.UNMAPPED, "politics.mcakingdoms.sovereignty.capitals_quarantined");
        var live = governingIn(kingdom);
        if (bindings.stream().anyMatch(b -> b.status() == Status.MAPPED)) {
            if (!capabilities.readable()) return new Sovereignty(Sovereignty.Mode.CAPITALS, "politics.mcakingdoms.sovereignty.capitals_removed");
            boolean governing = bindings.stream().anyMatch(b -> b.status() == Status.MAPPED && live.stream().anyMatch(c -> c.id().equals(b.capital())));
            return new Sovereignty(Sovereignty.Mode.CAPITALS, governing ? "politics.mcakingdoms.sovereignty.capitals_managed" : "politics.mcakingdoms.sovereignty.capitals_gone");
        }
        // Not mapped yet: a governing capital already in this kingdom keeps its crown actions read-only until reconciliation.
        if (!live.isEmpty()) return new Sovereignty(Sovereignty.Mode.UNMAPPED, "politics.mcakingdoms.sovereignty.capitals_pending");
        return Sovereignty.NATIVE;
    }

    /** The governing capitals whose village lies in {@code kingdom}. */
    public List<CapitalsBridge.CapitalView> governingIn(String kingdom) {
        if (!CapitalsBridge.capabilities().readable()) return List.of();
        return capitals().stream().filter(CapitalsBridge.CapitalView::governing)
                .filter(c -> settlement(c).map(s -> s.kingdomId().toString().equals(kingdom)).orElse(false)).toList();
    }

    /** The mapped, governing capital of {@code kingdom}, for read-through pages. */
    public Optional<CapitalsBridge.CapitalView> crownOf(String kingdom) {
        var mapped = CapitalsSavedData.get(server).bindings().values().stream().filter(b -> b.kingdom().equals(kingdom) && b.status() == Status.MAPPED)
                .map(Binding::capital).toList();
        return capitals().stream().filter(c -> mapped.contains(c.id())).findFirst();
    }

    /** One proposed or kept mapping, for the dry-run report. */
    public record Proposal(CapitalsBridge.CapitalView capital, Optional<SettlementView> settlement, Optional<Binding> current, Status proposed, String reasonKey) { }

    /**
     * The mappings the evidence supports now, without writing anything (C3 dry run): one governing capital in a kingdom
     * maps; several in one kingdom, or one whose settlement changed kingdom, are quarantined unless an operator accepted it.
     */
    public List<Proposal> proposals() {
        var data = CapitalsSavedData.get(server);
        var out = new ArrayList<Proposal>();
        var governing = capitals().stream().filter(CapitalsBridge.CapitalView::governing).toList();
        Map<String, List<CapitalsBridge.CapitalView>> byKingdom = new LinkedHashMap<>();
        for (var capital : governing) settlement(capital).ifPresent(s -> byKingdom.computeIfAbsent(s.kingdomId().toString(), k -> new ArrayList<>()).add(capital));
        for (var capital : governing) {
            var settlement = settlement(capital);
            // A released mapping was for a capital that had stopped governing; governing again is new evidence.
            var current = data.binding(capital.id()).filter(b -> b.status() != Status.RELEASED);
            if (settlement.isEmpty()) { out.add(new Proposal(capital, settlement, current, null, "politics.mcakingdoms.capitals.reason.unrecognized")); continue; }
            String kingdom = settlement.get().kingdomId().toString();
            if (current.isPresent() && !current.get().kingdom().equals(kingdom)) { out.add(new Proposal(capital, settlement, current, Status.QUARANTINED, "politics.mcakingdoms.capitals.reason.moved")); continue; }
            var rivals = byKingdom.getOrDefault(kingdom, List.of());
            boolean accepted = current.isPresent() && current.get().status() == Status.MAPPED && current.get().decidedBy() != null;
            boolean rivalAccepted = rivals.stream().anyMatch(r -> !r.id().equals(capital.id()) && data.binding(r.id()).map(b -> b.status() == Status.MAPPED && b.decidedBy() != null).orElse(false));
            if (accepted) out.add(new Proposal(capital, settlement, current, Status.MAPPED, "politics.mcakingdoms.capitals.reason.accepted"));
            else if (rivalAccepted) out.add(new Proposal(capital, settlement, current, Status.QUARANTINED, "politics.mcakingdoms.capitals.reason.other_accepted"));
            else if (rivals.size() > 1) out.add(new Proposal(capital, settlement, current, Status.QUARANTINED, "politics.mcakingdoms.capitals.reason.several"));
            else if (current.isPresent() && current.get().status() == Status.QUARANTINED && current.get().decidedBy() != null)
                out.add(new Proposal(capital, settlement, current, Status.QUARANTINED, current.get().reason()));
            else out.add(new Proposal(capital, settlement, current, Status.MAPPED, "politics.mcakingdoms.capitals.reason.village"));
        }
        return out;
    }

    /** Writes the mappings the evidence supports (bounded, every {@value #INTERVAL} ticks); unchanged mappings write nothing. */
    public void reconcile() {
        if (!CapitalsBridge.capabilities().readable() || legacyAllReadOnly()) return;
        var data = CapitalsSavedData.get(server);
        if (!data.writable()) return;
        var next = new LinkedHashMap<>(data.bindings());
        long now = server.overworld().getGameTime();
        for (var proposal : proposals()) {
            if (proposal.proposed() == null || proposal.proposed() == Status.RELEASED || proposal.settlement().isEmpty()) continue;
            var current = proposal.current().orElse(null);
            if (current != null && current.status() == proposal.proposed() && current.reason().equals(proposal.reasonKey())) continue;
            var settlement = proposal.settlement().get();
            UUID by = current == null ? null : proposal.proposed() == current.status() ? current.decidedBy() : null;
            next.put(proposal.capital().id(), new Binding(proposal.capital().id(), current != null && proposal.proposed() == Status.QUARANTINED ? current.kingdom() : settlement.kingdomId().toString(),
                    settlement.id(), proposal.capital().dimension().toString(), proposal.capital().villageId(), proposal.proposed(), proposal.reasonKey(), by, now));
        }
        if (!next.equals(data.bindings()) && data.commit(server, next))
            com.mojang.logging.LogUtils.getLogger().info("[MCA: Kingdoms] MCA Capitals mappings updated (revision {})", data.revision());
    }

    public void tick() {
        if (server.getTickCount() % INTERVAL == 0) reconcile();
    }

    /** An operator accepts {@code capital} as its kingdom's crown; other capitals in that kingdom are quarantined. */
    public String accept(UUID operator, UUID capital) {
        var data = CapitalsSavedData.get(server);
        dev.otectus.mcakingdoms.interaction.Keyed.require(data.writable(), "politics.mcakingdoms.capitals.read_only", "The MCA Capitals mappings are kept read-only.");
        var view = capitals().stream().filter(c -> c.id().equals(capital) && c.governing()).findFirst()
                .orElseThrow(() -> dev.otectus.mcakingdoms.interaction.Keyed.refuse("politics.mcakingdoms.capitals.not_governing", "That capital is not governing in MCA Capitals right now."));
        var settlement = settlement(view).orElseThrow(() -> dev.otectus.mcakingdoms.interaction.Keyed.refuse("politics.mcakingdoms.capitals.reason.unrecognized",
                "MCA: Kingdoms has not recognized this capital's village yet."));
        String kingdom = settlement.kingdomId().toString();
        long now = server.overworld().getGameTime();
        var next = new LinkedHashMap<>(data.bindings());
        next.put(capital, new Binding(capital, kingdom, settlement.id(), view.dimension().toString(), view.villageId(), Status.MAPPED, "politics.mcakingdoms.capitals.reason.accepted", operator, now));
        for (var other : governingIn(kingdom)) if (!other.id().equals(capital))
            next.put(other.id(), new Binding(other.id(), kingdom, settlement(other).map(SettlementView::id).orElse(null), other.dimension().toString(), other.villageId(),
                    Status.QUARANTINED, "politics.mcakingdoms.capitals.reason.other_accepted", operator, now));
        dev.otectus.mcakingdoms.interaction.Keyed.require(data.commit(server, next), "politics.mcakingdoms.capitals.save_failed", "The mapping could not be saved, so nothing changed.");
        return dev.otectus.mcakingdoms.interaction.Keyed.text("politics.mcakingdoms.capitals.accepted", "MCA Capitals now governs the crown of %s; MCA: Kingdoms keeps its civic offices, agreements, guilds and honors.",
                dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, kingdom));
    }

    /**
     * An operator releases a mapping on purpose, returning the kingdom's crown to MCA: Kingdoms. Refused while Capitals
     * still governs that capital, which would leave two rulers.
     */
    public String release(UUID operator, UUID capital) {
        var data = CapitalsSavedData.get(server);
        dev.otectus.mcakingdoms.interaction.Keyed.require(data.writable(), "politics.mcakingdoms.capitals.read_only", "The MCA Capitals mappings are kept read-only.");
        var binding = data.binding(capital).orElseThrow(() -> dev.otectus.mcakingdoms.interaction.Keyed.refuse("politics.mcakingdoms.capitals.no_mapping", "That capital has no mapping."));
        boolean governing = capitals().stream().anyMatch(c -> c.id().equals(capital) && c.governing());
        dev.otectus.mcakingdoms.interaction.Keyed.require(!governing, "politics.mcakingdoms.capitals.still_governing",
                "MCA Capitals still governs this capital; releasing it would give the kingdom two rulers. End it in Capitals first.");
        var next = new LinkedHashMap<>(data.bindings());
        next.put(capital, binding.with(Status.RELEASED, "politics.mcakingdoms.capitals.reason.released", operator, server.overworld().getGameTime()));
        dev.otectus.mcakingdoms.interaction.Keyed.require(data.commit(server, next), "politics.mcakingdoms.capitals.save_failed", "The mapping could not be saved, so nothing changed.");
        return dev.otectus.mcakingdoms.interaction.Keyed.text("politics.mcakingdoms.capitals.released", "The mapping is released; crown actions of %s follow MCA: Kingdoms again unless another capital governs there.",
                dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, binding.kingdom()));
    }
}
