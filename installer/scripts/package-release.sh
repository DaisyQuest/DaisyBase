#!/usr/bin/env bash
set -euo pipefail

output_root=""
package_name=""
java_home=""
reference_parser_home=""
reference_parser_mode=""
port="15432"
checkpoint_interval="8"
strict_durability="true"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-root) output_root="$2"; shift 2 ;;
    --package-name) package_name="$2"; shift 2 ;;
    --java-home) java_home="$2"; shift 2 ;;
    --reference-parser-home) reference_parser_home="$2"; shift 2 ;;
    --reference-parser-mode) reference_parser_mode="$2"; shift 2 ;;
    --port) port="$2"; shift 2 ;;
    --checkpoint-interval) checkpoint_interval="$2"; shift 2 ;;
    --strict-durability) strict_durability="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
version="$(sed -n 's/.*version = "\([^"]*\)".*/\1/p' "$repo_root/build.gradle.kts" | head -n 1)"
if [[ -z "$version" ]]; then
  echo "Failed to determine project version from build.gradle.kts" >&2
  exit 1
fi

arch_name() {
  case "$(uname -m)" in
    aarch64|arm64) echo "aarch64" ;;
    *) echo "x64" ;;
  esac
}

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/installer/build/release"
fi
if [[ -z "$package_name" ]]; then
  package_name="javadb-$version-linux-$(arch_name)"
else
  package_name="$(basename "$package_name")"
fi

stage_parent="$output_root/staging"
archive_parent="$output_root/archives"
stage_dir="$stage_parent/$package_name"
archive_path="$archive_parent/$package_name.tar.gz"

mkdir -p "$stage_parent" "$archive_parent"
rm -rf "$stage_dir" "$archive_path"

install_args=(
  --install-dir "$stage_dir"
  --database-home "$stage_dir/db-home"
  --port "$port"
  --checkpoint-interval "$checkpoint_interval"
  --strict-durability "$strict_durability"
  --non-interactive
)
if [[ -n "$java_home" ]]; then install_args+=(--java-home "$java_home"); fi
if [[ -n "$reference_parser_home" ]]; then install_args+=(--reference-parser-home "$reference_parser_home"); fi
if [[ -n "$reference_parser_mode" ]]; then install_args+=(--reference-parser-mode "$reference_parser_mode"); fi

"$repo_root/scripts/install.sh" "${install_args[@]}"

tar -czf "$archive_path" -C "$stage_parent" "$package_name"
(
  cd "$archive_parent"
  sha256sum "$(basename "$archive_path")" > "SHA256SUMS"
)

echo "Created release archive: $archive_path"
