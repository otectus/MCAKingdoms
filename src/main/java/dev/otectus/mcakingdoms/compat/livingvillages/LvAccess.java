package dev.otectus.mcakingdoms.compat.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * The audited Recruits: Living Villages 1.1.0 boundary. Every provider class, field and method this integration uses is
 * resolved here once, by exact name and signature; a missing member makes {@link #shape()} report it and the integration
 * stay off. Living Villages exposes no public API, so these are implementation details of that one release, called
 * only on the server thread. Nothing here is copied provider code: reads take what the provider holds, and writes call
 * the provider's own operations.
 */
public final class LvAccess {
    static final String ROOT = "com.diego.recruitsvillages.";
    static final String RECRUITS = "com.talhanation.recruits.";

    private static volatile LvAccess instance;
    private static volatile String failure = "";

    final Class<?> savedData, faction, unit, slot, warManager, war, siege, relations, modifier, materialization, population,
            economy, currency, stores, config, screenData, network, playerService, jobs, workerMaterializer, liveEffects,
            diplomacyStatus;
    private final Method savedGet, getFactions, getFaction, getCapital, getFactionVillages, elapsedSteps, putFaction,
            fId, fName, fSetName, fTeam, fLeaderRef, fCapitalId, fIsCapital, fFactionId, fCenter, fOwner, fPlayerFaction,
            fLedger, fCivilians, fSetCivilians, fBodies, fRoster, fRecruitCount, fEmeralds, fSetEmeralds, fFood, fStructures, fWarStage,
            fHouseLimit, fSetHouseLimit, fSetCenter, fSetTerrainFit, fSetFood, fMaterialsHeld, fMaterial,
            uId, uType, uLevel, uRole, uBody,
            sId, sType, sBuilt, sWorkerAssigned, sWorker, sTier, sPaid, sProgress, sSetBuilt, sSetPaid, sSetPlaced, sSetPlacedPos,
            sUnbuilt, isAtWar, isBesieged, warBetween, canWageWar, cooldownRemaining, endWar, recallForcesHome, clearMuster,
            status, score, modifiers, isMaterialized, isManaged, villageBox, trackedVillagers, trackedWorkers, factionOf,
            popTotal, popCapacity, popHouses, popJobless, popWorkers, popJoblessLive, popRecruitCap, structureCost,
            currencyItem, storesCredit, storesCreditMaterial, maxTreasury, maxMaterial, screenBuild, networkSend,
            nearestVillage, factionOfPlayer, jobLevel, applyRank, professionFor, shownLevel, addTierOffers, workerLevelUp,
            modLabel, modValue, bridgeSpotClaimable;
    private final Field wars, sieges, cooldowns, warAttacker, warAttackerCapital, warAttackerVillages, warDefender,
            warDefenderVillages, warAttackerForce, warDefenderForce, warParticipants, warDefenceParticipants, warInitiator,
            warActive, warUndeclared, siegeAttacker, siegeDefender, siegeAStart, siegeDStart;
    private final Constructor<?> siegeConstructor, factionConstructor;

    private LvAccess() throws ReflectiveOperationException {
        savedData = type("data.LivingVillagesSavedData");
        faction = type("data.FactionData");
        unit = type("data.RecruitUnit");
        slot = type("data.StructureSlot");
        warManager = type("war.WarManager");
        war = type("war.WarManager$War");
        siege = type("war.WarManager$AbstractSiege");
        relations = type("sim.Relations");
        modifier = type("sim.Relations$Modifier");
        materialization = type("materialize.MaterializationManager");
        population = type("sim.Population");
        economy = type("sim.Economy");
        currency = type("sim.Currency");
        stores = type("sim.Stores");
        config = type("config.RecruitsVillagesConfig");
        screenData = type("network.VillageScreenData");
        network = type("network.RVNetwork");
        playerService = type("player.PlayerFactionService");
        jobs = type("sim.Jobs");
        workerMaterializer = type("materialize.WorkerMaterializer");
        liveEffects = type("materialize.LiveEffects");
        diplomacyStatus = Class.forName(RECRUITS + "world.RecruitsDiplomacyManager$DiplomacyStatus");

        savedGet = savedData.getMethod("get", MinecraftServer.class);
        getFactions = savedData.getMethod("getFactions");
        getFaction = savedData.getMethod("getFaction", UUID.class);
        getCapital = savedData.getMethod("getCapital", faction);
        getFactionVillages = savedData.getMethod("getFactionVillages", faction);
        elapsedSteps = savedData.getMethod("getElapsedSteps");
        putFaction = savedData.getMethod("putFaction", faction);

        fId = faction.getMethod("getId");
        fName = faction.getMethod("getName");
        fSetName = faction.getMethod("setName", String.class);
        fTeam = faction.getMethod("getStringID");
        fLeaderRef = faction.getMethod("getLeaderRef");
        fCapitalId = faction.getMethod("getCapitalId");
        fIsCapital = faction.getMethod("isCapital");
        fFactionId = faction.getMethod("getFactionId");
        fCenter = faction.getMethod("getCenterPos");
        fSetCenter = faction.getMethod("setCenterPos", BlockPos.class);
        fOwner = faction.getMethod("getOwnerUuid");
        fPlayerFaction = faction.getMethod("isPlayerFaction");
        fLedger = faction.getMethod("getLedgerPos");
        fCivilians = faction.getMethod("getCivilianCount");
        fSetCivilians = faction.getMethod("setCivilianCount", int.class);
        fBodies = faction.getMethod("getCivilianBodies");
        fRoster = faction.getMethod("getRoster");
        fRecruitCount = faction.getMethod("getRecruitCount");
        fEmeralds = faction.getMethod("getEmeralds");
        fSetEmeralds = faction.getMethod("setEmeralds", int.class);
        fFood = faction.getMethod("getFood");
        fSetFood = faction.getMethod("setFood", int.class);
        fStructures = faction.getMethod("getStructures");
        fWarStage = faction.getMethod("getWarStage");
        fHouseLimit = faction.getMethod("getHouseLimit");
        fSetHouseLimit = faction.getMethod("setHouseLimit", int.class);
        fSetTerrainFit = faction.getMethod("setTerrainFit", boolean.class);
        fMaterialsHeld = faction.getMethod("materialsHeld");
        fMaterial = faction.getMethod("getMaterial", String.class);
        factionConstructor = faction.getConstructor(UUID.class, String.class);

        uId = unit.getMethod("getId");
        uType = unit.getMethod("getType");
        uLevel = unit.getMethod("getLevel");
        uRole = unit.getMethod("getRole");
        uBody = unit.getMethod("getBodyId");

        sId = slot.getMethod("getId");
        sType = slot.getMethod("getType");
        sBuilt = slot.getMethod("isBuilt");
        sWorkerAssigned = slot.getMethod("isWorkerAssigned");
        sWorker = slot.getMethod("getWorkerEntityId");
        sTier = slot.getMethod("getTier");
        sPaid = slot.getMethod("isConstructionPaid");
        sProgress = slot.getMethod("getBuildProgress");
        sSetBuilt = slot.getMethod("setBuilt", boolean.class);
        sSetPaid = slot.getMethod("setConstructionPaid", boolean.class);
        sSetPlaced = slot.getMethod("setPlaced", boolean.class);
        sSetPlacedPos = slot.getMethod("setPlacedPos", int.class, int.class, int.class);
        sUnbuilt = slot.getMethod("unbuilt", String.class);

        isAtWar = warManager.getMethod("isAtWar", UUID.class);
        isBesieged = warManager.getMethod("isBesieged", UUID.class);
        warBetween = warManager.getMethod("warBetween", UUID.class, UUID.class);
        canWageWar = warManager.getMethod("canWageWar", faction);
        cooldownRemaining = warManager.getMethod("declareCooldownRemaining", ServerLevel.class, faction);
        endWar = declared(warManager, "endWar", ServerLevel.class, war);
        recallForcesHome = declared(warManager, "recallForcesHome", ServerLevel.class, war);
        clearMuster = declared(warManager, "clearMuster", war);
        wars = field(warManager, "WARS");
        sieges = field(warManager, "ABSTRACT_SIEGES");
        cooldowns = field(warManager, "DECLARE_COOLDOWNS");
        warAttacker = field(war, "attacker");
        warAttackerCapital = field(war, "attackerCapital");
        warAttackerVillages = field(war, "attackerVillages");
        warDefender = field(war, "defender");
        warDefenderVillages = field(war, "defenderVillages");
        warAttackerForce = field(war, "attackerForce");
        warDefenderForce = field(war, "defenderForce");
        warParticipants = field(war, "participants");
        warDefenceParticipants = field(war, "defenceParticipants");
        warInitiator = field(war, "initiator");
        warActive = field(war, "active");
        warUndeclared = field(war, "undeclared");
        siegeAttacker = field(siege, "attackerId");
        siegeDefender = field(siege, "defenderId");
        siegeAStart = field(siege, "aStart");
        siegeDStart = field(siege, "dStart");
        siegeConstructor = siege.getDeclaredConstructor(UUID.class, UUID.class, int.class, int.class);
        siegeConstructor.setAccessible(true);

        status = relations.getMethod("status", faction, faction);
        score = relations.getMethod("score", ServerLevel.class, faction, faction);
        modifiers = relations.getMethod("modifiers", ServerLevel.class, faction, faction);
        modLabel = modifier.getMethod("label");
        modValue = modifier.getMethod("value");

        isMaterialized = materialization.getMethod("isMaterialized", UUID.class);
        isManaged = materialization.getMethod("isManaged", UUID.class);
        villageBox = materialization.getMethod("villageBox", faction);
        trackedVillagers = materialization.getMethod("trackedVillagers", UUID.class);
        trackedWorkers = materialization.getMethod("trackedWorkers", UUID.class);
        factionOf = materialization.getMethod("factionOf", UUID.class);

        popTotal = population.getMethod("total", faction);
        popCapacity = population.getMethod("capacity", faction);
        popHouses = population.getMethod("houses", faction);
        popJobless = population.getMethod("jobless", faction);
        popWorkers = population.getMethod("workers", faction);
        popJoblessLive = population.getMethod("joblessLive", faction);
        popRecruitCap = population.getMethod("recruitCap", faction);
        structureCost = economy.getMethod("structureCost", String.class);
        currencyItem = currency.getMethod("item");
        storesCredit = stores.getMethod("credit", faction, int.class);
        storesCreditMaterial = stores.getMethod("credit", faction, String.class, int.class);
        maxTreasury = stores.getMethod("maxTreasury");
        maxMaterial = stores.getMethod("maxMaterial", String.class);
        screenBuild = screenData.getMethod("build", ServerLevel.class, savedData, faction, boolean.class, ServerPlayer.class);
        networkSend = network.getMethod("sendTo", ServerPlayer.class, Object.class);
        nearestVillage = playerService.getMethod("nearestVillage", ServerLevel.class, ServerPlayer.class);
        factionOfPlayer = playerService.getMethod("factionOfPlayer", ServerLevel.class, ServerPlayer.class);
        bridgeSpotClaimable = type("faction.RecruitsFactionBridge").getMethod("spotClaimable", faction, int.class, int.class, net.minecraft.core.Vec3i.class);
        jobLevel = jobs.getMethod("levelOf", slot);
        applyRank = workerMaterializer.getMethod("applyRank", net.minecraft.world.entity.npc.Villager.class,
                net.minecraft.world.entity.npc.VillagerProfession.class, slot);
        professionFor = workerMaterializer.getMethod("professionFor", String.class);
        shownLevel = workerMaterializer.getMethod("shownLevel", net.minecraft.world.entity.npc.Villager.class);
        addTierOffers = declared(workerMaterializer, "addTierOffers", net.minecraft.world.item.trading.MerchantOffers.class,
                net.minecraft.world.entity.npc.VillagerTrades.ItemListing[].class, net.minecraft.util.RandomSource.class,
                net.minecraft.world.entity.npc.Villager.class);
        workerLevelUp = liveEffects.getMethod("workerLevelUp", ServerLevel.class, net.minecraft.world.phys.Vec3.class);
        // Configuration values this integration reports or obeys; resolved now so a renamed key disables it.
        for (String key : List.of("AI_WAR_ENABLED", "AI_WAR_REQUIRES_ENEMY", "REL_ENABLED", "CIVILIAN_ENTITY_TYPE", "CURRENCY_ITEM",
                "SIEGE_TRIGGER_RECRUITS", "SIEGE_ALLOW_NO_OWNER", "DISABLE_NOBLES", "ADOPT_UNDECLARED_SIEGES", "CLAIM_RADIUS_CHUNKS",
                "MATERIALIZE_RADIUS", "JOIN_RANGE", "ACCEPT_WINDOW_SECONDS", "WAR_DECLARE_COOLDOWN_SECONDS", "NATURAL_GEN_ENABLED",
                "FIRST_FREE_BUILDINGS", "LAND_COST_ESTIMATE", "STRUCTURE_THRESHOLD", "ADOPT_STRAY_VILLAGERS", "MAX_TREASURY",
                "WAR_TIMEOUT_SECONDS", "SIM_INTERVAL_TICKS", "EXPANSION_ENABLED", "NEW_VILLAGE_CATCHUP"))
            config.getField(key);
    }

    /** The adapter, or empty when Living Villages is absent or its audited shape is missing a member (see {@link #shape()}). */
    public static Optional<LvAccess> get() {
        var current = instance;
        if (current != null) return Optional.of(current);
        synchronized (LvAccess.class) {
            if (instance != null) return Optional.of(instance);
            if (!net.minecraftforge.fml.ModList.get().isLoaded("recruitsvillages")) {
                failure = "absent";
                return Optional.empty();
            }
            try {
                instance = new LvAccess();
                failure = "";
                return Optional.of(instance);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException mismatch) {
                failure = mismatch.getClass().getSimpleName() + ": " + mismatch.getMessage();
                com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] Recruits: Living Villages does not have the audited 1.1.0 shape; "
                        + "the integration stays off: {}", failure);
                return Optional.empty();
            }
        }
    }

    /** Empty when the audited shape resolved, otherwise the first missing member. */
    public static String shape() {
        get();
        return failure;
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(ROOT + name);
    }

    private static Method declared(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Living Villages call " + method.getName() + " failed", cause);
        }
    }

    static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Living Villages field " + field.getName() + " unreadable", failure);
        }
    }

    // ---- saved data -------------------------------------------------------------------------------------------------

    public Object store(MinecraftServer server) {
        return call(savedGet, null, server);
    }

    public Collection<?> villages(MinecraftServer server) {
        return List.copyOf((Collection<?>) call(getFactions, store(server)));
    }

    public Optional<Object> village(MinecraftServer server, UUID id) {
        return Optional.ofNullable(call(getFaction, store(server), id));
    }

    public Object capitalOf(MinecraftServer server, Object village) {
        return call(getCapital, store(server), village);
    }

    @SuppressWarnings("unchecked")
    public List<Object> membersOf(MinecraftServer server, Object village) {
        return List.copyOf((List<Object>) call(getFactionVillages, store(server), village));
    }

    public long elapsedSteps(MinecraftServer server) {
        return (long) call(elapsedSteps, store(server));
    }

    public void dirty(MinecraftServer server) {
        ((SavedData) store(server)).setDirty();
    }

    public void add(MinecraftServer server, Object village) {
        call(putFaction, store(server), village);
    }

    public Object newVillage(UUID id, String name) {
        try {
            return factionConstructor.newInstance(id, name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Living Villages village record could not be created", failure);
        }
    }

    // ---- village fields ---------------------------------------------------------------------------------------------

    public UUID id(Object village) { return (UUID) call(fId, village); }
    public String name(Object village) { return (String) call(fName, village); }
    public void setName(Object village, String name) { call(fSetName, village, name); }
    public String team(Object village) { String team = (String) call(fTeam, village); return team == null ? "" : team; }
    public UUID leaderRef(Object village) { return (UUID) call(fLeaderRef, village); }
    public UUID capitalId(Object village) { return (UUID) call(fCapitalId, village); }
    public boolean isCapital(Object village) { return (boolean) call(fIsCapital, village); }
    public UUID factionId(Object village) { return (UUID) call(fFactionId, village); }
    public BlockPos center(Object village) { return (BlockPos) call(fCenter, village); }
    public void setCenter(Object village, BlockPos pos) { call(fSetCenter, village, pos); }
    public UUID owner(Object village) { return (UUID) call(fOwner, village); }
    public boolean playerFaction(Object village) { return (boolean) call(fPlayerFaction, village); }
    public BlockPos ledger(Object village) { return (BlockPos) call(fLedger, village); }
    public int civilians(Object village) { return (int) call(fCivilians, village); }
    public void setCivilians(Object village, int count) { call(fSetCivilians, village, count); }
    @SuppressWarnings("unchecked") public Map<UUID, BlockPos> bodies(Object village) { return (Map<UUID, BlockPos>) call(fBodies, village); }
    @SuppressWarnings("unchecked") public List<Object> roster(Object village) { return (List<Object>) call(fRoster, village); }
    public int recruits(Object village) { return (int) call(fRecruitCount, village); }
    public int treasury(Object village) { return (int) call(fEmeralds, village); }
    public void setTreasury(Object village, int value) { call(fSetEmeralds, village, value); }
    public int food(Object village) { return (int) call(fFood, village); }
    public void setFood(Object village, int value) { call(fSetFood, village, value); }
    @SuppressWarnings("unchecked") public List<Object> structures(Object village) { return (List<Object>) call(fStructures, village); }
    public String warStage(Object village) { return String.valueOf(call(fWarStage, village)); }
    public int houseLimit(Object village) { return (int) call(fHouseLimit, village); }
    public void setHouseLimit(Object village, int limit) { call(fSetHouseLimit, village, limit); }
    public void setTerrainFit(Object village, boolean fit) { call(fSetTerrainFit, village, fit); }
    @SuppressWarnings("unchecked") public Set<String> materials(Object village) { return Set.copyOf((Set<String>) call(fMaterialsHeld, village)); }
    public int material(Object village, String material) { return (int) call(fMaterial, village, material); }

    /** A player faction mirrored before its Ledger is placed has no physical anchor: a realm, never a village. */
    public boolean virtual(Object village) {
        return playerFaction(village) && ledger(village) == null && BlockPos.ZERO.equals(center(village));
    }

    public UUID unitId(Object unit) { return (UUID) call(uId, unit); }
    public String unitType(Object unit) { return (String) call(uType, unit); }
    public int unitLevel(Object unit) { return (int) call(uLevel, unit); }
    public int unitRole(Object unit) { return (int) call(uRole, unit); }
    public UUID unitBody(Object unit) { return (UUID) call(uBody, unit); }

    public UUID slotId(Object slot) { return (UUID) call(sId, slot); }
    public String slotType(Object slot) { return (String) call(sType, slot); }
    public boolean slotBuilt(Object slot) { return (boolean) call(sBuilt, slot); }
    public boolean slotStaffed(Object slot) { return (boolean) call(sWorkerAssigned, slot); }
    public UUID slotWorker(Object slot) { return (UUID) call(sWorker, slot); }
    public int slotTier(Object slot) { return (int) call(sTier, slot); }
    public boolean slotPaid(Object slot) { return (boolean) call(sPaid, slot); }
    public int slotProgress(Object slot) { return (int) call(sProgress, slot); }

    /** A built, already placed slot standing for an existing building, so the provider never stamps a template over it. */
    public Object existingBuilding(String type, BlockPos at) {
        Object slot = call(sUnbuilt, null, type);
        call(sSetBuilt, slot, true);
        call(sSetPaid, slot, true);
        call(sSetPlaced, slot, true);
        call(sSetPlacedPos, slot, at.getX(), at.getY(), at.getZ());
        return slot;
    }

    // ---- war ----------------------------------------------------------------------------------------------------------

    public boolean atWar(UUID village) { return (boolean) call(isAtWar, null, village); }
    public boolean besieged(UUID village) { return (boolean) call(isBesieged, null, village); }
    public boolean warBetween(UUID a, UUID b) { return (boolean) call(warBetween, null, a, b); }
    public boolean canWageWar(Object village) { return (boolean) call(canWageWar, null, village); }
    public int cooldownSeconds(ServerLevel level, Object village) { return (int) call(cooldownRemaining, null, level, village); }

    /** The provider's live war list (not a copy); iterate a copy and mutate only through the provider's operations. */
    @SuppressWarnings("unchecked")
    List<Object> liveWars() { return (List<Object>) read(wars, null); }

    @SuppressWarnings("unchecked")
    List<Object> abstractSieges() { return (List<Object>) read(sieges, null); }

    @SuppressWarnings("unchecked")
    Map<UUID, Long> cooldowns() { return (Map<UUID, Long>) read(cooldowns, null); }

    public LvSnapshots.War war(Object war) {
        @SuppressWarnings("unchecked") Set<UUID> attackers = (Set<UUID>) read(warAttackerVillages, war);
        @SuppressWarnings("unchecked") Set<UUID> defenders = (Set<UUID>) read(warDefenderVillages, war);
        @SuppressWarnings("unchecked") Set<UUID> attackerForce = (Set<UUID>) read(warAttackerForce, war);
        @SuppressWarnings("unchecked") Set<UUID> defenderForce = (Set<UUID>) read(warDefenderForce, war);
        @SuppressWarnings("unchecked") Set<UUID> participants = (Set<UUID>) read(warParticipants, war);
        @SuppressWarnings("unchecked") Set<UUID> defence = (Set<UUID>) read(warDefenceParticipants, war);
        return new LvSnapshots.War((UUID) read(warAttacker, war), (UUID) read(warAttackerCapital, war), (UUID) read(warDefender, war),
                Set.copyOf(attackers), Set.copyOf(defenders), attackerForce.size(), defenderForce.size(),
                Set.copyOf(participants), Set.copyOf(defence), (UUID) read(warInitiator, war),
                (boolean) read(warActive, war), (boolean) read(warUndeclared, war));
    }

    @SuppressWarnings("unchecked")
    Set<UUID> attackerSet(Object war) { return (Set<UUID>) read(warAttackerVillages, war); }

    @SuppressWarnings("unchecked")
    Set<UUID> defenderSet(Object war) { return (Set<UUID>) read(warDefenderVillages, war); }

    public LvSnapshots.Siege siege(Object siege) {
        return new LvSnapshots.Siege((UUID) read(siegeAttacker, siege), (UUID) read(siegeDefender, siege),
                (int) read(siegeAStart, siege), (int) read(siegeDStart, siege));
    }

    public List<LvSnapshots.War> wars() {
        return liveWars().stream().map(this::war).toList();
    }

    public List<LvSnapshots.Siege> sieges() {
        return abstractSieges().stream().map(this::siege).toList();
    }

    /** Ends a live war through the provider's own recall and end operations: survivors return home, nobody is rewarded. */
    void endLiveWar(ServerLevel level, Object war) {
        call(recallForcesHome, null, level, war);
        call(endWar, null, level, war);
    }

    /** Drops a declared war that has not begun: its muster is released and it leaves the war list. */
    void dropPendingWar(Object war) {
        call(clearMuster, null, war);
        liveWars().remove(war);
    }

    /** Puts an abstract siege back after a failed live start, so the conflict continues instead of vanishing. */
    void restoreSiege(LvSnapshots.Siege siege) {
        try {
            abstractSieges().add(siegeConstructor.newInstance(siege.attacker(), siege.defender(), siege.attackerStart(), siege.defenderStart()));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Living Villages siege could not be restored", failure);
        }
    }

    boolean removeSiege(UUID attacker, UUID defender) {
        return abstractSieges().removeIf(s -> attacker.equals(read(siegeAttacker, s)) && defender.equals(read(siegeDefender, s)));
    }

    // ---- diplomacy ------------------------------------------------------------------------------------------------------

    /** 0 neutral, 1 ally, 2 enemy, as the provider reads the Recruits relation between two capitals. */
    public int relation(Object a, Object b) { return (int) call(status, null, a, b); }
    public int opinion(ServerLevel level, Object from, Object to) { return (int) call(score, null, level, from, to); }

    public List<LvSnapshots.Modifier> modifiers(ServerLevel level, Object from, Object to) {
        var out = new ArrayList<LvSnapshots.Modifier>();
        for (Object m : (List<?>) call(modifiers, null, level, from, to))
            out.add(new LvSnapshots.Modifier((String) call(modLabel, m), (int) call(modValue, m)));
        return List.copyOf(out);
    }

    /** The Recruits enum constant for a provider status int. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object diplomacyStatus(int status) {
        String name = switch (status) { case 1 -> "ALLY"; case 2 -> "ENEMY"; default -> "NEUTRAL"; };
        return Enum.valueOf((Class<? extends Enum>) diplomacyStatus, name);
    }

    static int statusOf(Object diplomacyStatus) {
        return switch (String.valueOf(diplomacyStatus)) { case "ALLY" -> 1; case "ENEMY" -> 2; default -> 0; };
    }

    /** Writes one direction of the native relation through Recruits' diplomacy manager, as Living Villages itself does. */
    void writeRelation(ServerLevel level, String from, String to, int status) {
        try {
            Object manager = Class.forName(RECRUITS + "FactionEvents").getField("recruitsDiplomacyManager").get(null);
            if (manager == null) throw new IllegalStateException("Recruits diplomacy is not ready");
            manager.getClass().getMethod("setRelation", String.class, String.class, diplomacyStatus, ServerLevel.class)
                    .invoke(manager, from, to, diplomacyStatus(status), level);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Recruits diplomacy write failed", failure);
        }
    }

    /** One direction of the native relation, read from Recruits: 0 neutral, 1 ally, 2 enemy, -1 unknown. */
    int readRelation(String from, String to) {
        try {
            Object manager = Class.forName(RECRUITS + "FactionEvents").getField("recruitsDiplomacyManager").get(null);
            if (manager == null) return -1;
            return statusOf(manager.getClass().getMethod("getRelation", String.class, String.class).invoke(manager, from, to));
        } catch (ReflectiveOperationException failure) {
            return -1;
        }
    }

    // ---- materialization and population ---------------------------------------------------------------------------------

    public boolean materialized(UUID village) { return (boolean) call(isMaterialized, null, village); }
    public boolean managed(UUID village) { return (boolean) call(isManaged, null, village); }
    public AABB box(Object village) { return (AABB) call(villageBox, null, village); }
    @SuppressWarnings("unchecked") public List<UUID> trackedCivilians(UUID village) { return (List<UUID>) call(trackedVillagers, null, village); }
    @SuppressWarnings("unchecked") public Map<UUID, UUID> trackedWorkers(UUID village) { return (Map<UUID, UUID>) call(trackedWorkers, null, village); }
    public UUID unitFactionOf(UUID entity) { return (UUID) call(factionOf, null, entity); }
    public int population(Object village) { return (int) call(popTotal, null, village); }
    public int beds(Object village) { return (int) call(popCapacity, null, village); }
    public int houses(Object village) { return (int) call(popHouses, null, village); }
    public int jobless(Object village) { return (int) call(popJobless, null, village); }
    public int workers(Object village) { return (int) call(popWorkers, null, village); }
    public int joblessLive(Object village) { return (int) call(popJoblessLive, null, village); }
    public int garrisonCap(Object village) { return (int) call(popRecruitCap, null, village); }
    public int structureCost(String type) { return (int) call(structureCost, null, type); }

    // ---- economy --------------------------------------------------------------------------------------------------------

    public net.minecraft.world.item.Item currency() { return (net.minecraft.world.item.Item) call(currencyItem, null); }
    public int credit(Object village, int amount) { return (int) call(storesCredit, null, village, amount); }
    public int creditMaterial(Object village, String material, int amount) { return (int) call(storesCreditMaterial, null, village, material, amount); }
    public int maxTreasury() { return (int) call(maxTreasury, null); }
    public int maxMaterial(String material) { return (int) call(maxMaterial, null, material); }

    // ---- screens, membership --------------------------------------------------------------------------------------------

    /** Sends the provider's own village screen, built by the provider for this player, exactly as its /rvvillage command does. */
    public void openScreen(ServerPlayer player, Object village) {
        Object message = call(screenBuild, null, player.serverLevel(), store(player.getServer()), village, false, player);
        call(networkSend, null, player, message);
    }

    public Optional<Object> nearestVillage(ServerPlayer player) {
        return Optional.ofNullable(call(nearestVillage, null, player.serverLevel(), player));
    }

    public Optional<Object> playerRealm(ServerPlayer player) {
        return Optional.ofNullable(call(factionOfPlayer, null, player.serverLevel(), player));
    }

    // ---- workers -------------------------------------------------------------------------------------------------------

    public int jobLevel(Object slot) { return (int) call(jobLevel, null, slot); }
    public net.minecraft.world.entity.npc.VillagerProfession profession(String building) {
        return (net.minecraft.world.entity.npc.VillagerProfession) call(professionFor, null, building);
    }
    public int shownLevel(net.minecraft.world.entity.npc.Villager worker) { return (int) call(shownLevel, null, worker); }
    public void applyRank(net.minecraft.world.entity.npc.Villager worker, net.minecraft.world.entity.npc.VillagerProfession profession, Object slot) {
        call(applyRank, null, worker, profession, slot);
    }
    /** The provider's own two offers for one tier, added to the worker's existing trades. */
    public void addTier(net.minecraft.world.item.trading.MerchantOffers offers, net.minecraft.world.entity.npc.VillagerTrades.ItemListing[] listings,
                        net.minecraft.world.entity.npc.Villager worker) {
        call(addTierOffers, null, offers, listings, worker.getRandom(), worker);
    }
    public void levelUpEffect(ServerLevel level, net.minecraft.world.phys.Vec3 at) { call(workerLevelUp, null, level, at); }

    // ---- configuration --------------------------------------------------------------------------------------------------

    /** A provider server-config value, or {@code fallback} before the world's server config has loaded. */
    public Object setting(String field, Object fallback) {
        try {
            Object value = config.getField(field).get(null);
            return value instanceof ForgeConfigSpec.ConfigValue<?> configured ? configured.get() : fallback;
        } catch (ReflectiveOperationException | IllegalStateException | NullPointerException notLoaded) {
            return fallback;
        }
    }

    public boolean flag(String field) { return Boolean.TRUE.equals(setting(field, false)); }

    /** Living Villages' own test that a building spot's chunks can be claimed for {@code village}. */
    public boolean spotClaimable(Object village, int x, int z, net.minecraft.core.Vec3i footprint) {
        return (boolean) call(bridgeSpotClaimable, null, village, x, z, footprint);
    }

    public int number(String field, int fallback) {
        return setting(field, fallback) instanceof Number number ? number.intValue() : fallback;
    }

    public double decimal(String field, double fallback) {
        return setting(field, fallback) instanceof Number number ? number.doubleValue() : fallback;
    }

    // ---- claims (Recruits) -----------------------------------------------------------------------------------------------

    /** The Recruits claim holding the village's centre chunk, as the provider finds a village's claim. */
    public Optional<UUID> claimOf(Object village) {
        try {
            Object manager = Class.forName(RECRUITS + "ClaimEvents").getField("recruitsClaimManager").get(null);
            if (manager == null) return Optional.empty();
            Object claim = manager.getClass().getMethod("getClaim", ChunkPos.class).invoke(manager, new ChunkPos(center(village)));
            if (claim == null) return Optional.empty();
            return Optional.of((UUID) claim.getClass().getMethod("getUUID").invoke(claim));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** The owning Recruits faction id of the claim at the village centre, or empty. */
    public Optional<String> claimOwner(Object village) {
        try {
            Object manager = Class.forName(RECRUITS + "ClaimEvents").getField("recruitsClaimManager").get(null);
            if (manager == null) return Optional.empty();
            Object claim = manager.getClass().getMethod("getClaim", ChunkPos.class).invoke(manager, new ChunkPos(center(village)));
            if (claim == null) return Optional.empty();
            return Optional.ofNullable((String) claim.getClass().getMethod("getOwnerFactionStringID").invoke(claim));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** Called by the integration's lifecycle; the adapter holds no world state, only resolved members. */
    static void forgetForTests() {
        instance = null;
        failure = "";
    }
}
