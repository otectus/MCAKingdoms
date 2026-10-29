package dev.otectus.mcakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.*;
import dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi;
import dev.otectus.mcakingdoms.api.politics.Politics;
import dev.otectus.mcakingdoms.compat.livingvillages.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static dev.otectus.mcakingdoms.acceptance.IntegrationScenario.check;

/**
 * Recruits 1.15.2 + Recruits: Living Villages 1.1.0 on a packaged server: villages founded by Living Villages' own founding
 * routine become one settlement each; same-culture factions stay separate realms; an existing Kingdoms settlement keeps its
 * identity; an AI siege runs through the provider's abstract combat to an occupation recorded once; a capital's fall keeps
 * its realm under a new capital; an agreement accepted by an AI realm ends the war and holds against the provider's relation
 * step, its preflight and its AI target selection; residents survive dematerialization, model trimming and cleanup; and every
 * record survives a restart, the provider's absence and a future schema.
 */
final class LivingVillagesScenario {
    private static final String ROOT = "com.diego.recruitsvillages.";
    private static final String STATE_FILE = "lv-scenario.properties";

    private LivingVillagesScenario() {
    }

    static void run(MinecraftServer server, String phase, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) throws Exception {
        switch (phase) {
            case "lv" -> schedule.accept(5L, () -> wrap(finish, () -> main(server, schedule, finish)));
            case "lv-restart" -> schedule.accept(60L, () -> wrap(finish, () -> restart(server, finish)));
            case "lv-absent" -> schedule.accept(20L, () -> wrap(finish, () -> absent(server, finish)));
            case "lv-reinstalled" -> schedule.accept(80L, () -> wrap(finish, () -> reinstalled(server, finish)));
            case "lv-future" -> schedule.accept(20L, () -> wrap(finish, () -> future(server, finish)));
            case "lv-mca" -> schedule.accept(5L, () -> wrap(finish, () -> mca(server, schedule, finish)));
            default -> finish.accept("FAIL integration unknown Living Villages phase " + phase);
        }
    }

    @FunctionalInterface
    interface Step { void run() throws Exception; }

    private static void wrap(Consumer<String> finish, Step step) {
        try { step.run(); } catch (Throwable failure) { failure.printStackTrace(); finish.accept("FAIL integration living villages: " + failure); }
    }

    // ---- provider helpers --------------------------------------------------------------------------------------------

