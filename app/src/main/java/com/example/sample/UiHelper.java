package com.example.sample;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

public class UiHelper {

    public static boolean shouldAnimate(Context context) {
        try {
            float scale = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE);
            return scale > 0f;
        } catch (Settings.SettingNotFoundException e) {
            return true;
        }
    }

    public static int dpToPx(Context context, float dp) {
        return Math.round(context.getResources().getDisplayMetrics().density * dp);
    }

    public static void fadeIn(View view, long duration) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void fadeOut(View view, long duration, Runnable endAction) {
        if (view == null) return;
        view.animate()
                .alpha(0f)
                .setDuration(duration)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    if (endAction != null) endAction.run();
                })
                .start();
    }

    public static void setTextWithFade(TextView textView, String text, long outDuration, long inDuration) {
        if (textView == null) return;
        if (!shouldAnimate(textView.getContext())) {
            textView.setText(text);
            textView.setAlpha(1f);
            return;
        }
        textView.animate()
                .alpha(0f)
                .setDuration(outDuration)
                .withEndAction(() -> {
                    textView.setText(text);
                    textView.animate()
                            .alpha(1f)
                            .setDuration(inDuration)
                            .start();
                })
                .start();
    }

    public static void slideUpAndFadeIn(View view, long duration) {
        if (view == null) return;
        float translation = dpToPx(view.getContext(), 24);
        view.setAlpha(0f);
        view.setTranslationY(translation);
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void pressFeedback(View view, float scaleTo, long duration) {
        if (view == null || !shouldAnimate(view.getContext())) return;
        view.animate()
                .scaleX(scaleTo)
                .scaleY(scaleTo)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
