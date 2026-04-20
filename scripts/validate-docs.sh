#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
required=(
  "$root/README.md"
  "$root/docs/README.md"
  "$root/docs/50-point-documentation-plan.md"
  "$root/docs/site/index.html"
  "$root/docs/site/styles.css"
  "$root/docs/site/javadoc-stylesheet.css"
  "$root/docs/system/daisybase-system-catalog.json"
  "$root/.github/workflows/github-pages.yml"
  "$root/tools/daisybase-system-mcp/server.py"
  "$root/tools/daisybase-system-mcp/test_server.py"
)

for path in "${required[@]}"; do
  [[ -f "$path" ]] || { echo "Missing documentation artifact: $path" >&2; exit 1; }
done

python3 - <<'PY' "$root"
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
html = (root / 'docs/site/index.html').read_text(encoding='utf-8')
if 'Skip to main content' not in html:
    raise SystemExit('Docs portal is missing the skip link.')
if 'id="main-content"' not in html:
    raise SystemExit('Docs portal is missing the main content landmark.')
catalog = json.loads((root / 'docs/system/daisybase-system-catalog.json').read_text(encoding='utf-8'))
if catalog['coveragePlan']['implementedPoints'] != 50:
    raise SystemExit('Coverage plan count is not 50.')
if len(catalog['modules']) < 10:
    raise SystemExit('System catalog is unexpectedly incomplete.')
PY

export PYTHONDONTWRITEBYTECODE=1
python3 "$root/tools/daisybase-system-mcp/test_server.py"
echo 'Documentation validation passed.'
