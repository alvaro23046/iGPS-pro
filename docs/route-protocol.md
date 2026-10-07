# Protocolo de rutas iGS520

Fuentes: EXP-003 (envío de ruta desde app oficial 8.06.42) + análisis estático local de la app (no publicado).

## Transporte (CONFIRMED)
- Cabecera 20 B por `6e400002-…dcca8e` (0x0013); ACK/cabeceras del dispositivo por `6e400003-…dcca8e` (0x0015).
- `[kind, service, ff, ff, op, ff, ff, lenHi, lenLo, crc(payload), flag, ff×8, crc(header[0:19])]`
  - kind 01 = mensaje, 02 = ACK (`[7]` = estado, 00 = OK). flag 01 único / 02 intermedio / 03 último.
  - CRC-8/MAXIM (poly 0x31 reflejado, init 0). Validado en 1214/1214 cabeceras y 9/9 trozos.
- Payload protobuf por el canal de datos del servicio; ROUTE_PLAN (service 7) → `6e400002-…dcca6e` (0x001F).

## route_plan_data_msg (CONFIRMED por captura + código)
| Campo | Significado |
|---|---|
| 1 | service_type = 7 |
| 2 | operate_type: 1 LIST_GET, 3 FILE_DEL, 4 FILE_SEND, 5 FILE_USE, 6 FILES_DEL, 7 LIST_NUM_GET, 8 RENAME, 9 TURN_PROMPT, 10 NOTIFY_TRAJECTORY_DEVIATION, 11 ROUTE_POINT_GET, 12 REROUTE_STATUS, 13/14 soporte de funciones, 15 PACKET_FILE_SEND_END |
| 3 | line_id `"<id>.cnx"` |
| 4 | file_content (≤ 4096 B por mensaje) |
| 5 | route_plan_info {1 id, 2 file_type (1 cnx, 2 gpx, 3 fit, 4 tcx, 5 xml), 3 name, 4 total_distance (cm)} |

Un archivo se envía como N mensajes completos (uno por cada 4096 B), cada uno con su cabecera; el iGS confirma cada uno con `02 07 ff ff 04 ff ff 00 …`.
`tools/protocol/igs_proto.py` reproduce byte a byte el envío capturado.

## Archivo .cnx (XML)
`<Route><Id/><Distance/><Duration/><Ascent/><Descent/><TracksCount/><NavsCount/><PointsCount/><Reduce/><Lang/><Tracks/><Navs><Nav><Lat/><Lng/><Type/><Info/></Nav>…</Navs><Points><Point><Lat/><Lng/><Descr/></Point></Points></Route>`
- Tracks sin codificar: `lat,lng,alt;…`. Con `<Encode>2</Encode>` (rutas de la nube): primer punto absoluto, después deltas (1e-7 °, altitud en cm).
- Nav Type (tabla de maniobras Google de la app): 0 recto, 1 izquierda, 2 derecha, 5 vuelta en U izq., 6 vuelta en U der., 7 rotonda izq., 8 rotonda der.

## Recálculo (PROBABLE, sin capturar)
El iGS notifica desvío (op 10); la app pide la posición (op 11) y envía `navi_app.cnx` por el servicio FILE_OPERATION (tipo YAW_ANT_RENAVI_BOOK).
iGS Bridge v0.1 recalcula por GPS del teléfono y reenvía la ruta por FILE_SEND.
