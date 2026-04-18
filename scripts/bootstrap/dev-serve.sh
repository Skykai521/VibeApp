#!/usr/bin/env bash
#
# Serves scripts/bootstrap/artifacts/ over http://localhost:8000 and
# maps it through to the connected Android device via
# `adb reverse tcp:8000 tcp:8000`.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
port="${PORT:-8000}"

[[ -d "$artifacts_dir" ]] || { echo "No artifacts; run build-*.sh first." >&2; exit 2; }

adb reverse "tcp:$port" "tcp:$port"
echo "adb reverse OK: device localhost:$port -> host :$port"
echo "Serving $artifacts_dir on http://localhost:$port ..."
echo "(Ctrl-C to stop; adb reverse is auto-removed on exit.)"
trap 'adb reverse --remove tcp:'"$port"' || true; exit' INT TERM
cd "$artifacts_dir"
python3 -m http.server "$port"
