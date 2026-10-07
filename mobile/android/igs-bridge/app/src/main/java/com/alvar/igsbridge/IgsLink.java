package com.alvar.igsbridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Enlace GATT con el iGS520. Todas las llamadas son bloqueantes: usar desde un hilo de fondo. */
@SuppressLint("MissingPermission")
public final class IgsLink {
    public interface Log { void log(String s); }

    static UUID u(String s) { return UUID.fromString(s); }
    static final String BASE = "6e40000%d-b5a3-f393-e0a9-e50e24dcca%s";
    static final String[] SVCS = {"9e", "8e", "7e", "6e"};
    static final UUID CCCD = u("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Log log;
    private BluetoothGatt gatt;
    private int mtu = 23;
    private volatile CountDownLatch opLatch;
    private volatile int opStatus;
    private final LinkedBlockingQueue<byte[]> headerNotifs = new LinkedBlockingQueue<>();
    private CountDownLatch connLatch;
    private volatile boolean connected;

    public IgsLink(Context ctx, Log log) { this.ctx = ctx; this.log = log; }

    public static List<BluetoothDevice> bondedCandidates(Context ctx) {
        BluetoothAdapter a = ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        List<BluetoothDevice> out = new ArrayList<>();
        if (a == null) return out;
        for (BluetoothDevice d : a.getBondedDevices()) {
            String n = d.getName() == null ? "" : d.getName().toUpperCase();
            if (n.contains("IGS") || n.contains("IGPSPORT")) out.add(0, d);
            else if (d.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) out.add(d);
        }
        return out;
    }

    public boolean isConnected() { return connected; }

    private BluetoothGattCharacteristic ch(String svc, int n) {
        if (gatt == null) return null;
        android.bluetooth.BluetoothGattService s = gatt.getService(u(String.format(BASE, 1, svc)));
        return s == null ? null : s.getCharacteristic(u(String.format(BASE, n, svc)));
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log.log("BLE conectado, pidiendo MTU…");
                g.requestMtu(247);
            } else {
                connected = false;
                log.log("BLE desconectado (status " + status + ")");
                if (connLatch != null) connLatch.countDown();
                release(-1);
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int m, int status) {
            mtu = m;
            log.log("MTU " + m);
            g.discoverServices();
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            connected = status == BluetoothGatt.GATT_SUCCESS;
            log.log("Servicios descubiertos: " + g.getServices().size());
            if (connLatch != null) connLatch.countDown();
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { release(status); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { release(status); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            String id = c.getUuid().toString();
            if (id.startsWith("6e400003") && id.endsWith("8e")) {
                headerNotifs.offer(v);
            }
        }
    };

    private void release(int status) {
        opStatus = status;
        CountDownLatch l = opLatch;
        if (l != null) l.countDown();
    }

    public synchronized void connect(BluetoothDevice d) throws Exception {
        if (connected) return;
        connLatch = new CountDownLatch(1);
        log.log("Conectando a " + d.getName() + " …");
        gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        if (!connLatch.await(25, TimeUnit.SECONDS) || !connected) {
            close();
            throw new Exception("No conectó. ¿La app iGPSPORT está abierta y ocupa el iGS? Ciérrala.");
        }
        for (String s : SVCS) {
            BluetoothGattCharacteristic n = ch(s, 3);
            if (n == null) throw new Exception("Servicio " + s + " no encontrado: ¿es un iGS520?");
            gatt.setCharacteristicNotification(n, true);
            BluetoothGattDescriptor dsc = n.getDescriptor(CCCD);
            op(() -> gatt.writeDescriptor(dsc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        }
        headerNotifs.clear();
        log.log("Notificaciones activadas. Listo.");
    }

    interface Call { int run(); }

    private synchronized void op(Call c) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            opLatch = new CountDownLatch(1);
            int r = c.run();
            if (r == BluetoothGatt.GATT_SUCCESS || r == 0) {
                if (!opLatch.await(5, TimeUnit.SECONDS)) throw new Exception("GATT sin respuesta");
                if (opStatus != BluetoothGatt.GATT_SUCCESS) throw new Exception("GATT status " + opStatus);
                return;
            }
            Thread.sleep(30);   // pila ocupada (ERROR_GATT_WRITE_REQUEST_BUSY): reintentar
        }
        throw new Exception("GATT ocupado");
    }

    private void write(BluetoothGattCharacteristic c, byte[] v) throws Exception {
        if (c == null) throw new Exception("característica no disponible");
        op(() -> gatt.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE));
    }

    /** Envía un mensaje: payload troceado por el canal de datos del servicio, luego cabecera por 8e. Espera el ACK. */
    public byte[] send(String dataSvc, IgsProtocol.Frame f, long timeoutMs) throws Exception {
        BluetoothGattCharacteristic data = ch(dataSvc, 2), hdr = ch("8e", 2);
        int max = Math.max(20, mtu - 3);
        headerNotifs.clear();
        for (int off = 0; off < f.payload.length; off += max) {
            int n = Math.min(max, f.payload.length - off);
            byte[] part = new byte[n];
            System.arraycopy(f.payload, off, part, 0, n);
            write(data, part);
        }
        write(hdr, f.header);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            byte[] r = headerNotifs.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (r == null) break;
            if (r.length == 20 && r[1] == f.header[1] && r[4] == f.header[4]) return r;
        }
        throw new Exception("Sin ACK del iGS (svc " + f.header[1] + " op " + f.header[4] + ")");
    }

