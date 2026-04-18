#!/usr/bin/env bash
#
# Composes scripts/bootstrap/artifacts/manifest.json from the
# .tar.zst artifacts in that directory, then signs it into manifest.json.sig
# using the dev keypair.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
manifest="$artifacts_dir/manifest.json"
version="v2.0.0-dev"

[[ -d "$artifacts_dir" ]] || { echo "No artifacts dir; run build-*.sh first." >&2; exit 2; }

# Emit header
{
    echo "{"
    echo "  \"schemaVersion\": 1,"
    echo "  \"manifestVersion\": \"$version\","
    echo "  \"components\": ["
} > "$manifest"

# Enumerate components (grouped by prefix in the filename).
# Supported prefixes: "hello-" -> id "hello"; "jdk-17.0.13-" -> id "jdk-17.0.13".
first=1
for id in "hello" "jdk-17.0.13"; do
    # Collect matching artifacts
    entries=""
    for artifact in "$artifacts_dir/${id}-"*.tar.zst; do
        [[ -f "$artifact" ]] || continue
        fname=$(basename "$artifact")
        abi=$(echo "$fname" | sed -E "s/^${id}-([^.]+)\.tar\.zst$/\1/")
        size=$(stat -f %z "$artifact" 2>/dev/null || stat -c %s "$artifact")
        sha=$(shasum -a 256 "$artifact" | awk '{print $1}')
        entries="$entries        \"$abi\": { \"fileName\": \"$fname\", \"sizeBytes\": $size, \"sha256\": \"$sha\" },"$'\n'
    done
    [[ -z "$entries" ]] && continue
    # Trim trailing comma + newline
    entries="$(echo -n "${entries%$'\n'}" | sed '$ s/,$//')"

    version_field=""
    if [[ "$id" == "jdk-17.0.13" ]]; then version_field="17.0.13"; fi
    if [[ "$id" == "hello"       ]]; then version_field="1.0"; fi

    [[ $first -eq 1 ]] || echo "    ," >> "$manifest"
    {
        echo "    {"
        echo "      \"id\": \"$id\","
        echo "      \"version\": \"$version_field\","
        echo "      \"artifacts\": {"
        echo "$entries"
        echo "      }"
        echo "    }"
    } >> "$manifest"
    first=0
done

{
    echo "  ]"
    echo "}"
} >> "$manifest"

echo "wrote $manifest"

# Sign
kotlin "$script_dir/sign-manifest.kts" "$manifest"
