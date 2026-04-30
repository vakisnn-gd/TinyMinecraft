import java.util.ArrayList;
import java.util.List;

final class UiRenderer {
    int getPauseOptionAt(double cursorX, double cursorY, int framebufferWidth, float uiScale) {
        return getCenteredMenuOptionAt(cursorX, cursorY, framebufferWidth, uiScale, GameConfig.PAUSE_OPTIONS.length, 178.0f);
    }

    int getDeathOptionAt(double cursorX, double cursorY, int framebufferWidth, float uiScale) {
        return getCenteredMenuOptionAt(cursorX, cursorY, framebufferWidth, uiScale, GameConfig.DEATH_OPTIONS.length, 236.0f);
    }

    InventoryUiLayout buildInventoryLayout(boolean creativeMode, int activeCreativeTab, int framebufferWidth, int framebufferHeight, float uiScale) {
        return buildInventoryLayout(creativeMode, activeCreativeTab, framebufferWidth, framebufferHeight, uiScale, GameConfig.INVENTORY_SCREEN_PLAYER);
    }

    InventoryUiLayout buildInventoryLayout(boolean creativeMode, int activeCreativeTab, int framebufferWidth, int framebufferHeight, float uiScale, int screenMode) {
        if (creativeMode) {
            screenMode = GameConfig.INVENTORY_SCREEN_PLAYER;
        }
        float maxScaleByWidth = (framebufferWidth - 32.0f) / (creativeMode ? 430.0f : 470.0f);
        float maxScaleByHeight = (framebufferHeight - 40.0f) / (creativeMode ? 430.0f : 380.0f);
        uiScale = Math.max(creativeMode ? 0.68f : 0.85f, Math.min(uiScale, Math.min(maxScaleByWidth, maxScaleByHeight)));
        float slotSize = 21.0f * uiScale;
        float slotGap = 3.0f * uiScale;
        float gridWidth = slotSize * 9.0f + slotGap * 8.0f;
        float gridHeight = slotSize * 4.0f + slotGap * 3.0f;
        float hotbarGap = 10.0f * uiScale;
        float padding = 16.0f * uiScale;
        float titleHeight = 22.0f * uiScale;
        float tabWidth = 68.0f * uiScale;
        float tabGap = 4.0f * uiScale;
        float tabsWidth = tabWidth * GameConfig.CREATIVE_TABS.length + tabGap * (GameConfig.CREATIVE_TABS.length - 1);

        float creativeTrashWidth = 0.0f;
        float panelWidth = creativeMode ? Math.max(gridWidth + padding * 2.0f + creativeTrashWidth, tabsWidth + padding * 2.0f) : Math.max(gridWidth + padding * 2.0f, 440.0f * uiScale);
        float panelHeight = creativeMode
            ? titleHeight + 4.0f * (slotSize + slotGap) + 18.0f * uiScale + gridHeight + hotbarGap + slotSize * 2.0f + padding * 2.0f
            : (screenMode == GameConfig.INVENTORY_SCREEN_PLAYER ? 348.0f * uiScale : 370.0f * uiScale);
        float panelX = clamp(framebufferWidth * 0.5f - panelWidth * 0.5f, 8.0f, Math.max(8.0f, framebufferWidth - panelWidth - 8.0f));
        float panelY = clamp(framebufferHeight * 0.5f - panelHeight * 0.5f - (creativeMode ? 18.0f * uiScale : 0.0f),
            8.0f, Math.max(8.0f, framebufferHeight - panelHeight - 8.0f));

        float storageX;
        float storageAbsoluteY;
        float hotbarX;
        float hotbarAbsoluteY;
        float armorX;
        float armorAbsoluteY;
        float offhandX;
        float offhandAbsoluteY;
        float craftX;
        float craftAbsoluteY;
        float resultX;
        float resultY;
        float creativeX = panelX + padding;
        float creativeAbsoluteY = panelY + titleHeight + slotSize + 12.0f * uiScale;

        if (creativeMode) {
            creativeX = panelX + padding + creativeTrashWidth;
            storageX = creativeX;
            storageAbsoluteY = creativeAbsoluteY + 4.0f * (slotSize + slotGap) + 20.0f * uiScale;
            hotbarX = storageX;
            hotbarAbsoluteY = storageAbsoluteY + gridHeight + hotbarGap;
            armorX = panelX + padding;
            armorAbsoluteY = storageAbsoluteY;
            offhandX = armorX;
            offhandAbsoluteY = hotbarAbsoluteY;
            craftX = storageX + gridWidth - 5.0f * (slotSize + slotGap);
            craftAbsoluteY = storageAbsoluteY;
            resultX = craftX + 2.0f * (slotSize + slotGap) + 16.0f * uiScale;
            resultY = craftAbsoluteY + 0.5f * (slotSize + slotGap);
        } else {
            craftX = panelX + panelWidth - padding - 5.0f * (slotSize + slotGap);
            craftAbsoluteY = panelY + 42.0f * uiScale;
            resultX = craftX + 2.0f * (slotSize + slotGap) + 18.0f * uiScale;
            resultY = craftAbsoluteY + 0.5f * (slotSize + slotGap);
            armorX = panelX + padding;
            armorAbsoluteY = panelY + 42.0f * uiScale;
            offhandX = armorX;
            offhandAbsoluteY = armorAbsoluteY + 4.0f * (slotSize + slotGap) + 10.0f * uiScale;
            storageX = panelX + (panelWidth - gridWidth) * 0.5f;
            storageAbsoluteY = screenMode == GameConfig.INVENTORY_SCREEN_PLAYER ? panelY + 168.0f * uiScale : panelY + 190.0f * uiScale;
            hotbarX = storageX;
            hotbarAbsoluteY = storageAbsoluteY + gridHeight + hotbarGap;
        }

        ArrayList<SlotBox> slots = new ArrayList<>();
        if (creativeMode) {
            addSlotBox(slots, InventorySlotGroup.TRASH, 0,
                panelX + panelWidth - padding - slotSize,
                hotbarAbsoluteY,
                slotSize);
            int[] tabIndices = InventoryItems.CREATIVE_TAB_INDICES[Math.max(0, Math.min(activeCreativeTab, InventoryItems.CREATIVE_TAB_INDICES.length - 1))];
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 9; column++) {
                    int visibleIndex = row * 9 + column;
                    if (visibleIndex >= tabIndices.length) {
                        continue;
                    }
                    addSlotBox(slots, InventorySlotGroup.CREATIVE, tabIndices[visibleIndex],
                        creativeX + column * (slotSize + slotGap),
                        creativeAbsoluteY + row * (slotSize + slotGap),
                        slotSize);
                }
            }
        }
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotBox(slots, InventorySlotGroup.STORAGE, row * 9 + column,
                    storageX + column * (slotSize + slotGap),
                    storageAbsoluteY + row * (slotSize + slotGap),
                    slotSize);
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            addSlotBox(slots, InventorySlotGroup.HOTBAR, slot,
                hotbarX + slot * (slotSize + slotGap),
                hotbarAbsoluteY,
                slotSize);
        }
        if (!creativeMode && screenMode == GameConfig.INVENTORY_SCREEN_PLAYER) {
            for (int slot = 0; slot < 4; slot++) {
                addSlotBox(slots, InventorySlotGroup.ARMOR, slot,
                    armorX,
                    armorAbsoluteY + slot * (slotSize + slotGap),
                    slotSize);
            }
            addSlotBox(slots, InventorySlotGroup.OFFHAND, 0, offhandX, offhandAbsoluteY, slotSize);
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 2; column++) {
                    addSlotBox(slots, InventorySlotGroup.CRAFT, row * 2 + column,
                        craftX + column * (slotSize + slotGap),
                        craftAbsoluteY + row * (slotSize + slotGap),
                        slotSize);
                }
            }
            addSlotBox(slots, InventorySlotGroup.CRAFT_RESULT, 0, resultX, resultY, slotSize);
        } else if (!creativeMode && screenMode == GameConfig.INVENTORY_SCREEN_WORKBENCH) {
            float workbenchWidth = 3.0f * slotSize + 2.0f * slotGap + 54.0f * uiScale + slotSize;
            float workbenchX = panelX + panelWidth * 0.5f - workbenchWidth * 0.5f;
            float workbenchY = panelY + 42.0f * uiScale;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    addSlotBox(slots, InventorySlotGroup.CRAFT_3X3, row * 3 + column,
                        workbenchX + column * (slotSize + slotGap),
                        workbenchY + row * (slotSize + slotGap),
                        slotSize);
                }
            }
            addSlotBox(slots, InventorySlotGroup.CRAFT_3X3_RESULT, 0,
                workbenchX + 3.0f * (slotSize + slotGap) + 54.0f * uiScale,
                workbenchY + slotSize + slotGap,
                slotSize);
        } else if (!creativeMode && screenMode == GameConfig.INVENTORY_SCREEN_CHEST) {
            float chestX = panelX + padding;
            float chestY = panelY + 36.0f * uiScale;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 9; column++) {
                    addSlotBox(slots, InventorySlotGroup.CHEST_CONTAINER, row * 9 + column,
                        chestX + column * (slotSize + slotGap),
                        chestY + row * (slotSize + slotGap),
                        slotSize);
                }
            }
        } else if (!creativeMode && screenMode == GameConfig.INVENTORY_SCREEN_FURNACE) {
            float furnaceX = panelX + padding + slotSize;
            float furnaceY = panelY + 48.0f * uiScale;
            addSlotBox(slots, InventorySlotGroup.FURNACE_INPUT, 0, furnaceX, furnaceY, slotSize);
            addSlotBox(slots, InventorySlotGroup.FURNACE_FUEL, 0, furnaceX, furnaceY + slotSize + slotGap, slotSize);
            addSlotBox(slots, InventorySlotGroup.FURNACE_OUTPUT, 0, furnaceX + 4.0f * (slotSize + slotGap), furnaceY + 0.5f * (slotSize + slotGap), slotSize);
        }

        return new InventoryUiLayout(
            panelX,
            panelY,
            panelWidth,
            panelHeight,
            slotSize,
            slotGap,
            storageX,
            storageAbsoluteY,
            hotbarX,
            hotbarAbsoluteY,
            armorX,
            armorAbsoluteY,
            offhandX,
            offhandAbsoluteY,
            craftX,
            craftAbsoluteY,
            resultX,
            resultY,
            creativeX,
            creativeAbsoluteY,
            9,
            4,
            Math.max(0, Math.min(activeCreativeTab, GameConfig.CREATIVE_TABS.length - 1)),
            slots
        );
    }

    private int getCenteredMenuOptionAt(double cursorX, double cursorY, int framebufferWidth, float uiScale, int optionCount, float firstOptionY) {
        float boxWidth = 260.0f * uiScale;
        float boxHeight = 48.0f * uiScale;
        float boxX = framebufferWidth * 0.5f - boxWidth * 0.5f;
        for (int i = 0; i < optionCount; i++) {
            float boxY = firstOptionY * uiScale + i * 64.0f * uiScale;
            if (cursorX >= boxX && cursorX <= boxX + boxWidth && cursorY >= boxY && cursorY <= boxY + boxHeight) {
                return i;
            }
        }
        return -1;
    }

    private void addSlotBox(List<SlotBox> slots, InventorySlotGroup group, int index, float x, float y, float size) {
        slots.add(new SlotBox(new InventorySlotRef(group, index), x, y, size));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class InventoryUiLayout {
        final float panelX;
        final float panelY;
        final float panelWidth;
        final float panelHeight;
        final float slotSize;
        final float slotGap;
        final float storageX;
        final float storageY;
        final float hotbarX;
        final float hotbarY;
        final float armorX;
        final float armorY;
        final float offhandX;
        final float offhandY;
        final float craftX;
        final float craftY;
        final float resultX;
        final float resultY;
        final float creativeX;
        final float creativeY;
        final int creativeColumns;
        final int creativeRows;
        final int activeCreativeTab;
        final List<SlotBox> slots;

        InventoryUiLayout(float panelX, float panelY, float panelWidth, float panelHeight,
                          float slotSize, float slotGap,
                          float storageX, float storageY,
                          float hotbarX, float hotbarY,
                          float armorX, float armorY,
                          float offhandX, float offhandY,
                          float craftX, float craftY,
                          float resultX, float resultY,
                          float creativeX, float creativeY,
                          int creativeColumns, int creativeRows,
                          int activeCreativeTab,
                          List<SlotBox> slots) {
            this.panelX = panelX;
            this.panelY = panelY;
            this.panelWidth = panelWidth;
            this.panelHeight = panelHeight;
            this.slotSize = slotSize;
            this.slotGap = slotGap;
            this.storageX = storageX;
            this.storageY = storageY;
            this.hotbarX = hotbarX;
            this.hotbarY = hotbarY;
            this.armorX = armorX;
            this.armorY = armorY;
            this.offhandX = offhandX;
            this.offhandY = offhandY;
            this.craftX = craftX;
            this.craftY = craftY;
            this.resultX = resultX;
            this.resultY = resultY;
            this.creativeX = creativeX;
            this.creativeY = creativeY;
            this.creativeColumns = creativeColumns;
            this.creativeRows = creativeRows;
            this.activeCreativeTab = activeCreativeTab;
            this.slots = slots;
        }

        boolean contains(double x, double y) {
            return x >= panelX && x <= panelX + panelWidth
                && y >= panelY && y <= panelY + panelHeight;
        }

        SlotBox hitTest(double x, double y) {
            for (SlotBox slot : slots) {
                if (slot.contains(x, y)) {
                    return slot;
                }
            }
            return null;
        }
    }

    static final class SlotBox {
        final InventorySlotRef ref;
        final float x;
        final float y;
        final float size;

        SlotBox(InventorySlotRef ref, float x, float y, float size) {
            this.ref = ref;
            this.x = x;
            this.y = y;
            this.size = size;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + size
                && mouseY >= y && mouseY <= y + size;
        }
    }
}
