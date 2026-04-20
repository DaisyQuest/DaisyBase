import json
import pathlib
import shlex
import subprocess
import sys
from dataclasses import dataclass
from typing import Callable

ROOT = pathlib.Path(__file__).resolve().parents[2]


def _command_runner(arguments: list[str]) -> str:
    script = ROOT / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")
    if sys.platform.startswith("win"):
        joined = subprocess.list2cmdline(arguments)
    else:
        joined = shlex.join(arguments)
    command = [str(script), ":orm:run", f"--args={joined}"]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=True)
    return completed.stdout.strip()


@dataclass
class OrmToolService:
    runner: Callable[[list[str]], str] = _command_runner

    def introspect_schema(self, url: str, user: str | None, password: str | None,
                          schema_pattern: str, table_pattern: str) -> dict:
        args = [
            "--mode", "schema-json",
            "--url", url,
            "--schema-pattern", schema_pattern,
            "--table-pattern", table_pattern,
        ]
        if user:
            args += ["--user", user]
        if password:
            args += ["--password", password]
        return json.loads(self.runner(args))

    def generate_entity_bundle(self, url: str, user: str | None, password: str | None,
                               schema_pattern: str, table_pattern: str, package_name: str) -> dict:
        args = [
            "--mode", "generate-json",
            "--url", url,
            "--schema-pattern", schema_pattern,
            "--table-pattern", table_pattern,
            "--package", package_name,
        ]
        if user:
            args += ["--user", user]
        if password:
            args += ["--password", password]
        return json.loads(self.runner(args))

    def write_entity_bundle(self, url: str, user: str | None, password: str | None,
                            schema_pattern: str, table_pattern: str, package_name: str,
                            output_dir: str) -> dict:
        args = [
            "--mode", "write-files",
            "--url", url,
            "--schema-pattern", schema_pattern,
            "--table-pattern", table_pattern,
            "--package", package_name,
            "--output-dir", output_dir,
        ]
        if user:
            args += ["--user", user]
        if password:
            args += ["--password", password]
        return {"written": json.loads(self.runner(args))}


class DaisyBaseOrmMcpServer:
    def __init__(self, service: OrmToolService | None = None) -> None:
        self.service = service or OrmToolService()

    def initialize(self) -> dict:
        return {
            "protocolVersion": "2024-11-05",
            "serverInfo": {"name": "daisybase-orm-mcp", "version": "0.1.0-SNAPSHOT"},
            "capabilities": {"tools": {}},
        }

    def list_tools(self) -> list[dict]:
        return [
            {
                "name": "introspect_schema",
                "description": "Inspect DaisyBase tables through JDBC metadata.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "url": {"type": "string"},
                        "user": {"type": "string"},
                        "password": {"type": "string"},
                        "schemaPattern": {"type": "string"},
                        "tablePattern": {"type": "string"}
                    },
                    "required": ["url"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "generate_entity_bundle",
                "description": "Generate DaisyBase ORM entity and repository source code for matching tables.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "url": {"type": "string"},
                        "user": {"type": "string"},
                        "password": {"type": "string"},
                        "schemaPattern": {"type": "string"},
                        "tablePattern": {"type": "string"},
                        "packageName": {"type": "string"}
                    },
                    "required": ["url", "packageName"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "write_entity_bundle",
                "description": "Generate ORM sources and write them to an output directory.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "url": {"type": "string"},
                        "user": {"type": "string"},
                        "password": {"type": "string"},
                        "schemaPattern": {"type": "string"},
                        "tablePattern": {"type": "string"},
                        "packageName": {"type": "string"},
                        "outputDir": {"type": "string"}
                    },
                    "required": ["url", "packageName", "outputDir"],
                    "additionalProperties": False,
                },
            },
        ]

    def call_tool(self, name: str, arguments: dict | None) -> dict:
        arguments = arguments or {}
        schema_pattern = arguments.get("schemaPattern", "%")
        table_pattern = arguments.get("tablePattern", "%")
        user = arguments.get("user")
        password = arguments.get("password")
        if name == "introspect_schema":
            payload = self.service.introspect_schema(arguments["url"], user, password, schema_pattern, table_pattern)
        elif name == "generate_entity_bundle":
            payload = self.service.generate_entity_bundle(arguments["url"], user, password, schema_pattern,
                                                          table_pattern, arguments["packageName"])
        elif name == "write_entity_bundle":
            payload = self.service.write_entity_bundle(arguments["url"], user, password, schema_pattern,
                                                       table_pattern, arguments["packageName"], arguments["outputDir"])
        else:
            raise KeyError(f"Unknown tool: {name}")
        return {"content": [{"type": "text", "text": json.dumps(payload, indent=2)}], "isError": False}

    def handle(self, method: str, params: dict | None) -> dict:
        params = params or {}
        if method == "initialize":
            return self.initialize()
        if method == "tools/list":
            return {"tools": self.list_tools()}
        if method == "tools/call":
            return self.call_tool(params["name"], params.get("arguments"))
        if method == "ping":
            return {}
        raise KeyError(f"Unsupported method: {method}")


def read_message(stream):
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
    server = DaisyBaseOrmMcpServer()
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
