import socket, struct, sys, time
class R:
    def __init__(s, port=31003):
        s.s = socket.create_connection(("127.0.0.1", port), timeout=60)
        s.n = 1
        s._send(3, open(r"C:\MinecraftNetwork\tools\rcon.secret").read().strip()); s._recv()
    def _send(s, t, body):
        s.n += 1
        d = struct.pack("<ii", s.n, t) + body.encode() + b"\x00\x00"
        s.s.sendall(struct.pack("<i", len(d)) + d)
    def _recv(s):
        b = b""
        while len(b) < 4: b += s.s.recv(4 - len(b))
        n = struct.unpack("<i", b)[0]; buf = b""
        while len(buf) < n: buf += s.s.recv(n - len(buf))
        return buf[8:-2].decode("utf-8", "replace")
    def cmd(s, c): s._send(2, c); return s._recv()
if __name__ == "__main__":
    r = R(); t = time.time()
    for i in range(1000): r.cmd(f"setblock {8004+i%40} 262 {8004+(i//40)%25} minecraft:stone")
    print("1000 setblocks:", round(time.time() - t, 2), "s")
    r.cmd("fill 8004 262 8004 8044 262 8029 air")
