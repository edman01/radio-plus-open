package fi.radioplus.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the restrained glass, glow and notched clock rail used by the
 * reference Škoda radio screen.
 */
public final class SkodaHeaderBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader baseShader;
    private Shader glowShader;

    public SkodaHeaderBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
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
                Color.rgb(10, 15, 28),
                Color.rgb(1, 3, 8),
                Shader.TileMode.CLAMP
        );
        glowShader = new RadialGradient(
                width * 0.5f,
                height * 0.68f,
                width * 0.56f,
                new int[]{
                        Color.argb(62, 45, 64, 102),
                        Color.argb(22, 18, 27, 48),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.48f, 1f},
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
        float railY = 1.5f * density;
        float center = width / 2f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f * density);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(Color.argb(46, 118, 145, 198));
        paint.setShadowLayer(7f * density, 0, 0, Color.argb(90, 103, 127, 185));
        canvas.drawLine(0f, railY, width, railY, paint);

        paint.clearShadowLayer();
        paint.setStrokeWidth(1.35f * density);
        paint.setColor(Color.argb(220, 205, 210, 230));
        canvas.drawLine(0f, railY, width, railY, paint);

        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(18, 159, 180, 220));
        for (int index = -8; index <= 8; index++) {
            float startX = center + index * 54f * density;
            canvas.drawLine(
                    startX,
                    height - 30f * density,
                    startX + 44f * density,
                    height,
                    paint
            );
        }
    }
}
