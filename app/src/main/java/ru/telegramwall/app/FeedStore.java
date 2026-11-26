package ru.telegramwall.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class FeedStore {
    private static final String PREFS = "telegram_wall_feed";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 100;
    private static String cachedRaw;
    private static String cachedDirectory;
    private static List<FeedItem> cachedItems;

    private FeedStore() {}

    static synchronized List<FeedItem> read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ITEMS, "[]");
        String directory = context.getApplicationInfo().dataDir;
        if (cachedItems != null && directory.equals(cachedDirectory) && raw.equals(cachedRaw)) {
            return new ArrayList<>(cachedItems);
        }
        ArrayList<FeedItem> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) result.add(FeedItem.fromJson(object));
            }
        } catch (JSONException ignored) {
            // A corrupt local cache should not prevent the app from opening.
        }
        sortNewestFirst(result);
        cachedRaw = raw;
        cachedDirectory = directory;
        cachedItems = new ArrayList<>(result);
        return result;
    }

    static synchronized void add(Context context, List<FeedItem> incoming) {
        merge(context, incoming, null);
    }

    static synchronized void addSummaryLines(Context context, String notificationKey, List<FeedItem> incoming) {
        merge(context, incoming, "summary:" + notificationKey);
    }

    private static void merge(Context context, List<FeedItem> incoming, String legacySummaryId) {
        if (incoming == null || incoming.isEmpty()) return;
        ArrayList<FeedItem> all = new ArrayList<>(read(context));
        boolean changed = legacySummaryId != null && all.removeIf(item -> item.id.equals(legacySummaryId));
        for (FeedItem candidate : incoming) {
            int detail = detailRank(candidate);
            boolean hasBetter = false;
            for (FeedItem existing : all) {
                if (detailRank(existing) > detail && matchesFallback(candidate, existing)) { hasBetter = true; break; }
            }
            if (hasBetter) continue;
            changed |= all.removeIf(existing -> detailRank(existing) < detail && matchesFallback(existing, candidate));
            boolean duplicate = false;
            for (int index = 0; index < all.size(); index++) {
                FeedItem existing = all.get(index);
                if (existing.id.equals(candidate.id)) {
                    duplicate = true;
                    if (candidate.id.startsWith("summary:") && !existing.message.equals(candidate.message)) {
                        all.set(index, withAvatar(candidate,
                                candidate.avatarFile.isEmpty() ? existing.avatarFile : candidate.avatarFile));
                        changed = true;
                    } else if (!candidate.avatarFile.isEmpty()
                            && !candidate.avatarFile.equals(existing.avatarFile)) {
                        all.set(index, withAvatar(existing, candidate.avatarFile));
                        changed = true;
                    }
                    break;
                }
            }
            if (!duplicate && (!candidate.message.isEmpty() || !candidate.mediaKind.isEmpty())) {
                all.add(candidate);
                changed = true;
            }
        }
        if (!changed) return;
        sortNewestFirst(all);
        if (all.size() > MAX_ITEMS) {
            all = new ArrayList<>(all.subList(0, MAX_ITEMS));
        }
        save(context, all);
        MediaCache.prune(context, all);
    }

    private static int detailRank(FeedItem item) {
        return item.isPreview() ? 0 : item.id.startsWith("summary-message:") ? 1 : 2;
    }

    private static boolean matchesFallback(FeedItem preview, FeedItem child) {
        String account = sourceAccount(preview);
        if (account.isEmpty() || !account.equals(sourceAccount(child))
                || !preview.message.equals(child.message)
                || !plainSender(preview.sender).equals(plainSender(child.sender))) return false;
        if (!preview.isPreview() && !child.isPreview() && preview.time != child.time) return false;
        return preview.chat.equals(child.chat) || (isDirectChat(preview) && isDirectChat(child));
    }

    private static boolean isDirectChat(FeedItem item) {
        return item.chat.equals("Telegram") || item.chat.equals("Личные чаты")
                || plainSender(item.chat).equals(plainSender(item.sender));
    }

    private static String sourceAccount(FeedItem item) {
        String[] parts = item.id.split("\\|", 5);
        if (parts.length < 5) return "";
        String user = parts[0].substring(parts[0].lastIndexOf(':') + 1);
        return user + "|" + parts[1] + "|" + parts[3];
    }

    private static String plainSender(String sender) { return sender.replaceFirst("\\s+\\([0-9]+\\)$", "").trim(); }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ITEMS).apply();
        MediaCache.clear(context);
        cachedItems = null;
        cachedRaw = null;
    }

    static synchronized boolean updateMedia(Context context, String id, String kind, String file, String state) {
        List<FeedItem> all = read(context);
        for (int i = 0; i < all.size(); i++) {
            FeedItem old = all.get(i);
            if (!old.id.equals(id)) continue;
            if (file.isEmpty() && MediaCache.file(context, old.mediaFile) != null) return false;
            all.set(i, new FeedItem(old.id, old.sender, old.chat, old.message, old.time,
                    kind, file, state, old.avatarFile));
            save(context, all);
            MediaCache.prune(context, all);
            return true;
        }
        return false; // A completed background load must not restore cleared or expired cards.
    }

    static synchronized boolean updateAvatar(Context context, String id, String file) {
        List<FeedItem> all = read(context);
        String next = file == null ? "" : file;
        for (int i = 0; i < all.size(); i++) {
            FeedItem old = all.get(i);
            if (!old.id.equals(id)) continue;
            if (next.isEmpty()) return false;
            all.set(i, withAvatar(old, next));
            save(context, all);
            MediaCache.prune(context, all);
            return true;
        }
        return false; // A completed background load must not restore cleared or expired cards.
    }

    static synchronized void discardAvatarIfUnreferenced(Context context, String file) {
        if (file == null || file.isEmpty()) return;
        for (FeedItem item : read(context)) {
            if (file.equals(item.avatarFile)) return;
        }
        java.io.File orphan = MediaCache.file(context, file);
        if (orphan != null) orphan.delete();
    }

    private static FeedItem withAvatar(FeedItem item, String avatarFile) {
        return new FeedItem(item.id, item.sender, item.chat, item.message, item.time,
                item.mediaKind, item.mediaFile, item.mediaState, avatarFile);
    }

    private static void save(Context context, List<FeedItem> items) {
        JSONArray array = new JSONArray();
        for (FeedItem item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
                // Skip malformed item.
            }
        }
        String raw = array.toString();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, raw).apply();
        cachedRaw = raw;
        cachedDirectory = context.getApplicationInfo().dataDir;
        cachedItems = new ArrayList<>(items);
    }

    private static void sortNewestFirst(List<FeedItem> items) {
        Collections.sort(items, new Comparator<FeedItem>() {
            @Override public int compare(FeedItem left, FeedItem right) {
                return Long.compare(right.time, left.time);
            }
        });
    }
}
