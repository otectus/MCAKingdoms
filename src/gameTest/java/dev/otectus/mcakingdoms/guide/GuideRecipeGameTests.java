package dev.otectus.mcakingdoms.guide;

import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Optional;

/**
 * The Book's recipe display model against real recipe managers (A02, A03): the server's own recipes for both items, a
 * data-pack replacement, a removal with and without other recipes for the same output, tag alternatives, an empty tag,
 * a special recipe and a furnace recipe. The client resolves exactly the same way from the recipes the server synchronized.
 */
@GameTestHolder("mcakingdoms")
@PrefixGameTestTemplate(false)
public final class GuideRecipeGameTests {
    private static final ResourceLocation LEDGER = new ResourceLocation("mcakingdoms", "village_ledger");
    private static final ResourceLocation BOOK = new ResourceLocation("mcakingdoms", "book_of_kingdoms");

    private static Item item(ResourceLocation id) {
        return ForgeRegistries.ITEMS.getValue(id);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    private static List<Item> items(GuideRecipe.Slot slot) {
        return slot.options().stream().map(ItemStack::getItem).toList();
    }

    private static String text(GuideRecipe recipe) {
        return String.join(" | ", recipe.describe().stream().map(net.minecraft.network.chat.Component::getString).toList());
    }

    private static RecipeManager isolated(String... recipes) {
        RecipeManager manager = new RecipeManager();
        var list = new java.util.ArrayList<net.minecraft.world.item.crafting.Recipe<?>>();
        for (int i = 0; i + 1 < recipes.length; i += 2)
            list.add(RecipeManager.fromJson(new ResourceLocation(recipes[i]), JsonParser.parseString(recipes[i + 1]).getAsJsonObject()));
        manager.replaceRecipes(list);
        return manager;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void shippedRecipesResolveAsTheServerHasThem(GameTestHelper helper) {
        var level = helper.getLevel();
        var ledger = GuideRecipe.resolve(level.getRecipeManager(), level.registryAccess(), LEDGER, Optional.of(item(LEDGER)));
        check(ledger.status() == GuideRecipe.Status.AVAILABLE && ledger.layout() == GuideRecipe.Layout.SHAPED, "Ledger: " + ledger);
        check(ledger.width() == 3 && ledger.height() == 3 && ledger.slots().size() == 9, "Ledger grid size");
        List<Item> paper = List.of(Items.PAPER);
        check(items(ledger.slots().get(0)).equals(paper) && items(ledger.slots().get(1)).equals(paper) && items(ledger.slots().get(2)).equals(paper), "Top row is paper");
        check(items(ledger.slots().get(3)).equals(paper) && items(ledger.slots().get(4)).equals(List.of(Items.BOOK)) && items(ledger.slots().get(5)).equals(paper), "Middle row is paper, book, paper");
        check(ledger.slots().get(6).empty() && items(ledger.slots().get(7)).equals(List.of(Items.COMPASS)) && ledger.slots().get(8).empty(), "Bottom row is empty, compass, empty");
        check(ledger.result().is(item(LEDGER)) && ledger.result().getCount() == 1, "Ledger output");
        check(ledger.station() == GuideRecipe.Station.CRAFTING_TABLE, "A 3x3 recipe needs a crafting table");
        check(ledger.ingredients().size() == 3 && ledger.ingredients().get(0).count() == 5, "Grouped ingredients: " + ledger.ingredients());
        String words = text(ledger);
        check(words.contains("Row 3: empty, Compass, empty") && words.contains("5 × Paper") && words.contains("Crafting table"), "Text equivalent: " + words);

        var book = GuideRecipe.resolve(level.getRecipeManager(), level.registryAccess(), BOOK, Optional.of(item(BOOK)));
        check(book.status() == GuideRecipe.Status.AVAILABLE && book.layout() == GuideRecipe.Layout.SHAPELESS && book.slots().size() == 2, "Book: " + book);
        check(book.station() == GuideRecipe.Station.INVENTORY_OR_TABLE, "Two ingredients fit the inventory grid");
        check(book.result().getCount() == 1 && text(book).contains("Any arrangement"), "Book text: " + text(book));
        check(!book.hasAlternatives() && !ledger.hasAlternatives(), "Shipped recipes name single items");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aReplacedRecipeShowsTheReplacement(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = isolated("mcakingdoms:village_ledger", """
                {"type":"minecraft:crafting_shaped","pattern":["PP","BC"],"key":{"P":{"item":"minecraft:paper"},"B":{"item":"minecraft:book"},"C":{"item":"minecraft:compass"}},
                 "result":{"item":"mcakingdoms:village_ledger","count":2}}""");
        var ledger = GuideRecipe.resolve(manager, level.registryAccess(), LEDGER, Optional.of(item(LEDGER)));
        check(ledger.status() == GuideRecipe.Status.AVAILABLE && ledger.width() == 2 && ledger.height() == 2, "Replacement grid: " + ledger);
        check(ledger.result().getCount() == 2, "Replacement output count");
        check(ledger.station() == GuideRecipe.Station.INVENTORY_OR_TABLE, "A 2x2 replacement fits the inventory grid");
        check(text(ledger).contains("Makes 2 ×"), "Text shows the server's count: " + text(ledger));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aRemovedRecipeIsNeverShownAsAvailable(GameTestHelper helper) {
        var level = helper.getLevel();
        var none = GuideRecipe.resolve(isolated(), level.registryAccess(), LEDGER, Optional.of(item(LEDGER)));
        check(none.status() == GuideRecipe.Status.REMOVED && none.alternatives().isEmpty() && none.slots().isEmpty(), "Removed: " + none);
        check(text(none).startsWith("This recipe is unavailable on this server."), "Removed text: " + text(none));
        var other = isolated("test:another_ledger", """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:map"},{"item":"minecraft:book"}],"result":{"item":"mcakingdoms:village_ledger"}}""");
        var moved = GuideRecipe.resolve(other, level.registryAccess(), LEDGER, Optional.of(item(LEDGER)));
        check(moved.status() == GuideRecipe.Status.REMOVED, "The original id stays unavailable even when another recipe makes the item");
        check(moved.alternatives().size() == 1 && moved.alternatives().get(0).id().equals(new ResourceLocation("test", "another_ledger")),
                "The other recipe is offered under its own id: " + moved.alternatives());
        var noWorld = GuideRecipe.noWorld(LEDGER);
        check(noWorld.status() == GuideRecipe.Status.NO_WORLD && text(noWorld).contains("no world is connected"), "No world: " + text(noWorld));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void alternativesAndEmptyTagsAreExplained(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = isolated("test:planks_box", """
                {"type":"minecraft:crafting_shaped","pattern":["W W","WXW"],"key":{"W":{"tag":"minecraft:planks"},"X":{"tag":"mcakingdoms:no_items_have_this_tag"}},
                 "result":{"item":"minecraft:chest"}}""");
        var box = GuideRecipe.resolve(manager, level.registryAccess(), new ResourceLocation("test", "planks_box"), Optional.empty());
        check(box.status() == GuideRecipe.Status.AVAILABLE && box.hasAlternatives(), "Tag alternatives: " + box);
        check(box.slots().get(0).options().size() > 1, "A tag slot lists every item it accepts");
        check(box.slots().get(1).empty(), "A blank pattern cell is an empty slot");
        check(box.slots().get(4).unavailable(), "An empty tag is an unavailable ingredient, not a barrier block");
        String words = text(box);
        check(words.contains("any of") && words.contains("cannot be completed"), "Alternatives and the blocked ingredient are named: " + words);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void specialAndCookingRecipesAreHonest(GameTestHelper helper) {
        var level = helper.getLevel();
        var special = GuideRecipe.resolve(level.getRecipeManager(), level.registryAccess(), new ResourceLocation("minecraft", "map_cloning"), Optional.empty());
        check(special.status() == GuideRecipe.Status.UNSUPPORTED && text(special).contains("special crafting rule"), "Special recipe: " + text(special));
        var smelting = GuideRecipe.resolve(level.getRecipeManager(), level.registryAccess(), new ResourceLocation("minecraft", "iron_ingot_from_smelting_raw_iron"), Optional.empty());
        check(smelting.status() == GuideRecipe.Status.AVAILABLE && smelting.layout() == GuideRecipe.Layout.SINGLE
                && smelting.station() == GuideRecipe.Station.FURNACE, "Smelting: " + smelting);
        helper.succeed();
    }
}
