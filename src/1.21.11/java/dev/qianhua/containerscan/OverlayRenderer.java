package dev.qianhua.containerscan;

import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

final class OverlayRenderer implements IRenderer {
    private final WorldContainerOverlay overlay;
    private final Matrix4f projection = new Matrix4f();
    private final Quaternionf viewRotation = new Quaternionf();
    private Vec3 origin;

    private OverlayRenderer(WorldContainerOverlay overlay) {
        this.overlay = overlay;
    }

    static void register(WorldContainerOverlay overlay) {
        var renderer = new OverlayRenderer(overlay);
        var events = RenderEventHandler.getInstance();
        events.registerWorldLastRenderer(renderer);
        events.registerGameOverlayRenderer(renderer);
    }

    @Override
    public void onRenderWorldLastAdvanced(RenderTarget target, Matrix4f renderProjection, Matrix4f cullingProjection,
                                          Frustum frustum, Camera camera, RenderBuffers buffers, ProfilerFiller profiler) {
        origin = camera.position();
        // MaLiLib supplies the render and culling projections on this game version.
        projection.set(renderProjection).rotate(camera.rotation().conjugate(viewRotation));
    }

    @Override
    public void onRenderGameOverlayPostAdvanced(GuiContext graphics, float partialTicks, ProfilerFiller profiler) {
        if (origin != null && overlay.shouldRender(graphics.mc())) {
            overlay.render(graphics, origin, projection);
        }
    }
}
