package dev.qianhua.containerscan;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.network.IClientPayloadData;
import fi.dy.masa.malilib.util.GuiUtils;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

public final class ContainerScanClient implements ClientModInitializer {
    private static final int SELECTION_SETTLE_TICKS = 3;
    private static ContainerScanClient instance;

    private final WorldContainerOverlay worldOverlay = new WorldContainerOverlay();
    private ScanSettings settings;
    private SelectionScan activeScan;
    private SelectionScan currentScan;
    private ScanKey observedSelection;
    private int settleTicks;
    private ServuxInventory servux;

    @Override
    public void onInitializeClient() {
        servux = new ServuxInventory();
        servux.register();
        settings = ScanSettings.load();
        TickHandler.getInstance().registerClientTickHandler(this::tick);
        OverlayRenderer.register(worldOverlay);
        instance = this;
    }

    Set<Identifier> containerTypes() {
        return settings.types();
    }

    Map<Identifier, Block> detectedContainerTypes() {
        return currentScan == null ? Map.of() : currentScan.detectedTypes();
    }

    void saveSettings(Set<Identifier> types) throws IOException {
        ScanSettings updated = new ScanSettings(types);
        updated.save();
        settings = updated;
    }

    SelectionScan startScan() {
        var client = Minecraft.getInstance();
        return startScan(client, scanKey(client));
    }

    SelectionScan scanForScreen() {
        var client = Minecraft.getInstance();
        var key = scanKey(client);
        return currentScan != null && key.equals(observedSelection) ? currentScan : startScan(client, key);
    }

    private SelectionScan startScan(Minecraft client, ScanKey key) {
        finishActiveScan();
        observedSelection = key;
        settleTicks = 0;
        currentScan = new SelectionScan(client, key.bounds(), key.types(), servux);
        activeScan = currentScan;
        worldOverlay.show(currentScan);
        return currentScan;
    }

    private ScanKey scanKey(Minecraft client) {
        var selection = DataManager.getSelectionManager().getCurrentSelection();
        return new ScanKey(client.level, SelectionScan.selectionBounds(selection), settings.types());
    }

    private void finishActiveScan() {
        activeScan = null;
        servux.clearRequests();
    }

    private void clearScan() {
        finishActiveScan();
        currentScan = null;
        observedSelection = null;
        worldOverlay.show(null);
    }

    public static boolean handleToolClick(Corner corner) {
        if (instance == null || !isContainerMode()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue() || !canUseTool()) {
            return false;
        }
        if (corner == Corner.NONE) {
            instance.openScan();
        } else {
            var client = Minecraft.getInstance();
            var selections = DataManager.getSelectionManager();
            var selection = selections.getCurrentSelection();
            var box = selection == null ? null : selection.getSelectedSubRegionBox();
            if (box != null) {
                var previous = box.getPosition(corner);
                selection.setOriginSelected(false);
                selections.setPositionOfCurrentSelectionToRayTrace(client, corner, false, 200);
                if (!Objects.equals(previous, box.getPosition(corner))) {
                    instance.clearScan();
                }
            }
        }
        return true;
    }

    public static boolean isContainerMode() {
        return DataManager.getToolMode().name().equals("LITEMATICA_CONTAINER_SCAN");
    }

    public static void observeMiniHudPacket(Identifier channel, IClientPayloadData packet) {
        if (instance != null && instance.activeScan != null && ServuxEntityPacket.TYPE.id().equals(channel)) {
            instance.servux.receiveMiniHud(packet);
        }
    }

    private static boolean canUseTool() {
        var client = Minecraft.getInstance();
        return client.level != null && client.player != null && GuiUtils.getCurrentScreen() == null
                && Configs.Generic.TOOL_ITEM_ENABLED.getBooleanValue() && EntityUtils.hasToolItem(client.player);
    }

    private void openScan() {
        GuiBase.openGui(new ScanScreen(this));
    }

    private void tick(Minecraft client) {
        if (client.level == null || client.player == null || !isContainerMode()
                || !Configs.Generic.TOOL_ITEM_ENABLED.getBooleanValue()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()) {
            if (observedSelection != null) {
                clearScan();
            }
            return;
        }
        var key = scanKey(client);
        if (!key.equals(observedSelection)) {
            clearScan();
            observedSelection = key;
            settleTicks = SELECTION_SETTLE_TICKS;
        }
        if (currentScan == null && --settleTicks <= 0) {
            startScan(client, key);
        }
        if (activeScan != null) {
            activeScan.tick(client, EntityDataManager.getInstance());
            if (activeScan.isFinished()) {
                finishActiveScan();
            }
        }
    }

    static Component text(String key, Object... args) {
        return Component.translatable("container_scan." + key, args);
    }

    private record ScanKey(ClientLevel level, List<SelectionScan.Bounds> bounds, Set<Identifier> types) {}
}
