package dev.otectus.mcakingdoms.client.rich;

import dev.otectus.mcakingdoms.client.book.BookSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A read-only vanilla scroll field holding laid-out {@link Piece}s: wrapped text, items, recipe grids, labelled callouts,
 * diagrams, requirement rows and in-place actions (links). Minecraft draws the field, border, clipping and scrollbar.
 *
 * <p>Actions inside the field are reachable by keyboard: Tab and Shift+Tab move between them before leaving the field,
 * Enter or Space activates the focused one, and its label is narrated. With no action focused the arrow, Page and Home/End
 * keys scroll. The reading position is kept by anchor (a piece id plus an offset), so new content for the same article,
 * a resize or a refresh does not jump back to the top.
 */
public final class RichPanel extends AbstractScrollWidget {
    /** The visual width the native scrollbar takes outside the widget bounds. */
    public static final int SCROLLBAR = 8;
    static final int GAP = 6;

    private List<Piece> pieces = List.of();
    private final List<Target> targets = new ArrayList<>();
    private int innerHeight;
    private int laidOutWidth = -1;
    /** -1 while reading (no action focused). */
    private int focusIndex = -1;
    private Component narration = Component.empty();
    private boolean manuscript;

    public RichPanel(int x, int y, int width, int height, Component heading) {
        super(x, y, Math.max(16, width - SCROLLBAR), height, heading);
    }

    /** Opt-in paper rendering. Other record/task panels retain their native field appearance. */
    public RichPanel manuscript() {
        manuscript = true;
        laidOutWidth = -1;
        return this;
    }

    private int pageStep() { return Math.max(9, height - totalInnerPadding() - 11); }
    public int pageCount() { return 1 + (int) Math.ceil((double) getMaxScrollAmount() / pageStep()); }
    public int pageIndex() {
        if (getMaxScrollAmount() > 0 && scrollAmount() >= getMaxScrollAmount()) return pageCount() - 1;
        return Math.min(pageCount() - 1, (int) (scrollAmount() / pageStep()));
    }

    /** A leaf turn retains a line of overlap so a paragraph or tall illustration never loses a row. */
    public boolean turnPage(int direction) {
        double before = scrollAmount();
        double page = direction > 0 ? Math.floor(before / pageStep()) + 1 : Math.ceil(before / pageStep()) - 1;
        setScrollAmount(page * pageStep());
        return before != scrollAmount();
    }

    /** Moves and resizes the field; {@code width} includes the scrollbar, as in the constructor. */
    public void place(int x, int y, int width, int height) {
        Anchor anchor = anchor();
        setX(x);
        setY(y);
        setWidth(Math.max(16, width - SCROLLBAR));
        setHeight(height);
        relayout();
        restore(anchor);
    }

    /** Replaces the content, starting at the top. */
    public void setPieces(List<Piece> value) {
        setPieces(value, null);
    }

    /** Replaces the content and returns to {@code anchor} when that piece still exists (null starts at the top). */
    public void setPieces(List<Piece> value, Anchor anchor) {
        String focusedTarget = focusIndex >= 0 && focusIndex < targets.size() ? targets.get(focusIndex).key : null;
        pieces = List.copyOf(value);
        laidOutWidth = -1;
        relayout();
        focusIndex = -1;
        if (focusedTarget != null) for (int i = 0; i < targets.size(); i++) if (focusedTarget.equals(targets.get(i).key)) focusIndex = i;
        if (anchor == null) setScrollAmount(0);
        else restore(anchor);
    }

    /** Lays the pieces out again for the current width, keeping the reading position (after a font or data change). */
    public void refreshLayout() {
        Anchor anchor = anchor();
        laidOutWidth = -1;
        relayout();
        restore(anchor);
    }

    public List<Piece> pieces() {
        return pieces;
    }

    private int contentWidth() {
        return width - totalInnerPadding();
    }

    private void relayout() {
        int contentWidth = contentWidth();
        if (contentWidth == laidOutWidth) return;
        laidOutWidth = contentWidth;
        Font font = Minecraft.getInstance().font;
        targets.clear();
        int y = 0;
        MutableComponent spoken = Component.empty();
        for (Piece piece : pieces) {
            piece.manuscript = manuscript;
            piece.targets.clear();
            piece.top = y;
            piece.height = Math.max(0, piece.layout(font, contentWidth));
            for (Target target : piece.targets) {
                target.piece = piece;
                targets.add(target);
            }
            y += piece.height + (piece.height > 0 ? GAP : 0);
            Component said = piece.narration();
            if (said != null && !said.getString().isBlank()) spoken.append(said).append(". ");
        }
        innerHeight = Math.max(0, y - GAP);
        narration = spoken;
        setScrollAmount(scrollAmount());
    }

