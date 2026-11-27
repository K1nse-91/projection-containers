package dev.qianhua.containerscan;

import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

final class GuiTheme {
    static final int TEXT = 0xfff5f5f7;
    static final int SECONDARY = 0xffc4c4cc;
    static final int DISABLED = 0xff8e8e96;
    static final int WARNING = 0xffffb868;
    static final int SCRIM = 0xbb101012;
    static final int SEPARATOR = 0x3aebebf5;
    private static final FormattedText ELLIPSIS = FormattedText.of("…");

    private GuiTheme() {}

    static void rounded(GuiContext graphics, int x, int y, int width, int height, int color) {
        if (width < 6 || height < 6) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        graphics.fill(x + 3, y, x + width - 3, y + 1, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 3, color);
        graphics.fill(x, y + 3, x + width, y + height - 3, color);
        graphics.fill(x + 1, y + height - 3, x + width - 1, y + height - 1, color);
        graphics.fill(x + 3, y + height - 1, x + width - 3, y + height, color);
    }

    static FormattedCharSequence fit(Font font, Component message, int width) {
        if (font.width(message) <= width) {
            return message.getVisualOrderText();
        }
        int remaining = width - font.width(ELLIPSIS);
        if (remaining < 0) {
            return FormattedCharSequence.EMPTY;
        }
        return Language.getInstance().getVisualOrder(FormattedText.composite(font.substrByWidth(message, remaining), ELLIPSIS));
    }

    static void label(GuiContext graphics, Font font, Component message, int x, int y,
                      int width, int color, int alignment) {
        var line = fit(font, message, width);
        int gap = Math.max(0, width - font.width(line));
        graphics.drawString(font, line, x + (alignment == 0 ? 0 : alignment == 1 ? gap / 2 : gap), y, color, false);
    }
}
