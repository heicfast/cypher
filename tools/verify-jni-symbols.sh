#!/usr/bin/env bash
# Verify that every JNI symbol the Kotlin side declares (external fun in
# CypherBridge.kt) is exported by every checked-in libcypher.so.
#
# JNI symbol resolution is name-mangled from the fully-qualified class:
#   package chat.cypher.android.core + object CypherBridge + method name
#   => Java_chat_cypher_android_core_CypherBridge_<method>
# A missing symbol is an instant UnsatisfiedLinkError crash at runtime —
# this gate makes it a build failure instead.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE_KT="$REPO_ROOT/android/app/src/main/java/chat/cypher/android/core/CypherBridge.kt"
JNI_LIBS_DIR="$REPO_ROOT/android/app/src/main/jniLibs"

# The Kotlin object's fully-qualified name determines the mangled prefix.
KOTLIN_PACKAGE="chat.cypher.android.core"
KOTLIN_OBJECT="CypherBridge"
PREFIX="Java_$(echo "$KOTLIN_PACKAGE" | tr '.' '_')_${KOTLIN_OBJECT}_"

# --- extract the expected symbols from the Kotlin declarations -------------
# `external fun name(...)` declarations, in file order.
mapfile -t METHODS < <(
    sed -n 's/^[[:space:]]*external fun \([A-Za-z0-9_]*\).*/\1/p' "$BRIDGE_KT" | sort -u
)

if [ "${#METHODS[@]}" -eq 0 ]; then
    echo "ERROR: no external fun declarations found in $BRIDGE_KT" >&2
    exit 1
fi

echo "Kotlin declares ${#METHODS[@]} native methods on $KOTLIN_PACKAGE.$KOTLIN_OBJECT:"
for m in "${METHODS[@]}"; do
    echo "  - $m"
done
echo

# --- verify each .so exports every expected symbol --------------------------
fail=0
for so in "$JNI_LIBS_DIR"/*/libcypher.so; do
    [ -f "$so" ] || { echo "ERROR: missing $so" >&2; fail=1; continue; }
    abi="$(basename "$(dirname "$so")")"
    # Exported dynamic symbols only; skip the undefined ones (UND).
    exported="$(nm -D --defined-only "$so" 2>/dev/null | awk '{print $NF}' || true)"
    if [ -z "$exported" ]; then
        # nm may be unavailable; fall back to readelf.
        exported="$(readelf --dyn-syms -W "$so" | awk '$7 != "UND" {print $8}')"
    fi
    missing=0
    for m in "${METHODS[@]}"; do
        sym="${PREFIX}${m}"
        if ! grep -qx "$sym" <<< "$exported"; then
            echo "FAIL [$abi]: $so does not export $sym" >&2
            fail=1
            missing=1
        fi
    done
    total="$(grep -c "^${PREFIX}" <<< "$exported" || true)"
    if [ "$missing" -eq 0 ]; then
        echo "OK   [$abi]: ${#METHODS[@]}/${#METHODS[@]} declared methods exported (${total} JNI symbols total)"
    else
        echo "FAIL [$abi]: some declared methods missing (${total} JNI symbols total)" >&2
    fi
done

if [ "$fail" -ne 0 ]; then
    echo ""
    echo "JNI symbol verification FAILED — the app would crash with UnsatisfiedLinkError." >&2
    exit 1
fi
echo ""
echo "JNI symbol verification passed: every Kotlin external fun resolves in every ABI."
