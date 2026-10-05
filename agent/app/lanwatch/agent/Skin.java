package app.lanwatch.agent;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * The phone's look, matched to the desktop's.
 *
 * Android 12 and newer hand out the wallpaper palette; below that there is no dynamic colour API,
 * so the same hand-tuned tonal palette the desktop uses is applied here — dark by default, light
 * in light mode. Both sides therefore read as one program on one screen.
 *
 * The minimum is Android 5.1, so every API-gated call is guarded and falls back rather than
 * crashing on an old phone.
 */
public final class Skin {
    public static int accent;
    public static int accentText;
    public static int accentSoft;
    public static int background;
    public static int surface;
    public static int surfaceHigh;
    public static int surfaceHover;
    public static int outline;
    public static int text;
    public static int textDim;
    public static int textFaint;

    public static float radius;
    public static float radiusSmall;

    private Skin() { }

    public static void apply(Activity activity) {
        boolean dark = isDark(activity);
        boolean dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

        if (dynamic) {
            accent = sys(activity, android.R.color.system_accent1_500, 0xFF9DD3FF);
            accentText = sys(activity, android.R.color.system_accent1_100, 0xFF06243D);
            accentSoft = withAlpha(accent, 38);
            background = sys(activity, android.R.color.system_neutral1_1000, 0xFF101014);
            surface = sys(activity, android.R.color.system_neutral1_900, 0xFF1A1A20);
            surfaceHigh = sys(activity, android.R.color.system_neutral1_800, 0xFF26262E);
            surfaceHover = sys(activity, android.R.color.system_neutral1_700, 0xFF32323C);
            outline = sys(activity, android.R.color.system_neutral1_700, 0xFF3A3A44);
            text = sys(activity, android.R.color.system_neutral1_50, 0xFFF2F2F5);
            textDim = sys(activity, android.R.color.system_neutral1_200, 0xFFB6B6C2);
            textFaint = sys(activity, android.R.color.system_neutral1_500, 0xFF82828F);
        } else if (dark) {
            accent = 0xFF9DD3FF; accentText = 0xFF06243D; accentSoft = 0x269DD3FF;
            background = 0xFF101014; surface = 0xFF1A1A20; surfaceHigh = 0xFF26262E;
            surfaceHover = 0xFF32323C; outline = 0xFF3A3A44;
            text = 0xFFF2F2F5; textDim = 0xFFB6B6C2; textFaint = 0xFF82828F;
        } else {
            accent = 0xFF00639B; accentText = 0xFFFFFFFF; accentSoft = 0x1F00639B;
            background = 0xFFF6F7FB; surface = 0xFFFFFFFF; surfaceHigh = 0xFFEDEFF4;
            surfaceHover = 0xFFE2E5EC; outline = 0xFFC9CDD8;
            text = 0xFF15171C; textDim = 0xFF4A4E59; textFaint = 0xFF7A7F8B;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        radius = 18f * density;
        radiusSmall = 12f * density;
    }

    private static boolean isDark(Activity activity) {
        int mode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode != android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    /**
     * A colour from the system palette.
     *
     * getColorStateList with a theme only exists from API 23, and these resources exist from API
     * 31, so both are checked before being touched and a sensible constant is used otherwise.
     */
    private static int sys(Activity activity, int id, int fallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback;
        try {
            ColorStateList list = activity.getResources().getColorStateList(id, activity.getTheme());
            if (list != null && list.getDefaultColor() != 0) return list.getDefaultColor();
        } catch (Exception e) {
            // An unexpected OEM build without the resource; the fallback is close enough.
        }
        return fallback;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable round(int color, float cornerRadius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(cornerRadius);
        return drawable;
    }

    public static GradientDrawable outlined(int fill, int stroke, float cornerRadius) {
        GradientDrawable drawable = round(fill, cornerRadius);
        drawable.setStroke(Math.max(1, (int) (cornerRadius / 9f)), stroke);
        return drawable;
    }

    /**
     * Makes a view tappable with a native ripple, leaving the caller's background alone.
     *
     * The ripple goes on the foreground, never the background: a RippleDrawable used as a
     * background is transparent and would erase the fill the button was given. Android 5.1 is API
     * 22, so foreground exists across the whole supported range.
     */
    public static void ripple(View view, View.OnClickListener click) {
        view.setClickable(true);
        view.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setForeground(rippleDrawable());
        }
        view.setOnClickListener(click);
    }

    public static Drawable rippleDrawable() {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(text, 28)), null, null);
    }

    public static TextView text(android.content.Context context, String value, float sizeSp,
            int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** The colour a battery percentage is shown in, matching the desktop's thresholds. */
    public static int batteryColor(int percent) {
        if (percent <= 15) return 0xFFFF8A80;
        if (percent <= 40) return 0xFFFFC46B;
        return 0xFF7AD48A;
    }
}