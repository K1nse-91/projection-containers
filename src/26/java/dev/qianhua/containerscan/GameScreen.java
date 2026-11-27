package dev.qianhua.containerscan;

import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class GameScreen extends Screen {
    protected GameScreen(Component title) {
        super(title);
    }

    protected final void addDrawing(Drawing drawing) {
        addRenderableOnly((graphics, x, y, delta) -> drawing.draw(GuiContext.fromGuiGraphics(graphics), x, y, delta));
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        super.extractRenderState(graphics, x, y, delta);
        drawAfterWidgets(GuiContext.fromGuiGraphics(graphics), x, y);
    }

    @Override
    public final void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        drawBackdrop(GuiContext.fromGuiGraphics(graphics));
    }

    protected abstract void drawBackdrop(GuiContext graphics);
    protected void drawAfterWidgets(GuiContext graphics, int x, int y) {}

    @FunctionalInterface
    protected interface Drawing {
        void draw(GuiContext graphics, int x, int y, float delta);
    }

    protected abstract static class GameButton extends Button {
        protected GameButton(int x, int y, int width, int height, Component label, OnPress action) {
            super(x, y, width, height, label, action, DEFAULT_NARRATION);
        }

        @Override
        protected final void extractContents(GuiGraphicsExtractor graphics, int x, int y, float delta) {
            drawButton(GuiContext.fromGuiGraphics(graphics));
        }

        protected abstract void drawButton(GuiContext graphics);
    }
}
