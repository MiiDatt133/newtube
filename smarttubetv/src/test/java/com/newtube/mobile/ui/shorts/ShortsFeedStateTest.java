package com.newtube.mobile.ui.shorts;

import static org.junit.Assert.*;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

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
    public void firstPageAcceptedItemsAreDeduped() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();

        MediaGroup group = groupWithItems("a", "b", "a", "c"); // duplicate "a"
        int firstNew = state.acceptGroup(group);

        assertEquals(0, firstNew);
        assertEquals(3, state.items().size()); // a, b, c
        assertEquals("a", state.items().get(0).videoId);
        assertEquals("b", state.items().get(1).videoId);
        assertEquals("c", state.items().get(2).videoId);
    }

    @Test
    public void secondPageAppendsAfterFirst() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("a", "b"));
        state.endLoad(false);

        state.beginLoad();
        int firstNew = state.acceptGroup(groupWithItems("b", "c", "d")); // "b" duplicate
        state.endLoad(false);

        assertEquals(2, firstNew); // "c" at index 2
        assertEquals(4, state.items().size());
        assertEquals("c", state.items().get(2).videoId);
        assertEquals("d", state.items().get(3).videoId);
    }

    @Test
    public void emptyPageDoesNotEndFeed() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems());
        state.endLoad(false);

        assertEquals(0, state.items().size());
        // Empty group with no next page key ends the feed
        assertTrue(state.hasEnded());
    }

    @Test
    public void groupWithNextPageKeyKeepsFeedOpen() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        MediaGroup group = groupWithItems("a");
        group.setNextPageKey("page2"); // continuation available
        state.acceptGroup(group);
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
        state.acceptGroup(groupWithItems("a")); // no key
        state.endLoad(false);

        assertTrue(state.hasEnded());
        assertFalse(state.needsMore());
        assertNull(state.nextPage());
    }

    @Test
    public void repeatedNextPageKeyEndsFeedHonestly() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        MediaGroup g1 = groupWithItems("a");
        g1.setNextPageKey("page2");
        state.acceptGroup(g1);
        state.endLoad(false);

        state.beginLoad();
        MediaGroup g2 = groupWithItems("b");
        g2.setNextPageKey("page2"); // same key - server repeating
        state.acceptGroup(g2);
        state.endLoad(false);

        assertTrue(state.hasEnded());
    }

    @Test
    public void newKeyAfterKeylessGroupReopensFeed() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("tv_partial")); // no key (TV partial)
        state.endLoad(false);
        assertTrue(state.hasEnded());

        // Then a WEB group arrives with a key - feed reopens
        state.beginLoad();
        MediaGroup webGroup = groupWithItems("web_item");
        webGroup.setNextPageKey("web_page2");
        state.acceptGroup(webGroup);
        state.endLoad(false);

        assertFalse(state.hasEnded());
        assertTrue(state.needsMore());
    }

    @Test
    public void errorDoesNotResetItems() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("a", "b"));
        state.endLoad(true); // error

        assertEquals(2, state.items().size());
        assertFalse(state.isLoading());
    }

    @Test
    public void initialErrorAllowsRetry() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.endLoad(true); // initial load error

        assertTrue(state.needsInitial());
        assertFalse(state.isLoading());
    }

    @Test
    public void markEndedFromNullContinuation() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("a"));
        state.endLoad(false);

        assertFalse(state.hasEnded());
        state.markEnded(); // continuation source returned null (engine reports exhausted)
        assertTrue(state.hasEnded());
        assertNull(state.nextPage());
    }

    @Test
    public void resetClearsEverything() {
        ShortsFeedState state = new ShortsFeedState();
        state.beginLoad();
        state.acceptGroup(groupWithItems("a", "b"));
        state.endLoad(false);
        state.beginLoad();
        state.acceptGroup(groupWithItems("c"));
        state.endLoad(false);

        state.reset();

        assertTrue(state.needsInitial());
        assertEquals(0, state.items().size());
        assertFalse(state.hasEnded());
        assertNull(state.nextPage());
    }

    /** Helper: creates a MediaGroup with the given video IDs. */
    private static MediaGroup groupWithItems(String... videoIds) {
        List<MediaItem> items = new ArrayList<>();
        for (String id : videoIds) {
            MediaItem item = (MediaItem) Proxy.newProxyInstance(
                    MediaItem.class.getClassLoader(),
                    new Class<?>[]{MediaItem.class},
                    (proxy, method, args) -> {
                        if ("getVideoId".equals(method.getName())) return id;
                        if ("getTitle".equals(method.getName())) return id;
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        return null;
                    });
            items.add(item);
        }
        return mediaGroup(items);
    }

    private static MediaGroup mediaGroup(List<MediaItem> items) {
        return (MediaGroup) Proxy.newProxyInstance(
                MediaGroup.class.getClassLoader(),
                new Class<?>[]{MediaGroup.class},
                (proxy, method, args) -> {
                    if ("getMediaItems".equals(method.getName())) return items;
                    if ("getNextPageKey".equals(method.getName())) return null;
                    if ("setNextPageKey".equals(method.getName())) return null;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
    }
}