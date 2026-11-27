package dev.qianhua.containerscan;

import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;

final class OverlayRenderer implements IRenderer {
    private final WorldContainerOverlay overlay;
    private final Matrix4f projection = new Matrix4f();
    private Camera camera;

    private OverlayRenderer(WorldContainerOverlay overlay) {
        this.overlay = overlay;
    }

    static void register(WorldContainerOverlay overlay) {
        var renderer = new OverlayRenderer(overlay);
        var events = RenderEventHandler.getInstance();
        events.registerWorldLastRenderer(renderer);
        events.registerInGameGuiRenderer(renderer);
    }

    @Override
    public void onExtractWorldLast(DeltaTracker deltaTracker, Camera camera, float partialTicks, ProfilerFiller profiler) {
        this.camera = camera;
    }

    @Override
    public void onExtractGuiOverlayPost(GuiContext graphics, float partialTicks, ProfilerFiller profiler) {
        if (overlay.shouldRender(graphics.mc()) && camera != null && camera.isInitialized()) {
            camera.getViewRotationProjectionMatrix(projection);
            overlay.render(graphics, camera.position(), projection);
        }
    }
}
