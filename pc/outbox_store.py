#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文字互传 · 离线发件箱存储（供 textrelay_pc.py 使用）

用标准库 dbm.dumb 键值库做持久化（固定位于 家目录/.textrelay/outbox），
消息整体覆写保存；手机上线后由主程序读取并送达，送达成功即清空。
"""

import dbm.dumb
import json
import os
import threading
import time
import uuid

_lock = threading.Lock()
_MAX = 500


def load(file_path: str) -> list:
    """读取全部暂存消息（按时间排序，容忍坏记录）"""
    with _lock:
        items = []
        try:
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            db = dbm.dumb.open(file_path, "c")
            try:
                for key in db.keys():
                    try:
                        items.append(json.loads(db[key].decode("utf-8")))
                    except Exception:
                        pass
            finally:
                db.close()
        except Exception:
            pass
        items.sort(key=lambda m: m.get("ts", 0))
        return items[-_MAX:]


def save(items: list, file_path: str) -> None:
    """整体覆写暂存库"""
    with _lock:
        try:
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            db = dbm.dumb.open(file_path, "n")
            try:
                for m in items[-_MAX:]:
                    key = str(m.get("id", "")).encode("utf-8")
                    db[key] = json.dumps(m, ensure_ascii=False).encode("utf-8")
            finally:
                db.close()
        except Exception as e:
            print(f"[!] 暂存写入失败：{e}")


def append(text: str, sender_name: str, file_path: str) -> int:
    """追加一条待发消息，返回当前暂存条数"""
    msg = {
        "id": uuid.uuid4().hex,
        "sid": "pc",
        "name": sender_name,
        "text": text,
        "ts": int(time.time() * 1000),
    }
    items = load(file_path)
    items.append(msg)
    save(items, file_path)
    return len(items)
