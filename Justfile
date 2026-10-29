# Cypher — developer commands (mirrors the bitchat Justfile shape).
# No recipe here ever touches or restores tracked files.

default:
    @just --list

# Verify toolchain + repository state.
check:
    cargo --version
    rustc --version
    cd android && ./gradlew --version | head -6

# Run the full Rust test suite (golden vectors + simulated mesh included).
test:
    cargo test --workspace

# Lint.
lint:
    cargo fmt --all -- --check
    cargo clippy --workspace --all-targets -- -D warnings

# Format.
fmt:
    cargo fmt --all

# Build the Rust core for all Android ABIs (needs ANDROID_NDK_HOME + cargo-ndk).
build-android:
    #!/usr/bin/env bash
    set -e
    for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
        cargo ndk -t "$target" -o android/app/src/main/jniLibs build --release -p cypher-android
    done
    echo "libcypher.so built for all ABIs into android/app/src/main/jniLibs"

# Assemble the debug APK (runs the Rust build via Gradle integration).
apk:
    cd android && ./gradlew :app:assembleDebug

# Remove build artifacts only (never tracked files).
clean:
    cargo clean
    rm -rf android/.gradle android/build android/app/build android/.cxx
