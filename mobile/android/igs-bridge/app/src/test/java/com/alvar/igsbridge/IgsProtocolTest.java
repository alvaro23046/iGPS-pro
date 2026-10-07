package com.alvar.igsbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class IgsProtocolTest {
    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test public void crcCheckValue() {
        assertEquals(0xA1, IgsProtocol.crc8Maxim("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test public void headerMatchesCapturedExp001() {
        // EXP-001: payload 080d1001 por 0x000D, cabecera capturada por 0x0013
        byte[] h = IgsProtocol.header(1, 0x0d, 1, unhex("080d1001"), 1);
        assertEquals("010dffff01ffff0004be01ffffffffffffffffec", IgsProtocol.hex(h));
    }

    @Test public void ackMatchesCaptured() {
        assertEquals("0206ffff04ffff00ffffffffffffffffffffff2e", IgsProtocol.hex(IgsProtocol.ack(6, 4)));
        assertEquals("0207ffff04ffff00ffffffffffffffffffffff4c", IgsProtocol.hex(IgsProtocol.ack(7, 4)));
    }

    @Test public void encodedTracksRoundTrip() {
        // fragmento real de EXP-003 (inicio de la ruta), decodificado y re-codificado
        String real = "4.6888695,-74.0971850,283100;-502,546,20;-1834,2428,-20;143,24,20;-97,170,-40;";
        List<double[]> pts = IgsProtocol.decodeTracks(real);
        String xml = new String(IgsProtocol.buildCnxEncoded(1, pts, new ArrayList<>(), 1, 0, 0, false), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("<Tracks>" + real + "</Tracks>"));
    }

    @Test public void routeChunkingFlags() {
        List<double[]> tr = new ArrayList<>();
        for (int i = 0; i < 400; i++) tr.add(new double[]{-25.28 - i * 1e-4, -57.63, 0});
        byte[] cnx = IgsProtocol.buildCnx(900001, tr, new ArrayList<>(), 4000);
        List<IgsProtocol.Frame> f = IgsProtocol.routeFrames(900001, "Test", cnx, 400000);
        assertTrue(f.size() >= 2);
        assertEquals(2, f.get(0).header[10]);
        assertEquals(3, f.get(f.size() - 1).header[10]);
        for (IgsProtocol.Frame x : f) {
            assertTrue(IgsProtocol.headerValid(x.header));
            assertEquals(x.payload.length, ((x.header[7] & 0xFF) << 8) | (x.header[8] & 0xFF));
        }
    }
}
