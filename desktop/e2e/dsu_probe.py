#!/usr/bin/env python3
"""Cliente DSU mínimo, con la misma forma que el de Dolphin.

Sirve para probar un servidor DSU de PepoMote SIN Dolphin de por medio, que
es lo que hace falta cuando el servidor corre en un móvil Android y la parte
de Dolphin (editar DSUClient.ini a mano) aún no está hecha: si aquí se ven
PadData moviéndose, lo que falle después es configuración de Dolphin, no del
servidor.

    python dsu_probe.py 192.168.1.42          # el móvil, puerto 26760
    python dsu_probe.py 127.0.0.1 26770       # un receptor de pruebas
    python dsu_probe.py 192.168.1.42 --slot 1 # otro pad

Sin dependencias. Ctrl-C para salir.
"""
import argparse
import socket
import struct
import sys
import time
import zlib

PROTOCOL_VERSION = 1001
CLIENT_ID = 0x50524F42  # "BORP" en LE: cualquier id vale, solo nos identifica

MSG_VERSION = 0x100000
MSG_PORT_INFO = 0x100001
MSG_PAD_DATA = 0x100002

# Bits de los bytes 36 y 37 del PadData (protocol/DSU.md)
B1 = ["Share", "L3", "R3", "Options", "Up", "Right", "Down", "Left"]
B2 = ["L2", "R2", "L1", "R1", "Triangle", "Circle", "Cross", "Square"]


def frame(msg_type, payload=b""):
    """Cabecera DSUC + tipo + carga, con el CRC32 del paquete entero."""
    body = struct.pack("<I", msg_type) + payload
    head = b"DSUC" + struct.pack("<HHII", PROTOCOL_VERSION, len(body), 0, CLIENT_ID)
    packet = head + body
    crc = zlib.crc32(packet) & 0xFFFFFFFF
    return packet[:8] + struct.pack("<I", crc) + packet[12:]


def check(packet):
    """Valida cabecera DSUS y CRC. Devuelve (tipo, carga) o None."""
    if len(packet) < 20 or packet[:4] != b"DSUS":
        return None
    version, length = struct.unpack("<HH", packet[4:8])
    if version != PROTOCOL_VERSION:
        return None
    crc_in = struct.unpack("<I", packet[8:12])[0]
    zeroed = packet[:8] + b"\0\0\0\0" + packet[12:]
    if zlib.crc32(zeroed) & 0xFFFFFFFF != crc_in:
        return None
    msg_type = struct.unpack("<I", packet[16:20])[0]
    return msg_type, packet[20:]


def names(mask, table):
    return [n for i, n in enumerate(table) if mask & (1 << i)]


def show_pad_data(payload):
    """payload empieza justo tras el tipo: los 80 bytes de un PadData."""
    if len(payload) < 80:
        return
    slot = payload[0]
    connected = payload[1]
    battery = payload[10]
    counter = struct.unpack("<I", payload[12:16])[0]
    b1, b2, ps, touch = payload[16:20]
    lx, ly, rx, ry = payload[20:24]
    t_us = struct.unpack("<Q", payload[48:56])[0]
    accel = struct.unpack("<3f", payload[56:68])
    gyro = struct.unpack("<3f", payload[68:80])

    pressed = names(b1, B1) + names(b2, B2)
    if ps:
        pressed.append("PS")
    if touch:
        pressed.append("Touch(recentrado)")

    print(
        f"pad {slot} {'ok' if connected else 'DESCONECTADO'} #{counter:<6} "
        f"bat={battery} "
        f"accel=({accel[0]:+.2f},{accel[1]:+.2f},{accel[2]:+.2f})g "
        f"gyro=({gyro[0]:+7.1f},{gyro[1]:+7.1f},{gyro[2]:+7.1f})°/s "
        f"stick=({lx - 128:+4d},{ly - 128:+4d}) "
        f"t={t_us} "
        f"{' '.join(pressed)}"
    )


def show_port_info(payload):
    if len(payload) < 11:
        return
    slot, state = payload[0], payload[1]
    mac = ":".join(f"{b:02x}" for b in payload[4:10])
    estado = "conectado" if state == 2 else "sin muestras"
    print(f"PortInfo: pad {slot} {estado} mac={mac} bateria={payload[10]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("host", help="IP del servidor DSU (el móvil, o 127.0.0.1)")
    ap.add_argument("port", nargs="?", type=int, default=26760)
    ap.add_argument("--slot", type=int, default=0, help="pad al que suscribirse (0-3)")
    ap.add_argument("--seconds", type=float, default=0, help="parar tras N segundos (0 = sin fin)")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(1.0)
    dest = (args.host, args.port)
    print(f"Sondeando {args.host}:{args.port}, pad {args.slot}. Ctrl-C para salir.")

    sock.sendto(frame(MSG_VERSION), dest)
    # PortInfo de los 4 pads: i32 con cuántos, y luego sus índices
    sock.sendto(frame(MSG_PORT_INFO, struct.pack("<i4B", 4, 0, 1, 2, 3)), dest)

    # Suscripción como la de Dolphin: flags 1 (por PadID) + el índice
    subscribe = frame(MSG_PAD_DATA, struct.pack("<BB6B", 1, args.slot, 0, 0, 0, 0, 0, 0))
    sock.sendto(subscribe, dest)

    started = time.monotonic()
    last_sub = started
    seen = 0
    quiet_since = started
    try:
        while True:
            now = time.monotonic()
            if args.seconds and now - started >= args.seconds:
                break
            # Dolphin renueva la suscripción cada segundo; el servidor caduca
            # a los 3 s sin saber de nosotros
            if now - last_sub >= 1.0:
                sock.sendto(subscribe, dest)
                last_sub = now
            try:
                data, _ = sock.recvfrom(2048)
            except socket.timeout:
                if now - quiet_since >= 3.0:
                    print("… sin respuesta (¿la IP y el puerto son los correctos? ¿el móvil está mandando?)")
                    quiet_since = now
                continue
            parsed = check(data)
            if parsed is None:
                print(f"paquete no válido ({len(data)} bytes)")
                continue
            msg_type, payload = parsed
            quiet_since = now
            if msg_type == MSG_VERSION:
                print(f"Servidor DSU vivo (protocolo {struct.unpack('<H', payload[:2])[0]})")
            elif msg_type == MSG_PORT_INFO:
                show_port_info(payload)
            elif msg_type == MSG_PAD_DATA:
                seen += 1
                show_pad_data(payload)
    except KeyboardInterrupt:
        pass

    elapsed = time.monotonic() - started
    print(f"\n{seen} PadData en {elapsed:.1f} s ({seen / elapsed if elapsed else 0:.0f}/s)")
    return 0 if seen else 1


if __name__ == "__main__":
    sys.exit(main())
