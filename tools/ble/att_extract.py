#!/usr/bin/env python3
"""Parser pasivo de btsnoop (HCI H4) -> tráfico ATT + tabla GATT reconstruida.

Solo lee un archivo. No habla con ningún dispositivo.
Uso: att_extract.py captura.btsnoop [--out-prefix PREFIJO]
Genera PREFIJO.att.jsonl (cada PDU ATT) y PREFIJO.gatt.md (servicios/características vistos).
"""
import argparse, json, struct, sys
from pathlib import Path

ATT_OPS = {0x01: "ERROR_RSP", 0x02: "MTU_REQ", 0x03: "MTU_RSP", 0x04: "FIND_INFO_REQ", 0x05: "FIND_INFO_RSP",
           0x08: "READ_BY_TYPE_REQ", 0x09: "READ_BY_TYPE_RSP", 0x0A: "READ_REQ", 0x0B: "READ_RSP",
           0x10: "READ_BY_GROUP_REQ", 0x11: "READ_BY_GROUP_RSP", 0x12: "WRITE_REQ", 0x13: "WRITE_RSP",
           0x1B: "NOTIFY", 0x1D: "INDICATE", 0x1E: "CONFIRM", 0x52: "WRITE_CMD", 0x16: "PREP_WRITE_REQ",
           0x18: "EXEC_WRITE_REQ"}
PROPS = [(0x02, "READ"), (0x04, "WRITE_NO_RSP"), (0x08, "WRITE"), (0x10, "NOTIFY"), (0x20, "INDICATE")]
HANDLE_OPS = {0x0A, 0x0B, 0x12, 0x1B, 0x1D, 0x52, 0x16}


def uuid_str(b):
    if len(b) == 2:
        return f"0x{struct.unpack('<H', b)[0]:04X}"
    h = b[::-1].hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def records(path):
    data = Path(path).read_bytes()
    if data[:8] != b"btsnoop\0":
        sys.exit("No es un archivo btsnoop")
    off = 16
    while off + 24 <= len(data):
        orig, incl, flags, _drops, ts = struct.unpack(">IIIIq", data[off:off + 24])
        yield flags, ts, data[off + 24:off + 24 + incl]
        off += 24 + incl


def att_pdus(path):
    partial = {}  # (handle, dir) -> [expected_len, bytes]
    for flags, ts, pkt in records(path):
        if not pkt or pkt[0] != 0x02 or len(pkt) < 5:
            continue  # solo ACL
        hf, alen = struct.unpack("<HH", pkt[1:5])
        conn, pb = hf & 0x0FFF, (hf >> 12) & 0x3
        payload = pkt[5:5 + alen]
        direction = "dev->phone" if flags & 1 else "phone->dev"
        key = (conn, direction)
        if pb == 0x1:  # continuación
            if key in partial:
                partial[key][1] += payload
        else:
            if len(payload) < 4:
                continue
            partial[key] = [struct.unpack("<H", payload[:2])[0] + 4, bytearray(payload)]
        if key in partial and len(partial[key][1]) >= partial[key][0]:
            l2 = bytes(partial.pop(key)[1])
            cid = struct.unpack("<H", l2[2:4])[0]
            if cid == 0x0004 and len(l2) > 4:
                yield ts, conn, direction, l2[4:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("--out-prefix")
    a = ap.parse_args()
    prefix = a.out_prefix or str(Path(a.capture).with_suffix(""))
    services, chars, descs, usage = [], {}, {}, {}
    n = 0
    with open(prefix + ".att.jsonl", "w") as jf:
        for ts, conn, d, pdu in att_pdus(a.capture):
            op = pdu[0]
            rec = {"ts": ts, "conn": conn, "dir": d, "op": ATT_OPS.get(op, f"0x{op:02X}"), "hex": pdu.hex()}
            if op in HANDLE_OPS and len(pdu) >= 3:
                h = struct.unpack("<H", pdu[1:3])[0]
                rec["handle"] = h
                rec["value"] = pdu[3:].hex()
                usage.setdefault(h, set()).add(rec["op"])
            elif op == 0x0B:
                pass
            if op == 0x11 and len(pdu) > 2:
                L = pdu[1]
                for i in range(2, len(pdu) - L + 1, L):
                    s, e = struct.unpack("<HH", pdu[i:i + 4])
                    services.append((s, e, uuid_str(pdu[i + 4:i + L])))
            elif op == 0x09 and len(pdu) > 2:
                L = pdu[1]
                for i in range(2, len(pdu) - L + 1, L):
                    e = pdu[i:i + L]
                    if L in (7, 21):  # declaración de característica
                        decl, props, vh = struct.unpack("<HBH", e[:5])
                        chars[vh] = (decl, props, uuid_str(e[5:]))
            elif op == 0x05 and len(pdu) > 2:
                step = 4 if pdu[1] == 1 else 18
                for i in range(2, len(pdu) - step + 1, step):
                    descs[struct.unpack("<H", pdu[i:i + 2])[0]] = uuid_str(pdu[i + 2:i + step])
            jf.write(json.dumps(rec) + "\n")
            n += 1

    def svc_of(h):
        for s, e, u in services:
            if s <= h <= e:
                return u
        return "?"

    lines = ["| Service | Char handle | Characteristic | Properties | Observed ops | Confidence |",
             "|---|---|---|---|---|---|"]
    for vh in sorted(set(chars) | set(usage)):
        decl, props, u = chars.get(vh, (None, 0, "? (no visto en discovery)"))
        p = ",".join(name for bit, name in PROPS if props & bit) or "?"
        ops = ",".join(sorted(usage.get(vh, []))) or "-"
        lines.append(f"| {svc_of(vh)} | 0x{vh:04X} | {u} | {p} | {ops} | {'CONFIRMED' if vh in chars else 'UNKNOWN'} |")
    lines += ["", "Descriptores:", ""] + [f"- 0x{h:04X}: {u}" for h, u in sorted(descs.items())]
    lines += ["", "Servicios:", ""] + [f"- 0x{s:04X}-0x{e:04X}: {u}" for s, e, u in services]
    Path(prefix + ".gatt.md").write_text("\n".join(lines) + "\n")
    print(f"{n} PDUs ATT -> {prefix}.att.jsonl ; GATT -> {prefix}.gatt.md")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
