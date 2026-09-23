"""仅用环回 HTTP 服务验证双节点单次检查和独立飞书通知协议。"""
import base64
from contextlib import contextmanager, redirect_stdout
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
from io import StringIO
import json
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[3] / "scripts" / "check-font-nodes.py"
spec = importlib.util.spec_from_file_location("font_nodes", MODULE)
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)
TARGET = "a" * 64
OTHER = "b" * 64


def health(ready=True, build=TARGET, enabled=True):
    """构造生产健康字段形状，不引入真实凭据。"""
    return {"data": {"status": "UP", "portfolioFonts": {
        "enabled": enabled, "ready": ready, "buildId": build,
        "reasonCodes": [] if ready else ["TOOL_UNAVAILABLE"]}}}


@contextmanager
def local_server(routes):
    """测试退出后关闭监听，不保留后台监控任务。"""
    received = []

    class Handler(BaseHTTPRequestHandler):
        """记录实际收到的方法、路径和通知正文，不打印 HTTP 日志。"""
        def do_GET(self):
            """健康查询读取预设响应。"""
            self.respond()

        def do_POST(self):
            """飞书通知读取预设响应。"""
            self.respond()

        def respond(self):
            """返回可控 JSON、错误、重定向或延迟。"""
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length else b""
            received.append((self.command, self.path, json.loads(body) if body else None))
            status, data, delay = routes.get(self.path, (404, {}, 0))
            time.sleep(delay)
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Location", "/redirect-target")
            self.end_headers()
            try:
                self.wfile.write(json.dumps(data).encode())
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *_):
            """测试中不输出路径或通知参数。"""
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", received
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=1)


