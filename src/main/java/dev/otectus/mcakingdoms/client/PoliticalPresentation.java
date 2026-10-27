package dev.otectus.mcakingdoms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.otectus.mcakingdoms.interaction.InteractionNetwork;
import dev.otectus.mcakingdoms.network.OfficeBadgePacket;
import dev.otectus.mcakingdoms.network.PoliticalNoticePacket;
import dev.otectus.mcakingdoms.presentation.PresentationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

/**
 * The client half of A02: political notices the player opted into, and office badges for observed holders.
 *
 * <p>A notice is shown once per committed moment, as a chat line (the accessible text) and optionally a silent banner.
 * A badge is a separate, smaller line drawn above the entity's name tag; the entity's own name, custom name, equipment
 * and skin are never changed, and no badge is inferred from a name. Badges are dropped when their entity leaves the
 * client world and everything is forgotten on disconnect, so one server's roles never appear on another.
 */
public final class PoliticalPresentation {
    private static final Map<Integer, List<OfficeBadgePacket.Role>> BADGES = new HashMap<>();
    private static final Set<UUID> SEEN = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) { return size() > 128; }
    });

    private PoliticalPresentation() {
    }

    public static void notice(PoliticalNoticePacket packet) {
        var mode = PresentationConfig.POLITICAL_NOTICES.get();
        if (mode == PresentationConfig.Notices.OFF || !SEEN.add(packet.eventId())) return;
        var minecraft = Minecraft.getInstance();
        Component text = InteractionNetwork.component(packet.text());
        minecraft.gui.getChat().addMessage(Component.translatableWithFallback("notice.mcakingdoms.political.line", "[Kingdoms] %s", text));
        if (mode == PresentationConfig.Notices.BANNER) {
            Component title = packet.memorial() ? Component.translatableWithFallback("notice.mcakingdoms.political.memorial_title", "In memory")
                    : Component.translatableWithFallback("notice.mcakingdoms.political.title", "Kingdoms");
            SystemToast.addOrUpdate(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, title, text);
        }
    }

    public static void badge(OfficeBadgePacket packet) {
        if (packet.roles().isEmpty()) BADGES.remove(packet.entityId());
        else if (BADGES.size() < 4096 || BADGES.containsKey(packet.entityId())) BADGES.put(packet.entityId(), packet.roles());
    }

    /** The full roles of an observed entity, for tooltips and screens; empty when it holds none we were told of. */
    public static List<OfficeBadgePacket.Role> roles(int entityId) {
        return BADGES.getOrDefault(entityId, List.of());
    }

    /** One role in words; the same wording the server uses in pickers. */
    public static Component describe(OfficeBadgePacket.Role role) {
        return dev.otectus.mcakingdoms.politics.OfficePresentation.describe(role);
    }

    /** The compact badge: the first role with its mark, and how many more there are. */
    static Component compact(List<OfficeBadgePacket.Role> roles) {
        var first = roles.get(0);
        MutableComponent line = Component.literal(first.leader() ? "♛ " : "⚑ ").append(describe(first));
        if (roles.size() > 1) line.append(Component.translatableWithFallback("badge.mcakingdoms.more", " +%s", roles.size() - 1));
        return line;
    }

    @SubscribeEvent
    public static void renderBadge(RenderNameTagEvent event) {
        if (!PresentationConfig.OFFICE_BADGES.get()) return;
        var roles = BADGES.get(event.getEntity().getId());
        if (roles == null || roles.isEmpty()) return;
        var minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        double range = PresentationConfig.OFFICE_BADGE_DISTANCE.get();
        if (dispatcher.distanceToSqr(event.getEntity()) > range * range || minecraft.player == null || event.getEntity().isInvisibleTo(minecraft.player)) return;
        Component text = compact(roles);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // One line above where the name tag sits, a little smaller than a name.
        pose.translate(0.0F, event.getEntity().getNameTagOffsetY() + 0.3F, 0.0F);
        pose.mulPose(dispatcher.cameraOrientation());
        pose.scale(-0.02F, -0.02F, 0.02F);
        var matrix = pose.last().pose();
        Font font = minecraft.font;
        float x = -font.width(text) / 2.0F;
        int background = (int) (minecraft.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        boolean seeThrough = !event.getEntity().isDiscrete();
        font.drawInBatch(text, x, 0, 0x20FFFFFF, false, matrix, event.getMultiBufferSource(),
                seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, background, event.getPackedLight());
        if (seeThrough) font.drawInBatch(text, x, 0, 0xFFF3E3B0, false, matrix, event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0, event.getPackedLight());
        pose.popPose();
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) BADGES.remove(event.getEntity().getId());
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BADGES.clear();
        SEEN.clear();
    }

    /** A dimension change gives every entity a new id; badges arrive again as entities are tracked. */
    static void dimensionChanged() {
        BADGES.clear();
    }
}
