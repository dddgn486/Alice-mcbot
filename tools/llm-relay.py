#!/usr/bin/env python3
"""Alice 决策层 · 本地 LLM 中继（开发环境兜底，D-135 附注二）。

为什么存在：Minecraft 客户端的 JVM 出网路径可能不通（2026-09-12 实测：系统代理
127.0.0.1:7897 与直连**都**连接超时），而 WSL 侧直连 API 正常（22 ms）。
这个中继跑在 WSL（能出网的一侧），把 `POST /chat/completions` 原样转发到上游，
于是 mod 只需要访问 `http://127.0.0.1:<port>/chat/completions`（Windows→WSL 的 localhost 转发）。

用法：
    python3 tools/llm-relay.py [--port 8791] [--config <client>/config/alice-llm.json]

安全：key 只从配置文件读取，**不打印**；中继只监听 127.0.0.1。
"""
import argparse, json, sys, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = None
UPSTREAM = "https://api.deepseek.com/chat/completions"


def load_key(path):
    with open(path, encoding="utf-8") as handle:
        cfg = json.load(handle)
    return cfg.get("apiKey", ""), cfg.get("url", UPSTREAM)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[relay] " + (fmt % args) + "\n")

    def do_POST(self):
        if not self.path.startswith("/chat/completions"):
            self.send_error(404, "only /chat/completions")
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        key, upstream = ARGS.key, ARGS.url
        request = urllib.request.Request(upstream, data=body, method="POST", headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + key,
        })
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
                status = response.status
        except urllib.error.HTTPError as err:
            payload = err.read()
            status = err.code
        except Exception as err:  # 网络异常如实回报（不假装成功）
            payload = json.dumps({"error": {"message": f"relay_transport: {err}"}}).encode()
            status = 502
        print(f"[relay] forward {length}B → upstream status={status} bytes={len(payload)}", flush=True)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    global ARGS
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8791)
    parser.add_argument("--config",
                        default="/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/config/alice-llm.json")
    ARGS = parser.parse_args()
    key, url = load_key(ARGS.config)
    if not key:
        print("配置里没有 apiKey，无法启动中继", file=sys.stderr)
        return 2
    ARGS.key, ARGS.url = key, url
    server = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    print(f"[relay] listening 127.0.0.1:{ARGS.port} → {url} (key 已加载，未打印)", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
