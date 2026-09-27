"""Durable multi-writer store with shared deletion state and local device settings."""
import json
import os
import threading
import time
import uuid

MAX_MESSAGES = 1000
MAX_AGE_MS = 24 * 3600 * 1000


class MessageStore:
    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.messages = {}
        self.deleted = {}
        self.cleared_before = 0
        self.shared_deleted = {}
        self.shared_cleared_before = 0
        self.device_name = ""
        self.manual_peers = []
        self.device_id = "pc-" + uuid.uuid4().hex
        self.rev = time.time_ns() // 1_000_000  # A restart must invalidate the browser cache.
        if os.path.exists(path):
            with open(path, encoding="utf-8") as source:
                data = json.load(source)
            self.device_id = data["device_id"]
            self.device_name = data.get("device_name", "")
            self.manual_peers = data.get("manual_peers", [])
            self.shared_deleted = data.get("shared_deleted", {})
            self.shared_cleared_before = data.get("shared_cleared_before", 0)
            self.cleared_before = data.get("cleared_before", 0)
            self.deleted = data.get("deleted", {})
            self.messages = {m["id"]: m for m in data.get("messages", [])
                             if m["id"] not in self.deleted and m["id"] not in self.shared_deleted
                             and m["ts"] > max(self.cleared_before, self.shared_cleared_before)}
        self._prune()
        self._save()

    def _prune(self):
        cutoff = int(time.time() * 1000) - MAX_AGE_MS
        retained = sorted((m for m in self.messages.values() if m["ts"] >= cutoff),
                          key=lambda m: (m["ts"], m["id"]))[-MAX_MESSAGES:]
        changed = len(retained) != len(self.messages)
        self.messages = {m["id"]: m for m in retained}
        self.deleted = {key: ts for key, ts in self.deleted.items() if ts >= cutoff}
        return changed

    def _save(self):
        os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
        temp = self.path + ".tmp"
        with open(temp, "w", encoding="utf-8") as target:
            json.dump({"device_id": self.device_id, "device_name": self.device_name, "manual_peers": self.manual_peers,
                       "shared_deleted": self.shared_deleted, "shared_cleared_before": self.shared_cleared_before,
                       "messages": list(self.messages.values()),
                       "deleted": self.deleted, "cleared_before": self.cleared_before},
                      target, ensure_ascii=False)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temp, self.path)

    def _commit(self):
        self._save()  # Never acknowledge a network push before durable storage succeeds.
        self.rev += 1

    def snapshot(self, since=0):
        with self.lock:
            if self._prune():
                self._commit()
            return [dict(m) for m in sorted(self.messages.values(), key=lambda m: (m["ts"], m["id"]))
                    if m["ts"] > since]

    def merge(self, items):
        if isinstance(items, dict):
            items = [items]
        if not isinstance(items, list):
            raise ValueError("expected a message or message array")
        # Validate the complete batch before accepting any of it.
        for m in items:
            if (not isinstance(m, dict) or
                    any(not isinstance(m.get(k), str) for k in ("id", "sid", "text")) or
                    not m["id"] or not isinstance(m.get("ts"), int) or
                    isinstance(m["ts"], bool) or not isinstance(m.get("name", "?"), str)):
                raise ValueError("invalid message")
        with self.lock:
            before = dict(self.messages)
            added = 0
            cutoff = int(time.time() * 1000) - MAX_AGE_MS
            for m in items:
                if (m["id"] not in self.messages and m["id"] not in self.deleted and m["id"] not in self.shared_deleted and
                        m["ts"] > self.cleared_before and m["ts"] >= cutoff):
                    self.messages[m["id"]] = {k: m[k] for k in ("id", "sid", "text", "ts")}
                    self.messages[m["id"]]["name"] = m.get("name", "?")
                    added += 1
            pruned = self._prune()
            if added or pruned:
                try:
                    self._commit()
                except Exception:
                    self.messages = before
                    raise
            return added

    def create(self, text, name):
        message = {"id": uuid.uuid4().hex, "sid": self.device_id, "name": name,
                   "text": text, "ts": int(time.time() * 1000)}
        self.merge([message])
        return message

    def delete(self, message_id=None):
        with self.lock:
            old_messages, old_deleted, old_cleared = dict(self.messages), dict(self.deleted), self.cleared_before
            old_shared, old_shared_clear = dict(self.shared_deleted), self.shared_cleared_before
            now = int(time.time() * 1000)
            if message_id is None:
                # Keep IDs too: a peer's clock may be ahead of this device's clock.
                self.deleted.update({key: m["ts"] for key, m in self.messages.items()})
                self.shared_deleted.update({key: max(now, m["ts"]) for key, m in self.messages.items()})
                self.messages.clear()
                self.shared_cleared_before = max(self.shared_cleared_before, now)
                self.cleared_before = max(self.cleared_before, self.shared_cleared_before)
            elif message_id in self.messages:
                self.deleted[message_id] = self.messages.pop(message_id)["ts"]
                self.shared_deleted[message_id] = max(now, self.deleted[message_id])
            try:
                self._commit()
            except Exception:
                self.messages, self.deleted, self.cleared_before = old_messages, old_deleted, old_cleared
                self.shared_deleted, self.shared_cleared_before = old_shared, old_shared_clear
                raise

    def rename(self, name):
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 64:
            raise ValueError("设备名称须为 1–64 个字符")
        with self.lock:
            old = self.device_name
            self.device_name = name.strip()
            try:
                self._commit()
            except Exception:
                self.device_name = old
                raise

    def set_manual_peers(self, peers):
        with self.lock:
            old = self.manual_peers
            self.manual_peers = [list(peer) for peer in peers]
            try:
                self._commit()
            except Exception:
                self.manual_peers = old
                raise

    def deletion_state(self):
        with self.lock:
            return {"deleted": dict(self.shared_deleted), "cleared_before": self.shared_cleared_before}

    def merge_deletions(self, state):
        if not isinstance(state, dict) or not isinstance(state.get("deleted", {}), dict):
            raise ValueError("invalid deletion state")
        deleted, cutoff = state.get("deleted", {}), state.get("cleared_before", 0)
        if not isinstance(cutoff, int) or cutoff < 0 or any(
                not isinstance(key, str) or not isinstance(ts, int) or ts < 0 for key, ts in deleted.items()):
            raise ValueError("invalid deletion state")
        with self.lock:
            before = (dict(self.messages), dict(self.deleted), self.cleared_before,
                      dict(self.shared_deleted), self.shared_cleared_before)
            changed = cutoff > self.shared_cleared_before
            self.shared_cleared_before = max(self.shared_cleared_before, cutoff)
            self.cleared_before = max(self.cleared_before, cutoff)
            for key, ts in deleted.items():
                if ts > self.shared_deleted.get(key, 0):
                    self.shared_deleted[key] = ts
                    self.deleted[key] = max(ts, self.deleted.get(key, 0))
                    changed = True
            if changed:
                self.messages = {key: m for key, m in self.messages.items()
                                 if key not in self.shared_deleted and m["ts"] > self.shared_cleared_before}
                try:
                    self._commit()
                except Exception:
                    (self.messages, self.deleted, self.cleared_before,
                     self.shared_deleted, self.shared_cleared_before) = before
                    raise
            return changed
