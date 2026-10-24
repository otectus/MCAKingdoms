package dev.otectus.mcakingdoms;

import dev.otectus.mcakingdoms.api.ApiBootstrap;
import dev.otectus.mcakingdoms.api.Registration;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.compat.mca.McaCompat;
import dev.otectus.mcakingdoms.config.McaKingdomsConfig;
import dev.otectus.mcakingdoms.core.KingdomsServiceImpl;
import dev.otectus.mcakingdoms.data.DefinitionRegistry;
import dev.otectus.mcakingdoms.integration.IntegrationBootstrap;
import dev.otectus.mcakingdoms.integration.IntegrationConfig;
import dev.otectus.mcakingdoms.presentation.Presentation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod(McaKingdomsApi.MOD_ID)
public final class McaKingdoms {
    public static final dev.otectus.mcakingdoms.politics.PoliticalDefinitions POLITICS = new dev.otectus.mcakingdoms.politics.PoliticalDefinitions();
    public static final DefinitionRegistry DEFINITIONS = new DefinitionRegistry();
    private static final int MAX_PENDING_CHUNKS = 4_096;
    private static final Map<MinecraftServer, RuntimeState> RUNTIMES = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, Set<PendingChunk>> PENDING_CHUNKS = new ConcurrentHashMap<>();

