package net.ledok.datarewriter.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A minimal draggable scrollbar: track + thumb, click to jump, drag to
 * scroll. Works in any unit (rows or pixels) — 'content' and 'view' just
 * have to share it. The geometry passed to render() is remembered for the
 * mouse handlers.
 */
final class ScrollBar {
    private static final int WIDTH = 5;

    private int x;
    private int y;
    private int height;
    private int content;
    private int view;
    private boolean dragging;

    /** Draws the bar (nothing when everything fits) and stores the geometry. */
    void render(GuiGraphics graphics, int x, int y, int height, int content, int view, int offset) {
        this.x = x;
        this.y = y;
        this.height = height;
        this.content = content;
        this.view = view;
        if (content <= view) {
            return;
        }
        graphics.fill(x, y, x + WIDTH, y + height, 0x66000000);
        int thumbH = thumbHeight();
        int thumbY = y + (height - thumbH) * Math.min(offset, content - view) / (content - view);
        graphics.fill(x, thumbY, x + WIDTH, thumbY + thumbH, dragging ? 0xFFBBBBBB : 0xFF777777);
    }

    private int thumbHeight() {
        return Math.max(10, (int) ((long) height * view / content));
    }

    boolean isDragging() {
        return dragging;
    }

    /** Press on the bar: the new offset (and a drag starts), or -1. */
    int mouseClicked(double mouseX, double mouseY) {
        if (content <= view || mouseX < x - 2 || mouseX >= x + WIDTH + 2
                || mouseY < y || mouseY >= y + height) {
            return -1;
        }
        dragging = true;
        return offsetFor(mouseY);
    }

    /** Drag in progress: the new offset, or -1 when not dragging. */
    int mouseDragged(double mouseY) {
        return dragging ? offsetFor(mouseY) : -1;
    }

    void mouseReleased() {
        dragging = false;
    }

    private int offsetFor(double mouseY) {
        int thumbH = thumbHeight();
        double t = (mouseY - y - thumbH / 2.0) / Math.max(1, height - thumbH);
        return (int) Math.round(Math.max(0, Math.min(1, t)) * (content - view));
    }
}
