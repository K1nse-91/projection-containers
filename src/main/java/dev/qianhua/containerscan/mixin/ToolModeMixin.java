package dev.qianhua.containerscan.mixin;

import fi.dy.masa.litematica.tool.ToolMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ToolMode.class, remap = false)
enum ToolModeMixin {
    LITEMATICA_CONTAINER_SCAN("litematica_container_scan", "container_scan.title", false, false);

    @Shadow
    ToolModeMixin(String configName, String translationKey, boolean creativeOnly, boolean usesSchematic) {
    }
}
