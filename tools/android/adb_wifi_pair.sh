#!/data/data/com.termux/files/usr/bin/bash
# Vincula y conecta adb inalámbrico al PROPIO teléfono desde Termux.
# Uso (con la ventana "código de vinculación" abierta en pantalla dividida):
#   bash tools/android/adb_wifi_pair.sh PUERTO_VINCULACION CODIGO [PUERTO_CONEXION]
# Acepta "38831" o "192.168.100.6:38831".
set -u
norm() { case "$1" in *:*) echo "${1##*:}";; *) echo "$1";; esac; }
PP=$(norm "${1:?falta PUERTO_VINCULACION (el que sale bajo el código)}")
CODE="${2:?falta CODIGO de 6 dígitos}"
CP=$(norm "${3:-}")
IP=$(ifconfig 2>/dev/null | grep -oE 'inet 192\.168\.[0-9]+\.[0-9]+' | head -1 | cut -d' ' -f2)
adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
OK=""
for H in 127.0.0.1 ${IP:-}; do
  echo "== adb pair $H:$PP"
  if adb pair "$H:$PP" "$CODE" 2>&1 | tee /dev/stderr | grep -q "Successfully paired"; then OK=$H; break; fi
done
[ -n "$OK" ] || { echo "FALLÓ. Causas típicas: la ventana del código se cerró, puerto viejo, código caducado."; exit 1; }
[ -n "$CP" ] || read -rp "Puerto de 'Dirección IP y puerto' (arriba, solo número): " CP
CP=$(norm "$CP")
adb connect "$OK:$CP"; adb devices -l
