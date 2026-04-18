#!/usr/bin/env bash
#
# Downloads Termux's openjdk-17 .deb for the target ABI, extracts the
# JDK tree under termux's $PREFIX/lib/jvm/java-17-openjdk, strips
# unused pieces, and repacks as jdk-17.0.13-<abi>.tar.zst.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"

abi=""
# Default Termux mirror; can be overridden via --mirror.
mirror="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
# Default jdk package version. Intentionally matches
# ProcessEnvBuilder.JDK_DIR_NAME = "jdk-17.0.13".
jdk_version="${JDK_VERSION:-17.0.13_p11-0}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --mirror) mirror="$2"; shift 2;;
        --jdk-version) jdk_version="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a|x86_64>
          [--mirror URL] [--jdk-version VER]

Downloads Termux openjdk-17 .deb, repacks as
scripts/bootstrap/artifacts/jdk-17.0.13-<abi>.tar.zst.

Defaults:
  mirror:      $mirror
  jdk-version: $jdk_version  (override if upstream moves)
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

[[ -n "$abi" ]] || { echo "--abi is required" >&2; exit 2; }

# Termux package architecture naming — distinct from Android's ABI codes.
case "$abi" in
    arm64-v8a)   termux_arch="aarch64";;
    armeabi-v7a) termux_arch="arm";;
    x86_64)      termux_arch="x86_64";;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac

deb_name="openjdk-17_${jdk_version}_${termux_arch}.deb"
deb_url="$mirror/pool/main/o/openjdk-17/$deb_name"

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-jdk.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

echo "Downloading $deb_url ..."
curl -fSL -o "$staging/$deb_name" "$deb_url"

# .deb is an ar archive: debian-binary, control.tar.*, data.tar.*
echo "Extracting .deb ..."
(cd "$staging" && ar x "$deb_name")

# Termux's data.tar is xz-compressed in recent releases; handle either.
if   [[ -f "$staging/data.tar.xz" ]]; then data_tar="data.tar.xz"
elif [[ -f "$staging/data.tar.zst" ]]; then data_tar="data.tar.zst"
elif [[ -f "$staging/data.tar" ]];    then data_tar="data.tar"
else echo ".deb contained unknown data.tar variant" >&2; exit 2; fi

unpack="$staging/unpack"
mkdir -p "$unpack"
tar -xf "$staging/$data_tar" -C "$unpack"

# Termux installs the JDK at $PREFIX/lib/jvm/java-17-openjdk where
# $PREFIX = data/data/com.termux/files/usr. We strip that prefix.
jdk_src=""
for candidate in \
    "$unpack/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk" \
    "$unpack/data/data/com.termux/files/usr/opt/openjdk"; do
    if [[ -d "$candidate" ]]; then jdk_src="$candidate"; break; fi
done
[[ -n "$jdk_src" ]] || { echo "JDK tree not found in .deb; check layout" >&2; find "$unpack" -type d -name "*openjdk*" 2>/dev/null | head; exit 2; }

# Strip docs / man / legal / sample trees that aren't needed at runtime.
for d in demo man sample src.zip legal; do
    rm -rf "$jdk_src/$d" || true
done
find "$jdk_src" -name "*.diz" -delete 2>/dev/null || true

out="$artifacts_dir/jdk-17.0.13-$abi.tar.zst"
(cd "$(dirname "$jdk_src")" && tar -cf - "$(basename "$jdk_src")") \
    | zstd -19 -q -o "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
