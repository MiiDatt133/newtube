package com.newtube.mobile.ui.shorts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** NEWTUBE(shorts): unit tests for the pure feed state — dedupe, pagination, end-of-feed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = android.app.Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsFeedStateTest {

    @Test
    public void initialState() {
        ShortsFeedState state = new ShortsFeedState();
        assertTrue(state.needsInitial());
        assertFalse(state.isLoading());
        assertFalse(state.hasEnded());
        assertTrue(state.hasMore());
        assertEquals(0, state.items().size());
        assertNull(state.nextPage());
    }

    @Test
    public void firstPageItemsAreDedupedByVideoId() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();

        int firstNew = state.acceptGroup(groupWithItems(null, "a", "b", "a", "c"));

        assertEquals(0, firstNew);
        assertEquals(3, state.items().size()); // duplicate "a" dropped
        assertEquals("a", state.items().get(0).videoId);
        assertEquals("b", state.items().get(1).videoId);
        assertEquals("c", state.items().get(2).videoId);
    }

    @Test
    public void secondPageAppendsAfterFirst() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "a", "b"));
        state.endLoad(false);

        state.beginLoad();
        int firstNew = state.acceptGroup(groupWithItems("page3", "b", "c", "d")); // "b" duplicate
        state.endLoad(false);

        assertEquals(2, firstNew); // "c" at index 2
        assertEquals(4, state.items().size());
        assertEquals("c", state.items().get(2).videoId);
        assertEquals("d", state.items().get(3).videoId);
        assertTrue(state.needsMore());
        assertEquals("page3", state.nextPage().getNextPageKey());
    }

    @Test
    public void emptyPageWithNoKeyEndsTheFeed() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        int firstNew = state.acceptGroup(groupWithItems(null));

        assertEquals(-1, firstNew);
        state.endLoad(false);

        assertEquals(0, state.items().size());
        assertTrue(state.hasEnded());
        assertFalse(state.needsMore());
    }

    @Test
    public void groupWithNextPageKeyKeepsFeedOpen() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "a"));
        state.endLoad(false);

        assertFalse(state.hasEnded());
        assertTrue(state.needsMore());
        assertNotNull(state.nextPage());
        assertEquals("page2", state.nextPage().getNextPageKey());
    }

    @Test
    public void groupWithoutNextPageKeyEndsFeed() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems(null, "a"));
        state.endLoad(false);

        assertTrue(state.hasEnded());
        assertFalse(state.needsMore());
        assertNull(state.nextPage());
    }

    @Test
    public void repeatedNextPageKeyEndsFeedHonestly() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "a"));
        state.endLoad(false);

        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "b")); // same key - server repeating
        state.endLoad(false);

        assertTrue("a server repeating the same page must not loop the app", state.hasEnded());
    }

    @Test
    public void newKeyAfterKeylessGroupReopensFeed() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems(null, "tv_partial")); // keyless (TV partial)
        state.endLoad(false);
        assertTrue(state.hasEnded());

        // The WEB reel feed lands after the TV partial: a fresh key reopens continuation.
        state.beginLoad();
        state.acceptGroup(groupWithItems("web_page2", "web_item"));
        state.endLoad(false);

        assertFalse(state.hasEnded());
        assertTrue(state.needsMore());
    }

    @Test
    public void onlyOneLoadAtATime() {
        ShortsFeedState state = new ShortsFeedState();
        assertTrue(state.beginLoad());
        assertFalse("second claim while in flight", state.beginLoad());

        state.acceptGroup(groupWithItems("page2", "a"));
        state.endLoad(false);
        assertTrue("fresh claim after the load lands", state.beginLoad());
        assertFalse("still only one at a time", state.beginLoad());
    }

    @Test
    public void initialErrorAllowsRetryButKeepsLandedItems() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "a"));
        state.endLoad(true); // error mid-feed

        assertEquals(1, state.items().size());
        assertFalse(state.isLoading());
        assertFalse("an error does not end the feed", state.hasEnded());

        ShortsFeedState fresh = new ShortsFeedState();
        fresh.beginLoad();
        fresh.endLoad(true); // failed initial: nothing landed
        assertTrue("the next attempt may be an initial one again", fresh.needsInitial());
    }

    @Test
    public void markEndedFromNullContinuation() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "a"));
        state.endLoad(false);

        assertFalse(state.hasEnded());
        state.markEnded(); // engine: continuation source exhausted (null result)
        assertTrue(state.hasEnded());
        assertNull(state.nextPage());
    }

    @Test
    public void resetClearsEverything() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("page2", "a", "b"));
        state.endLoad(false);
        state.beginLoad();
        state.acceptGroup(groupWithItems("page3", "c"));
        state.endLoad(false);

        state.reset();

        assertTrue(state.needsInitial());
        assertEquals(0, state.items().size());
        assertFalse(state.hasEnded());
        assertNull(state.nextPage());
        assertFalse(state.isLoading());
    }

    @Test
    public void itemWithoutVideoIdIsSkipped() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        int firstNew = state.acceptGroup(groupWithItems(null, "a", null, "b"));

        assertEquals(0, firstNew);
        assertEquals(2, state.items().size());
    }

    // ---------------------------------------------------------------------------------
    // Fakes (dynamic proxies: no Mockito in this module's test classpath)
    // ---------------------------------------------------------------------------------

    /** A MediaGroup whose continuation key and items the test chooses. */
    private static MediaGroup groupWithItems(String nextPageKey, String... videoIds) {
        List<MediaItem> items = new ArrayList<>();
        for (String id : videoIds) {
            items.add(mediaItem(id));
        }
        final String key = nextPageKey;
        return (MediaGroup) Proxy.newProxyInstance(
                MediaGroup.class.getClassLoader(),
                new Class<?>[]{MediaGroup.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMediaItems":
                            return items;
                        case "getNextPageKey":
                            return key;
                        case "getType":
                            return MediaGroup.TYPE_SHORTS;
                        case "isEmpty":
                            return items.isEmpty();
                        case "toString":
                            return "FakeGroup";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    private static MediaItem mediaItem(String videoId) {
        return (MediaItem) Proxy.newProxyInstance(
                MediaItem.class.getClassLoader(),
                new Class<?>[]{MediaItem.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideoId":
                        case "getTitle":
                            return videoId;
                        case "hashCode":
                            return videoId == null ? 0 : videoId.hashCode();
                        case "equals":
                            return proxy == args[0];
                        default: {
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                        }
                    }
                });
    }
}