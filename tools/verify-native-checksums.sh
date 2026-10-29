#!/usr/bin/env bash
#
# Verify the checked-in Rust core libraries (libcypher.so) against the
# pinned SHA-256 manifest — the Cypher adaptation of bitchat's
# tools/arti-build/verify-checksums.sh production pipeline.
#
# Usage: tools/verify-native-checksums.sh
# Exit:  0 = libraries match the pinned manifest and contain no host paths.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CHECKSUM_FILE="$SCRIPT_DIR/native-SHA256SUMS"

if [ ! -f "$CHECKSUM_FILE" ]; then
  echo "error: missing native checksum manifest: $CHECKSUM_FILE" >&2
  exit 1
fi

cd "$PROJECT_ROOT"

if command -v sha256sum >/dev/null 2>&1; then
  (cd android && sha256sum -c "$CHECKSUM_FILE")
else
  (cd android && shasum -a 256 -c "$CHECKSUM_FILE")
fi

for library in android/app/src/main/jniLibs/*/libcypher.so; do
  if [ ! -f "$library" ]; then
    echo "error: missing native library: $library" >&2
    exit 1
  fi
  if strings "$library" | grep -Eq '/Users/|/home/z/|/home/runner/|[A-Za-z]:\\Users\\'; then
    echo "error: host-specific path detected in $library" >&2
    exit 1
  fi
done

echo "Native Cypher libraries match the pinned checksums and contain no host paths."
