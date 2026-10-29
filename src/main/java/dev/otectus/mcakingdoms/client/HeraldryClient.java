package dev.otectus.mcakingdoms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.otectus.mcakingdoms.heraldry.HeraldicBlock;
import dev.otectus.mcakingdoms.heraldry.HeraldicBlockEntity;
import dev.otectus.mcakingdoms.heraldry.HeraldicBlocks;
import dev.otectus.mcakingdoms.item.HeraldicBlockItem;
import dev.otectus.mcakingdoms.item.ModItems;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.joml.Matrix4f;

/**
 * Client rendering of physical heraldry (B1): the banner's and shield's two tinctures from the colours the server sent,
 * and the kingdom's sigil drawn on the cloth or the shield. Only sigils under {@code textures/gui/heraldry/} are drawn,
 * so no object can name an arbitrary texture; an object without one shows plain cloth.
 */
@Mod.EventBusSubscriber(modid = "mcakingdoms", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HeraldryClient {
    private static final int CLOTH = 0xE8E2D0, TRIM = 0x3B2A1E;

    private HeraldryClient() {
    }

    @SubscribeEvent
    public static void blockColours(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> {
            if (level != null && pos != null && level.getBlockEntity(pos) instanceof HeraldicBlockEntity heraldic)
                return tint == 0 ? heraldic.primary() : tint == 1 ? heraldic.secondary() : 0xFFFFFF;
            return tint == 0 ? CLOTH : tint == 1 ? TRIM : 0xFFFFFF;
        }, HeraldicBlocks.KINGDOM_BANNER.get(), HeraldicBlocks.HERALDIC_SHIELD.get());
    }

    @SubscribeEvent
    public static void itemColours(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? HeraldicBlockItem.colour(stack, "Primary", CLOTH) : tint == 1 ? HeraldicBlockItem.colour(stack, "Secondary", TRIM) : 0xFFFFFF,
                ModItems.KINGDOM_BANNER.get(), ModItems.HERALDIC_SHIELD.get());
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(HeraldicBlocks.HERALDIC.get(), Sigil::new);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(ModItems.KINGDOM_CHARTER.get(), new ResourceLocation("mcakingdoms", "sealed"),
                (stack, level, entity, seed) -> stack.hasTag() && stack.getTag().contains("Charter") ? 1.0F : 0.0F));
    }

    /** Draws the sigil on both faces of a banner's cloth and on the face of a shield. */
    static final class Sigil implements BlockEntityRenderer<HeraldicBlockEntity> {
        Sigil(BlockEntityRendererProvider.Context context) {
        }

        @Override
        public void render(HeraldicBlockEntity heraldic, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            ResourceLocation icon = heraldic.icon();
            if (icon == null || !icon.getPath().matches("[a-z0-9_]{1,64}") || !(heraldic.getBlockState().getBlock() instanceof HeraldicBlock block)) return;
            if (block.kind() == HeraldicBlock.Kind.NOTICEBOARD) return;
            var texture = new ResourceLocation(icon.getNamespace(), "textures/gui/heraldry/" + icon.getPath() + ".png");
            var facing = heraldic.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
            pose.pushPose();
            pose.translate(0.5, 0.5, 0.5);
            // The models face north at y = 0; the blockstate turns them clockwise, so the sigil turns the same way.
            pose.mulPose(Axis.YP.rotationDegrees(-((facing.toYRot() + 180) % 360)));
            if (block.kind() == HeraldicBlock.Kind.BANNER) {
                quad(pose, consumer, -4 / 16F, -2.5F / 16, 4 / 16F, 5.5F / 16, -0.36F / 16 - 0.001F, true, light, overlay);
                quad(pose, consumer, -4 / 16F, -2.5F / 16, 4 / 16F, 5.5F / 16, 0.36F / 16 + 0.001F, false, light, overlay);
            } else {
                quad(pose, consumer, -5 / 16F, -5 / 16F, 5 / 16F, 5 / 16F, 5.7F / 16, true, light, overlay);
            }
            pose.popPose();
        }

        /** One square at depth {@code z} (negative is the north face), seen from the north when {@code north}. */
        private static void quad(PoseStack pose, VertexConsumer consumer, float x0, float y0, float x1, float y1, float z, boolean north, int light, int overlay) {
            Matrix4f matrix = pose.last().pose();
            var normal = pose.last().normal();
            float nz = north ? -1 : 1;
            if (north) {
                vertex(consumer, matrix, normal, x1, y0, z, 0, 1, nz, light, overlay);
                vertex(consumer, matrix, normal, x0, y0, z, 1, 1, nz, light, overlay);
                vertex(consumer, matrix, normal, x0, y1, z, 1, 0, nz, light, overlay);
                vertex(consumer, matrix, normal, x1, y1, z, 0, 0, nz, light, overlay);
            } else {
                vertex(consumer, matrix, normal, x0, y0, z, 0, 1, nz, light, overlay);
                vertex(consumer, matrix, normal, x1, y0, z, 1, 1, nz, light, overlay);
                vertex(consumer, matrix, normal, x1, y1, z, 1, 0, nz, light, overlay);
                vertex(consumer, matrix, normal, x0, y1, z, 0, 0, nz, light, overlay);
            }
        }

        private static void vertex(VertexConsumer consumer, Matrix4f matrix, org.joml.Matrix3f normal, float x, float y, float z, float u, float v, float nz, int light, int overlay) {
            consumer.vertex(matrix, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, nz).endVertex();
        }
    }
}
