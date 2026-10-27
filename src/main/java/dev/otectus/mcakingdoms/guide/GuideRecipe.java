package dev.otectus.mcakingdoms.guide;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.crafting.IShapedRecipe;

import java.util.*;

/**
 * A read-only display model of one recipe, resolved by id from the recipe manager the caller supplies: on the client the
 * one the connected server synchronized, in tests the server's own. It never consumes, gives or crafts anything, and it
 * never substitutes a bundled copy for what the server actually has.
 *
 * <p>Shaped recipes keep their width, height, empty cells and arrangement; shapeless recipes say that position does not
 * matter. Where the recipe can be crafted is decided from the recipe itself ({@code canCraftInDimensions}), not from a
 * separately written sentence. A recipe this model cannot show faithfully (a special or custom crafting rule) is reported
 * as {@link Status#UNSUPPORTED} instead of being guessed.
 */
public record GuideRecipe(ResourceLocation id, Status status, Layout layout, int width, int height, List<Slot> slots,
                          ItemStack result, Station station, List<Group> ingredients, ResourceLocation serializer,
                          List<GuideRecipe> alternatives) {
    public enum Status {
        /** Found on this server and shown as it is. */
        AVAILABLE,
        /** No recipe has this id on the connected server (a data pack removed or renamed it). */
        REMOVED,
        /** There is no connected world, so the server's recipes are unknown. */
        NO_WORLD,
        /** The recipe exists but uses a rule this book cannot display faithfully. */
        UNSUPPORTED
    }

    public enum Layout { SHAPED, SHAPELESS, SINGLE, NONE }

    /** Where the recipe is made, from its type and the space it needs. */
    public enum Station { INVENTORY_OR_TABLE, CRAFTING_TABLE, FURNACE, BLAST_FURNACE, SMOKER, CAMPFIRE, STONECUTTER, SMITHING_TABLE, UNKNOWN }

    /**
     * One input cell. {@code options} are the items any of which fits; an empty cell of a shaped grid has none and
     * {@link #empty()} true. A tag that names no items is {@link #unavailable()}: it can never be filled.
     */
    public record Slot(List<ItemStack> options, boolean empty, boolean unavailable) {
        public Slot { options = options.stream().map(ItemStack::copy).toList(); }
        public static final Slot EMPTY = new Slot(List.of(), true, false);
    }

    /** Identical inputs counted together for the ingredient list, such as "5 × Paper". */
    public record Group(List<ItemStack> options, int count, boolean unavailable) {
        public Group { options = options.stream().map(ItemStack::copy).toList(); }
    }

    /** The most alternatives listed for a removed recipe whose output still has other recipes. */
    public static final int MAX_ALTERNATIVES = 3;

    public GuideRecipe {
        slots = List.copyOf(slots);
        ingredients = List.copyOf(ingredients);
        alternatives = List.copyOf(alternatives);
        result = result.copy();
    }

    /** The model for a world that is not connected: nothing about the server's recipes is claimed. */
    public static GuideRecipe noWorld(ResourceLocation id) {
        return new GuideRecipe(id, Status.NO_WORLD, Layout.NONE, 0, 0, List.of(), ItemStack.EMPTY, Station.UNKNOWN, List.of(), null, List.of());
    }

    /**
     * Resolves {@code id} in {@code manager}. When it is missing and {@code expectedOutput} is known, other recipes for that
     * item are listed as explicit alternatives, each under its own id; none of them is presented as the original.
     */
    public static GuideRecipe resolve(RecipeManager manager, RegistryAccess access, ResourceLocation id, Optional<Item> expectedOutput) {
        Optional<? extends Recipe<?>> found = manager.byKey(id);
        if (found.isPresent()) return of(found.get(), access);
        List<GuideRecipe> alternatives = new ArrayList<>();
        expectedOutput.ifPresent(item -> manager.getRecipes().stream()
                .filter(recipe -> !recipe.getId().equals(id))
                .filter(recipe -> resultOf(recipe, access).is(item))
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                .limit(MAX_ALTERNATIVES)
                .forEach(recipe -> alternatives.add(of(recipe, access))));
        return new GuideRecipe(id, Status.REMOVED, Layout.NONE, 0, 0, List.of(), ItemStack.EMPTY, Station.UNKNOWN, List.of(), null, alternatives);
    }

    private static ItemStack resultOf(Recipe<?> recipe, RegistryAccess access) {
        try {
            ItemStack result = recipe.getResultItem(access);
            return result == null ? ItemStack.EMPTY : result;
        } catch (RuntimeException unsupported) {
            return ItemStack.EMPTY;
        }
    }

    /** The display model of one resolved recipe. */
    public static GuideRecipe of(Recipe<?> recipe, RegistryAccess access) {
        ResourceLocation serializer = net.minecraftforge.registries.ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer());
        ItemStack result = resultOf(recipe, access);
        if (recipe.isSpecial() || result.isEmpty()) return unsupported(recipe, result, serializer);
        RecipeType<?> type = recipe.getType();
        List<Ingredient> inputs = recipe.getIngredients();
        if (type == RecipeType.CRAFTING) {
            Station station = recipe.canCraftInDimensions(2, 2) ? Station.INVENTORY_OR_TABLE : Station.CRAFTING_TABLE;
            if (recipe instanceof IShapedRecipe<?> shaped) {
                int width = shaped.getRecipeWidth(), height = shaped.getRecipeHeight();
                if (width < 1 || height < 1 || width > 3 || height > 3 || inputs.size() != width * height) return unsupported(recipe, result, serializer);
                List<Slot> slots = inputs.stream().map(GuideRecipe::slot).toList();
                return new GuideRecipe(recipe.getId(), Status.AVAILABLE, Layout.SHAPED, width, height, slots, result, station, group(slots), serializer, List.of());
            }
            if (recipe instanceof ShapelessRecipe) {
                if (inputs.isEmpty() || inputs.size() > 9) return unsupported(recipe, result, serializer);
                List<Slot> slots = inputs.stream().map(GuideRecipe::slot).toList();
                return new GuideRecipe(recipe.getId(), Status.AVAILABLE, Layout.SHAPELESS, 0, 0, slots, result, station, group(slots), serializer, List.of());
            }
            // A custom crafting rule (another mod's serializer): its real matching logic is unknown here.
            return unsupported(recipe, result, serializer);
        }
        Station station = type == RecipeType.SMELTING ? Station.FURNACE : type == RecipeType.BLASTING ? Station.BLAST_FURNACE
                : type == RecipeType.SMOKING ? Station.SMOKER : type == RecipeType.CAMPFIRE_COOKING ? Station.CAMPFIRE
                : type == RecipeType.STONECUTTING ? Station.STONECUTTER : Station.UNKNOWN;
        if (station != Station.UNKNOWN && inputs.size() == 1) {
            List<Slot> slots = List.of(slot(inputs.get(0)));
            return new GuideRecipe(recipe.getId(), Status.AVAILABLE, Layout.SINGLE, 1, 1, slots, result, station, group(slots), serializer, List.of());
        }
        return unsupported(recipe, result, serializer);
    }

    private static GuideRecipe unsupported(Recipe<?> recipe, ItemStack result, ResourceLocation serializer) {
        return new GuideRecipe(recipe.getId(), Status.UNSUPPORTED, Layout.NONE, 0, 0, List.of(), result, Station.UNKNOWN, List.of(), serializer, List.of());
    }

    private static Slot slot(Ingredient ingredient) {
        if (ingredient.isEmpty()) return Slot.EMPTY;
        List<ItemStack> options = Arrays.stream(ingredient.getItems())
                // Forge shows an empty tag as a renamed barrier ("Empty Tag: …"); that is not an item anyone can supply.
                .filter(stack -> !stack.isEmpty() && !(stack.is(Items.BARRIER) && stack.hasCustomHoverName()))
                .toList();
        return options.isEmpty() ? new Slot(List.of(), false, true) : new Slot(options, false, false);
    }

    private static List<Group> group(List<Slot> slots) {
        Map<List<Item>, int[]> counts = new LinkedHashMap<>();
        Map<List<Item>, Slot> first = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (slot.empty()) continue;
            List<Item> key = slot.unavailable() ? List.of() : slot.options().stream().map(ItemStack::getItem).toList();
            counts.computeIfAbsent(key, ignored -> new int[1])[0]++;
            first.putIfAbsent(key, slot);
        }
        List<Group> groups = new ArrayList<>();
        counts.forEach((key, count) -> groups.add(new Group(first.get(key).options(), count[0], first.get(key).unavailable())));
        return groups;
    }

    /** Whether any input is a choice between several items (a tag or alternative list). */
    public boolean hasAlternatives() {
        return slots.stream().anyMatch(slot -> slot.options().size() > 1);
    }

    private static Component t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("guide.mcakingdoms.recipe." + key, fallback, args);
    }

    /** A stable name for one input: the item, "any of …" for alternatives, or the unavailable state. Never cycles. */
    public static Component name(List<ItemStack> options, boolean unavailable) {
        if (unavailable) return t("ingredient.unavailable", "an ingredient this server has no items for");
        if (options.isEmpty()) return t("empty", "empty");
        if (options.size() == 1) return options.get(0).getHoverName();
        MutableComponent list = Component.empty();
        int shown = Math.min(3, options.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) list.append(", ");
            list.append(options.get(i).getHoverName());
        }
        return options.size() > shown ? t("any_of_more", "any of %s and %s more", list, options.size() - shown) : t("any_of", "any of %s", list);
    }

    public static Component station(Station station) {
        return switch (station) {
            case INVENTORY_OR_TABLE -> t("station.inventory", "Inventory crafting grid or crafting table");
            case CRAFTING_TABLE -> t("station.table", "Crafting table");
            case FURNACE -> t("station.furnace", "Furnace");
            case BLAST_FURNACE -> t("station.blast_furnace", "Blast furnace");
            case SMOKER -> t("station.smoker", "Smoker");
            case CAMPFIRE -> t("station.campfire", "Campfire");
            case STONECUTTER -> t("station.stonecutter", "Stonecutter");
            case SMITHING_TABLE -> t("station.smithing", "Smithing table");
            case UNKNOWN -> t("station.unknown", "Unknown station");
        };
    }

    /**
     * The text equivalent: output and quantity, where it is made, the arrangement (row by row for shaped recipes) and the
     * grouped ingredients. Narration and the accessible description read this, so it never depends on a cycling preview.
     */
    public List<Component> describe() {
        List<Component> lines = new ArrayList<>();
        switch (status) {
            case NO_WORLD -> lines.add(t("no_world", "Current server recipes are unavailable because no world is connected."));
            case REMOVED -> {
                lines.add(t("removed", "This recipe is unavailable on this server."));
                if (!alternatives.isEmpty()) lines.add(t("alternatives", "This server has other recipes for the same item; they are shown below under their own names."));
            }
            case UNSUPPORTED -> {
                if (!result.isEmpty()) lines.add(t("makes", "Makes %s × %s.", result.getCount(), result.getHoverName()));
                lines.add(t("unsupported", "This recipe uses a special crafting rule (%s) that the Book cannot show faithfully. A recipe viewer mod can show it, if one is installed.",
                        serializer == null ? "?" : serializer.toString()));
            }
            case AVAILABLE -> {
                lines.add(t("makes", "Makes %s × %s.", result.getCount(), result.getHoverName()));
                lines.add(t("where", "Where: %s.", station(station)));
                if (layout == Layout.SHAPED) {
                    lines.add(t("shaped", "Arrangement matters: a %s by %s grid.", width, height));
                    for (int row = 0; row < height; row++) {
                        MutableComponent cells = Component.empty();
                        for (int column = 0; column < width; column++) {
                            if (column > 0) cells.append(", ");
                            Slot slot = slots.get(row * width + column);
                            cells.append(name(slot.options(), slot.unavailable()));
                        }
                        lines.add(t("row", "Row %s: %s.", row + 1, cells));
                    }
                } else if (layout == Layout.SHAPELESS) lines.add(t("shapeless", "Any arrangement: the position of each ingredient does not matter."));
                MutableComponent list = Component.empty();
                for (int i = 0; i < ingredients.size(); i++) {
                    if (i > 0) list.append(", ");
                    Group group = ingredients.get(i);
                    list.append(t("count", "%s × %s", group.count(), name(group.options(), group.unavailable())));
                }
                lines.add(t("ingredients", "Ingredients: %s.", list));
                if (ingredients.stream().anyMatch(Group::unavailable)) lines.add(t("blocked", "One ingredient has no items on this server, so this recipe cannot be completed."));
            }
        }
        return lines;
    }
}
