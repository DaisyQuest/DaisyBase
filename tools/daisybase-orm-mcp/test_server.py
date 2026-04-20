import json
import subprocess
import sys
import unittest

from server import DaisyBaseOrmMcpServer, OrmToolService, _command_runner


class FakeService(OrmToolService):
    def __init__(self):
        pass

    def introspect_schema(self, url, user, password, schema_pattern, table_pattern):
        return {"tables": [{"schema": schema_pattern, "table": table_pattern, "url": url}]}

    def generate_entity_bundle(self, url, user, password, schema_pattern, table_pattern, package_name):
        return {"packageName": package_name, "sources": {"DemoEntity.java": "class DemoEntity {}"}}

    def write_entity_bundle(self, url, user, password, schema_pattern, table_pattern, package_name, output_dir):
        return {"written": [output_dir + "/DemoEntity.java"]}


class DaisyBaseOrmMcpServerTest(unittest.TestCase):
    def setUp(self):
        self.server = DaisyBaseOrmMcpServer(FakeService())

    def test_initialize(self):
        result = self.server.handle("initialize", {})
        self.assertEqual(result["serverInfo"]["name"], "daisybase-orm-mcp")

    def test_list_tools(self):
        result = self.server.handle("tools/list", {})
        tool_names = {tool["name"] for tool in result["tools"]}
        self.assertEqual(tool_names, {"introspect_schema", "generate_entity_bundle", "write_entity_bundle"})

    def test_introspect_tool(self):
        result = self.server.handle("tools/call", {"name": "introspect_schema", "arguments": {"url": "jdbc:daisybase:embedded:test"}})
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual(payload["tables"][0]["url"], "jdbc:daisybase:embedded:test")

    def test_generate_tool(self):
        result = self.server.handle("tools/call", {"name": "generate_entity_bundle", "arguments": {"url": "jdbc:daisybase:embedded:test", "packageName": "dev.daisybase.generated"}})
        payload = json.loads(result["content"][0]["text"])
        self.assertTrue(payload["sources"]["DemoEntity.java"].startswith("class DemoEntity"))


class CommandRunnerFormattingTest(unittest.TestCase):
    def test_windows_runner_quotes_spacey_arguments(self):
        original_platform = sys.platform
        original_run = subprocess.run
        observed = {}
        try:
            import server
            server.sys.platform = "win32"

            def fake_run(command, cwd, capture_output, text, check):
                observed["command"] = command

                class Result:
                    stdout = "{}"

                return Result()

            subprocess.run = fake_run
            _command_runner(["--mode", "write-files", "--output-dir", "C:/Temp/DaisyBase Demo"])
        finally:
            import server
            server.sys.platform = original_platform
            subprocess.run = original_run
        self.assertIn('"C:/Temp/DaisyBase Demo"', observed["command"][2])


if __name__ == "__main__":
    unittest.main()
