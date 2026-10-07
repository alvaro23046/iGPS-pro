# Arquitectura

Fase actual: laboratorio Android pasivo.

    App oficial iGPSPORT ⇄ BLE ⇄ iGS520
              │ (HCI snoop, solo observación)
              ▼
    btsnoop → att_extract.py → GATT + log ATT → packet_diff.py

Futuro: iGS Bridge (Android → iOS) = companion app + routing (OSM: BRouter/GraphHopper/Valhalla) + upload BLE.
Nivel de control (A–D) se decidirá con evidencia (Fase 17).