    /** Where the reader is: the first piece still visible at the top, and how far into it. */
    public Anchor anchor() {
        int scroll = (int) scrollAmount();
        for (Piece piece : pieces) if (piece.top + piece.height > scroll && !piece.anchor.isEmpty()) return new Anchor(piece.anchor, scroll - piece.top);
        return new Anchor("", scroll);
    }

    private void restore(Anchor anchor) {
        if (anchor == null) return;
        if (anchor.piece().isEmpty()) { setScrollAmount(anchor.offset()); return; }
        for (Piece piece : pieces) if (piece.anchor.equals(anchor.piece())) {
            setScrollAmount(piece.top + Math.min(anchor.offset(), Math.max(0, piece.height - 1)));
            return;
        }
        setScrollAmount(0);
    }

    /** Scrolls so that the named piece is at the top, if it exists. */
    public void scrollTo(String pieceAnchor) {
        for (Piece piece : pieces) if (piece.anchor.equals(pieceAnchor)) { setScrollAmount(piece.top); return; }
    }

    public int scroll() {
        return (int) scrollAmount();
    }

    @Override protected int getInnerHeight() {
        return innerHeight + innerPadding();
    }

    @Override protected int getMaxScrollAmount() {
        return Math.max(0, getInnerHeight() + innerPadding() - height);
    }

    @Override protected double scrollRate() {
        return Minecraft.getInstance().font.lineHeight * 3;
    }

    @Override protected boolean scrollbarVisible() {
        return getMaxScrollAmount() > 0;
    }

