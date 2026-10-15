package com.newtube.mobile.ui.shorts;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NEWTUBE(shorts): the pure state of the dedicated Shorts feed - which Shorts are on screen, which
 * page comes next, and when the source is spent. The feed source is the engine's own Shorts
 * endpoint (ContentService.getShortsObserve / continueGroupObserve, i.e. the TV subscriptions
 * Shorts plus the WEB reel feed), whose pages are Shorts-only by construction: every item with a
 * videoId is admitted, whether or not a marker survived the trip (a Short is never judged by its
 * duration - see ShortsFilter). ShortsFilter keeps filtering every ordinary list; this state only
 * ever receives the dedicated feed's groups.
 *
 * <p>Kept free of Android and Rx so the rules are unit-testable: dedupe by videoId, at most one
 * page in flight ({@link #beginLoad}), the continuation of the LAST landed group, and an end when
 * a landed group carries no next page key (or repeats the previous key - a server that answers
 * the same page forever must not loop the app).
 */
public final class ShortsFeedState {
    private final List<Video> mItems = new ArrayList<>();
    private final Set<String> mSeenIds = new HashSet<>();
    private MediaGroup mNextPage;
    private String mNextPageKey;
    private boolean mEnded;
    private boolean mLoading;
    private boolean mInitialRequested;

    /** The feed as it stands; live list, read-only for callers. */
    public List<Video> items() {
        return mItems;
    }

    public boolean isLoading() {
        return mLoading;
    }

    /** No continuation is known: the feed has run out (an empty answer ends too). */
    public boolean hasEnded() {
        return mEnded;
    }

    public boolean hasMore() {
        return !mEnded;
    }

    /** First page not requested yet: the initial load uses ContentService.getShortsObserve(). */
    public boolean needsInitial() {
        return !mInitialRequested;
    }

    /** A continuation may start: the initial page has landed a group and the source is not spent. */
    public boolean needsMore() {
        return mInitialRequested && mNextPage != null && !mEnded;
    }

    /** The group whose continuation fetches the next page, or null when none is known. */
    public MediaGroup nextPage() {
        return mNextPage;
    }

    /**
     * Whether a load (initial or continuation) may start now; claims it by setting
     * {@link #isLoading}. The caller must pair every claim with {@link #endLoad}.
     */
    public boolean beginLoad() {
        if (mLoading || mEnded) {
            return false;
        }
        if (!mInitialRequested) {
            mInitialRequested = true;
            mLoading = true;
            return true;
        }
        if (mNextPage == null) {
            return false;
        }
        mLoading = true;
        return true;
    }

    /**
     * A page has landed. Items with no videoId are skipped and duplicates (same videoId as an
     * item already on screen) are dropped. The group becomes the next continuation source only
     * when it carries a next page key; a group without one ends the feed once the load that
     * delivered it completes.
     *
     * @return the index of the first newly added item, or -1 when the page added nothing
     */
    public int acceptGroup(MediaGroup group) {
        if (group == null) {
            return -1;
        }

        List<MediaItem> mediaItems = group.getMediaItems();
        int firstNew = -1;

        if (mediaItems != null) {
            for (MediaItem item : mediaItems) {
                if (item == null || item.getVideoId() == null || mSeenIds.contains(item.getVideoId())) {
                    continue;
                }
                mSeenIds.add(item.getVideoId());
                if (firstNew < 0) {
                    firstNew = mItems.size();
                }
                mItems.add(Video.from(item));
            }
        }

        String key = group.getNextPageKey();
        if (key != null && !key.equals(mNextPageKey)) {
            mNextPageKey = key;
            mNextPage = group;
            // A key after a keyless group (getShortsObserve's TV partial, then the WEB reel feed)
            // reopens the continuation the keyless group had closed.
            mEnded = false;
        } else if (key == null) {
            // No continuation on this group. The feed ends when the load that carried it is done
            // (getShortsObserve may still emit a second group - TV partial then WEB).
            mNextPage = null;
            mNextPageKey = null;
            mEnded = true;
        } else {
            // Same key as the group already on file: a server repeating itself. End honestly
            // instead of requesting the same page forever.
            mEnded = true;
        }

        return firstNew;
    }

    /**
     * The load that was claimed by {@link #beginLoad} is over. A load that errored is not counted
     * as delivering anything: the end-of-feed verdict stays with the last landed group, and a
     * retry may claim a fresh load.
     */
    public void endLoad(boolean error) {
        mLoading = false;
        if (error && mItems.isEmpty()) {
            // A failed initial load never landed a group; let the next attempt be an initial one.
            mInitialRequested = false;
        }
    }

    /** A fresh feed (retry, account switch): nothing on screen, nothing remembered. */
    public void reset() {
        mItems.clear();
        mSeenIds.clear();
        mNextPage = null;
        mNextPageKey = null;
        mEnded = false;
        mLoading = false;
        mInitialRequested = false;
    }

    /**
     * The continuation source itself answered "nothing" (the engine's
     * {@code continueGroupObserve} reports an exhausted source as a null result, i.e. an error):
     * the feed is over, whatever the last group's key said.
     */
    public void markEnded() {
        mEnded = true;
        mNextPage = null;
    }
}
