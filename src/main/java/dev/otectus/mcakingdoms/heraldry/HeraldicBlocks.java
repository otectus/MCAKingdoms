package dev.otectus.mcakingdoms.heraldry;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The physical heraldry blocks and their block entity (B1). Ids are stable; items are registered in {@code ModItems}. */
public final class HeraldicBlocks {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, McaKingdomsApi.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, McaKingdomsApi.MOD_ID);

    public static final RegistryObject<Block> KINGDOM_BANNER = BLOCKS.register("kingdom_banner", () -> new HeraldicBlock(HeraldicBlock.Kind.BANNER,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> HERALDIC_SHIELD = BLOCKS.register("heraldic_shield", () -> new HeraldicBlock(HeraldicBlock.Kind.SHIELD,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> COURT_NOTICEBOARD = BLOCKS.register("court_noticeboard", () -> new HeraldicBlock(HeraldicBlock.Kind.NOTICEBOARD,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> SEAL_PRESS = BLOCKS.register("seal_press", () -> new SealPressBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<HeraldicBlockEntity>> HERALDIC = ENTITIES.register("heraldic",
            () -> BlockEntityType.Builder.of(HeraldicBlockEntity::new, KINGDOM_BANNER.get(), HERALDIC_SHIELD.get(), COURT_NOTICEBOARD.get()).build(null));

    private HeraldicBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ENTITIES.register(modBus);
    }
}
