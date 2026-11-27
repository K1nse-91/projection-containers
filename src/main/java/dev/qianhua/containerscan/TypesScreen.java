package dev.qianhua.containerscan;

import static dev.qianhua.containerscan.ContainerScanClient.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class TypesScreen extends ListScreen {
    private final ScanScreen parent;
    private final ContainerScanClient mod;
    private final LinkedHashSet<Identifier> edited;
    private final List<TypeOption> options;
    private final PagedList position = new PagedList();
    private Component notice;

    TypesScreen(ScanScreen parent, ContainerScanClient mod) {
        super(text("settings"));
        this.parent = parent;
        this.mod = mod;
        edited = new LinkedHashSet<>(mod.containerTypes());
        options = catalogue(edited, mod.detectedContainerTypes());
    }

    static List<TypeOption> catalogue(Set<Identifier> configured, Map<Identifier, Block> detected) {
        Set<Identifier> defaults = ScanSettings.defaults();
        var options = new ArrayList<TypeOption>();
        defaults.forEach(id -> options.add(TypeOption.from(id, detected)));
        var extra = new LinkedHashSet<>(configured);
        extra.addAll(detected.keySet());
        extra.removeAll(defaults);
        var additional = extra.stream().map(id -> TypeOption.from(id, detected))
                .sorted(Comparator.comparing(option -> option.name().getString())).toList();
        options.addAll(additional);
        return List.copyOf(options);
    }

    @Override
    protected void init() {
        arrange(position, options.size(), 20, notice);
        heading(title, () -> text("enabled_types", edited.size(), options.size()), notice);

        int start = position.first();
        int end = position.end();
        for (int index = start; index < end; index++) {
            var option = options.get(index);
            row(accessibleName(option), index - start, button -> {
                if (!edited.add(option.id())) {
                    edited.remove(option.id());
                }
                button.setMessage(accessibleName(option));
            }, (graphics, button) -> paintOption(graphics, button, option));
        }

        pagination();
        footer(text("defaults"), 0, false, this::restoreDefaults);
        footer(text("cancel"), 1, false, this::onClose);
        footer(text("save"), 2, true, this::save);
    }

    private Component accessibleName(TypeOption option) {
        return text("type_state", option.name(), stateLabel(edited.contains(option.id())));
    }

    private void paintOption(GuiContext graphics, Button button, TypeOption option) {
        int x = button.getX();
        int y = button.getY();
        int w = button.getWidth();
        boolean enabled = edited.contains(option.id());
        if (!option.icon().isEmpty()) {
            graphics.renderItem(option.icon(), x + 3, y + 2);
        }
        GuiTheme.label(graphics, font, option.name(), x + 25, y + 5, w - 70, GuiTheme.TEXT, 0);
        Component state = stateLabel(enabled);
        if (button.isHoveredOrFocused()) {
            state = state.copy().withStyle(ChatFormatting.UNDERLINE);
        }
        GuiTheme.label(graphics, font, state, x + w - 39, y + 5, 36,
                enabled ? GuiTheme.TEXT : GuiTheme.SECONDARY, 2);
    }

    private static Component stateLabel(boolean enabled) {
        return text(enabled ? "type_on" : "type_off");
    }

    private void restoreDefaults() {
        edited.clear();
        edited.addAll(ScanSettings.defaults());
        notice = null;
        position.reset();
        rebuildWidgets();
    }

    private void save() {
        try {
            mod.saveSettings(edited);
            parent.rescan();
            onClose();
        } catch (IOException error) {
            ScanSettings.LOGGER.warn("Could not save container types", error);
            notice = text("save_failed");
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        GuiBase.openGui(parent);
    }

    record TypeOption(Identifier id, Component name, ItemStack icon) {
        static TypeOption from(Identifier id, Map<Identifier, Block> detected) {
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElseGet(() -> detected.get(id));
            if (block != null) {
                return new TypeOption(id, block.getName(), new ItemStack(block.asItem()));
            }
            String path = id.getPath().replace('_', ' ').replace('/', ' ');
            String fallback = path.isEmpty() ? id.getNamespace()
                    : path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
            return new TypeOption(id, Component.translatableWithFallback(id.toLanguageKey("block_entity"), fallback),
                    ItemStack.EMPTY);
        }
    }
}