    public McaKingdoms() {
        // Before any config is registered or read: carry Ultima Kingdoms' config files over (0.1.1 rename).
        dev.otectus.mcakingdoms.persistence.LegacyRename.migrateConfigs(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, McaKingdomsConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, IntegrationConfig.SPEC, IntegrationConfig.FILE_NAME);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, dev.otectus.mcakingdoms.warfare.WarfareConfig.SPEC, "mcakingdoms-warfare-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, dev.otectus.mcakingdoms.evolution.EvolutionConfig.SPEC, "mcakingdoms-evolution-common.toml");
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.evolution.EvolutionRuntime.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.evolution.EvolutionCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.evolution.ProtectionCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.evolution.RecruitTransferCommands.class);
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        Presentation.init(modBus);
        IntegrationBootstrap.init(modBus);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.politics.PoliticalCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.warfare.WarfareCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.warfare.WarfareRuntime.class);
        MinecraftForge.EVENT_BUS.register(dev.otectus.mcakingdoms.warfare.MilitaryTargetPolicy.class);
    }

    @SubscribeEvent
    public void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DEFINITIONS.reloadListener());
        event.addListener(POLITICS);
        event.addListener(dev.otectus.mcakingdoms.evolution.EvolutionDefinitions.INSTANCE);
        event.addListener(dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.reloadListener());
        event.addListener(dev.otectus.mcakingdoms.civic.CivicDefinitions.INSTANCE);
        event.addListener(dev.otectus.mcakingdoms.factions.organization.OrganizationRuntime.reloadListener());
        event.addListener(IntegrationBootstrap.gateReloadListener());
    }

    @SubscribeEvent
    public void serverAboutToStart(ServerAboutToStartEvent event) {
        // Levels and saved data load after this event, so a world saved as Ultima Kingdoms is carried
        // over before anything reads the new file names.
        dev.otectus.mcakingdoms.persistence.LegacyRename.migrateSavedData(event.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data"));
        dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.commitPending();
        dev.otectus.mcakingdoms.evolution.EvolutionDefinitions.INSTANCE.commitPending();
        DEFINITIONS.commitPending();
        POLITICS.commitPending();
        dev.otectus.mcakingdoms.civic.CivicDefinitions.INSTANCE.commitPending();
        dev.otectus.mcakingdoms.factions.organization.OrganizationRuntime.commitPending();
        PENDING_CHUNKS.computeIfAbsent(event.getServer(), ignored -> ConcurrentHashMap.newKeySet());
    }

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        KingdomsServiceImpl service = new KingdomsServiceImpl(server, DEFINITIONS);
        ApiBootstrap.attach(server, service);
        Registration mca = () -> { };
        Registration integrations = () -> { };
        try {
            IntegrationBootstrap.commitPending(service);
            dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(server).adopt(server, service);
            // Discovery and visits are adopted first, so a merge carries them over; the pass itself runs on the next ticks.
            service.beginStartupReconciliation();
            integrations = IntegrationBootstrap.attach(server, service);
            mca = McaCompat.attach(server, service);
            service.liveExternalReferences(McaCompat.EXTERNAL_REF, McaCompat::liveVillageReferences);
            var politics = new dev.otectus.mcakingdoms.politics.GovernmentService(server, service, POLITICS);
            dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.attach(server, politics);
            dev.otectus.mcakingdoms.warfare.CampaignService.attach(server);
            dev.otectus.mcakingdoms.compat.crime.JurisdictionPolicyBridge.attach(server);
            MinecraftForge.EVENT_BUS.register(politics);
            RUNTIMES.put(server, new RuntimeState(service, mca, integrations, politics));
            Set<PendingChunk> pending = PENDING_CHUNKS.remove(server);
            if (pending != null) {
                pending.forEach(chunk -> {
                    ServerLevel level = server.getLevel(chunk.dimension());
                    if (level != null) service.enqueueChunk(level, new ChunkPos(chunk.chunk()));
                });
            }
            enqueueLoadedSpawnChunks(server, service);
        } catch (RuntimeException exception) {
            PENDING_CHUNKS.remove(server);
            RuntimeState failedRuntime = RUNTIMES.remove(server);
            if (failedRuntime != null) MinecraftForge.EVENT_BUS.unregister(failedRuntime.politics());
            dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.detach(server);
            dev.otectus.mcakingdoms.compat.crime.JurisdictionPolicyBridge.detach(server);
            dev.otectus.mcakingdoms.warfare.CampaignService.clear(server);
            try {
                mca.close();
            } finally {
                try {
                    integrations.close();
                } finally {
                    ApiBootstrap.detach(server, service);
                }
            }
            throw exception;
        }
    }

    @SubscribeEvent
    public void datapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.commitPending();
        dev.otectus.mcakingdoms.evolution.EvolutionDefinitions.INSTANCE.commitPending();
        MinecraftServer server = event.getPlayerList().getServer();
        DEFINITIONS.commitPending();
        POLITICS.commitPending();
        dev.otectus.mcakingdoms.civic.CivicDefinitions.INSTANCE.commitPending();
        dev.otectus.mcakingdoms.factions.organization.OrganizationRuntime.commitPending();
        RuntimeState runtime = RUNTIMES.get(server);
        if (runtime != null) {
            IntegrationBootstrap.commitPending(runtime.service());
            runtime.service().definitionsReloaded();
        }
    }

    @SubscribeEvent
    public void chunkLoaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RuntimeState runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) {
            runtime.service().enqueueChunk(level, event.getChunk().getPos());
            return;
        }
        Set<PendingChunk> pending = PENDING_CHUNKS.computeIfAbsent(
                level.getServer(), ignored -> ConcurrentHashMap.newKeySet());
        if (pending.size() < MAX_PENDING_CHUNKS) {
            pending.add(new PendingChunk(level.dimension(), event.getChunk().getPos().toLong()));
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.tick(event.getServer());
        RuntimeState runtime = RUNTIMES.get(event.getServer());
        if (runtime != null) runtime.service().tickDiscovery();
    }

    /**
     * Items saved under Ultima Kingdoms' registry names (the Book of Kingdoms and the Village Ledger) load
     * as the same items under this mod's namespace instead of vanishing from inventories and chests.
     */
    @SubscribeEvent
    public void remapLegacyRegistryNames(net.minecraftforge.registries.MissingMappingsEvent event) {
        for (var mapping : event.getMappings(net.minecraft.core.registries.Registries.ITEM,
                dev.otectus.mcakingdoms.persistence.LegacyRename.LEGACY_NAMESPACE)) {
            net.minecraft.resources.ResourceLocation current =
                    new net.minecraft.resources.ResourceLocation(McaKingdomsApi.MOD_ID, mapping.getKey().getPath());
            if (net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(current)) {
                mapping.remap(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(current));
            }
        }
    }

    /**
     * Carries every record keyed by the retired settlement over to the survivor, store by store. The merge has already
     * committed, so a store that cannot be rewritten is logged and left to read-time resolution, never thrown.
     */
    @SubscribeEvent
    public void settlementMerged(dev.otectus.mcakingdoms.api.event.SettlementMergedEvent event) {
        MinecraftServer server = event.server();
        java.util.UUID source = event.source().id();
        java.util.UUID target = event.target().id();
        carryOver("discovery", () -> dev.otectus.mcakingdoms.knowledge.SettlementKnowledge.get(server).merge(source, target));
        carryOver("civic network", () -> dev.otectus.mcakingdoms.civic.CivicService.settlementMerged(server, source, target));
        carryOver("campaigns", () -> dev.otectus.mcakingdoms.warfare.CampaignSavedData.settlementMerged(server, source, target));
        carryOver("military control", () -> dev.otectus.mcakingdoms.warfare.ControlSavedData.settlementMerged(server, source, target));
        carryOver("civilian contracts", () -> dev.otectus.mcakingdoms.warfare.contracts.CivilianContractService.settlementMerged(server, source, target));
        carryOver("world evolution", () -> dev.otectus.mcakingdoms.evolution.EvolutionSavedData.settlementMerged(server, source, target));
        carryOver("protection pacts", () -> dev.otectus.mcakingdoms.evolution.ProtectionSavedData.settlementMerged(server, source, target));
        carryOver("dramas", () -> dev.otectus.mcakingdoms.evolution.drama.DramaSavedData.settlementMerged(server, source, target));
        carryOver("world context", () -> dev.otectus.mcakingdoms.worldcontext.WorldContextSavedData.settlementMerged(server, source, target));
    }

    private static void carryOver(String store, Runnable rewrite) {
        try {
            rewrite.run();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(McaKingdoms.class).error(
                    "[MCA: Kingdoms] Could not carry {} records over to a merged settlement; they still resolve on read", store, failure);
        }
    }

    /** One welcome line on a player's first login, so the menu key and the ledger are discoverable without the wiki. */
    @SubscribeEvent
    public void playerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var persisted = player.getPersistentData().getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        if (persisted.getBoolean("mcakingdoms:welcomed")) return;
        // A player welcomed by Ultima Kingdoms (before the 0.1.1 rename) has already had the line.
        boolean welcomedBefore = persisted.getBoolean(dev.otectus.mcakingdoms.persistence.LegacyRename.LEGACY_NAMESPACE + ":welcomed");
        persisted.remove(dev.otectus.mcakingdoms.persistence.LegacyRename.LEGACY_NAMESPACE + ":welcomed");
        persisted.putBoolean("mcakingdoms:welcomed", true);
        if (welcomedBefore) {
            player.getPersistentData().put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, persisted);
            return;
        }
        player.getPersistentData().put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, persisted);
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatableWithFallback("notice.mcakingdoms.welcome",
                "MCA: Kingdoms: press %s for Kingdom Tasks, craft a Village Ledger (paper, book and compass) to browse settlements, or read the Book of Kingdoms (book and paper).",
                net.minecraft.network.chat.Component.keybind("key.mcakingdoms.actions")));
    }
    @SubscribeEvent
    public void entityJoined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof LivingEntity)
                || event.getEntity() instanceof Player) {
            return;
        }
        RuntimeState runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) runtime.service().observeEntity(event.getEntity());
    }

    @SubscribeEvent
    public void livingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) return;
        int interval = McaKingdomsConfig.CIVIC_EVIDENCE_INTERVAL.get();
        if (Math.floorMod(entity.tickCount + entity.getId(), interval) != 0) return;
        RuntimeState runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) runtime.service().observeEntity(entity);
    }

    @SubscribeEvent
    public void livingConverted(LivingConversionEvent.Post event) {
        MinecraftServer server = event.getEntity().getServer();
        RuntimeState runtime = server == null ? null : RUNTIMES.get(server);
        if (runtime != null) runtime.service().copyIdentity(event.getEntity(), event.getOutcome());
    }

    @SubscribeEvent
    public void serverStopped(ServerStoppedEvent event) {
        dev.otectus.mcakingdoms.evolution.drama.DramaRuntime.clear(event.getServer());
        dev.otectus.mcakingdoms.compat.crime.JurisdictionPolicyBridge.detach(event.getServer());
        dev.otectus.mcakingdoms.warfare.CampaignService.clear(event.getServer());
        dev.otectus.mcakingdoms.warfare.WarfareRuntime.clear(event.getServer());
        PENDING_CHUNKS.remove(event.getServer());
        RuntimeState runtime = RUNTIMES.remove(event.getServer());
        if (runtime != null) {
            MinecraftForge.EVENT_BUS.unregister(runtime.politics());
            dev.otectus.mcakingdoms.api.politics.KingdomsPoliticsApi.detach(event.getServer());
            try {
                runtime.mca().close();
            } finally {
                try {
                    runtime.integrations().close();
                } finally {
                    ApiBootstrap.detach(event.getServer(), runtime.service());
                }
            }
        }
        IntegrationBootstrap.clear();
        DEFINITIONS.clear();
        POLITICS.clear();
        dev.otectus.mcakingdoms.evolution.EvolutionDefinitions.INSTANCE.clear();
        dev.otectus.mcakingdoms.civic.CivicDefinitions.INSTANCE.clear();
        dev.otectus.mcakingdoms.factions.organization.OrganizationRuntime.clear();
    }

    private record RuntimeState(KingdomsServiceImpl service, Registration mca, Registration integrations, dev.otectus.mcakingdoms.politics.GovernmentService politics) {
    }

    private static void enqueueLoadedSpawnChunks(MinecraftServer server, KingdomsServiceImpl service) {
        for (ServerLevel level : server.getAllLevels()) {
            ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
            for (int x = spawn.x - 4; x <= spawn.x + 4; x++) {
                for (int z = spawn.z - 4; z <= spawn.z + 4; z++) {
                    if (level.hasChunk(x, z)) service.enqueueChunk(level, new ChunkPos(x, z));
                }
            }
        }
    }

    private record PendingChunk(ResourceKey<Level> dimension, long chunk) {
    }
}
