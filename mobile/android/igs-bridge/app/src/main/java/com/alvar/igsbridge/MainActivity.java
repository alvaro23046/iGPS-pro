package com.alvar.igsbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements LocationListener {
    static final int ROUTE_ID = 900001;          // id fijo: cada envío reemplaza la ruta de iGS Bridge
    static final double OFF_ROUTE_M = 60;        // desvío para recalcular
    static final long REROUTE_COOLDOWN_MS = 45_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private IgsLink link;
    private TextView status, logView;
    private EditText dest;
    private CheckBox auto;
    private Location last;
    private double[] destination;
    private Router.Route current;
    private int offCount;
    private long lastReroute;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        link = new IgsLink(this, this::log);
        buildUi();
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        startGps();
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleShare(i); }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startGps(); }

    private void handleShare(Intent i) {
        if (i != null && i.getBooleanExtra("replay", false)) { replay(); return; }
        if (i != null && i.getBooleanExtra("exp", false)) { experiment(); return; }
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) { dest.setText(t.trim()); log("Destino recibido por compartir."); planAndSend(); }
        }
    }

    // ---------- UI (HUD futurista) ----------
    static final int BG = Color.rgb(5, 8, 18), CYAN = Color.rgb(0, 229, 255), MAGENTA = Color.rgb(255, 46, 136),
            LIME = Color.rgb(57, 255, 160), MUTED = Color.rgb(120, 140, 180), CARD = Color.argb(150, 18, 26, 52);
    private RouteView map;
    private TextView tDist, tNavs, tSize, tLink, connDot;
    private android.widget.ProgressBar progress;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable glass(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(radius)); g.setStroke(dp(1), Color.argb(70, 0, 229, 255));
        return g;
    }

    private Button button(String text, int c1, int c2, boolean filled) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(filled ? Color.rgb(4, 8, 18) : c1);
        btn.setTextSize(15);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setLetterSpacing(0.04f);
        GradientDrawable g = filled
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c1, c2})
                : new GradientDrawable();
        if (!filled) { g.setColor(Color.argb(30, Color.red(c1), Color.green(c1), Color.blue(c1))); g.setStroke(dp(1), c1); }
        g.setCornerRadius(dp(16));
        btn.setBackground(g);
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(filled ? 58 : 50));
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private TextView label(String t, int size, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(size); v.setTextColor(color);
        if (mono) { v.setTypeface(Typeface.MONOSPACE); v.setLetterSpacing(0.12f); }
        return v;
    }

    private TextView tile(LinearLayout row, String caption) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(glass(14));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView value = label("—", 20, Color.WHITE, false);
        value.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.addView(value);
        t.addView(label(caption, 9, MUTED, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginEnd(dp(4)); lp.setMarginStart(dp(4));
        row.addView(t, lp);
        return value;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(8, 14, 32), BG, Color.rgb(14, 6, 24)}));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(18));
        scroll.addView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        TextView title = label("iGS BRIDGE", 24, Color.WHITE, false);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        title.setShadowLayer(dp(10), 0, 0, CYAN);
        titles.addView(title);
        titles.addView(label("NAVEGACIÓN · iGS520", 9, CYAN, true));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(glass(20));
        pill.setPadding(dp(10), dp(6), dp(12), dp(6));
        connDot = label("●", 12, MUTED, false);
        pill.addView(connDot);
        tLink = label(" OFFLINE", 9, MUTED, true);
        pill.addView(tLink);
        head.addView(pill);
        root.addView(head);

        map = new RouteView(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(230));
        mlp.topMargin = dp(16);
        root.addView(map, mlp);

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(10);
        root.addView(row, rlp);
        tDist = tile(row, "DISTANCIA");
        tNavs = tile(row, "GIROS");
        tSize = tile(row, "PAQUETE");

        progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(60, 0, 229, 255)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(6));
        plp.topMargin = dp(12);
        root.addView(progress, plp);

        status = label("Comparte un lugar desde Google Maps o Waze, o escribe un destino.", 14, Color.rgb(200, 215, 240), false);
        status.setPadding(dp(2), dp(10), dp(2), dp(6));
        root.addView(status);

        dest = new EditText(this);
        dest.setHint("Destino · dirección, lugar, lat,lng o enlace");
        dest.setHintTextColor(MUTED);
        dest.setTextColor(Color.WHITE);
        dest.setTextSize(15);
        dest.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        dest.setMaxLines(3);
        dest.setBackground(glass(16));
        dest.setPadding(dp(16), dp(14), dp(16), dp(14));
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        dest.setText(sp.getString("dest", ""));
        root.addView(dest);

        Button send = button("▶  CALCULAR Y ENVIAR AL iGS", CYAN, MAGENTA, true);
        send.setOnClickListener(v -> planAndSend());
        root.addView(send);
        LinearLayout two = new LinearLayout(this);
        Button reroute = button("⟲  Recalcular", CYAN, CYAN, false);
        reroute.setOnClickListener(v -> reroute("manual"));
        Button use = button("⚑  Iniciar en iGS", LIME, LIME, false);
        use.setOnClickListener(v -> startOnDevice());
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, dp(50), 1);
        h1.setMarginEnd(dp(5)); h1.topMargin = dp(10);
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, dp(50), 1);
        h2.setMarginStart(dp(5)); h2.topMargin = dp(10);
        two.addView(reroute, h1);
        two.addView(use, h2);
        root.addView(two);

        auto = new CheckBox(this);
        auto.setText("Recálculo automático al desviarme " + (int) OFF_ROUTE_M + " m");
        auto.setTextColor(Color.rgb(200, 215, 240));
        auto.setButtonTintList(android.content.res.ColorStateList.valueOf(CYAN));
        auto.setChecked(sp.getBoolean("auto", true));
        auto.setOnCheckedChangeListener((c, on) -> getPreferences(MODE_PRIVATE).edit().putBoolean("auto", on).apply());
        auto.setPadding(0, dp(6), 0, dp(6));
        root.addView(auto);

        root.addView(label("TELEMETRÍA", 9, MUTED, true));
        logView = label("", 11, Color.rgb(120, 230, 190), true);
        logView.setLetterSpacing(0);
        logView.setBackground(glass(12));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(6);
        root.addView(logView, llp);
        setContentView(scroll);
    }

    void setLink(boolean on, String text) {
        ui.post(() -> {
            int c = on ? LIME : MUTED;
            connDot.setTextColor(c); tLink.setTextColor(c); tLink.setText(" " + text);
        });
    }

    void setProgressPct(int pct) { ui.post(() -> progress.setProgress(pct)); }

    void showRoute(Router.Route r, int bytes) {
        ui.post(() -> {
            map.setRoute(r.track, r.navs);
            tDist.setText(String.format(Locale.US, "%.1f km", r.distanceM / 1000));
            tNavs.setText(String.valueOf(r.navs.size()));
            if (bytes > 0) tSize.setText(String.format(Locale.US, "%.1f KB", bytes / 1024.0));
        });
    }

    void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        ui.post(() -> {
            String all = logView.getText() + line + "\n";
            String[] lines = all.split("\n");
            if (lines.length > 40) all = String.join("\n", java.util.Arrays.copyOfRange(lines, lines.length - 40, lines.length)) + "\n";
            logView.setText(all);
        });
    }

    void bumpProgress(String m) {
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("Trozo (\\d+)/(\\d+)").matcher(m);
        if (x.find()) setProgressPct(100 * Integer.parseInt(x.group(1)) / Integer.parseInt(x.group(2)));
    }

    /** Depuración: aísla qué campo provoca el rechazo (estado 1) usando el .cnx oficial de files/route.cnx. */
    void experiment() {
        run(() -> {
            byte[] cnx = java.nio.file.Files.readAllBytes(new java.io.File(getExternalFilesDir(null), "route.cnx").toPath());
            link.connect(pickDevice());
            setLink(true, "iGS520");
            link.handshake();
            String off = " Actividad de paseo-20230719";
            Object[][] cases = {
                    {"id422690+nombreOficial", 422690, off, 5239200L},
                    {"id422691+nombreBridge", 422691, "iGS Bridge", 5239200L},
                    {"id900001+nombreOficial", 900001, off, 5239200L},
                    {"id422692+dist0", 422692, off, 0L},
            };
            for (Object[] c : cases) {
                java.util.List<IgsProtocol.Frame> fr = IgsProtocol.routeFrames((Integer) c[1], (String) c[2], cnx, (Long) c[3]);
                byte[] r = link.sendRaw("6e", fr.get(0).payload, fr.get(0).header, 15000);
                String st = r == null ? "null" : String.valueOf(r[7]);
                log("EXP " + c[0] + " trozo1 estado=" + st);
                if (r != null && r[7] == 0) {
                    for (int i = 1; i < fr.size(); i++) link.sendRaw("6e", fr.get(i).payload, fr.get(i).header, 15000);
                    log("EXP " + c[0] + " completo");
                }
                Thread.sleep(500);
            }
            setStatus("Experimento terminado");
        });
    }

    /** Depuración: reenvía tramas capturadas ("svc hdrHex payloadHex" por línea) desde files/replay.txt. */
    void replay() {
        run(() -> {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "replay.txt");
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
            link.connect(pickDevice());
            setLink(true, "iGS520");
            link.handshake();
            int i = 0;
            for (String l : lines) {
                if (l.isBlank()) continue;
                String[] p = l.trim().split(" ");
                byte[] r = link.sendRaw(p[0], IgsLink.unhex(p[2]), IgsLink.unhex(p[1]), 15000);
                log("REPLAY " + (++i) + " -> " + (r == null ? "sin respuesta" : IgsProtocol.hex(r).substring(0, 22)));
            }
            setStatus("Replay terminado");
        });
    }

    void setStatus(String s) { ui.post(() -> status.setText(s)); }

    // ---------- GPS ----------
    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 5, this);
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 20, this); } catch (Exception ignored) {}
    }

    @Override public void onLocationChanged(Location l) {
        last = l;
        if (map != null) ui.post(() -> map.setMe(l.getLatitude(), l.getLongitude()));
        if (current == null || !auto.isChecked() || busy || l.getAccuracy() > 40) return;
        double d = Router.distanceToTrack(l.getLatitude(), l.getLongitude(), current.track);
        offCount = d > OFF_ROUTE_M ? offCount + 1 : 0;
        if (offCount >= 3 && System.currentTimeMillis() - lastReroute > REROUTE_COOLDOWN_MS) {
            log(String.format(Locale.US, "Desvío de %.0f m: recalculando…", d));
            reroute("auto");
        }
    }

    // ---------- flujo ----------
    private BluetoothDevice pickDevice() throws Exception {
        List<BluetoothDevice> c = IgsLink.bondedCandidates(this);
        if (c.isEmpty()) throw new Exception("No hay ningún iGS emparejado con este teléfono.");
        return c.get(0);
    }

    private void planAndSend() {
        String q = dest.getText().toString().trim();
        if (q.isEmpty()) { setStatus("Escribe o comparte un destino."); return; }
        getPreferences(MODE_PRIVATE).edit().putString("dest", q).apply();
        run(() -> {
            setStatus("Buscando destino…");
            destination = Router.resolveDestination(this, q);
            if (destination == null) throw new Exception("No encontré ese destino.");
            log(String.format(Locale.US, "Destino %.5f, %.5f", destination[0], destination[1]));
            routeAndSend();
        });
    }

    private void reroute(String why) {
        if (destination == null) { setStatus("Primero envía una ruta."); return; }
        lastReroute = System.currentTimeMillis();
        offCount = 0;
        run(this::routeAndSend);
    }

    private void routeAndSend() throws Exception {
        for (int i = 0; i < 20 && last == null; i++) { setStatus("Esperando ubicación…"); Thread.sleep(1000); }
        if (last == null) throw new Exception("Aún no tengo tu ubicación GPS.");
        setStatus("Calculando ruta en bici…");
        Router.Route r = Router.route(new double[]{last.getLatitude(), last.getLongitude()}, destination);
        showRoute(r, 0);
        log(String.format(Locale.US, "Ruta: %.1f km, %d puntos, %d indicaciones", r.distanceM / 1000, r.track.size(), r.navs.size()));
        String name = "iGS Bridge " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        setStatus("Conectando con el iGS…");
        link.connect(pickDevice());
        setLink(true, "iGS520");
        link.handshake();
        long distCm = Math.round(r.distanceM * 100);
        // Variantes en orden de preferencia; se queda con la primera que el iGS acepte.
        Object[][] variants = {
                {"encode2+navs", IgsProtocol.buildCnxEncoded(ROUTE_ID, r.track, r.navs, r.distanceM, 0, 0, true), 4096},
                {"encode2", IgsProtocol.buildCnxEncoded(ROUTE_ID, r.track, r.navs, r.distanceM, 0, 0, false), 4096},
                {"encode2/2048", IgsProtocol.buildCnxEncoded(ROUTE_ID, r.track, r.navs, r.distanceM, 0, 0, false), 2048},
                {"plano", IgsProtocol.buildCnx(ROUTE_ID, r.track, r.navs, r.distanceM), 4096},
        };
        Exception lastErr = null;
        boolean ok = false;
        for (Object[] v : variants) {
            byte[] cnx = (byte[]) v[1];
            setStatus("Enviando ruta [" + v[0] + "] (" + cnx.length / 1024 + " KB)…");
            try {
                showRoute(r, cnx.length);
                setProgressPct(0);
                link.sendRoute(ROUTE_ID, name, cnx, distCm, (Integer) v[2], m -> { log(m); bumpProgress(m); });
                setProgressPct(100);
                log("ACEPTADA con variante " + v[0]);
                getPreferences(MODE_PRIVATE).edit().putString("variant", (String) v[0]).apply();
                ok = true;
                break;
            } catch (Exception e) {
                lastErr = e;
                log("Variante " + v[0] + " rechazada: " + e.getMessage());
                Thread.sleep(800);
            }
        }
        if (!ok) throw lastErr;
        current = r;
        setStatus("✔ Ruta enviada: \"" + name + "\". En el iGS: Navegación → Rutas.");
        log("Ruta enviada.");
    }

    private void startOnDevice() {
        run(() -> {
            link.connect(pickDevice());
            byte[] ack = link.send("6e", IgsProtocol.useFrame(ROUTE_ID), 10000);
            log("FILE_USE respuesta: " + IgsProtocol.hex(ack));
            setStatus("Orden de iniciar navegación enviada.");
        });
    }

    interface Job { void run() throws Exception; }

    private void run(Job j) {
        if (busy) { log("Ocupado, espera…"); return; }
        busy = true;
        worker.execute(() -> {
            try { j.run(); }
            catch (Exception e) { log("ERROR: " + e.getMessage()); setStatus("⚠ " + e.getMessage()); link.close(); setLink(false, "OFFLINE"); }
            finally { busy = false; }
        });
    }

    @Override protected void onDestroy() {
        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this);
        link.close();
        super.onDestroy();
    }
}
