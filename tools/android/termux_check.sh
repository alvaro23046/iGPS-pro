#!/data/data/com.termux/files/usr/bin/bash
# Diagnóstico SOLO LECTURA del entorno Termux / Android. No cambia nada.
echo "== Termux =="; whoami; uname -a; echo "PREFIX=$PREFIX"
echo "== Android =="
for p in ro.product.model ro.build.version.release ro.build.version.sdk ro.build.version.security_patch; do
  printf '%s=%s\n' "$p" "$(getprop $p)"; done
echo "== Herramientas =="
for t in python adb sshd tshark termux-info; do printf '%-12s %s\n' "$t" "$(command -v $t || echo MISSING)"; done
echo "== Bluetooth (best effort, sin root) =="
settings get global bluetooth_on 2>/dev/null || echo "settings no accesible desde Termux (normal)"
getprop persist.bluetooth.btsnooplogmode 2>/dev/null | sed 's/^/btsnooplogmode=/'
echo "== ADB local =="
if command -v adb >/dev/null; then adb devices -l; else echo "pkg install android-tools  # para adb inalámbrico local"; fi
