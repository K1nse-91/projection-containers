package dev.qianhua.containerscan.mixin;

import dev.qianhua.containerscan.PopupItemOpacity;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @ModifyConstant(method = "submitBlitFromItemAtlas", constant = @Constant(intValue = -1))
    private int containerScan$tintItem(int color, GuiItemRenderState item, float u, float v, int size, int atlasSize) {
        return PopupItemOpacity.applyTint(color, item);
    }
}
