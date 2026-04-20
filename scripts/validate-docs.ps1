$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$required = @(
  'README.md',
  'docs\README.md',
  'docs\50-point-documentation-plan.md',
  'docs\site\index.html',
  'docs\site\styles.css',
  'docs\system\daisybase-system-catalog.json',
  'tools\daisybase-system-mcp\server.py',
  'tools\daisybase-system-mcp\test_server.py'
)

foreach ($relative in $required) {
  $path = Join-Path $root $relative
  if (-not (Test-Path $path)) {
    throw "Missing documentation artifact: $relative"
  }
}

$html = Get-Content (Join-Path $root 'docs\site\index.html') -Raw
if ($html -notmatch 'Skip to main content') { throw 'Docs portal is missing the skip link.' }
if ($html -notmatch 'id="main-content"') { throw 'Docs portal is missing the main content landmark.' }

$catalog = Get-Content (Join-Path $root 'docs\system\daisybase-system-catalog.json') -Raw | ConvertFrom-Json
if ($catalog.coveragePlan.implementedPoints -ne 50) { throw 'Coverage plan count is not 50.' }
if ($catalog.modules.Count -lt 10) { throw 'System catalog is unexpectedly incomplete.' }

& py -3 (Join-Path $root 'tools\daisybase-system-mcp\test_server.py')
Write-Host 'Documentation validation passed.'
