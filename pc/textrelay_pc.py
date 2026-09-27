#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文字互传 · PC 端小工具（仅标准库，无需 pip 安装任何东西）

作用：
  1. 通过 UDP 信标自动发现局域网内打开了「文字互传」APP 的手机
  2. 在本机启动 http://127.0.0.1:24680 ，把网页请求转发给手机
  3. 自动用浏览器打开，电脑上永远只需要记住 127.0.0.1:24680

安全边界：
  - 只监听 127.0.0.1，局域网其他机器无法访问本代理
  - 转发目标只接受「本工具自己通过 UDP 信标发现」的私网 IPv4 设备
  - 只转发文字互传 APP 的固定接口（白名单），禁用重定向，限制包体大小

用法：
  python textrelay_pc.py           # 启动并自动打开浏览器
  python textrelay_pc.py --no-browser
"""

import ipaddress
import json
import socket
import sys
import threading
import time
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 24680   # 本机网页端口
BEACON_PORT = 24681   # 与手机 APP 相同的发现端口
APP_TAG = "textrelay"
ONLINE_TIMEOUT = 15   # 超过 15 秒没有信标视为离线
MAX_BODY = 1 * 1024 * 1024      # 请求体上限 1MB
MAX_RESPONSE = 5 * 1024 * 1024  # 响应体上限 5MB

# 只转发文字互传 APP 的接口
ALLOWED_PATHS = {"/", "/index.html", "/api/info", "/api/messages", "/api/send", "/push"}

devices = {}          # ip -> {"name":..., "port":..., "last":...}
lock = threading.Lock()
current = None        # 当前转发目标 (ip, port)


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
                now = time.time()
                online = [k for k, v in devices.items() if now - v["last"] < ONLINE_TIMEOUT]
                if online:
                    best = max(online, key=lambda k: devices[k]["last"])
                    target = (best, devices[best]["port"])
                    if target != current:
                        current = target
                        d = devices[best]
                        print(f"[✓] 已连接手机：{d['name']} ({best}:{d['port']})")
        except OSError:
            continue


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")

    def log_message(self, fmt, *args):
        pass  # 静默普通请求日志

    def _proxy(self, method):
        path = self.path.split("?", 1)[0]
        if path not in ALLOWED_PATHS:
            self._error_page(404, "接口不存在")
            return
        with lock:
            target = current
        if not target:
            self._error_page(
                503,
                "尚未发现手机\n\n请检查：\n"
                "1. 手机与电脑连的是同一个 Wi-Fi\n"
                "2. 手机上已打开「文字互传」APP\n"
                "3. 若弹出 Windows 防火墙提示，请勾选「专用网络」并允许",
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
    threading.Thread(target=discover_loop, daemon=True).start()
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), ProxyHandler)
    print("=" * 52)
    print("文字互传 · PC 端已启动")
    print(f"浏览器地址：http://{LISTEN_HOST}:{LISTEN_PORT}")
    print("（手机上需已打开「文字互传」APP；Ctrl+C 退出）")
    print("=" * 52)
    if "--no-browser" not in sys.argv:
        threading.Timer(
            1.0, lambda: webbrowser.open(f"http://{LISTEN_HOST}:{LISTEN_PORT}")
        ).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")


if __name__ == "__main__":
    main()
