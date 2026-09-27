import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import textrelay_pc as relay
from message_store import MessageStore


def message(key, age=0):
    return {"id": key, "sid": key[0], "name": "测试设备", "text": key,
            "ts": int(time.time() * 1000) - age}


class MultiDeviceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.servers = []
        relay.devices.clear()
        relay.manual_devices.clear()
        relay.store = self.new_store("local")
        relay.PC_ID = relay.store.device_id
        relay.sync_wakeup.clear()

    def tearDown(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()
        self.temp.cleanup()

    def new_store(self, name):
        return MessageStore(os.path.join(self.temp.name, name + ".json"))

    def serve(self, handler):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server.server_address

    def node(self, name):
        node_store = self.new_store(name)
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/api/info":
                    data = {"app": "textrelay", "id": node_store.device_id, "name": name}
                else:
                    data = node_store.snapshot()
                self.reply(data)

            def do_POST(self):
                self.reply(node_store.merge(json.loads(self.rfile.read(int(self.headers["Content-Length"])))))

            def reply(self, data):
                data = json.dumps(data).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass
        return self.serve(Handler), node_store

    def test_three_writers_converge_with_old_messages_and_large_history(self):
        # Local PC plus two network devices. A recent message on A must not hide
        # B's older, previously unseen history; old code truncated at 200/300.
        endpoint_a, a = self.node("a")
        endpoint_b, b = self.node("b")
        a.merge([message("a-new")])
        b.merge([message(f"b-{i}", 3600_000) for i in range(450)])
        relay.store.merge([message("local")])
        relay.manual_devices[:] = [endpoint_a, endpoint_b]
        relay.sync_once()
        relay.sync_once()
        ids = {m["id"] for m in relay.store.snapshot()}
        self.assertEqual(452, len(ids))
        self.assertEqual(ids, {m["id"] for m in a.snapshot()})
        self.assertEqual(ids, {m["id"] for m in b.snapshot()})
        self.assertEqual(452, len(MessageStore(relay.store.path).snapshot()))

    def test_offline_peer_catches_up_without_clearing_other_history(self):
        endpoint_a, a = self.node("a")
        endpoint_b, b = self.node("b")
        relay.manual_devices[:] = [endpoint_a]
        relay.store.create("离线前发送", "电脑")
        relay.sync_once()
        self.assertEqual(0, len(b.snapshot()))
        relay.manual_devices.append(endpoint_b)
        relay.sync_once()
        self.assertEqual(relay.store.snapshot(), b.snapshot())
        self.assertEqual(relay.store.snapshot(), a.snapshot())

    def test_failed_peer_does_not_block_healthy_peer(self):
        endpoint, other = self.node("healthy")
        relay.manual_devices[:] = [("127.0.0.1", 1), endpoint]
        relay.store.create("继续发送", "电脑")
        relay.sync_once()
        self.assertEqual(relay.store.snapshot(), other.snapshot())

    def test_concurrent_writers_and_local_delete_survive_restart(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda i: relay.store.merge([message(str(i))]), range(80)))
        self.assertEqual(80, len(relay.store.snapshot()))
        removed = relay.store.snapshot()[0]
        relay.store.delete(removed["id"])
        restored = MessageStore(relay.store.path)
        restored.merge([removed])
        self.assertEqual(79, len(restored.snapshot()))
        original_id = restored.device_id
        restored.delete()
        restored = MessageStore(relay.store.path)
        self.assertEqual(original_id, restored.device_id)
        self.assertEqual([], restored.snapshot())

    def test_discovery_keeps_all_devices_and_scans_while_connected(self):
        with patch.object(relay, "local_ips", return_value=["192.168.1.2"]):
            for i in range(3, 6):
                self.assertTrue(relay.register_device(f"192.168.1.{i}",
                    {"id": str(i), "name": str(i), "port": 24680}))
            self.assertEqual(3, len(relay.active_targets()))
            relay.manual_devices.append(("192.168.1.9", 24680))
            with patch.object(relay, "scan_subnet") as scan:
                relay.scan_once()
                scan.assert_called_once_with("192.168.1")

    def test_scan_registers_every_hit(self):
        with patch.object(relay, "local_ips", return_value=["192.168.1.2"]), \
                patch.object(relay.socket, "create_connection") as connect, \
                patch.object(relay, "refresh_device") as refresh:
            relay.scan_subnet("192.168.1")
            self.assertEqual(506, refresh.call_count)
            self.assertEqual(506, connect.call_count)

    def test_lan_push_is_durable_accepts_both_formats_and_forbids_delete(self):
        endpoint = self.serve(relay.SyncHandler)
        one = message("single")
        self.assertEqual(1, relay.request(endpoint, "/push", one))
        self.assertEqual(1, relay.request(endpoint, "/push", [message("batch")]))
        self.assertEqual(2, len(MessageStore(relay.store.path).snapshot()))
        self.assertEqual(2, len(relay.request(endpoint, "/api/messages?since=0")))
        with self.assertRaises(urllib.error.HTTPError) as error:
            req = urllib.request.Request(f"http://{endpoint[0]}:{endpoint[1]}/api/messages", method="DELETE")
            relay._opener.open(req)
        self.assertEqual(404, error.exception.code)
        with self.assertRaises(urllib.error.HTTPError) as error:
            relay.request(endpoint, "/push", {"invalid": True})
        self.assertEqual(400, error.exception.code)

    def test_failed_persistence_is_not_acknowledged(self):
        endpoint = self.serve(relay.SyncHandler)
        with patch.object(relay.store, "_save", side_effect=OSError("disk full")):
            with self.assertRaises(urllib.error.HTTPError) as error:
                relay.request(endpoint, "/push", [message("not-saved")])
        self.assertEqual(503, error.exception.code)
        self.assertEqual([], relay.store.snapshot())

    def test_local_api_shows_all_peers_and_owns_its_messages(self):
        endpoint = self.serve(relay.RelayHandler)
        with patch.object(relay, "local_ips", return_value=[]):
            for i in range(3):
                relay.register_device(f"192.168.1.{i+3}", {"id": str(i), "name": str(i)})
        status = relay.request(endpoint, "/__relay/status")
        self.assertEqual(3, status["onlineCount"])
        with patch.object(relay, "broadcast", return_value=3):
            sent = relay.request(endpoint, "/api/send", {"text": "多设备"})
        self.assertEqual(3, sent["delivered"])
        self.assertEqual(relay.PC_ID, sent["message"]["sid"])
        self.assertEqual(1, relay.request(endpoint, "/api/messages/count")["count"])

    def test_retention_and_duplicate_ids(self):
        relay.store.merge([message(str(i)) for i in range(1100)])
        self.assertEqual(1000, len(relay.store.snapshot()))
        relay.store.merge(relay.store.snapshot())
        relay.store.merge([message("expired", 25 * 3600_000)])
        self.assertEqual(1000, len(relay.store.snapshot()))


if __name__ == "__main__":
    unittest.main()
