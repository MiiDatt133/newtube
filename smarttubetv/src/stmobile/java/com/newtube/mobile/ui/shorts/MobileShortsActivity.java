package com.newtube.mobile.ui.shorts;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.RatingWriter;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEngineEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerUiEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.ViewEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.player.Media3PlayerInitializer;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.playback.CommentsPanel;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * NEWTUBE(shorts): the dedicated vertical Shorts feed behind the bottom nav's Shorts tab.
 *
 * <p>One Short per page (RecyclerView + {@link PagerSnapHelper}: swipe up for the next, down for
 * the previous), autoplay of the snapped page, metadata overlay, thin progress, mute, and an
 * honest skeleton / empty-or-error surface. Data is the engine's own Shorts feed
 * ({@link ShortsFeedLoader} -> ContentService.getShortsObserve / continueGroupObserve): real
 * Shorts, real continuations, deduped, ending when the source ends. ShortsFilter still filters
 * every ordinary list; this screen is the one place Shorts are meant to appear.
 *
 * <p>Playback reuses the watch page's stack - one {@link Media3PlayerController} over one
 * {@link ExoPlayer} built by {@link Media3PlayerInitializer} - never one player per page. The
 * single player is handed the current page's TextureView surface on page selection and dropped
 * when the page is recycled. A media 403 is recovered the way the watch page does (route memory
 * + fresh URLs via applyNoPlaybackFix, at most twice), never by retrying the same links.
 */
