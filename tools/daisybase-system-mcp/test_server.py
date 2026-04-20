import json
import unittest

from server import Catalog, DaisyBaseMcpServer


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = Catalog.load()

    def test_overview_counts_match_catalog(self):
        overview = self.catalog.overview()
        self.assertEqual(overview["moduleCount"], len(self.catalog.data["modules"]))
        self.assertEqual(overview["surfaceCount"], len(self.catalog.data["surfaces"]))
        self.assertEqual(overview["implementedCoveragePoints"], 50)

    def test_module_lookup(self):
        module = self.catalog.module("jdbc")
        self.assertEqual(module["path"], "jdbc")

    def test_doc_lookup_reads_content(self):
        doc = self.catalog.doc("plan-50")
        self.assertIn("50-Point Documentation Plan", doc["content"])

    def test_search_returns_expected_doc(self):
        results = self.catalog.search_docs("installer")
        slugs = {item["slug"] for item in results}
        self.assertIn("installers", slugs)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.server = DaisyBaseMcpServer()

    def test_initialize(self):
        result = self.server.handle("initialize", {})
        self.assertEqual(result["serverInfo"]["name"], "daisybase-system-mcp")

    def test_resources_list(self):
        result = self.server.handle("resources/list", {})
        uris = {item["uri"] for item in result["resources"]}
        self.assertIn("daisybase://overview", uris)
        self.assertIn("daisybase://module/jdbc", uris)

    def test_resource_read(self):
        result = self.server.handle("resources/read", {"uri": "daisybase://limits"})
        payload = json.loads(result["contents"][0]["text"])
        self.assertTrue(any("installer" in item.lower() for item in payload))

    def test_tool_call(self):
        result = self.server.handle("tools/call", {"name": "describe_module", "arguments": {"name": "server"}})
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual(payload["name"], "server")


if __name__ == "__main__":
    unittest.main()
