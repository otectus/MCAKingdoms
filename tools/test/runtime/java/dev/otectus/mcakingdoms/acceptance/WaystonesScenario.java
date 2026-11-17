package dev.otectus.mcakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementCandidate;
import dev.otectus.mcakingdoms.compat.waystones.WaystonesIntegration;
import dev.otectus.mcakingdoms.compat.waystones.WaystonesNamingData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Packaged Waystones acceptance (WAY-01) on the exact tested jars, Waystones 14.1.21 and Balm 7.3.44, read only by
 * reflection like the integration. Phase {@code waystones}: a generated stone's first activation takes the settlement's
 * name; a newly placed stone in the same settlement takes the numbered name before its settings screen opens; a stone
 * outside every settlement and a sharestone keep Waystones' own behaviour; a player's accepted edit detaches the stone
 * and a backfill leaves it alone; a stone placed where a settlement is about to be recognized is named once it is. Phase
 * {@code waystones-restart}: names, numbers and the player's own name survive, and every assignment is confirmed.
 * Phase {@code waystones-unsupported}: the tested jar reporting another version is refused, and stones keep Waystones'
 * own names. Phase {@code waystones-untested-build}: a 14.1.21 jar whose bytes differ names stones, but its edits are not
 * observed, so a player's rename is kept by the never-rename-a-named-stone rule and settled at the next save.
 */
final class WaystonesScenario {
    static final BlockPos OAKFIELD = new BlockPos(7000, 0, 7000);
    static final BlockPos WILDS = new BlockPos(7600, 0, 7000);
    static final BlockPos LATE = new BlockPos(7000, 0, 7600);
    static final UUID ALEX = UUID.fromString("5a0e3e9e-31f1-4b8c-9c51-0d2b7e2c9a01");
    static final UUID BEA = UUID.fromString("5a0e3e9e-31f1-4b8c-9c51-0d2b7e2c9a02");
    private static final List<String> PASSED = new ArrayList<>();

    private WaystonesScenario() {
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        PASSED.add(message);
        System.out.println("PASS integration: " + message);
    }

    // ---- reflection helpers ----

    static Class<?> cls(String name) throws ClassNotFoundException { return Class.forName(name); }

