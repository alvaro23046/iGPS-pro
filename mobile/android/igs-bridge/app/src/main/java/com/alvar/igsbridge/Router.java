package com.alvar.igsbridge;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Destino desde texto/enlace (Google Maps, Waze) y cálculo de ruta en bici con OSRM (OpenStreetMap). */
public final class Router {
    public static final class Route {
        public final List<double[]> track = new ArrayList<>();
        public final List<IgsProtocol.Nav> navs = new ArrayList<>();
        public double distanceM;
        public double durationS;
    }

    private static final String OSRM = "https://routing.openstreetmap.de/routed-bike/route/v1/bike/";
    private static final Pattern P_3D4D = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)");
    private static final Pattern P_AT = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern P_PAIR = Pattern.compile("(-?\\d{1,2}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})");
    private static final Pattern P_URL = Pattern.compile("https?://\\S+");
    private static final Pattern P_Q = Pattern.compile("[?&](?:q|daddr|destination|query)=([^&]+)");

    private Router() {}

    /** Devuelve {lat,lng} del destino a partir de lo compartido por Google Maps/Waze o de un texto libre. */
    public static double[] resolveDestination(Context ctx, String shared) throws Exception {
        String text = URLDecoder.decode(shared, "UTF-8");
        double[] c = coords(text);
        if (c != null) return c;
        Matcher u = P_URL.matcher(shared);
        if (u.find()) {
            String url = u.group();
            for (int i = 0; i < 6 && url != null; i++) {   // seguir redirecciones (maps.app.goo.gl, waze.com/ul)
                String dec = URLDecoder.decode(url, "UTF-8");
                c = coords(dec);
                if (c != null) return c;
                Matcher q = P_Q.matcher(dec);
                if (q.find()) { c = geocode(ctx, q.group(1).replace('+', ' ')); if (c != null) return c; }
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) iGSBridge");
                h.setConnectTimeout(8000); h.setReadTimeout(8000);
                int code = h.getResponseCode();
                String loc = h.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) { url = new URL(new URL(url), loc).toString(); continue; }
                String body = read(h.getInputStream(), 300_000);
                c = coords(body.replace("%2C", ","));
                if (c != null) return c;
                url = null;
            }
            text = text.replace(u.group(), " ").trim();
        }
        if (!text.isEmpty()) return geocode(ctx, text.split("\n")[0]);
        return null;
    }

    static double[] coords(String s) {
        for (Pattern p : new Pattern[]{P_3D4D, P_AT, P_PAIR}) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                if (Math.abs(la) <= 90 && Math.abs(lo) <= 180) return new double[]{la, lo};
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    static double[] geocode(Context ctx, String q) throws Exception {
        List<Address> a = new Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 1);
        if (a == null || a.isEmpty()) return null;
        return new double[]{a.get(0).getLatitude(), a.get(0).getLongitude()};
    }

    static String read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0 && o.size() < max) o.write(b, 0, n);
        in.close();
        return o.toString(StandardCharsets.UTF_8.name());
    }

    public static Route route(double[] from, double[] to) throws Exception {
        String url = String.format(Locale.US, "%s%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
                OSRM, from[1], from[0], to[1], to[0]);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestProperty("User-Agent", "iGSBridge/0.1 (personal)");
        h.setConnectTimeout(10000); h.setReadTimeout(20000);
        if (h.getResponseCode() != 200) throw new Exception("OSRM HTTP " + h.getResponseCode());
        JSONObject j = new JSONObject(read(h.getInputStream(), 20_000_000));
        if (!"Ok".equals(j.optString("code"))) throw new Exception("OSRM: " + j.optString("message", j.optString("code")));
        JSONObject r = j.getJSONArray("routes").getJSONObject(0);
        Route out = new Route();
        out.distanceM = r.getDouble("distance");
        out.durationS = r.getDouble("duration");
        JSONArray coords = r.getJSONObject("geometry").getJSONArray("coordinates");
        for (int i = 0; i < coords.length(); i++) {
            JSONArray p = coords.getJSONArray(i);
            out.track.add(new double[]{p.getDouble(1), p.getDouble(0), 0});
        }
        JSONArray legs = r.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).getJSONArray("steps");
            for (int s = 0; s < steps.length(); s++) {
                JSONObject st = steps.getJSONObject(s);
                JSONObject m = st.getJSONObject("maneuver");
                String type = m.optString("type"), mod = m.optString("modifier", "");
                if (type.equals("depart")) continue;
                JSONArray loc = m.getJSONArray("location");
                String street = st.optString("name", "");
                int t = navType(type, mod);
                String info = describe(type, mod, street, m.optInt("exit", 0));
                out.navs.add(new IgsProtocol.Nav(loc.getDouble(1), loc.getDouble(0), t, info));
            }
        }
        return out;
    }

    /** OSRM -> Nav Type del iGS (mismos códigos que la app oficial asigna a las maniobras de Google). */
    static int navType(String type, String mod) {
        if (type.startsWith("roundabout") || type.equals("rotary")) return mod.contains("left") ? 7 : 8;
        if (mod.equals("uturn")) return 5;
        if (mod.contains("left")) return 1;
        if (mod.contains("right")) return 2;
        return 0;
    }

    static String describe(String type, String mod, String street, int exit) {
        String en = street.isEmpty() ? "" : " por " + street;
        if (type.equals("arrive")) return "Llegada al destino";
        if (type.startsWith("roundabout") || type.equals("rotary"))
            return "Rotonda" + (exit > 0 ? ", salida " + exit : "") + en;
        switch (mod) {
            case "uturn": return "Media vuelta" + en;
            case "sharp left": return "Giro cerrado a la izquierda" + en;
            case "left": return "Gira a la izquierda" + en;
            case "slight left": return "Ligeramente a la izquierda" + en;
            case "sharp right": return "Giro cerrado a la derecha" + en;
            case "right": return "Gira a la derecha" + en;
            case "slight right": return "Ligeramente a la derecha" + en;
            default: return "Sigue recto" + en;
        }
    }

    /** Distancia mínima (m) de un punto a la polilínea de la ruta. */
    public static double distanceToTrack(double lat, double lng, List<double[]> track) {
        double best = Double.MAX_VALUE;
        double kx = 111320 * Math.cos(Math.toRadians(lat)), ky = 110540;
        for (int i = 1; i < track.size(); i++) {
            double ax = (track.get(i - 1)[1] - lng) * kx, ay = (track.get(i - 1)[0] - lat) * ky;
            double bx = (track.get(i)[1] - lng) * kx, by = (track.get(i)[0] - lat) * ky;
            double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            double px = ax + t * dx, py = ay + t * dy;
            best = Math.min(best, Math.sqrt(px * px + py * py));
        }
        return best;
    }
}
