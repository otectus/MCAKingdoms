package dev.otectus.mcakingdoms.development;

import dev.otectus.mcakingdoms.development.DevelopmentSavedData.*;
import dev.otectus.mcakingdoms.interaction.ActionRegistry;
import dev.otectus.mcakingdoms.interaction.Keyed;
import dev.otectus.mcakingdoms.interaction.Notify;
import dev.otectus.mcakingdoms.persistence.FaultPoints;
import dev.otectus.mcakingdoms.persistence.PlayerEvidence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Deliveries to public works and refund claims as journaled inventory operations (AUD-02).
 *
 * <p>A delivery: (1) one write records the operation as {@code PENDING} and counts its items toward the work
 * provisionally; (2) in the same tick the items leave the inventory and a marker with the operation id joins the
 * player's saved data ({@link PlayerEvidence}); (3) the player's next regular save confirms it. Until then the work does
 * nothing it could not undo: it may show as funded, but it is not completed, built, cancelled or rolled back. A claim
 * runs the same way: exactly what fits into the inventory (worked out on a copy first) leaves the refund register and
 * enters the inventory; what does not fit stays owed and is never dropped on the ground.
 *
 * <p>After a crash the player's loaded data decides: a marker means the inventory change was saved, so the operation is
 * confirmed; no marker means it was not, so the operation is rolled back (the delivery is taken off the work, the claimed
 * refunds are owed again) and the player's saved inventory is exactly as before. A saved file that cannot be read leaves
 * the operation unresolved for an operator; it is never guessed. One unsettled operation per player at a time.
 */
final class InventoryOps {
    private static final String P = DevelopmentService.P;
    static final String OWNER = "development";
    /** The nil id: a refund whose work is unknown. */
    private static final UUID NO_WORK = new UUID(0, 0);

    private final DevelopmentService service;

    InventoryOps(DevelopmentService service) {
        this.service = service;
    }

    private DevelopmentSavedData data() { return service.data(); }

    private static Optional<Item> item(String id) {
        var key = ResourceLocation.tryParse(id);
        return key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
    }

    private void oneAtATime(UUID player) {
        Keyed.require(data().records().operations().stream().noneMatch(o -> o.player().equals(player) && !o.stage().resolved()), P + "refusal.operation_waiting",
                "Your earlier delivery or refund is still being confirmed; it settles at the server's next save. Check it with Claim development refunds.");
    }

