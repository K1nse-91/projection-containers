package dev.qianhua.containerscan.mixin;

import dev.qianhua.containerscan.ContainerScanClient;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.ToolHud;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ToolHud.class, remap = false)
abstract class ToolHudMixin extends InfoHud {
    @Inject(method = "updateHudText", at = @At("RETURN"))
    private void containerScan$hideSelectionNames(CallbackInfo callback) {
        if (!ContainerScanClient.isContainerMode()) {
            return;
        }
        var selections = DataManager.getSelectionManager();
        var selection = selections.getCurrentSelection();
        if (selection == null) {
            return;
        }
        String area = selections.getSelectionMode() == SelectionMode.NORMAL
                ? "selected_area_normal" : "selected_area_simple";
        removeLine(area, selection.getName());
        String subregion = selection.getCurrentSubRegionBoxName();
        if (subregion != null) {
            removeLine("selected_sub_region", subregion);
        }
    }

    private void removeLine(String label, String value) {
        lineList.remove(StringUtils.translate(
                "litematica.hud.area_selection." + label, GuiBase.TXT_GREEN + value + GuiBase.TXT_RST));
    }
}
