#!/usr/bin/env bash
#
# Downloads Gradle's official -bin distribution and repacks as
# gradle-9.3.1-common.tar.gz for VibeApp's bootstrap. ABI-independent:
# one artifact covers all devices.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"

gradle_version="${GRADLE_VERSION:-9.3.1}"
gradle_dist_url="${GRADLE_DIST_URL:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --gradle-version) gradle_version="$2"; shift 2;;
        --dist-url) gradle_dist_url="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 [--gradle-version VER] [--dist-url URL]

Downloads the Gradle '-bin' distribution zip and repacks as
scripts/bootstrap/artifacts/gradle-<version>-common.tar.gz.

Defaults:
  gradle-version: $gradle_version
  dist-url:       $gradle_dist_url
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-gradle.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

# Resolve dist URL after arg parsing so --gradle-version takes effect.
if [[ -z "$gradle_dist_url" ]]; then
    gradle_dist_url="https://services.gradle.org/distributions/gradle-${gradle_version}-bin.zip"
fi

echo "Downloading $gradle_dist_url ..."
curl -fSL --retry 3 -o "$staging/gradle.zip" "$gradle_dist_url"

echo "Unzipping ..."
unzip -q "$staging/gradle.zip" -d "$staging/unpack"

gradle_src="$staging/unpack/gradle-$gradle_version"
[[ -d "$gradle_src" ]] || {
    echo "Expected $gradle_src not found in the unzipped dist." >&2
    find "$staging/unpack" -maxdepth 2 -type d | head
    exit 2
}

# Strip bits not needed at runtime. init.d/ and lib/plugins/ are empty
# until a user stages custom init scripts / plugins, so harmless to keep.
# Remove the shell wrappers — we launch via `java -cp` in Phase 2b+,
# and gradle-<version>/bin/gradle's '#!/usr/bin/env sh' shebang would
# hit the Phase 1d first-exec issue anyway.
rm -rf "$gradle_src/bin"

out="$artifacts_dir/gradle-${gradle_version}-common.tar.gz"
# Tar the CONTENTS at root (not wrapped in gradle-<version>/) so
# post-extraction layout is $PREFIX/opt/gradle-9.3.1/lib/... directly.
(cd "$gradle_src" && tar -cf - .) | gzip -9 -c > "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
