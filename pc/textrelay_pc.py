#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文字互传 PC 节点：本机网页 + 持久消息库 + 多设备发现与双向同步。

网页仅监听 127.0.0.1；局域网端点只开放 info/messages/push，不开放删除。
仅标准库。python textrelay_pc.py [--no-browser] [--phone IP[:端口]]
"""
import argparse
import ipaddress
import json
import os
import socket
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import outbox_store
from message_store import MessageStore

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 24680
BEACON_PORT = 24681
APP_TAG = "textrelay"
MULTICAST_GROUP = "239.255.246.80"
ONLINE_TIMEOUT = 15
MAX_BODY = 1024 * 1024
MAX_RESPONSE = 5 * 1024 * 1024
DATA_DIR = os.path.join(os.path.expanduser("~"), ".textrelay")
OUTBOX_FILE = os.path.join(DATA_DIR, "outbox")
PAGE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "index.html")
PC_NAME = socket.gethostname() or "电脑"
PC_ID = None
SYNC_PORT = None
store = None  # Initialized by main, never write user data merely by importing this module.
devices = {}  # (ip, port) -> id/name/last; every endpoint participates in synchronization.
manual_devices = []
lock = threading.RLock()
sync_lock = threading.Lock()
sync_wakeup = threading.Event()
own_ips_cache = (set(), 0.0)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


# LAN traffic must not go through a system HTTP proxy or VPN web proxy.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)


def is_lan_ipv4(ip):
    try:
        addr = ipaddress.ip_address(ip)
        return (addr.version == 4 and addr.is_private and not addr.is_loopback
                and not addr.is_link_local and not addr.is_multicast and not addr.is_reserved)
    except ValueError:
        return False


def local_ips():
    global own_ips_cache
    now = time.time()
    if now - own_ips_cache[1] > 30:
        try:
            own_ips_cache = (set(socket.gethostbyname_ex(socket.gethostname())[2]), now)
        except OSError:
            own_ips_cache = (set(), now)
    return sorted(own_ips_cache[0])


def lan_ips():
    return [ip for ip in local_ips() if is_lan_ipv4(ip)]


def register_device(ip, info, port=None):
    if not isinstance(info, dict) or not is_lan_ipv4(ip) or ip in local_ips():
        return False
    if info.get("app", APP_TAG) != APP_TAG or not info.get("id") or info["id"] == PC_ID:
        return False
    try:
        port = int(port if port is not None else info.get("port", 24680))
    except (ValueError, TypeError):
        return False
    if not 1 <= port <= 65535:
        return False
    endpoint = (ip, port)
    with lock:
        new = endpoint not in devices
        # One physical device can advertise through multiple network adapters.
        for old in list(devices):
            if devices[old]["id"] == info["id"] and old != endpoint:
                del devices[old]
        name = str(info.get("name", "设备"))[:64]
        devices[endpoint] = {"id": str(info["id"]), "name": name, "last": time.time()}
    if new:
        # Windows consoles may use GBK; an unencodable device name must not kill discovery.
        line = f"[OK] 发现设备：{name} ({ip}:{port})"
        encoding = getattr(sys.stdout, "encoding", None) or "utf-8"
        print(line.encode(encoding, errors="replace").decode(encoding))
        sync_wakeup.set()
    return True


def active_targets():
    with lock:
        online = [endpoint for endpoint, info in devices.items()
                  if time.time() - info["last"] < ONLINE_TIMEOUT]
        return list(dict.fromkeys(online + list(manual_devices)))


def known_targets():
    # Probe previously seen peers too, so temporary UDP loss cannot strand a device.
    with lock:
        return list(dict.fromkeys(list(devices) + list(manual_devices)))


def request(endpoint, path, payload=None, timeout=4):
    ip, port = endpoint
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"http://{ip}:{port}{path}", data=data,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    with _opener.open(req, timeout=timeout) as response:
        result = response.read(MAX_RESPONSE + 1)
    if len(result) > MAX_RESPONSE:
        raise ValueError("response too large")
    return json.loads(result)


def refresh_device(endpoint):
    try:
        info = request(endpoint, "/api/info", timeout=1.5)
        return register_device(endpoint[0], info, endpoint[1])
    except (OSError, ValueError, TypeError):
        return False


def discover_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", BEACON_PORT))
    except OSError as error:
        print(f"[!] UDP 发现不可用，继续使用网段扫描：{error}")
        sock.close()
        return
    for ip in lan_ips() or ["0.0.0.0"]:
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                            socket.inet_aton(MULTICAST_GROUP) + socket.inet_aton(ip))
        except OSError:
            pass
    while True:
        try:
            data, addr = sock.recvfrom(8192)
            info = json.loads(data.decode("utf-8"))
            if isinstance(info, dict) and info.get("app") == APP_TAG:
                register_device(addr[0], info)
        except (OSError, ValueError, TypeError):
            continue


def beacon_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    while True:
        messages = store.snapshot()
        payload = json.dumps({"app": APP_TAG, "v": 1, "id": PC_ID, "name": PC_NAME,
                              "port": SYNC_PORT, "latest": max((m["ts"] for m in messages), default=0)}).encode()
        targets = {"255.255.255.255", MULTICAST_GROUP}
        targets.update(".".join(ip.split(".")[:3]) + ".255" for ip in lan_ips())
        for target in targets:
            try:
                sock.sendto(payload, (target, BEACON_PORT))
            except OSError:
                pass
        time.sleep(3)


def scan_subnet(base):
    # Both the Android default port and the PC's dedicated LAN port. Never stop at
    # the first match; a connected device is not evidence that discovery is complete.
    own = set(local_ips())
    candidates = [(f"{base}.{i}", port) for i in range(1, 255)
                  if f"{base}.{i}" not in own for port in (24680, 24682)]
    def probe(endpoint):
        try:
            with socket.create_connection(endpoint, timeout=0.35):
                pass
            refresh_device(endpoint)
        except OSError:
            pass
    with ThreadPoolExecutor(max_workers=32) as pool:
        list(pool.map(probe, candidates))


def scan_once():
    for base in sorted({ip.rsplit(".", 1)[0] for ip in lan_ips()}):
        scan_subnet(base)


def scan_loop():
    while True:
        try:
            scan_once()
        except OSError as error:
            print(f"[!] 扫描稍后重试：{error}")
        time.sleep(30)


def push_messages(endpoint, messages):
    # Bound batches by encoded bytes, rather than dropping all but the newest N.
    batch, size = [], 2
    for message in messages:
        encoded_size = len(json.dumps(message, ensure_ascii=False).encode("utf-8")) + 2
        if batch and size + encoded_size > MAX_BODY:
            request(endpoint, "/push", batch)
            batch, size = [], 2
        batch.append(message)
        size += encoded_size
    if batch:
        request(endpoint, "/push", batch)


def broadcast(messages):
    def push(endpoint):
        try:
            push_messages(endpoint, messages)
            return True
        except (OSError, ValueError):
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        return sum(pool.map(push, active_targets()))


def sync_once():
    if not sync_lock.acquire(blocking=False):
        return
    try:
        targets = known_targets()
        def exchange(endpoint):
            # Pull and push independently: a failed read must not suppress sending.
            refresh_device(endpoint)
            try:
                store.merge(request(endpoint, "/api/messages?since=0"))
            except (OSError, ValueError, TypeError):
                pass
            try:
                push_messages(endpoint, store.snapshot())
            except (OSError, ValueError):
                pass
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(exchange, targets))
    finally:
        sync_lock.release()


def sync_loop():
    while True:
        sync_wakeup.wait(20)
        sync_wakeup.clear()
        try:
            sync_once()
        except Exception as error:
            print(f"[!] 同步稍后重试：{error}")
        # Coalesce arrivals from multiple devices; don't launch a sync per beacon.
        time.sleep(1)


class RelayHandler(BaseHTTPRequestHandler):
    lan_only = False

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_DELETE(self):
        self.handle_request("DELETE")

    def log_message(self, fmt, *args):
        pass

    def respond(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BODY:
            raise ValueError("invalid body size")
        return json.loads(self.rfile.read(length))

    def handle_request(self, method):
        self.connection.settimeout(6)
        try:
            self.route(method)
        except (ValueError, TypeError, KeyError) as error:
            self.respond({"error": str(error)}, 400)
        except OSError:
            # In particular: no success response when persistence fails.
            self.respond({"error": "storage or connection unavailable"}, 503)

    def route(self, method):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        if self.lan_only and (method, path) not in {
                ("GET", "/api/info"), ("GET", "/api/messages"), ("POST", "/push")}:
            self.respond({"error": "not found"}, 404)
        elif method == "GET" and path in ("/", "/index.html"):
            with open(PAGE_FILE, "rb") as source:
                data = source.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif method == "GET" and path == "/api/info":
            self.respond({"app": APP_TAG, "id": PC_ID, "name": PC_NAME, "port": SYNC_PORT, "ips": lan_ips()})
        elif method == "GET" and path == "/__relay/status":
            with lock:
                peers = [{"id": d["id"], "name": d["name"], "ip": ip, "port": port,
                          "online": time.time() - d["last"] < ONLINE_TIMEOUT}
                         for (ip, port), d in sorted(devices.items())]
            online = [p for p in peers if p["online"]]
            self.respond({"ok": True, "id": PC_ID, "connected": bool(online),
                          "device": "、".join(p["name"] for p in online), "devices": peers,
                          "onlineCount": len(online)})
        elif method == "GET" and path == "/api/messages/count":
            with store.lock:
                count = len(store.snapshot())
                self.respond({"rev": store.rev, "count": count})
        elif method == "GET" and path == "/api/messages":
            since = int(urllib.parse.parse_qs(parsed.query).get("since", [0])[0])
            self.respond(store.snapshot(since))
        elif method == "POST" and path == "/push":
            added = store.merge(self.read_json())
            if added:
                sync_wakeup.set()
            self.respond(added)
        elif method == "POST" and path == "/api/send":
            body = self.read_json()
            text = body.get("text", "") if isinstance(body, dict) else ""
            if not isinstance(text, str) or not text.strip():
                raise ValueError("empty text")
            message = store.create(text.strip(), PC_NAME)
            sync_wakeup.set()
            delivered = broadcast([message])
            self.respond({"ok": True, "delivered": delivered, "message": message})
        elif method == "DELETE" and path == "/api/messages":
            store.delete()
            self.respond({"ok": True})
        elif method == "DELETE" and path.startswith("/api/messages/"):
            store.delete(urllib.parse.unquote(path.rsplit("/", 1)[-1]))
            self.respond({"ok": True})
        else:
            self.respond({"error": "not found"}, 404)


class SyncHandler(RelayHandler):
    lan_only = True


ProxyHandler = RelayHandler  # Existing launchers still serve the local web UI.


def main():
    global store, PC_ID, SYNC_PORT
    parser = argparse.ArgumentParser(description="文字互传 · 多设备 PC 节点")
    parser.add_argument("--phone", action="append", default=[], metavar="IP[:端口]", help="手动添加设备，可多次指定")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    for value in args.phone:
        ip, _, port = value.partition(":")
        try:
            endpoint = (ip.strip(), int(port) if port else 24680)
            if not is_lan_ipv4(endpoint[0]) or not 1 <= endpoint[1] <= 65535:
                raise ValueError()
            manual_devices.append(endpoint)
        except ValueError:
            parser.error(f"无效的设备地址：{value}")
    # Bind before touching storage: a second instance must not open the same database.
    try:
        server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), RelayHandler)
    except OSError as error:
        print(f"[!] 本机端口已占用：{error}，请检查文字互传是否已经运行。")
        return 1
    sync_server = None
    try:
        for port in range(24682, 24691):
            try:
                sync_server = ThreadingHTTPServer(("0.0.0.0", port), SyncHandler)
                SYNC_PORT = port
                break
            except OSError:
                continue
        if sync_server is None:
            raise OSError("无法启动局域网同步端口 24682–24690")
        store = MessageStore(os.path.join(DATA_DIR, "messages.json"))
        PC_ID = store.device_id
        legacy = outbox_store.load(OUTBOX_FILE)
        if legacy:
            store.merge(legacy)
            outbox_store.save([], OUTBOX_FILE)
        threading.Thread(target=sync_server.serve_forever, daemon=True).start()
        for worker in (discover_loop, beacon_loop, scan_loop, sync_loop):
            threading.Thread(target=worker, daemon=True).start()
        print(f"文字互传已启动：http://{LISTEN_HOST}:{LISTEN_PORT} · 同步端口 {SYNC_PORT}")
        print("所有设备共同同步；离线内容保留 24 小时。按 Ctrl+C 退出。")
        if not args.no_browser:
            threading.Timer(1, lambda: webbrowser.open(f"http://{LISTEN_HOST}:{LISTEN_PORT}")).start()
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")
    finally:
        server.server_close()
        if sync_server:
            sync_server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
