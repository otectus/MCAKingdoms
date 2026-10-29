package dev.otectus.mcakingdoms.item;

import dev.otectus.mcakingdoms.api.McaKingdomsApi;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, McaKingdomsApi.MOD_ID);

    public static final RegistryObject<Item> VILLAGE_LEDGER = ITEMS.register("village_ledger",
            () -> new VillageLedgerItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> BOOK_OF_KINGDOMS = ITEMS.register("book_of_kingdoms",
            () -> new BookOfKingdomsItem(new Item.Properties().stacksTo(1)));
    // Physical heraldry (B1): the blocks are in heraldry/HeraldicBlocks; ids are stable.
    public static final RegistryObject<Item> KINGDOM_BANNER = ITEMS.register("kingdom_banner",
            () -> new HeraldicBlockItem(dev.otectus.mcakingdoms.heraldry.HeraldicBlocks.KINGDOM_BANNER.get(), new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> HERALDIC_SHIELD = ITEMS.register("heraldic_shield",
            () -> new HeraldicBlockItem(dev.otectus.mcakingdoms.heraldry.HeraldicBlocks.HERALDIC_SHIELD.get(), new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> COURT_NOTICEBOARD = ITEMS.register("court_noticeboard",
            () -> new HeraldicBlockItem(dev.otectus.mcakingdoms.heraldry.HeraldicBlocks.COURT_NOTICEBOARD.get(), new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> SEAL_PRESS = ITEMS.register("seal_press",
            () -> new BlockItem(dev.otectus.mcakingdoms.heraldry.HeraldicBlocks.SEAL_PRESS.get(), new Item.Properties()));
    public static final RegistryObject<Item> KINGDOM_CHARTER = ITEMS.register("kingdom_charter",
            () -> new KingdomCharterItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(ModItems::addCreativeTabContents);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(VILLAGE_LEDGER);
            event.accept(BOOK_OF_KINGDOMS);
            event.accept(KINGDOM_CHARTER);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SEAL_PRESS);
            event.accept(COURT_NOTICEBOARD);
        }
        if (event.getTabKey() == CreativeModeTabs.COLORED_BLOCKS) {
            event.accept(KINGDOM_BANNER);
            event.accept(HERALDIC_SHIELD);
        }
    }
}
