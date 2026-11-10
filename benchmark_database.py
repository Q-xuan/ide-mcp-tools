"""只读对照客户端和 IDE 内部的元数据调用耗时。"""
import argparse
import json
from pathlib import Path
import time
import urllib.request


class McpClient:
    def __init__(self, url):
        self.url = url
        self.headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        self.request_id = 0
        result, _ = self.rpc("initialize", {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "ide-mcp-metadata-benchmark", "version": "0.1.1"},
        })
        self.headers["MCP-Protocol-Version"] = result["protocolVersion"]

    def rpc(self, method, params):
        self.request_id += 1
        payload = json.dumps({"jsonrpc": "2.0", "id": self.request_id,
                              "method": method, "params": params}).encode()
        request = urllib.request.Request(self.url, data=payload, headers=self.headers)
        started = time.perf_counter_ns()
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.headers.get("Mcp-Session-Id"):
                self.headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
            raw = response.read().decode("utf-8")
            event_stream = "text/event-stream" in response.headers.get("Content-Type", "")
        received = time.perf_counter_ns()
        if event_stream:
            messages = [json.loads("\n".join(line[5:].lstrip() for line in block.splitlines()
                                           if line.startswith("data:")))
                        for block in raw.replace("\r\n", "\n").split("\n\n")
                        if any(line.startswith("data:") for line in block.splitlines())]
            result = next(item for item in messages if item.get("id") == self.request_id)
        else:
            result = json.loads(raw)
        finished = time.perf_counter_ns()
        if "error" in result:
            raise RuntimeError(result["error"])
        return result["result"], {"clientMs": (finished - started) / 1e6,
                                   "requestMs": (received - started) / 1e6,
                                   "decodeMs": (finished - received) / 1e6}

    def call(self, name, arguments):
        result, timings = self.rpc("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            raise RuntimeError(result.get("content"))
        data = result.get("structuredContent")
        if data is None:
            text = next(item["text"] for item in result.get("content", []) if item["type"] == "text")
            data = json.loads(text)
        return data, timings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="本机 GoLand 的内置 MCP Streamable HTTP 地址")
    parser.add_argument("--project", required=True)
    parser.add_argument("--connection-id", required=True)
    parser.add_argument("--schema", required=True)
    parser.add_argument("--database", default="")
    parser.add_argument("--samples", type=int, choices=range(1, 6), default=3)
    parser.add_argument("--probe", action="store_true", help="验证0.1.1的只读计时入口")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    client = McpClient(args.url)
    target = {"projectPath": args.project, "connectionId": args.connection_id,
              "schemaName": args.schema, "databaseName": args.database}
    samples = []
    for _ in range(args.samples):
        result, timings = client.call("list_schema_objects", target)
        samples.append({**timings, "objectCount": len(result["schemaObjects"]),
                        "isIntrospected": result["schemaInfo"]["isIntrospected"]})
    report = {"target": target, "nativeCalls": samples,
              "note": "首次仅表示本次采样的第一次调用，不保证冷启动；requestMs包含传输、排队和IDE执行。"}
    if args.probe:
        result, timings = client.call("probe_database_metadata", {**target, "iterations": args.samples})
        report["probe"] = {**timings, "ide": result}
        if any(sample["objectCount"] != samples[-1]["objectCount"] for sample in result["samples"]):
            raise RuntimeError("采样期间集合列表发生变化，结果不能直接作为同输入性能对照")
    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
