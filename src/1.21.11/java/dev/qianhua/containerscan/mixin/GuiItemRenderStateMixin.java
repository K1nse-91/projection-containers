package dev.qianhua.containerscan.mixin;

import dev.qianhua.containerscan.PopupItemOpacity;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GuiItemRenderState.class)
abstract class GuiItemRenderStateMixin implements PopupItemOpacity.Tinted {
    @Unique
    private final int containerScan$itemTint = PopupItemOpacity.currentTint();

    @Override
    public int containerScan$itemTint() {
        return containerScan$itemTint;
    }
}
