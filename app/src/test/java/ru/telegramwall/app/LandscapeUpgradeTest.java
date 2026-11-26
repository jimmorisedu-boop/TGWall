package ru.telegramwall.app;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w844dp-h390dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LandscapeUpgradeTest {
    @Test public void repostWithPersonIconFillsExistingLegacyRowWhileFeedIsOpen() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        FeedStore.clear(context);
        Bitmap marker = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        marker.eraseColor(Color.MAGENTA);
        Person sender = new Person.Builder().setName("Alice").setIcon(Icon.createWithBitmap(marker)).build();
        Notification notification = new Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(new Notification.MessagingStyle(new Person.Builder().setName("Me").build())
                        .setConversationTitle("Team").setGroupConversation(true)
                        .addMessage("Hello", 1000L, sender)).build();
        StatusBarNotification post = new StatusBarNotification("org.telegram.messenger", "org.telegram.messenger",
                42, "test", 1000, 0, 0, notification, Process.myUserHandle(), 5000L);
        // 1.2.3 stored an occurrence index after the timestamp and sender/body hash.
        UUID hash = UUID.nameUUIDFromBytes("5:AliceHello".getBytes(StandardCharsets.UTF_8));
        String id = post.getKey() + "_1000_" + hash + "_0";
        JSONObject old = new JSONObject().put("id", id).put("sender", "Alice").put("chat", "Team")
                .put("message", "Hello").put("time", 1000).put("mediaKind", "")
                .put("mediaFile", "").put("mediaState", "");
        context.getSharedPreferences("telegram_wall_feed", Context.MODE_PRIVATE).edit()
                .putString("items", new JSONArray().put(old).toString()).commit();

        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            TestUi.layout(activity, 844, 390);
            assertEquals(0, markerPixels(TestUi.grid(activity).getChildAt(0)));
            listener.onNotificationPosted(post);
            Field field = TelegramNotificationListener.class.getDeclaredField("mediaExecutor");
            field.setAccessible(true);
            ((ExecutorService) field.get(listener)).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);
            TestUi.layout(activity, 844, 390);

            assertEquals(1, TestUi.grid(activity).getCount());
            FeedItem item = (FeedItem) TestUi.grid(activity).getItemAtPosition(0);
            assertEquals(id, item.id);
            assertEquals("", item.mediaFile);
            assertNotNull(MediaCache.file(context, item.avatarFile));
            assertTrue("Updated sender image must be visible", markerPixels(TestUi.grid(activity).getChildAt(0)) > 100);
        } finally {
            listener.onDestroy();
            FeedStore.clear(context);
        }
    }

    private int markerPixels(View row) {
        Bitmap pixels = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
        row.draw(new Canvas(pixels));
        int width = pixels.getWidth(), height = pixels.getHeight();
        int[] colors = new int[width * height];
        pixels.getPixels(colors, 0, width, 0, 0, width, height);
        pixels.recycle();
        int count = 0;
        for (int color : colors) if (color == Color.MAGENTA) count++;
        return count;
    }
}