public class MobileShortsActivity extends MobileActivity implements
        ShortsFeedLoader.Callback, ShortsPagerAdapter.SurfaceHost {
    /** How many pages from the end the next page is fetched. */
    private static final int NEAR_END_PAGES = 2;
    /** Media-route recoveries per open before the error surface takes over. */
    private static final int MAX_OPEN_ATTEMPTS = 2;
    private static final long TICK_MS = 500;

    private RecyclerView mPager;
    private LinearLayoutManager mLayoutManager;
    private PagerSnapHelper mSnapHelper;
    private ShortsPagerAdapter mAdapter;
    private ProgressBar mBuffer;
    private ProgressBar mProgress;
    private ImageButton mBackButton;
    private ImageButton mMuteButton;
    private ImageButton mLikeButton;
    private ImageButton mDislikeButton;
    private ImageButton mCommentButton;
    private ImageButton mShareButton;
    private TextView mLikeCount;
    private TextView mCommentCount;
    private ImageView mCenterIcon;
    private ProgressBar mSkeleton;
    private View mMessageContainer;
    private TextView mMessage;
    private MaterialButton mRetry;

    private Media3PlayerInitializer mPlayerInitializer;
    private Media3PlayerController mPlayerController;
    private ExoPlayer mPlayer;

    private ShortsFeedLoader mLoader;
    private Disposable mResolveAction;
    private Disposable mMetadataAction;

    /** The rail's writes: one at a time in tap order, rollback on failure (like the watch page). */
    private final RatingWriter mRatingWriter = new RatingWriter();
    private CommentsPanel mCommentsPanel;
    @Nullable private String mCommentsKey;
    private int mLikeStatus = MediaItemMetadata.LIKE_STATUS_INDIFFERENT;

    @Nullable private Video mCurrentVideo;
    @Nullable private TextureView mAttachedTexture;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mCurrentPosition = RecyclerView.NO_POSITION;
    private int mOpenGeneration;
    private int mOpenAttempts;
    private boolean mMuted;
    private boolean mFeedShown;
    /** The person tapped the video to pause it; swipes, page changes and resume clear it. */
    private boolean mUserPaused;
    /** The comment panel covered the video and paused it; closing it resumes. */
    private boolean mPanelPaused;
    @Nullable private String mAccountKey;

    private final Runnable mTicker = this::onTick;

    private final Player.Listener mUiListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
                mBuffer.setVisibility(View.VISIBLE);
            } else if (playbackState == Player.STATE_READY) {
                mBuffer.setVisibility(View.GONE);
                mProgress.setVisibility(View.VISIBLE);
            } else if (playbackState == Player.STATE_ENDED) {
                advanceToNextPage();
            }
        }

        @Override
        public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
            mVideoWidth = videoSize.width;
            mVideoHeight = videoSize.height;
            ShortsPagerAdapter.Holder holder = currentHolder();
            if (holder != null) {
                holder.setVideoSize(mVideoWidth, mVideoHeight);
            }
        }
    };

    /** Engine -> screen translation; recovery mirrors ErrorFixerController's media-403 habit. */
    private final PlayerEventListener mPlayerListener = new PlayerEventListener() {
        @Override public void onPlay() { mBuffer.setVisibility(View.GONE); }
        @Override public void onPause() { /* progress stays; the ticker pauses itself */ }
        @Override public void onPlayEnd() { /* ENDED handled by the media3 listener */ }
        @Override public void onBuffering() { mBuffer.setVisibility(View.VISIBLE); }
        @Override public void onSeekEnd() { }
        @Override public void onSeekPositionChanged(long positionMs) { }
        @Override public void onSpeedChanged(float speed) { }
        @Override public void onSourceChanged(Video item) { }
        @Override public void onVideoLoaded(Video item) { }
        @Override public void onEngineInitialized() { }
        @Override public void onEngineReleased() { }
        @Override public void onTrackChanged(FormatItem track) { }
        @Override public void onTrackSelected(FormatItem track) { }

        @Override
        public void onEngineError(int type, int rendererIndex, Throwable error) {
            onPlaybackError(type, error);
        }

        @Override public void onNewVideo(Video item) { }
        @Override public void onMetadata(com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata metadata) { }
        @Override public void onInit() { }
        @Override public void onFinish() { }
        @Override public void onSuggestionItemClicked(Video item) { }
        @Override public void onSuggestionItemLongClicked(Video item) { }
        @Override public void onScrollEnd(Video item) { }
        @Override public boolean onPreviousClicked() { return false; }
        @Override public boolean onNextClicked() { return false; }
        @Override public void onPlayClicked() { }
        @Override public void onPauseClicked() { }
        @Override public boolean onKeyDown(int keyCode) { return false; }
        @Override public void onButtonClicked(int buttonId, int buttonState) { }
        @Override public void onButtonLongClicked(int buttonId, int buttonState) { }
        @Override public void onControlsShown(boolean shown) { }
        @Override public void onViewCreated() { }
        @Override public void onViewDestroyed() { }
        @Override public void onViewPaused() { }
        @Override public void onViewResumed() { }
        @Override public void onTickle() { }
    };

    public static Intent intent(Context context) {
        return new Intent(context, MobileShortsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_shorts);

        mPager = findViewById(R.id.mobile_shorts_pager);
        mBuffer = findViewById(R.id.mobile_shorts_buffer);
        mProgress = findViewById(R.id.mobile_shorts_progress);
        mBackButton = findViewById(R.id.mobile_shorts_back);
        mMuteButton = findViewById(R.id.mobile_shorts_mute);
        mLikeButton = findViewById(R.id.mobile_shorts_like);
        mDislikeButton = findViewById(R.id.mobile_shorts_dislike);
        mCommentButton = findViewById(R.id.mobile_shorts_comment);
        mShareButton = findViewById(R.id.mobile_shorts_share);
        mLikeCount = findViewById(R.id.mobile_shorts_like_count);
        mCommentCount = findViewById(R.id.mobile_shorts_comment_count);
        mCenterIcon = findViewById(R.id.mobile_shorts_center_icon);
        mSkeleton = findViewById(R.id.mobile_shorts_skeleton);
        mMessageContainer = findViewById(R.id.mobile_shorts_message_container);
        mMessage = findViewById(R.id.mobile_shorts_message);
        mRetry = findViewById(R.id.mobile_shorts_retry);

        setupPager();
        createPlayerObjects();

        mLoader = new ShortsFeedLoader(YouTubeServiceManager.instance().getContentService(), this);
        mAccountKey = currentAccountKey();
        mCommentsPanel = new CommentsPanel(this, findViewById(R.id.mobile_comments_panel), mCommentsHost);

        mBackButton.setOnClickListener(v -> finish());
        mRetry.setOnClickListener(v -> {
            hideMessage();
            if (mCurrentVideo == null) {
                mLoader.reload();
            } else {
                retryCurrentVideo();
            }
        });
        mMuteButton.setOnClickListener(v -> toggleMute());
        mLikeButton.setOnClickListener(v -> toggleRating(true));
        mDislikeButton.setOnClickListener(v -> toggleRating(false));
        mCommentButton.setOnClickListener(v -> openComments());
        mShareButton.setOnClickListener(v -> shareCurrent());
        updateRatingButtons();

        applyChromeInsets();
        mLoader.loadInitial();
    }

    private void setupPager() {
        mLayoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        mPager.setLayoutManager(mLayoutManager);
        mAdapter = new ShortsPagerAdapter(this);
        mPager.setAdapter(mAdapter);
        mSnapHelper = new PagerSnapHelper();
        mSnapHelper.attachToRecyclerView(mPager);
        mPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    onPagerSettled();
                }
            }
        });
    }

    private void createPlayerObjects() {
        mPlayerController = new Media3PlayerController(this, mPlayerListener);
        mPlayerInitializer = new Media3PlayerInitializer(this);
        DefaultTrackSelector trackSelector = mPlayerInitializer.createTrackSelector();
        mPlayerController.setTrackSelector(trackSelector);
        mPlayer = mPlayerInitializer.createPlayer(
                trackSelector, mPlayerController.getMediaSourceFactory().getBandwidthMeter());
        mPlayer.setPlayWhenReady(false);
        mPlayer.addListener(mUiListener);
        mPlayerController.setPlayer(mPlayer);
    }

    /** Full-bleed video; the chrome (back, progress, mute, metadata) takes the insets itself. */
    @Override
    protected boolean shouldInsetContentForSystemBars() {
        return false;
    }

    private void applyChromeInsets() {
        View root = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            mBackButton.setPadding(mBackButton.getPaddingLeft(),
                    insets.top + pad, mBackButton.getPaddingRight(), mBackButton.getPaddingBottom());
            mMuteButton.setPadding(mMuteButton.getPaddingLeft(), mMuteButton.getPaddingTop(),
                    mMuteButton.getPaddingRight(), insets.bottom + 4 * pad);
            mProgress.setTranslationY(-insets.bottom);
            View rail = findViewById(R.id.mobile_shorts_rail);
            if (rail != null) {
                rail.setTranslationY(-insets.bottom);
            }
            // The comments panel is a full-screen sheet here (on the watch page it sits under the
            // video): keep its header below the status bar and its list above the nav bar.
            View panel = findViewById(R.id.mobile_comments_panel);
            if (panel != null) {
                panel.setPadding(panel.getPaddingLeft(), insets.top,
                        panel.getPaddingRight(), insets.bottom);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    // ---------------------------------------------------------------------------------
    // Feed
    // ---------------------------------------------------------------------------------

    @Override
    public void onFeedPage(int firstNewIndex, int size) {
        mAdapter.append(mLoader.items(), firstNewIndex);
        if (!mFeedShown) {
            mFeedShown = true;
            setSkeletonVisible(false);
            if (mCurrentPosition == RecyclerView.NO_POSITION && size > 0) {
                mPager.scrollToPosition(0);
                onPagerSettled();
            }
        }
        NetPath.log("shorts feed page first=" + firstNewIndex + " size=" + size);
    }

    @Override
    public void onFeedLoadingChanged(boolean loading) {
        if (!mFeedShown) {
            setSkeletonVisible(loading);
        }
        if (!loading && mFeedShown && mLoader.items().isEmpty()) {
            // Landed, nothing to show, and nothing more coming: an honest empty state.
            showMessage(getString(R.string.mobile_shorts_empty), false);
        }
    }

    @Override
    public void onFeedError(boolean initial, Throwable error) {
        NetPath.log("shorts feed error initial=" + initial + " " + error);
        if (initial || mLoader.items().isEmpty()) {
            setSkeletonVisible(false);
            boolean offline = com.liskovsoft.sharedutils.helpers.Helpers.startsWithAny(
                    String.valueOf(error.getMessage()), "Unable to resolve host", "Failed to connect",
                    "timeout", "Network is unreachable");
            showMessage(getString(offline
                    ? R.string.mobile_empty_no_connection : R.string.mobile_shorts_load_error), true);
        }
        // A mid-feed page failure keeps what is on screen; the next scroll-end retries once the
        // loading flag is clear (loadMore checks needsMore, still true).
    }

    @Override
    public void onFeedEnded() {
        NetPath.log("shorts feed end size=" + mLoader.items().size());
    }

    private void setSkeletonVisible(boolean visible) {
        mSkeleton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showMessage(CharSequence text, boolean retry) {
        mMessage.setText(text);
        mMessageContainer.setVisibility(View.VISIBLE);
        mRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private void hideMessage() {
        mMessageContainer.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------------------
    // Paging + playback
    // ---------------------------------------------------------------------------------

    private void onPagerSettled() {
        View snapView = mSnapHelper.findSnapView(mLayoutManager);
        int position = snapView != null ? mPager.getChildAdapterPosition(snapView)
                : RecyclerView.NO_POSITION;
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        selectPage(position);
    }

    private void selectPage(int position) {
        if (position == mCurrentPosition) {
            maybeLoadMore(position);
            return;
        }
        mCurrentPosition = position;
        mUserPaused = false; // a fresh page starts playing, whatever the last page did
        updateCenterIcon();

        Video video = mAdapter.itemAt(position);
        attachPageSurface(position);

        if (video == null || video.videoId == null) {
            return;
        }
        if (mCurrentVideo != null && video.videoId.equals(mCurrentVideo.videoId)
                && mPlayer != null && mPlayer.getMediaItemCount() > 0) {
            mPlayer.setPlayWhenReady(true); // back on a played page: resume it
            return;
        }
        openShort(video);
        maybeLoadMore(position);
    }

    private void maybeLoadMore(int position) {
        int size = mAdapter.getItemCount();
        if (size > 0 && position >= size - 1 - NEAR_END_PAGES) {
            mLoader.loadMore();
        }
    }

    private void advanceToNextPage() {
        int next = mCurrentPosition + 1;
        if (next < mAdapter.getItemCount()) {
            mPager.smoothScrollToPosition(next);
        }
        // Past the last page the pager stays put; the loader's near-end fetch extends the feed.
    }

    /** The holder of the settled page, when one is laid out. */
    @Nullable
    private ShortsPagerAdapter.Holder currentHolder() {
        if (mCurrentPosition == RecyclerView.NO_POSITION) {
            return null;
        }
        RecyclerView.ViewHolder holder = mPager.findViewHolderForAdapterPosition(mCurrentPosition);
        return holder instanceof ShortsPagerAdapter.Holder ? (ShortsPagerAdapter.Holder) holder : null;
    }

    /** Hand the single player the settled page's TextureView (media3 manages the Surface). */
    private void attachPageSurface(int position) {
        RecyclerView.ViewHolder holder = mPager.findViewHolderForAdapterPosition(position);
        if (!(holder instanceof ShortsPagerAdapter.Holder)) {
            return;
        }
        ShortsPagerAdapter.Holder page = (ShortsPagerAdapter.Holder) holder;
        mAttachedTexture = page.texture;
        if (mPlayer != null) {
            mPlayer.setVideoTextureView(page.texture);
            page.setVideoSize(mVideoWidth, mVideoHeight);
        }
    }

    @Override
    public void onShortSurfaceAvailable(TextureView textureView, int videoWidth, int videoHeight) {
        ShortsPagerAdapter.Holder holder = currentHolder();
        if (holder == null || holder.texture != textureView || mPlayer == null) {
            return;
        }
        mAttachedTexture = textureView;
        mPlayer.setVideoTextureView(textureView);
        holder.setVideoSize(mVideoWidth, mVideoHeight);
    }

    @Override
    public void onShortSurfaceLost(TextureView textureView) {
        if (mAttachedTexture == textureView && mPlayer != null) {
            mPlayer.clearVideoTextureView();
            mAttachedTexture = null;
        }
    }

    private void openShort(Video video) {
        mCurrentVideo = video;
        mOpenAttempts = 0;
        mOpenGeneration++;
        hideMessage();
        mBuffer.setVisibility(View.VISIBLE);
        mUserPaused = false;
        updateCenterIcon();
        resetRatingUi();
        // The panel's feeds belong to the previous video; a stale set must never show under the
        // new one (its own source lands with the metadata below).
        mCommentsPanel.setSource(null, null, null, null);
        MediaServiceManager.noteChannel(video);
        resolveCurrent();
        loadMetadata();
    }

    private void retryCurrentVideo() {
        if (mCurrentVideo == null) {
            return;
        }
        mOpenAttempts = 0;
        mOpenGeneration++;
        mBuffer.setVisibility(View.VISIBLE);
        resolveCurrent();
    }

    private void resolveCurrent() {
        final int generation = mOpenGeneration;
        final Video video = mCurrentVideo;
        if (video == null || video.videoId == null) {
            return;
        }
        RxDispose(mResolveAction);
        mResolveAction = YouTubeServiceManager.instance().getMediaItemService()
                .getFormatInfoObserve(video.videoId)
                .subscribe(
                        info -> onFormatInfo(generation, video, info),
                        error -> onResolveError(generation, video, error));
    }

    private void onFormatInfo(int generation, Video video, MediaItemFormatInfo info) {
        if (generation != mOpenGeneration || mCurrentVideo != video || isFinishing() || isDestroyed()) {
            return;
        }
        mBuffer.setVisibility(View.GONE);
        video.sync(info);

        if (info.isUnplayable()) {
            showPlaybackError(info.getPlayabilityReason());
            return;
        }

        // Same route choice the watch page's VideoLoaderController makes, in its order of
        // preference: DASH (generated MPD) for regular VOD, HLS/live URLs when that is all there
        // is, progressive url list as the last resort.
        if (info.isLive() && info.containsDashUrl()) {
            mPlayerController.openDashUrl(info.getDashManifestUrl());
        } else if (info.containsDashFormats()) {
            mPlayerController.openDash(info);
        } else if (info.containsHlsUrl()) {
            mPlayerController.openHlsUrl(info.getHlsManifestUrl());
        } else if (info.containsUrlFormats()) {
            mPlayerController.openProgressive(info);
        } else {
            showPlaybackError(info.getPlayabilityReason());
            return;
        }
        mPlayer.setPlayWhenReady(!mUserPaused);
    }

    private void onResolveError(int generation, Video video, Throwable error) {
        if (generation != mOpenGeneration || mCurrentVideo != video || isFinishing() || isDestroyed()) {
            return;
        }
        NetPath.log("shorts resolve error video=" + video.videoId + " " + error);
        // A /player failure recovers once through the engine's own fix (fresh route), like the
        // watch page does; a second failure is honest and lands on the error surface.
        if (mOpenAttempts++ < MAX_OPEN_ATTEMPTS) {
            VideoInfoService.instance().anchorRouteToVideo(video.videoId);
            YouTubeServiceManager.instance().applyNoPlaybackFix();
            resolveCurrent();
            return;
        }
        mBuffer.setVisibility(View.GONE);
        showPlaybackError(getString(R.string.mobile_shorts_load_error));
    }

    /** Media 403 recovery: remember the refused route, mint fresh URLs, reload this Short. */
    private void onPlaybackError(int type, Throwable error) {
        if (mCurrentVideo == null || isFinishing() || isDestroyed()) {
            return;
        }
        String detail = error != null ? String.valueOf(error.getMessage()) : "";
        NetPath.log("shorts player error type=" + type + " video=" + mCurrentVideo.videoId
                + " " + detail);
        mBuffer.setVisibility(View.GONE);
        if (mOpenAttempts++ < MAX_OPEN_ATTEMPTS) {
            VideoInfoService.instance().anchorRouteToVideo(mCurrentVideo.videoId);
            if (detail.contains("403")) {
                VideoInfoService.instance().markCurrentPlaybackRouteForbidden();
            }
            YouTubeServiceManager.instance().applyNoPlaybackFix();
            resolveCurrent();
            return;
        }
        showPlaybackError(getString(R.string.mobile_shorts_load_error));
    }

    private void showPlaybackError(CharSequence reason) {
        mBuffer.setVisibility(View.GONE);
        boolean offline = com.liskovsoft.sharedutils.helpers.Helpers.startsWithAny(
                String.valueOf(reason), "Unable to resolve host", "Failed to connect",
                "timeout", "Network is unreachable");
        showMessage(offline ? getString(R.string.mobile_empty_no_connection)
                : (reason != null && reason.length() > 0 && !offline && isReasonReadable(reason)
                        ? reason : getString(R.string.mobile_shorts_load_error)), true);
    }

    /** Playability reasons are short sentences ("Video unavailable"); never raw dumps. */
    private static boolean isReasonReadable(CharSequence reason) {
        String text = reason.toString();
        return text.length() < 120 && !text.contains("\n") && !text.contains("Exception")
                && !text.contains("http");
    }

    // ---------------------------------------------------------------------------------
    // Tap-to-pause, action rail, comments
    // ---------------------------------------------------------------------------------

    /** A tap (not a swipe) on the page: YouTube Shorts' play/pause. */
    @Override
    public void onShortTapped() {
        if (mPlayer == null || mCurrentVideo == null) {
            return;
        }
        boolean play = !mPlayer.getPlayWhenReady();
        mUserPaused = !play;
        mPlayer.setPlayWhenReady(play);
        updateCenterIcon();
    }

    private void updateCenterIcon() {
        mCenterIcon.setVisibility(mUserPaused ? View.VISIBLE : View.GONE);
    }

    /** Like/dislike through the watch page's queued writer: optimistic UI, rollback on failure. */
    private void toggleRating(boolean like) {
        Video video = mCurrentVideo;
        if (video == null || video.videoId == null) {
            return;
        }
        if (!YouTubeSignInService.instance().isSigned()) {
            MessageHelpers.showMessage(this, R.string.msg_signed_users_only);
            return;
        }
        final int before = mLikeStatus;
        final int after;
        if (like) {
            after = before == MediaItemMetadata.LIKE_STATUS_LIKE
                    ? MediaItemMetadata.LIKE_STATUS_INDIFFERENT
                    : MediaItemMetadata.LIKE_STATUS_LIKE;
        } else {
            after = before == MediaItemMetadata.LIKE_STATUS_DISLIKE
                    ? MediaItemMetadata.LIKE_STATUS_INDIFFERENT
                    : MediaItemMetadata.LIKE_STATUS_DISLIKE;
        }
        if (after == before) {
            return;
        }
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        MediaItem item = video.mediaItem != null ? video.mediaItem : video.toMediaItem();
        Observable<Void> call;
        if (like) {
            call = after == MediaItemMetadata.LIKE_STATUS_LIKE
                    ? service.setLikeObserve(item) : service.removeLikeObserve(item);
        } else {
            call = after == MediaItemMetadata.LIKE_STATUS_DISLIKE
                    ? service.setDislikeObserve(item) : service.removeDislikeObserve(item);
        }
        mLikeStatus = after;
        updateRatingButtons();
        mRatingWriter.write(video.videoId, before, after, call, (videoId, rating) -> {
            if (mCurrentVideo == null || isFinishing() || isDestroyed()
                    || !videoId.equals(mCurrentVideo.videoId)) {
                return; // the screen moved on; the next video's metadata shows the truth
            }
            mLikeStatus = rating;
            updateRatingButtons();
            MessageHelpers.showMessage(MobileShortsActivity.this,
                    R.string.mobile_rating_not_saved);
        });
    }

    private void updateRatingButtons() {
        boolean liked = mLikeStatus == MediaItemMetadata.LIKE_STATUS_LIKE;
        boolean disliked = mLikeStatus == MediaItemMetadata.LIKE_STATUS_DISLIKE;
        mLikeButton.setImageResource(liked ? R.drawable.ic_watch_thumb_up
                : R.drawable.ic_watch_thumb_up_outline);
        // YouTube's "liked" blue; white on the rail otherwise.
        mLikeButton.setColorFilter(liked ? 0xFF3EA6FF : getColor(R.color.mobile_player_on_video));
        mDislikeButton.setImageResource(disliked ? R.drawable.ic_watch_thumb_down
                : R.drawable.ic_watch_thumb_down_outline);
        mDislikeButton.setColorFilter(getColor(R.color.mobile_player_on_video));
    }

    private void resetRatingUi() {
        mLikeStatus = MediaItemMetadata.LIKE_STATUS_INDIFFERENT;
        mCommentsKey = null;
        mLikeCount.setText(null);
        mLikeCount.setVisibility(View.GONE);
        mCommentCount.setText(null);
        mCommentCount.setVisibility(View.GONE);
        updateRatingButtons();
    }

    private void openComments() {
        if (mCommentsKey == null || mCommentsPanel == null) {
            return; // metadata has not named the comments yet (or the video has none)
        }
        mCommentsPanel.open();
    }

    private void shareCurrent() {
        Video video = mCurrentVideo;
        if (video == null || video.videoId == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + video.videoId);
        startActivity(Intent.createChooser(send, getString(R.string.mobile_watch_share_subject)));
    }

    private final CommentsPanel.Host mCommentsHost = new CommentsPanel.Host() {
        @Override
        public void onCommentTimestamp(long positionMs) {
            if (mPlayer != null
                    && mPlayer.getDuration() != androidx.media3.common.C.TIME_UNSET
                    && positionMs <= mPlayer.getDuration()) {
                mPlayer.seekTo(positionMs);
            }
        }

        @Override
        public void onCommentVideoLink(String videoId) {
            int index = mAdapter.indexOf(videoId);
            if (index != RecyclerView.NO_POSITION && mCommentsPanel != null) {
                mCommentsPanel.close();
                mPager.smoothScrollToPosition(index);
            }
        }

        @Override
        public void onCommentsPanelShown(boolean shown) {
            if (mPlayer == null) {
                return;
            }
            if (shown) {
                // Covering the Short pauses it; closing resumes it unless the person paused it.
                mPanelPaused = !mUserPaused && mPlayer.getPlayWhenReady();
                if (mPanelPaused) {
                    mPlayer.setPlayWhenReady(false);
                }
            } else if (mPanelPaused) {
                mPanelPaused = false;
                if (!mUserPaused) {
                    mPlayer.setPlayWhenReady(true);
                }
            }
        }
    };

    // ---------------------------------------------------------------------------------
    // Metadata (rating state + comments source)
    // ---------------------------------------------------------------------------------

    /**
     * The engine's /next for this Short: like status and counts for the rail, and the comments
     * keys the panel needs. Its own disposable (not MediaServiceManager's shared one) so a Shorts
     * fetch can never cancel a metadata load the docked watch page is waiting on.
     */
    private void loadMetadata() {
        final int generation = mOpenGeneration;
        final Video video = mCurrentVideo;
        if (video == null || video.videoId == null) {
            return;
        }
        RxDispose(mMetadataAction);
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        Observable<MediaItemMetadata> observable = video.mediaItem != null
                ? service.getMetadataObserve(video.mediaItem)
                : service.getMetadataObserve(video.videoId, video.getPlaylistId(),
                        video.playlistIndex, video.playlistParams);
        mMetadataAction = observable.subscribe(
                metadata -> onVideoMetadata(generation, video, metadata),
                error -> NetPath.log("shorts metadata error video=" + video.videoId + " "
                        + error.getMessage()));
    }

    private void onVideoMetadata(int generation, Video video, MediaItemMetadata metadata) {
        if (generation != mOpenGeneration || mCurrentVideo != video
                || isFinishing() || isDestroyed()) {
            return;
        }
        video.sync(metadata);
        mLikeStatus = metadata.getLikeStatus();
        updateRatingButtons();

        String likeCount = metadata.getLikeCount();
        mLikeCount.setText(likeCount);
        mLikeCount.setVisibility(TextUtils.isEmpty(likeCount) ? View.GONE : View.VISIBLE);

        mCommentsKey = metadata.getCommentsKey();
        String commentsCount = mCommentsKey != null ? metadata.getCommentsCount() : null;
        mCommentCount.setText(commentsCount);
        mCommentCount.setVisibility(TextUtils.isEmpty(commentsCount) ? View.GONE : View.VISIBLE);
        mCommentsPanel.setSource(video.videoId, mCommentsKey, metadata.getNewestCommentsKey(),
                commentsCount);

        if (mCurrentPosition != RecyclerView.NO_POSITION
                && mAdapter.itemAt(mCurrentPosition) == video) {
            mAdapter.notifyItemChanged(mCurrentPosition); // title/author enrichment
        }
    }

    // ---------------------------------------------------------------------------------
    // Mute + ticker + lifecycle
    // ---------------------------------------------------------------------------------

    private void toggleMute() {
        mMuted = !mMuted;
        if (mPlayerController != null) {
            mPlayerController.setVolume(mMuted ? 0f : 1f);
        }
        mMuteButton.setImageResource(mMuted ? R.drawable.action_sound_off : R.drawable.action_sound_on);
        mMuteButton.setContentDescription(getString(mMuted
                ? R.string.mobile_shorts_unmute : R.string.action_sound_off));
    }

    private void onTick() {
        if (mPlayer == null) {
            return;
        }
        long position = mPlayer.getCurrentPosition();
        long duration = mPlayer.getDuration();
        if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
            mProgress.setProgress((int) (1000L * position / duration));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String account = currentAccountKey();
        if (mAccountKey != null && account != null && !account.equals(mAccountKey)) {
            // Account switched while this screen was away (the You panel's sheet): a fresh feed
            // for the new account; the old request's late answer is dropped by the generation.
            mAccountKey = account;
            reloadFeed();
        }
        if (mPlayer != null && mCurrentVideo != null && !mUserPaused) {
            mPlayer.setPlayWhenReady(true);
        }
        mPager.postDelayed(mTicker, TICK_MS);
    }

    @Override
    protected void onPause() {
        mPager.removeCallbacks(mTicker);
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mPager.removeCallbacks(mTicker);
        if (mLoader != null) {
            mLoader.cancel();
        }
        RxDispose(mResolveAction);
        mResolveAction = null;
        RxDispose(mMetadataAction);
        mMetadataAction = null;
        if (mCommentsPanel != null) {
            mCommentsPanel.release();
            mCommentsPanel = null;
        }
        if (mPlayerController != null) {
            mPlayerController.release(); // stops, clears and releases the single player
            mPlayerController = null;
            mPlayer = null;
        }
        super.onDestroy();
    }

    private void reloadFeed() {
        mFeedShown = false;
        mCurrentPosition = RecyclerView.NO_POSITION;
        mCurrentVideo = null;
        mOpenGeneration++;
        RxDispose(mResolveAction);
        mResolveAction = null;
        RxDispose(mMetadataAction);
        mMetadataAction = null;
        mUserPaused = false;
        updateCenterIcon();
        resetRatingUi();
        if (mCommentsPanel != null) {
            mCommentsPanel.setSource(null, null, null, null);
        }
        mAdapter.submit(mLoader.items());
        hideMessage();
        setSkeletonVisible(true);
        mLoader.reload();
    }

    @Nullable
    private static String currentAccountKey() {
        com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                MediaServiceManager.instance().getSelectedAccount();
        return account != null ? account.getEmail() : null;
    }

    private static void RxDispose(Disposable disposable) {
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(disposable);
    }
}
