package dev.otectus.mcakingdoms.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** A banner, shield or noticeboard item; when it was placed before, it names the kingdom it stands for (B3). */
public final class HeraldicBlockItem extends BlockItem {
    public HeraldicBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** The kingdom an earlier placement gave this item, or empty. */
    public static String kingdom(ItemStack stack) {
        CompoundTag tag = stack.getTagElement("BlockEntityTag");
        String kingdom = tag == null ? "" : tag.getCompound("Heraldry").getString("Kingdom");
        return ResourceLocation.tryParse(kingdom) == null ? "" : kingdom;
    }

    /** A colour a placed object handed to its item (server-written), or {@code fallback}. */
    public static int colour(ItemStack stack, String key, int fallback) {
        CompoundTag tag = stack.getTagElement("BlockEntityTag");
        return tag != null && tag.getCompound("Heraldry").contains(key) ? tag.getCompound("Heraldry").getInt(key) & 0xFFFFFF : fallback;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        String kingdom = kingdom(stack);
        var id = ResourceLocation.tryParse(kingdom);
        if (id == null || kingdom.isEmpty()) {
            lines.add(Component.translatableWithFallback("item.mcakingdoms.heraldic.unbound", "Stands for the kingdom where it is placed.").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.translatableWithFallback("item.mcakingdoms.heraldic.bound", "Of %s", Component.translatable("kingdom." + id.getNamespace() + "." + id.getPath())).withStyle(ChatFormatting.GRAY));
    }
}
