package fi.radioplus.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Dark diagonally etched navigation rail from the reference head unit.
 */
public final class SkodaNavBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader baseShader;

    public SkodaNavBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            baseShader = null;
            return;
        }
        baseShader = new LinearGradient(
                0,
                0,
                0,
                height,
                new int[]{
                        Color.rgb(27, 38, 61),
                        Color.rgb(7, 11, 22),
                        Color.rgb(2, 4, 9)
                },
                new float[]{0f, 0.34f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || baseShader == null) {
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(baseShader);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        float density = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(48, 132, 151, 191));
        float hatchHeight = 42f * density;
        for (float x = -hatchHeight; x < width + hatchHeight; x += 9f * density) {
            canvas.drawLine(x, hatchHeight, x + hatchHeight, 0, paint);
        }

        paint.setColor(Color.argb(210, 170, 183, 211));
        paint.setStrokeWidth(1.1f * density);
        canvas.drawLine(0, density, width, density, paint);
    }
}
