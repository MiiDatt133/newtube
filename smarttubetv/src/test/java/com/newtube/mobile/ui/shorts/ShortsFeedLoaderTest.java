package com.newtube.mobile.ui.shorts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.sharedutils.rx.RxHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.mockito.Mockito;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

/** NEWTUBE(shorts): unit tests for the feed loader — generation-based stale rejection,
 *  null-result-as-ended, lifecycle. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = android.app.Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsFeedLoaderTest {

    private ContentService mContentService;
    private TestCallback mCallback;
    private ShortsFeedLoader mLoader;

    @Before
    public void setUp() {
        mContentService = Mockito.mock(ContentService.class);
        mCallback = new TestCallback();
        mLoader = new ShortsFeedLoader(mContentService, mCallback);
    }

    @After
    public void tearDown() {
        mLoader.cancel();
    }

    @Test
    public void initialLoadEmitsPage() {
        MediaGroup group = groupWithItems("a", "b", "c");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(group));

        mLoader.loadInitial();

        assertTrue(mCallback.loadingChanged);
        assertEquals(1, mCallback.pagesReceived.size());
        assertEquals(0, mCallback.pagesReceived.get(0).firstNewIndex);
        assertEquals(3, mCallback.pagesReceived.get(0).size);
        assertFalse(mLoader.isLoading());
        assertFalse(mLoader.hasMore()); // no continuation key -> end
    }

    @Test
    public void continuationLoadAppendsPage() {
        MediaGroup first = groupWithItems("a", "b");
        first.setNextPageKey("page2");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(first));

        mLoader.loadInitial();
        assertFalse(mLoader.isLoading());

        MediaGroup second = groupWithItems("c", "d");
        Mockito.when(mContentService.continueGroupObserve(Mockito.any(MediaGroup.class)))
                .thenReturn(Observable.just(second));

        mLoader.loadMore();
        assertFalse(mLoader.isLoading());

        assertEquals(2, mCallback.pagesReceived.size());
        assertEquals(2, mCallback.pagesReceived.get(1).firstNewIndex); // "c" at index 2
        assertEquals(4, mCallback.pagesReceived.get(1).size);
    }

    @Test
    public void nullContinuationResultIsEndedNotError() {
        MediaGroup first = groupWithItems("a");
        first.setNextPageKey("page2");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(first));

        mLoader.loadInitial();

        // Engine returns null for exhausted continuation -> onError with IllegalStateException
        Mockito.when(mContentService.continueGroupObserve(Mockito.any(MediaGroup.class)))
                .thenReturn(Observable.error(
                        new IllegalStateException("fromNullable result is null")));

        mLoader.loadMore();

        assertFalse(mCallback.errorReceived);
        assertTrue(mCallback.endedReceived);
        assertTrue(mLoader.hasEnded());
    }

    @Test
    public void staleGenerationDropped() throws InterruptedException {
        // First load is slow; second load cancels it
        MediaGroup first = groupWithItems("a");
        first.setNextPageKey("page2");
        Observable<MediaGroup> slow = Observable.just(first)
                .delay(java.util.concurrent.TimeUnit.MILLISECONDS, 50);

        Mockito.when(mContentService.getShortsObserve()).thenReturn(slow);

        mLoader.loadInitial();
        assertTrue(mLoader.isLoading());

        // Cancel by starting reload
        mLoader.reload();
        Thread.sleep(100); // let async settle

        // The late answer from first load must be dropped (generation bumped)
        assertFalse(mLoader.isLoading());
        assertEquals(0, mCallback.pagesReceived.size()); // first load dropped
    }

    @Test
    public void cancelStopsInFlightLoad() {
        MediaGroup group = groupWithItems("a");
        Observable<MediaGroup> never = Observable.never();
        Mockito.when(mContentService.getShortsObserve()).thenReturn(never);

        mLoader.loadInitial();
        assertTrue(mLoader.isLoading());

        mLoader.cancel();

        assertFalse(mLoader.isLoading());
        assertEquals(0, mCallback.pagesReceived.size());
    }

    @Test
    public void loadMoreNoOpWhenLoading() {
        MediaGroup first = groupWithItems("a");
        first.setNextPageKey("page2");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(first));
        Mockito.when(mContentService.continueGroupObserve(Mockito.any(MediaGroup.class)))
                .thenReturn(Observable.never());

        mLoader.loadInitial();
        mLoader.loadMore(); // should no-op because first is still loading (never emits)

        // Only one subscribe should happen
        Mockito.verify(mContentService, Mockito.times(1)).getShortsObserve();
        Mockito.verify(mContentService, Mockito.never()).continueGroupObserve(Mockito.any());
    }

    @Test
    public void loadMoreNoOpWhenEnded() {
        MediaGroup first = groupWithItems("a");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(first));

        mLoader.loadInitial();
        mLoader.loadMore(); // ended, no continuation

        assertTrue(mLoader.hasEnded());
        Mockito.verify(mContentService, Mockito.never()).continueGroupObserve(Mockito.any());
    }

    @Test
    public void reloadResetsState() {
        MediaGroup first = groupWithItems("a", "b");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(first));

        mLoader.loadInitial();
        assertEquals(2, mLoader.items().size());

        MediaGroup second = groupWithItems("c");
        Mockito.when(mContentService.getShortsObserve()).thenReturn(Observable.just(second));

        mLoader.reload();
        assertEquals(1, mLoader.items().size());
        assertEquals("c", mLoader.items().get(0).videoId);
    }

    /** Test callback capturing events. */
    private static class TestCallback implements ShortsFeedLoader.Callback {
        boolean loadingChanged;
        boolean errorReceived;
        boolean endedReceived;
        List<PageInfo> pagesReceived = new ArrayList<>();

        @Override
        public void onFeedPage(int firstNewIndex, int size) {
            pagesReceived.add(new PageInfo(firstNewIndex, size));
        }

        @Override
        public void onFeedLoadingChanged(boolean loading) {
            loadingChanged = loading;
        }

        @Override
        public void onFeedError(boolean initial, Throwable error) {
            errorReceived = true;
        }

        @Override
        public void onFeedEnded() {
            endedReceived = true;
        }

        private static class PageInfo {
            final int firstNewIndex;
            final int size;
            PageInfo(int first, int sz) { firstNewIndex = first; size = sz; }
        }
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