package dev.otectus.mcakingdoms.clienttest;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import dev.otectus.mcakingdoms.api.SettlementView;
import dev.otectus.mcakingdoms.client.ClientPresentationState;
import dev.otectus.mcakingdoms.network.OverlayPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Opt-in full-pack check (village identity plan, 10.4): a copy of an Ultima world saved by 0.1.1, where one plains
 * village was two settlements, Dawnfield and its fragment Peacecross. After the update a new player, who spawns inside
 * the village, walks the whole of it west to east and must see one entry title, the surviving name, and never the
 * retired one.
 */
public final class SplitVillageClientHarness {
    private static final String WORLD = "split-village-world-copy";
    private static final String SURVIVOR = "Dawnfield";
    private static final String RETIRED = "Peacecross";
    private static final BlockPos SURVIVOR_ANCHOR = new BlockPos(-1977, 128, 1324);
    private static final BlockPos RETIRED_ANCHOR = new BlockPos(-2029, 110, 1324);
    private static final String STRUCTURE_KEY = "minecraft:overworld:minecraft:village_plains:-124:82";
    private static final int TICKS_PER_STEP = 30;
    private static final Field OVERLAY, OVERLAY_TICKS;

    static {
        try {
            OVERLAY = ClientPresentationState.class.getDeclaredField("currentOverlay");
            OVERLAY.setAccessible(true);
            OVERLAY_TICKS = ClientPresentationState.class.getDeclaredField("overlayTicks");
            OVERLAY_TICKS.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Path output = Path.of(System.getProperty("mcakingdoms.clientTest.output"));
    private final long started = System.currentTimeMillis();
    private final StringBuilder report = new StringBuilder();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final Set<String> shown = new LinkedHashSet<>();
    private volatile List<BlockPos> path;
    private volatile boolean done;
    private boolean opening, preparing;
    private int settled, lastOverlayTicks, step = -1, stepTicks, screenshotIn = -1;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (System.currentTimeMillis() - started > 900_000) throw new IllegalStateException("Split village run timed out at " + mc.screen);
            if (!opening && mc.screen instanceof TitleScreen) {
                opening = true;
                mc.options.pauseOnLostFocus = false;
                mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);
            }
            if (mc.player == null || mc.getSingleplayerServer() == null) return;
            observeOverlay(mc);
            if (!preparing && ++settled > 200) {
                preparing = true;
                prepare(mc);
            }
            if (path != null) walk(mc);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /** Every settlement the overlay carried, and each time an entry title started, with its name. */
    private void observeOverlay(Minecraft mc) throws IllegalAccessException {
        OverlayPacket packet = (OverlayPacket) OVERLAY.get(null);
        int ticks = OVERLAY_TICKS.getInt(null);
        packet.settlement().ifPresent(settlement -> shown.add(settlement.displayName()));
        if (ticks > lastOverlayTicks && packet.settlement().isPresent()) {
            titles.add(packet.settlement().get().displayName() + (packet.firstVisit() ? " (first visit)" : ""));
            if (screenshotIn < 0) screenshotIn = 15;
        }
        lastOverlayTicks = ticks;
        if (screenshotIn > 0 && --screenshotIn == 0) {
            Screenshot.grab(mc.gameDirectory, "split-village-title.png", mc.getMainRenderTarget(), message -> { });
        }
    }

    /** On the integrated server: the update merged the fragment; then a path across the whole village. */
    private void prepare(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        UUID player = mc.player.getUUID();
        server.execute(() -> {
            try {
                var kingdoms = McaKingdomsApi.get(server);
                var level = server.overworld();
                SettlementView survivor = kingdoms.findSettlement(SURVIVOR).orElseThrow(() -> new AssertionError(SURVIVOR + " is missing"));
                SettlementView retired = kingdoms.findSettlement(RETIRED).orElseThrow(() -> new AssertionError(RETIRED + " no longer resolves"));
                if (!retired.id().equals(survivor.id())) throw new AssertionError(RETIRED + " is still a settlement of its own: " + retired.id());
                for (BlockPos anchor : List.of(SURVIVOR_ANCHOR, RETIRED_ANCHOR)) {
                    UUID at = kingdoms.getSettlementAt(level, anchor).map(SettlementView::id).orElse(null);
                    if (!survivor.id().equals(at)) throw new AssertionError("The settlement at " + anchor.toShortString() + " is " + at);
                }
                var keys = kingdoms.getSettlementExternalRefs(survivor.id()).getOrDefault("minecraft:structure", Set.of());
                if (!keys.contains(STRUCTURE_KEY)) throw new AssertionError("The village lost its structure identity: " + keys);
                report.append("PASS ").append(RETIRED).append(" resolves to ").append(survivor.displayName()).append(' ')
                        .append(survivor.id()).append("; aliases ").append(survivor.aliases()).append("; bounds ")
                        .append(survivor.bounds()).append('\n');
                Objects.requireNonNull(server.getPlayerList().getPlayer(player)).setGameMode(GameType.SPECTATOR);
                List<BlockPos> route = new ArrayList<>();
                for (int x = survivor.bounds().minX() - 48; x <= survivor.bounds().maxX() + 48; x += 12) {
                    route.add(new BlockPos(x, 150, RETIRED_ANCHOR.getZ()));
                }
                path = List.copyOf(route);
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    /** One step every {@link #TICKS_PER_STEP} ticks; the server polls each player's settlement every 10 ticks. */
    private void walk(Minecraft mc) {
        if (++stepTicks < TICKS_PER_STEP) return;
        stepTicks = 0;
        step++;
        if (step < path.size()) {
            BlockPos target = path.get(step);
            var server = mc.getSingleplayerServer();
            UUID player = mc.player.getUUID();
            server.execute(() -> {
                var serverPlayer = server.getPlayerList().getPlayer(player);
                if (serverPlayer != null) serverPlayer.teleportTo(server.overworld(), target.getX() + 0.5, target.getY(), target.getZ() + 0.5, -90, 0);
            });
            return;
        }
        if (step < path.size() + 4) return; // let the last overlay update arrive
        report.append("Walked ").append(path.size()).append(" steps from x=").append(path.get(0).getX()).append(" to x=")
                .append(path.get(path.size() - 1).getX()).append(" along z=").append(RETIRED_ANCHOR.getZ()).append('\n');
        report.append("Entry titles: ").append(titles).append('\n');
        report.append("Settlements shown by the overlay: ").append(shown).append('\n');
        if (titles.size() != 1 || !titles.get(0).startsWith(SURVIVOR)) throw new AssertionError("Expected one " + SURVIVOR + " title: " + titles);
        if (!shown.equals(Set.of(SURVIVOR))) throw new AssertionError("The overlay named another settlement: " + shown);
        complete();
    }

    private void complete() {
        try {
            Files.writeString(output.resolve("SPLIT_VILLAGE_PASS.txt"), report);
            done = true;
            var mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.level != null) mc.level.disconnect();
                mc.clearLevel();
                mc.stop();
            });
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void fail(Throwable failure) {
        failure.printStackTrace();
        done = true;
        try {
            Files.deleteIfExists(output.resolve("SPLIT_VILLAGE_PASS.txt"));
            Files.writeString(output.resolve("SPLIT_VILLAGE_FAIL.txt"), failure + "\n" + report);
        } catch (Exception ignored) {
        }
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }
}
