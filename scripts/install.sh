#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR=""
DATABASE_HOME=""
JAVA_HOME_ARG=""
REFERENCE_PARSER_HOME=""
REFERENCE_PARSER_MODE=""
PORT="15432"
CHECKPOINT_INTERVAL="8"
STRICT_DURABILITY="true"
NON_INTERACTIVE="false"
ACCEPT_DEFAULTS="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --database-home) DATABASE_HOME="$2"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="$2"; shift 2 ;;
    --reference-parser-home) REFERENCE_PARSER_HOME="$2"; shift 2 ;;
    --reference-parser-mode) REFERENCE_PARSER_MODE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --checkpoint-interval) CHECKPOINT_INTERVAL="$2"; shift 2 ;;
    --strict-durability) STRICT_DURABILITY="$2"; shift 2 ;;
    --non-interactive) NON_INTERACTIVE="true"; shift ;;
    --accept-defaults) ACCEPT_DEFAULTS="true"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

temurin_arch() {
  case "$(uname -m)" in
    aarch64|arm64) echo "aarch64" ;;
    *) echo "x64" ;;
  esac
}

temurin_url() {
  echo "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$(temurin_arch)/jdk/hotspot/normal/eclipse?project=jdk"
}

temurin_runtime_name() {
  echo "temurin-jdk21-linux-$(temurin_arch)"
}

java_is_21() {
  local bin="$1"
  "$bin" -version 2>&1 | grep -Eq 'version "2[1-9]'
}

ensure_java_home() {
  if [[ -n "$JAVA_HOME_ARG" ]]; then
    echo "$JAVA_HOME_ARG"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] && java_is_21 "$JAVA_HOME/bin/java"; then
    echo "$JAVA_HOME"
    return
  fi
  if command -v java >/dev/null 2>&1 && java_is_21 "$(command -v java)"; then
    echo ""
    return
  fi

  local bootstrap_root="$repo_root/.bootstrap"
  local download_dir="$bootstrap_root/downloads"
  local runtime_root="$bootstrap_root/runtime"
  local runtime_name
  runtime_name="$(temurin_runtime_name)"
  local archive_path="$download_dir/${runtime_name}.tar.gz"
  local extract_root="$runtime_root/$runtime_name"
  mkdir -p "$download_dir" "$runtime_root"
  if [[ ! -f "$archive_path" ]]; then
    echo "Downloading Temurin JDK 21..." >&2
    curl -fsSL "$(temurin_url)" -o "$archive_path"
  fi
  if [[ ! -d "$extract_root" ]]; then
    local temp_unpack
    temp_unpack="$(mktemp -d "$runtime_root/unpack-XXXXXX")"
    tar -xzf "$archive_path" -C "$temp_unpack"
    local extracted
    extracted="$(find "$temp_unpack" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    if [[ -z "$extracted" ]]; then
      mv "$temp_unpack" "$extract_root"
    else
      mv "$extracted" "$extract_root"
      rm -rf "$temp_unpack"
    fi
  fi
  echo "$extract_root"
}

resolved_java_home="$(ensure_java_home)"
if [[ -n "$resolved_java_home" ]]; then
  export JAVA_HOME="$resolved_java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

"$repo_root/gradlew" :server:installDist :cli:installDist :installer:installDist

installer_bin="$repo_root/installer/build/install/installer/bin/installer"
args=(
  --repo-root "$repo_root"
  --cli-dist "$repo_root/cli/build/install/cli"
  --server-dist "$repo_root/server/build/install/server"
  --port "$PORT"
  --checkpoint-interval "$CHECKPOINT_INTERVAL"
  --strict-durability "$STRICT_DURABILITY"
)
if [[ -n "$resolved_java_home" ]]; then args+=(--java-home "$resolved_java_home"); fi
if [[ -n "$INSTALL_DIR" ]]; then args+=(--install-dir "$INSTALL_DIR"); fi
if [[ -n "$DATABASE_HOME" ]]; then args+=(--database-home "$DATABASE_HOME"); fi
if [[ -n "$REFERENCE_PARSER_HOME" ]]; then args+=(--reference-parser-home "$REFERENCE_PARSER_HOME"); fi
if [[ -n "$REFERENCE_PARSER_MODE" ]]; then args+=(--reference-parser-mode "$REFERENCE_PARSER_MODE"); fi
if [[ "$NON_INTERACTIVE" == "true" ]]; then args+=(--non-interactive); fi
if [[ "$ACCEPT_DEFAULTS" == "true" ]]; then args+=(--accept-defaults); fi

exec "$installer_bin" "${args[@]}"
