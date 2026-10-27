"""Send a real Votifier v2 vote (what server-list sites do) to the survival server."""
import base64, hashlib, hmac, json, re, socket, struct, sys, time

user = sys.argv[1]
service = sys.argv[2] if len(sys.argv) > 2 else "TestServerList"
cfg = open(r"C:\MinecraftNetwork\survival\plugins\Votifier\config.yml", encoding="utf-8").read()
token = re.search(r"tokens:[\s\S]*?\n\s*default:\s*(\S+)", cfg).group(1)
s = socket.create_connection(("127.0.0.1", 8192), timeout=5)
greeting = s.recv(256).decode().strip()            # "VOTIFIER 2 <challenge>"
challenge = greeting.split()[2]
payload = json.dumps({"serviceName": service, "username": user, "address": "127.0.0.1",
                      "timestamp": int(time.time() * 1000), "challenge": challenge})
sig = base64.b64encode(hmac.new(token.encode(), payload.encode(), hashlib.sha256).digest()).decode()
msg = json.dumps({"signature": sig, "payload": payload}).encode()
s.sendall(struct.pack(">HH", 0x733A, len(msg)) + msg)
print("greeting:", greeting[:20], "| reply:", s.recv(256).decode().strip())
s.close()
