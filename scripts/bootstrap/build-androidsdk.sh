#!/usr/bin/env bash
#
# Composes a per-ABI Android SDK 36.0.0 tarball for on-device use:
#   - platforms/android-36/        from Google's platform-${rev}.zip
#   - build-tools/36.0.0/d8.jar    from Google's build-tools_r36.0.0-linux.zip
#   - build-tools/36.0.0/aapt2     from Termux's aapt2 .deb (real Android-native binary)
#   - licenses/, package.xml, source.properties (locally-generated; AGP-accepted)
#
# Layout is tar'd at root (no wrapper dir) so extraction into
# filesDir/usr/opt/android-sdk-36.0.0/ yields platforms/, build-tools/, licenses/
# directly.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
license_src="$script_dir/android-sdk-license.txt"

abi=""
mirror="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
# aapt2 is the only version currently published on the Termux mirror as of
# 2026-04-18. Bump if upstream moves; verify HEAD before changing the default.
aapt2_version="${AAPT2_VERSION:-13.0.0.6-23}"
sdk_version="${ANDROID_SDK_VERSION:-36.0.0}"
platform_rev="${ANDROID_PLATFORM_REV:-36_r01}"

# Termux runtime-dep version pins for aapt2's transitive .so deps.
# Override via env if upstream moves. Verified HEAD 200 on 2026-04-18.
#
# Why each is needed (from `readelf -d` walk over the aapt2 binary and the
# libandroid-*.so files it loads):
#   libc++         -> libc++_shared.so       (C++ runtime; aapt2 direct)
#   abseil-cpp     -> libabsl_*.so           (aapt2 direct)
#   libprotobuf    -> libprotobuf.so         (aapt2 direct)
#   aapt           -> libandroid-{base,cutils,fw,utils,ziparchive}.so
#                                            (aapt2 direct; bundled inside the
#                                             `aapt` Termux pkg, not a separate
#                                             libandroid-fw etc. package)
#   libexpat       -> libexpat.so.1          (aapt2 direct)
#   libpng         -> libpng16.so            (aapt2 direct)
#   fmt            -> libfmt.so              (transitive from libandroid-fw)
#   libzopfli      -> libzopfli.so           (transitive from libandroid-ziparchive)
#   zlib           -> libz.so.1              (transitive; Bionic ships libz.so
#                                             but the soname-1 alias is only on
#                                             Termux side)
libcxx_version="${LIBCXX_VERSION:-29}"
abseil_version="${ABSEIL_VERSION:-20250814.1}"
# libprotobuf and fmt have debian epoch prefixes (2:, 1:); URL-encoded as %3A.
protobuf_version="${PROTOBUF_VERSION:-2:33.1-1}"
aapt_version="${AAPT_VERSION:-13.0.0.6-23}"
libexpat_version="${LIBEXPAT_VERSION:-2.7.5}"
libpng_version="${LIBPNG_VERSION:-1.6.58}"
fmt_version="${FMT_VERSION:-1:11.2.0}"
libzopfli_version="${LIBZOPFLI_VERSION:-1.0.3-5}"
zlib_version="${ZLIB_VERSION:-1.3.2}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --mirror) mirror="$2"; shift 2;;
        --aapt2-version) aapt2_version="$2"; shift 2;;
        --sdk-version) sdk_version="$2"; shift 2;;
        --platform-rev) platform_rev="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a|x86_64>
          [--mirror URL] [--aapt2-version VER] [--sdk-version VER]
          [--platform-rev REV]

Composes scripts/bootstrap/artifacts/android-sdk-${sdk_version}-<abi>.tar.gz
from Google's platform/build-tools zips + Termux's aapt2 .deb.

Defaults:
  mirror:           $mirror
  aapt2-version:    $aapt2_version  (Termux package version; bump if upstream moves)
  sdk-version:      $sdk_version
  platform-rev:     $platform_rev
  libcxx-version:   $libcxx_version  (env LIBCXX_VERSION)
  abseil-version:   $abseil_version  (env ABSEIL_VERSION)
  protobuf-version: $protobuf_version  (env PROTOBUF_VERSION; epoch supported)

Build-tools URL: composed as
  https://dl.google.com/android/repository/build-tools_r<MAJOR>_linux.zip
where <MAJOR> strips everything from the first dot in --sdk-version.
Override the entire URL via env BUILD_TOOLS_URL if Google's path scheme
shifts (e.g. for a future build-tools layout).
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

[[ -n "$abi" ]] || { echo "--abi is required" >&2; exit 2; }
[[ -f "$license_src" ]] || { echo "Missing $license_src" >&2; exit 2; }

