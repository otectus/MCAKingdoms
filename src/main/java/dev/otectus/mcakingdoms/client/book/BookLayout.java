package dev.otectus.mcakingdoms.client.book;

/** Shared book geometry in GUI pixels. Text is never scaled down to fit a window. */
public record BookLayout(int x, int y, int width, int height, boolean spread) {
    public static BookLayout fit(int screenWidth, int screenHeight) {
        int width = Math.min(780, screenWidth - 24);
        int height = Math.min(470, screenHeight - 16);
        return new BookLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height, width >= 496);
    }

    /** A bounded single-leaf dossier for operational screens, with the same page margins as the books. */
    public static BookLayout folio(int screenWidth, int screenHeight, int maxWidth, int maxHeight) {
        int width = Math.min(maxWidth, screenWidth - 24);
        int height = Math.min(maxHeight, screenHeight - 16);
        return new BookLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height, false);
    }

    public int bottom() { return y + height; }
    public int leafX(boolean right) { return x + 22 + (right && spread ? leafWidth() + 24 : 0); }
    public int leafWidth() { return spread ? (width - 68) / 2 : width - 44; }
    public int contentTop() { return y + 18; }
    public int footer() { return bottom() - 40; }
}
