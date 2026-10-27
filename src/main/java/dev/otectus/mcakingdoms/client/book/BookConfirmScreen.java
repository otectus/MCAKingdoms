package dev.otectus.mcakingdoms.client.book;

import dev.otectus.mcakingdoms.client.MenuTextPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.Consumer;

/** Manuscript confirmation for local settings; its caller alone owns the effect and return destination. */
public final class BookConfirmScreen extends Screen {
    private final Consumer<Boolean> callback;
    private final Component message;
    private BookLayout book;
    private boolean answered;

    public BookConfirmScreen(Consumer<Boolean> callback, Component title, Component message) {
        super(title);
        this.callback = callback;
        this.message = message;
    }

    @Override protected void init() {
        book = BookLayout.folio(width, height, 480, 280);
        int x = book.x() + 22, w = book.width() - 44;
        var explanation = addRenderableWidget(new MenuTextPanel(x, book.y() + 48, w, book.height() - 98, title).manuscript());
        explanation.setContent(List.of(message));
        int half = (w - 6) / 2;
        addRenderableWidget(BookButton.builder(CommonComponents.GUI_YES, b -> answer(true)).bounds(x, book.footer(), half, 20).build());
        var cancel = addRenderableWidget(BookButton.builder(CommonComponents.GUI_NO, b -> answer(false))
                .bounds(x + half + 6, book.footer(), w - half - 6, 20).build());
        setInitialFocus(cancel);
    }

    private void answer(boolean confirmed) {
        if (answered) return;
        answered = true;
        callback.accept(confirmed);
    }
    @Override public void onClose() { answer(false); }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        renderBackground(g);
        BookSkin.cover(g, book.x(), book.y(), book.width(), book.height(), false, BookSkin.Binding.TASKS);
        BookSkin.title(g, font, title, book.x() + 22, book.y() + 18, book.width() - 44);
        BookSkin.divider(g, book.x() + 22, book.y() + 34, book.width() - 44);
        super.render(g, x, y, partial);
    }
}