# Termux package architecture naming differs from Android's ABI codes.
case "$abi" in
    arm64-v8a)   termux_arch="aarch64";;
    armeabi-v7a) termux_arch="arm";;
    x86_64)      termux_arch="x86_64";;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-androidsdk.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

curl_fetch() {
    local url="$1" out="$2"
    echo "Downloading $url ..."
    if ! curl -fSL --retry 1 -o "$out" "$url"; then
        echo "Retry: $url ..."
        curl -fSL --retry 1 -o "$out" "$url"
    fi
}

# --- 1. Google platform-${platform_rev}.zip ---------------------------------
platform_zip_url="https://dl.google.com/android/repository/platform-${platform_rev}.zip"
curl_fetch "$platform_zip_url" "$staging/platform.zip"

mkdir -p "$staging/platform-unpack"
unzip -q "$staging/platform.zip" -d "$staging/platform-unpack"

# Google ships platform-N.zip with a top-level "android-N/" directory inside.
platform_src=""
for candidate in "$staging/platform-unpack"/*; do
    if [[ -d "$candidate" && -f "$candidate/android.jar" ]]; then
        platform_src="$candidate"
        break
    fi
done
[[ -n "$platform_src" ]] || {
    echo "Could not locate platform tree (expected android.jar)" >&2
    find "$staging/platform-unpack" -maxdepth 2 -type d
    exit 2
}

# --- 2. Google build-tools zip ----------------------------------------------
# Google's actual URL (per repository2-3.xml as of 36.0.0) is
#   https://dl.google.com/android/repository/build-tools_r36_linux.zip
# i.e. underscore separators, MAJOR-only revision (no .0.0). Strip everything
# from the first dot in $sdk_version to derive the major. Allow a full-URL
# override via env BUILD_TOOLS_URL for future scheme changes.
bt_zip_url="${BUILD_TOOLS_URL:-https://dl.google.com/android/repository/build-tools_r${sdk_version%%.*}_linux.zip}"
curl_fetch "$bt_zip_url" "$staging/build-tools.zip"

mkdir -p "$staging/bt-unpack"
unzip -q "$staging/build-tools.zip" -d "$staging/bt-unpack"

# Google's build-tools zip wraps everything in android-<API>/ (e.g. android-16).
bt_src=""
for candidate in "$staging/bt-unpack"/*; do
    if [[ -d "$candidate" && -f "$candidate/lib/d8.jar" ]]; then
        bt_src="$candidate"
        break
    fi
done
[[ -n "$bt_src" ]] || {
    echo "Could not locate build-tools tree (expected lib/d8.jar)" >&2
    find "$staging/bt-unpack" -maxdepth 3 -type f -name "d8.jar"
    exit 2
}

# --- 3. Termux aapt2 .deb (real Android-native binary) ----------------------
deb_name="aapt2_${aapt2_version}_${termux_arch}.deb"
deb_url="$mirror/pool/main/a/aapt2/$deb_name"
curl_fetch "$deb_url" "$staging/$deb_name"

echo "Extracting aapt2 .deb ..."
mkdir -p "$staging/deb"
(cd "$staging/deb" && ar x "$staging/$deb_name")

if   [[ -f "$staging/deb/data.tar.xz"  ]]; then deb_data="data.tar.xz"
elif [[ -f "$staging/deb/data.tar.zst" ]]; then deb_data="data.tar.zst"
elif [[ -f "$staging/deb/data.tar"     ]]; then deb_data="data.tar"
else echo ".deb contained unknown data.tar variant" >&2; ls "$staging/deb"; exit 2; fi

mkdir -p "$staging/deb/unpack"
tar -xf "$staging/deb/$deb_data" -C "$staging/deb/unpack"

aapt2_bin="$staging/deb/unpack/data/data/com.termux/files/usr/bin/aapt2"
[[ -f "$aapt2_bin" ]] || {
    echo "aapt2 binary not found in expected Termux location" >&2
    find "$staging/deb/unpack" -name "aapt2" 2>/dev/null
    exit 2
}

# --- 4. Compose target tree -------------------------------------------------
out_root="$staging/out"
plat_dst="$out_root/platforms/android-36"
bt_dst="$out_root/build-tools/${sdk_version}"
lic_dst="$out_root/licenses"

mkdir -p "$plat_dst" "$bt_dst" "$lic_dst"

# 4a. Platform: android.jar + data/ subtree.
cp -a "$platform_src/android.jar" "$plat_dst/"
if [[ -d "$platform_src/data" ]]; then
    cp -a "$platform_src/data" "$plat_dst/"
fi
# Bonus carry-overs that don't bloat much and AGP/lint occasionally references.
for f in core-for-system-modules.jar framework.aidl optional uiautomator.jar; do
    [[ -e "$platform_src/$f" ]] && cp -a "$platform_src/$f" "$plat_dst/" || true
done

# 4b. Build-tools.
# d8.jar lives under build-tools/<API>/lib/d8.jar in Google's zip.
cp -a "$bt_src/lib/d8.jar" "$bt_dst/d8.jar"
# apksigner.jar similarly under lib/.
cp -a "$bt_src/lib/apksigner.jar" "$bt_dst/apksigner.jar"
# Carry the full lib/ subtree — apksigner.jar in lib/ is where AGP looks first,
# and lib/ also contains apksigner deps (e.g. conscrypt-uber). Includes d8 deps.
cp -a "$bt_src/lib" "$bt_dst/lib"
# core-lambda-stubs.jar is optional but include if present.
[[ -f "$bt_src/core-lambda-stubs.jar" ]] && cp -a "$bt_src/core-lambda-stubs.jar" "$bt_dst/" || true

# aapt2 from Termux deb. chmod 0755.
cp "$aapt2_bin" "$bt_dst/aapt2"
chmod 0755 "$bt_dst/aapt2"

# 4b'. Bundle aapt2's runtime .so deps next to the binary.
#
# Termux's aapt2 dynamically links against libc++_shared.so, abseil-cpp's
# libabsl_*.so, and libprotobuf.so. The binary's DT_RUNPATH points at
# /data/data/com.termux/files/usr/lib/, which doesn't exist under VibeApp's
# UID — so without bundling these, exec succeeds but the dynamic linker
# fails with `library "libabsl_...so" not found`. Same failure mode as the
# JDK runtime deps we hit during Phase 2a.
#
# The install-side wiring (LD_LIBRARY_PATH pointing at this dir) lands in
# Phase 2d Task 3.
fetch_termux_lib() {
    local pkg_path="$1" deb_name="$2"
    # URL-encode '+' and ':' in case the deb name contains them (libc++,
    # epoch-prefixed packages like libprotobuf 2:33.1-1).
    local enc_path enc_name
    enc_path="${pkg_path//+/%2B}"
    enc_name="${deb_name//+/%2B}"
    enc_name="${enc_name//:/%3A}"
    local url="$mirror/pool/main/$enc_path/$enc_name"
    local dep_stage="$staging/dep-$(basename "$pkg_path")-$$-$RANDOM"
    mkdir -p "$dep_stage"
    echo "Fetching runtime dep: $deb_name ..."
    curl -fSL --retry 3 -o "$dep_stage/raw.deb" "$url"
    (cd "$dep_stage" && ar x raw.deb)
    if   [[ -f "$dep_stage/data.tar.xz"  ]]; then tar -xf "$dep_stage/data.tar.xz"  -C "$dep_stage"
    elif [[ -f "$dep_stage/data.tar.zst" ]]; then tar -xf "$dep_stage/data.tar.zst" -C "$dep_stage"
    else tar -xf "$dep_stage/data.tar"     -C "$dep_stage"; fi
    # Termux installs libs under $PREFIX/lib. Copy every libX.so* from there
    # into $bt_dst alongside aapt2.
    find "$dep_stage/data/data/com.termux/files/usr/lib" -maxdepth 1 -name "lib*.so*" 2>/dev/null \
        | while read -r lib; do
            cp -a "$lib" "$bt_dst/"
        done
}

# Versions defaulted at top of script; override via env if upstream moves.
fetch_termux_lib "libc/libc++"      "libc++_${libcxx_version}_${termux_arch}.deb"
fetch_termux_lib "a/abseil-cpp"     "abseil-cpp_${abseil_version}_${termux_arch}.deb"
fetch_termux_lib "libp/libprotobuf" "libprotobuf_${protobuf_version}_${termux_arch}.deb"
fetch_termux_lib "a/aapt"           "aapt_${aapt_version}_${termux_arch}.deb"
fetch_termux_lib "libe/libexpat"    "libexpat_${libexpat_version}_${termux_arch}.deb"
fetch_termux_lib "libp/libpng"      "libpng_${libpng_version}_${termux_arch}.deb"
fetch_termux_lib "f/fmt"            "fmt_${fmt_version}_${termux_arch}.deb"
fetch_termux_lib "libz/libzopfli"   "libzopfli_${libzopfli_version}_${termux_arch}.deb"
fetch_termux_lib "z/zlib"           "zlib_${zlib_version}_${termux_arch}.deb"

# Sanity-check: walk DT_NEEDED on aapt2 AND every bundled .so and verify each
# non-Bionic dep was bundled (a soname like libfoo.so.1 must match a file
# named libfoo.so.1 OR libfoo.so* in $bt_dst). Soft-fails (warns) if no
# readelf is available — the validation re-runs on-device anyway.
readelf_bin=""
if command -v llvm-readelf >/dev/null 2>&1; then readelf_bin="llvm-readelf"
elif command -v readelf      >/dev/null 2>&1; then readelf_bin="readelf"
fi
if [[ -n "$readelf_bin" ]]; then
    bionic_provided="libc.so libdl.so liblog.so libm.so libstdc++.so libc++_shared.so libandroid.so libz.so libjnigraphics.so libnetd_client.so libEGL.so libGLESv1_CM.so libGLESv2.so libGLESv3.so"
    missing=""
    seen_files="$bt_dst/aapt2"
    while IFS= read -r f; do seen_files="$seen_files $f"; done < <(find "$bt_dst" -maxdepth 1 -name "*.so*" -type f)
    for f in $seen_files; do
        needed_libs="$("$readelf_bin" -d "$f" 2>/dev/null \
            | awk '/NEEDED/ {gsub(/[\[\]]/, "", $NF); print $NF}')"
        for lib in $needed_libs; do
            skip=0
            for b in $bionic_provided; do
                [[ "$lib" == "$b" ]] && { skip=1; break; }
            done
            [[ $skip -eq 1 ]] && continue
            # Match either the exact soname or the unversioned base.
            if ls "$bt_dst"/"$lib" >/dev/null 2>&1; then continue; fi
            base="${lib%%.so*}.so"
            if ls "$bt_dst"/"$base"* >/dev/null 2>&1; then continue; fi
            missing="$missing $lib(<-$(basename "$f"))"
        done
    done
    # Dedup.
    missing="$(echo "$missing" | tr ' ' '\n' | sort -u | tr '\n' ' ')"
    if [[ -n "${missing// /}" ]]; then
        echo "WARN: DT_NEEDED entries not bundled and not in Bionic stdset:$missing" >&2
        echo "WARN: aapt2 (or a transitive lib) will fail at runtime. Bundle these." >&2
    else
        echo "aapt2 runtime-dep closure OK ($(echo "$seen_files" | wc -w | tr -d ' ') ELF files scanned)."
    fi
else
    echo "NOTE: no readelf on host; skipped aapt2 DT_NEEDED sanity check." >&2
fi

# Shell wrappers for d8 / apksigner so callers expecting CLI tools work.
cat > "$bt_dst/d8" <<'WRAP'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
exec java -cp "$here/d8.jar" com.android.tools.r8.D8 "$@"
WRAP
chmod 0755 "$bt_dst/d8"

cat > "$bt_dst/apksigner" <<'WRAP'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$here/apksigner.jar" "$@"
WRAP
chmod 0755 "$bt_dst/apksigner"

# source.properties (exactly per spec).
cat > "$bt_dst/source.properties" <<'EOF'
Pkg.Revision=36.0.0
#Pkg.License=android-sdk-license
EOF

# 4c. package.xml descriptors (AGP-accepted schema).
cat > "$plat_dst/package.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:sdk-repository xmlns:ns2="http://schemas.android.com/sdk/android/repo/repository2/03">
  <localPackage path="platforms;android-36">
    <type-details xsi:type="ns3:platformDetailsType"
                  xmlns:ns3="http://schemas.android.com/sdk/android/repo/repository2/03"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      <api-level>36</api-level>
    </type-details>
    <revision><major>1</major></revision>
    <display-name>Android SDK Platform 36</display-name>
  </localPackage>
</ns2:sdk-repository>
EOF

cat > "$bt_dst/package.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:sdk-repository xmlns:ns2="http://schemas.android.com/sdk/android/repo/repository2/03">
  <localPackage path="build-tools;36.0.0">
    <type-details xsi:type="ns3:genericDetailsType"
                  xmlns:ns3="http://schemas.android.com/sdk/android/repo/repository2/03"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
    <revision><major>36</major><minor>0</minor><micro>0</micro></revision>
    <display-name>Android SDK Build-Tools 36.0.0</display-name>
  </localPackage>
</ns2:sdk-repository>
EOF

# 4d. Licenses.
cp "$license_src" "$lic_dst/android-sdk-license"

# --- 5. Pack ----------------------------------------------------------------
out="$artifacts_dir/android-sdk-${sdk_version}-${abi}.tar.gz"
# Tar contents at root (no wrapper dir). Post-extract the install dir directly
# contains platforms/ build-tools/ licenses/.
(cd "$out_root" && tar -cf - .) | gzip -9 -c > "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
