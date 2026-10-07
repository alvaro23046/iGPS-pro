# Android lab (Samsung SM-A175F, Android 16, sin root)

## Activar HCI snoop
1. Ajustes → Acerca del teléfono → Información de software → tocar 7 veces *Número de compilación*.
2. Ajustes → Opciones de desarrollador → **Habilitar registro de Bluetooth HCI snoop** → *Activado* (no "filtrado").
3. Apagar y encender Bluetooth (obligatorio para que empiece el log).

## Extraer
Opción A — PC con adb (USB): `tools/android/pull_hci.sh EXP-001`
Opción B — solo el teléfono (Termux + adb inalámbrico):
```sh
pkg install android-tools git python unzip
# Opciones desarrollador → Depuración inalámbrica → Vincular con código
adb pair 127.0.0.1:<PUERTO_VINCULACION> <CODIGO>
adb connect 127.0.0.1:<PUERTO_DEPURACION>
bash tools/android/pull_hci.sh EXP-001
```
Sin root, `/data/misc/bluetooth/logs` no es legible directamente; el script cae a `adb bugreport`
y extrae `btsnoop_hci.log` del ZIP. El ZIP contiene datos personales: queda en `captures/hci/tmp/` (ignorado por Git).

## Analizar
`python3 tools/ble/att_extract.py captures/hci/<fecha>_EXP-001.btsnoop`
o abrir el `.btsnoop`/`.pcapng` en Wireshark (filtro `btatt`).
