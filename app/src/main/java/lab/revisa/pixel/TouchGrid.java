package lab.revisa.pixel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Cuadrícula a pantalla completa: cada cuadro se pinta al tocarlo; al completarla avisa. */
class TouchGrid extends View {
    private final int cols, rows;
    private final boolean[] hit;
    private int count;
    private final Runnable onComplete;
    private Runnable onDone;
    private final Paint on = new Paint(), off = new Paint(), line = new Paint(), text = new Paint();

    TouchGrid(Context c, int cols, int rows, Runnable onComplete) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        this.hit = new boolean[cols * rows];
        this.onComplete = onComplete;
        on.setColor(0xFF2E9D5E);
        off.setColor(0xFF0D1114);
        line.setColor(0xFF27313A);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f);
        text.setColor(Color.WHITE);
        text.setTextSize(42f);
        text.setTextAlign(Paint.Align.CENTER);
    }

    void setOnDone(Runnable r) { onDone = r; }

    /** Cierra sin completar (botón atrás). */
    void cancel() { if (onDone != null) onDone.run(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() / (float) cols, h = getHeight() / (float) rows;
        for (int r = 0; r < rows; r++)
            for (int k = 0; k < cols; k++) {
                c.drawRect(k * w, r * h, (k + 1) * w, (r + 1) * h, hit[r * cols + k] ? on : off);
                c.drawRect(k * w, r * h, (k + 1) * w, (r + 1) * h, line);
            }
        c.drawText(count + " de " + hit.length + "  ·  atrás para salir", getWidth() / 2f, getHeight() / 2f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        for (int p = 0; p < e.getPointerCount(); p++) {
            for (int hIdx = 0; hIdx < e.getHistorySize(); hIdx++) mark(e.getHistoricalX(p, hIdx), e.getHistoricalY(p, hIdx));
            mark(e.getX(p), e.getY(p));
        }
        return true;
    }

    private void mark(float x, float y) {
        int k = (int) (x / (getWidth() / (float) cols)), r = (int) (y / (getHeight() / (float) rows));
        if (k < 0 || k >= cols || r < 0 || r >= rows) return;
        int i = r * cols + k;
        if (hit[i]) return;
        hit[i] = true;
        count++;
        invalidate();
        if (count == hit.length) {
            onComplete.run();
            postDelayed(() -> { if (onDone != null) onDone.run(); }, 300);
        }
    }
}
