package dev.qianhua.containerscan;

import static dev.qianhua.containerscan.ContainerScanClient.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.game.wrap.GameWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

final class WorldContainerOverlay {
    private static final int RANGE = 48;
    private static final int MAX_VISIBLE = 16;
    private static final int COLUMNS = 6;
    private static final int SLOT = 20;
    private static final int MARGIN = 5;
    private static final int TITLE_GAP = 8;
    private static final int GRID_TOP = 15;
    private static final int INLINE_HEIGHT = 23;
    private static final int MAX_CARD_WIDTH = COLUMNS * SLOT + 2 * MARGIN;
    private static final int MAX_FOCUSED_SLOTS = 54;
    private static final float FOCUS_SCALE = 1.13f;
    private static final float NEAR_CLIP = 0.15f;
    private static final float OUTLINE_PIXELS = 1.5f;
    private static final int OUTLINE_COLOR = 0xffffd60a;
    private static final int[] BOX_EDGES = {
            0, 1, 1, 3, 3, 2, 2, 0,
            4, 5, 5, 7, 7, 6, 6, 4,
            0, 4, 1, 5, 2, 6, 3, 7
    };
    private static final Comparator<Popup> NEAREST_FIRST = Comparator
            .comparingDouble((Popup popup) -> popup.distanceSquared)
            .thenComparingLong(popup -> popup.entry.pos().asLong());

    private final Map<SelectionScan.Entry, Popup> popups = new IdentityHashMap<>();
    private final List<Popup> visible = new ArrayList<>();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private final Vector4f[] outlineCorners = new Vector4f[8];
    private final Vector4f edgeStart = new Vector4f();
    private final Vector4f edgeEnd = new Vector4f();
    private SelectionScan scan;
    private Popup focusedPopup;
    private int lastRevision;

    WorldContainerOverlay() {
        for (int index = 0; index < outlineCorners.length; index++) {
            outlineCorners[index] = new Vector4f();
        }
    }

    void show(SelectionScan scan) {
        this.scan = scan;
        lastRevision = -1;
        popups.clear();
        visible.clear();
        focusedPopup = null;
    }

    boolean shouldRender(Minecraft client) {
        return scan != null && scan.isInLevel(client.level) && client.player != null
                && !GameWrap.isHideGui() && GuiUtils.getCurrentScreen() == null
                && ContainerScanClient.isContainerMode();
    }

    void render(GuiContext graphics, Vec3 origin, Matrix4fc viewProjection) {
        var client = graphics.mc();
        refreshPopups(client.font);
        if (popups.isEmpty()) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        projection.set(viewProjection);
        visible.clear();
        for (Popup popup : popups.values()) {
            if (popup.entry.isPresent() && project(popup, origin, width, height)) {
                visible.add(popup);
            }
        }
        if (visible.isEmpty()) {
            focusedPopup = null;
            return;
        }

        visible.sort(NEAREST_FIRST);
        int count = Math.min(visible.size(), MAX_VISIBLE);
        focusedPopup = focusAt(visible, count, focusedPopup, width, height);
        drawPopups(graphics, client.font, origin, width, height, count, client.getWindow().getGuiScale());
    }

    private void drawPopups(GuiContext graphics, Font font, Vec3 origin, int width, int height,
                            int count, int guiScale) {
        boolean hasFocus = focusedPopup != null;
        float opacity = hasFocus ? PopupItemOpacity.DIMMED : 1;
        graphics.nextStratum();
        int previousTint = hasFocus ? PopupItemOpacity.dim() : -1;
        try {
            for (int index = count - 1; index >= 0; index--) {
                Popup popup = visible.get(index);
                if (popup != focusedPopup) {
                    draw(graphics, font, popup, width, height, false, opacity);
                }
            }
        } finally {
            if (hasFocus) {
                PopupItemOpacity.restore(previousTint);
            }
        }
        if (hasFocus) {
            graphics.nextStratum();
            draw(graphics, font, focusedPopup, width, height, true, 1);
            // A separate HUD stratum keeps the projected outline above every popup.
            graphics.nextStratum();
            drawFocusedOutline(graphics, focusedPopup.entry.outline(), origin, width, height,
                    OUTLINE_PIXELS / Math.max(1, guiScale));
        }
    }

