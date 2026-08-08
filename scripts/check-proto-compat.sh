#!/usr/bin/env bash
# Guards the one rule that breaks every client at once:
#   the wire schema is ADDITIVE-ONLY, forever.
#
# A renumbered or repurposed field silently corrupts data on every phone
# running an older build — and we assume a 12-month tail of un-updated
# clients as permanent policy, not a transition.
#
# Usage: scripts/check-proto-compat.sh [base-ref]   (default: origin/main)
set -euo pipefail

BASE="${1:-origin/main}"
PROTO="proto/safesy/v1/telemetry.proto"

if ! git rev-parse --verify "$BASE" >/dev/null 2>&1; then
  echo "base ref $BASE not found; skipping compatibility check"
  exit 0
fi

if ! git show "$BASE:$PROTO" > /tmp/proto-base.proto 2>/dev/null; then
  echo "no baseline $PROTO on $BASE (new file); skipping"
  exit 0
fi

# Extract "MessageOrEnum.fieldname = N" pairs so a renumber is detectable.
extract() {
  awk '
    /^(message|enum) / { ctx=$2; gsub(/[^A-Za-z0-9_]/,"",ctx) }
    /=[[:space:]]*[0-9]+[[:space:]]*;/ {
      line=$0
      sub(/\/\/.*/,"",line)
      if (match(line, /([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*[0-9]+/)) {
        pair=substr(line, RSTART, RLENGTH)
        gsub(/[[:space:]]/,"",pair)
        print ctx "." pair
      }
    }' "$1" | sort -u
}

extract /tmp/proto-base.proto > /tmp/base-fields.txt
extract "$PROTO"              > /tmp/head-fields.txt

# A field present in base but absent in head = removed or renumbered.
if REMOVED=$(comm -23 /tmp/base-fields.txt /tmp/head-fields.txt) && [ -n "$REMOVED" ]; then
  echo "❌ Wire-compatibility break — these fields were removed or renumbered:"
  echo "$REMOVED" | sed 's/^/    /'
  echo
  echo "The schema is additive-only. To retire a field, leave its number in"
  echo "place and add an explicit 'reserved' entry instead."
  exit 1
fi

echo "✅ proto is backward-compatible with $BASE"