    @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        relayout();
        Font font = Minecraft.getInstance().font;
        int left = getX() + innerPadding(), top = getY() + innerPadding();
        int scroll = (int) scrollAmount();
        int contentMouseY = mouseY + scroll;
        boolean mouseInside = withinContentAreaPoint(mouseX, mouseY);
        Hover hover = new Hover();
        for (Piece piece : pieces) {
            if (piece.top + piece.height < scroll - 2 || piece.top > scroll + height) continue;
            int pieceMouseX = mouseInside ? mouseX - left : Integer.MIN_VALUE, pieceMouseY = mouseInside ? contentMouseY - top - piece.top : Integer.MIN_VALUE;
            piece.render(graphics, font, left, top + piece.top, contentWidth(), pieceMouseX, pieceMouseY, hover);
            for (Target target : piece.targets) renderTarget(graphics, font, left, top + piece.top, target,
                    mouseInside && target.contains(pieceMouseX, pieceMouseY), focusIndex >= 0 && targets.get(focusIndex) == target && isFocused());
        }
        if (hover.lines != null && Minecraft.getInstance().screen != null) Minecraft.getInstance().screen.setTooltipForNextRenderPass(hover.lines);
    }

    private void renderTarget(GuiGraphics graphics, Font font, int left, int top, Target target, boolean hovered, boolean focused) {
        if (!target.visible) return;
        int x = left + target.x, y = top + target.y;
        if (target.style == Target.Style.BUTTON) {
            int texture = !target.active ? 46 : hovered || focused ? 86 : 66;
            if (manuscript) BookSkin.button(graphics, x, y, target.width, target.height, target.active, hovered || focused, false);
            else graphics.blitNineSliced(WIDGETS, x, y, target.width, target.height, 20, 4, 200, 20, 0, texture);
            int color = manuscript ? BookSkin.CONTROL_TEXT : target.active ? 0xFFFFFF : 0xA0A0A0;
            List<FormattedCharSequence> line = font.split(target.label, Math.max(8, target.width - 8));
            if (!line.isEmpty()) {
                FormattedCharSequence text = line.size() > 1 ? net.minecraft.locale.Language.getInstance().getVisualOrder(font.ellipsize(target.label, target.width - 8)) : line.get(0);
                if (manuscript) text = BookSkin.controlText(text);
                graphics.drawString(font, text, x + (target.width - font.width(text)) / 2, y + (target.height - 8) / 2, color, true);
            }
        }
        if (focused && manuscript && target.style == Target.Style.BUTTON) BookSkin.focus(graphics, x, y, target.width, target.height);
        else if (focused) graphics.renderOutline(x - 1, y - 1, target.width + 2, target.height + 2, manuscript ? 0xFF802D34 : 0xFFFFFFFF);
        else if (hovered && target.style == Target.Style.AREA) graphics.renderOutline(x - 1, y - 1, target.width + 2, target.height + 2, 0xFFA0A0A0);
    }

    static final net.minecraft.resources.ResourceLocation WIDGETS = new net.minecraft.resources.ResourceLocation("minecraft", "textures/gui/widgets.png");

    @Override protected void renderBackground(GuiGraphics g) {
        if (!manuscript) super.renderBackground(g);
    }

    @Override protected void renderBorder(GuiGraphics g, int x, int y, int w, int h) {
        if (!manuscript) super.renderBorder(g, x, y, w, h);
    }

    @Override protected void renderDecorations(GuiGraphics g) {
        if (!manuscript) { super.renderDecorations(g); return; }
        if (isFocused()) g.renderOutline(getX(), getY(), width, height, 0x557C532E);
        if (!scrollbarVisible()) return;
        BookSkin.scrollbar(g, getX() + width, getY(), height, getInnerHeight(), scrollAmount(), getMaxScrollAmount());
    }

    private Target targetAt(double mouseX, double mouseY) {
        if (!withinContentAreaPoint(mouseX, mouseY)) return null;
        int x = (int) mouseX - getX() - innerPadding();
        int y = (int) mouseY + (int) scrollAmount() - getY() - innerPadding();
        for (Target target : targets) if (target.visible && target.contains(x, y - target.piece.top)) return target;
        return null;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible) return false;
        if (button == 0) {
            Target target = targetAt(mouseX, mouseY);
            if (target != null) {
                focusIndex = targets.indexOf(target);
                if (target.active) {
                    playDownSound(Minecraft.getInstance().getSoundManager());
                    target.action.run();
                }
                return true;
            }
            focusIndex = -1;
        }
        return button == 0 && super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return active && isMouseOver(mouseX, mouseY) && delta != 0 && super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= getX() && mouseX < getX() + width + SCROLLBAR && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (!active || !isFocused()) return false;
        if (focusIndex >= 0 && focusIndex < targets.size() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE)) {
            Target target = targets.get(focusIndex);
            if (target.active) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                target.action.run();
            }
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_PAGE_UP -> setScrollAmount(scrollAmount() - height + totalInnerPadding());
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScrollAmount(scrollAmount() + height - totalInnerPadding());
            case GLFW.GLFW_KEY_HOME -> setScrollAmount(0);
            case GLFW.GLFW_KEY_END -> setScrollAmount(getMaxScrollAmount());
            default -> { return super.keyPressed(key, scan, modifiers); }
        }
        return true;
    }

    /**
     * Tab enters the field in reading mode, then visits each action in order before moving on; Shift+Tab goes back
     * through them. Returning null hands focus to the next control of the screen.
     */
    @Override public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!active || !visible) return null;
        if (!(event instanceof FocusNavigationEvent.TabNavigation tab)) return isFocused() ? null : ComponentPath.leaf(this);
        List<Integer> usable = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) if (targets.get(i).visible) usable.add(i);
        if (!isFocused()) {
            focusIndex = tab.forward() || usable.isEmpty() ? -1 : usable.get(usable.size() - 1);
            reveal();
            return ComponentPath.leaf(this);
        }
        int position = usable.indexOf(focusIndex);
        if (tab.forward()) {
            if (position + 1 < usable.size()) { focusIndex = usable.get(position + 1); reveal(); return ComponentPath.leaf(this); }
        } else {
            if (position > 0) { focusIndex = usable.get(position - 1); reveal(); return ComponentPath.leaf(this); }
            if (position == 0) { focusIndex = -1; reveal(); return ComponentPath.leaf(this); }
        }
        focusIndex = -1;
        return null;
    }

    /** Keeps the focused action inside the visible area. */
    private void reveal() {
        if (focusIndex < 0 || focusIndex >= targets.size()) return;
        Target target = targets.get(focusIndex);
        int top = target.piece.top + target.y, bottom = top + target.height;
        int visibleHeight = height - totalInnerPadding();
        if (top < scrollAmount()) setScrollAmount(Math.max(0, top - 2));
        else if (bottom > scrollAmount() + visibleHeight) setScrollAmount(bottom - visibleHeight + 2);
    }

    @Override public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) focusIndex = Math.min(focusIndex, targets.size() - 1);
    }

    /** The focused action's key, or null while reading. */
    public String focusedTarget() {
        return isFocused() && focusIndex >= 0 && focusIndex < targets.size() ? targets.get(focusIndex).key : null;
    }

    /** Focuses the action with this key (after a rebuild) if it still exists. */
    public boolean focusTarget(String key) {
        for (int i = 0; i < targets.size(); i++) if (Objects.equals(targets.get(i).key, key)) { focusIndex = i; reveal(); return true; }
        return false;
    }

    /** Every action currently laid out, for tests and harnesses. */
    public List<Target> targets() {
        return List.copyOf(targets);
    }

    /** The complete text equivalent, as narration reads it. */
    public Component narrationText() {
        return narration;
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        if (focusIndex >= 0 && focusIndex < targets.size() && isFocused()) {
            Target target = targets.get(focusIndex);
            output.add(NarratedElementType.TITLE, target.label);
            if (target.hint != null && !target.hint.getString().isBlank()) output.add(NarratedElementType.HINT, target.hint);
            output.add(NarratedElementType.USAGE, target.active ? Component.translatable("menu.mcakingdoms.rich.activate")
                    : Component.translatable("menu.mcakingdoms.rich.inactive"));
            return;
        }
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.USAGE, Component.translatable("menu.mcakingdoms.rich.usage"));
        output.add(NarratedElementType.HINT, narration);
    }

    /** A reading position: a piece anchor and the distance scrolled into it. */
    public record Anchor(String piece, int offset) {
    }

    /** What the pointer is over during one render, shown as a tooltip after the frame. */
    public static final class Hover {
        List<FormattedCharSequence> lines;

        public void set(List<FormattedCharSequence> value) {
            lines = value;
        }
    }

    /** An action inside the field: a guide link, a task entry point or a screen action. */
    public static final class Target {
        public enum Style { BUTTON, AREA }
        final String key;
        final Component label, hint;
        final Runnable action;
        final boolean active;
        final Style style;
        int x, y, width, height;
        boolean visible = true;
        Piece piece;

        public Target(String key, Component label, Component hint, Runnable action, boolean active, Style style) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.action = action;
            this.active = active;
            this.style = style;
        }

        public Target at(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        public String key() { return key; }
        public Component label() { return label; }
        public boolean active() { return active; }
        public void activate() { if (active) action.run(); }
    }

    /**
     * One laid-out part of the content. {@link #layout} receives the content width, may add {@link #targets} (positions
     * relative to the piece) and returns the height; {@link #render} draws at the given origin.
     */
    public abstract static class Piece {
        final String anchor;
        int top, height;
        protected boolean manuscript;
        protected final List<Target> targets = new ArrayList<>();

        protected Piece(String anchor) {
            this.anchor = anchor == null ? "" : anchor;
        }

        protected int ink(int color) { return manuscript ? BookSkin.ink(color) : color; }
        protected int lineHeight(Font font) { return font.lineHeight + (manuscript ? 2 : 0); }

        protected void drawText(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
            FormattedCharSequence rendered = manuscript ? BookSkin.inkText(text) : text;
            g.drawString(font, rendered, x, y, ink(color), shadow && !manuscript);
        }

        protected void drawText(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
            drawText(g, font, Component.literal(text).getVisualOrderText(), x, y, color, shadow);
        }

        public String anchorId() {
            return anchor;
        }

        protected abstract int layout(Font font, int width);

        /** {@code mouseX}/{@code mouseY} are relative to this piece, or {@link Integer#MIN_VALUE} when the pointer is outside. */
        protected abstract void render(GuiGraphics graphics, Font font, int x, int y, int width, int mouseX, int mouseY, Hover hover);

        /** What narration reads for this piece; blank to say nothing. */
        protected abstract Component narration();

        protected Target target(Target target) {
            targets.add(target);
            return target;
        }

        public List<Target> pieceTargets() {
            return List.copyOf(targets);
        }
    }
}
