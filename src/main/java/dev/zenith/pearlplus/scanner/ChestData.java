package dev.zenith.pearlplus.scanner;

import java.util.HashMap;
import java.util.Map;

public class ChestData {
    public String chestKey;
    public String blockType;
    public long x, y, z;
    public long scanTime;
    public boolean doubleChest;
    public String dimension;
    public String zoneName;
    public String zoneType;
    public Map<Integer, ItemData> items;

    public ChestData(String chestKey, String blockType, long x, long y, long z, boolean doubleChest, String dimension) {
        this.chestKey = chestKey;
        this.blockType = blockType;
        this.x = x;
        this.y = y;
        this.z = z;
        this.scanTime = System.currentTimeMillis();
        this.doubleChest = doubleChest;
        this.dimension = dimension;
        this.items = new HashMap<>();
    }

    public ItemData addItem(int slot, String itemId, int count) {
        final ItemData itemData = new ItemData(itemId, count);
        items.put(slot, itemData);
        return itemData;
    }

    public String getFormattedKey() {
        return String.format("%s:%d_%d_%d", dimension, x, y, z);
    }

    @Override
    public String toString() {
        return String.format("ChestData{key=%s, type=%s, items=%d}", chestKey, blockType, items.size());
    }

    public static class ItemData {
        public String itemId;
        public int count;
        public Integer containerSlotsUsed;
        public Integer containerCapacity;
        public String fullOfItemId;
        public Map<String, Integer> containerItemTotals;

        public ItemData(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        public void setContainerSummary(final int slotsUsed,
                                        final int capacity,
                                        final String fullOfItemId,
                                        final Map<String, Integer> containerItemTotals) {
            this.containerSlotsUsed = slotsUsed;
            this.containerCapacity = capacity;
            this.fullOfItemId = fullOfItemId;
            this.containerItemTotals = containerItemTotals;
        }
    }
}
