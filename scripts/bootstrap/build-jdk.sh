#!/usr/bin/env bash
#
# Downloads Termux's openjdk-17 .deb for the target ABI, extracts the
# JDK tree under termux's $PREFIX/lib/jvm/java-17-openjdk, strips
# unused pieces, and repacks as jdk-17.0.13-<abi>.tar.gz.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"

abi=""
# Default Termux mirror; can be overridden via --mirror.
mirror="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
# Default jdk package version. Intentionally matches
# ProcessEnvBuilder.JDK_DIR_NAME = "jdk-17.0.13".
jdk_version="${JDK_VERSION:-17.0.18}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --mirror) mirror="$2"; shift 2;;
        --jdk-version) jdk_version="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a>
          [--mirror URL] [--jdk-version VER]

Downloads Termux openjdk-17 .deb, repacks as
scripts/bootstrap/artifacts/jdk-17.0.13-<abi>.tar.gz.

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
    *) echo "Unsupported ABI: $abi (only arm64-v8a / armeabi-v7a)" >&2; exit 2;;
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

# Bundle runtime library dependencies alongside the JDK. libjli.so /
# libjvm.so NEED libz.so.1 and libandroid-shmem.so, which Termux ships
# as separate packages. Since libjli.so's DT_RUNPATH includes $ORIGIN,
# dropping these .so files into jdk/lib/ resolves them.
fetch_termux_lib() {
    local pkg_path="$1" deb_name="$2"
    local url="$mirror/pool/main/$pkg_path/$deb_name"
    local dep_stage="$staging/dep-$(basename "$pkg_path")"
    mkdir -p "$dep_stage"
    echo "Fetching runtime dep: $deb_name ..."
    curl -fSL --retry 3 -o "$dep_stage/$deb_name" "$url"
    (cd "$dep_stage" && ar x "$deb_name")
    if   [[ -f "$dep_stage/data.tar.xz"  ]]; then tar -xf "$dep_stage/data.tar.xz"  -C "$dep_stage" data.tar 2>/dev/null || tar -xf "$dep_stage/data.tar.xz"  -C "$dep_stage"
    elif [[ -f "$dep_stage/data.tar.zst" ]]; then tar -xf "$dep_stage/data.tar.zst" -C "$dep_stage"
    else tar -xf "$dep_stage/data.tar"     -C "$dep_stage"; fi
    # Termux installs libs under $PREFIX/lib. Copy every libX.so* from there.
    find "$dep_stage/data/data/com.termux/files/usr/lib" -maxdepth 1 -name "lib*.so*" 2>/dev/null \
        | while read -r lib; do
            cp -a "$lib" "$jdk_src/lib/"
        done
}

# Termux packages (name → path) of all transitive runtime deps needed by
# the JDK's native libs. Mapped out via `llvm-readelf -d` against every
# .so in the JDK tree and subtracting what Android Bionic provides
# (libc, libm, libdl, liblog, libstdc++ — all already present).
# Versions will drift; override via --jdk-version / edit here if needed.
fetch_termux_lib "z/zlib"                 "zlib_1.3.2_${termux_arch}.deb"
fetch_termux_lib "liba/libandroid-shmem"  "libandroid-shmem_0.7_${termux_arch}.deb"
fetch_termux_lib "liba/libandroid-spawn"  "libandroid-spawn_0.3_${termux_arch}.deb"
fetch_termux_lib "libi/libiconv"          "libiconv_1.18-1_${termux_arch}.deb"
fetch_termux_lib "libj/libjpeg-turbo"     "libjpeg-turbo_3.1.4.1_${termux_arch}.deb"

# libc++_shared.so: the NDK's C++ runtime. Required by libandroid-spawn.so.
# Bundled from the NDK toolchain rather than a Termux package (Termux
# uses its own forked C++ stdlib; we don't want two incompatible libc++
# at runtime, so use NDK's).
ndk_home_default="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
host_os_lower="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$host_os_lower" in
    darwin) ndk_host_tag="darwin-x86_64";;
    linux)  ndk_host_tag="linux-x86_64";;
    *) ndk_host_tag="";;
esac
case "$abi" in
    arm64-v8a)   ndk_triple="aarch64-linux-android";;
    armeabi-v7a) ndk_triple="arm-linux-androideabi";;
esac
if [[ -n "$ndk_host_tag" && -d "$ndk_home_default" ]]; then
    ndk_libcxx="$ndk_home_default/toolchains/llvm/prebuilt/$ndk_host_tag/sysroot/usr/lib/$ndk_triple/libc++_shared.so"
    if [[ -f "$ndk_libcxx" ]]; then
        cp -a "$ndk_libcxx" "$jdk_src/lib/"
        echo "Bundled libc++_shared.so from NDK ($ndk_triple)"
    else
        echo "WARN: NDK libc++_shared.so not found at $ndk_libcxx" >&2
    fi
else
    echo "WARN: NDK not found; skipping libc++_shared.so bundling" >&2
fi

out="$artifacts_dir/jdk-17.0.13-$abi.tar.gz"
# Tar the CONTENTS of the JDK tree (not the java-17-openjdk/ wrapper
# dir), so post-extraction the layout at filesDir/usr/opt/jdk-17.0.13/
# has bin/, lib/, etc. directly — matching what the Kotlin code and
# instrumented tests expect (<componentInstallDir>/bin/java).
(cd "$jdk_src" && tar -cf - .) | gzip -9 -c > "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
