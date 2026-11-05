package ru.telegramwall.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG = 0xFF0C1219, SURFACE = 0xFF16212D, INK = 0xFFEDF3F8;
    private static final int SECONDARY = 0xFFA0B0BF, BLUE = 0xFF70BDF1, BLUE_SOFT = 0xFF193247;
    private static final String KEEP_SCREEN = "keep_screen_on";
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
    private FeedAdapter adapter;
    private boolean keepScreenOn = true, receiverRegistered, panelActive, destroyed;
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
        mainHandler.removeCallbacks(refresh);
        if (receiverRegistered) {
            unregisterReceiver(feedReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        historyExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(KEEP_SCREEN, keepScreenOn);
        state.putParcelable("list_state", grid.onSaveInstanceState());
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        rotationAt = SystemClock.elapsedRealtime();
        grid.invalidateViews();
        applyImmersiveMode();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) applyImmersiveMode();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        grid = new GridView(this);
        grid.setNumColumns(1);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(dp(16), dp(8), dp(16), dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setVerticalScrollBarEnabled(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        adapter = new FeedAdapter();
        grid.setAdapter(adapter);
        FrameLayout.LayoutParams feedParams = new FrameLayout.LayoutParams(-1, -1);
        feedParams.topMargin = dp(60);
        root.addView(grid, feedParams);
        empty = text("", 15, SECONDARY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        empty.setOnClickListener(v -> { if (!hasNotificationAccess()) openNotificationAccessSettings(); });
        empty.setOnLongClickListener(v -> { showOptions(v); return true; });
        FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(-1, -1);
        emptyParams.topMargin = dp(60);
        root.addView(empty, emptyParams);
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
        root.addView(header, new FrameLayout.LayoutParams(-1, dp(60)));
        grid.setEmptyView(empty);
        grid.setOnItemLongClickListener((parent, view, position, id) -> { showOptions(view); return true; });
        grid.setRecyclerListener(view -> {
            Object holder = view.getTag();
            if (holder instanceof CardHolder) ((CardHolder) holder).releaseMedia();
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
        empty.setTextColor(access ? SECONDARY : BLUE);
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
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            CardHolder holder;
            if (recycled == null) {
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
        final TextView avatar = text("", 13, INK, true);
        final TextView sender = text("", 14, INK, true);
        final TextView chat = text("", 12, SECONDARY, false);
        final TextView time = text("", 11, SECONDARY, false);
        final TextView message = text("", 17, INK, false);
        final TextView mediaLabel = text("", 11, BLUE, false);
        final FrameLayout mediaHost = new FrameLayout(MainActivity.this);
        PanelMediaView media;
        FeedItem bound;

        CardHolder() {
            card.setPadding(dp(14), dp(14), dp(14), dp(16));
            LinearLayout top = row();
            card.addView(top, params(-1, -2));
            avatar.setGravity(Gravity.CENTER);
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
            message.setLineSpacing(dp(3), 1f);
            card.addView(message, spaced(-1, -2, 12));
            card.addView(mediaLabel, spaced(-1, -2, 12));
            card.addView(mediaHost, spaced(-1, -2, 8));
        }

        void bind(FeedItem item, int position) {
            card.setBackground(roundRect(position == 0 ? BLUE_SOFT : SURFACE, 16));
            if (bound == null || !bound.sender.equals(item.sender)) {
                int[] backgrounds = {0xFF233F54, 0xFF334336, 0xFF49392D, 0xFF3D304B, 0xFF24423E};
                int[] inks = {0xFF99CCEB, 0xFFB7CE9E, 0xFFE7BD91, 0xFFD2B6E7, 0xFF9BD6C5};
                int palette = Math.floorMod(item.sender.hashCode(), backgrounds.length);
                avatar.setText(initials(item.sender));
                avatar.setTextColor(inks[palette]);
                avatar.setBackground(roundRect(backgrounds[palette], 20));
                sender.setText(item.sender);
            }
            chat.setText(item.chat);
            chat.setVisibility(item.chat.equals("Telegram") || item.chat.equals(item.sender) ? View.GONE : View.VISIBLE);
            String formattedTime = formatTime(item.time);
            time.setText(item.isPreview() ? TextUtils.concat("≈", formattedTime) : formattedTime);
            time.setContentDescription(item.isPreview()
                    ? "Время обновления уведомления; точное время сообщения не передано" : formatTime(item.time));
            boolean generic = !item.mediaKind.isEmpty() && (item.message.equals("Фото") || item.message.equals("Фотография")
                    || item.message.equals("Видео") || item.message.equals("Photo") || item.message.equals("Video"));
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

    private void updateMediaVisibility() {
        if (grid == null) return;
        boolean selected = false;
        for (int i = 0; i < grid.getChildCount(); i++) {
            Object tag = grid.getChildAt(i).getTag();
            if (!(tag instanceof CardHolder)) continue;
            PanelMediaView media = ((CardHolder) tag).media;
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
        }
    }

    private void showOptions(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, "Доступ к уведомлениям");
        menu.add(0, 2, 1, "Не выключать экран").setCheckable(true).setChecked(keepScreenOn);
        menu.add(0, 3, 2, "Очистить ленту").setEnabled(adapter.getCount() > 0);
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
        String pattern = Math.max(0, System.currentTimeMillis() - millis) < 86400000L ? "HH:mm" : "dd.MM HH:mm";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(millis));
    }
}
