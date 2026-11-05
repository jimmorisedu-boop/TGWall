package ru.telegramwall.app;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapFactory;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridView;
import android.widget.PopupMenu;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowPopupMenu;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w390dp-h844dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DesignScreenshotTest {
    // Public screenshots use fictional conversations, never data captured from a phone.
    private static final long DEMO_TIME = java.time.Instant.parse("2026-01-01T12:30:00Z").toEpochMilli();
    private Context context;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        FeedStore.clear(context);
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void capturesPortraitFeed() throws Exception {
        fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "portrait", 390, 844);
            assertNotNull(findText(content(controller.get()), "Оля"));
            assertNotNull(findText(content(controller.get()), "Сообщения"));
            assertNotNull(findText(content(controller.get()), "Новые сверху"));
            assertNull(findText(content(controller.get()), "Telegram подключён"));
            assertNull(findText(content(controller.get()), "Telegram Wall"));
        }
    }

    @Test public void privateSummaryPreviewsRenderAsSeparateReadableCards() throws Exception {
        fixture();
        FeedStore.clear(context);
        android.app.Notification notification = new android.app.Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Telegram X")
                .setContentText("38 новых сообщений из 17 чатов").setWhen(System.currentTimeMillis())
                .setGroup("private").setGroupSummary(true)
                .setStyle(new android.app.Notification.InboxStyle()
                        .addLine("Алексей: Проверка личного сообщения")
                        .addLine("Мария: Второе сообщение отдельно")).build();
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(new android.service.notification.StatusBarNotification(
                "org.thunderdog.challegram", "org.thunderdog.challegram", 2, "pm_0", 1000, 0, 0,
                notification, android.os.Process.myUserHandle(), System.currentTimeMillis()));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "private-message-previews", 390, 844);
            assertEquals(2, TestUi.grid(controller.get()).getAdapter().getCount());
            assertNotNull(findText(content(controller.get()), "Алексей"));
            assertNotNull(findText(content(controller.get()), "Проверка личного сообщения"));
            assertNotNull(findText(content(controller.get()), "Второе сообщение отдельно"));
            assertNull(findText(content(controller.get()), "Telegram X · 38 новых сообщений из 17 чатов"));
        }
    }

    @Test @Config(qualifiers = "w844dp-h390dp-land-mdpi")
    public void capturesLandscapeFeedInChronologicalSingleColumn() throws Exception {
        fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "landscape", 844, 390);
            GridView grid = TestUi.grid(controller.get());
            assertEquals(1, grid.getNumColumns());
            assertEquals("Оля", ((FeedItem) grid.getAdapter().getItem(0)).sender);
            assertEquals("Паша", ((FeedItem) grid.getAdapter().getItem(1)).sender);
            View header = ((ViewGroup) content(controller.get())).getChildAt(2);
            assertTrue("Feed must start below the toolbar", grid.getTop() >= header.getBottom());
        }
    }

    @Test public void toolbarOpensSettingsWithoutLongPress() throws Exception {
        fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            TestUi.layout(controller.get(), 390, 844);
            findText(content(controller.get()), "⋮").performClick();
            PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
            assertNotNull(menu);
            assertNotNull(menu.getMenu().findItem(1));
            assertNotNull(menu.getMenu().findItem(2));
            assertNotNull(menu.getMenu().findItem(3));
            menu.dismiss();
        }
    }

    @Test public void capturesEmptyStateAndSettingsActionWorks() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "empty", 390, 844);
            TextView grant = findText(content(controller.get()), "Разрешить доступ к уведомлениям");
            assertNotNull(grant);
            grant.performClick();
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                    org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity().getAction());
        }
    }

    @Test public void menuSettingSurvivesRecreationAndClearRequiresConfirmation() throws Exception {
        fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
            TestUi.layout(activity, 390, 844);
            GridView grid = TestUi.grid(activity);
            assertTrue(grid.getOnItemLongClickListener().onItemLongClick(grid, grid.getChildAt(0), 0, 0));
            PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
            assertTrue(menu.getMenu().performIdentifierAction(2, 0));
            menu.dismiss();
            controller.recreate();
            activity = controller.get();
            assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            TestUi.layout(activity, 390, 844);
            grid = TestUi.grid(activity);
            assertTrue(grid.getOnItemLongClickListener().onItemLongClick(grid, grid.getChildAt(0), 0, 0));
            menu = ShadowPopupMenu.getLatestPopupMenu();
            menu.getMenu().performIdentifierAction(3, 0);
            assertEquals(6, FeedStore.read(context).size());
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            TestUi.settle(activity);
            assertTrue(FeedStore.read(context).isEmpty());
            menu.dismiss();
        }
    }

    @Test public void rotationReusesActivityAndLoadedFeed() throws Exception {
        fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            TestUi.layout(activity, 390, 844);
            android.widget.ListAdapter adapter = TestUi.grid(activity).getAdapter();
            Configuration landscape = new Configuration(activity.getResources().getConfiguration());
            landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
            landscape.screenWidthDp = 844;
            landscape.screenHeightDp = 390;
            controller.configurationChange(landscape);
            TestUi.layout(controller.get(), 844, 390);
            assertSame(activity, controller.get());
            assertSame(adapter, TestUi.grid(activity).getAdapter());
            assertEquals(6, adapter.getCount());
            assertEquals(1, TestUi.grid(activity).getNumColumns());
        }
    }

    @Test @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    public void capturesNarrowPortrait() throws Exception {
        fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "portrait-360", 360, 800);
        }
    }

    @Test public void capturesPhotoAndVideoPreviewInDarkPanel() throws Exception {
        mediaFixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "dark-media-portrait", 390, 844);
        }
    }

    @Test @Config(qualifiers = "w844dp-h390dp-land-mdpi")
    public void capturesMediaInLandscape() throws Exception {
        mediaFixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "dark-media-landscape", 844, 390);
        }
    }

    @Test public void capturesVideoWithoutPreview() throws Exception {
        fixture();
        FeedStore.clear(context);
        List<FeedItem> items = new ArrayList<>();
        items.add(new FeedItem("video-not-provided", "Бот дедлайнов", "Релиз без паники", "📹 Запись продуктивного созвона. Улик не осталось.",
                DEMO_TIME, "video", "", "unavailable"));
        FeedStore.add(context, items);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            capture(controller.get(), "dark-video-unavailable", 390, 844);
            assertNotNull(findText(content(controller.get()), "Telegram не передал превью видео"));
        }
    }

    private void mediaFixture() throws Exception {
        fixture();
        FeedStore.clear(context);
        Bitmap picture;
        try (InputStream input = getClass().getResourceAsStream("/notification-photo.jpg")) {
            picture = BitmapFactory.decodeStream(input);
        }
        String file = MediaCache.copy(context, "sample-notification-photo", null, "image/jpeg", picture);
        picture.recycle();
        long now = DEMO_TIME;
        List<FeedItem> items = new ArrayList<>();
        items.add(new FeedItem("photo-preview", "Кот Иннокентий", "Клуб диванных экспертов", "Съездил отдохнуть. Горы на созвон не вышли.", now,
                "image", file, "ready"));
        items.add(new FeedItem("video-preview", "Кот Иннокентий", "Клуб диванных экспертов", "📹 Озеро. Ни одного срочного поручения.", now - 120000,
                "video", file, "ready"));
        FeedStore.add(context, items);
    }

    private void fixture() {
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(context, TelegramNotificationListener.class).flattenToShortString());
        long now = DEMO_TIME;
        String[][] messages = {
                {"Оля", "Релиз без паники", "Пуши приходят сами. Хоть кто-то в проекте работает."},
                {"Паша", "Релиз без паники", "Всё настроил. Теперь героически смотрю на экран."},
                {"Нина", "Созвон о созвоне", "Отменила созвон. Команда впервые успела поработать."},
                {"Борис", "Созвон о созвоне", "Нужен созвон. Обсудим, почему не успеваем."},
                {"Мама", "Семейный техсовет", "Перезагрузила. Роутер ожил, я пока нет."},
                {"Костя", "Семейный техсовет", "Мам, перезагрузи роутер. Ему тоже нужен отпуск."}
        };
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            items.add(new FeedItem("sample" + i, messages[i][0], messages[i][1], messages[i][2], now - i * 180000));
        }
        FeedStore.add(context, items);
    }

    private View content(MainActivity activity) {
        return ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    }

    private void capture(MainActivity activity, String name, int width, int height) throws Exception {
        TestUi.layout(activity, width, height);
        View root = content(activity);
        Bitmap bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(2, 2);
        root.draw(canvas);
        File directory = new File(System.getProperty("telegramwall.screenshotDir"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

}
