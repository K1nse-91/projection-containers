package dev.qianhua.containerscan;

import static dev.qianhua.containerscan.ContainerScanClient.text;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class ScanScreen extends ListScreen {
    private final ContainerScanClient mod;
    private final List<PreviewRow> previews = new ArrayList<>();
    private final PagedList containers = new PagedList();
    private final PagedList items = new PagedList();
    private SelectionScan scan;
    private SelectionScan.Entry selected;
    private int shownRevision;

    ScanScreen(ContainerScanClient mod) {
        super(text("title"));
        this.mod = mod;
        scan = mod.scanForScreen();
    }

    @Override
    protected void init() {
        previews.clear();
        shownRevision = scan.revision();
        int count = selected == null ? scan.entries().size() : selected.snapshot().items().size();
        Component notice = notice();
        PagedList position = selected == null ? containers : items;
        arrange(position, count, selected == null ? 24 : 20, notice);
        heading(selected == null ? title : selected.name(), this::status, notice);

        if (selected == null) {
            addContainerRows(position.first(), position.end());
            if (count == 0 && scan.hasSelection() && !mod.containerTypes().isEmpty()) {
                emptyRow(text("no_containers"));
            }
        } else if (count == 0) {
            emptyRow(summary(selected.snapshot()));
        } else {
            addItemRows(position.first(), position.end());
        }

        pagination();
        if (selected == null) {
            footer(text("settings"), 0, false, () -> GuiBase.openGui(new TypesScreen(this, mod)));
        } else {
            footer(text("back_to_containers"), 0, false, this::showOverview);
        }
        footer(text("rescan"), 1, false, this::rescan);
        footer(text("close"), 2, true, this::onClose);
    }

    private Component notice() {
        if (selected != null && selected.snapshot().status() == SelectionScan.Status.PARTIAL) {
            return text("partial_detail");
        }
        if (scan.notice() != null) {
            return scan.notice();
        }
        return scan.unloadedChunks() ? text("unloaded") : null;
    }

    private void addContainerRows(int start, int end) {
        for (int index = start; index < end; index++) {
            var entry = scan.entries().get(index);
            var name = entry.name();
            var coordinates = Component.literal(entry.pos().toShortString());
            var detail = summary(entry.snapshot());
            var icon = new ItemStack(entry.block().asItem());
            row(text("container_row", name, coordinates, detail), index - start, button -> {
                selected = entry;
                items.reset();
                rebuildWidgets();
            }, (graphics, button) -> paintContainer(graphics, button, icon, name, coordinates, detail));
        }
    }

    private void paintContainer(GuiContext graphics, Button button, ItemStack icon,
                                Component name, Component coordinates, Component detail) {
        int x = button.getX();
        int y = button.getY();
        int w = button.getWidth();
        graphics.renderItem(icon, x + 3, y + 4);
        int detailWidth = Math.min(84, w / 3);
        int nameWidth = w - detailWidth - 34;
        GuiTheme.label(graphics, font, name, x + 25, y + 2, nameWidth, GuiTheme.TEXT, 0);
        GuiTheme.label(graphics, font, coordinates, x + 25, y + 12, nameWidth, GuiTheme.SECONDARY, 0);
        GuiTheme.label(graphics, font, detail, x + w - detailWidth - 3, y + 7,
                detailWidth, GuiTheme.SECONDARY, 2);
    }

    private void addItemRows(int start, int end) {
        for (int index = start; index < end; index++) {
            var item = selected.snapshot().items().get(index);
            int x = list.left();
            int y = list.rowY(index - start);
            int w = list.width();
            int h = list.rowHeight();
            var preview = ShulkerBoxPreview.forStack(item.stack());
            if (preview != null) {
                previews.add(new PreviewRow(x, y, w, h, preview));
            }
            addDrawing((graphics, mouseX, mouseY, delta) -> {
                boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
                graphics.renderItem(item.stack(), x + 3, y + 2);
                GuiTheme.label(graphics, font, item.stack().getHoverName(), x + 25, y + 5, w - 94, GuiTheme.TEXT, 0);
                GuiTheme.label(graphics, font, text("quantity", item.count()), x + w - 65, y + 5, 62,
                        GuiTheme.SECONDARY, 2);
                if (preview == null && hovered) {
                    graphics.getGuiGraphics().setTooltipForNextFrame(font, item.stack(), mouseX, mouseY);
                }
            });
        }
    }

    @Override
    protected void drawAfterWidgets(GuiContext graphics, int mouseX, int mouseY) {
        for (var preview : previews) {
            if (preview.contains(mouseX, mouseY)) {
                graphics.nextStratum();
                preview.contents().render(graphics, font, mouseX, mouseY);
                break;
            }
        }
    }

    private Component status() {
        if (selected != null) {
            return Component.literal(selected.pos().toShortString());
        }
        if (!scan.hasSelection()) {
            return text("no_selection");
        }
        if (mod.containerTypes().isEmpty()) {
            return text("no_types");
        }
        if (!scan.isFinished()) {
            return text("scanning", scan.completed(), scan.entries().size());
        }
        return text("complete", scan.entries().size(), scan.unavailable());
    }

    private static Component summary(SelectionScan.Snapshot snapshot) {
        return switch (snapshot.status()) {
            case WAITING -> text("waiting");
            case PARTIAL -> text("partial");
            case UNAVAILABLE -> text("unavailable");
            case LOOT -> text("loot");
            case READ -> snapshot.items().isEmpty()
                    ? text("empty") : text("stack_total", snapshot.items().stream()
                            .mapToLong(SelectionScan.ItemCount::count).sum());
        };
    }

    private void showOverview() {
        selected = null;
        rebuildWidgets();
    }

    void rescan() {
        scan = mod.startScan();
        selected = null;
        containers.reset();
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (shownRevision != scan.revision()) {
            rebuildWidgets();
        }
    }

    private record PreviewRow(int x, int y, int width, int height, ShulkerBoxPreview contents) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
}
