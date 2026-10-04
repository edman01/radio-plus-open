package fi.radioplus.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * Continuous restrained glass texture behind the complete radio screen.
 */
public final class SkodaRootLayout extends LinearLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader baseShader;
    private Shader glowShader;

    public SkodaRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            baseShader = null;
            glowShader = null;
            return;
        }
        baseShader = new LinearGradient(
                0,
                0,
                0,
                height,
                new int[]{
                        Color.rgb(8, 14, 27),
                        Color.rgb(2, 5, 12),
                        Color.rgb(1, 2, 7)
                },
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP
        );
        glowShader = new RadialGradient(
                width * 0.5f,
                height * 0.46f,
                width * 0.66f,
                new int[]{
                        Color.argb(46, 44, 63, 101),
                        Color.argb(18, 17, 26, 49),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || baseShader == null || glowShader == null) {
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(baseShader);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(glowShader);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(18, 134, 158, 205));
        float startY = height * 0.28f;
        float endY = height * 0.82f;
        float run = height * 0.48f;
        for (float x = -run; x < width + run; x += 58f * density) {
            canvas.drawLine(x, startY, x + run, endY, paint);
        }
    }
}
