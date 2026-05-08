package com.demkom58.divinedrop.drop;

import com.demkom58.divinedrop.DivineDrop;
import com.demkom58.divinedrop.config.ConfigData;
import com.demkom58.divinedrop.config.DataContainer;
import com.demkom58.divinedrop.config.StaticData;
import com.demkom58.divinedrop.util.ItemUtil;
import com.google.common.collect.MapMaker;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.MetadataValue;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ItemRegistry {

    @Getter private final Set<Item> timedItems = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());
    @Getter private final Set<ItemStack> deathDropItems = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    private final DivineDrop plugin;
    private final ConfigData data;
    private final ItemHandler itemHandler;

    public ItemRegistry(@NotNull final DivineDrop plugin,
                        @NotNull final ConfigData data,
                        @NotNull final ItemHandler itemHandler) {
        this.plugin = plugin;
        this.data = data;
        this.itemHandler = itemHandler;
    }

    /**
     * Calls from item spawn event.
     *
     * @param item - item that was spawned.
     *
     * @return true to allow spawn.
     */
    public boolean spawnedItem(@NotNull final Item item) {
        handleNewTimedItem(item);
        return true;
    }

    /**
     * Calls from chunk load event.
     * @param item - item that was loaded.
     */
    public void loadedItem(@NotNull final Item item) {
        if (isIgnoredItem(item))
            return;

        item.setCustomNameVisible(true);

        if (!data.isCleanerEnabled() || !data.isAddItemsOnChunkLoad()) {
            item.setCustomName(itemHandler.getFormattedName(item));
            return;
        }

        timedItems.add(item);
    }

    /**
     * Calls from item despawn event.
     *
     * @param item - item entity that should be despawned.
     *
     * @return true if allow despawn.
     */
    @SuppressWarnings("Duplicates")
    public boolean deSpawnedItem(@NotNull final Item item) {
        if (!data.isCleanerEnabled())
            return true;

        if (data.isSavePlayerDeathDroppedItems())
            deathDropItems.remove(item.getItemStack());

        timedItems.remove(item);
        return true;
    }

    /**
     * Calls on item pickup event.
     *
     * @param entity - entity that picks up item.
     * @param item - item that should be picked up.
     *
     * @return true if allowed to pickup
     */
    @SuppressWarnings("Duplicates")
    public boolean itemPickup(@NotNull final Entity entity, @NotNull final Item item) {
        if (!(entity instanceof Player))
            return true;

        final Player player = (Player) entity;

        if (data.isPickupOnShift() && !player.isSneaking())
            return false;

        final ItemStack stack = item.getItemStack();
        final int inventoryMax = Math.max(1, stack.getMaxStackSize());

        if (stack.getAmount() > inventoryMax) {
            final int remaining = moveToInventory(item, player.getInventory(), inventoryMax);
            player.updateInventory();

            if (remaining <= 0)
                unregisterRemovedItem(stack, item);
            else
                refreshItemAfterAmountChange(item);

            return false;
        }

        unregisterRemovedItem(stack, item);
        return true;
    }

    public boolean inventoryPickup(@NotNull final Inventory inventory, @NotNull final Item item) {
        final ItemStack stack = item.getItemStack();
        final int inventoryMax = Math.max(1, stack.getMaxStackSize());

        if (stack.getAmount() <= inventoryMax)
            return true;

        final int remaining = moveToInventory(item, inventory, inventoryMax);

        if (remaining <= 0)
            unregisterRemovedItem(stack, item);
        else
            refreshItemAfterAmountChange(item);

        return false;
    }

    /**
     * Class from Player death event.
     *
     * @param player - died player.
     * @param item - List of {@link ItemStack itemStack} that should be dropped from player.
     */
    public void deathItemsDrop(@NotNull final Player player, @NotNull final List<ItemStack> item) {
        if (!data.isCleanerEnabled())
            return;

        if (data.isSavePlayerDeathDroppedItems())
            deathDropItems.addAll(item);
    }

    /**
     * Calls from item merge event.
     *
     * @param with - this stack will be saved.
     * @param removed - that stack will be removed.
     *
     * @return true if allow merge.
     */
    public boolean mergeDrop(@NotNull final Item with, @NotNull final Item removed) {
        if (!data.isCleanerEnabled())
            handleNewTimedItem(with);
        else
            timedItems.remove(removed);

        if (data.getMaxStack() <= 0)
            return true;

        final Item keep = with.getTicksLived() >= removed.getTicksLived() ? with : removed;
        final Item merge = keep == with ? removed : with;
        mergeItems(keep, merge, Math.max(1, data.getMaxStack()));
        return false;
    }

    public void stackNearbyItems() {
        final int radius = data.getStackRadius();
        final int maxStack = Math.max(1, data.getMaxStack());

        if (radius <= 0 || maxStack <= 1)
            return;

        final double radiusSquared = radius * radius;
        for (World world : Bukkit.getWorlds()) {
            final List<Item> worldItems = new ArrayList<>();
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item))
                    continue;

                final Item item = (Item) entity;
                if (!item.isValid() || item.isDead() || isIgnoredItem(item))
                    continue;

                worldItems.add(item);
            }

            worldItems.sort(Comparator.comparingInt(Item::getTicksLived).reversed());
            final Map<ItemSignature, Map<Long, List<Item>>> holders = new HashMap<>();

            for (Item current : worldItems) {
                if (!current.isValid() || current.isDead())
                    continue;

                final ItemStack currentStack = current.getItemStack();
                if (currentStack.getType() == Material.AIR || currentStack.getAmount() <= 0)
                    continue;

                final ItemSignature signature = ItemSignature.of(currentStack);
                final Map<Long, List<Item>> buckets = holders.computeIfAbsent(signature, ignored -> new HashMap<>());

                final Location source = current.getLocation();
                final int sourceCellX = toCell(source.getX(), radius);
                final int sourceCellZ = toCell(source.getZ(), radius);

                for (int x = sourceCellX - 1; x <= sourceCellX + 1 && current.isValid(); x++) {
                    for (int z = sourceCellZ - 1; z <= sourceCellZ + 1 && current.isValid(); z++) {
                        final List<Item> candidates = buckets.get(cellKey(x, z));
                        if (candidates == null || candidates.isEmpty())
                            continue;

                        for (Item candidate : candidates) {
                            if (!current.isValid())
                                break;
                            if (candidate == current || !candidate.isValid() || candidate.isDead())
                                continue;
                            if (candidate.getItemStack().getAmount() >= maxStack)
                                continue;
                            if (candidate.getLocation().distanceSquared(source) > radiusSquared)
                                continue;

                            mergeItems(candidate, current, maxStack);
                        }
                    }
                }

                if (!current.isValid() || current.isDead())
                    continue;

                buckets.computeIfAbsent(cellKey(sourceCellX, sourceCellZ), ignored -> new ArrayList<>())
                        .add(current);
            }
        }
    }

    public boolean isIgnoredItem(Item item) {
        return data.isIgnoreNoPickup() && ItemUtil.hasNoPickupFlag(item);
    }

    private int moveToInventory(@NotNull final Item item,
                                @NotNull final Inventory inventory,
                                final int inventoryMax) {
        int remaining = item.getItemStack().getAmount();
        while (remaining > 0) {
            final int partAmount = Math.min(remaining, inventoryMax);
            final ItemStack toInsert = item.getItemStack().clone();
            toInsert.setAmount(partAmount);

            final Map<Integer, ItemStack> leftovers = inventory.addItem(toInsert);
            final int left = ItemUtil.totalAmount(leftovers);
            final int accepted = partAmount - left;

            if (accepted <= 0)
                break;

            remaining -= accepted;
            if (left > 0)
                break;
        }

        if (remaining <= 0) {
            item.remove();
            return 0;
        }

        final ItemStack updated = item.getItemStack().clone();
        updated.setAmount(remaining);
        item.setItemStack(updated);
        return remaining;
    }

    private int mergeItems(@NotNull final Item with, @NotNull final Item removed, final int maxStack) {
        if (!with.isValid() || !removed.isValid() || with == removed)
            return 0;

        final ItemStack withStack = with.getItemStack();
        final ItemStack removedStack = removed.getItemStack();
        if (!ItemUtil.isStackEquivalent(withStack, removedStack))
            return 0;

        final int amountWith = withStack.getAmount();
        final int amountRemoved = removedStack.getAmount();
        if (amountWith >= maxStack || amountRemoved <= 0)
            return 0;

        final int merged = Math.min(maxStack - amountWith, amountRemoved);
        if (merged <= 0)
            return 0;

        withStack.setAmount(amountWith + merged);
        with.setItemStack(withStack);
        refreshItemAfterAmountChange(with);

        final int left = amountRemoved - merged;
        if (left <= 0) {
            unregisterRemovedItem(removedStack, removed);
            removed.remove();
        } else {
            removedStack.setAmount(left);
            removed.setItemStack(removedStack);
            refreshItemAfterAmountChange(removed);
        }

        return merged;
    }

    private void handleNewTimedItem(@NotNull final Item item) {
        DivineDrop.getMorePaperLib().scheduling().entitySpecificScheduler(item).run(() -> {
            if (isIgnoredItem(item))
                return;

            item.setCustomNameVisible(true);

            if (!data.isCleanerEnabled()) {
                item.setCustomName(itemHandler.getFormattedName(item));
                return;
            }

            timedItems.add(item);
        }, null);
    }

    private void unregisterRemovedItem(@NotNull final ItemStack stack, @NotNull final Item item) {
        if (!data.isCleanerEnabled())
            return;

        if (data.isSavePlayerDeathDroppedItems())
            deathDropItems.remove(stack);

        timedItems.remove(item);
    }

    private void refreshItemAfterAmountChange(@NotNull final Item item) {
        if (isIgnoredItem(item))
            return;

        item.setCustomNameVisible(true);

        if (!data.isCleanerEnabled()) {
            item.setCustomName(itemHandler.getFormattedName(item));
            return;
        }

        final List<MetadataValue> metadataValues = item.getMetadata(StaticData.METADATA_COUNTDOWN);
        if (metadataValues.isEmpty())
            return;

        final Object dataContainer = metadataValues.get(0).value();
        if (dataContainer instanceof DataContainer)
            itemHandler.updateTimedItem(item, (DataContainer) dataContainer);
    }

    private static int toCell(double coordinate, int radius) {
        return (int) Math.floor(coordinate / radius);
    }

    private static long cellKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static final class ItemSignature {
        private final Material material;
        private final ItemMeta meta;

        private ItemSignature(@NotNull final Material material,
                              final ItemMeta meta) {
            this.material = material;
            this.meta = meta;
        }

        static ItemSignature of(@NotNull final ItemStack stack) {
            return new ItemSignature(stack.getType(), stack.getItemMeta());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof ItemSignature))
                return false;
            final ItemSignature other = (ItemSignature) obj;
            return material == other.material && Objects.equals(meta, other.meta);
        }

        @Override
        public int hashCode() {
            return Objects.hash(material, meta);
        }
    }
}
