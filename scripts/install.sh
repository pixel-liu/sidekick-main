#!/usr/bin/env bash
set -euo pipefail

repository="pixel-liu/sidekick-main"
requested_version="latest"
archive_path=""
install_dir="${SIDEKICK_INSTALL_DIR:-$HOME/.local/share/sidekick}"
bin_dir="${SIDEKICK_BIN_DIR:-$HOME/.local/bin}"
update_path=1

usage() {
  cat <<'EOF'
Usage: install.sh [options]

Options:
  --version VERSION       Install a specific release (for example 1.0.0 or v1.0.0)
  --archive PATH          Install from a local SideKick tar.gz archive
  --install-dir PATH      Application directory (default: ~/.local/share/sidekick)
  --bin-dir PATH          Command link directory (default: ~/.local/bin)
  --no-path-update        Do not update the shell profile
  -h, --help              Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      requested_version="${2:?--version requires a value}"
      shift 2
      ;;
    --archive)
      archive_path="${2:?--archive requires a value}"
      shift 2
      ;;
    --install-dir)
      install_dir="${2:?--install-dir requires a value}"
      shift 2
      ;;
    --bin-dir)
      bin_dir="${2:?--bin-dir requires a value}"
      shift 2
      ;;
    --no-path-update)
      update_path=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$install_dir" || "$install_dir" == "/" ]]; then
  echo "Refusing to install into a filesystem root." >&2
  exit 1
fi
if [[ -z "$bin_dir" || "$bin_dir" == "/" ]]; then
  echo "Refusing to use a filesystem root as the command directory." >&2
  exit 1
fi
case "$install_dir" in
  /*) ;;
  *) install_dir="$(pwd)/$install_dir" ;;
esac
case "$bin_dir" in
  /*) ;;
  *) bin_dir="$(pwd)/$bin_dir" ;;
esac

if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found. Install Java 17 or newer and add it to PATH first." >&2
  exit 1
fi

java_version_line="$(java -version 2>&1 | head -n 1)"
java_version="$(printf '%s\n' "$java_version_line" | sed -E 's/.*version "([0-9]+(\.[0-9]+)?).*/\1/')"
java_major="${java_version%%.*}"
if [[ "$java_major" == "1" ]]; then
  java_major="${java_version#*.}"
  java_major="${java_major%%.*}"
fi
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
  echo "SideKick requires Java 17 or newer. Detected: $java_version_line" >&2
  exit 1
fi

temp_dir="$(mktemp -d)"
staging_path=""
backup_path=""
cleanup() {
  if [[ -n "$staging_path" && -e "$staging_path" ]]; then
    rm -rf "$staging_path"
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

if [[ -n "$archive_path" ]]; then
  archive_path="$(cd "$(dirname "$archive_path")" && pwd)/$(basename "$archive_path")"
  resolved_version="local"
else
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to download SideKick." >&2
    exit 1
  fi
  if [[ "$requested_version" == "latest" ]]; then
    latest_url="$(curl -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/$repository/releases/latest")"
    release_tag="${latest_url##*/}"
    resolved_version="${release_tag#v}"
  else
    resolved_version="${requested_version#v}"
  fi
  archive_path="$temp_dir/sidekick-$resolved_version.tar.gz"
  echo "Downloading SideKick $resolved_version..."
  curl -fL "https://github.com/$repository/releases/download/v$resolved_version/sidekick-$resolved_version.tar.gz" -o "$archive_path"
  checksum_file="$temp_dir/sidekick-$resolved_version-sha256.txt"
  curl -fL "https://github.com/$repository/releases/download/v$resolved_version/sidekick-$resolved_version-sha256.txt" -o "$checksum_file"
  archive_name="$(basename "$archive_path")"
  expected_hash="$(awk -v name="$archive_name" '$2 == name || $2 == "*" name { print $1; exit }' "$checksum_file")"
  if [[ ! "$expected_hash" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "No SHA-256 checksum was published for $archive_name." >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual_hash="$(sha256sum "$archive_path" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    actual_hash="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
  else
    echo "sha256sum or shasum is required to verify the downloaded archive." >&2
    exit 1
  fi
  actual_hash="$(printf '%s' "$actual_hash" | tr '[:upper:]' '[:lower:]')"
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  if [[ "$actual_hash" != "$expected_hash" ]]; then
    echo "SHA-256 verification failed for $archive_name." >&2
    exit 1
  fi
fi

extract_dir="$temp_dir/extracted"
mkdir -p "$extract_dir"
tar -xzf "$archive_path" -C "$extract_dir"
package_root=""
for candidate in "$extract_dir"/*; do
  if [[ -f "$candidate/bin/sidekick" && -f "$candidate/lib/sidekick.jar" ]]; then
    package_root="$candidate"
    break
  fi
done
if [[ -z "$package_root" ]]; then
  echo "The archive is not a valid SideKick distribution." >&2
  exit 1
fi

install_parent="$(dirname "$install_dir")"
install_name="$(basename "$install_dir")"
mkdir -p "$install_parent" "$bin_dir"
suffix="$$-$(date +%s)"
staging_path="$install_parent/.$install_name.installing-$suffix"
backup_path="$install_parent/.$install_name.backup-$suffix"
cp -R "$package_root" "$staging_path"

if [[ -e "$install_dir" ]]; then
  mv "$install_dir" "$backup_path"
fi
if ! mv "$staging_path" "$install_dir"; then
  if [[ -e "$backup_path" && ! -e "$install_dir" ]]; then
    mv "$backup_path" "$install_dir"
  fi
  exit 1
fi
staging_path=""
if [[ -e "$backup_path" ]]; then
  rm -rf "$backup_path"
fi

chmod +x "$install_dir/bin/sidekick"
command_path="$bin_dir/sidekick"
if [[ -e "$command_path" && ! -L "$command_path" ]]; then
  echo "Refusing to replace the existing non-symlink command: $command_path" >&2
  exit 1
fi
ln -sfn "$install_dir/bin/sidekick" "$command_path"

profile_file=""
if [[ $update_path -eq 1 ]]; then
  case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *)
      if [[ "${SHELL:-}" == */zsh ]]; then
        profile_file="$HOME/.zshrc"
      else
        profile_file="$HOME/.profile"
      fi
      escaped_bin_dir="${bin_dir//\\/\\\\}"
      escaped_bin_dir="${escaped_bin_dir//\"/\\\"}"
      path_line="export PATH=\"$escaped_bin_dir:\$PATH\""
      if [[ ! -f "$profile_file" ]] || ! grep -Fqx "$path_line" "$profile_file"; then
        printf '\n# SideKick command\n%s\n' "$path_line" >> "$profile_file"
      fi
      ;;
  esac
fi

echo "SideKick $resolved_version installed in: $install_dir"
echo "Command link: $command_path"
if [[ -n "$profile_file" ]]; then
  echo "PATH was updated in $profile_file. Open a new terminal or run: source \"$profile_file\""
else
  echo "Enter a project directory and run: sidekick"
fi