class FontNodesMonitorTest(unittest.TestCase):
    """显式双节点结果、通知签名和错误不重试的行为测试。"""

    def run_check(self, base, extra=(), env=None):
        """捕获唯一结构化报告并保留真实 HTTP 路径。"""
        output = StringIO()
        args = ["--health-url", base + "/one", "--health-url", base + "/two",
                "--expected-build-id", TARGET, "--timeout", "0.3", *extra]
        with redirect_stdout(output):
            status = monitor.main(args, {} if env is None else env)
        return status, json.loads(output.getvalue()), output.getvalue()

    def test_two_matching_nodes_are_healthy_and_do_not_notify(self):
        """--notify 在健康状态不发送消息，各节点仅查询一次。"""
        routes = {"/one": (200, health(), 0), "/two": (200, health(), 0), "/alert": (200, {"code": 0}, 0)}
        with local_server(routes) as (base, received):
            status, report, _ = self.run_check(base, ["--notify"], {"FONT_ALERT_WEBHOOK_URL": base + "/alert"})
            self.assertEqual(status, 0)
            self.assertEqual(report["readyNodes"], 2)
            self.assertEqual([(method, path) for method, path, _ in received], [("GET", "/one"), ("GET", "/two")])

    def test_build_drift_and_insufficient_nodes_are_reported_without_auto_actions(self):
        """漂移即便放宽最小数量也失败；结果不输出端点地址。"""
        routes = {"/one": (200, health(), 0), "/two": (200, health(build=OTHER), 0)}
        with local_server(routes) as (base, received):
            status, report, output = self.run_check(base, ["--min-ready-nodes", "1"])
            self.assertEqual(status, 1)
            self.assertIn("NODE_BUILD_DRIFT", report["reasonCodes"])
            self.assertIn("FONT_BUILD_MISMATCH", report["reasonCodes"])
            self.assertNotIn(base, output)
            self.assertEqual(len(received), 2)

    def test_minimum_ready_nodes_and_explicit_prevalidation_mode(self):
        """节点数量门槛有实际效果，关闭阶段须显式允许才计入就绪数量。"""
        routes = {"/one": (200, health(), 0), "/two": (200, health(ready=False), 0)}
        with local_server(routes) as (base, _):
            status, report, _ = self.run_check(base)
            self.assertEqual(status, 1)
            self.assertIn("READY_NODES_INSUFFICIENT", report["reasonCodes"])
            self.assertEqual(self.run_check(base, ["--min-ready-nodes", "1"])[0], 0)
        routes = {"/one": (200, health(enabled=False), 0), "/two": (200, health(enabled=False), 0)}
        with local_server(routes) as (base, _):
            self.assertEqual(self.run_check(base)[0], 1)
            self.assertEqual(self.run_check(base, ["--allow-disabled"])[0], 0)

    def test_errors_redirects_and_timeouts_do_not_retry(self):
        """任一节点失败仍检查另一节点，不重试、不跟随跳转。"""
        for status, delay in ((503, 0), (302, 0), (200, 0.15)):
            routes = {"/one": (status, health(), delay), "/two": (200, health(), 0)}
            with self.subTest(status=status, delay=delay), local_server(routes) as (base, received):
                result, report, _ = self.run_check(base, ["--timeout", "0.05"])
                self.assertEqual(result, 1)
                self.assertEqual(report["readyNodes"], 1)
                self.assertEqual([(method, path) for method, path, _ in received], [("GET", "/one"), ("GET", "/two")])

    def test_explicit_notification_is_signed_once_with_independent_secret(self):
        """签名与现有飞书协议一致；健康失败退出码不因通知成功而清零。"""
        routes = {"/one": (200, health(ready=False), 0), "/two": (200, health(), 0), "/alert": (200, {"code": 0}, 0)}
        with local_server(routes) as (base, received), patch.object(monitor.time, "time", return_value=123456):
            env = {"FONT_ALERT_WEBHOOK_URL": base + "/alert", "FONT_ALERT_WEBHOOK_SECRET": "font-test-secret"}
            status, report, output = self.run_check(base, ["--notify"], env)
            self.assertEqual(status, 1)
            self.assertTrue(report["notification"]["delivered"])
            alerts = [body for method, path, body in received if method == "POST" and path == "/alert"]
            self.assertEqual(len(alerts), 1)
            expected = base64.b64encode(hmac.new(b"123456\nfont-test-secret", b"", hashlib.sha256).digest()).decode()
            self.assertEqual(alerts[0]["sign"], expected)
            self.assertEqual(alerts[0]["timestamp"], "123456")
            self.assertNotIn("font-test-secret", output)
            self.assertNotIn(base, output)

    def test_no_fallback_credentials_and_failed_notification_is_not_retried(self):
        """不用反馈机器人变量兜底，远端拒绝或超时只报告一次通知失败。"""
        routes = {"/one": (200, health(ready=False), 0), "/two": (200, health(), 0)}
        with local_server(routes) as (base, received):
            _, report, _ = self.run_check(base, ["--notify"], {"FEISHU_FEEDBACK_WEBHOOK_URL": base + "/alert"})
            self.assertEqual(report["notification"]["reasonCode"], "ALERT_CONFIG_MISSING")
            self.assertEqual(len(received), 2)
        for status, payload, delay in ((200, {"code": 1001}, 0), (201, {"code": 0}, 0), (500, {}, 0), (302, {}, 0), (200, {"code": 0}, 0.15)):
            current = dict(routes, **{"/alert": (status, payload, delay)})
            with self.subTest(status=status, payload=payload, delay=delay), local_server(current) as (base, received):
                _, report, _ = self.run_check(base, ["--notify", "--timeout", "0.05"], {"FONT_ALERT_WEBHOOK_URL": base + "/alert"})
                self.assertFalse(report["notification"]["delivered"])
                self.assertEqual(sum(method == "POST" for method, _, _ in received), 1)

    def test_non_http_notification_address_is_rejected_without_outputting_secret(self):
        """通知地址不能读取本地文件或将凭据写入异常输出。"""
        routes = {"/one": (200, health(ready=False), 0), "/two": (200, health(), 0)}
        with local_server(routes) as (base, received):
            _, report, output = self.run_check(base, ["--notify"], {"FONT_ALERT_WEBHOOK_URL": "file:///tmp/private-secret"})
            self.assertFalse(report["notification"]["delivered"])
            self.assertNotIn("private-secret", output)
            self.assertEqual(len(received), 2)

    def test_malformed_diagnostics_do_not_leak_response_content(self):
        """不回显无效构建身份或非原因码文本。"""
        invalid = health(ready=False, build="sensitive-test-value")
        invalid["data"]["portfolioFonts"]["reasonCodes"] = ["https://private.invalid/secret", "SOURCE_UNAVAILABLE"]
        with local_server({"/one": (200, invalid, 0), "/two": (200, health(), 0)}) as (base, _):
            status, report, output = self.run_check(base)
            self.assertEqual(status, 1)
            self.assertIn("SOURCE_UNAVAILABLE", report["reasonCodes"])
            self.assertNotIn("sensitive-test-value", output)
            self.assertNotIn("private.invalid", output)


if __name__ == "__main__":
    unittest.main()
