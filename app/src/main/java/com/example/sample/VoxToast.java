package com.example.sample;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

public class VoxToast {

    public static final int SUCCESS = 0;
    public static final int ERROR = 1;
    public static final int WARNING = 2;
    public static final int INFO = 3;

    private static final int DEFAULT_DURATION = 2500;
    private static final int LONG_DURATION = 4500;

    public static void show(Context context, String message, int type) {
        show(context, message, type, type == ERROR ? LONG_DURATION : DEFAULT_DURATION);
    }

    public static void show(Context context, String message, int type, int durationMs) {
        if (context == null) return;
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> showInternal(activity, message, type, durationMs));
        } else {
            showInternal(context, message, type, durationMs);
        }
    }

    private static void showInternal(Context context, String message, int type, int durationMs) {
        try {
            LayoutInflater inflater = LayoutInflater.from(context);
            View view = inflater.inflate(R.layout.toast_custom, null);

            View indicator = view.findViewById(R.id.toastIndicator);
            TextView tvMessage = view.findViewById(R.id.toastMessage);

            tvMessage.setText(message);

            int color;
            switch (type) {
                case SUCCESS: color = Color.parseColor("#22C55E"); break;
                case ERROR:   color = Color.parseColor("#EF4444"); break;
                case WARNING: color = Color.parseColor("#BA7517"); break;
                case INFO:
                default:      color = Color.parseColor("#6366F1"); break;
            }

            indicator.setBackgroundColor(color);

            PopupWindow popup = new PopupWindow(view,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    false);

            popup.setOutsideTouchable(true);
            popup.setClippingEnabled(false);

            if (context instanceof android.app.Activity) {
                android.app.Activity activity = (android.app.Activity) context;
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    View rootView = activity.findViewById(android.R.id.content);
                    if (rootView != null) {
                        popup.showAtLocation(rootView, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 100);

                        view.postDelayed(() -> {
                            if (popup.isShowing()) {
                                try { popup.dismiss(); } catch (Exception ignored) {}
                            }
                        }, durationMs);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static void success(Context context, String message) {
        show(context, message, SUCCESS);
    }

    public static void success(Context context, String message, int durationMs) {
        show(context, message, SUCCESS, durationMs);
    }

    public static void error(Context context, String message) {
        show(context, message, ERROR);
    }

    public static void error(Context context, String message, int durationMs) {
        show(context, message, ERROR, durationMs);
    }

    public static void warning(Context context, String message) {
        show(context, message, WARNING);
    }

    public static void warning(Context context, String message, int durationMs) {
        show(context, message, WARNING, durationMs);
    }

    public static void info(Context context, String message) {
        show(context, message, INFO);
    }

    public static void info(Context context, String message, int durationMs) {
        show(context, message, INFO, durationMs);
    }
}
