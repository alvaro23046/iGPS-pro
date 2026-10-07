#!/usr/bin/env python3
"""Compara payloads ATT de dos experimentos (salidas .att.jsonl de att_extract.py).

Uso: packet_diff.py A.att.jsonl B.att.jsonl [--handle 0x0012] [--op WRITE_CMD]
Alinea los paquetes por orden dentro del mismo handle/op y reporta offsets constantes
vs variables. Todo resultado es HIPÓTESIS (Confidence LOW) hasta confirmarlo con otro experimento.
"""
import argparse, json
from collections import defaultdict


def load(path, handle, op):
    groups = defaultdict(list)
    for line in open(path):
        r = json.loads(line)
        if "handle" not in r or (handle is not None and r["handle"] != handle) or (op and r["op"] != op):
            continue
        groups[(r["handle"], r["op"], r["dir"])].append(bytes.fromhex(r["value"]))
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a"); ap.add_argument("b")
    ap.add_argument("--handle", type=lambda s: int(s, 0)); ap.add_argument("--op")
    x = ap.parse_args()
    A, B = load(x.a, x.handle, x.op), load(x.b, x.handle, x.op)
    for key in sorted(set(A) | set(B)):
        pa, pb = A.get(key, []), B.get(key, [])
        print(f"\n=== handle 0x{key[0]:04X} {key[1]} {key[2]} : A={len(pa)} pkts B={len(pb)} pkts")
        la, lb = sorted({len(p) for p in pa}), sorted({len(p) for p in pb})
        print(f"  longitudes A={la[:10]} B={lb[:10]}")
        for i, (p, q) in enumerate(zip(pa, pb)):
            diffs = [o for o in range(max(len(p), len(q))) if (p[o:o+1] != q[o:o+1])]
            if not diffs:
                continue
            print(f"  pkt #{i}: {len(diffs)} bytes difieren")
            for o in diffs[:32]:
                va = f"{p[o]:02X}" if o < len(p) else "--"; vb = f"{q[o]:02X}" if o < len(q) else "--"
                print(f"    OFFSET 0x{o:02X}  A:{va}  B:{vb}   meaning: ?  Confidence: LOW")
        if pa:  # offsets constantes dentro de A (candidatos a header)
            m = min(len(p) for p in pa)
            const = [o for o in range(m) if len({p[o] for p in pa}) == 1]
            print(f"  offsets constantes en todos los pkts A (posible header): {[hex(o) for o in const[:16]]}")


if __name__ == "__main__":
    main()
