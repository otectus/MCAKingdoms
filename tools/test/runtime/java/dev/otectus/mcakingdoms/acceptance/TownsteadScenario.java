package dev.otectus.mcakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementBounds;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.api.event.PublicWorkCompletedEvent;
import dev.otectus.mcakingdoms.api.townstead.KingdomsTownsteadApi;
import dev.otectus.mcakingdoms.api.townstead.TownsteadBuildingEvidence;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapability;
import dev.otectus.mcakingdoms.api.townstead.TownsteadCapabilityState;
import dev.otectus.mcakingdoms.api.townstead.event.TownsteadBuildingsChangedEvent;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadAuthority;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadMappings;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadPresentation;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadRuntime;
import dev.otectus.mcakingdoms.compat.townstead.TownsteadSavedData;
import dev.otectus.mcakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Packaged Townstead acceptance (spec 15.2): the same scenario on every tuple. It reads which Townstead and MCA are
 * installed and expects the matching backend: {@code v1} on Townstead 0.8.x, {@code legacy} on 0.7.x, {@code mca} with
 * MCA alone. Phases: {@code townstead} (first boot), {@code townstead-restart}, {@code townstead-absent} (Townstead jar
 * removed, MCA kept) and {@code townstead-restored}. Never included in release jars.
 */
final class TownsteadScenario {
    static final int VILLAGE = 92101;
    static final BlockPos POS = new BlockPos(6000, 64, 6000);
    static final UUID PLAYER = UUID.fromString("721be7a7-110e-4a66-a24b-3ad63d3c9300");
    static final UUID WORK = UUID.fromString("721be7a7-110e-4a66-a24b-3ad63d3c9301");
    static final List<TownsteadBuildingsChangedEvent> CHANGES = new ArrayList<>();

    private TownsteadScenario() {
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS integration: " + message);
    }

    static String version(String mod) {
        return ModList.get().getModContainerById(mod).map(c -> c.getModInfo().getVersion().toString()).orElse("absent");
    }

    static String expectedBackend() {
        String townstead = version("townstead");
        if (townstead.startsWith("0.8")) return "v1";
        if (townstead.startsWith("0.7")) return "legacy";
        return ModList.get().isLoaded("mca") ? "mca" : "none";
    }

    @SubscribeEvent
    public static void changed(TownsteadBuildingsChangedEvent event) {
        CHANGES.add(event);
    }

    static void run(MinecraftServer server, String phase, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) {
        schedule.accept(5L, () -> {
            try {
                switch (phase) {
                    case "townstead" -> first(server, schedule, finish);
                    case "townstead-restart", "townstead-restored" -> later(server, phase, finish);
                    case "townstead-absent" -> absent(server, finish);
                    default -> finish.accept("FAIL integration townstead: unknown phase " + phase);
                }
            } catch (Throwable failure) {
                failure.printStackTrace();
                finish.accept("FAIL integration townstead " + phase + ": " + failure);
            }
        });
    }

    // ---- MCA fixture for either MCA package root ----

