package dev.qianhua.containerscan.mixin;

import dev.qianhua.containerscan.PopupItemOpacity;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(PictureInPictureRenderer.class)
abstract class PictureInPictureRendererMixin {
    @ModifyConstant(method = "blitTexture", constant = @Constant(intValue = -1))
    private int containerScan$tintOversizedItem(int color, PictureInPictureRenderState state, GuiRenderState gui) {
        return state instanceof OversizedItemRenderState item
                ? PopupItemOpacity.applyTint(color, item.guiItemRenderState()) : color;
    }
}
