package com.aymandev.universalresources;

import java.util.Set;
import java.util.HashSet;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.server.level.ServerLevel;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class Converter {

  private static final Map<Item, List<? extends String>> ITEM_TO_TAG_LIST_MAP = new HashMap<>();

  static {
    ITEM_TO_TAG_LIST_MAP.put(UniversalResourcesItems.TOOLS_PACK.get(), Config.TOOLS_ITEMS.get());
    ITEM_TO_TAG_LIST_MAP.put(
        UniversalResourcesItems.RARE_METAL_PILE.get(), Config.RARE_METAL_BLOCKS.get());
    ITEM_TO_TAG_LIST_MAP.put(UniversalResourcesItems.IRON_PILE.get(), Config.IRON_BLOCKS.get());
    ITEM_TO_TAG_LIST_MAP.put(UniversalResourcesItems.STONE_PILE.get(), Config.STONE_BLOCKS.get());
    ITEM_TO_TAG_LIST_MAP.put(UniversalResourcesItems.WOOD_PILE.get(), Config.WOOD_BLOCKS.get());
  }

  public static ItemStack getItemForBlock(Level level, BlockState blockState) {
    if (blockState.isAir() || isFree(blockState)) {
      return null;
    }

    for (var entry : ITEM_TO_TAG_LIST_MAP.entrySet()) {
      var item = entry.getKey();
      var list = entry.getValue();
      var stack = tryToGetItemStackForCategory(item, list, blockState);

      if (stack == null) {
        continue;
      }

      return stack;
    }

    var block = blockState.getBlock();

    if (isBlockCraftableWith(level, block, getIngredientFromConfig(Config.WOOD_ITEMS.get()))) {
      return new ItemStack(UniversalResourcesItems.WOOD_PILE.get());
    }

    if (isBlockCraftableWith(level, block, getIngredientFromConfig(Config.STONE_ITEMS.get()))) {
      return new ItemStack(UniversalResourcesItems.STONE_PILE.get());
    }

    if (isBlockCraftableWith(level, block, getIngredientFromConfig(Config.IRON_ITEMS.get()))) {
      return new ItemStack(UniversalResourcesItems.IRON_PILE.get());
    }

    if (isBlockCraftableWith(level, block, getIngredientFromConfig(Config.TOOLS_ITEMS.get()))) {
      return new ItemStack(UniversalResourcesItems.TOOLS_PACK.get());
    }

    if (isBlockCraftableWith(
        level, block, getIngredientFromConfig(Config.RARE_METAL_ITEMS.get()))) {
      return new ItemStack(UniversalResourcesItems.RARE_METAL_PILE.get());
    }

    UniversalResources.LOGGER.info(
        "{} does not have configured conversion in config. Fallback to universal bag", block);

    return getDefaultItem();
  }

  private static ItemStack getDefaultItem() {
    return new ItemStack(UniversalResourcesItems.UNIVERSAL_PACK.get(), 1);
  }

  private static boolean isFree(BlockState blockState) {
    if (Config.FREE_BLOCKS.get().isEmpty()) {
      return false;
    }

    for (var str : Config.FREE_BLOCKS.get()) {
      if (matches(blockState, str)) {
        return true;
      }
    }

    return false;
  }

  private static ItemStack tryToGetItemStackForCategory(
      Item itemForCategory, List<? extends String> tagList, BlockState blockState) {

    if (tagList.isEmpty()) {
      return null;
    }

    for (var str : tagList) {
      if (matches(blockState, str)) {
        return new ItemStack(itemForCategory, 1);
      }
    }

    return null;
  }

  private static boolean matches(BlockState blockState, String value) {
    if (value.startsWith("#")) {
      var loc = Identifier.parse(value.substring(1));
      var tag = TagKey.create(BuiltInRegistries.BLOCK.key(), loc);

      return blockState.is(tag);
    }

    var loc = Identifier.parse(value);
    // PORT(26.1): Registry#get returns an Optional holder; getValue is the plain lookup.
    return blockState.is(BuiltInRegistries.BLOCK.getValue(loc));
  }

  /**
   * PORT(26.1): Ingredient no longer mixes tag and item values (it is a single HolderSet), so the configured list is
   * kept as a predicate plus the expanded item list the Architect's Cutter check needs.
   */
  private record Required(Set<TagKey<Item>> tags, Set<Item> items) {
    boolean test(ItemStack stack) {
      if (items.contains(stack.getItem())) {
        return true;
      }
      for (var tag : tags) {
        if (stack.is(tag)) {
          return true;
        }
      }
      return false;
    }

    List<Item> expanded() {
      var result = new ArrayList<Item>(items);
      for (var tag : tags) {
        BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(holder -> result.add(holder.value()));
      }
      return result;
    }
  }

  private static Required getIngredientFromConfig(List<? extends String> strList) {
    var tags = new HashSet<TagKey<Item>>();
    var items = new HashSet<Item>();

    for (var str : strList) {
      if (str.startsWith("#")) {
        tags.add(TagKey.create(BuiltInRegistries.ITEM.key(), Identifier.parse(str.substring(1))));
        continue;
      }
      items.add(BuiltInRegistries.ITEM.getValue(Identifier.parse(str)));
    }

    return new Required(tags, items);
  }

  /**
   * PORT(26.1): recipes live on the server only (ServerLevel#recipeAccess), and a recipe's result and ingredients are
   * read through its displays and placement info instead of getResultItem/getIngredients.
   */
  private static boolean isBlockCraftableWith(Level level, Block block, Required required) {
    if (!(level instanceof ServerLevel serverLevel)) {
      return false;
    }

    var item = block.asItem();

    for (var holder : serverLevel.recipeAccess().getRecipes()) {
      var recipe = holder.value();

      if (!producesItem(recipe, item)) {
        continue;
      }

      if (recipe instanceof ArchitectsCutterRecipe cutterRecipe) {
        return architectsCutterContainsIngredient(cutterRecipe, required);
      }

      for (var ingredient : recipe.placementInfo().ingredients()) {
        if (containsIngredient(ingredient, required)) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean producesItem(Recipe<?> recipe, Item item) {
    for (var display : recipe.display()) {
      var result = display.result();
      if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay && itemDisplay.item().value() == item) {
        return true;
      }
      if (result instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay && stackDisplay.stack().item().value() == item) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsIngredient(Ingredient recipeIngredient, Required required) {
    return recipeIngredient.items().anyMatch(holder -> required.test(new ItemStack(holder)));
  }

  private static boolean architectsCutterContainsIngredient(
      ArchitectsCutterRecipe recipe, Required required) {
    var output = recipe.getBlock();

    if (!(output instanceof IMateriallyTexturedBlock textured)) {
      return false;
    }

    for (var component : textured.getComponents()) {
      var validSkins = component.getValidSkins();

      for (var candidate : required.expanded()) {
        if (!(candidate instanceof BlockItem item)) {
          continue;
        }

        if (item.getBlock().defaultBlockState().is(validSkins)) {
          return true;
        }
      }
    }

    return false;
  }
}
