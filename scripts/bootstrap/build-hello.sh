#!/usr/bin/env bash
#
# Cross-compiles hello.c for a target Android ABI, packages the
# resulting binary as hello-<abi>.tar.gz under scripts/bootstrap/artifacts/.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
hello_dir="$script_dir/hello"

ndk_home="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
abi=""
min_api=29

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --ndk-path) ndk_home="$2"; shift 2;;
        --min-api) min_api="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a|x86_64> [--ndk-path PATH] [--min-api N]

Cross-compiles scripts/bootstrap/hello/hello.c for the given Android ABI
and writes scripts/bootstrap/artifacts/hello-<abi>.tar.gz.
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

[[ -n "$abi" ]] || { echo "--abi is required" >&2; exit 2; }
[[ -d "$ndk_home" ]] || { echo "NDK not found at $ndk_home" >&2; exit 2; }

host_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$host_os" in
    darwin) host_tag="darwin-x86_64";;
    linux)  host_tag="linux-x86_64";;
    *) echo "Unsupported host: $host_os" >&2; exit 2;;
esac

case "$abi" in
    arm64-v8a)    clang_target="aarch64-linux-android${min_api}";;
    armeabi-v7a)  clang_target="armv7a-linux-androideabi${min_api}";;
    x86_64)       clang_target="x86_64-linux-android${min_api}";;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac

toolchain="$ndk_home/toolchains/llvm/prebuilt/$host_tag"
clang="$toolchain/bin/clang"
[[ -x "$clang" ]] || { echo "clang not executable at $clang" >&2; exit 2; }

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-hello.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

# Component layout: bin/hello, matching how BootstrapFileSystem installs
# into usr/opt/<componentId>/. We install this component as
# componentId = "hello", so the final path is
# filesDir/usr/opt/hello/bin/hello.
mkdir -p "$staging/bin"
"$clang" --target="$clang_target" \
         -Wall -Wextra -Werror \
         -O2 \
         -o "$staging/bin/hello" \
         "$hello_dir/hello.c"
chmod +x "$staging/bin/hello"

out="$artifacts_dir/hello-$abi.tar.gz"
(cd "$staging" && tar -cf - .) | gzip -9 -c > "$out"
sha256=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha256"
