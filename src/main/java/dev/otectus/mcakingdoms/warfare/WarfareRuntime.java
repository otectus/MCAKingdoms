package dev.otectus.mcakingdoms.warfare;

import dev.otectus.mcakingdoms.interaction.Keyed;

import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.event.SettlementMutationPreflightEvent;
import dev.otectus.mcakingdoms.compat.recruits.RecruitsObservation;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

/** Bounded reconciliation of explicit bindings. Native siege policy and native writes stay native. */
public final class WarfareRuntime {
    private static final Map<MinecraftServer, WarfareRuntime> RUNTIMES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final ControlSavedData data;
    private final dev.otectus.mcakingdoms.compat.recruits.RecruitsDurableClaims.Reader durableReader = new dev.otectus.mcakingdoms.compat.recruits.RecruitsDurableClaims.Reader();
    private final Map<UUID, String> availability = new HashMap<>();
    private UUID cursor;
    private WarfareRuntime(MinecraftServer server) {
        this.server = server; kingdoms = McaKingdomsApi.get(server); data = ControlSavedData.get(server);
    }
    public static WarfareRuntime get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Warfare requires server thread");
        return RUNTIMES.computeIfAbsent(server, WarfareRuntime::new);
    }
    public static void clear(MinecraftServer server) { RUNTIMES.remove(server); }
    public long revision() { return data.revision(); }
    public Optional<ControlState.Binding> binding(UUID settlement) { return data.binding(kingdoms.resolveSettlementId(settlement)); }
    public Optional<ControlState.Mapping> mapping(String nativeFaction) { return data.mapping(nativeFaction); }
    public List<ControlState.Binding> bindings() { return data.bindings(); }
    /** Every registered native-faction-to-kingdom link, read only. */
    public java.util.Collection<ControlState.Mapping> mappings() { return java.util.List.copyOf(data.snapshot().mappings.values()); }
    /** A recognized autonomy as players read it: the Sovereignty label when known, otherwise its words. */
    private static net.minecraft.network.chat.Component autonomy(String value) {
        try { return dev.otectus.mcakingdoms.interaction.ActionRegistry.enumLabel(CampaignState.Sovereignty.valueOf(value)); }
        catch (IllegalArgumentException unknown) { return net.minecraft.network.chat.Component.literal(dev.otectus.mcakingdoms.interaction.Names.words(value)); }
    }
    public boolean confirmed(UUID settlement) {
        return WarfareConfig.enabled() && data.writable()
                && Keyed.key(availability.getOrDefault(kingdoms.resolveSettlementId(settlement), "")).equals("warfare.mcakingdoms.control.confirmed");
    }
    public Optional<ControlState.Binding> verifiedBinding(UUID settlement) {
        if (!server.isSameThread() || !WarfareConfig.enabled() || !data.writable()) return Optional.empty();
        var binding = binding(settlement).orElse(null); if (binding == null) return Optional.empty();
        var nativeView = RecruitsObservation.claim(server, binding.claim());
        var saved = durableReader.snapshot(server).claims().get(binding.claim());
        var site = RecruitsObservation.at(server, new net.minecraft.world.level.ChunkPos(binding.chunkX(), binding.chunkZ()));
        if (nativeView.status() != RecruitsObservation.Status.AVAILABLE || saved == null || !nativeView.ownerId().equals(binding.owner())
                || !saved.owner().equals(binding.owner()) || saved.siege() != nativeView.underSiege()
                || !site.claimId().equals(binding.claim().toString())
                || !saved.chunks().contains(net.minecraft.world.level.ChunkPos.asLong(binding.chunkX(), binding.chunkZ()))) return Optional.empty();
        return Optional.of(binding);
    }
    private void enabled() {
        if (!WarfareConfig.enabled()) throw Keyed.refuse("warfare.mcakingdoms.control.disabled","Political warfare is turned off on this server.");
        if (!data.writable()) throw Keyed.refuse("warfare.mcakingdoms.control.read_only","Control records are read-only; an operator must recover them.");
    }
    private void actor(ServerPlayer actor) {
        if (!server.isSameThread() || actor.getServer() != server) throw Keyed.refuse("warfare.mcakingdoms.control.wrong_server","That request belongs to another server.");
    }
    private static void operator(ServerPlayer actor) {
        if (!actor.hasPermissions(2)) throw Keyed.refuse("warfare.mcakingdoms.control.operator_bindings","Only an operator can register military bindings.");
    }
    /** Operators, or the kingdom's own government with proposal authority, may register its military mappings and bindings. */
    private void authority(ServerPlayer actor, net.minecraft.resources.ResourceLocation kingdom) {
        if (actor.hasPermissions(2)) return;
        if (!dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.get(server).authorized(actor, kingdom.toString(), dev.otectus.mcakingdoms.api.politics.Politics.Permission.PROPOSE))
            throw Keyed.refuse("warfare.mcakingdoms.control.government_bindings","Only an operator, or this kingdom's government with the Propose agreements responsibility, can register military bindings.");
    }
    private void commit(ControlState next) {
        next.revision = Math.addExact(next.revision, 1);
        if (!data.commit(server, next)) throw Keyed.refuse("warfare.mcakingdoms.control.save_failed","Control records could not be saved, so nothing changed.");
    }
    /** Mapping labels refer to an existing native owner observed where the operator stands. */
    public String mapHere(ServerPlayer actor, net.minecraft.resources.ResourceLocation kingdom) {
        actor(actor);
        enabled(); authority(actor, kingdom);
        if (kingdoms.getKingdom(kingdom).filter(KingdomView::defined).isEmpty()) throw Keyed.refuse("warfare.mcakingdoms.control.kingdom_unavailable", "That kingdom is not available.");
        var view = RecruitsObservation.here(actor); requireClaim(view);
        var next = data.snapshot();
        var mapping = new ControlState.Mapping(view.ownerId(), kingdom.toString());
        var existing = next.mappings.get(view.ownerId());
        if (mapping.equals(existing)) return Keyed.text("warfare.mcakingdoms.control.mapping_exists","This military link is already registered.");
        if (existing != null) throw Keyed.refuse("warfare.mcakingdoms.control.mapping_taken","This Recruits faction is already linked to a kingdom; it is never relinked implicitly.");
        if (next.mappings.size() >= ControlState.LIMIT) throw Keyed.refuse("warfare.mcakingdoms.control.mapping_capacity","No more military links can be recorded.");
        next.mappings.put(view.ownerId(), mapping); commit(next);
        return Keyed.text("warfare.mcakingdoms.control.mapped", "Recruits faction %s is now linked to %s. Its membership is unchanged.", view.ownerId(), dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, kingdom.toString()));
    }
    public String bindHere(ServerPlayer actor, UUID settlementId) {
        actor(actor);
        enabled();
        var settlement = kingdoms.getSettlement(settlementId).orElseThrow(() -> Keyed.refuse("warfare.mcakingdoms.control.settlement_unavailable", "That settlement is not available."));
        authority(actor, settlement.kingdomId());
        if (kingdoms.getKingdom(settlement.kingdomId()).filter(KingdomView::defined).isEmpty())
            throw Keyed.refuse("warfare.mcakingdoms.control.kingdom_definition","The settlement's kingdom is not defined by the loaded data packs.");
        if (!settlement.dimension().equals(Level.OVERWORLD)
                || kingdoms.getSettlementAt(actor.serverLevel(), actor.blockPosition()).filter(s -> s.id().equals(settlementId)).isEmpty())
            throw Keyed.refuse("warfare.mcakingdoms.control.stand_inside","Stand inside the overworld settlement and its Recruits claim to bind it.");
        var view = RecruitsObservation.here(actor); requireClaim(view);
        UUID claim = UUID.fromString(view.claimId());
        var durable = durableReader.snapshot(server);
        var saved = durable.claims().get(claim);
        if (!durable.available() || saved == null || !saved.owner().equals(view.ownerId()) || saved.siege() != view.underSiege()
                || !saved.chunks().contains(actor.chunkPosition().toLong()))
            throw Keyed.refuse("warfare.mcakingdoms.control.claim_unsaved","The Recruits claim is waiting for a confirmed save; try again after the world saves.");
        var next = data.snapshot();
        if (!next.mappings.containsKey(view.ownerId())) throw Keyed.refuse("warfare.mcakingdoms.control.map_first","Link this Recruits faction to a kingdom first.");
        var existing = next.bindings.get(settlementId);
        if (existing != null && existing.claim().equals(claim)) return Keyed.text("warfare.mcakingdoms.control.binding_exists","This settlement's claim is already bound.");
        if (existing != null || next.retired.containsKey(claim) || next.bindings.values().stream().anyMatch(b -> b.claim().equals(claim)))
            throw Keyed.refuse("warfare.mcakingdoms.control.binding_taken","This settlement or claim is already bound elsewhere; it is never reassigned implicitly.");
        if (next.bindings.size() + next.retired.size() >= ControlState.LIMIT) throw Keyed.refuse("warfare.mcakingdoms.control.binding_capacity","No more claim bindings can be recorded.");
        var condition = view.underSiege() ? ControlState.Condition.UNDER_SIEGE
                : next.mappings.get(view.ownerId()).kingdom().equals(settlement.kingdomId().toString())
                ? ControlState.Condition.CONTROLLED : ControlState.Condition.OCCUPIED;
        var entry = new ControlState.Entry(1, server.overworld().getGameTime(), view.ownerId(), condition);
        next.bindings.put(settlementId, new ControlState.Binding(settlementId, claim, actor.chunkPosition().x,
                actor.chunkPosition().z, settlement.kingdomId().toString(), view.ownerId(), condition, 1, List.of(entry)));
        commit(next); availability.put(settlementId, Keyed.text("warfare.mcakingdoms.control.observed_now", "Observed just now."));
        return Keyed.text("warfare.mcakingdoms.control.bound","The Recruits claim is bound. The recognized government and civic identity are unchanged.");
    }
    private static void requireClaim(RecruitsObservation.View view) {
        if (view.status() != RecruitsObservation.Status.AVAILABLE || view.claimId().isEmpty())
            throw Keyed.refuse("warfare.mcakingdoms.control.claim_unavailable", "The Recruits claim is unavailable: %s", view.status());
    }
    /** Explicit operator opt-out, including provider removal recovery; native state is never modified. */
    public String retire(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); operator(actor);
        if (!data.writable()) throw Keyed.refuse("warfare.mcakingdoms.control.restore_backup","Control records are read-only; restore a supported backup.");
        var next = data.snapshot();
        if (next.revision != expectedRevision) throw Keyed.refuse("warfare.mcakingdoms.control.revision_changed","Control records changed; inspect again before retiring.");
        var binding = next.bindings.remove(id);
        if (binding == null) throw Keyed.refuse("warfare.mcakingdoms.control.no_binding","There is no active binding to retire.");
        next.retired.put(binding.claim(), binding.retire(server.overworld().getGameTime(), actor.getUUID()));
        commit(next); availability.remove(id);
        return Keyed.text("warfare.mcakingdoms.control.retired","Control binding retired; its history is kept. Recruits' control is unchanged, and the settlement may now be merged or reassigned.");
    }
    /** This endpoint discloses control only at the viewer's present settlement, or to operators. */
    public List<String> view(ServerPlayer viewer, UUID requested) {
        actor(viewer);
        UUID id = kingdoms.resolveSettlementId(requested);
        if (!SettlementKnowledge.get(server).visible(viewer, id)) return List.of(Keyed.text("warfare.mcakingdoms.control.info_unavailable","Control information is unavailable."));
        if (!viewer.hasPermissions(2) && kingdoms.getSettlementAt(viewer.serverLevel(), viewer.blockPosition())
                .filter(s -> s.id().equals(id)).isEmpty()) return List.of(Keyed.text("warfare.mcakingdoms.control.visit","Visit this settlement to see its control history."));
        var state = data.snapshot();
        var binding = state.bindings.get(id);
        if (binding == null) binding = state.retired.values().stream().filter(b -> kingdoms.resolveSettlementId(b.settlement()).equals(id))
                .max(Comparator.comparingLong(b -> b.history().get(b.history().size() - 1).gameTime())).orElse(null);
        if (binding == null) return List.of(Keyed.text("warfare.mcakingdoms.control.no_binding_view","No Recruits claim is bound to this settlement."));
        var lines = new ArrayList<String>();
        if (viewer.hasPermissions(2)) lines.add(Keyed.text("warfare.mcakingdoms.control.operator_revision", "Control revision %s. Operators retire this binding with the \"Retire a native claim binding\" task, which reviews the current revision.", state.revision));
        lines.add(!WarfareConfig.enabled() ? Keyed.text("warfare.mcakingdoms.control.state.disabled", "Political warfare is off; this is the last kept record.")
                : !data.writable() ? Keyed.text("warfare.mcakingdoms.control.state.read_only", "Control records are unavailable; an operator must recover them.")
                : binding.condition() == ControlState.Condition.RETIRED ? Keyed.text("warfare.mcakingdoms.control.state.retired", "Retired binding; history only.")
                : availability.getOrDefault(id, Keyed.text("warfare.mcakingdoms.control.state.stale", "Waiting to be checked against Recruits after the server started.")));
        var political = CampaignService.get(server).control(viewer, id);
        lines.add(Keyed.text("warfare.mcakingdoms.control.sovereign", "Recognized government: %s", political.<net.minecraft.network.chat.Component>map(c -> net.minecraft.network.chat.Component.translatableWithFallback("warfare.mcakingdoms.control.sovereign.autonomy", "%s · %s", dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, c.recognizedKingdom()), autonomy(c.autonomy()))).orElse(dev.otectus.mcakingdoms.interaction.Names.kingdomName(server, binding.sovereign()))));
        lines.add(Keyed.text("warfare.mcakingdoms.control.owner", "Observed military owner: %s · %s", binding.owner(), binding.condition()));
        lines.add(Keyed.text("warfare.mcakingdoms.control.observation", "Observation #%s. Occupation does not change civic identity or civilian law.", binding.sequence()));
        binding.history().stream().skip(Math.max(0, binding.history().size() - 6)).forEach(e ->
                lines.add(Keyed.text("warfare.mcakingdoms.control.history", "%s: %s · %s", dev.otectus.mcakingdoms.interaction.ActionRegistry.tr("interaction.mcakingdoms.time.day", "Day %s", e.gameTime() / 24000 + 1), e.condition(), e.owner())));
        lines.add(Keyed.text("warfare.mcakingdoms.control.footer","Peace and autonomy are negotiated through the Warfare tasks, civilian contracts through the Contracts tasks. Local civilian law stays in force."));
        return List.copyOf(lines);
    }
    public List<String> here(ServerPlayer viewer) {
        return kingdoms.getSettlementAt(viewer.serverLevel(), viewer.blockPosition())
                .map(s -> view(viewer, s.id())).orElse(List.of(Keyed.text("warfare.mcakingdoms.control.none_here","No recognized settlement here.")));
    }
    /** Public for operational checks; processes at most one configured batch, without visiting chunks. */
    public void reconcile() {
        if (!server.isSameThread()) throw new IllegalStateException("Control reconciliation requires server thread");
        if (!WarfareConfig.enabled() || !data.writable()) return;
        var next = data.snapshot();
        var ids = next.bindings.keySet().stream().sorted().toList();
        var batch = ids.stream().filter(id -> cursor == null || id.compareTo(cursor) > 0).limit(WarfareConfig.BUDGET.get()).toList();
        if (batch.isEmpty()) { cursor = null; return; }
        var durable = durableReader.snapshot(server);
        boolean changed = false;
        for (UUID id : batch) {
            var binding = next.bindings.get(id);
            var kingdom = net.minecraft.resources.ResourceLocation.tryParse(binding.sovereign());
            if (kingdom == null || kingdoms.getKingdom(kingdom).filter(KingdomView::defined).isEmpty()) {
                cursor = id; availability.put(id, Keyed.text("warfare.mcakingdoms.control.suspended.kingdom","Suspended: the recognized kingdom is not defined by the loaded data packs.")); continue;
            }
            var observation = RecruitsObservation.claim(server, binding.claim());
            cursor = id;
            if (observation.status() != RecruitsObservation.Status.AVAILABLE) {
                availability.put(id, Keyed.text("warfare.mcakingdoms.control.suspended.observation", "Suspended: %s; showing the last kept record.", observation.status())); continue;
            }
            boolean exists = !observation.claimId().isEmpty();
            var mapping = next.mappings.get(observation.ownerId());
            if (mapping != null) {
                var mappedKingdom = net.minecraft.resources.ResourceLocation.tryParse(mapping.kingdom());
                if (mappedKingdom == null || kingdoms.getKingdom(mappedKingdom).filter(KingdomView::defined).isEmpty()) {
                    availability.put(id, Keyed.text("warfare.mcakingdoms.control.suspended.controller","Suspended: the controlling kingdom is not defined by the loaded data packs.")); continue;
                }
            }
            if (exists) {
                var site = RecruitsObservation.at(server, new net.minecraft.world.level.ChunkPos(binding.chunkX(), binding.chunkZ()));
                if (site.status() != RecruitsObservation.Status.AVAILABLE || !site.claimId().equals(binding.claim().toString())) {
                    availability.put(id, Keyed.text("warfare.mcakingdoms.control.suspended.site","Suspended: the registered site no longer belongs to this Recruits claim.")); continue;
                }
            }
            var saved = durable.claims().get(binding.claim());
            if (!durable.available() || exists && (saved == null || !saved.owner().equals(observation.ownerId())
                    || saved.siege() != observation.underSiege()) || !exists && saved != null) {
                availability.put(id, Keyed.text("warfare.mcakingdoms.control.pending_save","Waiting for Recruits to confirm its save; showing the last kept record.")); continue;
            }
            if (exists && !saved.chunks().contains(net.minecraft.world.level.ChunkPos.asLong(binding.chunkX(), binding.chunkZ()))) {
                availability.put(id, Keyed.text("warfare.mcakingdoms.control.suspended.site","Suspended: the registered site no longer belongs to this Recruits claim.")); continue;
            }
            var updated = binding.observe(exists ? observation.ownerId() : binding.owner(), observation.underSiege(), exists,
                    next.mappings, server.overworld().getGameTime());
            next.bindings.put(id, updated); changed |= updated != binding;
            availability.put(id, Keyed.text("warfare.mcakingdoms.control.confirmed", "Recruits' saved record confirmed on day %s.", server.overworld().getGameTime() / 24000 + 1));
        }
        if (changed && !data.commit(server, increment(next)))
            batch.forEach(id -> availability.put(id, Keyed.text("warfare.mcakingdoms.control.observation_failed","Suspended: the observation could not be saved; showing the previous history.")));
    }
    private static ControlState increment(ControlState state) { state.revision = Math.addExact(state.revision, 1); return state; }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && WarfareConfig.enabled()
                && event.getServer().getTickCount() % WarfareConfig.INTERVAL.get() == 0) {
            get(event.getServer()).reconcile();
            CampaignService.get(event.getServer()).tick();
        }
    }
    @SubscribeEvent public static void preflight(SettlementMutationPreflightEvent event) {
        // Even when disabled, do not silently orphan a registered identity or change its civic meaning.
        var runtime = get(event.server());
        var state = runtime.data.snapshot();
        if (!runtime.data.writable() || state.bindings.containsKey(event.source().id())
                || event.mergeTarget().filter(s -> state.bindings.containsKey(s.id())).isPresent())
            event.reject(Keyed.text("warfare.mcakingdoms.control.migration_required","This settlement has registered military control; retire that binding before merging or reassigning it."));
    }
}
