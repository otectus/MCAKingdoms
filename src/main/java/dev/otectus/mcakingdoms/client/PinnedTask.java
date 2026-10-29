package dev.otectus.mcakingdoms.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The objective pin (H10): one task the player pinned, shown as a short line at the top left of the HUD with what the
 * server last said about it (available, or why not), and a key (unbound until the player chooses one) that opens it.
 * It is local to this client, stores only the task id, and never runs or completes anything: opening it still goes
 * through Kingdom Tasks, where the server checks everything again. A task the server no longer lists is unpinned.
 */
public final class PinnedTask {
    private static final KeyMapping OPEN = new KeyMapping("key.mcakingdoms.pinned", InputConstants.UNKNOWN.getValue(), "key.categories.mcakingdoms");
    /** What the server last said about the pinned task: empty when available, otherwise the encoded reason; null when unknown. */
    private static volatile String reason;

    private PinnedTask() {
    }

    @Mod.EventBusSubscriber(modid = "mcakingdoms", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {
        }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    }

    /** Called with each authorized catalogue: records the pinned task's availability, or unpins it when it is no longer listed. */
    static void catalogue(java.util.Map<String, String> unavailable, boolean complete) {
        var pinned = ClientState.get().pinnedTask();
        if (pinned.isEmpty()) return;
        if (!unavailable.containsKey(pinned.get())) {
            if (complete) {
                ClientState.get().unpin();
                reason = null;
                Minecraft.getInstance().getNarrator().sayNow(Component.translatableWithFallback("interaction.mcakingdoms.pin.gone", "The pinned task is no longer offered here, so it was unpinned."));
            }
            return;
        }
        reason = unavailable.get(pinned.get());
    }

    static Component title(String task) {
        return Component.translatableWithFallback("task.mcakingdoms." + task + ".title", task);
    }

    /** Narrates and returns the pin state after toggling {@code task}. */
    static boolean toggle(String task) {
        boolean pinned = ClientState.get().togglePin(task);
        reason = null;
        Minecraft.getInstance().getNarrator().sayNow(pinned ? Component.translatableWithFallback("interaction.mcakingdoms.pin.pinned", "Pinned: %s", title(task))
                : Component.translatableWithFallback("interaction.mcakingdoms.pin.unpinned", "Unpinned: %s", title(task)));
        return pinned;
    }

    @Mod.EventBusSubscriber(modid = "mcakingdoms", value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (OPEN.consumeClick()) {
                var minecraft = Minecraft.getInstance();
                var pinned = ClientState.get().pinnedTask();
                if (minecraft.player != null && minecraft.screen == null && pinned.isPresent()) InteractionClient.open(null, pinned.get(), "");
            }
        }

        @SubscribeEvent
        public static void render(RenderGuiEvent.Post event) {
            var minecraft = Minecraft.getInstance();
            var pinned = ClientState.get().pinnedTask();
            if (pinned.isEmpty() || minecraft.options.hideGui || minecraft.screen != null || minecraft.player == null || minecraft.options.renderDebug) return;
            var known = reason;
            Component line = known == null || known.isEmpty()
                    ? Component.translatableWithFallback("interaction.mcakingdoms.pin.line", "Pinned: %s", title(pinned.get()))
                    : Component.translatableWithFallback("interaction.mcakingdoms.pin.line.unavailable", "Pinned: %s (not available here: %s)", title(pinned.get()), InteractionNetwork.component(known));
            var font = minecraft.font;
            int width = Math.min(font.width(line), event.getWindow().getGuiScaledWidth() / 2);
            var lines = font.split(line, Math.max(40, width));
            var g = event.getGuiGraphics();
            g.fill(3, 3, 9 + width, 7 + lines.size() * (font.lineHeight + 1), 0x88000000);
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), 6, 6 + i * (font.lineHeight + 1), known == null || known.isEmpty() ? 0xFFF0E6C8 : 0xFFE0B080, true);
        }
    }
}
