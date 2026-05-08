package com.demkom58.divinedrop.util;

import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class ItemUtil {
    private static final String NO_PICKUP_METADATA = "no_pickup";

    private ItemUtil() {
    }

    public static boolean hasNoPickupFlag(Item item) {
        return item.getPickupDelay() == Short.MAX_VALUE || item.hasMetadata(NO_PICKUP_METADATA);
    }

    public static boolean isStackEquivalent(ItemStack first, ItemStack second) {
        return first.getType() == second.getType() && first.isSimilar(second);
    }

    public static int totalAmount(Map<Integer, ItemStack> stacks) {
        int amount = 0;
        for (ItemStack stack : stacks.values())
            amount += stack.getAmount();
        return amount;
    }
}
