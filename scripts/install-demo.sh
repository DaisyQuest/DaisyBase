#!/usr/bin/env bash
set -euo pipefail

install_dir=""
database_home=""
java_home_arg=""
tomee_home=""
demo_war=""
http_port="8080"
context_path="daisybase-demo-business"
enterprise_name="Northwind Field Systems"
gui="false"
non_interactive="false"
accept_defaults="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir) install_dir="$2"; shift 2 ;;
    --database-home) database_home="$2"; shift 2 ;;
    --java-home) java_home_arg="$2"; shift 2 ;;
    --tomee-home) tomee_home="$2"; shift 2 ;;
    --demo-war) demo_war="$2"; shift 2 ;;
    --http-port) http_port="$2"; shift 2 ;;
    --context-path) context_path="$2"; shift 2 ;;
    --enterprise-name) enterprise_name="$2"; shift 2 ;;
    --gui) gui="true"; shift ;;
    --non-interactive) non_interactive="true"; shift ;;
    --accept-defaults) accept_defaults="true"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

is_wsl() {
  grep -qi microsoft /proc/version 2>/dev/null
}

run_gradle() {
  if [[ -x "$repo_root/gradlew" && ( -n "${JAVA_HOME:-}" || -n "$(command -v java 2>/dev/null)" ) ]]; then
    "$repo_root/gradlew" "$@"
    return
  fi
  if is_wsl && command -v cmd.exe >/dev/null 2>&1; then
    local win_repo
    win_repo="$(wslpath -w "$repo_root")"
    cmd.exe /c "cd /d $win_repo && gradlew.bat $*"
    return
  fi
  "$repo_root/gradlew" "$@"
}

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
  if [[ -n "$java_home_arg" ]]; then
    echo "$java_home_arg"
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

run_gradle :installer:installDist :demo-business-app:war

if [[ -z "$demo_war" ]]; then
  demo_war="$(find "$repo_root/demo-business-app/build/libs" -maxdepth 1 -name 'daisybase-demo-business*.war' | head -n 1)"
fi

installer_bin="$repo_root/installer/build/install/installer/bin/installer"
args=(
  --profile demo-business
  --repo-root "$repo_root"
  --demo-war "$demo_war"
  --http-port "$http_port"
  --context-path "$context_path"
  --enterprise-name "$enterprise_name"
)
if [[ -n "$resolved_java_home" ]]; then args+=(--java-home "$resolved_java_home"); fi
if [[ -n "$install_dir" ]]; then args+=(--install-dir "$install_dir"); fi
if [[ -n "$database_home" ]]; then args+=(--database-home "$database_home"); fi
if [[ -n "$tomee_home" ]]; then args+=(--tomee-home "$tomee_home"); fi
if [[ "$gui" == "true" ]]; then args+=(--gui); fi
if [[ "$non_interactive" == "true" ]]; then args+=(--non-interactive); fi
if [[ "$accept_defaults" == "true" ]]; then args+=(--accept-defaults); fi

exec "$installer_bin" "${args[@]}"
