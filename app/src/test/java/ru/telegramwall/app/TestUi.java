package ru.telegramwall.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.TextView;
import org.robolectric.Shadows;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

final class TestUi {
    private TestUi() {}
    static void settle(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("historyExecutor");
        field.setAccessible(true);
        for (int i = 0; i < 2; i++) {
            ((ExecutorService) field.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }
    static View content(MainActivity activity) {
        return ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    }
    static GridView grid(MainActivity activity) { return (GridView) ((ViewGroup) content(activity)).getChildAt(0); }
    static void layout(MainActivity activity, int width, int height) throws Exception {
        settle(activity);
        View root = content(activity);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                TextView found = findText(parent.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
