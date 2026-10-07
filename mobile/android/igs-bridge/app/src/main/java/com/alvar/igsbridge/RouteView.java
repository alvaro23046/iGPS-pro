package com.alvar.igsbridge;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.util.List;

/** Vista previa de la ruta estilo HUD: rejilla, trazo con brillo neón, inicio/fin, posición y giros. */
public class RouteView extends View {
    private List<double[]> track;
    private List<IgsProtocol.Nav> navs;
    private double[] me;
    private float phase;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            frame = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public RouteView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // BlurMaskFilter
        grid.setColor(Color.argb(28, 0, 229, 255)); grid.setStrokeWidth(1);
        glow.setStyle(Paint.Style.STROKE); glow.setStrokeWidth(9 * d); glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND); glow.setMaskFilter(new BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL));
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3 * d); line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        frame.setStyle(Paint.Style.STROKE); frame.setStrokeWidth(1.5f * d);
        text.setColor(Color.argb(150, 0, 229, 255)); text.setTextSize(10 * d);
        text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setLetterSpacing(0.15f);
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(2400); a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(v -> { phase = (float) v.getAnimatedValue(); invalidate(); });
        a.start();
    }

    public void setRoute(List<double[]> t, List<IgsProtocol.Nav> n) { track = t; navs = n; invalidate(); }
    public void setMe(double lat, double lng) { me = new double[]{lat, lng}; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = 18 * d;
        RectF box = new RectF(d, d, w - d, h - d);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(10, 16, 34), Color.rgb(6, 9, 20), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, bg);
        c.save();
        Path clip = new Path(); clip.addRoundRect(box, r, r, Path.Direction.CW); c.clipPath(clip);
        float step = 22 * d;
        for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, grid);
        for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, grid);
        // barrido de radar
        Paint sweep = new Paint();
        float sy = phase * h;
        sweep.setShader(new LinearGradient(0, sy - 40 * d, 0, sy, Color.TRANSPARENT, Color.argb(40, 0, 229, 255), Shader.TileMode.CLAMP));
        c.drawRect(0, sy - 40 * d, w, sy, sweep);

        if (track == null || track.size() < 2) {
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("SIN RUTA · COMPARTE UN DESTINO", w / 2, h / 2, text);
            c.restore();
            drawFrame(c, box, r);
            return;
        }
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (double[] p : track) { minLa = Math.min(minLa, p[0]); maxLa = Math.max(maxLa, p[0]); minLo = Math.min(minLo, p[1]); maxLo = Math.max(maxLo, p[1]); }
        double k = Math.cos(Math.toRadians((minLa + maxLa) / 2));
        double sx = (maxLo - minLo) * k, syy = maxLa - minLa;
        float pad = 26 * d;
        double scale = Math.min((w - 2 * pad) / Math.max(sx, 1e-9), (h - 2 * pad) / Math.max(syy, 1e-9));
        float ox = (float) ((w - sx * scale) / 2), oy = (float) ((h - syy * scale) / 2);
        Path p = new Path();
        for (int i = 0; i < track.size(); i++) {
            double[] q = track.get(i);
            float x = ox + (float) ((q[1] - minLo) * k * scale), y = oy + (float) ((maxLa - q[0]) * scale);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        int cA = Color.rgb(0, 229, 255), cB = Color.rgb(255, 46, 136);
        glow.setShader(new LinearGradient(0, 0, w, h, Color.argb(160, 0, 229, 255), Color.argb(160, 255, 46, 136), Shader.TileMode.CLAMP));
        line.setShader(new LinearGradient(0, 0, w, h, cA, cB, Shader.TileMode.CLAMP));
        c.drawPath(p, glow);
        c.drawPath(p, line);
        if (navs != null) {
            dot.setColor(Color.argb(200, 255, 214, 0));
            for (IgsProtocol.Nav n : navs) {
                float x = ox + (float) ((n.lng - minLo) * k * scale), y = oy + (float) ((maxLa - n.lat) * scale);
                c.drawCircle(x, y, 2.6f * d, dot);
            }
        }
        double[] s = track.get(0), e = track.get(track.size() - 1);
        node(c, ox + (float) ((s[1] - minLo) * k * scale), oy + (float) ((maxLa - s[0]) * scale), cA);
        node(c, ox + (float) ((e[1] - minLo) * k * scale), oy + (float) ((maxLa - e[0]) * scale), cB);
        if (me != null) {
            float x = ox + (float) ((me[1] - minLo) * k * scale), y = oy + (float) ((maxLa - me[0]) * scale);
            dot.setColor(Color.argb((int) (120 * (1 - phase)), 255, 255, 255));
            c.drawCircle(x, y, (6 + 18 * phase) * d, dot);
            dot.setColor(Color.WHITE);
            c.drawCircle(x, y, 5 * d, dot);
        }
        c.restore();
        drawFrame(c, box, r);
    }

    private void node(Canvas c, float x, float y, int color) {
        dot.setColor(color);
        dot.setAlpha(70);
        c.drawCircle(x, y, (9 + 6 * phase) * d, dot);
        dot.setAlpha(255);
        c.drawCircle(x, y, 5 * d, dot);
    }

    private void drawFrame(Canvas c, RectF box, float r) {
        frame.setShader(new LinearGradient(0, 0, box.right, box.bottom, Color.argb(160, 0, 229, 255),
                Color.argb(120, 255, 46, 136), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, frame);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("NAV·HUD", box.left + 14 * d, box.top + 18 * d, text);
    }
}