    /** Envía payload+cabecera tal cual (réplica de la app oficial) y espera cualquier cabecera del mismo servicio. */
    public byte[] sendRaw(String dataSvc, byte[] payload, byte[] hdr, long waitMs) throws Exception {
        BluetoothGattCharacteristic data = ch(dataSvc, 2), h = ch("8e", 2);
        int max = Math.max(20, mtu - 3);
        headerNotifs.clear();
        for (int off = 0; off < payload.length; off += max) {
            int n = Math.min(max, payload.length - off);
            byte[] part = new byte[n];
            System.arraycopy(payload, off, part, 0, n);
            write(data, part);
        }
        write(h, hdr);
        long end = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end) {
            byte[] r = headerNotifs.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (r == null) break;
            if (r.length == 20 && r[1] == hdr[1]) return r;
        }
        return null;
    }

    /** Secuencia de inicio observada en EXP-001/EXP-003 antes de cualquier operación de rutas. */
    static final String[][] HANDSHAKE = {
            {"9e", "080d1001", "010dffff01ffff0004be01ffffffffffffffffec"},
            {"9e", "08111002", "0111ffff02ffff0004ad01ffffffffffffffffae"},
            {"7e", "08061008", "0106ffff08ffff0004e301ffffffffffffffff0b"},
            {"6e", "08071007", "0107ffff07ffff00040901ffffffffffffffffe6"},
            {"6e", "080f1002181322021800", "010fffff02ffff000a6801ffffffffffffffff22"},
            {"6e", "08071001620418002001", "0107ffff01ffff000a1101ffffffffffffffff95"},
            {"7e", "0813100218103800", "0113ffff02ffff00087b01ffffffffffffffff4a"},
    };

    static byte[] unhex(String x) {
        byte[] b = new byte[x.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(x.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private boolean handshakeDone;

    public void handshake() throws Exception {
        if (handshakeDone) return;
        for (String[] s : HANDSHAKE) {
            byte[] r = sendRaw(s[0], unhex(s[1]), unhex(s[2]), 3000);
            log.log("init " + s[2].substring(0, 10) + " -> " + (r == null ? "sin respuesta" : IgsProtocol.hex(r).substring(0, 22)));
        }
        handshakeDone = true;
    }

    public void sendRoute(int id, String name, byte[] cnx, long distCm, int chunk, Log progress) throws Exception {
        List<IgsProtocol.Frame> frames = IgsProtocol.routeFrames(id, name, cnx, distCm, chunk);
        for (int i = 0; i < frames.size(); i++) {
            byte[] ack = send("6e", frames.get(i), 15000);
            if (ack[0] == 2 && ack[7] != 0) throw new Exception("El iGS rechazó el trozo " + (i + 1) + " (estado " + ack[7] + ")");
            progress.log("Trozo " + (i + 1) + "/" + frames.size() + " OK");
        }
    }

    public void close() {
        connected = false;
        handshakeDone = false;
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
    }
}