    static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            boolean fits = true;
            for (int i = 0; i < args.length && fits; i++) fits = args[i] == null || box(m.getParameterTypes()[i]).isInstance(args[i]);
            if (fits) return m.invoke(target instanceof Class<?> ? null : target, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + args.length);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        return type == boolean.class ? Boolean.class : type == int.class ? Integer.class : type == long.class ? Long.class : Object.class;
    }

    static String name(Object stone) throws Exception { Object n = call(stone, "getName"); return n == null ? "" : (String) n; }
    static UUID id(Object stone) throws Exception { return (UUID) call(stone, "getWaystoneUid"); }

    static BlockPos ground(ServerLevel level, BlockPos at) {
        level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        return new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState withValue(BlockState state, Class<?> owner, String field, Object value) throws Exception {
        Property property = (Property) owner.getField(field).get(null);
        return state.setValue(property, (Comparable) value);
    }

    /** A waystone block at {@code pos} with the given origin, its record initialized as worldgen or placement leaves it. */
    static Object generated(ServerLevel level, BlockPos pos) throws Exception {
        var block = BuiltInRegistries.BLOCK.get(new ResourceLocation("waystones", "waystone"));
        Class<?> base = cls("net.blay09.mods.waystones.block.WaystoneBlockBase");
        Class<?> origin = cls("net.blay09.mods.waystones.api.WaystoneOrigin");
        BlockState lower = withValue(withValue(block.defaultBlockState(), base, "HALF", DoubleBlockHalf.LOWER), base, "ORIGIN", enumValue(origin, "VILLAGE"));
        level.setBlock(pos, lower, 3);
        level.setBlock(pos.above(), withValue(lower, base, "HALF", DoubleBlockHalf.UPPER), 3);
        Object entity = level.getBlockEntity(pos);
        call(entity, "initializeWaystone", level, null, enumValue(origin, "VILLAGE"));
        return call(entity, "getWaystone");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumValue(Class<?> type, String name) { return Enum.valueOf((Class) type, name); }

    /** A player places a waystone item, as Waystones' own block does: initialize, activate, then the settings screen. */
    static Object placed(ServerLevel level, BlockPos pos, FakePlayer player) throws Exception {
        return placedWith(level, pos, player, new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("waystones", "waystone"))));
    }

    /** As {@link #placed}, from a given item: one carrying a stone's {@code UUID} places that stone again (silk touch). */
    static Object placedWith(ServerLevel level, BlockPos pos, FakePlayer player, ItemStack item) throws Exception {
        var block = BuiltInRegistries.BLOCK.get(new ResourceLocation("waystones", "waystone"));
        Class<?> base = cls("net.blay09.mods.waystones.block.WaystoneBlockBase");
        Class<?> origin = cls("net.blay09.mods.waystones.api.WaystoneOrigin");
        BlockState lower = withValue(withValue(block.defaultBlockState(), base, "HALF", DoubleBlockHalf.LOWER), base, "ORIGIN", enumValue(origin, "PLAYER"));
        level.setBlock(pos, lower, 3);
        player.moveTo(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
        block.setPlacedBy(level, pos, lower, player, item);
        return call(level.getBlockEntity(pos), "getWaystone");
    }

    static Object stone(MinecraftServer server, UUID id) throws Exception {
        return ((Optional<?>) call(cls("net.blay09.mods.waystones.api.WaystonesAPI"), "getWaystone", server, id)).orElseThrow();
    }

    static FakePlayer player(ServerLevel level, UUID id, String name) {
        return FakePlayerFactory.get(level, new GameProfile(id, name));
    }

    static void run(MinecraftServer server, String phase, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) {
        schedule.accept(5L, () -> {
            try {
                if (phase.equals("waystones-unsupported")) { unsupported(server, finish); return; }
                check(WaystonesIntegration.status().ready(), "the Waystones integration is ready on Waystones " + WaystonesIntegration.status().waystones()
                        + " with Balm " + WaystonesIntegration.status().balm() + ": " + WaystonesIntegration.status().reason());
                if (phase.equals("waystones")) first(server, schedule, finish);
                else if (phase.equals("waystones-untested-build")) untestedBuild(server, finish);
                else restart(server, finish);
            } catch (Throwable failure) {
                failure.printStackTrace();
                finish.accept("FAIL integration " + phase + ": " + failure);
            }
        });
    }

    static void first(MinecraftServer server, BiConsumer<Long, Runnable> schedule, Consumer<String> finish) throws Exception {
        ServerLevel level = server.overworld();
        var api = McaKingdomsApi.get(server);
        BlockPos center = ground(level, OAKFIELD);
        var oakfield = api.registerCandidate(level, SettlementCandidate.manual(level.dimension(), center, 48, new ResourceLocation("mcakingdoms_acceptance:waystones"),
                "waystones-oakfield", "Oakfield"));
        check(oakfield.displayName().equals("Oakfield"), "the fixture settlement is called Oakfield");
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ground(level, center.offset(dx * 16, 0, dz * 16));
        var alex = player(level, ALEX, "WaystoneAlex");
        var bea = player(level, BEA, "WaystoneBea");
        // 1. A generated stone's first activation: Waystones' name event, after its own duplicate handling.
        Object generated = generated(level, ground(level, center.offset(4, 0, 4)));
        check(name(generated).isEmpty(), "a generated stone starts without a name");
        call(cls("net.blay09.mods.waystones.core.PlayerWaystoneManager"), "activateWaystone", alex, generated);
        check(name(stone(server, id(generated))).equals("Oakfield"), "a generated stone's first activation took the settlement's name: " + name(stone(server, id(generated))));
        var activated = (Collection<?>) call(cls("net.blay09.mods.waystones.api.WaystonesAPI"), "getActivatedWaystones", alex);
        boolean listed = false;
        for (Object s : activated) if (id(s).equals(id(generated)) && name(s).equals("Oakfield")) listed = true;
        check(listed, "the activating player's destination list carries the settlement name");
        // 2. A newly placed stone in the same settlement: named before its settings screen opens, numbered against the first.
        Object placed = placed(level, ground(level, center.offset(-6, 0, 2)), bea);
        check(name(stone(server, id(placed))).equals("Oakfield (2)"), "a placed stone took the numbered settlement name: " + name(stone(server, id(placed))));
        check(WaystonesNamingData.get(server).entry(id(placed)).map(e -> e.number() == 2).orElse(false), "the number given is recorded");
        // 3. Outside every settlement, and a sharestone inside one: Waystones' own behaviour.
        Object wild = placed(level, ground(level, WILDS), bea);
        check(name(stone(server, id(wild))).isEmpty(), "a placed stone outside every settlement keeps Waystones' own (empty) name");
        BlockPos sharePos = ground(level, center.offset(8, 0, -8));
        Object share = ((Optional<?>) call(cls("net.blay09.mods.waystones.api.WaystonesAPI"), "placeSharestone", level, sharePos, DyeColor.RED)).orElseThrow();
        String shareName = name(share);
        check(!shareName.startsWith("Oakfield") && WaystonesNamingData.get(server).entry(id(share)).isEmpty(), "a sharestone keeps its own name: '" + shareName + "'");
        // 4. A player's accepted edit, through Waystones' own handler: the stone is theirs from now on.
        Class<?> edit = cls("net.blay09.mods.waystones.network.message.EditWaystoneMessage");
        Object message = edit.getConstructor(UUID.class, String.class, boolean.class).newInstance(id(placed), "Bea's Rest", false);
        bea.moveTo(((BlockPos) call(placed, "getPos")).getX() + 1.5, ((BlockPos) call(placed, "getPos")).getY(), ((BlockPos) call(placed, "getPos")).getZ() + 0.5);
        call(edit, "handle", bea, message);
        check(name(stone(server, id(placed))).equals("Bea's Rest"), "Waystones accepted the player's own name");
        check(System.getProperty("mcakingdoms.waystones.gate", "").equals("applied"), "the edit observer is applied to the tested jar: " + System.getProperty("mcakingdoms.waystones.gate"));
        check(WaystonesNamingData.get(server).entry(id(placed)).map(e -> e.stage() == WaystonesNamingData.Stage.DETACHED).orElse(false), "the player's edit detached the stone");
        var counts = WaystonesIntegration.backfill(server, true, 64);
        check(name(stone(server, id(placed))).equals("Bea's Rest") && name(stone(server, id(generated))).equals("Oakfield"), "a backfill left every named stone as it was: " + counts);
        // 4b. Submitting the name a stone already has is still the player's choice.
        Object third = placed(level, ground(level, center.offset(10, 0, 6)), alex);
        String thirdName = name(stone(server, id(third)));
        check(thirdName.equals("Oakfield (2)"), "a number given up by a player's rename is used again: " + thirdName);
        BlockPos thirdAt = (BlockPos) call(third, "getPos");
        alex.moveTo(thirdAt.getX() + 1.5, thirdAt.getY(), thirdAt.getZ() + 0.5);
        call(edit, "handle", alex, edit.getConstructor(UUID.class, String.class, boolean.class).newInstance(id(third), thirdName, false));
        check(name(stone(server, id(third))).equals(thirdName) && WaystonesNamingData.get(server).entry(id(third)).map(e -> e.stage() == WaystonesNamingData.Stage.DETACHED).orElse(false),
                "submitting the same name in Waystones' screen still makes the stone the player's");
        // 4c. A stone broken for good gives its name back once a save sees it gone.
        Object moved = placed(level, ground(level, center.offset(-10, 0, -6)), bea);
        String movedName = name(stone(server, id(moved)));
        check(movedName.equals("Oakfield (3)"), "the next stone takes the next number: " + movedName);
        BlockPos movedAt = (BlockPos) call(moved, "getPos");
        level.removeBlock(movedAt.above(), false);
        level.removeBlock(movedAt, false);
        check(((Optional<?>) call(cls("net.blay09.mods.waystones.api.WaystonesAPI"), "getWaystone", server, id(moved))).isEmpty(), "a broken stone is gone from Waystones");
        server.saveEverything(true, false, true);
        check(WaystonesNamingData.get(server).entry(id(moved)).map(e -> e.stage() == WaystonesNamingData.Stage.EXPIRED).orElse(false), "the save settled the broken stone's record");
        Object successor = placed(level, ground(level, center.offset(-12, 0, 10)), alex);
        check(name(stone(server, id(successor))).equals("Oakfield (3)"), "the broken stone's name is free again: " + name(stone(server, id(successor))));
        // 4d. Renaming the settlement leaves established names alone (one-time naming); a new stone takes the new name.
        McaKingdomsApi.get(server).rename(oakfield.id(), "Oakmere");
        check(name(stone(server, id(generated))).equals("Oakfield") && name(stone(server, id(successor))).equals("Oakfield (3)"), "a settlement rename renamed no established stone");
        Object afterRename = placed(level, ground(level, center.offset(14, 0, 12)), alex);
        check(name(stone(server, id(afterRename))).equals("Oakmere"), "a stone placed after the rename takes the new name: " + name(stone(server, id(afterRename))));
        // 5. A stone placed where a settlement is about to be recognized: it waits, then takes the name once.
        BlockPos late = ground(level, LATE);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ground(level, late.offset(dx * 16, 0, dz * 16));
        for (int i = 0; i < 5; i++) {
            BlockPos foot = ground(level, late.offset(-8 + i * 4, 0, 6));
            BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, net.minecraft.core.Direction.SOUTH);
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(foot.south(), bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
        }
        level.setBlock(ground(level, late.offset(0, 0, -6)), Blocks.BELL.defaultBlockState(), 3);
        check(api.getSettlementAt(level, late).isEmpty(), "the late village is not recognized yet when its waystone is placed");
        Object waiting = placed(level, late, alex);
        check(name(stone(server, id(waiting))).isEmpty() && WaystonesNamingData.get(server).entry(id(waiting)).map(e -> e.stage() == WaystonesNamingData.Stage.PENDING).orElse(false),
                "a stone placed before its village is recognized waits");
        // A second waiting stone the player names before the village is recognized is theirs.
        Object named = placed(level, ground(level, late.offset(6, 0, 2)), alex);
        BlockPos namedAt = (BlockPos) call(named, "getPos");
        alex.moveTo(namedAt.getX() + 1.5, namedAt.getY(), namedAt.getZ() + 0.5);
        call(edit, "handle", alex, edit.getConstructor(UUID.class, String.class, boolean.class).newInstance(id(named), "Late Rest", false));
        check(name(stone(server, id(named))).equals("Late Rest") && WaystonesNamingData.get(server).entry(id(named)).map(e -> e.stage() == WaystonesNamingData.Stage.DETACHED).orElse(false),
                "a waiting stone named by its player before the village is recognized is the player's");
        schedule.accept(400L, () -> {
            try {
                var village = api.getSettlementAt(level, late);
                check(village.isPresent(), "the late village was recognized without reloading its chunks");
                String expected = village.get().displayName();
                check(name(stone(server, id(waiting))).equals(expected), "the waiting stone took the late village's name once: " + name(stone(server, id(waiting))) + " / " + expected);
                check(name(stone(server, id(named))).equals("Late Rest"), "the stone its player named meanwhile kept that name: " + name(stone(server, id(named))));
                Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("waystones-fixture.txt"),
                        id(generated) + "\n" + id(placed) + "\n" + id(wild) + "\n" + id(share) + "\n" + id(waiting) + "\n" + expected + "\n" + shareName + "\n");
                finish.accept("PASS integration waystones: " + PASSED.size() + " checks; generated, placed, outside, sharestone, player edit, backfill and late discovery");
            } catch (Throwable failure) {
                failure.printStackTrace();
                finish.accept("FAIL integration waystones: " + failure);
            }
        });
    }

    /** The fixture settlement Oakfield with its neighbouring chunks loaded. */
    static BlockPos oakfield(MinecraftServer server, String note) {
        ServerLevel level = server.overworld();
        BlockPos center = ground(level, OAKFIELD);
        var oakfield = McaKingdomsApi.get(server).registerCandidate(level, SettlementCandidate.manual(level.dimension(), center, 48,
                new ResourceLocation("mcakingdoms_acceptance:waystones"), "waystones-oakfield", "Oakfield"));
        check(oakfield.displayName().equals("Oakfield"), note);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ground(level, center.offset(dx * 16, 0, dz * 16));
        return center;
    }

    static void unsupported(MinecraftServer server, Consumer<String> finish) throws Exception {
        var status = WaystonesIntegration.status();
        check(status.readiness() == WaystonesIntegration.Readiness.UNSUPPORTED && status.waystones().equals("14.1.99"),
                "Waystones reporting another version is unsupported: " + status.readiness() + " " + status.waystones() + " (" + status.reason() + ")");
        check(System.getProperty("mcakingdoms.waystones.gate", "").equals("skipped:version:14.1.99"),
                "the edit observer is not applied to another version: " + System.getProperty("mcakingdoms.waystones.gate"));
        ServerLevel level = server.overworld();
        BlockPos center = oakfield(server, "settlements are still recognized beside an unsupported Waystones");
        var alex = player(level, ALEX, "WaystoneAlex");
        var bea = player(level, BEA, "WaystoneBea");
        Object generated = generated(level, ground(level, center.offset(4, 0, 4)));
        call(cls("net.blay09.mods.waystones.core.PlayerWaystoneManager"), "activateWaystone", alex, generated);
        String generatedName = name(stone(server, id(generated)));
        check(!generatedName.startsWith("Oakfield"), "a generated stone keeps the name Waystones gives it: '" + generatedName + "'");
        Object placed = placed(level, ground(level, center.offset(-6, 0, 2)), bea);
        check(name(stone(server, id(placed))).isEmpty(), "a placed stone is left unnamed for its own settings screen");
        var data = WaystonesNamingData.get(server);
        check(data.entry(id(generated)).isEmpty() && data.entry(id(placed)).isEmpty(), "nothing is recorded while the integration is unsupported");
        var counts = WaystonesIntegration.backfill(server, true, 64);
        check(counts.containsKey(dev.otectus.mcakingdoms.compat.waystones.WaystoneNames.Reason.PROVIDER_UNSUPPORTED) && name(stone(server, id(placed))).isEmpty(),
                "a backfill refuses with the provider unsupported and changes nothing: " + counts);
        finish.accept("PASS integration waystones-unsupported: " + PASSED.size() + " checks; refused version, native names, nothing recorded");
    }

    static void untestedBuild(MinecraftServer server, Consumer<String> finish) throws Exception {
        check(System.getProperty("mcakingdoms.waystones.gate", "").startsWith("skipped:untested_build:"),
                "the edit observer is not applied to a 14.1.21 jar whose bytes differ: " + System.getProperty("mcakingdoms.waystones.gate"));
        check(!WaystonesIntegration.editsObserved(), "player edits are reported as not observed");
        ServerLevel level = server.overworld();
        BlockPos center = oakfield(server, "the fixture settlement is called Oakfield");
        var alex = player(level, ALEX, "WaystoneAlex");
        var bea = player(level, BEA, "WaystoneBea");
        Object generated = generated(level, ground(level, center.offset(4, 0, 4)));
        call(cls("net.blay09.mods.waystones.core.PlayerWaystoneManager"), "activateWaystone", alex, generated);
        check(name(stone(server, id(generated))).equals("Oakfield"), "a generated stone still takes the settlement's name: " + name(stone(server, id(generated))));
        Object placed = placed(level, ground(level, center.offset(-6, 0, 2)), bea);
        check(name(stone(server, id(placed))).equals("Oakfield (2)"), "a placed stone still takes the numbered name: " + name(stone(server, id(placed))));
        Class<?> edit = cls("net.blay09.mods.waystones.network.message.EditWaystoneMessage");
        Object message = edit.getConstructor(UUID.class, String.class, boolean.class).newInstance(id(placed), "Bea's Rest", false);
        BlockPos at = (BlockPos) call(placed, "getPos");
        bea.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + 0.5);
        call(edit, "handle", bea, message);
        check(name(stone(server, id(placed))).equals("Bea's Rest"), "Waystones accepted the player's own name");
        var data = WaystonesNamingData.get(server);
        check(data.entry(id(placed)).map(e -> e.stage() == WaystonesNamingData.Stage.ASSIGNED).orElse(false), "the unobserved edit left the record as it was");
        var counts = WaystonesIntegration.backfill(server, true, 64);
        check(name(stone(server, id(placed))).equals("Bea's Rest"), "a backfill leaves the player's name: " + counts);
        server.saveEverything(true, false, true);
        check(data.entry(id(placed)).map(e -> e.stage() == WaystonesNamingData.Stage.DETACHED).orElse(false),
                "the next save found another name and left that stone to its player for good");
        check(data.entry(id(generated)).map(e -> e.stage() == WaystonesNamingData.Stage.MANAGED).orElse(false), "the next save confirmed the generated stone's name");
        check(name(stone(server, id(placed))).equals("Bea's Rest"), "the player's name is still in place after the save");
        finish.accept("PASS integration waystones-untested-build: " + PASSED.size() + " checks; naming on, edits unobserved, player's name kept and settled at the save");
    }

    static void restart(MinecraftServer server, Consumer<String> finish) throws Exception {
        var lines = Files.readAllLines(server.getWorldPath(LevelResource.ROOT).resolve("waystones-fixture.txt"));
        UUID generated = UUID.fromString(lines.get(0)), placed = UUID.fromString(lines.get(1)), wild = UUID.fromString(lines.get(2)),
                share = UUID.fromString(lines.get(3)), waiting = UUID.fromString(lines.get(4));
        check(name(stone(server, generated)).equals("Oakfield"), "the generated stone kept its settlement name across a restart");
        check(name(stone(server, placed)).equals("Bea's Rest"), "the player's own name survived a restart");
        check(name(stone(server, wild)).isEmpty(), "the stone outside every settlement is still unnamed by this integration");
        // Waystones' registry lookup does not return a sharestone after a restart; what this integration owns is its record.
        check(WaystonesNamingData.get(server).entry(share).isEmpty(), "the sharestone still has no naming record after a restart");
        check(name(stone(server, waiting)).equals(lines.get(5)), "the late stone kept the village name across a restart");
        var data = WaystonesNamingData.get(server);
        check(data.entry(generated).map(e -> e.stage() == WaystonesNamingData.Stage.MANAGED && e.number() == 1).orElse(false), "the generated stone's name is confirmed, with its number");
        check(data.entry(placed).map(e -> e.stage() == WaystonesNamingData.Stage.DETACHED).orElse(false), "the player's stone stays detached after a restart");
        check(data.count(WaystonesNamingData.Stage.ASSIGNED) == 0, "no assignment is left unconfirmed after a clean restart");
        CompoundTag saved = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(WaystonesNamingData.NAME + ".dat").toFile());
        check(saved.getCompound("data").getInt("Schema") == 1, "the naming records are on disk with their schema");
        finish.accept("PASS integration waystones-restart: " + PASSED.size() + " checks; names, numbers and the player's own name survived");
    }
}