    static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(ROOT + name); }

    static Method method(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        Method m = owner.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    static Object found(ServerLevel level, String name, BlockPos site) throws Exception {
        return method(type("faction.VillageFounder"), "found", ServerLevel.class, String.class, BlockPos.class).invoke(null, level, name, site);
    }

    static LvAccess access() { return LvAccess.get().orElseThrow(); }

    /**
     * A dedicated test server has no players, so Living Villages' own proximity loop folds a village back into its model
     * within a second of materializing it. Holding the loop's counter stands in for a player standing in the village.
     */
    static void holdProximity(boolean hold) throws Exception {
        var counter = type("materialize.MaterializeEvents").getDeclaredField("counter");
        counter.setAccessible(true);
        counter.setInt(null, hold ? Integer.MIN_VALUE / 2 : 0);
    }

    static BlockPos ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    static Object unit(int level) throws Exception {
        return method(type("data.RecruitUnit"), "create", String.class, int.class, int.class, int.class).invoke(null, "recruits:recruit", level, 2, 2);
    }

    static void roster(Object village, int count, int level) throws Exception {
        var roster = access().roster(village);
        roster.clear();
        for (int i = 0; i < count; i++) roster.add(unit(level));
        method(type("data.FactionData"), "setLeaderRef", UUID.class).invoke(village, access().unitId(roster.get(0)));
    }

    static void subordinate(MinecraftServer server, Object village, Object capital) throws Exception {
        method(type("data.FactionData"), "setCapitalId", UUID.class).invoke(village, access().id(capital));
        method(type("data.FactionData"), "setStringID", String.class).invoke(village, access().team(capital));
        method(type("faction.RecruitsFactionBridge"), "ensureFaction", ServerLevel.class, type("data.FactionData")).invoke(null, server.overworld(), village);
        access().dirty(server);
    }

    static void relation(MinecraftServer server, Object a, Object b, String status) throws Exception {
        Object manager = Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsDiplomacyManager").get(null);
        @SuppressWarnings({"unchecked", "rawtypes"}) Object value = Enum.valueOf((Class) Class.forName("com.talhanation.recruits.world.RecruitsDiplomacyManager$DiplomacyStatus"), status);
        var set = manager.getClass().getMethod("setRelation", String.class, String.class, value.getClass(), ServerLevel.class);
        set.invoke(manager, access().team(a), access().team(b), value, server.overworld());
        set.invoke(manager, access().team(b), access().team(a), value, server.overworld());
    }

    static int relation(Object a, Object b) throws Exception {
        return (int) method(type("sim.Relations"), "status", type("data.FactionData"), type("data.FactionData")).invoke(null, a, b);
    }

    static void abstractSiege(MinecraftServer server, Object attacker, Object defender) throws Exception {
        method(type("war.WarManager"), "startAbstractSiege", ServerLevel.class, type("data.FactionData"), type("data.FactionData"))
                .invoke(null, server.overworld(), attacker, defender);
    }

    static void tickSieges(MinecraftServer server) throws Exception {
        method(type("war.WarManager"), "tickAbstractSieges", ServerLevel.class).invoke(null, server.overworld());
    }

    static boolean declare(MinecraftServer server, Object attacker, Object defender) throws Exception {
        return (boolean) method(type("war.WarManager"), "declareWar", ServerLevel.class, type("data.FactionData"), type("data.FactionData"),
                net.minecraft.server.level.ServerPlayer.class).invoke(null, server.overworld(), attacker, defender, null);
    }

    static LvState state(MinecraftServer server) { return LvBridgeSavedData.get(server).snapshot(); }

    static LvState.Binding binding(MinecraftServer server, Object village) {
        return Objects.requireNonNull(state(server).bindings.get(access().id(village)), "binding for " + access().name(village));
    }

    static Properties saved() throws Exception {
        var p = new Properties();
        try (var in = Files.newBufferedReader(java.nio.file.Path.of(STATE_FILE))) { p.load(in); }
        return p;
    }

    // ---- phase: lv ------------------------------------------------------------------------------------------------------

    static void main(MinecraftServer server, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) throws Exception {
        var snapshot = LvCompat.snapshot(server);
        System.out.println("Living Villages capabilities: " + snapshot);
        check(snapshot.gate().equals("applied"), "LvGate applied the hooks to the audited 1.1.0 jar: " + snapshot.gate());
        check(snapshot.overall() == LvCompat.Readiness.READY, "every capability READY on the packaged server: " + snapshot.capabilities());
        check(LvRuntime.get(server).isPresent(), "the bridge runtime is active");
        var level = server.overworld();
        var kingdoms = McaKingdomsApi.get(server);
        // An existing, locked Kingdoms settlement where a Living Villages village is later founded (T06).
        var oldmereAnchor = ground(level, 700, 0);
        var oldmere = kingdoms.registerCandidate(level, SettlementCandidate.manual(level.dimension(), oldmereAnchor, 24,
                new ResourceLocation("mcakingdoms_acceptance", "lv"), "oldmere", "Oldmere"));
        kingdoms.setLocks(oldmere.id(), true, false);
        Object a1 = found(level, "Ashford", ground(level, 24, 24));
        Object a2 = found(level, "Ashford Mill", ground(level, 180, 24));
        Object b1 = found(level, "Brennan", ground(level, 360, 300));
        Object b2 = found(level, "Brennan Ford", ground(level, 520, 300));
        Object d1 = found(level, "Dunmere", ground(level, 712, 8));
        subordinate(server, a2, a1);
        subordinate(server, b2, b1);
        // A player faction mirrored before its Ledger is placed: no physical village (T03).
        Object virtual = access().newVillage(UUID.randomUUID(), "Player Hold");
        method(type("data.FactionData"), "setOwnerUuid", UUID.class).invoke(virtual, UUID.randomUUID());
        method(type("data.FactionData"), "setStringID", String.class).invoke(virtual, "player_hold");
        access().add(server, virtual);
        roster(a1, 24, 6);
        roster(a2, 10, 4);
        roster(b1, 2, 1);
        roster(b2, 3, 1);
        roster(d1, 30, 6);
        var props = new Properties();
        for (var entry : Map.of("a1", a1, "a2", a2, "b1", b1, "b2", b2, "d1", d1, "virtual", virtual).entrySet())
            props.setProperty(entry.getKey(), access().id(entry.getValue()).toString());
        props.setProperty("oldmere", oldmere.id().toString());
        schedule.accept(200L, () -> wrap(finish, () -> imported(server, props, schedule, finish, a1, a2, b1, b2, d1, virtual)));
    }

    static void imported(MinecraftServer server, Properties props, BiConsumer<Long, Runnable> schedule, Consumer<String> finish,
                         Object a1, Object a2, Object b1, Object b2, Object d1, Object virtual) throws Exception {
        var kingdoms = McaKingdomsApi.get(server);
        var s = state(server);
        check(s.migration.complete, "the existing-world import completed in bounded batches");
        var settlements = new HashSet<UUID>();
        for (Object v : List.of(a1, a2, b1, b2, d1)) {
            var b = binding(server, v);
            check(b.status() == LvState.BindingStatus.BOUND && b.settlement() != null, access().name(v) + " is bound to one settlement");
            check(settlements.add(b.settlement()), access().name(v) + " has its own settlement (no two villages share one)");
            check(kingdoms.getSettlementByExternalRef(LvBindings.EXTERNAL_REF, access().id(v).toString()).map(SettlementView::id).orElseThrow().equals(b.settlement()),
                    access().name(v) + " carries the recruitsvillages:village reference");
        }
        var hold = binding(server, virtual);
        check(hold.status() == LvState.BindingStatus.VIRTUAL && hold.settlement() == null, "T03: the Ledger-less player faction is a realm only, no settlement at the origin");
        check(kingdoms.getSettlementByExternalRef(LvBindings.EXTERNAL_REF, access().id(virtual).toString()).isEmpty(),
                "no settlement carries the Ledger-less faction's reference");
        check(((dev.otectus.mcakingdoms.core.KingdomsServiceImpl) kingdoms).orderedSettlements(Optional.empty()).stream()
                .noneMatch(v -> v.anchor().getX() == 0 && v.anchor().getZ() == 0), "no phantom settlement is anchored at the default coordinate");
        // T06: the village founded inside Oldmere is Oldmere, keeping its id, name and lock.
        UUID oldmere = UUID.fromString(props.getProperty("oldmere"));
        check(binding(server, d1).settlement().equals(oldmere), "T06: Dunmere bound to the existing settlement Oldmere");
        var kept = kingdoms.getSettlement(oldmere).orElseThrow();
        check(kept.displayName().equals("Oldmere") && kept.nameLocked(), "T06: Oldmere keeps its name and name lock; Living Villages' name stays an alias");
        check(binding(server, d1).providerName().equals("Dunmere"), "the provider name is kept on the binding");
        // T04: same culture, separate realms.
        var realmA = s.realmOfVillage(access().id(a1)).orElseThrow();
        var realmB = s.realmOfVillage(access().id(b1)).orElseThrow();
        check(!realmA.id().equals(realmB.id()), "T04: two independent factions are two realms");
        check(realmA.members().containsAll(List.of(access().id(a1), access().id(a2))) && realmA.members().size() == 2, "realm A holds its capital and subordinate");
        var cultureA = kingdoms.getSettlement(binding(server, a1).settlement()).orElseThrow().kingdomId();
        var cultureB = kingdoms.getSettlement(binding(server, b1).settlement()).orElseThrow().kingdomId();
        check(cultureA.equals(cultureB), "the flat plains give both realms one civic kingdom (" + cultureA + "), and they still are separate realms");
        props.setProperty("realmA", realmA.id().toString());
        props.setProperty("realmB", realmB.id().toString());
        // T01: a repeated import is a no-op.
        long bindingsBefore = s.bindings.size();
        var dispatcher = server.getCommands();
        dispatcher.performPrefixedCommand(server.createCommandSourceStack(), "kingdoms livingvillages migration apply");
        schedule.accept(120L, () -> wrap(finish, () -> {
            var again = state(server);
            check(again.migration.complete && again.bindings.size() == bindingsBefore, "T01: running the import again changes nothing");
            for (Object v : List.of(a1, a2, b1, b2, d1))
                check(again.bindings.get(access().id(v)).settlement().equals(binding(server, v).settlement()), "T01: " + access().name(v) + " keeps its settlement");
            residents(server, props, schedule, finish, a1, a2, b1, b2, d1);
        }));
    }

    static void war(MinecraftServer server, Properties props, BiConsumer<Long, Runnable> schedule, Consumer<String> finish,
                    Object a1, Object a2, Object b1, Object b2, Object d1) throws Exception {
        // T08/T13: an AI siege on an ordinary AI capital, resolved by the provider's abstract combat; no player anywhere.
        UUID b1Settlement = binding(server, b1).settlement();
        String b1Name = McaKingdomsApi.get(server).getSettlement(b1Settlement).orElseThrow().displayName();
        abstractSiege(server, a1, b1);
        var siege = access().sieges().stream().filter(x -> x.attacker().equals(access().id(a1)) && x.defender().equals(access().id(b1))).findFirst();
        check(siege.isPresent(), "T13: the AI capital Brennan can be besieged without any leader online");
        var conflict = LvState.Phase.ACTIVE_ABSTRACT;
        check(state(server).conflicts.values().stream().anyMatch(c -> c.open() && c.phase() == conflict && c.defenderVillage().equals(access().id(b1))),
                "the bridge recorded one open abstract conflict before any casualty");
        for (int i = 0; i < 20 && access().sieges().stream().anyMatch(x -> x.defender().equals(access().id(b1))); i++) tickSieges(server);
        var after = state(server);
        var done = after.conflicts.values().stream().filter(c -> c.defenderVillage().equals(access().id(b1))).toList();
        check(done.size() == 1 && done.get(0).phase() == LvState.Phase.COMPLETED && done.get(0).receipt() != null, "T08: one conflict, completed with a conquest receipt: " + done);
        var receipt = after.journal.get(done.get(0).receipt());
        check(receipt.kind() == LvState.OperationKind.CONQUEST && receipt.state() == LvState.OperationState.APPLIED, "the occupation is journaled as applied, waiting for a save");
        check(after.history.stream().filter(e -> e.kind() == LvState.EventKind.CONQUERED && b1Settlement.equals(e.settlement())).count() == 1,
                "the conquest is in history exactly once");
        UUID realmA = UUID.fromString(props.getProperty("realmA")), realmB = UUID.fromString(props.getProperty("realmB"));
        var occupied = after.bindings.get(access().id(b1));
        check(realmA.equals(occupied.realm()) && realmB.equals(occupied.recognizedRealm()) && occupied.occupied(),
                "T19: Brennan is held by realm A and still recognized as realm B's (occupied)");
        check(McaKingdomsApi.get(server).getSettlement(b1Settlement).orElseThrow().displayName().equals(b1Name), "occupation does not rename the town");
        schedule.accept(40L, () -> wrap(finish, () -> {
            // T20: the realm whose capital fell lives on under Brennan Ford, with its id.
            var s = state(server);
            var b = s.realms.get(realmB);
            check(b != null && b.status() == LvState.RealmStatus.ACTIVE && b.capital().equals(access().id(b2)),
                    "T20: realm B keeps its id with Brennan Ford as its new capital: " + b);
            check(s.history.stream().anyMatch(e -> e.kind() == LvState.EventKind.CAPITAL_CHANGED && realmB.equals(e.realm())), "the capital change is recorded");
            server.saveEverything(false, true, true);
            schedule.accept(5L, () -> wrap(finish, () -> {
                var ack = state(server).journal.get(done.get(0).receipt());
                check(ack.state() == LvState.OperationState.ACKNOWLEDGED || ack.state() == LvState.OperationState.PUBLISHED,
                        "the conquest is acknowledged only after the world save that wrote Living Villages' data: " + ack.state());
                peace(server, props, schedule, finish, a1, a2, b1, b2, d1);
            }));
        }));
    }

    @SuppressWarnings("unchecked")
    static void peace(MinecraftServer server, Properties props, BiConsumer<Long, Runnable> schedule, Consumer<String> finish,
                      Object a1, Object a2, Object b1, Object b2, Object d1) throws Exception {
        var level = server.overworld();
        UUID realmA = UUID.fromString(props.getProperty("realmA")), realmB = UUID.fromString(props.getProperty("realmB"));
        var rt = LvRuntime.get(server).orElseThrow();
        // A kingdom government speaks for realm A through an explicit operator association; realm B stays AI.
        var leader = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "LvEnvoy")) { @Override public boolean hasPermissions(int n) { return n <= 2; } };
        leader.setPos(24, 70, 24);
        server.getProfileCache().add(leader.getGameProfile());
        Map<UUID, net.minecraft.server.level.ServerPlayer> online = null;
        for (var field : PlayerList.class.getDeclaredFields()) if (field.getGenericType().getTypeName().contains("java.util.UUID") && field.getGenericType().getTypeName().contains("ServerPlayer")) {
            field.setAccessible(true); online = (Map<UUID, net.minecraft.server.level.ServerPlayer>) field.get(server.getPlayerList()); break;
        }
        check(online != null, "fixture finds the online-player registry");
        online.put(leader.getUUID(), leader);
        try {
            var seat = McaKingdomsApi.get(server).getSettlement(binding(server, a1).settlement()).orElseThrow();
            String kingdom = seat.kingdomId().toString();
            var politics = KingdomsPoliticsApi.get(server);
            var founded = politics.execute(leader, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.BOOTSTRAP, kingdom, kingdom + "_charter",
                    seat.id().toString(), new Politics.Person(leader.getUUID(), Politics.Kind.PLAYER), "", "", "", 0, 64, 0));
            check(founded.success(), "the envoy's kingdom government is founded: " + founded.message());
            check(politics.authorized(leader, kingdom, Politics.Permission.RATIFY), "the envoy holds ratification authority");
            LvDiplomacy.associate(rt, realmA, kingdom, "DIPLOMACY", leader.getUUID());
            // A war in progress: realm A besieges Brennan Ford, realm B's weak capital.
            abstractSiege(server, a1, b2);
            check(access().sieges().stream().anyMatch(x -> x.defender().equals(access().id(b2))), "a second siege runs against Brennan Ford");
            String answer = LvDiplomacy.propose(rt, leader, UUID.randomUUID(), "realm:" + realmA, "realm:" + realmB, LvState.Term.PEACE, 240000, "End the siege");
            System.out.println("Peace answer: " + dev.otectus.mcakingdoms.interaction.Keyed.english(answer));
            var treaty = state(server).treaties.values().stream().filter(t -> t.term() == LvState.Term.PEACE).findFirst().orElseThrow();
            check(treaty.status() == LvState.TreatyStatus.ACTIVE && treaty.signatures().values().stream().anyMatch(sig -> sig.kind() == LvState.ActorKind.PROVIDER_REALM),
                    "the outmatched AI realm accepted peace by its own policy and signed as a provider realm: " + treaty.decision());
            check(access().sieges().stream().noneMatch(x -> x.defender().equals(access().id(b2))), "T15: the agreed peace stopped the abstract siege through the provider");
            check(relation(a1, b2) == 0 && relation(b2, a1) == 0, "both native directions read neutral before peace is reported operational");
            check(state(server).conflicts.values().stream().anyMatch(c -> c.defenderVillage().equals(access().id(b2)) && c.phase() == LvState.Phase.WITHDRAWN),
                    "the conflict is withdrawn, not conquered");
            check(binding(server, b2).realm().equals(realmB), "Brennan Ford was not conquered by the peace");
            // T16: Living Villages' relation step cannot overturn it, and repeated attempts do not loop.
            Method apply = method(type("sim.Relations"), "apply", ServerLevel.class, type("data.FactionData"), type("data.FactionData"),
                    Class.forName("com.talhanation.recruits.world.RecruitsDiplomacyManager$DiplomacyStatus"), String.class);
            @SuppressWarnings({"rawtypes"}) Object enemy = Enum.valueOf((Class) Class.forName("com.talhanation.recruits.world.RecruitsDiplomacyManager$DiplomacyStatus"), "ENEMY");
            for (int i = 0; i < 5; i++) apply.invoke(null, level, a1, b2, enemy, "have declared each other enemies");
            check(relation(a1, b2) == 0, "T16: five provider enmity proposals were held back by the agreement");
            check(state(server).treaties.get(treaty.id()).suppressed() >= 5 && state(server).treaties.get(treaty.id()).status() == LvState.TreatyStatus.ACTIVE,
                    "each held-back proposal is counted and the agreement stays in force");
            // T15/T17: the preflight refuses a new war between them on every path.
            abstractSiege(server, a1, b2);
            check(access().sieges().stream().noneMatch(x -> x.defender().equals(access().id(b2))), "a new abstract siege under the agreement is refused");
            check(!declare(server, a1, b2), "a live declaration under the agreement is refused");
            // The AI's own selection passes over the protected neighbour and still finds an eligible one. Realm A just won a
            // war, so its native rest period is cleared for the test (the provider's own cooldown map and war stage).
            var cooldowns = type("war.WarManager").getDeclaredField("DECLARE_COOLDOWNS");
            cooldowns.setAccessible(true);
            ((Map<?, ?>) cooldowns.get(null)).clear();
            @SuppressWarnings({"rawtypes"}) Object expanding = Enum.valueOf((Class) type("data.FactionData$WarStage"), "EXPANDING");
            method(type("data.FactionData"), "setWarStage", type("data.FactionData$WarStage")).invoke(a1, expanding);
            relation(server, a1, d1, "ENEMY");
            relation(server, a1, b2, "ENEMY");
            int warsBefore = access().wars().size() + access().sieges().size();
            method(type("war.WarManager"), "considerAiWars", ServerLevel.class).invoke(null, level);
            // The agreement binds realms A and B; a third realm (Dunmere) may still choose Brennan Ford.
            var chosen = state(server);
            java.util.function.Predicate<UUID> partyA = v -> chosen.realmOfVillage(v).map(r -> r.id().equals(realmA)).orElse(false);
            boolean attackedB2 = access().sieges().stream().anyMatch(x -> x.defender().equals(access().id(b2)) && partyA.test(x.attacker()))
                    || access().wars().stream().anyMatch(w -> w.defender().equals(access().id(b2)) && partyA.test(w.attacker()));
            check(!attackedB2, "the AI never sent a village of realm A against the village the agreement protects");
            check(state(server).refusals.stream().anyMatch(r -> access().id(b2).equals(r.defender()) && r.reason().equals("treaty")),
                    "the refused target is recorded with its reason");
            System.out.println("AI selection after the refusal: wars " + warsBefore + " → " + (access().wars().size() + access().sieges().size()));
            relation(server, a1, b2, "NEUTRAL");
            // A cession the former sovereign does not have to accept: an AI realm at peace refuses it.
            String cede = LvDiplomacy.propose(rt, leader, UUID.randomUUID(), "realm:" + realmA, "realm:" + realmB, LvState.Term.CEDE, 240000, "Recognize Brennan");
            check(state(server).treaties.values().stream().anyMatch(t -> t.term() == LvState.Term.CEDE && t.status() == LvState.TreatyStatus.REJECTED),
                    "the AI refused the cession by its recorded policy: " + dev.otectus.mcakingdoms.interaction.Keyed.english(cede));
            check(binding(server, b1).occupied(), "Brennan stays occupied, recognized as realm B's");
        } finally {
            online.remove(leader.getUUID());
        }
        // An open conflict for the restart: the AI's own choice above, or else a direct siege of Oldmere.
        if (access().sieges().stream().noneMatch(x -> x.defender().equals(access().id(d1))) && access().wars().stream().noneMatch(w -> w.defender().equals(access().id(d1))))
            abstractSiege(server, a1, d1);
        check(access().sieges().stream().anyMatch(x -> x.defender().equals(access().id(d1))), "a siege of Oldmere is running at shutdown");
        props.setProperty("restoreDefender", access().id(d1).toString());
        try (var out = Files.newBufferedWriter(java.nio.file.Path.of(STATE_FILE))) { props.store(out, "Living Villages scenario"); }
        server.saveEverything(false, true, true);
        finish.accept("PASS integration Living Villages: import, realms, residents, abstract conquest, capital succession, AI peace, arbitration, preflight, AI selection");
    }

    static long tagged(ServerLevel level, Object village, UUID id) {
        return level.getEntitiesOfClass(Mob.class, access().box(village), m -> m.isAlive() && m.getPersistentData().hasUUID("RVVillagerFaction")
                && m.getPersistentData().getUUID("RVVillagerFaction").equals(id)).size();
    }

    static long villagers(ServerLevel level, Object village) {
        return level.getEntitiesOfClass(Villager.class, access().box(village), Villager::isAlive).size();
    }

    static void residents(MinecraftServer server, Properties props, BiConsumer<Long, Runnable> schedule, Consumer<String> finish,
                          Object a1, Object a2, Object b1, Object b2, Object d1) throws Exception {
        var level = server.overworld();
        Class<?> manager = type("materialize.MaterializationManager");
        // By now Living Villages' model has recruited Ashford far past its beds, and materializing trims a model to its beds
        // (civilians first), so no civilian would be placed. The fixture gives Ashford a model that fits: four soldiers with
        // the leader, civilians in the remaining beds. The soldiers held back return to the roster once residents are checked.
        List<Object> roster = access().roster(a1);
        Object leaderRef = method(type("data.FactionData"), "getLeaderRef").invoke(a1);
        var reserve = new ArrayList<Object>();
        for (var it = roster.iterator(); it.hasNext() && roster.size() > 4; ) {
            Object unit = it.next();
            if (!method(type("data.RecruitUnit"), "getId").invoke(unit).equals(leaderRef)) { reserve.add(unit); it.remove(); }
        }
        access().setCivilians(a1, Math.max(1, access().beds(a1) - roster.size()));
        System.out.println("fixture: Ashford before materializing: " + roster.size() + " soldiers (" + reserve.size() + " held back), "
                + access().civilians(a1) + " civilians, " + access().treasury(a1) + " emeralds, managed " + access().managed(access().id(a1))
                + ", the provider's record " + (access().village(server, access().id(a1)).orElse(null) == a1));
        holdProximity(true);
        method(manager, "materialize", ServerLevel.class, type("data.FactionData")).invoke(null, level, a1);
        // Beds count once their houses stand in the world, which materializing does first.
        check(access().joblessLive(a1) > 0, "fixture: Ashford's model has civilians to place (" + access().joblessLive(a1) + " of "
                + access().civilians(a1) + " civilians, " + roster.size() + " soldiers)");
        schedule.accept(100L, () -> wrap(finish, () -> {
            UUID id = access().id(a1);
            method(manager, "liveSync", type("data.FactionData")).invoke(null, a1);
            check(access().materialized(id), "Ashford materialized near spawn");
            long people = tagged(level, a1, id), bodies = villagers(level, a1);
            check(people > 0, "Living Villages placed Ashford's civilians in the world: " + people);
            var uuids = new HashSet<UUID>();
            level.getEntitiesOfClass(Mob.class, access().box(a1), m -> m.isAlive() && m.getPersistentData().hasUUID("RVVillagerFaction")).forEach(m -> uuids.add(m.getUUID()));
            // T23: players leave; the provider folds the village back into its model. The people stay.
            method(manager, "dematerialize", type("data.FactionData")).invoke(null, a1);
            check(!access().managed(id), "Ashford is no longer managed by the provider");
            check(tagged(level, a1, id) == people, "T23: every resident is still in the world after dematerialization (" + people + ")");
            method(manager, "materialize", ServerLevel.class, type("data.FactionData")).invoke(null, level, a1);
            schedule.accept(60L, () -> wrap(finish, () -> {
                method(manager, "liveSync", type("data.FactionData")).invoke(null, a1);
                var again = new HashSet<UUID>();
                level.getEntitiesOfClass(Mob.class, access().box(a1), m -> m.isAlive() && m.getPersistentData().hasUUID("RVVillagerFaction")
                        && m.getPersistentData().getUUID("RVVillagerFaction").equals(id)).forEach(m -> again.add(m.getUUID()));
                check(again.containsAll(uuids) || uuids.containsAll(again), "T23: returning players find the same people, not a second set: " + uuids.size() + " / " + again.size());
                check(villagers(level, a1) <= bodies + 1, "no duplicate bodies appeared on re-materialization");
                // T28: the model's count drops (as after recruitment); the provider's trimming keeps the people.
                access().setCivilians(a1, 0);
                long before = villagers(level, a1);
                method(manager, "liveSync", type("data.FactionData")).invoke(null, a1);
                var cleaned = method(manager, "cleanup", ServerLevel.class, type("data.FactionData")).invoke(null, level, a1);
                check(villagers(level, a1) == before, "T28: trimming and /rvcleanup removed no resident of the integrated village (" + before + "): " + cleaned);
                long kept = level.getEntitiesOfClass(Villager.class, access().box(a1), v -> v.getPersistentData().contains("mcakingdoms:lv_kept")).size();
                check(kept > 0, "people the provider stopped counting are kept outside its books (" + kept + ")");
                props.setProperty("ashfordPeople", Long.toString(before));
                // Players leave again: the village is folded back, its people stay, and its wars run out of view.
                method(manager, "dematerialize", type("data.FactionData")).invoke(null, a1);
                check(villagers(level, a1) == before, "the people stay through a second dematerialization");
                schedule.accept(30L, () -> wrap(finish, () -> {
                    var tracked = state(server).residents.values().stream().filter(r -> r.village().equals(id)).count();
                    check(tracked >= before, "the bridge tracks Ashford's residents (" + tracked + " records for " + before + " people)");
                    holdProximity(false);
                    roster.addAll(reserve);
                    war(server, props, schedule, finish, a1, a2, b1, b2, d1);
                }));
            }));
        }));
    }

    // ---- phase: lv-restart --------------------------------------------------------------------------------------------

    static void restart(MinecraftServer server, Consumer<String> finish) throws Exception {
        var props = saved();
        check(LvRuntime.get(server).isPresent(), "the bridge is active after a restart");
        var s = state(server);
        for (String key : List.of("a1", "a2", "b1", "b2", "d1")) {
            var b = s.bindings.get(UUID.fromString(props.getProperty(key)));
            check(b != null && b.status() == LvState.BindingStatus.BOUND, key + " is still bound after restart");
        }
        check(s.bindings.values().stream().filter(b -> b.status() == LvState.BindingStatus.BOUND).map(LvState.Binding::settlement).distinct().count()
                == s.bindings.values().stream().filter(b -> b.status() == LvState.BindingStatus.BOUND).count(), "no settlement gained a second village across restart");
        check(s.realms.containsKey(UUID.fromString(props.getProperty("realmA"))) && s.realms.containsKey(UUID.fromString(props.getProperty("realmB"))), "realm ids survive restart");
        UUID defender = UUID.fromString(props.getProperty("restoreDefender"));
        check(access().sieges().stream().anyMatch(x -> x.defender().equals(defender)), "T40: the siege Living Villages forgot is restored from the bridge record: sieges "
                + access().sieges() + ", conflicts " + s.conflicts.values().stream().filter(c -> c.defenderVillage().equals(defender))
                .map(c -> c.phase() + " " + c.transitions().stream().map(LvState.Transition::detail).toList()).toList());
        check(s.conflicts.values().stream().anyMatch(c -> c.defenderVillage().equals(defender) && c.phase() == LvState.Phase.ACTIVE_ABSTRACT
                && c.transitions().stream().anyMatch(t -> t.detail().equals("restored_after_restart"))), "the same conflict continues, not a new one");
        check(s.treaties.values().stream().anyMatch(t -> t.term() == LvState.Term.PEACE && t.status() == LvState.TreatyStatus.ACTIVE), "the peace is still in force");
        check(s.journal.values().stream().filter(o -> o.kind() == LvState.OperationKind.CONQUEST).allMatch(o -> o.state() != LvState.OperationState.INTENT),
                "no conquest is left half-recorded");
        var ashford = access().village(server, UUID.fromString(props.getProperty("a1"))).orElseThrow();
        long people = villagers(server.overworld(), ashford);
        check(people >= Long.parseLong(props.getProperty("ashfordPeople")), "Ashford's residents are still in the world after restart (" + people + ")");
        finish.accept("PASS integration Living Villages restart: bindings, realms, restored siege, peace and residents");
    }

    static void absent(MinecraftServer server, Consumer<String> finish) throws Exception {
        var props = saved();
        check(!LvCompat.installed() && LvRuntime.get(server).isEmpty(), "without Living Villages the bridge is inactive and Kingdoms starts");
        var kingdoms = McaKingdomsApi.get(server);
        check(kingdoms.getSettlement(UUID.fromString(props.getProperty("oldmere"))).isPresent(), "settlements and their history remain");
        check(kingdoms.getSettlementByExternalRef(LvBindings.EXTERNAL_REF, props.getProperty("b1")).isPresent(), "provider references remain readable");
        check(LvCompat.snapshot(server).overall() == LvCompat.Readiness.UNAVAILABLE, "status reports the provider as unavailable, not destroyed");
        finish.accept("PASS integration Living Villages absent: records kept, nothing rewritten");
    }

    static void reinstalled(MinecraftServer server, Consumer<String> finish) throws Exception {
        var props = saved();
        check(LvRuntime.get(server).isPresent(), "the bridge resumes when Living Villages returns");
        var s = state(server);
        for (String key : List.of("a1", "a2", "b1", "b2", "d1"))
            check(s.bindings.get(UUID.fromString(props.getProperty(key))).status() == LvState.BindingStatus.BOUND, key + " is bound again without a new settlement");
        check(s.bindings.values().stream().noneMatch(b -> b.status() == LvState.BindingStatus.AMBIGUOUS), "no village became ambiguous");
        finish.accept("PASS integration Living Villages reinstalled: same bindings, no duplicates");
    }

    static void future(MinecraftServer server, Consumer<String> finish) throws Exception {
        check(!LvBridgeSavedData.get(server).writable(), "a future schema is kept read-only");
        check(LvRuntime.get(server).isEmpty(), "the integration makes no changes while its records are read-only");
        check(LvCompat.snapshot(server).of(LvCompat.Capability.STABLE_BINDING).readiness() != LvCompat.Readiness.READY, "status explains it");
        finish.accept("PASS integration Living Villages future schema: store read-only, integration held");
    }

    // ---- phase: lv-mca -------------------------------------------------------------------------------------------------

    static void mca(MinecraftServer server, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) throws Exception {
        check(net.minecraftforge.fml.ModList.get().isLoaded("mca"), "MCA Reborn is installed for this phase");
        check(LvCompat.snapshot(server).ready(LvCompat.Capability.RESIDENT_PRESERVATION), "resident preservation is READY with MCA");
        var level = server.overworld();
        Object village = found(level, "Mcaford", ground(level, 24, 24));
        schedule.accept(80L, () -> wrap(finish, () -> {
            check(binding(server, village).status() == LvState.BindingStatus.BOUND, "the MCA-run village is bound");
            Class<?> manager = type("materialize.MaterializationManager");
            // As in the main phase: the model fits its beds with civilians to place, and the proximity loop stands in for a
            // player in the village.
            List<Object> roster = access().roster(village);
            Object leaderRef = method(type("data.FactionData"), "getLeaderRef").invoke(village);
            for (var it = roster.iterator(); it.hasNext() && roster.size() > 4; )
                if (!method(type("data.RecruitUnit"), "getId").invoke(it.next()).equals(leaderRef)) it.remove();
            access().setCivilians(village, Math.max(1, access().beds(village) - roster.size()));
            holdProximity(true);
            method(manager, "materialize", ServerLevel.class, type("data.FactionData")).invoke(null, level, village);
            check(access().joblessLive(village) > 0, "fixture: Mcaford's model has civilians to place (" + access().joblessLive(village) + ")");
            schedule.accept(100L, () -> wrap(finish, () -> {
                method(manager, "liveSync", type("data.FactionData")).invoke(null, village);
                UUID id = access().id(village);
                var people = level.getEntitiesOfClass(Mob.class, access().box(village), m -> m.isAlive() && m.getPersistentData().hasUUID("RVVillagerFaction")
                        && m.getPersistentData().getUUID("RVVillagerFaction").equals(id));
                check(!people.isEmpty(), "the village has residents");
                check(people.stream().allMatch(m -> m.getClass().getName().startsWith("forge.net.mca.entity.")), "T25: every new resident is an MCA person made by MCA's factory: "
                        + people.stream().map(m -> m.getClass().getSimpleName()).distinct().toList());
                var uuids = people.stream().map(Mob::getUUID).toList();
                method(manager, "dematerialize", type("data.FactionData")).invoke(null, village);
                check(uuids.stream().allMatch(u -> level.getEntity(u) != null && level.getEntity(u).isAlive()), "T23: the MCA residents are still the same living people after the provider folds the village");
                schedule.accept(30L, () -> wrap(finish, () -> {
                    check(state(server).residents.keySet().containsAll(uuids), "the bridge tracks every MCA resident by its entity id");
                    holdProximity(false);
                    finish.accept("PASS integration Living Villages with MCA: residents are MCA persons and are never discarded");
                }));
            }));
        }));
    }
}
