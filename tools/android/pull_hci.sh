#!/usr/bin/env bash
# Extrae el HCI snoop log del Android vía adb (sin root) usando bugreport.
# Uso: pull_hci.sh EXP-001 [serial]
# Solo lectura: no modifica el teléfono.
set -euo pipefail
EXP="${1:?uso: pull_hci.sh EXP-XXX [serial]}"
[[ "$EXP" =~ ^EXP-[0-9]{3}$ ]] || { echo "nombre de experimento inválido"; exit 1; }
SER=(); [[ -n "${2:-}" ]] && SER=(-s "$2")
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/captures/hci"; TMP="$OUT/tmp"; mkdir -p "$TMP"
DATE="$(date +%Y-%m-%d)"; BASE="${DATE}_${EXP}"

adb devices -l
[[ "$(adb "${SER[@]}" get-state 2>/dev/null)" == "device" ]] || { echo "No hay dispositivo adb autorizado"; exit 1; }
echo "snoop mode: $(adb "${SER[@]}" shell getprop persist.bluetooth.btsnooplogmode)"

# 1) Intento directo (funciona en algunos ROMs / builds userdebug)
if adb "${SER[@]}" pull /data/misc/bluetooth/logs/btsnoop_hci.log "$OUT/$BASE.btsnoop" 2>/dev/null; then
  echo "pull directo OK"
else
  # 2) bugreport (vía oficial sin root). Tarda 1-3 min.
  echo "pull directo denegado -> generando bugreport..."
  adb "${SER[@]}" bugreport "$TMP/${BASE}_bugreport.zip"
  LOG="$(unzip -Z1 "$TMP/${BASE}_bugreport.zip" | grep -E 'btsnoop_hci\.log$' | head -1 || true)"
  [[ -n "$LOG" ]] || { echo "btsnoop_hci.log no está en el bugreport. ¿Snoop activado y BT reiniciado?"; \
     unzip -Z1 "$TMP/${BASE}_bugreport.zip" | grep -i -E 'snoop|bluetooth' | head -20; exit 2; }
  unzip -p "$TMP/${BASE}_bugreport.zip" "$LOG" > "$OUT/$BASE.btsnoop"
fi

head -c 8 "$OUT/$BASE.btsnoop" | grep -q btsnoop || { echo "AVISO: no es formato btsnoop (¿Samsung cifrado/filtrado?)"; }
sha256sum "$OUT/$BASE.btsnoop" | tee "$OUT/$BASE.sha256"
# Conversión a pcapng si hay tshark/editcap
if command -v editcap >/dev/null; then editcap -F pcapng "$OUT/$BASE.btsnoop" "$OUT/$BASE.pcapng" && echo "-> $OUT/$BASE.pcapng"; fi
echo "Listo: $OUT/$BASE.btsnoop"
