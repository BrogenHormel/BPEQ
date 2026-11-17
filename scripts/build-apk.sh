#!/usr/bin/env bash
# Builds a signed release APK: bumps versionCode, optionally sets versionName, runs all unit tests,
# then copies the APK to ~/peq-builds/peq-<versionName>.apk.
#   scripts/build-apk.sh            # bump versionCode only
#   scripts/build-apk.sh 0.2.0      # also set versionName
set -euo pipefail
cd "$(dirname "$0")/.."
props=version.properties
code=$(grep '^versionCode=' $props | cut -d= -f2)
name=${1:-$(grep '^versionName=' $props | cut -d= -f2)}
code=$((code + 1))
printf 'versionCode=%s\nversionName=%s\n' "$code" "$name" > $props
echo "Building BPEQ $name (versionCode $code)"
[ -f "$HOME/peq-signing/signing.properties" ] || { echo "missing ~/peq-signing/signing.properties"; exit 1; }
./gradlew --console=plain -q :core:test :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
"$HOME/Android/Sdk/build-tools/36.0.0/apksigner" verify --print-certs "$apk" | grep -m1 'SHA-256' || true
mkdir -p "$HOME/peq-builds"
cp "$apk" "$HOME/peq-builds/peq-$name.apk"
echo "OK -> $HOME/peq-builds/peq-$name.apk ($(du -h "$apk" | cut -f1))"
