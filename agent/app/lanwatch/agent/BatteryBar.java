package app.lanwatch.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The thin level bar under a device row.
 *
 * Drawing it beats nesting two views and resizing one of them: a bar is two rounded rectangles
 * whose width comes from {@link #getWidth()}, which is known exactly when it is drawn and only
 * guessed beforehand.
 */
public final class BatteryBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int percent = -1;

    public BatteryBar(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /** Sets the level; a negative value draws an empty track with no fill. */
    public void setPercent(int percent) {
        if (this.percent == percent) return;
        this.percent = percent;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float height = getHeight();
        float radius = height / 2f;

        paint.setColor(Skin.surfaceHover);
        rect.set(0, 0, getWidth(), height);
        canvas.drawRoundRect(rect, radius, radius, paint);

        if (percent < 0) return;
        // A full or empty bar must still be a pill, so the width is never allowed below its height.
        float filled = Math.max(height, getWidth() * Math.min(100, percent) / 100f);
        paint.setColor(Skin.batteryColor(percent));
        rect.set(0, 0, filled, height);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}