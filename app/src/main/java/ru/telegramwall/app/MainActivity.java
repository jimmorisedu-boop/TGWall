package ru.telegramwall.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG = 0xFF0C1219, SURFACE = 0xFF16212D, INK = 0xFFEDF3F8;
    private static final int SECONDARY = 0xFFA0B0BF, BLUE = 0xFF70BDF1, BLUE_SOFT = 0xFF193247;
    private static final int LANDSCAPE_BG = 0xFF000000, LANDSCAPE_INK = 0xFFF2F2F2;
    private static final int LANDSCAPE_SUB = 0xFFAAAAAA, LANDSCAPE_LINE = 0xFF222222;
    private static final int LANDSCAPE_RECENT = 0xFF111111, LANDSCAPE_MARK = 0xFF252525;
    private static final String KEEP_SCREEN = "keep_screen_on";
    private static final String LARGE_TEXT = "large_text";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "feed-history");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable refresh = () -> requestHistory(true);
    private FrameLayout root;
    private GridView grid;
    private TextView empty;
    private TextView headerDate;
    private AlertDialog detailDialog;
    private PanelMediaView detailAttachment;
    private FeedAdapter adapter;
    private boolean keepScreenOn = true, receiverRegistered, panelActive, destroyed;
    private boolean landscapeUi, largeText;
    private boolean loading, reloadRequested, scrollToNewest;
    private Parcelable pendingListState;
    private long createdAt, rotationAt;
    private boolean firstContentLogged;

    private final BroadcastReceiver feedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            mainHandler.removeCallbacks(refresh);
            mainHandler.postDelayed(refresh, 80);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        createdAt = SystemClock.elapsedRealtime();
        keepScreenOn = state == null ? getPreferences(MODE_PRIVATE).getBoolean(KEEP_SCREEN, true)
                : state.getBoolean(KEEP_SCREEN, true);
        largeText = state == null ? getPreferences(MODE_PRIVATE).getBoolean(LARGE_TEXT, false)
                : state.getBoolean(LARGE_TEXT, false);
        if (state != null) pendingListState = state.getParcelable("list_state");
        buildUi();
        applyImmersiveMode();
        updateScreenFlag();
    }

    @Override protected void onResume() {
        super.onResume();
        panelActive = true;
        applyImmersiveMode();
        registerFeedReceiver();
        updateEmptyState();
        requestHistory(pendingListState == null);
    }

    @Override protected void onPause() {
        panelActive = false;
        releaseVisibleMedia();
        if (detailAttachment != null) detailAttachment.release();
        mainHandler.removeCallbacks(refresh);
        if (receiverRegistered) {
            unregisterReceiver(feedReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (detailDialog != null) detailDialog.dismiss();
        mainHandler.removeCallbacksAndMessages(null);
        historyExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(KEEP_SCREEN, keepScreenOn);
        state.putBoolean(LARGE_TEXT, largeText);
        state.putParcelable("list_state", grid.onSaveInstanceState());
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        rotationAt = SystemClock.elapsedRealtime();
        Parcelable listState = grid == null ? null : grid.onSaveInstanceState();
        if (grid == null || isLandscapeConfiguration() != landscapeUi) {
            pendingListState = listState;
            if (grid != null) grid.setAdapter(null);
            buildUi();
        } else {
            grid.invalidateViews();
        }
        applyImmersiveMode();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) applyImmersiveMode();
    }

    private void buildUi() {
        landscapeUi = isLandscapeConfiguration();
        if (adapter == null) adapter = new FeedAdapter();
        root = new FrameLayout(this);
        root.setBackgroundColor(landscapeUi ? LANDSCAPE_BG : BG);

        grid = new GridView(this);
        grid.setNumColumns(1);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(landscapeUi ? 0 : 10));
        grid.setVerticalSpacing(dp(landscapeUi ? 0 : 14));
        grid.setPadding(dp(landscapeUi ? 12 : 16), dp(landscapeUi ? 4 : 8),
                dp(landscapeUi ? 12 : 16), dp(landscapeUi ? 4 : 16));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setVerticalScrollBarEnabled(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setAdapter(adapter);
        int headerHeight = landscapeUi ? 48 : 60;
        FrameLayout.LayoutParams feedParams = new FrameLayout.LayoutParams(-1, -1);
        feedParams.topMargin = dp(headerHeight);
        root.addView(grid, feedParams);

        empty = text("", landscapeUi ? 15 : 15, landscapeUi ? LANDSCAPE_SUB : SECONDARY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        empty.setOnClickListener(v -> { if (!hasNotificationAccess()) openNotificationAccessSettings(); });
        empty.setOnLongClickListener(v -> { showOptions(v); return true; });
        FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(-1, -1);
        emptyParams.topMargin = dp(headerHeight);
        root.addView(empty, emptyParams);

        LinearLayout header = landscapeUi ? buildLandscapeHeader() : buildPortraitHeader();
        root.addView(header, new FrameLayout.LayoutParams(-1, dp(headerHeight)));
        grid.setEmptyView(empty);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < adapter.getCount()) showDetails(adapter.getItem(position));
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> { showOptions(view); return true; });
        grid.setRecyclerListener(view -> {
            Object holder = view.getTag();
            if (holder instanceof CardHolder) ((CardHolder) holder).releaseMedia();
            if (holder instanceof LandscapeHolder) ((LandscapeHolder) holder).release();
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { updateMediaVisibility(); }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { updateMediaVisibility(); }
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            updateMediaVisibility();
            if (grid.getChildCount() > 0 && adapter.getCount() > 0 && !firstContentLogged) {
                firstContentLogged = true;
                Log.i("TGWallPerf", "first_messages_ms=" + (SystemClock.elapsedRealtime() - createdAt)
                        + " visible_cards=" + grid.getChildCount() + " total=" + adapter.getCount());
            }
            if (rotationAt != 0 && grid.getChildCount() > 0) {
                Log.i("TGWallPerf", "rotation_layout_ms=" + (SystemClock.elapsedRealtime() - rotationAt)
                        + " columns=" + grid.getNumColumns());
                rotationAt = 0;
            }
        });
        setContentView(root);
        updateEmptyState();
        updateHeaderDate();
        if (pendingListState != null && adapter.getCount() > 0) {
            Parcelable restore = pendingListState;
            pendingListState = null;
            grid.post(() -> grid.onRestoreInstanceState(restore));
        }
    }

    private boolean isLandscapeConfiguration() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private LinearLayout buildPortraitHeader() {
        headerDate = null;
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(6), dp(8), dp(6));
        header.setBackgroundColor(BG);
        header.addView(text("Сообщения", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(text("Новые сверху", 12, SECONDARY, false), params(-2, -2));
        TextView more = text("⋮", 26, INK, false);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Настройки");
        more.setOnClickListener(this::showOptions);
        header.addView(more, params(48, 48));
        return header;
    }

    private LinearLayout buildLandscapeHeader() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), 0, dp(9), 0);
        header.setBackgroundColor(LANDSCAPE_BG);
        WallMarkView mark = new WallMarkView(this);
        mark.setContentDescription("TGWall");
        header.addView(mark, params(22, 22));
        TextView title = text("Сообщения", 19, LANDSCAPE_INK, true);
        title.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(10));
        header.addView(title, titleParams);
        headerDate = text("", 13, LANDSCAPE_SUB, false);
        headerDate.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        headerDate.setFontFeatureSettings("tnum");
        header.addView(headerDate, params(-2, -2));
        TextView more = text("⋮", 25, 0xFFBBBBBB, false);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Настройки");
        more.setOnClickListener(this::showOptions);
        header.addView(more, params(44, 44));
        return header;
    }

    private void requestHistory(boolean newest) {
        if (destroyed) return;
        scrollToNewest |= newest;
        if (loading) { reloadRequested = true; return; }
        loading = true;
        Context application = getApplicationContext();
        try {
            historyExecutor.execute(() -> {
                List<FeedItem> items = FeedStore.read(application);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    loading = false;
                    adapter.submit(items);
                    if (pendingListState != null) {
                        grid.onRestoreInstanceState(pendingListState);
                        pendingListState = null;
                        scrollToNewest = false;
                    } else if (scrollToNewest) {
                        grid.setSelection(0);
                        scrollToNewest = false;
                    }
                    if (reloadRequested) {
                        reloadRequested = false;
                        requestHistory(false);
                    }
                    grid.post(this::updateMediaVisibility);
                });
            });
        } catch (RejectedExecutionException ignored) { loading = false; }
    }

    private void updateEmptyState() {
        boolean access = hasNotificationAccess();
        empty.setText(access ? "Ожидание сообщений" : "Разрешить доступ к уведомлениям");
        empty.setTextColor(access ? (landscapeUi ? LANDSCAPE_SUB : SECONDARY) : (landscapeUi ? LANDSCAPE_INK : BLUE));
        empty.setClickable(!access);
    }

    private final class FeedAdapter extends BaseAdapter {
        private List<FeedItem> items = Collections.emptyList();
        @Override public int getCount() { return items.size(); }
        @Override public FeedItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        void submit(List<FeedItem> incoming) {
            boolean same = items.size() == incoming.size();
            if (same) for (int i = 0; i < items.size(); i++) {
                if (items.get(i) != incoming.get(i)) { same = false; break; }
            }
            if (same) return;
            items = incoming;
            notifyDataSetChanged();
            updateHeaderDate();
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (landscapeUi) {
                LandscapeHolder holder;
                if (recycled == null || !(recycled.getTag() instanceof LandscapeHolder)) {
                    if (recycled != null && recycled.getTag() instanceof CardHolder) {
                        ((CardHolder) recycled.getTag()).releaseMedia();
                    }
                    holder = new LandscapeHolder();
                    recycled = holder.row;
                    recycled.setTag(holder);
                    recycled.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                } else holder = (LandscapeHolder) recycled.getTag();
                holder.bind(getItem(position), position);
                return recycled;
            }
            CardHolder holder;
            if (recycled == null || !(recycled.getTag() instanceof CardHolder)) {
                if (recycled != null && recycled.getTag() instanceof LandscapeHolder) {
                    ((LandscapeHolder) recycled.getTag()).release();
                }
                holder = new CardHolder();
                recycled = holder.card;
                recycled.setTag(holder);
                recycled.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            } else holder = (CardHolder) recycled.getTag();
            holder.bind(getItem(position), position);
            return recycled;
        }
    }

    private final class CardHolder {
        final LinearLayout card = column();
        final AvatarView avatar = new AvatarView(MainActivity.this);
        final TextView sender = text("", 14, INK, true);
        final TextView chat = text("", 12, SECONDARY, false);
        final TextView time = text("", 11, SECONDARY, false);
        final TextView message = text("", 17, INK, false);
        final TextView mediaLabel = text("", 11, BLUE, false);
        final FrameLayout mediaHost = new FrameLayout(MainActivity.this);
        PanelMediaView media;
        FeedItem bound;

        CardHolder() {
            card.setPadding(dp(16), dp(16), dp(16), dp(18));
            LinearLayout top = row();
            card.addView(top, params(-1, -2));
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            top.addView(avatar, params(36, 36));
            LinearLayout labels = column();
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginStart(dp(10));
            labelParams.setMarginEnd(dp(8));
            top.addView(labels, labelParams);
            sender.setMaxLines(2);
            sender.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(sender);
            chat.setSingleLine(true);
            chat.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(chat, spaced(-1, -2, 3));
            time.setGravity(Gravity.TOP | Gravity.END);
            top.addView(time, params(-2, -2));
            message.setLineSpacing(dp(4), 1f);
            card.addView(message, spaced(-1, -2, 14));
            card.addView(mediaLabel, spaced(-1, -2, 14));
            card.addView(mediaHost, spaced(-1, -2, 8));
        }

        void bind(FeedItem item, int position) {
            card.setBackground(roundRect(position == 0 ? BLUE_SOFT : SURFACE, 16));
            int[] backgrounds = {0xFF233F54, 0xFF334336, 0xFF49392D, 0xFF3D304B, 0xFF24423E};
            int[] inks = {0xFF99CCEB, 0xFFB7CE9E, 0xFFE7BD91, 0xFFD2B6E7, 0xFF9BD6C5};
            int palette = Math.floorMod(item.sender.hashCode(), backgrounds.length);
            avatar.bind(item.avatarFile, initials(item.sender), backgrounds[palette], inks[palette]);
            sender.setText(item.sender);
            sender.setTextSize(largeText ? 15 : 14);
            chat.setText(item.chat);
            chat.setVisibility(item.chat.equals("Telegram") || item.chat.equals(item.sender) ? View.GONE : View.VISIBLE);
            String formattedTime = formatTime(item.time);
            time.setText(item.isPreview() ? TextUtils.concat("≈", formattedTime) : formattedTime);
            time.setContentDescription(item.isPreview()
                    ? "Время обновления уведомления; точное время сообщения не передано" : formatTime(item.time));
            boolean generic = !item.mediaKind.isEmpty() && (item.message.equals("Фото") || item.message.equals("Фотография")
                    || item.message.equals("Видео") || item.message.equals("Photo") || item.message.equals("Video"));
            message.setTextSize(largeText ? 21 : 17);
            message.setVisibility(generic || item.message.isEmpty() ? View.GONE : View.VISIBLE);
            if (!TextUtils.equals(message.getText(), item.message)) message.setText(item.message);
            if (item.mediaKind.isEmpty()) {
                releaseMedia();
                mediaHost.setVisibility(View.GONE);
                mediaLabel.setVisibility(View.GONE);
            } else {
                boolean available = MediaCache.file(MainActivity.this, item.mediaFile) != null;
                String label = "video".equals(item.mediaKind)
                        ? item.mediaFile.endsWith(".video") ? "Видео · автоматически, без звука"
                        : available ? "Видео · превью из уведомления" : "Видео" : "Изображение";
                mediaLabel.setText(label);
                mediaLabel.setVisibility("video".equals(item.mediaKind) || !available ? View.VISIBLE : View.GONE);
                mediaHost.setVisibility(View.VISIBLE);
                if (media == null || bound == null || !bound.id.equals(item.id)
                        || !bound.mediaFile.equals(item.mediaFile) || !bound.mediaState.equals(item.mediaState)) {
                    releaseMedia();
                    media = new PanelMediaView(MainActivity.this, item);
                    mediaHost.addView(media, new FrameLayout.LayoutParams(-1, -2));
                }
            }
            bound = item;
        }

        void releaseMedia() {
            if (media != null) media.release();
            mediaHost.removeAllViews();
            media = null;
        }
    }

    /** Compact one-line/two-line landscape entry with bounded thumbnails and inline muted video. */
    private final class LandscapeHolder {
        final LandscapeRow row = new LandscapeRow(MainActivity.this);
        final LinearLayout contentRow = row();
        final TextView time = text("", 14, LANDSCAPE_SUB, false);
        final AvatarView avatar = new AvatarView(MainActivity.this);
        final TextView sender = text("", 17, LANDSCAPE_INK, true);
        final TextView chat = text("", 12, LANDSCAPE_SUB, false);
        final TextView message = text("", 17, LANDSCAPE_INK, false);
        final ImageView thumbnail = new ImageView(MainActivity.this);
        final FrameLayout attachmentHost = new FrameLayout(MainActivity.this);
        Bitmap thumbnailBitmap;
        PanelMediaView media;
        FeedItem bound;

        LandscapeHolder() {
            boolean narrow = getResources().getConfiguration().screenWidthDp <= 740;
            int identityWidth = narrow ? 143 : 164;
            int columnGap = narrow ? 8 : 12;
            int avatarSize = narrow ? 29 : 34;
            row.setMinimumHeight(dp(56));
            row.setPadding(0, dp(10), 0, dp(10));
            row.addView(contentRow, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
            contentRow.setGravity(Gravity.CENTER_VERTICAL);
            contentRow.setPadding(dp(12), 0, dp(12), 0);

            time.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            time.setFontFeatureSettings("tnum");
            // Keep the time column stable while leaving room for the optional ≈ prefix,
            // including at larger system font scales.
            Paint timePaint = new Paint(time.getPaint());
            timePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            int timeWidth = Math.max(dp(56), (int) Math.ceil(timePaint.measureText("≈00:00")));
            time.setSingleLine(false);
            time.setMaxLines(2);
            time.setEllipsize(null);
            contentRow.addView(time, new LinearLayout.LayoutParams(timeWidth, -2));

            LinearLayout identity = row();
            identity.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(dp(identityWidth), -2);
            identityParams.setMarginStart(dp(columnGap));
            contentRow.addView(identity, identityParams);
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            avatar.setTextSize(12);
            identity.addView(avatar, params(avatarSize, avatarSize));
            LinearLayout labels = column();
            labels.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelsParams.setMarginStart(dp(8));
            identity.addView(labels, labelsParams);
            sender.setMaxLines(1);
            sender.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(sender, params(-1, -2));
            chat.setSingleLine(true);
            chat.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(chat, spaced(-1, -2, 3));

            LinearLayout messageArea = row();
            messageArea.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams messageAreaParams = new LinearLayout.LayoutParams(0, -2, 1);
            messageAreaParams.setMarginStart(dp(columnGap));
            contentRow.addView(messageArea, messageAreaParams);
            message.setMaxLines(2);
            message.setEllipsize(TextUtils.TruncateAt.END);
            message.setLineSpacing(dp(3), 1f);
            messageArea.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(roundRect(0xFF171717, 6));
            thumbnail.setClipToOutline(true);
            thumbnail.setVisibility(View.GONE);
            LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(54), dp(35));
            thumbnailParams.setMarginStart(dp(10));
            attachmentHost.setBackground(roundRect(0xFF171717, 6));
            attachmentHost.setClipToOutline(true);
            attachmentHost.setVisibility(View.GONE);
            messageArea.addView(attachmentHost, thumbnailParams);
            attachmentHost.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
        }

        void bind(FeedItem item, int position) {
            row.setRecent(position == 0);
            int[] backgrounds = {0xFF27465A, 0xFF293E42, 0xFF43384D, 0xFF453B30, 0xFF303D51};
            int[] inks = {0xFFACD9F3, 0xFFB1D7CC, 0xFFDFC8E8, 0xFFE4CBA7, 0xFFC0D4F3};
            int palette = Math.floorMod(item.sender.hashCode(), backgrounds.length);
            avatar.bind(item.avatarFile, initials(item.sender), backgrounds[palette], inks[palette]);
            sender.setText(item.sender);
            chat.setText(item.chat);
            chat.setVisibility(item.chat.equals("Telegram") || item.chat.equals(item.sender) ? View.GONE : View.VISIBLE);
            sender.setTextSize(14);
            message.setTextSize(largeText ? 21 : 17);
            message.setText(item.message);
            message.setVisibility(item.message.isEmpty() ? View.GONE : View.VISIBLE);
            String formattedTime = formatTime(item.time).replace(' ', '\n');
            time.setText(item.isPreview() ? TextUtils.concat("≈", formattedTime) : formattedTime);
            time.setTypeface(Typeface.create("sans-serif", position == 0 ? Typeface.BOLD : Typeface.NORMAL));
            time.setTextColor(position == 0 ? LANDSCAPE_INK : LANDSCAPE_SUB);
            time.setContentDescription(item.isPreview()
                    ? "Время обновления уведомления; точное время сообщения не передано" : formatTime(item.time));
            setThumbnail(item);
            row.setContentDescription(item.sender + ", " + item.chat + ", " + formatTime(item.time)
                    + (item.message.isEmpty() ? "" : ": " + item.message));
            bound = item;
        }

        private void setThumbnail(FeedItem item) {
            File file = thumbnailFile(item);
            boolean playableVideo = "video".equals(item.mediaKind)
                    && MediaCache.file(MainActivity.this, item.mediaFile) != null
                    && item.mediaFile.endsWith(".video");
            if (playableVideo) {
                if (media == null || bound == null || !TextUtils.equals(bound.id, item.id)
                        || !TextUtils.equals(bound.mediaFile, item.mediaFile)) {
                    releaseInlineMedia();
                    media = new PanelMediaView(MainActivity.this, item, true);
                    attachmentHost.removeAllViews();
                    attachmentHost.addView(media, new FrameLayout.LayoutParams(-1, -1));
                }
                thumbnail.setVisibility(View.GONE);
                attachmentHost.setVisibility(View.VISIBLE);
                return;
            }
            releaseInlineMedia();
            if (file == null) {
                thumbnail.setImageDrawable(null);
                thumbnail.setVisibility(View.GONE);
                if (thumbnailBitmap != null) { thumbnailBitmap.recycle(); thumbnailBitmap = null; }
                attachmentHost.setVisibility(View.GONE);
                return;
            }
            if (thumbnailBitmap == null || bound == null || !TextUtils.equals(bound.mediaFile, item.mediaFile)
                    || !TextUtils.equals(bound.mediaKind, item.mediaKind)) {
                if (thumbnailBitmap != null) thumbnailBitmap.recycle();
                thumbnailBitmap = decodeBitmap(file, 320);
            }
            if (thumbnailBitmap != null) thumbnail.setImageBitmap(thumbnailBitmap);
            thumbnail.setContentDescription("video".equals(item.mediaKind) ? "Превью видео" : "Изображение");
            thumbnail.setVisibility(View.VISIBLE);
            attachmentHost.setVisibility(View.VISIBLE);
        }

        void release() {
            releaseInlineMedia();
            avatar.clearAvatar();
            if (thumbnailBitmap != null) { thumbnailBitmap.recycle(); thumbnailBitmap = null; }
            thumbnail.setImageDrawable(null);
            bound = null;
        }

        void releaseInlineMedia() {
            if (media == null) return;
            media.release();
            media = null;
            attachmentHost.removeAllViews();
            attachmentHost.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
        }

        private Bitmap decodeBitmap(File file, int maxDimension) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxDimension) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
    }

    private final class LandscapeRow extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean recent;

        LandscapeRow(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setRecent(boolean recent) {
            if (this.recent == recent) return;
            this.recent = recent;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth(), height = getHeight();
            if (recent) {
                paint.setColor(LANDSCAPE_RECENT);
                canvas.drawRoundRect(0, 0, width, height, dp(6), dp(6), paint);
                paint.setColor(LANDSCAPE_INK);
                canvas.drawRoundRect(0, dp(10), dp(3), Math.max(dp(10), height - dp(10)), dp(2), dp(2), paint);
            } else {
                paint.setColor(LANDSCAPE_LINE);
                canvas.drawRect(0, Math.max(0, height - dp(1)), width, height, paint);
            }
        }
    }

    private static final class WallMarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        WallMarkView(Context context) { super(context); setContentDescription("TGWall"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            paint.setColor(LANDSCAPE_MARK);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 6 * d, 6 * d, paint);
            paint.setColor(LANDSCAPE_INK);
            paint.setStrokeWidth(2 * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float left = 5 * d, top = 8 * d;
            canvas.drawLine(left, top, 17 * d, top, paint);
            canvas.drawLine(left, top + 5 * d, 14 * d, top + 5 * d, paint);
            canvas.drawLine(left, top + 10 * d, 11 * d, top + 10 * d, paint);
        }
    }

    /** Circular real avatar with deterministic initials fallback for old/missing media. */
    private final class AvatarView extends TextView {
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clipPath = new Path();
        private final RectF target = new RectF();
        private Bitmap avatarBitmap;
        private String avatarName = "";

        AvatarView(Context context) {
            super(context);
            setGravity(Gravity.CENTER);
            setIncludeFontPadding(false);
            setTextSize(13);
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setWillNotDraw(false);
        }

        void bind(String fileName, String fallback, int background, int foreground) {
            String safeFile = fileName == null ? "" : fileName;
            setText(fallback);
            setTextColor(foreground);
            if (!TextUtils.equals(avatarName, safeFile) || (avatarBitmap == null && !safeFile.isEmpty())) {
                clearAvatar();
                avatarName = safeFile;
                File file = MediaCache.file(MainActivity.this, safeFile);
                if (file != null) avatarBitmap = decodeBounded(file, 256);
            }
            if (avatarBitmap == null) setBackground(roundRect(background, 20));
            else setBackgroundColor(android.graphics.Color.TRANSPARENT);
            invalidate();
        }

        private Bitmap decodeBounded(File file, int maxDimension) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxDimension) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (avatarBitmap == null) {
                super.onDraw(canvas);
                return;
            }
            int width = getWidth(), height = getHeight();
            float radius = Math.min(width, height) / 2f;
            clipPath.reset();
            clipPath.addCircle(width / 2f, height / 2f, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clipPath);
            float scale = Math.max((float) width / avatarBitmap.getWidth(),
                    (float) height / avatarBitmap.getHeight());
            float drawWidth = avatarBitmap.getWidth() * scale;
            float drawHeight = avatarBitmap.getHeight() * scale;
            target.set((width - drawWidth) / 2f, (height - drawHeight) / 2f,
                    (width + drawWidth) / 2f, (height + drawHeight) / 2f);
            canvas.drawBitmap(avatarBitmap, null, target, imagePaint);
            canvas.restoreToCount(save);
        }

        void clearAvatar() {
            if (avatarBitmap != null) {
                avatarBitmap.recycle();
                avatarBitmap = null;
            }
            avatarName = "";
        }

        @Override protected void onDetachedFromWindow() {
            clearAvatar();
            super.onDetachedFromWindow();
        }
    }

    private File thumbnailFile(FeedItem item) {
        if (item.mediaKind.isEmpty() || item.mediaFile == null || item.mediaFile.isEmpty()) return null;
        if (!item.mediaFile.endsWith(".video")) return MediaCache.file(this, item.mediaFile);
        String key = item.mediaFile.substring(0, item.mediaFile.length() - 6);
        File jpg = MediaCache.file(this, key + ".jpg");
        return jpg != null ? jpg : MediaCache.file(this, key + ".png");
    }

    private void updateHeaderDate() {
        if (headerDate == null) return;
        long timestamp = adapter == null || adapter.getCount() == 0 ? System.currentTimeMillis()
                : adapter.getItem(0).time;
        Locale russian = new Locale("ru");
        headerDate.setText(new SimpleDateFormat("d MMMM, EEE", russian).format(new Date(timestamp)));
    }

    private void showDetails(FeedItem item) {
        // Pause inline video before transferring the attachment to the readable dialog.
        releaseVisibleMedia();
        LinearLayout details = column();
        details.setPadding(dp(4), 0, dp(4), 0);
        TextView context = text(item.chat + " · " + formatLongDate(item.time), 14,
                landscapeUi ? LANDSCAPE_SUB : SECONDARY, false);
        context.setContentDescription("Контекст сообщения: " + context.getText());
        details.addView(context, params(-1, -2));
        if (!item.message.isEmpty()) {
            TextView full = text(item.message, largeText ? 21 : 17,
                    landscapeUi ? LANDSCAPE_INK : INK, false);
            full.setTextIsSelectable(true);
            full.setLineSpacing(dp(4), 1f);
            details.addView(full, spaced(-1, -2, 14));
        }
        PanelMediaView media = null;
        if (!item.mediaKind.isEmpty()) {
            media = new PanelMediaView(this, item);
            media.setContentDescription("Вложение сообщения");
            details.addView(media, spaced(-1, -2, 12));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(details, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.sender)
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .create();
        detailDialog = dialog;
        detailAttachment = media;
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::updateMediaVisibility);
        scroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> updateMediaVisibility());
        dialog.setOnShowListener(ignored -> scroll.post(this::updateMediaVisibility));
        dialog.setOnDismissListener(ignored -> {
            if (detailAttachment != null) detailAttachment.release();
            detailAttachment = null;
            detailDialog = null;
            if (!destroyed) mainHandler.post(this::updateMediaVisibility);
        });
        dialog.show();
    }

    private String formatLongDate(long millis) {
        if (millis <= 0) millis = System.currentTimeMillis();
        return new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(new Date(millis));
    }

    private void updateMediaVisibility() {
        if (detailDialog != null) {
            if (detailAttachment != null) detailAttachment.refreshViewport(panelActive, true);
            return;
        }
        if (grid == null) return;
        boolean selected = false;
        for (int i = 0; i < grid.getChildCount(); i++) {
            Object tag = grid.getChildAt(i).getTag();
            PanelMediaView media;
            if (tag instanceof CardHolder) media = ((CardHolder) tag).media;
            else if (tag instanceof LandscapeHolder) media = ((LandscapeHolder) tag).media;
            else continue;
            if (media == null) continue;
            boolean play = panelActive && !selected && media.hasVideoFile() && media.visibleInPanel();
            media.refreshViewport(panelActive, play);
            if (play) selected = true;
        }
    }

    private void releaseVisibleMedia() {
        if (grid == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            Object holder = grid.getChildAt(i).getTag();
            if (holder instanceof CardHolder && ((CardHolder) holder).media != null) ((CardHolder) holder).media.release();
            if (holder instanceof LandscapeHolder && ((LandscapeHolder) holder).media != null)
                ((LandscapeHolder) holder).media.release();
        }
    }

    private void showOptions(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, "Доступ к уведомлениям");
        menu.add(0, 2, 1, "Не выключать экран").setCheckable(true).setChecked(keepScreenOn);
        menu.add(0, 3, 2, "Очистить ленту").setEnabled(adapter.getCount() > 0);
        menu.add(0, 4, 3, "Обычный текст (17)").setCheckable(true).setChecked(!largeText);
        menu.add(0, 5, 4, "Крупный текст (21)").setCheckable(true).setChecked(largeText);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openNotificationAccessSettings();
            if (item.getItemId() == 2) {
                keepScreenOn = !keepScreenOn;
                getPreferences(MODE_PRIVATE).edit().putBoolean(KEEP_SCREEN, keepScreenOn).apply();
                updateScreenFlag();
            }
            if (item.getItemId() == 3) {
                new AlertDialog.Builder(this).setTitle("Очистить ленту?")
                        .setMessage("Сохранённые сообщения будут удалены с этого устройства.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Очистить", (dialog, which) -> {
                            Context application = getApplicationContext();
                            historyExecutor.execute(() -> {
                                FeedStore.clear(application);
                                mainHandler.post(() -> requestHistory(true));
                            });
                        }).show();
            }
            if (item.getItemId() == 4 || item.getItemId() == 5) {
                largeText = item.getItemId() == 5;
                getPreferences(MODE_PRIVATE).edit().putBoolean(LARGE_TEXT, largeText).apply();
                adapter.notifyDataSetChanged();
            }
            return true;
        });
        popup.show();
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, TelegramNotificationListener.class);
        for (String entry : enabled.split(":")) if (component.equals(ComponentName.unflattenFromString(entry))) return true;
        return false;
    }

    private void openNotificationAccessSettings() {
        try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (ActivityNotFoundException ignored) { }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerFeedReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(TelegramNotificationListener.ACTION_FEED_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(feedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(feedReceiver, filter);
        receiverRegistered = true;
    }

    private void updateScreenFlag() {
        if (keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) {
        LinearLayout.LayoutParams result = params(width, height);
        result.topMargin = dp(top);
        return result;
    }
    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private String initials(String name) {
        String[] words = name.trim().split("\\s+");
        String result = words[0].substring(0, words[0].offsetByCodePoints(0, 1));
        if (words.length > 1) {
            String last = words[words.length - 1];
            result += last.substring(0, last.offsetByCodePoints(0, 1));
        }
        return result.toUpperCase(Locale.getDefault());
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String formatTime(long millis) {
        if (millis <= 0) millis = System.currentTimeMillis();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        if (adapter != null && adapter.getCount() > 0) now.setTimeInMillis(adapter.getItem(0).time);
        boolean sameDay = then.get(Calendar.ERA) == now.get(Calendar.ERA)
                && then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        String pattern = sameDay ? "HH:mm" : "dd.MM HH:mm";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(millis));
    }
}