    private void refreshPopups(Font font) {
        if (lastRevision == scan.revision()) {
            return;
        }
        lastRevision = scan.revision();
        for (var entry : scan.entries()) {
            var status = entry.snapshot().status();
            if ((status == SelectionScan.Status.READ || status == SelectionScan.Status.PARTIAL)
                    && !popups.containsKey(entry)) {
                popups.put(entry, new Popup(entry, font));
            }
        }
    }

    private boolean project(Popup popup, Vec3 origin, int width, int height) {
        var pos = popup.entry.anchor();
        double x = pos.x() - origin.x();
        double y = pos.y() - origin.y();
        double z = pos.z() - origin.z();
        double distance = x * x + y * y + z * z;
        if (distance > RANGE * RANGE) {
            return false;
        }
        projection.transform(clip.set((float) x, (float) y, (float) z, 1));
        if (!(clip.w() > NEAR_CLIP)) {
            return false;
        }
        float anchorX = screenX(clip, width);
        float anchorY = screenY(clip, height);
        float distanceScale = Math.clamp(8.0f / (float) Math.sqrt(distance), 0.50f, 0.98f);
        float scale = fitScale(distanceScale, popup.compact, width, height);
        float halfWidth = popup.compact.width() * scale * 0.5f;
        float cardHeight = popup.compact.height() * scale;
        if (anchorX + halfWidth < 0 || anchorX - halfWidth > width || anchorY < 0 || anchorY - cardHeight > height) {
            return false;
        }
        popup.distanceSquared = distance;
        popup.scale = scale;
        popup.anchorX = anchorX;
        popup.anchorY = anchorY;
        popup.left = left(anchorX, popup.compact.width() * scale, width);
        popup.top = top(anchorY, cardHeight, height);
        return true;
    }

