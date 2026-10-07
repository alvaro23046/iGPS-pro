# igs520-lab

Laboratorio de reverse engineering BLE del ciclocomputador **iGPSPORT iGS520**.
Meta: subir rutas (y quizá navegación en vivo) desde una app propia ("iGS Bridge").

## Reglas (Fase 0)
- Solo READ / OBSERVE / CAPTURE / COMPARE / DOCUMENT.
- Nada de firmware, flasheo, mass erase, read protection ni bootloader.
- Ninguna escritura BLE propia hasta que el baseline (EXP-001) esté documentado y
  el comando esté CONFIRMED. Toda escritura pide confirmación explícita.

## Flujo de captura (Android, sin root)
1. Opciones de desarrollador → *Habilitar registro de Bluetooth HCI snoop* → Activado.
2. Apagar/encender Bluetooth (el log arranca limpio).
3. Ejecutar el experimento (p.ej. EXP-001) con la app oficial.
4. `tools/android/pull_hci.sh EXP-001` (en PC con adb, o en Termux con adb inalámbrico).
5. `python3 tools/ble/att_extract.py captures/hci/<fecha>_EXP-001.btsnoop` → GATT + log ATT.

Ver `docs/android-lab.md`.