    /** Hands over what the actor carries toward an approved work; see the class comment for the order of the steps. */
    DevelopmentService.Reply contribute(ServerPlayer actor, UUID request, UUID projectId) {
        Keyed.require(!DevelopmentMode.economyPaused(), P + "refusal.economy_paused", "Deliveries are paused on this server.");
        var answered = service.admit(actor, request, "contribute|" + projectId, DevelopmentMode.PROJECTS);
        if (answered.isPresent()) return answered.get();
        var records = data().records();
        var p = records.project(projectId).orElseThrow(() -> Keyed.refuse(P + "refusal.no_project", "That work is not in the records."));
        Keyed.require(p.state() == State.APPROVED, P + "refusal.not_open_for_delivery", "That work is not taking deliveries now.");
        var here = service.api().getSettlementAt(actor.serverLevel(), actor.blockPosition());
        Keyed.require(here.map(s -> s.id().equals(service.api().resolveSettlementId(p.settlement()))).orElse(false), P + "refusal.deliver_here",
                "Deliver in person, inside the work's settlement.");
        oneAtATime(actor.getUUID());
        Keyed.require(PlayerEvidence.room(actor), P + "refusal.too_many_pending", "You have too many deliveries waiting to be confirmed.");
        var inventory = actor.getInventory();
        var offers = new ArrayList<Development.Offer>();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty() || stack.hasTag()) continue;
            var tags = new HashSet<String>();
            stack.getTags().forEach(tag -> tags.add(tag.location().toString()));
            offers.add(new Development.Offer(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), tags, stack.getCount()));
        }
        var takes = Development.plan(p.demand(), offers);
        Keyed.require(!takes.isEmpty(), P + "refusal.nothing_matches", "You carry nothing this work still needs (named, enchanted or marked items are never taken).");
        UUID op = UUID.nameUUIDFromBytes(("contribution|" + request).getBytes(StandardCharsets.UTF_8));
        long now = service.now();
        var counts = new TreeMap<String, Integer>();
        for (var take : takes) counts.merge(take.item() + "|" + take.requirement(), take.count(), Integer::sum);
        var items = new ArrayList<ItemCount>();
        counts.forEach((key, count) -> items.add(new ItemCount(key.substring(0, key.lastIndexOf('|')), count, Integer.parseInt(key.substring(key.lastIndexOf('|') + 1)))));
        var given = new TreeMap<String, Integer>();
        items.forEach(i -> given.merge(i.item(), i.count(), Integer::sum));
        var contributions = new ArrayList<>(p.contributions());
        given.forEach((id, count) -> contributions.add(new Contribution(actor.getUUID(), actor.getGameProfile().getName(), id, count, now, op)));
        Keyed.require(contributions.size() <= DevelopmentSavedData.CONTRIBUTIONS, P + "refusal.contributions_full", "That work has recorded its most deliveries.");
        var next = data().transaction();
        var updated = p.delivered(Development.apply(p.demand(), takes), contributions, now, next.revision);
        next.projects.put(projectId, updated);
        next.operations.put(op, new InventoryOp(op, OpKind.CONTRIBUTION, actor.getUUID(), projectId, items, OpStage.PENDING, now, now, ""));
        service.receipt(next, request, actor, "contribute|" + projectId, DevelopmentService.OPERATION + op);
        Keyed.require(data().commit(service.server(), next), P + "refusal.save_failed", "The development records could not be saved, so nothing changed.");
        FaultPoints.crash("development.contribution.journaled");
        // The same tick: the items leave the inventory and the marker joins the player's data; both reach disk in one save.
        for (var take : takes) inventory.items.get(take.slot()).split(take.count());
        var marker = new CompoundTag();
        marker.putString("Owner", OWNER);
        CompoundTag taken = new CompoundTag(); given.forEach(taken::putInt); marker.put("Taken", taken);
        PlayerEvidence.mark(actor, op, marker);
        actor.inventoryMenu.broadcastChanges();
        if (updated.state() == State.FUNDED) {
            String kingdom = service.authority(p);
            String where = service.settlement(p.settlement()).map(s -> s.displayName()).orElse("");
            try {
                Notify.government(service.server(), kingdom, actor.getUUID(), ActionRegistry.tr(P + "notice.funded", "%s in %s is fully funded.",
                        DevelopmentService.templateTitle(p.template()), where), "development.read");
            } catch (RuntimeException failure) { /* a notice never undoes a delivery */ }
        }
        return status(op);
    }

    /** What fits into a copy of the inventory, item by item in order; nothing in the real inventory changes. */
    private static Map<String, Integer> fit(ServerPlayer actor, Map<String, Integer> wanted) {
        Inventory copy = new Inventory(actor);
        var real = actor.getInventory();
        for (int i = 0; i < real.items.size(); i++) copy.items.set(i, real.items.get(i).copy());
        for (int i = 0; i < real.offhand.size(); i++) copy.offhand.set(i, real.offhand.get(i).copy());
        copy.selected = real.selected;
        var out = new TreeMap<String, Integer>();
        for (var entry : wanted.entrySet()) {
            var type = item(entry.getKey()).orElse(null);
            if (type == null) continue;
            int left = entry.getValue();
            while (left > 0) {
                int size = Math.min(left, type.getMaxStackSize());
                ItemStack stack = new ItemStack(type, size);
                copy.add(stack);
                int inserted = size - stack.getCount();
                if (inserted > 0) out.merge(entry.getKey(), inserted, Integer::sum);
                left -= inserted;
                if (!stack.isEmpty()) break;
            }
        }
        return out;
    }

    /** Returns what the actor is owed, exactly as far as their inventory has room; the rest stays owed (AUD-02). */
    DevelopmentService.Reply claim(ServerPlayer actor, UUID request) {
        var answered = service.admit(actor, request, "refunds", null);
        if (answered.isPresent()) return answered.get();
        var records = data().records();
        oneAtATime(actor.getUUID());
        var owed = records.refunds().stream().filter(r -> r.player().equals(actor.getUUID())).toList();
        Keyed.require(!owed.isEmpty(), P + "refusal.no_refunds", "You are owed no refunds.");
        var unknown = owed.stream().filter(r -> item(r.item()).isEmpty()).map(Refund::item).distinct().sorted().toList();
        var known = owed.stream().filter(r -> item(r.item()).isPresent()).toList();
        Keyed.require(!known.isEmpty(), P + "refusal.refunds_unknown_items",
                "Your refunds are for items this server does not have (%s). They stay owed until the mod that adds them is installed again.", String.join(", ", unknown));
        Keyed.require(PlayerEvidence.room(actor), P + "refusal.too_many_pending", "You have too many deliveries waiting to be confirmed.");
        var wanted = new LinkedHashMap<String, Integer>();
        known.forEach(r -> wanted.merge(r.item(), r.count(), Integer::sum));
        var fits = fit(actor, wanted);
        Keyed.require(!fits.isEmpty(), P + "refusal.inventory_full", "Your inventory is full. Make room, then claim again; nothing was taken from what you are owed.");
        // Refunds are claimed whole while they fit; a refund that fits only in part is split, and its rest stays owed.
        var room = new HashMap<>(fits);
        var claimed = new ArrayList<ItemCount>();
        var rest = new ArrayList<Refund>();
        for (var r : known) {
            int take = Math.min(r.count(), room.getOrDefault(r.item(), 0));
            if (take > 0) { claimed.add(new ItemCount(r.item(), take, -1, r.project())); room.merge(r.item(), -take, Integer::sum); }
            if (take < r.count()) rest.add(new Refund(r.player(), r.item(), r.count() - take, r.project()));
        }
        Keyed.require(claimed.size() <= 64, P + "refusal.too_many_refunds", "You are owed too many separate refunds to claim at once; claim again after this one.");
        UUID op = UUID.nameUUIDFromBytes(("claim|" + request).getBytes(StandardCharsets.UTF_8));
        long now = service.now();
        var next = data().transaction();
        next.refunds.removeAll(known);
        next.refunds.addAll(rest);
        int kept = rest.stream().mapToInt(Refund::count).sum();
        String detail = kept > 0 ? "kept:" + kept : unknown.isEmpty() ? "" : "unknown:" + String.join(",", unknown);
        next.operations.put(op, new InventoryOp(op, OpKind.REFUND_CLAIM, actor.getUUID(), null, claimed, OpStage.PENDING, now, now, detail));
        service.receipt(next, request, actor, "refunds", DevelopmentService.OPERATION + op);
        Keyed.require(data().commit(service.server(), next), P + "refusal.save_failed", "The development records could not be saved, so nothing changed.");
        FaultPoints.crash("development.claim.journaled");
        // The same tick: what was claimed enters the inventory, and the marker records what really went in.
        var delivered = new TreeMap<String, Integer>();
        var totals = new TreeMap<String, Integer>();
        claimed.forEach(i -> totals.merge(i.item(), i.count(), Integer::sum));
        for (var entry : totals.entrySet()) {
            var type = item(entry.getKey()).orElseThrow();
            int left = entry.getValue();
            while (left > 0) {
                int size = Math.min(left, type.getMaxStackSize());
                ItemStack stack = new ItemStack(type, size);
                actor.getInventory().add(stack);
                int inserted = size - stack.getCount();
                if (inserted > 0) delivered.merge(entry.getKey(), inserted, Integer::sum);
                left -= inserted;
                if (!stack.isEmpty()) break; // never dropped: what did not go in is owed again when the claim is confirmed
            }
        }
        var marker = new CompoundTag();
        marker.putString("Owner", OWNER);
        CompoundTag got = new CompoundTag(); delivered.forEach(got::putInt); marker.put("Delivered", got);
        PlayerEvidence.mark(actor, op, marker);
        actor.inventoryMenu.broadcastChanges();
        return status(op);
    }

    /** The current reading of an operation for its player: confirmed, waiting for a save, rolled back or unresolved. */
    DevelopmentService.Reply status(UUID id) {
        var op = data().records().operation(id).orElse(null);
        if (op == null) return new DevelopmentService.Reply(Keyed.text(P + "operation.gone", "That delivery or refund is settled and no longer listed."), false, id.toString());
        String title = op.project() == null ? "" : data().records().project(op.project()).map(p -> DevelopmentService.templateTitle(p.template()).getString()).orElse("");
        boolean funded = op.project() != null && data().records().project(op.project()).map(p -> p.funded()).orElse(false);
        return switch (op.stage()) {
            case CONFIRMED -> new DevelopmentService.Reply(op.kind() == OpKind.CONTRIBUTION
                    ? Keyed.text(P + "result.delivered", "You delivered %s item(s) to %s; the delivery is confirmed.", op.total(), title)
                    : claimed(op), false, id.toString());
            case PENDING -> new DevelopmentService.Reply(op.kind() == OpKind.CONTRIBUTION
                    ? funded ? Keyed.text(P + "result.delivery_pending_funded", "You delivered %s item(s); %s is now fully funded. The delivery is confirmed at the server's next save; until then the work does not start.", op.total(), title)
                    : Keyed.text(P + "result.delivery_pending", "You delivered %s item(s) to %s; they count toward it now and are confirmed at the server's next save. Nothing is taken twice.", op.total(), title)
                    : claimedPending(op), true, id.toString());
            case ROLLED_BACK -> new DevelopmentService.Reply(op.kind() == OpKind.CONTRIBUTION
                    ? Keyed.text(P + "result.delivery_rolled_back", "The server stopped before your delivery was saved; your items stayed with you and nothing was delivered.")
                    : Keyed.text(P + "result.claim_rolled_back", "The server stopped before your refund was saved; it is still owed to you. Claim it again."), false, id.toString());
            case UNRESOLVED -> new DevelopmentService.Reply(Keyed.text(P + "result.operation_unresolved",
                    "This delivery or refund could not be settled automatically (%s). An operator settles it with /kingdoms development operations; nothing is guessed.", op.detail()), true, id.toString());
        };
    }

    private static String claimedPending(InventoryOp op) {
        if (op.detail().startsWith("kept:")) return Keyed.text(P + "result.claim_pending_partly",
                "%s refunded item(s) are in your inventory; %s more did not fit and stay owed. The claim is confirmed at the server's next save.", op.total(), op.detail().substring(5));
        return Keyed.text(P + "result.claim_pending", "%s refunded item(s) are in your inventory; the claim is confirmed at the server's next save.", op.total());
    }

    private static String claimed(InventoryOp op) {
        if (op.detail().startsWith("kept:")) return Keyed.text(P + "result.refunded_partly", "%s refunded item(s) returned to you; %s did not fit and stay owed. Make room and claim again.",
                op.total(), op.detail().substring(5));
        if (op.detail().startsWith("unknown:")) return Keyed.text(P + "result.refunded_unknown",
                "%s refunded item(s) returned to you. Refunds for items this server does not have (%s) stay owed.", op.total(), op.detail().substring(8));
        return Keyed.text(P + "result.refunded", "%s refunded item(s) returned to you.", op.total());
    }

    // --- confirmation and recovery -------------------------------------------------------------------------------

    /** The player's saved data durably holds their markers: each of their development operations is settled from it. */
    static void playerSaved(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        var markers = PlayerEvidence.markers(player);
        if (markers.values().stream().noneMatch(m -> OWNER.equals(m.getString("Owner")))) return;
        var service = DevelopmentService.get(server);
        if (!service.data().writable()) return;
        new InventoryOps(service).settle(player.getUUID(), markers, player, true);
    }

    /** At login the player's data was just loaded from the copy that counts: every pending operation of theirs is settled from it. */
    static void playerLoggedIn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        var service = DevelopmentService.get(server);
        if (!service.data().writable()) return;
        boolean pending = service.data().records().operations().stream().anyMatch(o -> o.player().equals(player.getUUID()) && o.stage() == OpStage.PENDING);
        if (!pending && !PlayerEvidence.has(player)) return;
        new InventoryOps(service).settle(player.getUUID(), PlayerEvidence.markers(player), player, false);
    }

    /**
     * Settles {@code player}'s pending operations from the markers in their saved data: present means confirmed. Absent
     * means rolled back, but only when the markers come from a load ({@code fromSave} false): a save holds every marker
     * the player carries, so an operation without one in the save is simply newer than it. Markers of settled operations
     * are cleared from an online player. Returns how many operations changed.
     */
    int settle(UUID player, Map<UUID, CompoundTag> markers, ServerPlayer online, boolean fromSave) {
        var pending = data().records().operations().stream().filter(o -> o.player().equals(player) && o.stage() == OpStage.PENDING).toList();
        int changed = 0;
        if (!pending.isEmpty()) {
            var next = data().transaction();
            long now = service.now();
            for (var op : pending) {
                var marker = markers.get(op.id());
                if (marker != null) {
                    if (op.kind() == OpKind.REFUND_CLAIM) confirmClaim(next, op, marker, now);
                    else next.operations.put(op.id(), op.at(OpStage.CONFIRMED, op.items(), now, op.detail()));
                    changed++;
                } else if (!fromSave) {
                    rollBack(next, op, now);
                    changed++;
                }
            }
            if (changed > 0 && (next.refunds.size() > DevelopmentSavedData.REFUNDS || !data().commit(service.server(), next))) return 0;
        }
        if (online != null) for (var id : markers.keySet())
            if (OWNER.equals(markers.get(id).getString("Owner")) && data().records().operation(id).map(o -> o.stage().resolved()).orElse(true)) PlayerEvidence.clear(online, id);
        return changed;
    }

    /** The inventory change never reached the saved data: its provisional effect on the records is undone. */
    private static void rollBack(Records next, InventoryOp op, long now) {
        if (op.kind() == OpKind.REFUND_CLAIM) {
            for (var i : op.items()) next.refunds.add(new Refund(op.player(), i.item(), i.count(), i.source() == null ? NO_WORK : i.source()));
        } else if (op.project() != null && next.projects.containsKey(op.project())) {
            var p = next.projects.get(op.project());
            var demand = new ArrayList<>(p.demand());
            for (var i : op.items()) {
                if (i.requirement() < 0 || i.requirement() >= demand.size()) continue;
                var r = demand.get(i.requirement());
                demand.set(i.requirement(), new Requirement(r.ingredient(), r.count(), Math.max(0, r.delivered() - i.count())));
            }
            var contributions = p.contributions().stream().filter(c -> !op.id().equals(c.operation())).toList();
            // A provisionally funded work waits for deliveries again; nothing irreversible happened while it was unconfirmed.
            State state = p.state() == State.FUNDED ? State.APPROVED : p.state();
            State pausedFrom = p.pausedFrom() == State.FUNDED ? State.APPROVED : p.pausedFrom();
            next.projects.put(p.id(), new Project(p.id(), p.settlement(), p.kingdom(), p.template(), state, state == State.APPROVED ? P + "reason.delivery_rolled_back" : p.reasonKey(),
                    p.proposer(), p.approver(), p.approvedByOperator(), p.dimension(), p.origin(), demand, contributions, p.journal(), pausedFrom, p.createdAt(), now,
                    next.revision, p.kind(), p.blockers(), p.completion()));
        }
        next.operations.put(op.id(), op.at(OpStage.ROLLED_BACK, op.items(), now, op.detail()));
    }

    private static void confirmClaim(Records next, InventoryOp op, CompoundTag marker, long now) {
        // What reached the inventory, per item, is shared out over the claimed refunds in order; anything short is owed again.
        var left = new HashMap<String, Integer>();
        CompoundTag got = marker.getCompound("Delivered");
        for (String key : got.getAllKeys()) left.put(key, Math.max(0, got.getInt(key)));
        var delivered = new ArrayList<ItemCount>();
        int owedAgain = 0;
        for (var i : op.items()) {
            int given = Math.min(i.count(), left.getOrDefault(i.item(), 0));
            left.merge(i.item(), -given, Integer::sum);
            if (given > 0) delivered.add(new ItemCount(i.item(), given, -1, i.source()));
            if (given < i.count()) {
                next.refunds.add(new Refund(op.player(), i.item(), i.count() - given, i.source() == null ? NO_WORK : i.source()));
                owedAgain += i.count() - given;
            }
        }
        String detail = owedAgain > 0 ? "kept:" + owedAgain : op.detail();
        next.operations.put(op.id(), op.at(OpStage.CONFIRMED, delivered.isEmpty() ? op.items() : delivered, now, detail));
    }

    /** What the operator reconciliation did: settled from files, unresolved, and still waiting for an online player's save. */
    record Report(int settled, int unresolved, int stillPending) { }

    /**
     * {@code /kingdoms development operations reconcile}: settles every pending operation of an offline player from their
     * saved file, read without changing it; a file that cannot be read marks the operation unresolved, with the reason,
     * for a stopped-server repair. An online player's operations wait for their next save, which confirms them.
     */
    Report reconcile() {
        int settled = 0, unresolved = 0, still = 0;
        var byPlayer = new LinkedHashMap<UUID, List<InventoryOp>>();
        for (var op : data().records().operations()) if (op.stage() == OpStage.PENDING) byPlayer.computeIfAbsent(op.player(), x -> new ArrayList<>()).add(op);
        for (var entry : byPlayer.entrySet()) {
            if (service.server().getPlayerList().getPlayer(entry.getKey()) != null) { still += entry.getValue().size(); continue; }
            var file = PlayerEvidence.offline(service.server(), entry.getKey());
            if (file.isPresent()) { settled += settle(entry.getKey(), file.get(), null, false); continue; }
            var next = data().transaction();
            for (var op : entry.getValue()) next.operations.put(op.id(), op.at(OpStage.UNRESOLVED, op.items(), service.now(), "player file unreadable"));
            if (data().commit(service.server(), next)) unresolved += entry.getValue().size();
        }
        return new Report(settled, unresolved, still);
    }
}