    static Popup focusAt(List<Popup> visible, int count, Popup previous, int width, int height) {
        float x = width * 0.5f;
        float y = height * 0.5f;
        Popup selected = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        boolean previousVisible = false;
        for (int index = 0; index < count; index++) {
            Popup popup = visible.get(index);
            previousVisible |= popup == previous;
            float cardWidth = popup.compact.width() * popup.scale;
            float cardHeight = popup.compact.height() * popup.scale;
            if (contains(x, y, popup.left, popup.top, cardWidth, cardHeight)) {
                float dx = x - (popup.left + cardWidth * 0.5f);
                float dy = y - (popup.top + cardHeight * 0.5f);
                float distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    selected = popup;
                }
            }
        }
        if (selected == null && previousVisible) {
            Card expanded = previous.expanded;
            float scale = fitScale(previous.scale * FOCUS_SCALE, expanded, width, height);
            float cardWidth = expanded.width() * scale;
            float cardHeight = expanded.height() * scale;
            if (contains(x, y, left(previous.anchorX, cardWidth, width), top(previous.anchorY, cardHeight, height),
                    cardWidth, cardHeight)) {
                return previous;
            }
        }
        return selected;
    }

    private static boolean contains(float x, float y, float left, float top, float width, float height) {
        return x >= left && x <= left + width && y >= top && y <= top + height;
    }

    private void draw(GuiContext graphics, Font font, Popup popup, int width, int height,
                      boolean focused, float opacity) {
        Card card = focused ? popup.expanded : popup.compact;
        float scale = focused ? fitScale(popup.scale * FOCUS_SCALE, card, width, height) : popup.scale;
        float x = focused ? left(popup.anchorX, card.width() * scale, width) : popup.left;
        float y = focused ? top(popup.anchorY, card.height() * scale, height) : popup.top;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);

        GuiTheme.rounded(graphics, 0, 0, card.width(), card.height(),
                ARGB.multiplyAlpha(focused ? 0xf229292d : 0xd91c1c20, opacity));
        int titleColor = ARGB.multiplyAlpha(GuiTheme.TEXT, opacity);
        int detailColor = ARGB.multiplyAlpha(GuiTheme.SECONDARY, opacity);
        if (card.slots() <= 1) {
            drawInline(graphics, font, popup, card, titleColor, detailColor, opacity);
        } else {
            graphics.drawString(font, card.title(), MARGIN + 1, 4, titleColor, false);
            drawGrid(graphics, font, popup, card, detailColor, opacity);
        }
        pose.popMatrix();
    }

    private void drawInline(GuiContext graphics, Font font, Popup popup, Card card, int titleColor,
                            int detailColor, float opacity) {
        int labelY = (card.height() - 9) / 2;
        int detailX = card.width() - MARGIN - 1 - card.detailWidth();
        graphics.drawString(font, card.title(), MARGIN + 1, labelY, titleColor, false);
        if (card.slots() == 0) {
            graphics.drawString(font, card.emptyLabel(), detailX, labelY, detailColor, false);
        } else {
            Icon item = popup.items.getFirst();
            drawIcon(graphics, item, detailX, 3, opacity);
            if (item.amount() != null) {
                graphics.drawString(font, item.amount(), detailX + 20, labelY, detailColor, false);
            }
        }
    }

    private void drawGrid(GuiContext graphics, Font font, Popup popup, Card card, int detailColor,
                          float opacity) {
        int firstCell = (card.width() - Math.min(card.slots(), COLUMNS) * SLOT) / 2;
        for (int index = 0; index < card.slots(); index++) {
            int cellX = firstCell + index % COLUMNS * SLOT;
            int cellY = GRID_TOP + index / COLUMNS * SLOT;
            if (index == card.slots() - 1 && card.overflow() != null) {
                graphics.drawString(font, card.overflow(), cellX + 1, cellY + 5, detailColor, false);
            } else {
                var item = popup.items.get(index);
                drawIcon(graphics, item, cellX + 2, cellY + 1, opacity);
                if (item.amount() != null) {
                    graphics.drawString(font, item.amount(), cellX + SLOT - 1 - item.amountWidth(), cellY + 10,
                            ARGB.multiplyAlpha(GuiTheme.TEXT, opacity), true);
                }
            }
        }
    }

    private static void drawIcon(GuiContext graphics, Icon item, int x, int y, float opacity) {
        ItemStack stack = item.stack();
        graphics.renderItem(stack, x, y);
        if (stack.isBarVisible()) {
            // Match the game's durability bar while keeping it at the same opacity as the icon.
            graphics.fill(x + 2, y + 13, x + 15, y + 15, ARGB.multiplyAlpha(0xff000000, opacity));
            graphics.fill(x + 2, y + 13, x + 2 + stack.getBarWidth(), y + 14,
                    ARGB.multiplyAlpha(ARGB.opaque(stack.getBarColor()), opacity));
        }
    }

    private void drawFocusedOutline(GuiContext graphics, AABB bounds, Vec3 origin, int width, int height,
                                    float lineWidth) {
        for (int index = 0; index < outlineCorners.length; index++) {
            double x = ((index & 1) == 0 ? bounds.minX : bounds.maxX) - origin.x();
            double y = ((index & 2) == 0 ? bounds.minY : bounds.maxY) - origin.y();
            double z = ((index & 4) == 0 ? bounds.minZ : bounds.maxZ) - origin.z();
            projection.transform(outlineCorners[index].set((float) x, (float) y, (float) z, 1));
        }
        graphics.enableScissor(0, 0, width, height);
        for (int index = 0; index < BOX_EDGES.length; index += 2) {
            edgeStart.set(outlineCorners[BOX_EDGES[index]]);
            edgeEnd.set(outlineCorners[BOX_EDGES[index + 1]]);
            if (clipOutlineEdge(edgeStart, edgeEnd)) {
                drawOutlineLine(graphics, screenX(edgeStart, width), screenY(edgeStart, height),
                        screenX(edgeEnd, width), screenY(edgeEnd, height), lineWidth);
            }
        }
        graphics.disableScissor();
    }

    static boolean clipOutlineEdge(Vector4f start, Vector4f end) {
        if (start.w() < NEAR_CLIP) {
            if (end.w() < NEAR_CLIP) {
                return false;
            }
            start.lerp(end, (NEAR_CLIP - start.w()) / (end.w() - start.w()));
        } else if (end.w() < NEAR_CLIP) {
            end.lerp(start, (NEAR_CLIP - end.w()) / (start.w() - end.w()));
        }
        return true;
    }

    private static void drawOutlineLine(GuiContext graphics, float x, float y, float endX, float endY,
                                        float lineWidth) {
        float dx = endX - x;
        float dy = endY - y;
        int length = (int) Math.ceil(Math.hypot(dx, dy));
        if (length == 0) {
            return;
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.rotate((float) Math.atan2(dy, dx));
        pose.translate(0, -lineWidth * 0.5f);
        pose.scale(1, lineWidth);
        graphics.fill(0, 0, length, 1, OUTLINE_COLOR);
        pose.popMatrix();
    }

    private static float screenX(Vector4f point, int width) {
        return (0.5f + 0.5f * point.x() / point.w()) * width;
    }

    private static float screenY(Vector4f point, int height) {
        return (0.5f - 0.5f * point.y() / point.w()) * height;
    }

    private static float fitScale(float scale, Card card, int width, int height) {
        return Math.min(scale, Math.min(width / (float) card.width(), height / (float) card.height()));
    }

    private static float left(float anchor, float cardWidth, int width) {
        return Math.clamp(anchor - cardWidth * 0.5f, 0, Math.max(0, width - cardWidth));
    }

    private static float top(float anchor, float cardHeight, int height) {
        return Math.clamp(anchor - cardHeight, 0, Math.max(0, height - cardHeight));
    }

    static final class Popup {
        private final SelectionScan.Entry entry;
        private final List<Icon> items;
        private final Card compact;
        private final Card expanded;
        private double distanceSquared;
        private float scale;
        private float anchorX;
        private float anchorY;
        private float left;
        private float top;

        Popup(SelectionScan.Entry entry, Font font) {
            this.entry = entry;
            boolean partial = entry.snapshot().status() == SelectionScan.Status.PARTIAL;
            Component name = partial ? text("partial_card_title", entry.name()) : entry.name();
            Component emptyLabel = text(partial ? "partial_empty" : "empty");
            items = entry.snapshot().items().stream().map(item -> Icon.from(item, font)).toList();
            compact = Card.fit(font, name, emptyLabel, items, COLUMNS);
            expanded = Card.fit(font, name, emptyLabel, items, MAX_FOCUSED_SLOTS);
        }
    }

    record Card(int width, int height, int slots, FormattedCharSequence title, String overflow,
                int detailWidth, FormattedCharSequence emptyLabel) {
        static Card fit(Font font, Component name, Component emptyLabel, List<Icon> items, int limit) {
            int count = items.size();
            int slots = Math.min(count, limit);
            int titleWidth = font.width(name);
            if (slots <= 1) {
                Icon item = slots == 1 ? items.getFirst() : null;
                int detailWidth = item == null ? Math.min(font.width(emptyLabel), 62) : item.inlineWidth();
                int trailing = 2 * (MARGIN + 1) + TITLE_GAP + detailWidth;
                int width = Math.min(MAX_CARD_WIDTH, titleWidth + trailing);
                return new Card(width, INLINE_HEIGHT, slots, GuiTheme.fit(font, name, width - trailing), null,
                        detailWidth, item == null ? GuiTheme.fit(font, emptyLabel, detailWidth) : null);
            }
            String overflow = count > slots ? "+" + (count - slots + 1) : null;
            int gridWidth = Math.min(slots, COLUMNS) * SLOT + 2 * MARGIN;
            int width = Math.min(MAX_CARD_WIDTH, Math.max(gridWidth, titleWidth + 2 * (MARGIN + 1)));
            int height = GRID_TOP + (slots + COLUMNS - 1) / COLUMNS * SLOT + MARGIN;
            return new Card(width, height, slots, GuiTheme.fit(font, name, width - 2 * (MARGIN + 1)), overflow, 0, null);
        }
    }

    private record Icon(ItemStack stack, String amount, int amountWidth) {
        static Icon from(SelectionScan.ItemCount item, Font font) {
            String amount = item.count() > 1 ? Long.toString(item.count()) : null;
            return new Icon(item.stack(), amount, amount == null ? 0 : font.width(amount));
        }

        int inlineWidth() {
            return amount == null ? 16 : 20 + amountWidth;
        }
    }
}