    static String mcaRoot() {
        for (String root : List.of("forge.net.conczin.mca", "forge.net.mca")) {
            try {
                Class.forName(root + ".server.world.data.Village");
                return root;
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new IllegalStateException("no MCA village class");
    }

    @SuppressWarnings("unchecked")
    static Object village(ServerLevel level, int id, BlockPos pos) throws Exception {
        String root = mcaRoot();
        Class<?> type = Class.forName(root + ".server.world.data.Village");
        Object village = type.getConstructor(int.class, ServerLevel.class).newInstance(id, level);
        Object box = Class.forName(root + ".util.BlockBoxExtended").getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(pos.getX() - 8, pos.getY() - 2, pos.getZ() - 8, pos.getX() + 8, pos.getY() + 5, pos.getZ() + 8);
        Field boxField = type.getDeclaredField("box");
        boxField.setAccessible(true);
        boxField.set(village, box);
        type.getMethod("setAutoScan", boolean.class).invoke(village, false);
        Map<Integer, Object> buildings = (Map<Integer, Object>) type.getMethod("getBuildings").invoke(village);
        Class<?> building = Class.forName(root + ".server.world.data.Building");
        for (int i = 0; i < 4; i++) {
            Object b = building.getConstructor(BlockPos.class).newInstance(pos.offset(i * 2, 0, 0));
            building.getMethod("setId", int.class).invoke(b, i);
            building.getMethod("setType", String.class).invoke(b, i == 0 ? "library" : "house");
            buildings.put(i, b);
        }
        Class<?> manager = Class.forName(root + ".server.world.data.VillageManager");
        Object instance = manager.getMethod("get", ServerLevel.class).invoke(null, level);
        Field villages = manager.getDeclaredField("villages");
        villages.setAccessible(true);
        ((Map<Integer, Object>) villages.get(instance)).put(id, village);
        // Saved with the world like a real village, so the restart, absent and restored boots read the same buildings.
        ((net.minecraft.world.level.saveddata.SavedData) instance).setDirty();
        return village;
    }

    @SuppressWarnings("unchecked")
    static Object building(ServerLevel level, int id, int building) throws Exception {
        String root = mcaRoot();
        Object manager = Class.forName(root + ".server.world.data.VillageManager").getMethod("get", ServerLevel.class).invoke(null, level);
        Object village = ((Optional<Object>) manager.getClass().getMethod("getOrEmpty", int.class).invoke(manager, id)).orElseThrow();
        return ((Map<Integer, Object>) village.getClass().getMethod("getBuildings").invoke(village)).get(building);
    }

    /**
     * Fills or empties MCA's block record of a building, which is what MCA's own completion reads (a count against its
     * type's {@code minBlocks}). MCA gives a type no data pack defines a requirement of a billion beds, so such a building
     * is never complete: the acceptance mod defines the fixtures' {@code workshop} type in its own data.
     */
    @SuppressWarnings("unchecked")
    static void blocks(Object building, int count) throws Exception {
        if (count > 4_096) throw new IllegalStateException("the building's type needs " + count + " blocks: is it defined by a data pack?");
        Field blocks = building.getClass().getDeclaredField("blocks");
        blocks.setAccessible(true);
        Map<ResourceLocation, List<BlockPos>> map = (Map<ResourceLocation, List<BlockPos>>) blocks.get(building);
        map.clear();
        if (count <= 0) return;
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < count; i++) positions.add(new BlockPos(i, 0, 0));
        map.put(new ResourceLocation("minecraft:bookshelf"), positions);
    }

    static void dirty(ServerLevel level) throws Exception {
        Object manager = Class.forName(mcaRoot() + ".server.world.data.VillageManager").getMethod("get", ServerLevel.class).invoke(null, level);
        ((net.minecraft.world.level.saveddata.SavedData) manager).setDirty();
    }

    static int minimumBlocks(Object building) throws Exception {
        Object type = building.getClass().getMethod("getBuildingType").invoke(building);
        return (int) type.getClass().getMethod("getMinBlocks").invoke(type);
    }

    @SuppressWarnings("unchecked")
    static void online(MinecraftServer server, ServerPlayer player) throws Exception {
        // Field names are obfuscated on a packaged server: find PlayerList's Map<UUID, ServerPlayer> by its generic type.
        for (Field field : net.minecraft.server.players.PlayerList.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType()) || !(field.getGenericType() instanceof ParameterizedType type)) continue;
            var args = type.getActualTypeArguments();
            if (args.length == 2 && args[0] == UUID.class && args[1] == ServerPlayer.class) {
                field.setAccessible(true);
                ((Map<UUID, ServerPlayer>) field.get(server.getPlayerList())).put(player.getUUID(), player);
                return;
            }
        }
        throw new IllegalStateException("PlayerList has no player map");
    }

    static SettlementView settlement(MinecraftServer server) {
        return McaKingdomsApi.get(server).getSettlementForMcaVillage(server.overworld().dimension().location(), VILLAGE).orElseThrow();
    }

    static void lines(String what, List<?> lines) {
        System.out.println("Townstead " + what + ":");
        for (Object line : lines) System.out.println("  " + (line instanceof Component c ? c.getString() : String.valueOf(line)));
    }

    // ---- first boot ----

    static void first(MinecraftServer server, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) throws Exception {
        MinecraftForge.EVENT_BUS.register(TownsteadScenario.class);
        var townstead = KingdomsTownsteadApi.get(server);
        String backend = expectedBackend();
        check(townstead.backend().equals(backend), "Townstead " + version("townstead") + " with MCA " + version("mca") + " selects the " + backend + " backend");
        var states = townstead.capabilityStates();
        check(states.get(TownsteadCapability.POLITICAL_DELEGATION).status() != TownsteadCapabilityState.Status.READY,
                "political delegation is never reported ready (Townstead offers no contract)");
        if (backend.equals("v1")) V1.api(server);
        else check(states.get(TownsteadCapability.READ_VILLAGE).status() != TownsteadCapabilityState.Status.READY, "api.v1-only capabilities are unavailable off api.v1");
        lines("status", TownsteadPresentation.status(server));
        check(server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kingdoms compat townstead status") > 0,
                "/kingdoms compat townstead status answers");
        // The example data pack is installed for this phase (integration_runtime.py): every bundled and example mapping applies.
        var mappings = TownsteadMappings.snapshot();
        check(TownsteadMappings.problems().isEmpty(), "every bundled and example Townstead mapping applies " + TownsteadMappings.problems());
        check(mappings.cultureHints().containsKey("example:coastal") && mappings.careers().stream().anyMatch(c -> c.id().toString().equals("example:archivist_scribes")),
                "the example data pack's career rule and culture hint are loaded");
        if (backend.equals("none")) {
            finish.accept("PASS integration townstead: Kingdoms alone, no optional class loaded, status explains absence");
            return;
        }
        ServerLevel level = server.overworld();
        level.getChunkAt(POS);
        level.setChunkForced(POS.getX() >> 4, POS.getZ() >> 4, true);
        village(level, VILLAGE, POS);
        var kingdoms = McaKingdomsApi.get(server);
        var registered = kingdoms.registerCandidate(level, SettlementCandidate.external(level.dimension(), POS, 16, SettlementBounds.around(POS, 16),
                new ResourceLocation("mcakingdoms_acceptance:townstead"), "townstead", new ResourceLocation("mcakingdoms:serenum"),
                Map.of("mca", level.dimension().location() + "#" + VILLAGE), "Townstead Acceptance"));
        check(settlement(server).id().equals(registered.id()), "the MCA village maps to its settlement by its exact dimension-qualified reference");
        var evidence = townstead.buildingEvidence(level, registered.id());
        check(evidence.size() == 4, "all four buildings are read (" + evidence.size() + ")");
        check(evidence.stream().noneMatch(e -> e.completion() == TownsteadBuildingEvidence.Completion.UNKNOWN),
                "every building's completion is known from MCA's own record on this tuple");
        Object library = building(level, VILLAGE, 0);
        int minimum = minimumBlocks(library);
        blocks(library, Math.max(minimum, 1));
        var complete = townstead.buildingEvidence(level, registered.id()).stream().filter(e -> e.building().buildingId() == 0).findFirst().orElseThrow();
        check(complete.verifiedComplete(), "a library holding its " + minimum + " required blocks is complete");
        lines("reconcile (baseline)", TownsteadPresentation.reconcile(server, registered.id()));
        if (minimum > 0) {
            blocks(library, 0);
            var incomplete = townstead.buildingEvidence(level, registered.id()).stream().filter(e -> e.building().buildingId() == 0).findFirst().orElseThrow();
            check(!incomplete.acceptsNewUse(), "a library that lost its required blocks is incomplete and refused for new use");
            CHANGES.clear();
            lines("reconcile (completion change)", TownsteadPresentation.reconcile(server, registered.id()));
            check(CHANGES.size() == 1 && CHANGES.get(0).previous().equals(CHANGES.get(0).current())
                            && !CHANGES.get(0).previousStates().equals(CHANGES.get(0).currentStates()),
                    "a completion change with the same type and size is posted, seen only by the detailed states");
            blocks(library, minimum);
        }
        dirty(level);
        // Where a resident is, from Townstead's schedule or hangouts, as an absent civic contact is explained (spec 8.3).
        if (states.get(TownsteadCapability.READ_SCHEDULE).status().usable() || states.get(TownsteadCapability.READ_HANGOUTS).status().usable()) {
            var resident = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:female_villager")).create(level);
            resident.moveTo(POS.getX() + 3.5, POS.getY(), POS.getZ() + 0.5);
            level.addFreshEntity(resident);
            var where = TownsteadPresentation.whereabouts(server, resident);
            System.out.println("Townstead whereabouts: " + where.map(Component::getString).orElse("none"));
            check(where.isPresent(), "Townstead's schedule tells where a resident is, for an absent civic contact");
            resident.discard();
        }
        lines("village", TownsteadPresentation.village(server, registered.id()));
        lines("buildings", TownsteadPresentation.buildings(server, registered.id()));
        lines("authority", TownsteadPresentation.authority(server, registered.id()));
        var authority = TownsteadAuthority.get(server).orElseThrow();
        check(authority.resolve(registered.id()).size() == TownsteadAuthority.Domain.values().length, "every domain has an answer");
        lines("preview adopt", authority.preview(registered.id(), TownsteadSavedData.Owner.KINGDOMS));
        System.out.println("Townstead adopt: " + authority.decide(registered.id(), TownsteadSavedData.Owner.KINGDOMS, null, "acceptance").getString());
        check(TownsteadSavedData.get(server).decision(registered.id()).map(d -> d.owner() == TownsteadSavedData.Owner.KINGDOMS).orElse(false),
                "the adoption is recorded durably");
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(PLAYER, "TownsteadAcceptance"));
        player.moveTo(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5);
        SettlementKnowledge.get(server).discover(PLAYER, registered.id());
        lines("government", TownsteadPresentation.government(player, registered.id()));
        if (!backend.equals("mca")) {
            lines("village life", TownsteadPresentation.villageLife(player, registered.id()));
            lines("village work", TownsteadPresentation.work(player, registered.id()));
            lines("village news", TownsteadPresentation.news(player, registered.id(), 1));
        }
        lines("queues", TownsteadPresentation.queues(server));
        if (!backend.equals("v1")) {
            finish.accept("PASS integration townstead: " + backend + " backend with completion, mapping, presentation and authority");
            return;
        }
        online(server, player);
        V1.liveSwitch(server, schedule, () -> {
            try {
                V1.chronicle(server, player, registered, schedule, finish);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    // ---- later boots ----

    static void later(MinecraftServer server, String phase, Consumer<String> finish) {
        var townstead = KingdomsTownsteadApi.get(server);
        check(townstead.backend().equals(expectedBackend()), phase + ": the " + expectedBackend() + " backend is selected again");
        if (expectedBackend().equals("none")) {
            finish.accept("PASS integration townstead " + phase + ": Kingdoms alone restarts with no optional class loaded");
            return;
        }
        var settlement = settlement(server);
        check(TownsteadSavedData.get(server).decision(settlement.id()).map(d -> d.owner() == TownsteadSavedData.Owner.KINGDOMS).orElse(false),
                phase + ": the adoption survived");
        var local = TownsteadAuthority.get(server).orElseThrow().resolve(settlement.id(), TownsteadAuthority.Domain.LOCAL_GOVERNMENT);
        check(local.owner() == TownsteadAuthority.Owner.MCA_KINGDOMS, phase + ": MCA: Kingdoms governs the adopted settlement (" + local.state() + ")");
        var buildings = townstead.buildingEvidence(server.overworld(), settlement.id());
        check(buildings.size() == 4 && buildings.stream().anyMatch(b -> b.building().buildingId() == 0 && b.verifiedComplete()),
                phase + ": the saved village's buildings are read again, the finished library still complete");
        if (townstead.backend().equals("v1")) V1.noReplay(server, phase);
        lines(phase + " authority", TownsteadPresentation.authority(server, settlement.id()));
        finish.accept("PASS integration townstead " + phase + ": records kept, nothing replayed or awarded");
    }

    static void absent(MinecraftServer server, Consumer<String> finish) {
        var townstead = KingdomsTownsteadApi.get(server);
        check(townstead.backend().equals(expectedBackend()), "Townstead removed: the " + expectedBackend() + " backend is selected");
        var settlement = settlement(server);
        check(TownsteadSavedData.get(server).decision(settlement.id()).isPresent(), "Townstead removed: the decision is kept");
        check(townstead.capabilityStates().get(TownsteadCapability.READ_NEEDS_SUMMARY).status() == TownsteadCapabilityState.Status.ABSENT,
                "Townstead removed: its capabilities read absent, not failed");
        ServerPlayer player = FakePlayerFactory.get(server.overworld(), new GameProfile(PLAYER, "TownsteadAcceptance"));
        var life = TownsteadPresentation.villageLife(player, settlement.id());
        lines("absent village life", life);
        check(life.stream().anyMatch(line -> line instanceof Component c && c.getString().contains("not installed")), "Townstead removed: the page says so in plain words");
        var buildings = townstead.buildingEvidence(server.overworld(), settlement.id());
        check(buildings.size() == 4, "Townstead removed: MCA's own buildings are still read (" + buildings.size() + ")");
        finish.accept("PASS integration townstead-absent: nothing destroyed, no default takeover, MCA evidence kept");
    }

    /** Everything that names Townstead's api.v1 types; loaded only when Townstead 0.8.x is installed. */
    static final class V1 {
        private V1() {
        }

        static void api(MinecraftServer server) {
            var api = com.aetherianartificer.townstead.api.v1.TownsteadApiV1.get();
            check(api.getApiVersion() == 1 && api.getApiRevision() == 2, "Townstead's packaged api.v1 reports generation 1, revision 2");
            var states = KingdomsTownsteadApi.get(server).capabilityStates();
            for (TownsteadCapability capability : List.of(TownsteadCapability.READ_VILLAGE, TownsteadCapability.READ_BUILDINGS,
                    TownsteadCapability.READ_BUILDING_COMPLETION, TownsteadCapability.READ_NEEDS_SUMMARY, TownsteadCapability.SUBSCRIBE_EVENTS,
                    TownsteadCapability.READ_SETTINGS, TownsteadCapability.READ_WORK_ORDERS, TownsteadCapability.EMIT_CHRONICLE)) {
                check(states.get(capability).status().usable(), capability.serializedName() + " is usable on the packaged jar (" + states.get(capability).reason() + ")");
            }
            System.out.println("Townstead reactions: " + states.get(TownsteadCapability.DISPATCH_REACTION));
            // The mapping check above is authoritative here, because api.v1 lists Townstead's cultures and professions.
            check(api.registries().cultureIds().contains(new ResourceLocation("example:harbor_folk")),
                    "Townstead loaded the example pack's culture, so the example culture hint names a real culture");
        }

        /** Switches Townstead's careers off and on again through its own command; the change arrives as an event. */
        static void liveSwitch(MinecraftServer server, BiConsumer<Long, Runnable> schedule, Runnable next) {
            var source = server.createCommandSourceStack();
            server.getCommands().performPrefixedCommand(source, "townstead switchboard set systems.careers false");
            schedule.accept(5L, () -> {
                check(KingdomsTownsteadApi.get(server).capabilityStates().get(TownsteadCapability.READ_CAREERS).status() == TownsteadCapabilityState.Status.DISABLED,
                        "careers switched off live read DISABLED, still bound");
                lines("queues after the switch", TownsteadPresentation.queues(server));
                server.getCommands().performPrefixedCommand(source, "townstead switchboard reset systems.careers");
                schedule.accept(5L, () -> {
                    check(KingdomsTownsteadApi.get(server).capabilityStates().get(TownsteadCapability.READ_CAREERS).status().usable(),
                            "careers switched back on read usable again without a restart");
                    next.run();
                });
            });
        }

        /** One committed public work, posted twice, projects one story; Townstead's counter moves once. */
        static void chronicle(MinecraftServer server, ServerPlayer player, SettlementView settlement, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) {
            var api = com.aetherianartificer.townstead.api.v1.TownsteadApiV1.get();
            String key = "mcakingdoms:public_work_completed";
            int before = api.chronicles().counter(server, PLAYER, key);
            // Two posts of the same committed work (two event objects: Forge never re-posts one) carry one project id.
            for (int i = 0; i < 2; i++) MinecraftForge.EVENT_BUS.post(new PublicWorkCompletedEvent(server, WORK, settlement.id(), settlement.kingdomId(),
                    new ResourceLocation("mcakingdoms:granary"), PLAYER, PLAYER, true));
            schedule.accept(5L, () -> {
                int after = api.chronicles().counter(server, PLAYER, key);
                lines("queues after projection", TownsteadPresentation.queues(server));
                check(after == before + 1, "a committed public work posted twice reaches Townstead once (counter " + before + " to " + after + ")");
                var digest = api.chronicles().digest(server, new com.aetherianartificer.townstead.api.v1.model.VillageId(server.overworld().dimension().location(), VILLAGE),
                        com.aetherianartificer.townstead.api.v1.model.Cursor.START, 8);
                System.out.println("Townstead digest: " + digest.map(d -> d.entries().items().stream().map(e -> e.headlineLangKey() + " / " + e.headline()).toList()).orElse(List.of()));
                check(digest.map(d -> d.entries().items().stream().anyMatch(e -> e.templateId().startsWith("mcakingdoms:"))).orElse(false),
                        "the bundled Chronicle template produced the story in the village digest");
                ServerPlayer viewer = player;
                var news = TownsteadPresentation.news(viewer, settlement.id(), 1);
                lines("village news", news);
                check(news.stream().anyMatch(line -> line instanceof Component c && c.getString().contains("granary") && !c.getString().contains("public_work_completed")),
                        "village news tells MCA: Kingdoms' own story in words, from its key and parameters");
                finish.accept("PASS integration townstead: v1 backend, events, live switches, completion, authority and one Chronicle story");
            });
        }

        static void noReplay(MinecraftServer server, String phase) {
            int count = com.aetherianartificer.townstead.api.v1.TownsteadApiV1.get().chronicles().counter(server, PLAYER, "mcakingdoms:public_work_completed");
            check(count == 1, phase + ": no Chronicle story was replayed (counter " + count + ")");
            check(TownsteadRuntime.session(server).isPresent(), phase + ": the integration is attached");
        }
    }
}
