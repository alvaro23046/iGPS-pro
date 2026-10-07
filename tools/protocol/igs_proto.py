#!/usr/bin/env python3
"""Codificador del protocolo de rutas iGS520 (derivado de EXP-001/EXP-003 + análisis estático de la app 8.06.42).

Solo construye bytes; no habla con ningún dispositivo.

Transporte (CONFIRMED, 1214/1214 cabeceras con CRC válido):
  - Cabecera 20 B por 6e400002-...dcca8e (handle 0x0013):
      [0] 01=petición/datos, 02=ACK
      [1] service_type_index (7 = ROUTE_PLAN)
      [2:4] ff ff
      [4] operación (4 = FILE_SEND)
      [5:7] ff ff
      [7:9] longitud del payload (big-endian)
      [9] CRC-8/MAXIM del payload
      [10] 01 = mensaje único, 02 = trozo intermedio, 03 = último trozo
      [11:19] ff * 8
      [19] CRC-8/MAXIM de los bytes [0:19]
  - Payload protobuf por el canal de datos del servicio (ROUTE_PLAN -> ...dcca6e, handle 0x001F),
    troceado en escrituras de MTU-3 bytes.
  - El dispositivo responde a cada trozo con una cabecera 02 por ...dcca8e (handle 0x0015).

Mensaje route_plan_data_msg (protobuf):
  1 service_type = 7, 2 operate_type, 3 line_id "<id>.cnx", 4 file_content (<=4096 B por trozo),
  5 route_plan_info_msg {1 id, 2 file_type (1=cnx), 3 name, 4 total_distance}.
"""
from __future__ import annotations

import sys
from xml.sax.saxutils import escape

SERVICE_ROUTE_PLAN = 7
OP_FILE_SEND = 4
OP_FILE_USE = 5
FILE_TYPE_CNX = 1
CHUNK = 4096

# Google Directions "maneuver" -> Nav Type del iGS (tabla copiada de RouteUtil.maneuverMap de la app oficial)
MANEUVER_TYPE = {
    "turn-slight-left": 1, "turn-sharp-left": 1, "uturn-left": 5, "turn-left": 1,
    "turn-slight-right": 2, "turn-sharp-right": 2, "uturn-right": 6, "turn-right": 2,
    "straight": 0, "ramp-left": 1, "ramp-right": 2, "merge": -1, "fork-left": -1,
    "fork-right": -1, "ferry": -1, "ferry-train": -1, "roundabout-left": 7, "roundabout-right": 8,
}


def crc8_maxim(data: bytes) -> int:
    c = 0
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0x8C if c & 1 else c >> 1
    return c


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _field_varint(f: int, v: int) -> bytes:
    return _varint(f << 3) + _varint(v)


def _field_bytes(f: int, v: bytes) -> bytes:
    return _varint(f << 3 | 2) + _varint(len(v)) + v


def build_cnx(route_id: int, track: list[tuple[float, float, float]], navs: list[tuple[float, float, int, str]],
              distance_m: float, ascent: int = 0, descent: int = 0) -> bytes:
    """CNX sin codificar (formato RouteUtil.transformToBBRoute: 'lat,lng,alt;...')."""
    tracks = ";".join(f"{la},{ln},{al}" for la, ln, al in track)
    navxml = "".join(
        f"<Nav><Lat>{la}</Lat><Lng>{ln}</Lng><Type>{t}</Type><Info>{escape(info)}</Info></Nav>"
        for la, ln, t, info in navs)
    xml = (f'<?xml version="1.0" encoding="UTF-8"?>\n<Route><Id>{route_id}</Id><Distance>{int(distance_m)}</Distance>'
           f'<Duration>0</Duration><Ascent>{ascent}</Ascent><Descent>{descent}</Descent>'
           f'<TracksCount>{len(track)}</TracksCount><NavsCount>{len(navs)}</NavsCount><PointsCount>0</PointsCount>'
           f'<Reduce>0</Reduce><Lang>0</Lang><Tracks>{tracks}</Tracks>'
           f'<Navs>{navxml}</Navs><Points></Points></Route>')
    return xml.encode()


def route_messages(route_id: int, name: str, cnx: bytes, distance_cm: int, line_name: str | None = None) -> list[bytes]:
    """Un route_plan_data_msg por cada trozo de 4096 B del archivo."""
    line = (line_name or f"{route_id}.cnx").encode()
    info = (_field_varint(1, route_id) + _field_varint(2, FILE_TYPE_CNX) + _field_bytes(3, name.encode())
            + _field_varint(4, distance_cm))
    msgs = []
    for off in range(0, len(cnx), CHUNK):
        msgs.append(_field_varint(1, SERVICE_ROUTE_PLAN) + _field_varint(2, OP_FILE_SEND) + _field_bytes(3, line)
                    + _field_bytes(4, cnx[off:off + CHUNK]) + _field_bytes(5, info))
    return msgs


def header(service: int, op: int, payload: bytes, flag: int, kind: int = 1) -> bytes:
    h = bytes([kind, service, 0xFF, 0xFF, op, 0xFF, 0xFF]) + len(payload).to_bytes(2, "big") \
        + bytes([crc8_maxim(payload), flag]) + b"\xff" * 8
    return h + bytes([crc8_maxim(h)])


def frames_for_route(route_id: int, name: str, cnx: bytes, distance_cm: int, line_name: str | None = None):
    """[(header20, payload)] en el orden que los manda la app oficial."""
    msgs = route_messages(route_id, name, cnx, distance_cm, line_name)
    out = []
    for i, m in enumerate(msgs):
        flag = 1 if len(msgs) == 1 else (3 if i == len(msgs) - 1 else 2)
        out.append((header(SERVICE_ROUTE_PLAN, OP_FILE_SEND, m, flag), m))
    return out


if __name__ == "__main__":
    # Autoprueba: reconstruir el envío de EXP-003 y compararlo byte a byte con la captura (solo local).
    raw = open(sys.argv[1], "rb").read()        # captures/routes/exp003_raw.bin
    cnx = open(sys.argv[2], "rb").read()        # captures/routes/exp003_route.cnx
    fr = frames_for_route(422689, " Actividad de paseo-20230719", cnx, 5239200)
    rebuilt = b"".join(p for _, p in fr)
    print("payload idéntico:", rebuilt == raw, len(rebuilt), len(raw))
    print("cabeceras:", [h.hex() for h, _ in fr][:2], "...", fr[-1][0].hex())
