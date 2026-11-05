package ru.telegramwall.app;

import static org.junit.Assert.*;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.VideoView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w390dp-h844dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NotificationMediaTest {
    private Context context;
    private TelegramNotificationListener listener;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        FeedStore.clear(context);
        listener = Robolectric.buildService(TelegramNotificationListener.class).create().get();
    }

    @After public void stopWorker() { listener.onDestroy(); }

    @Test public void emptyCaptionImageIsCopiedAndSurvivesSourceRemoval() throws Exception {
        Uri uri = register("photo", "/notification-photo.jpg");
        listener.onNotificationPosted(messaging(null, "image/jpeg", uri));
        assertEquals(1, FeedStore.read(context).size());
        finishCopies();
        FeedItem item = FeedStore.read(context).get(0);
        assertEquals("image", item.mediaKind);
        assertEquals("Фото", item.message);
        assertEquals("ready", item.mediaState);
        File file = MediaCache.file(context, item.mediaFile);
        assertNotNull(file);
        Shadows.shadowOf(context.getContentResolver()).registerInputStreamSupplier(uri, () -> null);
        assertNotNull(BitmapFactory.decodeFile(file.getAbsolutePath()));
        listener.onNotificationPosted(messaging(null, "image/jpeg", uri));
        finishCopies();
        assertEquals(1, FeedStore.read(context).size());
        assertEquals("ready", FeedStore.read(context).get(0).mediaState);
    }

    @Test public void bigPictureIsCapturedWithoutCaption() throws Exception {
        Bitmap picture = BitmapFactory.decodeStream(getClass().getResourceAsStream("/notification-photo.jpg"));
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Alice")
                .setStyle(new Notification.BigPictureStyle().bigPicture(picture)).build();
        listener.onNotificationPosted(status(notification));
        finishCopies();
        FeedItem item = FeedStore.read(context).get(0);
        assertEquals("image", item.mediaKind);
        assertNotNull(MediaCache.file(context, item.mediaFile));
    }

    @Test public void videoThumbnailRemainsAPreviewNotAFakePlayableVideo() throws Exception {
        listener.onNotificationPosted(messaging("📹 Репортаж", "image/jpeg", register("video-thumb", "/notification-photo.jpg")));
        finishCopies();
        FeedItem item = FeedStore.read(context).get(0);
        assertEquals("video", item.mediaKind);
        assertFalse(item.mediaFile.endsWith(".video"));
        assertNotNull(MediaCache.file(context, item.mediaFile));
    }

    @Test public void videoWithoutAttachmentHasAnExplicitUnavailableState() {
        listener.onNotificationPosted(messaging("Видео", "", null));
        FeedItem item = FeedStore.read(context).get(0);
        assertEquals("video", item.mediaKind);
        assertEquals("unavailable", item.mediaState);
        assertEquals("", item.mediaFile);
    }

    @Test public void inaccessibleImageKeepsTheNotificationTextAndDoesNotCrash() throws Exception {
        listener.onNotificationPosted(messaging("Фото: отчёт", "image/jpeg", Uri.parse("content://missing-provider/photo")));
        finishCopies();
        FeedItem item = FeedStore.read(context).get(0);
        assertEquals("Фото: отчёт", item.message);
        assertEquals("unavailable", item.mediaState);
        assertEquals("", item.mediaFile);
    }

    @Test public void largeIconIsNeverMistakenForAnAttachedPhoto() throws Exception {
        Bitmap avatar = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Notification notification = new Notification.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Alice")
                .setContentText("Привет").setLargeIcon(avatar).build();
        listener.onNotificationPosted(status(notification));
        finishCopies();
        assertEquals("", FeedStore.read(context).get(0).mediaKind);
    }

    @Test public void completedLoadCannotResurrectClearedHistory() throws Exception {
        String file = MediaCache.copy(context, "cleared", register("cleared", "/notification-photo.jpg"), "image/jpeg", null);
        FeedStore.add(context, Collections.singletonList(new FeedItem("cleared", "Alice", "Team", "Фото", 1,
                "image", file, "ready")));
        FeedStore.clear(context);
        assertFalse(FeedStore.updateMedia(context, "cleared", "image", file, "ready"));
        assertTrue(FeedStore.read(context).isEmpty());
        assertNull(MediaCache.file(context, file));
    }

    @Test public void mediaFieldsPersistAndOldHistoryStillLoads() throws Exception {
        FeedItem item = new FeedItem("1", "Alice", "Team", "Caption", 10, "image", "abc.jpg", "ready");
        FeedItem restored = FeedItem.fromJson(item.toJson());
        assertEquals(item.mediaFile, restored.mediaFile);
        assertEquals(item.mediaKind, restored.mediaKind);
        assertEquals(item.mediaState, restored.mediaState);
        org.json.JSONObject old = new org.json.JSONObject("{\"id\":\"old\",\"sender\":\"Alice\",\"message\":\"Text\",\"time\":1}");
        assertEquals("", FeedItem.fromJson(old).mediaKind);
    }

    @Test public void quotaEvictsOldMediaAndUnsafePathsAreRejected() throws Exception {
        String name = UUID.randomUUID() + ".video";
        File file = new File(MediaCache.directory(context), name);
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.setLength(96L * 1024 * 1024 + 1); }
        file.setLastModified(System.currentTimeMillis() - 600000);
        MediaCache.prune(context, Collections.singletonList(new FeedItem("1", "A", "T", "Video", 1, "video", name, "ready")));
        assertFalse(file.exists());
        assertNull(MediaCache.file(context, "../other-file.jpg"));
    }

    @Test public void neverFetchesNetworkUrls() throws Exception {
        try {
            MediaCache.copy(context, "network", Uri.parse("https://example.com/photo.jpg"), "image/jpeg", null);
            fail("Only content URIs from a notification are accepted");
        } catch (IOException expected) { }
    }

    @Test public void serviceReconnectDoesNotLeaveAnEndlessLoadingCard() throws Exception {
        FeedStore.add(context, Collections.singletonList(new FeedItem("stale", "Alice", "Team", "Фото", 1,
                "image", "", "loading")));
        listener.onListenerConnected();
        finishCopies();
        assertEquals("unavailable", FeedStore.read(context).get(0).mediaState);
    }

    @Test public void actualSuppliedVideoStartsAutomaticallyMutedAndStopsOnPause() throws Exception {
        listener.onNotificationPosted(messaging("Видео", "video/mp4", register("video", "/notification-video.mp4")));
        finishCopies();
        assertTrue(FeedStore.read(context).get(0).mediaFile.endsWith(".video"));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = ((ViewGroup) controller.get().findViewById(android.R.id.content)).getChildAt(0);
            TestUi.layout(controller.get(), 390, 844);
            VideoView video = findVideo(root);
            assertNotNull("No tap should be required to prepare visible video", video);
            MediaPlayer preparedPlayer = new MediaPlayer();
            Shadows.shadowOf(video).getOnPreparedListener().onPrepared(preparedPlayer);
            assertTrue(video.isPlaying());
            assertTrue(preparedPlayer.isLooping());
            assertEquals(0f, Shadows.shadowOf(preparedPlayer).getLeftVolume(), 0f);
            assertEquals(0f, Shadows.shadowOf(preparedPlayer).getRightVolume(), 0f);
            assertNull(Shadows.shadowOf(controller.get()).peekNextStartedActivity());
            controller.pause();
            assertNull(video.getParent());
            preparedPlayer.release();
        }
    }

    @Test public void playbackErrorNeverOpensAModalDialog() throws Exception {
        listener.onNotificationPosted(messaging("Видео", "video/mp4", register("bad-video", "/notification-video.mp4")));
        finishCopies();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = ((ViewGroup) controller.get().findViewById(android.R.id.content)).getChildAt(0);
            TestUi.layout(controller.get(), 390, 844);
            VideoView video = findVideo(root);
            assertNotNull(video);
            assertTrue(Shadows.shadowOf(video).getOnErrorListener().onError(null, 1, 0));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertNull(findVideo(root));
        }
    }

    private void finishCopies() throws Exception {
        Field field = TelegramNotificationListener.class.getDeclaredField("mediaExecutor");
        field.setAccessible(true);
        ((ExecutorService) field.get(listener)).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private Uri register(String name, String resource) throws IOException {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        Uri uri = Uri.parse("content://fixture.notifications/" + name);
        Shadows.shadowOf(context.getContentResolver()).registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(bytes));
        return uri;
    }

    private StatusBarNotification messaging(String text, String mime, Uri uri) {
        Notification.MessagingStyle.Message message = new Notification.MessagingStyle.Message(text, 1000, "Alice");
        if (uri != null) message.setData(mime, uri);
        Notification notification = new Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(new Notification.MessagingStyle("Me").setConversationTitle("Team").addMessage(message)).build();
        return status(notification);
    }

    private StatusBarNotification status(Notification notification) {
        return new StatusBarNotification("org.telegram.messenger", "org.telegram.messenger", 42, "media", 1000, 0, 0,
                notification, Process.myUserHandle(), 5000);
    }

    private VideoView findVideo(View view) {
        if (view instanceof VideoView) return (VideoView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                VideoView result = findVideo(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
