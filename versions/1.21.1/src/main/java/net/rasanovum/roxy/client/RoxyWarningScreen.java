package net.rasanovum.roxy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class RoxyWarningScreen extends Screen {
    private final Screen parent;
    private final List<RoxyClientWarnings.Warning> warnings;
    private final String version;
    private Checkbox dismiss;
    private Button quit;
    private Button proceed;

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

        int textWidth = Math.min(720, this.width - 48);
        int x = (this.width - textWidth) / 2;
        int y = 66;
        for (RoxyClientWarnings.Warning warning : warnings) {
            y = drawWrapped(graphics, warning.title(), x, y, textWidth, 0xFFFFFFFF);
            y += 2;
            y = drawWrapped(graphics, warning.tooltip(), x, y, textWidth, 0xFFB8B8B8);
            y += 14;
        }
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
