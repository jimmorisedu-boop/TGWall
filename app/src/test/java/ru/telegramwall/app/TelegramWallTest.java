package ru.telegramwall.app;

import static org.junit.Assert.*;

import android.app.Notification;
import android.content.Context;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class TelegramWallTest {
    private Context context;

    @Before public void resetFeed() {
        context = RuntimeEnvironment.getApplication();
        FeedStore.clear(context);
    }

    @Test public void shiftedMessagingWindowDoesNotDuplicateOldMessages() {
        TelegramNotificationListener listener = Robolectric.buildService(
                TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(messaging(new String[]{"A", "B"}, new long[]{1000, 2000}));
        listener.onNotificationPosted(messaging(new String[]{"B", "C"}, new long[]{2000, 3000}));
        List<FeedItem> items = FeedStore.read(context);
        assertEquals("A, B and C must appear once each", 3, items.size());
        assertEquals("C", items.get(0).message);
        assertEquals("B", items.get(1).message);
        assertEquals("A", items.get(2).message);
    }

    @Test public void sameTimestampMessagesRemainDistinctAndRepostIsIdempotent() {
        TelegramNotificationListener listener = Robolectric.buildService(
                TelegramNotificationListener.class).create().get();
        StatusBarNotification notification = messaging(new String[]{"A", "B"}, new long[]{1000, 1000});
        listener.onNotificationPosted(notification);
        listener.onNotificationPosted(notification);
        assertEquals(2, FeedStore.read(context).size());
    }

    @Test public void identicalMessagesInSameMillisecondArePreserved() {
        TelegramNotificationListener listener = Robolectric.buildService(
                TelegramNotificationListener.class).create().get();
        StatusBarNotification notification = messaging(new String[]{"A", "A"}, new long[]{1000, 1000});
        listener.onNotificationPosted(notification);
        listener.onNotificationPosted(notification);
        assertEquals(2, FeedStore.read(context).size());
    }

    @Test public void storeRetainsOnlyOneHundredNewestMessages() {
        List<FeedItem> incoming = new ArrayList<>();
        for (int i = 0; i < 125; i++) {
            incoming.add(new FeedItem("id" + i, "Alice", "Team", "message" + i, i));
        }
        FeedStore.add(context, incoming);
        FeedStore.add(context, incoming);
        List<FeedItem> stored = FeedStore.read(context);
        assertEquals(100, stored.size());
        assertEquals(124, stored.get(0).time);
        assertEquals(25, stored.get(99).time);
        for (int i = 1; i < stored.size(); i++) {
            assertTrue(stored.get(i - 1).time >= stored.get(i).time);
        }
    }

    @Test public void storeRoundTripsUnicodeAndClearRemovesHistory() {
        List<FeedItem> incoming = new ArrayList<>();
        incoming.add(new FeedItem("id", "Алиса", "Работа", "Привет 👋\nВторая строка", 1234));
        incoming.add(new FeedItem("empty", "Alice", "Team", "   ", 9999));
        FeedStore.add(context, incoming);
        List<FeedItem> stored = FeedStore.read(context);
        assertEquals(1, stored.size());
        assertEquals("Привет 👋\nВторая строка", stored.get(0).message);
        FeedStore.clear(context);
        assertTrue(FeedStore.read(context).isEmpty());
    }

    @Test public void ignoresOtherAppsAndTelegramGroupSummaries() {
        TelegramNotificationListener listener = Robolectric.buildService(
                TelegramNotificationListener.class).create().get();
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice").setContentText("Message").build();
        listener.onNotificationPosted(status("com.example.other", notification));
        notification.flags |= Notification.FLAG_GROUP_SUMMARY;
        listener.onNotificationPosted(status("org.telegram.messenger", notification));
        assertTrue(FeedStore.read(context).isEmpty());
    }

    @Test public void launchesOnAndroid13() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNotNull(controller.get().findViewById(android.R.id.content));
        }
    }

    @Test @Config(sdk = 28, qualifiers = "w360dp-h800dp-port")
    public void largePortraitFeedCreatesOnlyVisibleCards() throws Exception {
        List<FeedItem> incoming = new ArrayList<>();
        for (int i = 0; i < 100; i++) incoming.add(new FeedItem("view" + i, "Alice", "Team", "Message " + i, i));
        FeedStore.add(context, incoming);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            TestUi.layout(activity, dp(360), dp(800));
            android.widget.GridView grid = TestUi.grid(activity);
            assertEquals(100, grid.getAdapter().getCount());
            assertTrue(grid.getChildCount() > 0);
            assertTrue("Offscreen cards must not all be instantiated", grid.getChildCount() < 25);
            assertEquals(1, grid.getNumColumns());
            assertNull(TestUi.findText(TestUi.content(activity), "Telegram Wall"));
        }
    }

    @Test public void mutatingReadResultDoesNotCorruptCachedHistory() {
        FeedStore.add(context, java.util.Collections.singletonList(new FeedItem("one", "A", "T", "Text", 1)));
        List<FeedItem> first = FeedStore.read(context);
        List<FeedItem> second = FeedStore.read(context);
        assertSame("Immutable items should be reused instead of decoding JSON again", first.get(0), second.get(0));
        first.clear();
        assertEquals(1, FeedStore.read(context).size());
    }

    @Test public void externalPreferenceUpdateInvalidatesHistoryCache() throws Exception {
        FeedStore.read(context);
        org.json.JSONArray array = new org.json.JSONArray();
        array.put(new FeedItem("external", "A", "T", "Updated", 2).toJson());
        context.getSharedPreferences("telegram_wall_feed", Context.MODE_PRIVATE).edit().putString("items", array.toString()).commit();
        assertEquals("Updated", FeedStore.read(context).get(0).message);
    }

    private void assertChildrenInside(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            assertTrue("Child exceeds available width: " + child.getClass().getSimpleName(),
                    child.getRight() <= parent.getWidth());
            if (child instanceof ViewGroup) assertChildrenInside((ViewGroup) child);
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private StatusBarNotification messaging(String[] texts, long[] times) {
        Notification.MessagingStyle style = new Notification.MessagingStyle("Me").setConversationTitle("Team");
        for (int i = 0; i < texts.length; i++) style.addMessage(texts[i], times[i], "Alice");
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build();
        return status("org.telegram.messenger", notification);
    }

    private StatusBarNotification status(String packageName, Notification notification) {
        return new StatusBarNotification(packageName, packageName, 42, "chat", 1000, 0, 0,
                notification, Process.myUserHandle(), 5000);
    }
}
