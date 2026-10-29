package dev.otectus.mcakingdoms.item;

import dev.otectus.mcakingdoms.document.DocumentService;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A kingdom charter (B2): blank, or naming one sealed record of the document ledger. Its tooltip only repeats what the
 * item carries and never says "official"; using it asks the server's ledger, which answers Official, Official
 * (historical), Revoked or Unverified. The item grants nothing.
 */
public final class KingdomCharterItem extends Item {
    public KingdomCharterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) DocumentService.inspect(serverPlayer, stack).lines().forEach(serverPlayer::sendSystemMessage);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("Charter");
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        if (!stack.hasTag() || !stack.getTag().contains("Charter")) {
            lines.add(Component.translatableWithFallback("item.mcakingdoms.kingdom_charter.blank", "Blank. An official seals it at a seal press.").withStyle(ChatFormatting.GRAY));
            return;
        }
        CompoundTag charter = stack.getTag().getCompound("Charter");
        Component title;
        try {
            var parsed = Component.Serializer.fromJson(charter.getString("Title"));
            title = parsed == null ? Component.empty() : Component.literal(parsed.getString());
        } catch (RuntimeException malformed) { title = Component.empty(); }
        lines.add(title.copy().withStyle(ChatFormatting.GRAY));
        String id = charter.hasUUID("Document") ? charter.getUUID("Document").toString().substring(0, 4) : "?";
        lines.add(Component.translatableWithFallback("item.mcakingdoms.kingdom_charter.record", "Names sealed record #%s, day %s", id, charter.getLong("Day")).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatableWithFallback("item.mcakingdoms.kingdom_charter.verify", "Not verified yet: use it to ask the ledger.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
