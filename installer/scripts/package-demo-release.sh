#!/usr/bin/env bash
set -euo pipefail

output_root=""
package_name=""
java_home=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-root) output_root="$2"; shift 2 ;;
    --package-name) package_name="$2"; shift 2 ;;
    --java-home) java_home="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

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
  output_root="$repo_root/installer/build/demo-release"
fi
if [[ -z "$package_name" ]]; then
  package_name="daisybase-demo-business-installer-$version-linux-$(arch_name)"
else
  package_name="$(basename "$package_name")"
fi

stage_parent="$output_root/staging"
archive_parent="$output_root/archives"
stage_dir="$stage_parent/$package_name"
archive_path="$archive_parent/$package_name.tar.gz"

mkdir -p "$stage_parent" "$archive_parent"
rm -rf "$stage_dir" "$archive_path"

if [[ -n "$java_home" ]]; then
  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

run_gradle :installer:installDist :demo-business-app:war

installer_dist="$repo_root/installer/build/install/installer"
demo_war="$(find "$repo_root/demo-business-app/build/libs" -maxdepth 1 -name 'daisybase-demo-business*.war' | head -n 1)"

mkdir -p "$stage_dir/installer" "$stage_dir/payload"
cp -R "$installer_dist" "$stage_dir/installer"
cp "$demo_war" "$stage_dir/payload/daisybase-demo-business.war"

cat > "$stage_dir/install-demo-headless.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

install_dir=""
database_home=""
java_home=""
tomee_home=""
http_port="8080"
context_path="daisybase-demo-business"
enterprise_name="Northwind Field Systems"
non_interactive="false"
accept_defaults="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir) install_dir="$2"; shift 2 ;;
    --database-home) database_home="$2"; shift 2 ;;
    --java-home) java_home="$2"; shift 2 ;;
    --tomee-home) tomee_home="$2"; shift 2 ;;
    --http-port) http_port="$2"; shift 2 ;;
    --context-path) context_path="$2"; shift 2 ;;
    --enterprise-name) enterprise_name="$2"; shift 2 ;;
    --non-interactive) non_interactive="true"; shift ;;
    --accept-defaults) accept_defaults="true"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

bundle_root="$(cd "$(dirname "$0")" && pwd)"
installer_bin="$bundle_root/installer/installer/bin/installer"
args=(
  --profile demo-business
  --demo-war "$bundle_root/payload/daisybase-demo-business.war"
  --http-port "$http_port"
  --context-path "$context_path"
  --enterprise-name "$enterprise_name"
)
if [[ -n "$install_dir" ]]; then args+=(--install-dir "$install_dir"); fi
if [[ -n "$database_home" ]]; then args+=(--database-home "$database_home"); fi
if [[ -n "$java_home" ]]; then args+=(--java-home "$java_home"); fi
if [[ -n "$tomee_home" ]]; then args+=(--tomee-home "$tomee_home"); fi
if [[ "$non_interactive" == "true" ]]; then args+=(--non-interactive); fi
if [[ "$accept_defaults" == "true" ]]; then args+=(--accept-defaults); fi
exec "$installer_bin" "${args[@]}"
EOF

cat > "$stage_dir/install-demo-gui.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

install_dir=""
database_home=""
java_home=""
tomee_home=""
http_port="8080"
context_path="daisybase-demo-business"
enterprise_name="Northwind Field Systems"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir) install_dir="$2"; shift 2 ;;
    --database-home) database_home="$2"; shift 2 ;;
    --java-home) java_home="$2"; shift 2 ;;
    --tomee-home) tomee_home="$2"; shift 2 ;;
    --http-port) http_port="$2"; shift 2 ;;
    --context-path) context_path="$2"; shift 2 ;;
    --enterprise-name) enterprise_name="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

bundle_root="$(cd "$(dirname "$0")" && pwd)"
installer_bin="$bundle_root/installer/installer/bin/installer"
args=(
  --profile demo-business
  --gui
  --demo-war "$bundle_root/payload/daisybase-demo-business.war"
  --http-port "$http_port"
  --context-path "$context_path"
  --enterprise-name "$enterprise_name"
)
if [[ -n "$install_dir" ]]; then args+=(--install-dir "$install_dir"); fi
if [[ -n "$database_home" ]]; then args+=(--database-home "$database_home"); fi
if [[ -n "$java_home" ]]; then args+=(--java-home "$java_home"); fi
if [[ -n "$tomee_home" ]]; then args+=(--tomee-home "$tomee_home"); fi
exec "$installer_bin" "${args[@]}"
EOF

chmod +x "$stage_dir/install-demo-headless.sh" "$stage_dir/install-demo-gui.sh"

cat > "$stage_dir/README.txt" <<EOF
DaisyBase Demo Business installer bundle

GUI:
  ./install-demo-gui.sh

Headless:
  ./install-demo-headless.sh --non-interactive --accept-defaults

The installer will download Temurin JDK 21 and Apache TomEE Plus $version when JAVA_HOME and TomeeHome are not provided.
EOF

tar -czf "$archive_path" -C "$stage_parent" "$package_name"
(
  cd "$archive_parent"
  sha256sum "$(basename "$archive_path")" > "SHA256SUMS"
)

echo "Created demo installer archive: $archive_path"
