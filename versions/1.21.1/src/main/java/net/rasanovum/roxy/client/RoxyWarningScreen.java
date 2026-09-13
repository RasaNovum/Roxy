package net.rasanovum.roxy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class RoxyWarningScreen extends Screen {
    private final Screen parent;
    private final List<RoxyClientWarnings.Warning> warnings;
    private final String version;
    private Checkbox dismiss;
    private Button quit;
    private Button proceed;
    private double scrollOffset;
    private int contentX;
    private int contentWidth;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;

    public RoxyWarningScreen(Screen parent, List<RoxyClientWarnings.Warning> warnings, String version) {
        super(Component.literal("Potential Roxy Issues Detected"));
        this.parent = parent;
        this.warnings = List.copyOf(warnings);
        this.version = version;
    }

    @Override
    protected void init() {
        int buttonsY = this.height - 34;
        dismiss = Checkbox.builder(Component.literal("Don't show this screen again"), this.font)
                .pos(this.width / 2 - 100, buttonsY - 27)
                .selected(false)
                .build();
        addRenderableWidget(dismiss);
        quit = Button.builder(Component.literal("Quit Game"), ignored -> quitGame())
                .bounds(this.width / 2 - 154, buttonsY, 150, 20)
                .build();
        proceed = Button.builder(Component.literal("Continue"), ignored -> continueToMenu())
                .bounds(this.width / 2 + 4, buttonsY, 150, 20)
                .build();
        addRenderableWidget(quit);
        addRenderableWidget(proceed);
        updateContentLayout();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        updateContentLayout();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF240B0B);
        graphics.fill(0, 0, this.width, 50, 0xFF4A1B1B);
        graphics.fill(0, 49, this.width, 51, 0xFF8C3333);
        graphics.fill(0, this.height - 72, this.width, this.height, 0xFF4A1B1B);
        graphics.fill(0, this.height - 74, this.width, this.height - 72, 0xFF8C3333);

        dismiss.render(graphics, mouseX, mouseY, partialTick);
        quit.render(graphics, mouseX, mouseY, partialTick);
        proceed.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFF5555);
        graphics.drawCenteredString(
                this.font,
                Component.literal(warnings.size() + (warnings.size() == 1 ? " potential issue was detected" : " potential issues were detected")),
                this.width / 2,
                30,
                0xFFFF7777
        );

        scrollOffset = Mth.clamp(scrollOffset, 0.0, (double) maxScroll());
        graphics.enableScissor(contentX, contentTop, contentX + contentWidth, contentBottom);
        try {
            int y = contentTop - (int) Math.round(scrollOffset);
            for (RoxyClientWarnings.Warning warning : warnings) {
                y = drawWrapped(graphics, warning.title(), contentX, y, contentWidth, 0xFFFFFFFF);
                y += 2;
                y = drawWrapped(graphics, warning.tooltip(), contentX, y, contentWidth, 0xFFB8B8B8);
                y += 14;
            }
        } finally {
            graphics.disableScissor();
        }
        renderScrollbar(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= contentX && mouseX < Math.min(this.width, contentX + contentWidth + 12)
                && mouseY >= contentTop && mouseY < contentBottom && maxScroll() > 0) {
            scrollOffset = Mth.clamp(scrollOffset - scrollY * 12.0, 0.0, (double) maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (maxScroll() > 0) {
            int page = contentBottom - contentTop;
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                scrollOffset = Math.max(0.0, scrollOffset - page);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                scrollOffset = Math.min(maxScroll(), scrollOffset + page);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_HOME) {
                scrollOffset = 0.0;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_END) {
                scrollOffset = maxScroll();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void updateContentLayout() {
        contentTop = 66;
        contentBottom = Math.max(contentTop + 1, this.height - 82);
        contentWidth = Math.min(720, Math.max(1, this.width - 64));
        contentX = (this.width - contentWidth) / 2;
        contentHeight = warningContentHeight(contentWidth);
        scrollOffset = Mth.clamp(scrollOffset, 0.0, (double) maxScroll());
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int maximumScroll = maxScroll();
        if (maximumScroll <= 0 || contentBottom <= contentTop + 2) return;

        int trackX = Math.min(this.width - 3, contentX + contentWidth + 4);
        int trackTop = contentTop + 1;
        int trackBottom = contentBottom - 1;
        int trackHeight = trackBottom - trackTop;
        if (trackHeight <= 0) return;
        int thumbHeight = Math.min(trackHeight, Math.max(8, trackHeight * (contentBottom - contentTop) / contentHeight));
        int thumbTravel = trackHeight - thumbHeight;
        int thumbY = trackTop + (int) Math.round(thumbTravel * scrollOffset / maximumScroll);
        graphics.fill(trackX, trackTop, trackX + 2, trackBottom, 0x664A1B1B);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFB8B8B8);
    }

    private int warningContentHeight(int width) {
        int y = 0;
        for (RoxyClientWarnings.Warning warning : warnings) {
            y += wrappedHeight(warning.title(), width);
            y += 2;
            y += wrappedHeight(warning.tooltip(), width);
            y += 14;
        }
        return y;
    }

    private int wrappedHeight(FormattedText text, int width) {
        return this.font.split(text, width).size() * (this.font.lineHeight + 1);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom - contentTop));
    }

    private int drawWrapped(GuiGraphics graphics, FormattedText text, int x, int y, int width, int color) {
        for (FormattedCharSequence line : this.font.split(text, width)) {
            graphics.drawString(this.font, line, x, y, color);
            y += this.font.lineHeight + 1;
        }
        return y;
    }

    @Override
    public void onClose() {
        continueToMenu();
    }

    private void continueToMenu() {
        saveDismissal();
        Minecraft.getInstance().setScreen(parent);
    }

    private void quitGame() {
        saveDismissal();
        Minecraft.getInstance().stop();
    }

    private void saveDismissal() {
        if (dismiss != null && dismiss.selected()) RoxyClientWarnings.dismiss(version);
    }
}
