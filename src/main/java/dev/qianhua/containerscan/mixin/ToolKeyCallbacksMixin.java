package dev.qianhua.containerscan.mixin;

import dev.qianhua.containerscan.ContainerScanClient;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys", remap = false)
abstract class ToolKeyCallbacksMixin {
    @Inject(method = "onKeyAction", at = @At("HEAD"), cancellable = true)
    private void containerScan$handleToolClick(KeyAction action, IKeybind key, CallbackInfoReturnable<Boolean> result) {
        Corner corner;
        if (key == Hotkeys.TOOL_PLACE_CORNER_1.getKeybind()) {
            corner = Corner.CORNER_1;
        } else if (key == Hotkeys.TOOL_PLACE_CORNER_2.getKeybind()) {
            corner = Corner.CORNER_2;
        } else if (key == Hotkeys.TOOL_SELECT_ELEMENTS.getKeybind()) {
            corner = Corner.NONE;
        } else {
            return;
        }
        if (ContainerScanClient.handleToolClick(corner)) {
            result.setReturnValue(true);
        }
    }
}
