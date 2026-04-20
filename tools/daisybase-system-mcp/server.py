import json
import pathlib
import sys
from dataclasses import dataclass

ROOT = pathlib.Path(__file__).resolve().parents[2]
CATALOG_PATH = ROOT / "docs" / "system" / "daisybase-system-catalog.json"


@dataclass
class Catalog:
    root: pathlib.Path
    data: dict

    @classmethod
    def load(cls) -> "Catalog":
        return cls(ROOT, json.loads(CATALOG_PATH.read_text(encoding="utf-8")))

    def overview(self) -> dict:
        return {
            "product": self.data["product"],
            "moduleCount": len(self.data["modules"]),
            "surfaceCount": len(self.data["surfaces"]),
            "docCount": len(self.data["docs"]),
            "implementedCoveragePoints": self.data["coveragePlan"]["implementedPoints"],
        }

    def module(self, name: str) -> dict:
        for item in self.data["modules"]:
            if item["name"] == name:
                return item
        raise KeyError(f"Unknown module: {name}")

    def surface(self, name: str) -> dict:
        for item in self.data["surfaces"]:
            if item["name"] == name:
                return item
        raise KeyError(f"Unknown surface: {name}")

    def doc(self, slug: str) -> dict:
        for item in self.data["docs"]:
            if item["slug"] == slug:
                path = self.root / item["path"]
                return {
                    "slug": slug,
                    "title": item["title"],
                    "path": item["path"],
                    "content": path.read_text(encoding="utf-8"),
                }
        raise KeyError(f"Unknown doc: {slug}")

    def search_docs(self, query: str) -> list[dict]:
        needle = query.strip().lower()
        results = []
        for item in self.data["docs"]:
            path = self.root / item["path"]
            content = path.read_text(encoding="utf-8")
            if needle in item["title"].lower() or needle in content.lower():
                results.append({"slug": item["slug"], "title": item["title"], "path": item["path"]})
        return results

    def resources(self) -> list[dict]:
        items = [
            {"uri": "daisybase://overview", "name": "DaisyBase Overview", "mimeType": "application/json"},
            {"uri": "daisybase://modules", "name": "DaisyBase Modules", "mimeType": "application/json"},
            {"uri": "daisybase://limits", "name": "DaisyBase Known Limits", "mimeType": "application/json"},
            {"uri": "daisybase://plan/50-point", "name": "DaisyBase Documentation Coverage Plan", "mimeType": "text/markdown"},
        ]
        items.extend({"uri": f"daisybase://module/{m['name']}", "name": f"Module {m['name']}", "mimeType": "application/json"} for m in self.data["modules"])
        items.extend({"uri": f"daisybase://surface/{s['name']}", "name": f"Surface {s['name']}", "mimeType": "application/json"} for s in self.data["surfaces"])
        items.extend({"uri": f"daisybase://doc/{d['slug']}", "name": d["title"], "mimeType": "text/markdown"} for d in self.data["docs"])
        return items

    def read_resource(self, uri: str) -> dict:
        if uri == "daisybase://overview":
            return {"mimeType": "application/json", "text": json.dumps(self.overview(), indent=2)}
        if uri == "daisybase://modules":
            return {"mimeType": "application/json", "text": json.dumps(self.data["modules"], indent=2)}
        if uri == "daisybase://limits":
            return {"mimeType": "application/json", "text": json.dumps(self.data["knownLimits"], indent=2)}
        if uri == "daisybase://plan/50-point":
            plan_path = self.root / self.data["coveragePlan"]["source"]
            return {"mimeType": "text/markdown", "text": plan_path.read_text(encoding="utf-8")}
        if uri.startswith("daisybase://module/"):
            return {"mimeType": "application/json", "text": json.dumps(self.module(uri.rsplit("/", 1)[-1]), indent=2)}
        if uri.startswith("daisybase://surface/"):
            return {"mimeType": "application/json", "text": json.dumps(self.surface(uri.rsplit("/", 1)[-1]), indent=2)}
        if uri.startswith("daisybase://doc/"):
            return {"mimeType": "text/markdown", "text": self.doc(uri.rsplit("/", 1)[-1])["content"]}
        raise KeyError(f"Unknown resource: {uri}")


class DaisyBaseMcpServer:
    def __init__(self) -> None:
        self.catalog = Catalog.load()

    def initialize(self) -> dict:
        return {
            "protocolVersion": "2024-11-05",
            "serverInfo": {"name": "daisybase-system-mcp", "version": self.catalog.data["product"]["version"]},
            "capabilities": {"resources": {}, "tools": {}},
        }

    def list_tools(self) -> list[dict]:
        return [
            {
                "name": "describe_system",
                "description": "Return the high-level DaisyBase system overview.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
            {
                "name": "describe_module",
                "description": "Return a module description by module name.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"name": {"type": "string"}},
                    "required": ["name"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "describe_surface",
                "description": "Return a runtime surface description by name.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"name": {"type": "string"}},
                    "required": ["name"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "search_docs",
                "description": "Search handbook pages by substring.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "list_known_limits",
                "description": "Return the current documented known limits.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
            {
                "name": "coverage_status",
                "description": "Return documentation plan coverage status.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
        ]

    def call_tool(self, name: str, arguments: dict | None) -> dict:
        arguments = arguments or {}
        if name == "describe_system":
            payload = self.catalog.overview()
        elif name == "describe_module":
            payload = self.catalog.module(arguments["name"])
        elif name == "describe_surface":
            payload = self.catalog.surface(arguments["name"])
        elif name == "search_docs":
            payload = self.catalog.search_docs(arguments["query"])
        elif name == "list_known_limits":
            payload = self.catalog.data["knownLimits"]
        elif name == "coverage_status":
            payload = self.catalog.data["coveragePlan"]
        else:
            raise KeyError(f"Unknown tool: {name}")
        return {"content": [{"type": "text", "text": json.dumps(payload, indent=2)}], "isError": False}

    def handle(self, method: str, params: dict | None) -> dict:
        params = params or {}
        if method == "initialize":
            return self.initialize()
        if method == "resources/list":
            return {"resources": self.catalog.resources()}
        if method == "resources/read":
            return {"contents": [self.catalog.read_resource(params["uri"])]}
        if method == "tools/list":
            return {"tools": self.list_tools()}
        if method == "tools/call":
            return self.call_tool(params["name"], params.get("arguments"))
        if method == "ping":
            return {}
        raise KeyError(f"Unsupported method: {method}")


def read_message(stream) -> dict | None:
    headers = {}
    while True:
        line = stream.readline()
        if line == "":
            return None
        line = line.rstrip("\r\n")
        if not line:
            break
        key, value = line.split(":", 1)
        headers[key.strip().lower()] = value.strip()
    length = int(headers["content-length"])
    body = stream.read(length)
    return json.loads(body)


def write_message(stream, payload: dict) -> None:
    encoded = json.dumps(payload).encode("utf-8")
    stream.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode("ascii"))
    stream.write(encoded)
    stream.flush()


def main() -> int:
    server = DaisyBaseMcpServer()
    input_stream = sys.stdin
    output_stream = sys.stdout.buffer
    while True:
        message = read_message(input_stream)
        if message is None:
            return 0
        response = {"jsonrpc": "2.0", "id": message.get("id")}
        try:
            response["result"] = server.handle(message["method"], message.get("params"))
        except Exception as exc:
            response["error"] = {"code": -32000, "message": str(exc)}
        write_message(output_stream, response)


if __name__ == "__main__":
    raise SystemExit(main())
