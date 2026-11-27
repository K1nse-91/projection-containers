package dev.qianhua.containerscan;

import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

final class ShulkerBoxPreview {
    private static final int COLUMNS = 9;
    private static final int ROWS = 3;
    private static final int SLOT_SIZE = 18;
    private static final int FRAME_PADDING = 7;

    private final NonNullList<ItemStack> slots = NonNullList.withSize(COLUMNS * ROWS, ItemStack.EMPTY);

    private ShulkerBoxPreview(ItemContainerContents contents) {
        contents.copyInto(slots);
    }

    static ShulkerBoxPreview forStack(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock) {
            return new ShulkerBoxPreview(stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
        }
        return null;
    }

    int width() {
        return COLUMNS * SLOT_SIZE + 2 * FRAME_PADDING;
    }

    int height() {
        return ROWS * SLOT_SIZE + 2 * FRAME_PADDING;
    }

    void render(GuiContext graphics, Font font, int mouseX, int mouseY) {
        var position = DefaultTooltipPositioner.INSTANCE.positionTooltip(
                graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY, width(), height());
        int x = Math.clamp(position.x(), 0, Math.max(0, graphics.guiWidth() - width()));
        int y = Math.clamp(position.y(), 0, Math.max(0, graphics.guiHeight() - height()));
        InventoryOverlay.renderInventoryBackground27(graphics, x, y, -1);
        for (int index = 0; index < slots.size(); index++) {
            ItemStack stack = slots.get(index);
            if (!stack.isEmpty()) {
                int itemX = x + FRAME_PADDING + 1 + index % COLUMNS * SLOT_SIZE;
                int itemY = y + FRAME_PADDING + 1 + index / COLUMNS * SLOT_SIZE;
                graphics.renderItem(stack, itemX, itemY);
                graphics.renderItemDecorations(font, stack, itemX, itemY);
            }
        }
    }
}
