package ru.telegramwall.app;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TelegramNotificationListener extends NotificationListenerService {
    static final String ACTION_FEED_CHANGED = "ru.telegramwall.app.FEED_CHANGED";
    private final Set<String> pendingMedia = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingAvatars = ConcurrentHashMap.newKeySet();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "notification-media");
        thread.setDaemon(true);
        return thread;
    });

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        mediaExecutor.execute(() -> {
            settleStaleLoads(false);
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification notification : active) {
                    if (Thread.currentThread().isInterrupted()) break;
                    capture(notification);
                }
            } catch (SecurityException ignored) { }
        });
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { capture(sbn); }

    @Override public void onDestroy() {
        mediaExecutor.shutdownNow();
        settleStaleLoads(true);
        super.onDestroy();
    }

    private void settleStaleLoads(boolean stopping) {
        boolean changed = false;
        for (FeedItem item : FeedStore.read(this)) {
            if ("loading".equals(item.mediaState) && (stopping || !pendingMedia.contains(item.id))) {
                changed |= FeedStore.updateMedia(this, item.id, item.mediaKind, "", "unavailable");
            }
        }
        if (changed) notifyFeedChanged();
    }

    private void capture(StatusBarNotification sbn) {
        if (sbn == null || !isTelegramPackage(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        boolean isSummary = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean privateSummary = isSummary && isPrivateSummary(sbn);
        if (isSummary && !privateSummary) return;
        boolean summaryHasMessages = false;
        if (privateSummary) {
            Object messages = extras.get(Notification.EXTRA_MESSAGES);
            summaryHasMessages = messages instanceof Parcelable[] && ((Parcelable[]) messages).length > 0;
            if (!summaryHasMessages) {
                if (extras.containsKey(Notification.EXTRA_MESSAGES)) return;
                String template = value(extras.getString(Notification.EXTRA_TEMPLATE));
                if (extras.containsKey(Notification.EXTRA_TEXT_LINES) || template.endsWith("$InboxStyle")) {
                    capturePrivateSummary(sbn, notification);
                    return;
                }
                String summaryTitle = value(extras.getCharSequence(Notification.EXTRA_TITLE));
                if (summaryTitle.isEmpty() || summaryTitle.equalsIgnoreCase("Telegram X")
                        || summaryTitle.equalsIgnoreCase("Telegram")) return;
            }
        }
        String title = value(extras.getCharSequence(Notification.EXTRA_TITLE));
        String conversation = value(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String subText = value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String chat = !conversation.isEmpty() ? conversation : !subText.isEmpty() ? subText : "Telegram";
        long postedAt = sbn.getPostTime();
        List<FeedItem> items = new ArrayList<>();
        List<Attachment> attachments = new ArrayList<>();
        List<AvatarAttachment> avatars = new ArrayList<>();
        Set<String> messageSenders = new HashSet<>();
        boolean allMessagesHavePersonalSender = true;
        boolean messagingParseFailed = false;

        try {
            Parcelable[] rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (rawMessages != null) {
                Map<String, Integer> occurrences = new HashMap<>();
                for (Parcelable raw : rawMessages) {
                    if (!(raw instanceof Bundle)) continue;
                    Bundle message = (Bundle) raw;
                    String body = value(message.getCharSequence("text"));
                    String mime = value(message.getString("type")).toLowerCase(Locale.ROOT);
                    Object rawUri = message.getParcelable("uri");
                    Uri uri = rawUri instanceof Uri ? (Uri) rawUri : null;
                    boolean supported = mime.startsWith("image/") || mime.startsWith("video/");
                    String kind = mediaKind(body, mime);
                    if (body.isEmpty() && (!supported || uri == null)) continue;
                    if (body.isEmpty()) body = "video".equals(kind) ? "Видео" : "Фото";
                    String sender = value(message.getCharSequence("sender"));
                    boolean personalSender = !sender.isEmpty();
                    Object senderIcon = null;
                    if (Build.VERSION.SDK_INT >= 28) {
                        try {
                            Object person = message.getParcelable("sender_person");
                            if (person instanceof android.app.Person) {
                                android.app.Person senderPerson = (android.app.Person) person;
                                String personName = value(senderPerson.getName());
                                if (!personName.isEmpty()) {
                                    sender = personName;
                                    personalSender = true;
                                }
                                if (personalSender) senderIcon = senderPerson.getIcon();
                            }
                        } catch (RuntimeException ignored) {
                            // A malformed sender parcel must not discard otherwise valid messages.
                        }
                    }
                    if (sender.isEmpty()) {
                        sender = title;
                        personalSender = false;
                    }
                    if (personalSender) messageSenders.add(sender);
                    else allMessagesHavePersonalSender = false;
                    long time = message.getLong("time", postedAt);
                    String base = stableId(sbn.getKey(), time, sender, body);
                    int occurrence = occurrences.getOrDefault(base, 0);
                    occurrences.put(base, occurrence + 1);
                    String id = (privateSummary ? "summary-message:" : "") + base + "_" + occurrence;
                    FeedItem item = new FeedItem(id, sender, chat, body, time, kind, "",
                            supported && uri != null ? "loading" : kind.isEmpty() ? "" : "unavailable");
                    items.add(item);
                    if (supported && uri != null) attachments.add(new Attachment(item, uri, mime, null));
                    if (senderIcon != null) avatars.add(new AvatarAttachment(item, senderIcon));
                }
            }
        } catch (RuntimeException ignored) {
            messagingParseFailed = true;
            /* Keep any valid messages, then use standard text if necessary. */
        }

        if (summaryHasMessages && items.isEmpty()) return;

        Object picture = extras.get(Notification.EXTRA_PICTURE);
        if (!(picture instanceof Bitmap)) picture = extras.get("android.pictureIcon");
        if (!(picture instanceof Bitmap) && !(picture instanceof Icon)) picture = null;
        if (items.isEmpty()) {
            String body = value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (body.isEmpty()) body = value(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (body.isEmpty()) body = value(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            String kind = picture != null ? mediaKind(body, "image/jpeg") : mediaKind(body, "");
            if (!body.isEmpty() || picture != null) {
                long time = notification.when > 0 ? notification.when : postedAt;
                String id = (privateSummary ? "summary-message:" : "") + stableId(sbn.getKey(), time, title, body) + "_0";
                items.add(new FeedItem(id, title, chat, body.isEmpty() ? "Фото" : body, time,
                        kind, "", picture != null ? "loading" : kind.isEmpty() ? "" : "unavailable"));
            }
        }
        if (picture != null && !items.isEmpty() && attachments.isEmpty()) {
            int latest = 0;
            for (int i = 1; i < items.size(); i++) if (items.get(i).time >= items.get(latest).time) latest = i;
            FeedItem item = items.get(latest);
            item = new FeedItem(item.id, item.sender, item.chat, item.message, item.time,
                    "video".equals(item.mediaKind) ? "video" : "image", "", "loading");
            items.set(latest, item);
            attachments.add(new Attachment(item, null, "image/jpeg", picture));
        }
        Object largeIcon = directLargeIcon(notification, extras, conversation, subText, isSummary,
                allMessagesHavePersonalSender, messagingParseFailed, messageSenders, attachments, picture);
        if (largeIcon != null && avatars.isEmpty()) {
            for (FeedItem item : items) avatars.add(new AvatarAttachment(item, largeIcon));
        }
        if (items.isEmpty()) return;
        if (privateSummary) FeedStore.addSummaryLines(this, sbn.getKey(), items);
        else FeedStore.add(this, items);
        notifyFeedChanged();
        for (Attachment attachment : attachments) load(attachment);
        for (AvatarAttachment avatar : avatars) loadAvatar(avatar);
    }

    private static boolean isPrivateSummary(StatusBarNotification sbn) {
        return "org.thunderdog.challegram".equals(sbn.getPackageName())
                && sbn.getTag() != null && sbn.getTag().startsWith("pm_");
    }

    private static Object directLargeIcon(Notification notification, Bundle extras,
                                          String conversation, String subText, boolean isSummary,
                                          boolean allMessagesHavePersonalSender,
                                          boolean messagingParseFailed,
                                          Set<String> messageSenders,
                                          List<Attachment> attachments, Object picture) {
        if (Build.VERSION.SDK_INT < 28 || isSummary || !attachments.isEmpty() || picture != null
                || !allMessagesHavePersonalSender || messagingParseFailed || messageSenders.size() != 1) return null;
        try {
            boolean hasGroupFlag = extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION);
            if (hasGroupFlag && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return null;
            String identity = messageSenders.iterator().next();
            if ((!conversation.isEmpty() && !identity.equals(conversation))
                    || (!subText.isEmpty() && !identity.equals(subText))) return null;
            boolean identityLabel = identity.equals(conversation) || identity.equals(subText);
            if (!identityLabel && !hasGroupFlag) return null;

            Icon icon = notification.getLargeIcon();
            if (icon != null) {
                return icon.getType() == Icon.TYPE_BITMAP || icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP
                        ? icon : null;
            }
            Object raw = extras.get("android.largeIcon");
            return raw instanceof Bitmap ? raw : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void capturePrivateSummary(StatusBarNotification sbn, Notification notification) {
        // Telegram X can deliver private push previews in InboxStyle before posting child notifications.
        if (!isPrivateSummary(sbn)) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null) return;
        List<FeedItem> previews = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        long time = notification.when > 0 ? notification.when : sbn.getPostTime();
        for (CharSequence line : lines) {
            String text = value(line);
            if (text.isEmpty()) continue;
            String sender = "Личные сообщения", chat = "Личные чаты", body = text;
            int delimiter = text.indexOf(": ");
            if (delimiter > 0 && delimiter < 256 && text.substring(0, delimiter).indexOf('\n') < 0) {
                sender = text.substring(0, delimiter).trim();
                body = text.substring(delimiter + 2).trim();
                int group = sender.lastIndexOf(" @ ");
                if (group > 0) {
                    chat = sender.substring(group + 3).trim();
                    sender = sender.substring(0, group).trim();
                }
            }
            if (body.isEmpty()) continue;
            String digest = UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
            int occurrence = occurrences.getOrDefault(digest, 0);
            occurrences.put(digest, occurrence + 1);
            String id = "preview:" + sbn.getKey() + "_" + digest + "_" + occurrence;
            String kind = mediaKind(body, "");
            previews.add(new FeedItem(id, sender, chat, body, time, kind, "", kind.isEmpty() ? "" : "unavailable"));
        }
        if (previews.isEmpty()) return; // A counter alone is not message content.
        FeedStore.addSummaryLines(this, sbn.getKey(), previews);
        notifyFeedChanged();
    }

    private void load(Attachment attachment) {
        for (FeedItem stored : FeedStore.read(this)) {
            if (stored.id.equals(attachment.item.id) && MediaCache.file(this, stored.mediaFile) != null) return;
        }
        if (!pendingMedia.add(attachment.item.id)) return;
        try {
            mediaExecutor.execute(() -> {
                String file = "";
                Bitmap picture = null;
                try {
                    picture = picture(attachment.picture);
                    file = MediaCache.copy(this, attachment.item.id, attachment.uri, attachment.mime, picture);
                    if (FeedStore.updateMedia(this, attachment.item.id, attachment.item.mediaKind, file, "ready")) {
                        notifyFeedChanged();
                    } else {
                        java.io.File orphan = MediaCache.file(this, file);
                        if (orphan != null) orphan.delete();
                    }
                } catch (IOException | RuntimeException ignored) {
                    if (FeedStore.updateMedia(this, attachment.item.id, attachment.item.mediaKind, "", "unavailable")) notifyFeedChanged();
                } finally {
                    if (attachment.picture instanceof Icon && picture != null) picture.recycle();
                    pendingMedia.remove(attachment.item.id);
                }
            });
        } catch (RejectedExecutionException ignored) { pendingMedia.remove(attachment.item.id); }
    }

    private void loadAvatar(AvatarAttachment attachment) {
        for (FeedItem stored : FeedStore.read(this)) {
            if (stored.id.equals(attachment.item.id) && MediaCache.file(this, stored.avatarFile) != null) return;
        }
        if (!pendingAvatars.add(attachment.item.id)) return;
        try {
            mediaExecutor.execute(() -> {
                Bitmap picture = null;
                String file = "";
                try {
                    picture = picture(attachment.icon, 256);
                    file = MediaCache.copyAvatar(this, attachment.item.id, picture);
                    if (FeedStore.updateAvatar(this, attachment.item.id, file)) {
                        notifyFeedChanged();
                    } else {
                        FeedStore.discardAvatarIfUnreferenced(this, file);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Bad or inaccessible notification icons should not affect the feed.
                } finally {
                    if (attachment.icon instanceof Icon && picture != null) picture.recycle();
                    pendingAvatars.remove(attachment.item.id);
                }
            });
        } catch (RejectedExecutionException ignored) { pendingAvatars.remove(attachment.item.id); }
    }

    private Bitmap picture(Object raw) {
        return picture(raw, 1280);
    }

    private Bitmap picture(Object raw, int maxDimension) {
        if (raw instanceof Bitmap) return (Bitmap) raw;
        if (!(raw instanceof Icon)) return null;
        Drawable drawable = ((Icon) raw).loadDrawable(this);
        if (drawable == null) return null;
        int width = Math.max(1, drawable.getIntrinsicWidth()), height = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.min(1f, (float) maxDimension / Math.max(width, height));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void notifyFeedChanged() {
        android.content.Intent intent = new android.content.Intent(ACTION_FEED_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    static String mediaKind(String text, String mime) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/") || text.startsWith("📹") || text.startsWith("🎥")
                || lower.equals("видео") || lower.equals("video")) return "video";
        if (mime.startsWith("image/") || text.startsWith("📷") || text.startsWith("🖼")
                || lower.equals("фото") || lower.equals("фотография") || lower.equals("photo")
                || lower.equals("стикер") || lower.equals("sticker")) return "image";
        return "";
    }

    private static String stableId(String key, long time, String sender, String body) {
        String content = UUID.nameUUIDFromBytes((sender.length() + ":" + sender + body).getBytes(StandardCharsets.UTF_8)).toString();
        return key + "_" + time + "_" + content;
    }

    private boolean isTelegramPackage(String name) {
        return "org.telegram.messenger".equals(name) || "org.telegram.messenger.beta".equals(name)
                || "org.thunderdog.challegram".equals(name);
    }

    private static String value(CharSequence text) { return text == null ? "" : text.toString().trim(); }

    private static final class Attachment {
        final FeedItem item;
        final Uri uri;
        final String mime;
        final Object picture;
        Attachment(FeedItem item, Uri uri, String mime, Object picture) {
            this.item = item; this.uri = uri; this.mime = mime; this.picture = picture;
        }
    }

    private static final class AvatarAttachment {
        final FeedItem item;
        final Object icon;
        AvatarAttachment(FeedItem item, Object icon) {
            this.item = item; this.icon = icon;
        }
    }
}
