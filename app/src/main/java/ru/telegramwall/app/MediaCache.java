package ru.telegramwall.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.*;
import java.util.*;

/** Private, bounded copies: notification URI permissions can disappear when Telegram cancels it. */
final class MediaCache {
    private static final long MAX_SOURCE = 24L * 1024 * 1024;
    private static final long MAX_CACHE = 96L * 1024 * 1024;
    private static final int MAX_AVATAR_DIMENSION = 256;
    private MediaCache() {}

    static File directory(Context context) {
        File directory = new File(context.getFilesDir(), "notification_media");
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    static File file(Context context, String name) {
        if (name == null || !name.matches("(?:[a-f0-9-]+\\.(?:jpg|png|video)|avatar-[a-f0-9-]+\\.(?:jpg|png))")) return null;
        File file = new File(directory(context), name);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    static String copy(Context context, String id, Uri uri, String mime, Bitmap picture) throws IOException {
        String key = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        File source = new File(directory(context), key + ".part");
        try {
            if (picture != null) return saveBitmap(context, key, picture);
            // Never fetch network links or read arbitrary filesystem paths from a notification.
            if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("No granted content URI");
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(source)) {
                if (input == null) throw new IOException("No media stream");
                byte[] buffer = new byte[16384];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    total += count;
                    if (total > MAX_SOURCE) throw new IOException("Attachment exceeds local limit");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("Empty attachment");
            }
            if (mime.startsWith("video/")) {
                File video = new File(directory(context), key + ".video");
                if (!source.renameTo(video)) throw new IOException("Cannot save video");
                try {
                    android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(video.getAbsolutePath());
                        Bitmap frame = retriever.getFrameAtTime(0);
                        if (frame != null) {
                            try { saveBitmap(context, key, frame); }
                            finally { frame.recycle(); }
                        }
                    } finally { retriever.release(); }
                } catch (Exception ignored) { /* Playback can still work without a thumbnail. */ }
                return video.getName();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not an image");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2;
            Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (decoded == null) throw new IOException("Cannot decode image");
            try { return saveBitmap(context, key, decoded); }
            finally { decoded.recycle(); }
        } finally {
            source.delete();
        }
    }

    private static String saveBitmap(Context context, String key, Bitmap bitmap) throws IOException {
        return saveBitmap(context, key, bitmap, 1280, "");
    }

    static String copyAvatar(Context context, String id, Bitmap picture) throws IOException {
        if (picture == null) throw new IOException("No avatar image");
        String key = UUID.nameUUIDFromBytes(("avatar:" + id)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return saveBitmap(context, key, picture, MAX_AVATAR_DIMENSION, "avatar-");
    }

    private static String saveBitmap(Context context, String key, Bitmap bitmap,
                                     int maxDimension, String prefix) throws IOException {
        if (bitmap.isRecycled()) throw new IOException("Image no longer available");
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) throw new IOException("Invalid image size");
        float scale = Math.min(1f, (float) maxDimension / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Bitmap bounded = scale < 1f ? Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true) : bitmap;
        String extension = bounded.hasAlpha() ? ".png" : ".jpg";
        File target = new File(directory(context), prefix + key + extension);
        File pending = new File(directory(context), prefix + key + ".image.part");
        try {
            try (OutputStream output = new FileOutputStream(pending)) {
                if (!bounded.compress(bounded.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, output)) {
                    throw new IOException("Cannot save image");
                }
            }
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace image");
            if (!pending.renameTo(target)) throw new IOException("Cannot commit image");
            return target.getName();
        } finally {
            pending.delete();
            if (bounded != bitmap) bounded.recycle();
        }
    }

    static void prune(Context context, List<FeedItem> items) {
        Set<String> referenced = new HashSet<>();
        for (FeedItem item : items) {
            referenced.add(item.mediaFile);
            referenced.add(item.avatarFile);
            if (item.mediaFile.endsWith(".video")) {
                String key = item.mediaFile.substring(0, item.mediaFile.length() - 6);
                referenced.add(key + ".jpg");
                referenced.add(key + ".png");
            }
        }
        File[] files = directory(context).listFiles();
        if (files == null) return;
        long total = 0, now = System.currentTimeMillis();
        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            // A short grace period protects a just-finished copy awaiting its store update.
            if (!referenced.contains(file.getName()) && now - file.lastModified() > 300000L) file.delete();
            if (file.exists()) { total += file.length(); candidates.add(file); }
        }
        candidates.sort(Comparator.comparingLong(File::lastModified));
        for (File file : candidates) {
            if (total <= MAX_CACHE) break;
            if ((!referenced.contains(file.getName()) && now - file.lastModified() < 300000L)
                    || file.getName().endsWith(".part")) continue;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    static void clear(Context context) {
        File[] files = directory(context).listFiles();
        if (files == null) return;
        for (File file : files) if (file.isFile() && !file.getName().endsWith(".part")) file.delete();
    }
}
