package com.newtube.mobile.ui.shorts;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.sharedutils.rx.RxHelper;

import java.util.List;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * NEWTUBE(shorts): fetches the dedicated Shorts feed through the engine's own Shorts endpoints
 * ({@code ContentService.getShortsObserve} for the first page - the TV subscriptions Shorts when
 * signed in, else the WEB reel feed - and {@code ContentService.continueGroupObserve} for every
 * later page). {@link ShortsFeedState} owns the admission/dedupe/end rules; this class owns the
 * request lifecycle: at most one request in flight, a generation counter so a cancelled load's
 * late answer can never touch a newer feed, and cancellation on leave/account switch.
 *
 * <p>Callbacks arrive on the main thread (the service observables are created with
 * {@code RxHelper.create}, which observes on the main looper).
 */
public final class ShortsFeedLoader {
    /** Feed events; all on the main thread. */
    public interface Callback {
        /** A page landed: items {@code [firstNewIndex, size)} are new. */
        void onFeedPage(int firstNewIndex, int size);

        void onFeedLoadingChanged(boolean loading);

        /** {@code initial} tells a first-page failure (empty screen, retry) from a mid-feed one. */
        void onFeedError(boolean initial, Throwable error);

        /** The source has no more pages. */
        void onFeedEnded();
    }

    private final ContentService mContentService;
    private final Callback mCallback;
    private final ShortsFeedState mState = new ShortsFeedState();
    private Disposable mAction;
    /** Bumped on every cancel/reload: an emission from an older generation is dropped. */
    private int mGeneration;

    public ShortsFeedLoader(ContentService contentService, Callback callback) {
        mContentService = contentService;
        mCallback = callback;
    }

    public List<Video> items() {
        return mState.items();
    }

    public boolean hasMore() {
        return mState.hasMore();
    }

    public boolean isLoading() {
        return mState.isLoading();
    }

    /** First page (or a retry after a failure): drop everything and fetch anew. */
    public void reload() {
        cancel();
        mState.reset();
        startLoad(true);
    }

    /** The first page when nothing has started yet; later calls are no-ops. */
    public void loadInitial() {
        if (mState.needsInitial()) {
            startLoad(true);
        }
    }

    public boolean hasEnded() {
        return mState.hasEnded();
    }

    /** Fetch the next page when the feed has one and none is in flight. */
    public void loadMore() {
        if (mState.isLoading() || !mState.needsMore()) {
            return;
        }
        startLoad(false);
    }

    /** Abort the request in flight; a late answer from it can no longer land. */
    public void cancel() {
        mGeneration++;
        RxHelper.disposeActions(mAction);
        mAction = null;
        if (mState.isLoading()) {
            mState.endLoad(true);
        }
    }

    /** Initial: {@code getShortsObserve} (one or two groups). Continuation: the last group's key. */
    private void startLoad(boolean initial) {
        if (!mState.beginLoad()) {
            return;
        }
        final int generation = mGeneration;
        final boolean loadInitial = initial;
        final int sizeBefore = mState.items().size();

        mCallback.onFeedLoadingChanged(true);

        io.reactivex.rxjava3.core.Observable<MediaGroup> observable = initial
                ? mContentService.getShortsObserve()
                : mContentService.continueGroupObserve(mState.nextPage());

        final int[] firstNew = {-1};

        mAction = observable.subscribe(
                group -> {
                    if (generation != mGeneration) {
                        return;
                    }
                    int index = mState.acceptGroup(group);
                    if (index >= 0 && (firstNew[0] < 0 || index < firstNew[0])) {
                        firstNew[0] = index;
                    }
                },
                error -> {
                    if (generation != mGeneration) {
                        return;
                    }
                    mAction = null;
                    // The engine reports an exhausted continuation as a null result, i.e. an
                    // error ("fromNullable result is null"): that is the feed's honest end, not
                    // a failure to surface.
                    if (!loadInitial && error instanceof IllegalStateException
                            && "fromNullable result is null".equals(error.getMessage())) {
                        mState.endLoad(false);
                        mState.markEnded();
                        mCallback.onFeedLoadingChanged(false);
                        mCallback.onFeedEnded();
                        return;
                    }
                    mState.endLoad(true);
                    mCallback.onFeedLoadingChanged(false);
                    mCallback.onFeedError(loadInitial, error);
                },
                () -> {
                    if (generation != mGeneration) {
                        return;
                    }
                    mAction = null;
                    mState.endLoad(false);
                    mCallback.onFeedLoadingChanged(false);
                    int size = mState.items().size();
                    if (size > sizeBefore) {
                        int from = firstNew[0] >= 0 ? firstNew[0] : sizeBefore;
                        mCallback.onFeedPage(from, size);
                    }
                    if (mState.hasEnded()) {
                        mCallback.onFeedEnded();
                    }
                });
    }
}
