package dev.qianhua.containerscan;

import static dev.qianhua.containerscan.ContainerScanClient.text;

import java.util.function.Consumer;
import java.util.function.Supplier;

import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

abstract class ListScreen extends GameScreen {
    private static final int HEADER_HEIGHT = 28;
    private static final int NOTICE_HEIGHT = 12;
    private static final int ACTION_HEIGHT = 17;
    private static final int FOOTER_GAP = 6;

    protected ListLayout list;
    private PagedList pages;

    protected ListScreen(Component title) {
        super(title);
    }

    protected void arrange(PagedList pages, int entries, int rowHeight, Component notice) {
        list = ListLayout.fit(width, height, entries, rowHeight, notice != null);
        this.pages = pages;
        pages.resize(entries, list.rows());
    }

    protected void heading(Component title, Supplier<Component> subtitle, Component notice) {
        Component label = title.copy().withStyle(ChatFormatting.BOLD);
        addDrawing((graphics, mouseX, mouseY, delta) -> {
            int x = list.left();
            int y = list.top();
            GuiTheme.label(graphics, font, label, x, y, list.width(), GuiTheme.TEXT, 0);
            GuiTheme.label(graphics, font, subtitle.get(), x, y + 14, list.width(), GuiTheme.SECONDARY, 0);
            if (notice != null) {
                GuiTheme.label(graphics, font, notice, x, y + 26, list.width(), GuiTheme.WARNING, 0);
            }
        });
    }

    protected Button row(Component title, int index, Consumer<Button> click, RowPainter painter) {
        return addRenderableWidget(new ListButton(list.left(), list.rowY(index), list.width(),
                list.rowHeight(), title, click, (graphics, button) -> {
                    if (button.isHoveredOrFocused()) {
                        int x = button.getX();
                        int y = button.getY();
                        graphics.fill(x, y + 5, x + 1, y + button.getHeight() - 5, GuiTheme.TEXT);
                    }
                    painter.draw(graphics, button);
                }));
    }

    protected void footer(Component label, int index, boolean primary, Runnable action) {
        Component caption = primary ? label.copy().withStyle(ChatFormatting.BOLD) : label;
        int width = Math.max(1, Math.min(list.width() / 3 - 2, font.width(caption) + 8));
        int x = list.left() + index * (list.width() - width) / 2;
        textButton(caption, x, list.actionY(), width, action);
    }

    protected void pagination() {
        if (pages.pages() == 1) {
            return;
        }
        int x = list.left();
        int y = list.navigationY();
        int width = Math.max(1, Math.min(60, list.width() / 3));
        textButton(text("previous"), x, y, width, () -> changePage(-1)).active = pages.page() > 0;
        textButton(text("next"), x + list.width() - width, y, width, () -> changePage(1)).active =
                pages.page() + 1 < pages.pages();
        addDrawing((graphics, mouseX, mouseY, delta) -> GuiTheme.label(graphics, font,
                text("page", pages.page() + 1, pages.pages()), x + width, y + 4,
                list.width() - width * 2, GuiTheme.SECONDARY, 1));
    }

    protected void emptyRow(Component message) {
        addDrawing((graphics, mouseX, mouseY, delta) -> GuiTheme.label(graphics, font, message,
                list.left(), list.rowY(0) + (list.rowHeight() - 9) / 2,
                list.width(), GuiTheme.SECONDARY, 0));
    }

    private Button textButton(Component label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(new ListButton(x, y, width, ACTION_HEIGHT, label, ignored -> action.run(),
                (graphics, button) -> {
                    Component message = button.getMessage();
                    if (button.active && button.isHoveredOrFocused()) {
                        message = message.copy().withStyle(ChatFormatting.UNDERLINE);
                    }
                    GuiTheme.label(graphics, font, message, button.getX(), button.getY() + 4, button.getWidth(),
                            button.active ? GuiTheme.TEXT : GuiTheme.DISABLED, 1);
                }));
    }

    private void changePage(int direction) {
        if (pages.changePage(direction)) {
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (pages != null && pages.scroll(vertical)) {
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    protected void drawBackdrop(GuiContext graphics) {
        graphics.fill(0, 0, width, height, GuiTheme.SCRIM);
        if (list != null) {
            int top = list.rowY(0);
            int bottom = list.rowY(list.rows());
            graphics.fill(list.left(), top - 1, list.left() + list.width(), top, GuiTheme.SEPARATOR);
            graphics.fill(list.left(), bottom, list.left() + list.width(), bottom + 1, GuiTheme.SEPARATOR);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @FunctionalInterface
    protected interface RowPainter {
        void draw(GuiContext graphics, Button button);
    }

    private static final class ListButton extends GameButton {
        private final RowPainter painter;

        ListButton(int x, int y, int width, int height, Component title, Consumer<Button> click, RowPainter painter) {
            super(x, y, width, height, title, click::accept);
            this.painter = painter;
        }

        @Override
        protected void drawButton(GuiContext graphics) {
            painter.draw(graphics, this);
        }
    }

    protected record ListLayout(int left, int top, int width, int header, int rowHeight, int rows, boolean paginated) {
        static ListLayout fit(int viewWidth, int viewHeight, int entries, int rowHeight, boolean notice) {
            int sideMargin = Math.min(16, viewWidth / 8);
            int width = Math.max(1, Math.min(336, viewWidth - sideMargin * 2));
            int top = Math.clamp(viewHeight / 18, 3, 18);
            int header = HEADER_HEIGHT + (notice ? NOTICE_HEIGHT : 0);
            boolean paginated = PagedList.pageCount(entries) > 1;
            int footer = FOOTER_GAP + (paginated ? 2 : 1) * ACTION_HEIGHT + 6;
            int capacity = (viewHeight - top - header - footer) / rowHeight;
            int rows = Math.max(1, Math.min(Math.min(entries, PagedList.PAGE_SIZE), capacity));
            return new ListLayout((viewWidth - width) / 2, top, width, header, rowHeight, rows, paginated);
        }

        int rowY(int index) {
            return top + header + index * rowHeight;
        }

        int navigationY() {
            return rowY(rows) + FOOTER_GAP;
        }

        int actionY() {
            return navigationY() + (paginated ? ACTION_HEIGHT : 0);
        }
    }
}
