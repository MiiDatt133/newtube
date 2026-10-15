package com.newtube.mobile.ui.shorts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * NEWTUBE(shorts): unit tests for the feed loader — lifecycle (initial/continuation/reload),
 * generation-based stale rejection, and the engine's "fromNullable result is null" exhaustion
 * reported as end-of-feed rather than an error. ContentService is a dynamic-proxy fake:
 * no Mockito in this module's test classpath.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = android.app.Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsFeedLoaderTest {

    private FakeContentService mService;
    private TestCallback mCallback;
    private ShortsFeedLoader mLoader;

    @Before
    public void setUp() {
        mService = new FakeContentService();
        mCallback = new TestCallback();
        mLoader = new ShortsFeedLoader(mService.instance(), mCallback);
    }

    @After
    public void tearDown() {
        mLoader.cancel();
    }

    @Test
    public void initialLoadEmitsPageAndEndsWithoutContinuation() {
        mService.mShortsResult = Observable.just(groupWithItems(null, "a", "b", "c"));

        mLoader.loadInitial();

        assertTrue(mCallback.loadingChanged);
        assertEquals(1, mCallback.pagesReceived.size());
        assertEquals(0, mCallback.pagesReceived.get(0).firstNewIndex);
        assertEquals(3, mCallback.pagesReceived.get(0).size);
        assertTrue(mCallback.endedReceived);
        assertFalse(mLoader.hasMore());
    }

    @Test
    public void continuationLoadAppendsPage() {
        mService.mShortsResult = Observable.just(groupWithItems("page2", "a", "b"));
        mLoader.loadInitial();

        mService.mContinueResult = Observable.just(groupWithItems("page3", "c", "d"));
        mLoader.loadMore();

        assertEquals(2, mCallback.pagesReceived.size());
        assertEquals(2, mCallback.pagesReceived.get(1).firstNewIndex); // "c" at index 2
        assertEquals(4, mCallback.pagesReceived.get(1).size);
        assertEquals(1, mService.mContinueCalls.get());
    }

    @Test
    public void nullContinuationResultIsEndedNotError() {
        mService.mShortsResult = Observable.just(groupWithItems("page2", "a"));
        mLoader.loadInitial();

        // The engine reports an exhausted continuation as a null result, i.e. an error.
        mService.mContinueResult = Observable.error(
                new IllegalStateException("fromNullable result is null"));
        mLoader.loadMore();

        assertFalse(mCallback.errorReceived);
        assertTrue(mCallback.endedReceived);
        assertTrue(mLoader.hasEnded());
        assertFalse(mLoader.hasMore());
    }

    @Test
    public void genuineContinuationErrorIsReportedAsError() {
        mService.mShortsResult = Observable.just(groupWithItems("page2", "a"));
        mLoader.loadInitial();

        mService.mContinueResult = Observable.error(new RuntimeException("boom"));
        mLoader.loadMore();

        assertTrue(mCallback.errorReceived);
        assertFalse(mCallback.endedReceived);
        // Not ended: a retry may claim a fresh continuation load.
        assertFalse(mLoader.hasEnded());
        assertTrue(mLoader.hasMore());
    }

    @Test
    public void cancelStopsInFlightLoadAndDropsLateAnswer() {
        PublishSubject<MediaGroup> inFlight = PublishSubject.create();
        mService.mShortsResult = inFlight;
        mLoader.loadInitial();
        assertTrue(mLoader.isLoading());

        mLoader.cancel();
        assertFalse(mLoader.isLoading());

        // A late landing from the cancelled generation must be dropped.
        inFlight.onNext(groupWithItems(null, "late"));
        inFlight.onComplete();

        assertEquals(0, mCallback.pagesReceived.size());
        assertEquals(0, mLoader.items().size());
    }

    @Test
    public void reloadDropsThePreviousGenerationsAnswer() {
        PublishSubject<MediaGroup> firstFeed = PublishSubject.create();
        mService.mShortsResult = firstFeed;
        mLoader.loadInitial();
        assertTrue(mLoader.isLoading());

        // The user leaves and returns (account switch): a fresh fetch starts...
        PublishSubject<MediaGroup> secondFeed = PublishSubject.create();
        mService.mShortsResult = secondFeed;
        mLoader.reload();

        // ...then the OLD request lands. It belongs to a dead generation: dropped.
        firstFeed.onNext(groupWithItems(null, "stale"));
        firstFeed.onComplete();
        assertEquals(0, mLoader.items().size());

        // The current generation lands normally.
        secondFeed.onNext(groupWithItems(null, "fresh"));
        secondFeed.onComplete();
        assertEquals(1, mLoader.items().size());
        assertEquals("fresh", mLoader.items().get(0).videoId);
    }

    @Test
    public void loadMoreNoOpWhenLoadingOrEnded() {
        mService.mShortsResult = Observable.never();
        mLoader.loadInitial();
        mLoader.loadMore(); // in flight: no second request
        assertEquals(0, mService.mContinueCalls.get());

        mLoader.cancel();
        mService.mShortsResult = Observable.just(groupWithItems(null, "a")); // no key -> ended
        mLoader.reload();
        mLoader.loadMore(); // ended: no continuation request
        assertEquals(0, mService.mContinueCalls.get());
        assertTrue(mLoader.hasEnded());
    }

    @Test
    public void initialErrorAllowsRetryThroughReload() {
        mService.mShortsResult = Observable.error(new RuntimeException("network"));
        mLoader.loadInitial();

        assertTrue(mCallback.errorReceived);
        assertEquals(0, mLoader.items().size());

        mService.mShortsResult = Observable.just(groupWithItems(null, "a"));
        mLoader.reload();

        assertEquals(1, mLoader.items().size());
        assertEquals("a", mLoader.items().get(0).videoId);
    }

    // ---------------------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------------------

    /** ContentService proxy: replays configured observables, counts continuation calls. */
    private static final class FakeContentService {
        Observable<MediaGroup> mShortsResult = Observable.empty();
        Observable<MediaGroup> mContinueResult = Observable.empty();
        final AtomicInteger mContinueCalls = new AtomicInteger();

        ContentService instance() {
            return (ContentService) Proxy.newProxyInstance(
                    ContentService.class.getClassLoader(),
                    new Class<?>[]{ContentService.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getShortsObserve":
                                return mShortsResult;
                            case "continueGroupObserve":
                                mContinueCalls.incrementAndGet();
                                return mContinueResult;
                            case "toString":
                                return "FakeContentService";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }

    private static final class TestCallback implements ShortsFeedLoader.Callback {
        boolean loadingChanged;
        boolean errorReceived;
        boolean endedReceived;
        final List<PageInfo> pagesReceived = new ArrayList<>();

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

        private static final class PageInfo {
            final int firstNewIndex;
            final int size;
            PageInfo(int first, int sz) { firstNewIndex = first; size = sz; }
        }
    }

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