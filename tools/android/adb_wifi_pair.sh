#!/data/data/com.termux/files/usr/bin/bash
# Vincula y conecta adb inalámbrico al PROPIO teléfono desde Termux.
# Encuentra los puertos solo (mDNS); tú solo escribes el código de 6 dígitos.
# Uso: abre "Vincular con código de vinculación", deja la ventana abierta, ejecuta:
#   bash tools/android/adb_wifi_pair.sh
export ADB_MDNS_OPENSCREEN=1
adb start-server >/dev/null 2>&1
find_port() { adb mdns services 2>/dev/null | grep "$1" | grep -oE '[0-9.]+:[0-9]+' | head -1; }

echo "Buscando puerto de vinculación (ventana del código abierta)..."
for i in $(seq 1 15); do P=$(find_port _adb-tls-pairing); [ -n "$P" ] && break; sleep 1; done
if [ -z "$P" ]; then
  read -rp "No detectado. Escribe el puerto que sale bajo el código (ej 38831): " PORT
  P="127.0.0.1:$PORT"
fi
echo "Vinculación en $P"
read -rp "Código de 6 dígitos: " CODE
adb pair "$P" "$CODE" || { echo "Falló la vinculación. Abre un código nuevo y repite."; exit 1; }

echo "Buscando puerto de conexión..."
for i in $(seq 1 15); do C=$(find_port _adb-tls-connect); [ -n "$C" ] && break; sleep 1; done
if [ -z "$C" ]; then
  read -rp "Escribe el puerto de 'Dirección IP y puerto' (arriba, ej 37109): " PORT
  C="127.0.0.1:$PORT"
fi
adb connect "$C"
adb devices -l
