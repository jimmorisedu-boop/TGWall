package ru.telegramwall.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;

/** Inline attachment: load only while visible, autoplay an actual supplied video without sound. */
final class PanelMediaView extends FrameLayout {
    private final FeedItem item;
    private final ImageView image;
    private final TextView status;
    private Bitmap bitmap;
    private VideoView video;
    private boolean videoFailed;
    private float aspect = 16f / 9f;

    PanelMediaView(Context context, FeedItem item) {
        super(context);
        this.item = item;
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0D151E);
        background.setCornerRadius(dp(12));
        setBackground(background);
        setClipToOutline(true);
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("video".equals(item.mediaKind) ? "Превью видео из уведомления" : "Изображение из уведомления");
        addView(image, new LayoutParams(-1, -1));
        status = new TextView(context);
        status.setTextColor(0xFFA3B3C2);
        status.setTextSize(13);
        status.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setText("ready".equals(item.mediaState) && MediaCache.file(context, item.mediaFile) == null
                ? "Вложение больше не хранится на устройстве"
                : item.mediaFile.endsWith(".video") ? "Видео загружается…"
                : "loading".equals(item.mediaState) ? "Изображение загружается…"
                : "video".equals(item.mediaKind) ? "Telegram не передал превью видео"
                : "В уведомлении нет доступного изображения");
        addView(status, new LayoutParams(-1, -1));
        File preview = previewFile();
        if (preview != null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(preview.getAbsolutePath(), bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) aspect = (float) bounds.outWidth / bounds.outHeight;
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        boolean available = MediaCache.file(getContext(), item.mediaFile) != null;
        android.content.res.Configuration config = getResources().getConfiguration();
        boolean landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int maxHeight = landscape ? Math.max(96, Math.min(320, config.screenHeightDp - 260)) : 280;
        int height = available ? Math.max(dp(landscape ? 96 : 120), Math.min(dp(maxHeight), Math.round(width / aspect)))
                : dp("loading".equals(item.mediaState) ? 96 : 64);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    boolean visibleInPanel() {
        return isShown() && getWidth() > 0 && getHeight() > 0 && getGlobalVisibleRect(new Rect());
    }

    boolean hasVideoFile() {
        return !videoFailed && item.mediaFile.endsWith(".video") && MediaCache.file(getContext(), item.mediaFile) != null;
    }

    void refreshViewport(boolean panelActive, boolean allowVideo) {
        if (!panelActive || !visibleInPanel()) { release(); return; }
        if (bitmap == null) {
            File preview = previewFile();
            if (preview != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                bitmap = BitmapFactory.decodeFile(preview.getAbsolutePath(), options);
                if (bitmap != null) {
                    image.setImageBitmap(bitmap);
                    status.setVisibility(View.GONE);
                }
            }
        }
        if (allowVideo && hasVideoFile()) startVideo();
        else stopVideo();
    }

    private File previewFile() {
        if (!item.mediaFile.endsWith(".video")) return MediaCache.file(getContext(), item.mediaFile);
        String key = item.mediaFile.substring(0, item.mediaFile.length() - 6);
        File jpg = MediaCache.file(getContext(), key + ".jpg");
        return jpg != null ? jpg : MediaCache.file(getContext(), key + ".png");
    }

    private void startVideo() {
        if (video != null || videoFailed) return;
        File source = MediaCache.file(getContext(), item.mediaFile);
        if (source == null) return;
        VideoView player = new VideoView(getContext());
        video = player;
        player.setFocusable(false);
        player.setFocusableInTouchMode(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) player.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE);
        player.setOnTouchListener((view, event) -> true);
        player.setOnPreparedListener(mediaPlayer -> {
            if (video != player) return;
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setLooping(true);
            status.setVisibility(View.GONE);
            player.start();
        });
        player.setOnErrorListener((mediaPlayer, what, extra) -> {
            post(() -> {
                if (video != player) return;
                videoFailed = true;
                stopVideo();
                if (bitmap == null) {
                    status.setText("Видео недоступно для воспроизведения");
                    status.setVisibility(View.VISIBLE);
                }
                requestLayout();
            });
            return true; // Never open VideoView's modal error dialog on an unattended panel.
        });
        addView(player, new LayoutParams(-1, -1, Gravity.CENTER));
        player.setVideoPath(source.getAbsolutePath());
    }

    private void stopVideo() {
        if (video == null) return;
        VideoView previous = video;
        video = null;
        previous.stopPlayback();
        removeView(previous);
    }

    void release() {
        stopVideo();
        image.setImageDrawable(null);
        bitmap = null;
    }

    @Override protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
