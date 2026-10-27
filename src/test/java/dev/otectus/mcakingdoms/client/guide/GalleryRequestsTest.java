package dev.otectus.mcakingdoms.client.guide;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F11: the Book gallery's request cycle. Unanswered requests stop after a bounded number of attempts and stay failed
 * however often the article is redrawn; an explicit retry recovers; a server-reported change refreshes once, after a
 * debounce, while the old tiles stay; a late reply from an earlier connection is ignored.
 */
class GalleryRequestsTest {
    /** Records every request and hands out ids that never repeat, as the client channel does. */
    private static final class Network implements GalleryRequests.Sender {
        long next = 100;
        final List<Long> sent = new ArrayList<>();
        @Override public long send() { sent.add(++next); return next; }
    }

    private static void ticks(GalleryRequests requests, Network network, int count, boolean shown) {
        for (int i = 0; i < count; i++) requests.tick(network, shown);
    }

    @Test
    void droppedRepliesStopAfterTheBoundedAttemptsAndRedrawsDoNotRestartThem() {
        var requests = new GalleryRequests();
        var network = new Network();
        assertTrue(requests.ensure(network));
        ticks(requests, network, GalleryRequests.TIMEOUT * GalleryRequests.ATTEMPTS + 5, true);
        assertEquals(GalleryRequests.State.FAILED, requests.state());
        assertEquals(GalleryRequests.ATTEMPTS, network.sent.size());
        // The failure redraws the article, which calls ensure again: nothing more is sent, now or later.
        for (int i = 0; i < 50; i++) assertFalse(requests.ensure(network));
        ticks(requests, network, 500, true);
        assertEquals(GalleryRequests.ATTEMPTS, network.sent.size(), "a rendering loop resent requests");
        assertEquals(GalleryRequests.State.FAILED, requests.state());
    }

    @Test
    void anExplicitRetryOrReopenRecovers() {
        var requests = new GalleryRequests();
        var network = new Network();
        requests.ensure(network);
        ticks(requests, network, GalleryRequests.TIMEOUT * GalleryRequests.ATTEMPTS + 1, true);
        assertTrue(requests.retry(network));
        assertTrue(requests.receive(network.sent.get(network.sent.size() - 1)));
        assertEquals(GalleryRequests.State.READY, requests.state());
        assertFalse(requests.retry(network), "a ready gallery is not asked again by Try again");

        var reopened = new GalleryRequests();
        reopened.ensure(network);
        ticks(reopened, network, GalleryRequests.TIMEOUT * GalleryRequests.ATTEMPTS + 1, true);
        reopened.reopened();
        assertEquals(GalleryRequests.State.NONE, reopened.state());
        assertTrue(reopened.ensure(network), "reopening the Book lets the gallery ask once more");
    }

    @Test
    void aReportedChangeRefreshesAShownGalleryOnceAfterTheDebounce() {
        var requests = new GalleryRequests();
        var network = new Network();
        requests.ensure(network);
        requests.receive(network.sent.get(0));
        int before = network.sent.size();
        // Several discoveries in quick succession coalesce into one refresh.
        requests.invalidate(true);
        requests.invalidate(true);
        requests.invalidate(true);
        ticks(requests, network, GalleryRequests.REFRESH_DELAY - 1, true);
        assertEquals(before, network.sent.size(), "refreshed before the debounce elapsed");
        ticks(requests, network, 2, true);
        assertEquals(before + 1, network.sent.size(), "one refresh after the debounce");
        assertTrue(requests.refreshing());
        assertEquals(GalleryRequests.State.READY, requests.state(), "the earlier tiles stay while refreshing");
        assertTrue(requests.receive(network.sent.get(network.sent.size() - 1)));
        assertFalse(requests.refreshing());
    }

    @Test
    void aChangeWhileHiddenRefreshesWhenNextShown() {
        var requests = new GalleryRequests();
        var network = new Network();
        requests.ensure(network);
        requests.receive(network.sent.get(0));
        requests.invalidate(false);
        ticks(requests, network, 100, false);
        assertEquals(1, network.sent.size(), "nothing is requested for a gallery nobody is looking at");
        assertTrue(requests.ensure(network), "showing it again asks once");
        assertFalse(requests.ensure(network));
    }

    @Test
    void aFailedRefreshKeepsTheTilesAndSaysSo() {
        var requests = new GalleryRequests();
        var network = new Network();
        requests.ensure(network);
        requests.receive(network.sent.get(0));
        requests.invalidate(true);
        ticks(requests, network, GalleryRequests.REFRESH_DELAY + 1 + GalleryRequests.TIMEOUT * GalleryRequests.ATTEMPTS + 1, true);
        assertEquals(GalleryRequests.State.READY, requests.state());
        assertTrue(requests.refreshFailed());
        int sent = network.sent.size();
        ticks(requests, network, 300, true);
        assertEquals(sent, network.sent.size(), "a failed refresh rests until Try again");
        assertTrue(requests.retry(network));
    }

    @Test
    void aLateReplyFromAnEarlierRequestOrServerIsIgnored() {
        var requests = new GalleryRequests();
        var network = new Network();
        requests.ensure(network);
        long serverA = network.sent.get(0);
        requests.clear();                       // disconnected from server A
        assertFalse(requests.receive(serverA), "A's reply arrived after leaving A");
        requests.ensure(network);               // joined server B
        assertFalse(requests.receive(serverA), "A's reply must not answer B's request");
        assertEquals(GalleryRequests.State.LOADING, requests.state());
        assertTrue(requests.receive(network.sent.get(1)));
    }

    @Test
    void aChangeReportedWhileLoadingIsFollowedByOneRefresh() {
        var requests = new GalleryRequests();
        var network = new Network();
        requests.ensure(network);
        requests.invalidate(true);
        requests.receive(network.sent.get(0));
        ticks(requests, network, GalleryRequests.REFRESH_DELAY + 1, true);
        assertEquals(2, network.sent.size(), "the answer may predate the change, so it is asked once more");
    }
}
