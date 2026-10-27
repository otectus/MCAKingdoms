package dev.otectus.mcakingdoms.client.guide;

/**
 * When the Book's kingdom gallery asks the server, kept apart from rendering so it cannot loop (F11). Rendering may only
 * start the first request of a connection. A request that is never answered is retried a bounded number of times and
 * then rests in {@link State#FAILED} until the player chooses Try again or reopens the Book. A server-reported change
 * (the player learned of a settlement, or kingdom definitions were reloaded) refreshes a shown gallery once, after a
 * short debounce, while the earlier tiles stay on screen; a gallery not on screen is refreshed the next time it is shown.
 * Replies are matched by request id, so a late reply to an earlier request or an earlier connection changes nothing.
 * Pure state: the caller supplies the sender and decides what "shown" means.
 */
public final class GalleryRequests {
    public enum State { NONE, LOADING, READY, FAILED }

    /** Sends one request and returns its id (unique for the client process). */
    @FunctionalInterface
    public interface Sender { long send(); }

    static final int TIMEOUT = 40, ATTEMPTS = 3, REFRESH_DELAY = 10;

    private State state = State.NONE;
    private long request;
    private int timeout, attempts;
    /** READY tiles are shown while a refresh is in flight. */
    private boolean refreshing;
    /** The last refresh went unanswered: the tiles shown may be out of date. */
    private boolean refreshFailed;
    /** The server reported a change while the gallery was not shown; the next showing refreshes. */
    private boolean stale;
    /** A change reported while loading; the answer may predate it, so one refresh follows. */
    private boolean changedWhileLoading;
    /** Ticks until a debounced refresh of a shown gallery, or -1. */
    private int refreshIn = -1;

    public State state() { return state; }
    public boolean refreshing() { return refreshing; }
    public boolean refreshFailed() { return refreshFailed; }

    private void send(Sender sender) {
        attempts++;
        timeout = TIMEOUT;
        request = sender.send();
    }

    private void start(Sender sender) {
        state = State.LOADING;
        attempts = 0;
        refreshing = false;
        send(sender);
    }

    private void refresh(Sender sender) {
        refreshing = true;
        refreshFailed = false;
        stale = false;
        attempts = 0;
        send(sender);
    }

    /** Called while building the gallery: the first request of a connection, or a refresh the server asked for. */
    public boolean ensure(Sender sender) {
        if (state == State.NONE) { start(sender); return true; }
        if (state == State.READY && stale && !refreshing) { refresh(sender); return true; }
        return false;
    }

    /** The player chose Try again: a failed gallery or a failed refresh asks again; anything else is left alone. */
    public boolean retry(Sender sender) {
        if (state == State.FAILED) { start(sender); return true; }
        if (state == State.READY && refreshFailed && !refreshing) { refresh(sender); return true; }
        return false;
    }

    /** The Book was opened again: a failed gallery may ask again when it is next shown. */
    public void reopened() {
        if (state == State.FAILED) state = State.NONE;
    }

    /** The answer to a request; true when it was the one awaited and the gallery should show it. */
    public boolean receive(long requestId) {
        if (requestId != request || !(state == State.LOADING || refreshing)) return false;
        state = State.READY;
        refreshing = false;
        refreshFailed = false;
        stale = false;
        if (changedWhileLoading) { changedWhileLoading = false; refreshIn = REFRESH_DELAY; }
        return true;
    }

    /** The server reported that this player's gallery changed. */
    public void invalidate(boolean shown) {
        switch (state) {
            case READY -> {
                if (refreshing) changedWhileLoading = true;
                else if (shown) { if (refreshIn < 0) refreshIn = REFRESH_DELAY; }
                else stale = true;
            }
            case LOADING -> changedWhileLoading = true;
            case NONE, FAILED -> { } // the next request reads the current state anyway
        }
    }

    /** One client tick; true when what the gallery shows changed (the caller redraws it). */
    public boolean tick(Sender sender, boolean shown) {
        boolean changed = false;
        if ((state == State.LOADING || refreshing) && --timeout <= 0) {
            if (attempts < ATTEMPTS) send(sender);
            else if (refreshing) { refreshing = false; refreshFailed = true; changed = true; }
            else { state = State.FAILED; changed = true; }
        }
        if (refreshIn >= 0 && --refreshIn < 0) {
            if (state == State.READY && !refreshing && shown) { refresh(sender); changed = true; }
            else if (state == State.READY) stale = true;
        }
        return changed;
    }

    /** Disconnected: nothing from this connection is kept, and its late replies no longer match. */
    public void clear() {
        state = State.NONE;
        request = 0;
        timeout = attempts = 0;
        refreshing = refreshFailed = stale = changedWhileLoading = false;
        refreshIn = -1;
    }
}
