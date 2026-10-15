package com.newtube.mobile.ui.shorts;

import android.graphics.Matrix;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(shorts): pages of the dedicated Shorts feed - one full-screen Short per page. The pages
 * only hold the video surface and the metadata overlay; ONE player (owned by
 * {@link MobileShortsActivity}) renders into whichever page is current, handed the page's
 * TextureView surface on selection and taken back when the page is recycled.
 */
public final class ShortsPagerAdapter extends RecyclerView.Adapter<ShortsPagerAdapter.Holder> {
    /** The activity's surface side of the handover. */
    public interface SurfaceHost {
        /** The current page's TextureView is ready to receive the player's surface. */
        void onShortSurfaceAvailable(TextureView textureView, int videoWidth, int videoHeight);

        /** The surface the player may be using belongs to a page that is going away. */
        void onShortSurfaceLost(TextureView textureView);
    }

    private final List<Video> mItems = new ArrayList<>();
    private final SurfaceHost mSurfaceHost;

    public ShortsPagerAdapter(SurfaceHost surfaceHost) {
        mSurfaceHost = surfaceHost;
        setHasStableIds(true);
    }

    public Video itemAt(int position) {
        return position >= 0 && position < mItems.size() ? mItems.get(position) : null;
    }

    /** Replace the whole list (initial load / reload); append is {@link #append}. */
    public void submit(List<Video> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    /** A page landed: items [firstIndex, size) are new. */
    public void append(List<Video> items, int firstIndex) {
        int size = items.size();
        if (firstIndex < 0 || firstIndex > size) {
            return;
        }
        mItems.clear();
        mItems.addAll(items);
        notifyItemRangeInserted(firstIndex, size - firstIndex);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int position) {
        Video video = itemAt(position);
        return video != null && video.videoId != null ? video.videoId.hashCode() : position;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_short, parent, false);
        return new Holder(view, mSurfaceHost);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(itemAt(position));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.unbind();
    }

    static final class Holder extends RecyclerView.ViewHolder implements
            TextureView.SurfaceTextureListener {
        final TextureView texture;
        final TextView title;
        final TextView channel;
        private final SurfaceHost mSurfaceHost;
        private int mVideoWidth;
        private int mVideoHeight;

        Holder(@NonNull View itemView, SurfaceHost surfaceHost) {
            super(itemView);
            mSurfaceHost = surfaceHost;
            texture = itemView.findViewById(R.id.mobile_short_texture);
            title = itemView.findViewById(R.id.mobile_short_title);
            channel = itemView.findViewById(R.id.mobile_short_channel);
            texture.setSurfaceTextureListener(this);
            itemView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyCrop());
        }

        void bind(Video video) {
            title.setText(video != null ? video.getTitleFull() : null);
            String author = video != null ? video.getAuthor() : null;
            channel.setText(author);
            channel.setVisibility(author != null ? View.VISIBLE : View.GONE);
            texture.setVisibility(View.VISIBLE);
        }

        void unbind() {
            // A recycled page must never keep the player's surface; the activity re-attaches on
            // the next selection.
            mSurfaceHost.onShortSurfaceLost(texture);
        }

        /** Crop-fill: the video keeps its aspect and fills the page, cropped on the long side. */
        void setVideoSize(int videoWidth, int videoHeight) {
            mVideoWidth = videoWidth;
            mVideoHeight = videoHeight;
            applyCrop();
        }

        private void applyCrop() {
            int viewW = texture.getWidth();
            int viewH = texture.getHeight();
            if (viewW == 0 || viewH == 0 || mVideoWidth == 0 || mVideoHeight == 0) {
                return;
            }
            // The default TextureView mapping stretches the buffer to the view; this matrix
            // re-scales it (about the center) so the buffer keeps its aspect, largest-side-fill,
            // with the view clipping the overflow - no stretching, no black bars.
            float scaleX = (float) (viewH * mVideoWidth) / (viewW * (float) mVideoHeight);
            float scaleY = 1f;
            if (scaleX < 1f) {
                // Video wider than the page relative to height: fill width instead.
                scaleY = 1f / scaleX;
                scaleX = 1f;
            }
            Matrix matrix = new Matrix();
            matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f);
            texture.setTransform(matrix);
        }

        @Override
        public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface,
                int width, int height) {
            mSurfaceHost.onShortSurfaceAvailable(texture, mVideoWidth, mVideoHeight);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface,
                int width, int height) {
            applyCrop();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
            mSurfaceHost.onShortSurfaceLost(texture);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
            // no-op
        }
    }

    /** Attach helper for the activity: hand the player this page's surface once it exists. */
    public static Surface surfaceOf(TextureView textureView) {
        android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
        return st != null ? new Surface(st) : null;
    }
}
