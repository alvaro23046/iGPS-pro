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
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleShare(i); }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startGps(); }

    private void handleShare(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) { dest.setText(t.trim()); log("Destino recibido por compartir."); planAndSend(); }
        }
    }

    // ---------- UI ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Button button(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(14));
        btn.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(14, 18, 32));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = new TextView(this);
        title.setText("  iGS Bridge");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        root.addView(head);

        status = new TextView(this);
        status.setTextColor(Color.rgb(160, 175, 210));
        status.setPadding(0, dp(8), 0, dp(8));
        status.setText("Comparte un lugar desde Google Maps o Waze, o escribe un destino.");
        root.addView(status);

        dest = new EditText(this);
        dest.setHint("Destino: dirección, lugar, lat,lng o enlace");
        dest.setHintTextColor(Color.rgb(110, 120, 150));
        dest.setTextColor(Color.WHITE);
        dest.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        dest.setMaxLines(3);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(Color.rgb(28, 34, 56)); eg.setCornerRadius(dp(12));
        dest.setBackground(eg);
        dest.setPadding(dp(14), dp(12), dp(14), dp(12));
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        dest.setText(sp.getString("dest", ""));
        root.addView(dest);

        Button send = button("Calcular ruta y enviar al iGS", Color.rgb(230, 57, 70));
        send.setOnClickListener(v -> planAndSend());
        root.addView(send);
        Button reroute = button("Recalcular desde mi posición", Color.rgb(46, 64, 110));
        reroute.setOnClickListener(v -> reroute("manual"));
        root.addView(reroute);
        Button use = button("Iniciar navegación en el iGS", Color.rgb(46, 110, 80));
        use.setOnClickListener(v -> startOnDevice());
        root.addView(use);

        auto = new CheckBox(this);
        auto.setText("Recalcular automáticamente si me desvío (" + (int) OFF_ROUTE_M + " m)");
        auto.setTextColor(Color.WHITE);
        auto.setChecked(sp.getBoolean("auto", true));
        auto.setOnCheckedChangeListener((c, on) -> getPreferences(MODE_PRIVATE).edit().putBoolean("auto", on).apply());
        root.addView(auto);

        ScrollView sv = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(140, 200, 160));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        sv.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = dp(10);
        root.addView(sv, lp);
        setContentView(root);
    }

    void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s + "\n";
        ui.post(() -> {
            logView.append(line);
            ((ScrollView) logView.getParent()).fullScroll(View.FOCUS_DOWN);
        });
    }

    void setStatus(String s) { ui.post(() -> status.setText(s)); }

    // ---------- GPS ----------
    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 5, this);
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 20, this); } catch (Exception ignored) {}
    }

    @Override public void onLocationChanged(Location l) {
        last = l;
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
        if (last == null) throw new Exception("Aún no tengo tu ubicación GPS.");
        setStatus("Calculando ruta en bici…");
        Router.Route r = Router.route(new double[]{last.getLatitude(), last.getLongitude()}, destination);
        log(String.format(Locale.US, "Ruta: %.1f km, %d puntos, %d indicaciones", r.distanceM / 1000, r.track.size(), r.navs.size()));
        byte[] cnx = IgsProtocol.buildCnx(ROUTE_ID, r.track, r.navs, r.distanceM);
        String name = "iGS Bridge " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        setStatus("Conectando con el iGS…");
        link.connect(pickDevice());
        setStatus("Enviando ruta (" + cnx.length / 1024 + " KB)…");
        link.sendRoute(ROUTE_ID, name, cnx, Math.round(r.distanceM * 100), this::log);
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
            catch (Exception e) { log("ERROR: " + e.getMessage()); setStatus("⚠ " + e.getMessage()); link.close(); }
            finally { busy = false; }
        });
    }

    @Override protected void onDestroy() {
        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this);
        link.close();
        super.onDestroy();
    }
}
