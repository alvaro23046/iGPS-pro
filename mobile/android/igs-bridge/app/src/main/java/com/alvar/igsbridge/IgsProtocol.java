package com.alvar.igsbridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Protocolo de rutas iGS520. Port de tools/protocol/igs_proto.py, validado byte a byte contra EXP-003.
 * Cabecera 20 B: [kind, service, ff, ff, op, ff, ff, lenHi, lenLo, crcPayload, flag, ff x8, crcHeader].
 */
public final class IgsProtocol {
    public static final int SERVICE_ROUTE_PLAN = 7;
    public static final int OP_FILE_SEND = 4;
    public static final int OP_FILE_USE = 5;
    public static final int FILE_TYPE_CNX = 1;
    public static final int CHUNK = 4096;

    private IgsProtocol() {}

    public static int crc8Maxim(byte[] data) {
        int c = 0;
        for (byte b : data) {
            c ^= b & 0xFF;
            for (int i = 0; i < 8; i++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x8C : c >>> 1;
        }
        return c;
    }

    public static byte[] header(int kind, int service, int op, byte[] payload, int flag) {
        byte[] h = new byte[20];
        h[0] = (byte) kind; h[1] = (byte) service; h[2] = (byte) 0xFF; h[3] = (byte) 0xFF;
        h[4] = (byte) op; h[5] = (byte) 0xFF; h[6] = (byte) 0xFF;
        h[7] = (byte) (payload.length >> 8); h[8] = (byte) payload.length;
        h[9] = (byte) crc8Maxim(payload); h[10] = (byte) flag;
        for (int i = 11; i < 19; i++) h[i] = (byte) 0xFF;
        byte[] first = new byte[19];
        System.arraycopy(h, 0, first, 0, 19);
        h[19] = (byte) crc8Maxim(first);
        return h;
    }

    /** ACK del teléfono a un mensaje del dispositivo (visto en EXP-001: 02 svc ff ff op ff ff 00 ff.. crc). */
    public static byte[] ack(int service, int op) {
        byte[] h = new byte[20];
        java.util.Arrays.fill(h, (byte) 0xFF);
        h[0] = 2; h[1] = (byte) service; h[4] = (byte) op; h[7] = 0;
        byte[] first = new byte[19];
        System.arraycopy(h, 0, first, 0, 19);
        h[19] = (byte) crc8Maxim(first);
        return h;
    }

    public static boolean headerValid(byte[] h) {
        if (h == null || h.length != 20) return false;
        byte[] first = new byte[19];
        System.arraycopy(h, 0, first, 0, 19);
        return (crc8Maxim(first) & 0xFF) == (h[19] & 0xFF);
    }

    // ---- protobuf mínimo ----
    static void varint(ByteArrayOutputStream o, long n) {
        while (true) {
            int b = (int) (n & 0x7F);
            n >>>= 7;
            if (n != 0) o.write(b | 0x80); else { o.write(b); return; }
        }
    }

    static void fVarint(ByteArrayOutputStream o, int f, long v) { varint(o, (long) f << 3); varint(o, v); }

    static void fBytes(ByteArrayOutputStream o, int f, byte[] v) {
        varint(o, ((long) f << 3) | 2); varint(o, v.length); o.write(v, 0, v.length);
    }

    static byte[] info(int routeId, String name, long distanceCm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        fVarint(o, 1, routeId); fVarint(o, 2, FILE_TYPE_CNX);
        fBytes(o, 3, name.getBytes(StandardCharsets.UTF_8)); fVarint(o, 4, distanceCm);
        return o.toByteArray();
    }

    public static final class Frame {
        public final byte[] header, payload;
        Frame(byte[] h, byte[] p) { header = h; payload = p; }
    }

    /** FILE_SEND troceado como la app oficial: un route_plan_data_msg completo por cada 4096 B de archivo. */
    public static List<Frame> routeFrames(int routeId, String name, byte[] cnx, long distanceCm) {
        return routeFrames(routeId, name, cnx, distanceCm, CHUNK);
    }

    public static List<Frame> routeFrames(int routeId, String name, byte[] cnx, long distanceCm, int chunk) {
        byte[] line = (routeId + ".cnx").getBytes(StandardCharsets.UTF_8);
        byte[] inf = info(routeId, name, distanceCm);
        List<byte[]> msgs = new ArrayList<>();
        for (int off = 0; off < cnx.length; off += chunk) {
            int n = Math.min(chunk, cnx.length - off);
            byte[] part = new byte[n];
            System.arraycopy(cnx, off, part, 0, n);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            fVarint(o, 1, SERVICE_ROUTE_PLAN); fVarint(o, 2, OP_FILE_SEND);
            fBytes(o, 3, line); fBytes(o, 4, part); fBytes(o, 5, inf);
            msgs.add(o.toByteArray());
        }
        List<Frame> out = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            int flag = msgs.size() == 1 ? 1 : (i == msgs.size() - 1 ? 3 : 2);
            out.add(new Frame(header(1, SERVICE_ROUTE_PLAN, OP_FILE_SEND, msgs.get(i), flag), msgs.get(i)));
        }
        return out;
    }

