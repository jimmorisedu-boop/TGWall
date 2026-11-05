package ru.telegramwall.app;

import org.json.JSONException;
import org.json.JSONObject;

final class FeedItem {
    final String id;
    final String sender;
    final String chat;
    final String message;
    final long time;
    final String mediaKind;
    final String mediaFile;
    final String mediaState;

    FeedItem(String id, String sender, String chat, String message, long time) {
        this(id, sender, chat, message, time, "", "", "");
    }

    FeedItem(String id, String sender, String chat, String message, long time,
             String mediaKind, String mediaFile, String mediaState) {
        this.id = id;
        this.sender = sender == null || sender.trim().isEmpty() ? "Telegram" : sender.trim();
        this.chat = chat == null || chat.trim().isEmpty() ? "Telegram" : chat.trim();
        this.message = message == null ? "" : message.trim();
        this.time = time;
        this.mediaKind = mediaKind == null ? "" : mediaKind;
        this.mediaFile = mediaFile == null ? "" : mediaFile;
        this.mediaState = mediaState == null ? "" : mediaState;
    }

    boolean isPreview() { return id.startsWith("preview:"); }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("sender", sender);
        object.put("chat", chat);
        object.put("message", message);
        object.put("time", time);
        object.put("mediaKind", mediaKind);
        object.put("mediaFile", mediaFile);
        object.put("mediaState", mediaState);
        return object;
    }

    static FeedItem fromJson(JSONObject object) throws JSONException {
        return new FeedItem(
                object.optString("id"),
                object.optString("sender"),
                object.optString("chat"),
                object.optString("message"),
                object.optLong("time"),
                object.optString("mediaKind"),
                object.optString("mediaFile"),
                object.optString("mediaState")
        );
    }
}
