#!/usr/bin/env python3
"""供既有监控平台单次检查两个字体节点；不修改开关、不切流、不调度、不重试。"""
import argparse
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_RESPONSE_BYTES = 65536
BUILD_ID_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
REASON_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{0,63}\Z")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """检查和通知均不跟随重定向，避免一次检查发出第二次请求。"""
    def redirect_request(self, request, response, code, message, headers, new_url):
        """拒绝默认重定向行为。"""
        return None


def request_json(url, timeout, payload=None):
    """单次有界 HTTP 读取，不输出 URL、凭据或远端正文。"""
    address = urllib.parse.urlsplit(url)
    if address.scheme not in ("http", "https") or not address.hostname or address.username or address.password:
        raise ValueError("检查与通知仅允许不含嵌入凭据的 HTTP 地址")
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError("HTTP 响应状态未确认成功")
        body = response.read(MAX_RESPONSE_BYTES + 1)
        if len(body) > MAX_RESPONSE_BYTES:
            raise ValueError("响应超出检查上限")
        return json.loads(body)


def inspect_node(url, index, expected, timeout, allow_disabled):
    """输出仅含序号、受限构建身份和原因码的节点快照。"""
    node = {"node": index, "enabled": False, "ready": False, "buildId": None, "reasonCodes": []}
    try:
        response = request_json(url, timeout)
    except urllib.error.HTTPError:
        node["reasonCodes"].append("HEALTH_HTTP_ERROR")
        return node
    except (OSError, ValueError, http.client.HTTPException):
        node["reasonCodes"].append("HEALTH_UNREACHABLE_OR_INVALID")
        return node
    data = response.get("data") if isinstance(response, dict) else None
    fonts = data.get("portfolioFonts") if isinstance(data, dict) else None
    if not isinstance(data, dict) or data.get("status") != "UP" or not isinstance(fonts, dict):
        node["reasonCodes"].append("FONT_DIAGNOSTICS_INVALID")
        return node
    node["enabled"] = fonts.get("enabled") is True
    node["ready"] = fonts.get("ready") is True
    build = fonts.get("buildId")
    node["buildId"] = build if isinstance(build, str) and BUILD_ID_PATTERN.fullmatch(build) else None
    if node["buildId"] != expected:
        node["reasonCodes"].append("FONT_BUILD_MISMATCH")
    if not node["ready"]:
        node["reasonCodes"].append("FONT_NOT_READY")
    if not node["enabled"] and not allow_disabled:
        node["reasonCodes"].append("FONT_DISABLED")
    reasons = fonts.get("reasonCodes", [])
    if isinstance(reasons, list):
        node["reasonCodes"].extend(reason for reason in reasons
                                   if isinstance(reason, str) and REASON_PATTERN.fullmatch(reason)
                                   and reason not in node["reasonCodes"])
    return node


def notify_failure(report, timeout, env):
    """显式通知时仅使用独立字体变量，单次发送并核实飞书业务响应。"""
    url = env.get("FONT_ALERT_WEBHOOK_URL")
    if not url:
        return {"attempted": False, "delivered": False, "reasonCode": "ALERT_CONFIG_MISSING"}
    payload = {"msg_type": "text", "content": {"text": "WeFolio 字体双节点检查失败\n" + json.dumps(report, ensure_ascii=False)}}
    secret = env.get("FONT_ALERT_WEBHOOK_SECRET")
    if secret:
        timestamp = str(int(time.time()))
        payload["timestamp"] = timestamp
        payload["sign"] = base64.b64encode(hmac.new((timestamp + "\n" + secret).encode("utf-8"),
                                                   b"", hashlib.sha256).digest()).decode("ascii")
    try:
        result = request_json(url, timeout, payload)
        delivered = isinstance(result, dict) and (type(result.get("code")) is int and result["code"] == 0
                    or type(result.get("StatusCode")) is int and result["StatusCode"] == 0)
        return {"attempted": True, "delivered": delivered,
                "reasonCode": "ALERT_DELIVERED" if delivered else "ALERT_REJECTED"}
    except (OSError, ValueError, http.client.HTTPException):
        return {"attempted": True, "delivered": False, "reasonCode": "ALERT_DELIVERY_FAILED"}


def main(argv=None, env=None):
    """执行一次双节点检查，失败时非零退出；只有 --notify 才发送通知。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--health-url", action="append", required=True, help="分别指定两次节点健康地址")
    parser.add_argument("--expected-build-id", required=True)
    parser.add_argument("--min-ready-nodes", type=int, default=2, choices=(1, 2))
    parser.add_argument("--timeout", type=float, default=2.0, help="单请求超时秒数")
    parser.add_argument("--allow-disabled", action="store_true", help="仅用于关闭状态下的预校验检查")
    parser.add_argument("--notify", action="store_true", help="失败时使用独立 FONT_ALERT_WEBHOOK_URL/SECRET 通知一次")
    args = parser.parse_args(argv)
    if len(args.health_url) != 2 or len(set(args.health_url)) != 2:
        parser.error("必须提供两个不同的节点健康地址")
    if not BUILD_ID_PATTERN.fullmatch(args.expected_build_id) or not 0 < args.timeout <= 30:
        parser.error("构建身份须为 64 位小写十六进制，超时须在 0 到 30 秒之间")
    nodes = [inspect_node(url, index, args.expected_build_id, args.timeout, args.allow_disabled)
             for index, url in enumerate(args.health_url, 1)]
    ready_count = sum(node["ready"] and node["buildId"] == args.expected_build_id
                      and (node["enabled"] or args.allow_disabled) for node in nodes)
    reasons = ["FONT_BUILD_MISMATCH"] if any("FONT_BUILD_MISMATCH" in node["reasonCodes"] for node in nodes) else []
    if ready_count < args.min_ready_nodes:
        reasons = sorted({reason for node in nodes for reason in node["reasonCodes"]})
        reasons.append("READY_NODES_INSUFFICIENT")
    known_builds = {node["buildId"] for node in nodes if node["buildId"] is not None}
    if len(known_builds) > 1:
        reasons.append("NODE_BUILD_DRIFT")
    report = {"event": "portfolio_font_nodes_check", "ok": not reasons,
              "expectedBuildId": args.expected_build_id, "readyNodes": ready_count,
              "requiredReadyNodes": args.min_ready_nodes, "reasonCodes": reasons, "nodes": nodes}
    if args.notify and reasons:
        report["notification"] = notify_failure(report, args.timeout, os.environ if env is None else env)
    print(json.dumps(report, ensure_ascii=False))
    return 1 if reasons else 0


if __name__ == "__main__":
    sys.exit(main())