    /** FILE_USE (RoutePlanningDelegate.setRoutePlanFile): selecciona la ruta en el dispositivo. PROBABLE. */
    public static Frame useFrame(int routeId) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        fVarint(o, 1, SERVICE_ROUTE_PLAN); fVarint(o, 2, OP_FILE_USE);
        fBytes(o, 3, (routeId + ".cnx").getBytes(StandardCharsets.UTF_8));
        fBytes(o, 5, info(routeId, String.valueOf(routeId), 0));
        byte[] p = o.toByteArray();
        return new Frame(header(1, SERVICE_ROUTE_PLAN, OP_FILE_USE, p, 1), p);
    }

    // ---- CNX ----
    public static final class Nav {
        public final double lat, lng; public final int type; public final String info;
        public Nav(double lat, double lng, int type, String info) { this.lat = lat; this.lng = lng; this.type = type; this.info = info; }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** CNX sin codificar, mismo esquema que RouteUtil.transformToBBRoute (Tracks "lat,lng,alt;..."). */
    public static byte[] buildCnx(int routeId, List<double[]> track, List<Nav> navs, double distanceM) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < track.size(); i++) {
            double[] p = track.get(i);
            if (i > 0) t.append(';');
            t.append(String.format(Locale.US, "%.7f,%.7f,%d", p[0], p[1], (int) Math.round(p.length > 2 ? p[2] : 0)));
        }
        StringBuilder n = new StringBuilder();
        for (Nav v : navs) {
            n.append(String.format(Locale.US, "<Nav><Lat>%.7f</Lat><Lng>%.7f</Lng><Type>%d</Type><Info>%s</Info></Nav>",
                    v.lat, v.lng, v.type, esc(v.info)));
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Route><Id>" + routeId + "</Id><Distance>" + (int) distanceM
                + "</Distance><Duration>0</Duration><Ascent>0</Ascent><Descent>0</Descent><TracksCount>" + track.size()
                + "</TracksCount><NavsCount>" + navs.size() + "</NavsCount><PointsCount>0</PointsCount><Reduce>0</Reduce><Lang>0</Lang><Tracks>"
                + t + "</Tracks><Navs>" + n + "</Navs><Points></Points></Route>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * CNX con el formato de las rutas de la nube (EXP-003): &lt;Encode&gt;2. Primer punto absoluto "lat,lng,alt_cm";
     * después lat/lng como deltas de SEGUNDO orden en 1e-7 grados y altitud como delta de primer orden en cm.
     */
    public static byte[] buildCnxEncoded(int routeId, List<double[]> track, List<Nav> navs, double distanceM,
                                         int ascent, int descent, boolean withNavs) {
        StringBuilder t = new StringBuilder();
        long pla = 0, plo = 0, pdla = 0, pdlo = 0, palt = 0;
        for (int i = 0; i < track.size(); i++) {
            double[] p = track.get(i);
            long la = Math.round(p[0] * 1e7), lo = Math.round(p[1] * 1e7);
            long alt = Math.round((p.length > 2 ? p[2] : 0) * 100);
            if (i == 0) {
                t.append(String.format(Locale.US, "%.7f,%.7f,%d;", la / 1e7, lo / 1e7, alt));
            } else {
                long dla = la - pla, dlo = lo - plo;
                t.append(dla - pdla).append(',').append(dlo - pdlo).append(',').append(alt - palt).append(';');
                pdla = dla; pdlo = dlo;
            }
            pla = la; plo = lo; palt = alt;
        }
        StringBuilder n = new StringBuilder();
        if (withNavs && !navs.isEmpty()) {
            n.append("<Navs>");
            for (Nav v : navs) {
                n.append(String.format(Locale.US, "<Nav><Lat>%.7f</Lat><Lng>%.7f</Lng><Type>%d</Type><Info>%s</Info></Nav>",
                        v.lat, v.lng, v.type, esc(v.info)));
            }
            n.append("</Navs>");
        } else {
            n.append("<Navs/>");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Route><Id>" + routeId + "</Id><Distance>"
                + String.format(Locale.US, "%.2f", distanceM) + "</Distance><Duration></Duration><Ascent>" + ascent
                + "</Ascent><Descent>" + descent + "</Descent><Encode>2</Encode><Lang>0</Lang>"
                + (withNavs && !navs.isEmpty() ? "<NavsCount>" + navs.size() + "</NavsCount>" : "")
                + "<TracksCount>" + track.size() + "</TracksCount><Tracks>" + t + "</Tracks>" + n + "</Route>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /** Decodifica Tracks Encode=2 (para pruebas). */
    public static List<double[]> decodeTracks(String tracks) {
        List<double[]> out = new ArrayList<>();
        String[] parts = tracks.split(";");
        String[] f = parts[0].split(",");
        long la = Math.round(Double.parseDouble(f[0]) * 1e7), lo = Math.round(Double.parseDouble(f[1]) * 1e7);
        long alt = Long.parseLong(f[2]), vla = 0, vlo = 0;
        out.add(new double[]{la / 1e7, lo / 1e7, alt / 100.0});
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            String[] d = parts[i].split(",");
            vla += Long.parseLong(d[0]); vlo += Long.parseLong(d[1]);
            la += vla; lo += vlo; alt += Long.parseLong(d[2]);
            out.add(new double[]{la / 1e7, lo / 1e7, alt / 100.0});
        }
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }
}
