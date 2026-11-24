package net.rasanovum.roxy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class RoxyWarningScreen extends Screen {
    private final Screen parent;
    private final List<RoxyClientWarnings.Warning> warnings;
    private final String version;
    private Checkbox dismiss;
    private boolean dismissSelected;
    private List<CachedWarning> cachedWarnings = List.of();
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
                .pos(0, buttonsY - 27)
                .selected(dismissSelected)
                .onValueChange((box, selected) -> dismissSelected = selected)
                .build();
        dismiss.setPosition(Math.max(8, (this.width - dismiss.getWidth()) / 2), buttonsY - 27);
        addRenderableWidget(dismiss);
        boolean showSetupGuide = warnings.stream().anyMatch(RoxyClientWarnings.Warning::macOSSetup);
        if (showSetupGuide) {
            int gap = 6;
            int totalWidth = Math.min(312, Math.max(96, this.width - 24));
            int buttonWidth = Math.max(28, (totalWidth - gap * 2) / 3);
            int rowWidth = buttonWidth * 3 + gap * 2;
            int startX = (this.width - rowWidth) / 2;
            addRenderableWidget(Button.builder(Component.literal("Quit Game"), ignored -> quitGame())
                    .bounds(startX, buttonsY, buttonWidth, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Setup Guide"),
                            ConfirmLinkScreen.confirmLink(this, RoxyClientWarnings.MACOS_GUIDE_URL))
                    .bounds(startX + buttonWidth + gap, buttonsY, buttonWidth, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Continue"), ignored -> continueToMenu())
                    .bounds(startX + (buttonWidth + gap) * 2, buttonsY, buttonWidth, 20)
                    .build());
        } else {
            int gap = 8;
            int totalWidth = Math.min(308, Math.max(80, this.width - 24));
            int buttonWidth = Math.max(36, (totalWidth - gap) / 2);
            int rowWidth = buttonWidth * 2 + gap;
            int startX = (this.width - rowWidth) / 2;
            addRenderableWidget(Button.builder(Component.literal("Quit Game"), ignored -> quitGame())
                    .bounds(startX, buttonsY, buttonWidth, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Continue"), ignored -> continueToMenu())
                    .bounds(startX + buttonWidth + gap, buttonsY, buttonWidth, 20)
                    .build());
        }
        updateContentLayout();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        updateContentLayout();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF240B0B);
        graphics.fill(0, 0, this.width, 50, 0xFF4A1B1B);
        graphics.fill(0, 49, this.width, 51, 0xFF8C3333);
        graphics.fill(0, this.height - 72, this.width, this.height, 0xFF4A1B1B);
        graphics.fill(0, this.height - 74, this.width, this.height - 72, 0xFF8C3333);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

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
            for (CachedWarning warning : cachedWarnings) {
                y = drawLines(graphics, warning.titleLines(), contentX, y, 0xFFFFFFFF);
                y += 2;
                y = drawLines(graphics, warning.tooltipLines(), contentX, y, 0xFFB8B8B8);
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
        List<CachedWarning> rebuilt = new ArrayList<>(warnings.size());
        int lineStep = this.font.lineHeight + 1;
        int totalHeight = 0;
        for (RoxyClientWarnings.Warning warning : warnings) {
            List<FormattedCharSequence> titleLines = this.font.split(warning.title(), contentWidth);
            List<FormattedCharSequence> tooltipLines = this.font.split(warning.tooltip(), contentWidth);
            rebuilt.add(new CachedWarning(titleLines, tooltipLines));
            totalHeight += titleLines.size() * lineStep + 2 + tooltipLines.size() * lineStep + 14;
        }
        cachedWarnings = rebuilt;
        contentHeight = totalHeight;
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

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom - contentTop));
    }

    private int drawLines(GuiGraphics graphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        int lineStep = this.font.lineHeight + 1;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, x, y, color);
            y += lineStep;
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
        if (dismissSelected || (dismiss != null && dismiss.selected())) {
            RoxyClientWarnings.dismiss(version, warnings);
        }
    }

    private record CachedWarning(List<FormattedCharSequence> titleLines, List<FormattedCharSequence> tooltipLines) {
    }
}
