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
 * Compact infotainment-style frequency track. It deliberately avoids large
 * decorative shapes so the station name and preset tiles remain dominant.
 */
public final class TunerBackdropView extends View {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float frequencyRatio = 0.45f;
    private boolean scanning;
    private float phase;
    private Shader backgroundShader;

    private int accent;
    private int panel;
    private int lineColor;

    public TunerBackdropView(Context context) {
        super(context);
        init();
    }

    public TunerBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TunerBackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        accent = getContext().getColor(R.color.accent);
        panel = getContext().getColor(R.color.panel);
        lineColor = getContext().getColor(R.color.text_muted);
        linePaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    public void setFrequency(int band, int frequency) {
        frequencyRatio = band < 3
                ? clamp((frequency - 87500f) / (108000f - 87500f))
                : clamp((frequency - 522f) / (1620f - 522f));
        invalidate();
    }

    public void setScanning(boolean scanning) {
        if (this.scanning == scanning) {
            return;
        }
        this.scanning = scanning;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        backgroundShader = new LinearGradient(
                0f,
                0f,
                width,
                0f,
                panel,
                blend(panel, accent, 0.10f),
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float startX = 18f;
        float endX = width - 18f;
        float trackY = height * 0.52f;

        fillPaint.setShader(backgroundShader);
        canvas.drawRect(0f, 0f, width, height, fillPaint);
        fillPaint.setShader(null);

        linePaint.setStrokeWidth(1.5f);
        linePaint.setColor(withAlpha(lineColor, 125));
        canvas.drawLine(startX, trackY, endX, trackY, linePaint);

        for (int i = 0; i <= 20; i++) {
            float x = startX + ((endX - startX) * i / 20f);
            float tick = i % 5 == 0 ? height * 0.32f : height * 0.19f;
            linePaint.setStrokeWidth(i % 5 == 0 ? 1.8f : 1f);
            linePaint.setColor(withAlpha(lineColor, i % 5 == 0 ? 150 : 78));
            canvas.drawLine(x, trackY - tick, x, trackY + tick, linePaint);
        }

        float markerX = startX + ((endX - startX) * frequencyRatio);
        float pulse = scanning ? (float) Math.sin(phase) * 2f : 0f;
        linePaint.setColor(accent);
        linePaint.setStrokeWidth(3.5f);
        canvas.drawLine(
                markerX,
                Math.max(1f, trackY - (height * 0.38f) - pulse),
                markerX,
                Math.min(height - 1f, trackY + (height * 0.38f) + pulse),
                linePaint
        );

        if (scanning) {
            phase += 0.32f;
            postInvalidateDelayed(32L);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int blend(int first, int second, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                Math.round((Color.red(first) * inverse) + (Color.red(second) * amount)),
                Math.round((Color.green(first) * inverse) + (Color.green(second) * amount)),
                Math.round((Color.blue(first) * inverse) + (Color.blue(second) * amount))
        );
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
