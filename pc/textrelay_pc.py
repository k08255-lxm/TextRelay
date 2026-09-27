#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文字互传 · PC 端小工具（仅标准库，无需 pip 安装任何东西）

作用：
  1. 通过 UDP 信标自动发现局域网内打开了「文字互传」APP 的手机
  2. 在本机启动 http://127.0.0.1:24680 ，把网页请求转发给手机
  3. 自动用浏览器打开，电脑上永远只需要记住 127.0.0.1:24680
  4. 手机不在线时也能发送：消息暂存在电脑（~/.textrelay/outbox，
     经 outbox_store 持久化），手机一上线自动送达

安全边界：
  - 只监听 127.0.0.1，局域网其他机器无法访问本代理
  - 转发目标只接受「本工具自己通过 UDP 信标发现」的私网 IPv4 设备
  - 只转发文字互传 APP 的固定接口（白名单），禁用重定向，限制包体大小

用法：
  python textrelay_pc.py           # 启动并自动打开浏览器
  python textrelay_pc.py --no-browser
"""

import argparse
import hashlib
import ipaddress
import json
import os
import socket
import sys
import threading
import time
import urllib.request
import uuid
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import outbox_store

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 24680   # 本机网页端口
BEACON_PORT = 24681   # 与手机 APP 相同的发现端口
APP_TAG = "textrelay"
ONLINE_TIMEOUT = 15   # 超过 15 秒没有信标视为离线
MAX_BODY = 1 * 1024 * 1024      # 请求体上限 1MB
MAX_RESPONSE = 5 * 1024 * 1024  # 响应体上限 5MB
OUTBOX_FILE = os.path.join(os.path.expanduser("~"), ".textrelay", "outbox")
PAGE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "index.html")

# 只转发文字互传 APP 的接口（页面与状态由本机直接提供，不依赖手机）
ALLOWED_PATHS = {"/", "/index.html", "/__relay/status", "/api/info", "/api/messages", "/api/send", "/push"}

devices = {}          # ip -> {"name":..., "port":..., "last":...}
lock = threading.RLock()  # 可重入：pick_target 会在持有锁时被再次调用
current = None        # 当前转发目标 (ip, port)，粘性：设备在线期间不切换
OUTBOX_REV = [1_000_000]  # 暂存消息的修订号（与手机端修订号空间隔离，避免切换时撞号）
PC_NAME = socket.gethostname() or "电脑"
manual_devices = []   # --phone 手动指定的 (ip, port)，UDP 不可用时的兜底
MULTICAST_GROUP = "239.255.246.80"
PC_ID = "pc-" + hashlib.sha256(PC_NAME.encode("utf-8")).hexdigest()[:8]


def is_lan_ipv4(ip: str) -> bool:
    """只接受私网 IPv4；拒绝环回/链路本地/组播/保留段"""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return (
        addr.version == 4
        and addr.is_private
        and not addr.is_loopback
        and not addr.is_link_local
        and not addr.is_multicast
        and not addr.is_reserved
    )


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 禁止跟随重定向，目标只可能是白名单接口


_opener = urllib.request.build_opener(NoRedirect)

own_ips_cache = (set(), 0.0)


def local_ips() -> list:
    """本机所有 IPv4（缓存 30 秒，避免频繁 DNS 查询）"""
    global own_ips_cache
    now = time.time()
    if now - own_ips_cache[1] > 30:
        try:
            own_ips_cache = (set(socket.gethostbyname_ex(socket.gethostname())[2]), now)
        except Exception:
            own_ips_cache = (set(), now)
    return sorted(own_ips_cache[0])


def own_ips() -> set:
    """本机所有 IP，用于忽略自己发出的信标回环"""
    return set(local_ips())


def pick_target():
    """最近活跃的在线手机；没有则回退到 --phone 手动指定的设备"""
    now = time.time()
    with lock:
        online = [k for k, v in devices.items() if now - v["last"] < ONLINE_TIMEOUT]
        if online:
            best = max(online, key=lambda k: devices[k]["last"])
            return (best, devices[best]["port"])
    return manual_devices[0] if manual_devices else None


def get_target():
    """粘性目标：当前设备仍在线就沿用（多设备在线不来回切换），失效才切换"""
    global current
    now = time.time()
    with lock:
        info = devices.get(current[0]) if current else None
    if current and info and now - info["last"] < ONLINE_TIMEOUT:
        return current
    t = pick_target()
    if t:
        with lock:
            changed = current != t
            current = t
        if changed:
            d = devices.get(t[0])
            if d:
                print(f"[✓] 已连接手机：{d['name']} ({t[0]}:{t[1]})")
    return t


def discover_loop():
    global current
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", BEACON_PORT))
    except OSError as e:
        print(f"[!] 无法监听 UDP {BEACON_PORT}：{e}")
        print("    可能被防火墙拦截（请允许 Python 访问专用网络）或端口被占用")
        return
    print(f"[*] 正在通过 UDP {BEACON_PORT} 发现手机…")
    while True:
        try:
            data, addr = sock.recvfrom(8192)
            try:
                obj = json.loads(data.decode("utf-8"))
            except Exception:
                continue
            if obj.get("app") != APP_TAG:
                continue
            ip = addr[0]
            # 忽略自己（ID 相同或来源是本机地址——广播可能经虚拟网卡回环）
            if obj.get("id") == PC_ID or ip in own_ips():
                continue
            if not is_lan_ipv4(ip):
                continue
            port = int(obj.get("port", 24680) or 0)
            if not (1 <= port <= 65535):
                continue
            with lock:
                devices[ip] = {
                    "name": str(obj.get("name", "?"))[:64],
                    "port": port,
                    "last": time.time(),
                }
                # 粘性切换：只有当前目标离线（或还没有目标）时才换设备，
                # 避免多设备同时在线时转发目标来回跳
                old = current
                old_info = devices.get(old[0]) if old else None
                old_online = bool(old and old_info and time.time() - old_info["last"] < ONLINE_TIMEOUT)
                if old is None or not old_online:
                    current = pick_target() or current
                changed = current != old and current is not None
            if changed:
                d = devices[current[0]]
                print(f"[✓] 已连接手机：{d['name']} ({current[0]}:{current[1]})")
                threading.Thread(target=flush_outbox, daemon=True).start()
        except OSError:
            continue


def lan_ips():
    """真实私网 IPv4（排除 TUN 虚拟网卡 198.18.0.0/15 等）"""
    return [s for s in local_ips()
            if is_lan_ipv4(s) and not s.startswith("198.18.")]


def fetch_name(ip, port):
    try:
        with _opener.open(f"http://{ip}:{port}/api/info", timeout=1.5) as resp:
            info = json.loads(resp.read().decode("utf-8"))
        if isinstance(info, dict) and info.get("name"):
            return str(info["name"])[:64]
    except Exception:
        pass
    return None


class SyncHandler(BaseHTTPRequestHandler):
    """给手机 APP 当设备用的最小同步端点：拉取返回空、推送直接确认"""

    def do_GET(self):
        data = b"[]"
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        if n:
            self.rfile.read(n)
        data = b"0"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass


def beacon_loop():
    """向局域网广播自己（与手机 APP 相同的信标协议），让手机的设备列表能看到这台电脑"""
    sync_port = None
    for p in range(24682, 24691):
        try:
            srv = ThreadingHTTPServer(("0.0.0.0", p), SyncHandler)
        except OSError:
            continue
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        sync_port = p
        break
    if sync_port is None:
        sync_port = 24680  # 兜底：退化为网页代理端口

    payload = json.dumps({
        "app": APP_TAG, "v": 1, "id": PC_ID, "name": PC_NAME,
        "port": sync_port, "latest": 0,
    }).encode("utf-8")
    ips = lan_ips()
    targets = ["255.255.255.255", MULTICAST_GROUP]
    if ips:
        targets.append(".".join(ips[0].split(".")[:3]) + ".255")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    print(f"[*] 已作为设备广播本机（{PC_NAME}，同步端口 {sync_port}）")
    while True:
        for t in targets:
            try:
                sock.sendto(payload, (t, BEACON_PORT))
            except OSError:
                pass
        time.sleep(3)


def scan_subnet(base: str):
    """UDP 收不到时的兜底：并发探测网段内开启了 24680 端口的主机，返回候选列表"""
    def probe(i):
        ip = f"{base}.{i}"
        try:
            s = socket.create_connection((ip, 24680), timeout=0.5)
            s.close()
            return ip
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=64) as ex:
        return [r for r in ex.map(probe, range(1, 255)) if r]


def scan_loop():
    """手机不在线且 UDP 收不到信标时，周期性扫描网段寻找手机"""
    global current
    while True:
        time.sleep(10)
        with lock:
            connected = any(time.time() - v["last"] < ONLINE_TIMEOUT for v in devices.values())
        if connected or manual_devices:
            continue
        ips = lan_ips()
        if not ips:
            continue
        base = ".".join(ips[0].split(".")[:3])
        hits = scan_subnet(base)[:50]   # TUN 等环境可能产生大量假命中，验证上限 50 个
        if not hits:
            continue

        def validate(hit):
            return (hit, fetch_name(hit, 24680))

        # 并行验证：必须是 /api/info 应答正常的文字互传设备（排除路由器管理页等）
        with ThreadPoolExecutor(max_workers=32) as ex:
            for hit, name in ex.map(validate, hits):
                if not name:
                    continue
                with lock:
                    devices[hit] = {"name": name, "port": 24680, "last": time.time()}
                    current = (hit, 24680)
                print(f"[✓] 扫描发现手机：{name} ({hit}:24680)")
                threading.Thread(target=flush_outbox, daemon=True).start()
                break


def queue_message(text):
    """手机不在线：消息暂存本地，等手机上线后 flush_outbox 送达"""
    msg = outbox_store.append(text, PC_NAME, OUTBOX_FILE)
    OUTBOX_REV[0] += 1
    print(f"[↓] 手机不在线，已暂存第 {len(outbox_store.load(OUTBOX_FILE))} 条消息（上线后自动送达）")
    return msg


def flush_outbox():
    msgs = outbox_store.load(OUTBOX_FILE)
    if not msgs:
        return
    target = get_target()
    if not target:
        return
    ip, port = target
    body = json.dumps(msgs, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"http://{ip}:{port}/push", data=body, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with _opener.open(req, timeout=6) as resp:
            resp.read()
    except Exception as e:
        print(f"[!] 暂存消息送达失败（稍后自动重试）：{e}")
        return
    outbox_store.save([], OUTBOX_FILE)
    OUTBOX_REV[0] += 1
    print(f"[✓] 已把暂存的 {len(msgs)} 条消息送达手机")


def flush_loop():
    while True:
        time.sleep(30)
        try:
            flush_outbox()
        except Exception:
            pass


# ---------- HTTP 代理 ----------

class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")

    def do_DELETE(self):
        self._proxy("DELETE")

    def log_message(self, fmt, *args):
        pass  # 静默普通请求日志

    def _proxy(self, method):
        path = self.path.split("?", 1)[0]
        # /api/messages/<id>（单条删除）允许前缀匹配
        if path not in ALLOWED_PATHS and not path.startswith("/api/messages/"):
            self._error_page(404, "接口不存在")
            return

        # 本地页面与连接状态：不依赖手机，离线也能打开并使用
        if path in ("/", "/index.html") and method == "GET":
            self._serve_page()
            return
        if path == "/__relay/status" and method == "GET":
            self._serve_status()
            return

        # 发送接口：手机不在线或转发失败时，落到本地发件箱
        if path == "/api/send" and method == "POST":
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length > MAX_BODY:
                self._error_page(413, "请求体过大")
                return
            raw = self.rfile.read(length) if length else b""
            try:
                text = str(json.loads(raw.decode("utf-8")).get("text", "")).strip()
            except Exception:
                text = ""
            if not text:
                self._error_page(400, "内容为空")
                return
            if self._forward_send(text):
                return
            msg = queue_message(text)
            self._json_response({"ok": True, "queued": True, "message": msg})
            return

        # 粘性目标（get_target）：动态发现优先、--phone 兜底、多设备在线不抖动
        target = get_target()
        if not target:
            # 手机不在线：暂存箱就是消息列表——列表/计数/清空/单删都作用于它
            if path == "/api/messages" and method == "GET":
                self._json_response(outbox_store.load(OUTBOX_FILE))
                return
            if path == "/api/messages/count" and method == "GET":
                self._json_response({"rev": OUTBOX_REV[0], "count": len(outbox_store.load(OUTBOX_FILE))})
                return
            if path == "/api/messages" and method == "DELETE":
                outbox_store.save([], OUTBOX_FILE)
                OUTBOX_REV[0] += 1
                self._json_response({"ok": True})
                return
            if path.startswith("/api/messages/") and method == "DELETE":
                oid = path.rsplit("/", 1)[-1]
                outbox_store.save(
                    [m for m in outbox_store.load(OUTBOX_FILE) if m.get("id") != oid],
                    OUTBOX_FILE,
                )
                OUTBOX_REV[0] += 1
                self._json_response({"ok": True})
                return
            if method == "DELETE":
                self._json_response({"ok": True})
                return
            self._error_page(
                503,
                "尚未发现手机\n\n请检查：\n"
                "1. 手机与电脑连的是同一个 Wi-Fi\n"
                "2. 手机上已打开「文字互传」APP\n"
                "3. 若弹出 Windows 防火墙提示，请勾选「专用网络」并允许\n"
                "4. 不做操作也可以：本工具会自动扫描网段寻找手机（约十几秒），\n"
                "   或退出后用命令行手动指定：python textrelay_pc.py --phone 192.168.3.40\n\n"
                "提示：发送文字不需要手机在线，会自动暂存，手机上线后送达。",
            )
            return
        ip, port = target
        url = f"http://{ip}:{port}{self.path}"
        body = None
        if method == "POST":
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length > MAX_BODY:
                self._error_page(413, "请求体过大")
                return
            body = self.rfile.read(length) if length else b""
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Content-Type", self.headers.get("Content-Type", "application/json; charset=utf-8"))
        try:
            with _opener.open(req, timeout=6) as resp:
                data = resp.read(MAX_RESPONSE + 1)
                if len(data) > MAX_RESPONSE:
                    self._error_page(502, "响应过大")
                    return
                self.send_response(resp.status)
                self.send_header(
                    "Content-Type",
                    resp.headers.get("Content-Type", "text/html; charset=utf-8"),
                )
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        except Exception as e:
            self._error_page(
                502,
                f"连接手机失败：{e}\n\n手机可能刚锁屏或切了网络，几秒后会自动恢复；"
                "也可刷新本页重试。",
            )

    def _serve_page(self):
        """本机直接提供网页，手机不在线也能打开输入框"""
        try:
            with open(PAGE_FILE, "rb") as f:
                data = f.read()
        except Exception:
            data = ("<!doctype html><meta charset='utf-8'>"
                    "<body style=\"font-family:system-ui;padding:40px\">"
                    "缺少 index.html，请重新获取 PC 端文件</body>").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_status(self):
        target = pick_target()
        device = None
        if target:
            with lock:
                info = devices.get(target[0])
            device = (info or {}).get("name") or f"{target[0]}:{target[1]}"
        self._json_response({"ok": True, "connected": target is not None, "device": device, "id": PC_ID})

    def _forward_send(self, text) -> bool:
        """有手机在线时转发发送；返回是否成功"""
        target = get_target()
        if not target:
            return False
        ip, port = target
        body = json.dumps({"text": text, "sid": PC_ID, "name": PC_NAME}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(f"http://{ip}:{port}/api/send", data=body, method="POST")
        req.add_header("Content-Type", "application/json; charset=utf-8")
        try:
            with _opener.open(req, timeout=6) as resp:
                data = resp.read(MAX_RESPONSE + 1)
        except Exception as e:
            print(f"[!] 发送转发失败，转为暂存：{e}")
            return False
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception:
            pass
        return True

    def _json_response(self, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _error_page(self, code, text):
        data = (
            "<!doctype html><meta charset='utf-8'>"
            "<body style=\"font-family:system-ui;padding:40px;white-space:pre-wrap;"
            "line-height:1.8;color:#333\">" + text + "</body>"
        ).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description="文字互传 · PC 端")
    parser.add_argument("--phone", action="append", default=[], metavar="IP[:端口]",
                        help="手动指定手机地址（UDP 被防火墙/AP隔离拦截时使用），可多次")
    parser.add_argument("--no-browser", action="store_true", help="不自动打开浏览器")
    args = parser.parse_args()
    for phone in args.phone:
        ip, _, port = phone.partition(":")
        try:
            entry = (ip.strip(), int(port) if port else 24680)
        except ValueError:
            print(f"[!] 忽略无效的 --phone 参数：{phone}")
            continue
        if not is_lan_ipv4(entry[0]):
            print(f"[!] 忽略非私网地址：{phone}")
            continue
        manual_devices.append(entry)
        print(f"[i] 手动指定手机：{entry[0]}:{entry[1]}")

    threading.Thread(target=discover_loop, daemon=True).start()
    threading.Thread(target=flush_loop, daemon=True).start()
    threading.Thread(target=beacon_loop, daemon=True).start()
    threading.Thread(target=scan_loop, daemon=True).start()
    try:
        server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), ProxyHandler)
    except OSError as e:
        print(f"[!] 端口 {LISTEN_PORT} 启动失败：{e}")
        print("    可能已经有一个文字互传在运行，直接用浏览器打开 http://127.0.0.1:24680 即可")
        sys.exit(1)
    pending = len(outbox_store.load(OUTBOX_FILE))
    print("=" * 52)
    print("文字互传 · PC 端已启动")
    print(f"浏览器地址：http://{LISTEN_HOST}:{LISTEN_PORT}")
    if pending:
        print(f"有 {pending} 条暂存消息，等手机上线自动送达")
    print("（手机不在线也能发送，会先暂存在电脑；Ctrl+C 退出）")
    print("=" * 52)
    if not args.no_browser:
        threading.Timer(
            1.0, lambda: webbrowser.open(f"http://{LISTEN_HOST}:{LISTEN_PORT}")
        ).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")


if __name__ == "__main__":
    main()
