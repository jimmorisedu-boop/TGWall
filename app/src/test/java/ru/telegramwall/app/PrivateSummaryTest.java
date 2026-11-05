package ru.telegramwall.app;

import static org.junit.Assert.*;
import android.app.Notification;
import android.content.Context;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PrivateSummaryTest {
    private Context context;

    @Before public void clear() {
        context = RuntimeEnvironment.getApplication();
        FeedStore.clear(context);
    }

    @Test public void privateInboxSummaryRetainsTextsThatHaveNoChildNotification() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: Private test", "Bob: Another private message"));
        List<FeedItem> items = FeedStore.read(context);
        assertEquals(2, items.size());
        assertEquals("Alice", items.get(0).sender);
        assertEquals("Private test", items.get(0).message);
        assertEquals("Bob", items.get(1).sender);
        assertEquals("Another private message", items.get(1).message);
        assertEquals(7000, items.get(0).time);
    }

    @Test public void changedSummaryKeepsPreviouslyCapturedMessages() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: First text"));
        listener.onNotificationPosted(summary("pm_0", 8000, "Alice: Second text"));
        List<FeedItem> items = FeedStore.read(context);
        assertEquals(2, items.size());
        assertEquals("Second text", items.get(0).message);
        assertEquals("First text", items.get(1).message);
        assertEquals(8000, items.get(0).time);
    }

    @Test public void repostingSameSummaryDoesNotCreateANewMessageOrChangeItsTime() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: Same text"));
        listener.onNotificationPosted(summary("pm_0", 9000, "Alice: Same text"));
        assertEquals(1, FeedStore.read(context).size());
        assertEquals(7000, FeedStore.read(context).get(0).time);
    }

    @Test public void summaryWithOnlyACounterDoesNotInventMessageContent() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000));
        assertTrue(FeedStore.read(context).isEmpty());
    }

    @Test public void ordinaryGroupSummariesAreStillIgnored() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("groups_0", 7000, "Alice: Group text"));
        assertTrue(FeedStore.read(context).isEmpty());
    }

    @Test public void multilineMessageIsOneCardAndUnreadCounterDoesNotInventMessages() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: First paragraph\n\nSecond paragraph: details"));
        List<FeedItem> items = FeedStore.read(context);
        assertEquals(1, items.size());
        assertEquals("Alice", items.get(0).sender);
        assertEquals("First paragraph\n\nSecond paragraph: details", items.get(0).message);
    }

    @Test public void shiftedInboxWindowDoesNotDuplicateEarlierLines() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: A", "Bob: B"));
        listener.onNotificationPosted(summary("pm_0", 8000, "Carol: C", "Alice: A"));
        assertEquals(3, FeedStore.read(context).size());
        assertEquals("C", FeedStore.read(context).get(0).message);
    }

    @Test public void identicalLinesInOneSnapshotRemainDistinctAcrossReposts() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: Hello", "Alice: Hello"));
        listener.onNotificationPosted(summary("pm_0", 8000, "Alice: Hello", "Alice: Hello"));
        assertEquals(2, FeedStore.read(context).size());
    }

    @Test public void groupMentionKeepsItsSenderAndChat() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice @ Team: Question?"));
        FeedItem item = FeedStore.read(context).get(0);
        assertEquals("Alice", item.sender);
        assertEquals("Team", item.chat);
        assertEquals("Question?", item.message);
    }

    @Test public void realChildNotificationReplacesItsPreviewWithExactMetadata() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        listener.onNotificationPosted(summary("pm_0", 7000, "Alice: Private test"));
        listener.onNotificationPosted(child(6900));
        List<FeedItem> items = FeedStore.read(context);
        assertEquals(1, items.size());
        assertEquals("Private test", items.get(0).message);
        assertEquals(6900, items.get(0).time);
        assertFalse(items.get(0).id.startsWith("preview:"));
        listener.onNotificationPosted(summary("pm_0", 8000, "Alice: Private test"));
        assertEquals(1, FeedStore.read(context).size());
    }

    @Test public void activeSummaryMigratesOldCombinedCardWithoutClearingOtherHistory() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        StatusBarNotification notification = summary("pm_0", 7000, "Alice: A", "Bob: B");
        FeedStore.add(context, java.util.Arrays.asList(
                new FeedItem("summary:" + notification.getKey(), "Личные сообщения", "Telegram X · 38 сообщений", "Alice: A\n\nBob: B", 6000),
                new FeedItem("other", "Carol", "Team", "Keep history", 5000)));
        listener.onNotificationPosted(notification);
        List<FeedItem> items = FeedStore.read(context);
        assertEquals(3, items.size());
        assertTrue(items.stream().anyMatch(item -> item.id.equals("other")));
        assertFalse(items.stream().anyMatch(item -> item.id.startsWith("summary:")));
    }

    @Test public void singlePrivateChatMessagingSummaryKeepsMessagesAndExactTimes() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Alice")
                .setContentText("2 новых сообщения").setGroup("messages0_1").setGroupSummary(true)
                .setStyle(new Notification.MessagingStyle("Me").setConversationTitle("Alice")
                        .addMessage("First", 5000, "Alice").addMessage("Second", 6000, "Alice")).build();
        listener.onNotificationPosted(privateNotification(notification));
        List<FeedItem> items = FeedStore.read(context);
        assertEquals(2, items.size());
        assertEquals("Second", items.get(0).message);
        assertEquals(6000, items.get(0).time);
        assertEquals("First", items.get(1).message);
        assertFalse(items.get(0).isPreview());
    }

    @Test public void singlePrivateMessagePlainSummaryIsCapturedWithoutAChild() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Alice")
                .setContentText("Private message").setWhen(6500)
                .setGroup("messages0_1").setGroupSummary(true).build();
        listener.onNotificationPosted(privateNotification(notification));
        assertEquals(1, FeedStore.read(context).size());
        assertEquals("Private message", FeedStore.read(context).get(0).message);
        assertEquals(6500, FeedStore.read(context).get(0).time);
    }

    @Test public void plainApplicationSummaryCounterIsNotCapturedAsAMessage() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Telegram X")
                .setContentText("38 новых сообщений из 17 чатов")
                .setGroup("messages0_1").setGroupSummary(true).build();
        listener.onNotificationPosted(privateNotification(notification));
        assertTrue(FeedStore.read(context).isEmpty());
    }

    @Test public void exactSummaryMessageIsNotDuplicatedByItsChildNotification() {
        TelegramNotificationListener listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Alice")
                .setGroup("messages0_1").setGroupSummary(true)
                .setStyle(new Notification.MessagingStyle("Me").setConversationTitle("Alice")
                        .addMessage("Private test", 6900, "Alice")).build();
        listener.onNotificationPosted(privateNotification(notification));
        assertEquals(1, FeedStore.read(context).size());
        listener.onNotificationPosted(child(6900));
        assertEquals(1, FeedStore.read(context).size());
        assertFalse(FeedStore.read(context).get(0).id.startsWith("summary-message:"));
    }

    private StatusBarNotification privateNotification(Notification notification) {
        return new StatusBarNotification("org.thunderdog.challegram", "org.thunderdog.challegram", 1, "pm_0",
                1000, 0, 0, notification, Process.myUserHandle(), 10000);
    }

    private StatusBarNotification child(long when) {
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Alice (2)")
                .setSubText("Личные чаты").setContentText("Private test").setWhen(when).build();
        return new StatusBarNotification("org.thunderdog.challegram", "org.thunderdog.challegram", 3, "pm_0",
                1000, 0, 0, notification, Process.myUserHandle(), 10000);
    }

    private StatusBarNotification summary(String tag, long when, String... lines) {
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (String line : lines) style.addLine(line);
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Telegram X")
                .setContentText("38 новых сообщений из 17 чатов").setWhen(when)
                .setGroup("messages0_1").setGroupSummary(true).setStyle(style).build();
        return new StatusBarNotification("org.thunderdog.challegram", "org.thunderdog.challegram", 1, tag,
                1000, 0, 0, notification, Process.myUserHandle(), 10000);
    }
}
