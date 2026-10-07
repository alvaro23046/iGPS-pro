# GATT iGS520

Fuente: EXP-001 (2026-10-07), `captures/ble/2026-10-07_EXP-001.gatt.md`. Discovery completo observado.
4 servicios propietarios estilo Nordic UART (base `6e40000x-b5a3-f393-e0a9-e50e24dccaXe`, X = 9/8/7/6).
En cada uno: `...02` = escritura teléfono→dispositivo, `...03` = notificaciones dispositivo→teléfono.

| Service | Characteristic (handle) | Properties | Observed use | Confidence |
|---|---|---|---|---|
| 0x1800 GAP | 0x2A00/0x2A01/0x2A04/0x2AA6 | READ (2A00 +WRITE) | estándar | CONFIRMED |
| 0x1801 GATT | — | — | vacío | CONFIRMED |
| …dcca9e | 6e400002 (0x000D) | WRITE, WRITE_NO_RSP | comandos protobuf: info/estado (cmd 0x0d, 0x11, 0x0c settings con token base64, 0x13) | CONFIRMED uso / PROBABLE función |
| …dcca9e | 6e400003 (0x000F) | NOTIFY | respuestas protobuf a lo anterior | CONFIRMED |
| …dcca8e | 6e400002 (0x0013) | WRITE, WRITE_NO_RSP | **canal de encabezado/framing**: 20 B `01 <cmd> ff ff <seq> ff ff 00 <len> <chk?> 01 ff… <chk?>`; `len` = tamaño del payload enviado por el canal de datos | CONFIRMED len / PROBABLE resto |
| …dcca8e | 6e400003 (0x0015) | NOTIFY | ACK del framing (`01/02 <cmd> … <len resp>`) | PROBABLE |
| …dcca7e | 6e400002 (0x0019) | WRITE, WRITE_NO_RSP | cmd 0x06: listado de **actividades** (respuesta con timestamps FIT epoch → ago-2026) | PROBABLE |
| …dcca7e | 6e400003 (0x001B) | NOTIFY | lista de actividades (protobuf) | PROBABLE |
| …dcca7e | 0x001A | ? (READ_REQ visto) | desconocido | UNKNOWN |
| …dcca6e | 6e400002 (0x001F) | WRITE, WRITE_NO_RSP | cmd 0x07 / 0x0f: listado de **rutas** guardadas | PROBABLE |
| …dcca6e | 6e400003 (0x0021) | NOTIFY | lista de rutas: nombres UTF-8 visibles ("Patiosx80", "… bogota - barbosa") | CONFIRMED nombres / PROBABLE función |
| 0x180A Device Info | 0x2A29/0x2A27/0x2A28 | READ | fabricante/hardware/software | CONFIRMED |

Descriptores CCCD (0x2902) en 0x0010, 0x0016, 0x001C, 0x0022: el teléfono escribe `0100` (activa notify) al conectar. CONFIRMED.

Codificación de payloads: **protobuf** (tags `08 xx 10 yy` = campo1=cmd, campo2=subcmd/seq). PROBABLE alta.
MTU/fragmentación: mensajes largos se parten en varios WRITE_CMD/NOTIFY seguidos. CONFIRMED.

Confidence: CONFIRMED (visto en captura) / PROBABLE / UNKNOWN.
