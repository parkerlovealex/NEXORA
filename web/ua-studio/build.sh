#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOTLIN_JS="${KOTLIN_JS:-$(command -v kotlinc-js || true)}"
if [[ -z "$KOTLIN_JS" ]]; then
  echo "kotlinc-js was not found. Install Kotlin/JS 1.9+ and retry." >&2
  exit 1
fi
KOTLIN_HOME="$(cd "$(dirname "$KOTLIN_JS")/.." && pwd)"
OUT="$HERE/.build"
rm -rf "$OUT"
mkdir -p "$OUT"
"$KOTLIN_JS" "$HERE/src/Main.kt" \
  -Xir-produce-js \
  -ir-output-dir "$OUT" \
  -ir-output-name ua-studio \
  -module-kind plain \
  -main call \
  -libraries "$KOTLIN_HOME/lib/kotlin-stdlib-js.jar"
cp "$OUT/ua-studio.js" "$HERE/ua-studio.js"
if command -v node >/dev/null 2>&1; then node --check "$HERE/ua-studio.js"; fi
echo "Built $HERE/ua-studio.js"
