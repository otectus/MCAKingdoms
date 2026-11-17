package dev.otectus.mcakingdoms.compat.townstead;

import dev.otectus.mcakingdoms.api.CivicIdentityView;
import dev.otectus.mcakingdoms.api.KingdomsService;
import dev.otectus.mcakingdoms.api.SettlementView;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The opt-in heraldry fallback (spec 11.1; {@code heraldryFallback}, off by default). When an MCA villager living in a
 * MCA: Kingdoms settlement equips a plain shield and no Townstead arms are proclaimed for that settlement's faction, the
 * shield takes the kingdom's mapped design ({@code mcakingdoms_townstead/livery}). Precedence: a decorated or named
 * shield is never touched (personal and local arms win); a settlement whose Townstead faction proclaims arms is left to
 * Townstead's own guard heraldry; with no mapped design nothing is invented. A painted shield is never repainted, so local
 * flags stay recognizable after a change of control. Painting grants no membership, enlistment or authority.
 */
public final class TownsteadHeraldry {
    private static final Map<MinecraftServer, TownsteadHeraldry> LISTENERS = new WeakHashMap<>();
    static final String MARK = "mcakingdoms_livery";
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private long painted;

    private TownsteadHeraldry(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    static TownsteadHeraldry listener(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        TownsteadHeraldry listener = new TownsteadHeraldry(server, kingdoms, townstead);
        synchronized (LISTENERS) {
            LISTENERS.put(server, listener);
        }
        return listener;
    }

    static void unregister(MinecraftServer server) {
        TownsteadHeraldry listener;
        synchronized (LISTENERS) {
            listener = LISTENERS.remove(server);
        }
        if (listener != null) MinecraftForge.EVENT_BUS.unregister(listener);
    }

    static long painted(MinecraftServer server) {
        synchronized (LISTENERS) {
            TownsteadHeraldry listener = LISTENERS.get(server);
            return listener == null ? 0 : listener.painted;
        }
    }

    @SubscribeEvent
    public void equipped(LivingEquipmentChangeEvent event) {
        if (!TownsteadIntegrationConfig.HERALDRY_FALLBACK.get()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide() || entity.getServer() != server) return;
        if (event.getSlot() != EquipmentSlot.OFFHAND && event.getSlot() != EquipmentSlot.MAINHAND) return;
        ItemStack stack = event.getTo();
        if (!plain(stack)) return;
        var type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (type == null || !type.getNamespace().equals("mca")) return;
        Optional<SettlementView> home = kingdoms.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement).flatMap(kingdoms::getSettlement);
        if (home.isEmpty()) return;
        var design = TownsteadMappings.livery(home.get().kingdomId());
        if (design.isEmpty()) return;
        // Townstead's faction arms come first: its own guard heraldry paints plain shields where arms are proclaimed.
        if (townstead.states().usable(dev.otectus.mcakingdoms.api.townstead.TownsteadCapability.READ_POLITICS)) {
            var dimension = home.get().dimension().location();
            for (int villageId : townstead.villageIds(home.get(), dimension)) {
                var faction = townstead.backendImpl().faction(server, dimension, villageId);
                if (faction.isPresent() && faction.get().emblem().isPresent()) return;
            }
        }
        paint(stack, design.get());
        painted++;
    }

    /** A shield with no pattern data, no base colour and no custom name: standard issue, nobody's personal arms. */
    static boolean plain(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.SHIELD) || stack.hasCustomHoverName()) return false;
        CompoundTag data = BlockItem.getBlockEntityData(stack);
        return data == null || (!data.contains("Patterns") && !data.contains("Base"));
    }

    static void paint(ItemStack stack, TownsteadMappings.Livery design) {
        BannerPattern.Builder builder = new BannerPattern.Builder();
        for (TownsteadMappings.Layer layer : design.layers()) {
            builder.addPattern(ResourceKey.create(Registries.BANNER_PATTERN, layer.pattern()), net.minecraft.world.item.DyeColor.byName(layer.color(), net.minecraft.world.item.DyeColor.WHITE));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Patterns", builder.toListTag());
        tag.putInt("Base", net.minecraft.world.item.DyeColor.byName(design.base(), net.minecraft.world.item.DyeColor.WHITE).getId());
        BlockItem.setBlockEntityData(stack, BlockEntityType.BANNER, tag);
        stack.getOrCreateTag().putString(MARK, design.kingdom().toString());
    }
}
