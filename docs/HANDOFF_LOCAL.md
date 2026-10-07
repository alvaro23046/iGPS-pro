# Traspaso a sesión local (PC Windows 11 / WSL2)

Estado al 2026-10-07:
- Repo creado y en GitHub: rama `claude/affectionate-goldberg-7iupkf` de alvaro23046/iGPS-pro.
- Herramientas listas y probadas con captura sintética: pull_hci.sh, att_extract.py, packet_diff.py.
- Teléfono Samsung SM-A175F (A17, Android 16), IP 192.168.100.6. adb inalámbrico YA vinculado
  (guid adb-R5GL662D9PF) desde Termux; falló `adb connect` por comandos pegados en una línea.
- HCI snoop: el usuario lo activó. EXP-001 NO capturado todavía. GATT desconocido.
- SSH a Termux probado antes: usuario u0_a333, puerto 8022, clave en
  ...\EXTRACT DATA\matrix-live-wallpaper\.ssh\android_agent_ed25519 (ver TRASPASO_TERMUX_ANDROID.md).
