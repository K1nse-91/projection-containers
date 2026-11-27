package dev.qianhua.containerscan;

import net.minecraft.util.ARGB;

public final class PopupItemOpacity {
    static final float DIMMED = 0.60f;
    private static final int DIMMED_CHANNEL = Math.round(DIMMED * 255);
    private static final int DIMMED_TINT =
            ARGB.color(DIMMED_CHANNEL, DIMMED_CHANNEL, DIMMED_CHANNEL, DIMMED_CHANNEL);
    private static final ThreadLocal<Integer> TINT = ThreadLocal.withInitial(() -> -1);

    private PopupItemOpacity() {}

    static int dim() {
        int previous = TINT.get();
        TINT.set(DIMMED_TINT);
        return previous;
    }

    static void restore(int tint) {
        TINT.set(tint);
    }

    public static int currentTint() {
        return TINT.get();
    }

    public static int applyTint(int color, Object state) {
        // The atlas and oversized item textures both use premultiplied alpha.
        int tint = ((Tinted) state).containerScan$itemTint();
        return tint == -1 ? color : ARGB.multiply(color, tint);
    }

    public interface Tinted {
        int containerScan$itemTint();
    }
}
