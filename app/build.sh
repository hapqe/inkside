#!/bin/bash
# Builds the Inkside APK with Android SDK tools (no Gradle).
# Output: build/Inkside.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
JAVA_BIN="/opt/homebrew/opt/openjdk@17/bin"
OUT=build
KS="$HOME/.android/debug.keystore"
PKG=me/hapke/inkside

export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_BIN:$PATH"

[ -x "$BT/aapt2" ] || { echo "aapt2 not found in $BT"; exit 1; }
[ -f "$PLATFORM" ] || { echo "android-36 platform not found"; exit 1; }
[ -x "$JAVA_BIN/javac" ] || { echo "JDK 17 not found at $JAVA_BIN"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res_flat" "$OUT/aars" "$OUT/lib/arm64-v8a"

# Third-party libraries (ML Kit handwriting recognition and what it needs), fetched
# once by Gradle into libs/. Gradle only downloads them; this script does the rest.
LIBS=libs
if [ ! -f "$LIBS/.complete" ]; then
    echo "== fetching libraries (Gradle, one-off)"
    command -v gradle > /dev/null || { echo "gradle not found — needed once to fetch libs/"; exit 1; }
    rm -rf "$LIBS"
    gradle -q -p tools/mlkit-deps copyDeps
    touch "$LIBS/.complete"
fi

echo "== unpack libraries"
LIB_JARS=()
LIB_RES=()
LIB_PKGS=()
LIB_ASSETS=()
for aar in "$LIBS"/*.aar; do
    name=$(basename "$aar" .aar)
    d="$OUT/aars/$name"
    mkdir -p "$d"
    unzip -q -o "$aar" -d "$d"
    [ -f "$d/classes.jar" ] && LIB_JARS+=("$d/classes.jar")
    for extra in "$d"/libs/*.jar; do [ -f "$extra" ] && LIB_JARS+=("$extra"); done
    pkg=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$d/AndroidManifest.xml" | head -1)
    [ -n "$pkg" ] && LIB_PKGS+=("$pkg")
    if [ -d "$d/res" ] && [ -n "$(ls -A "$d/res")" ]; then
        "$BT/aapt2" compile --dir "$d/res" -o "$OUT/aars/$name.res.zip"
        LIB_RES+=(-R "$OUT/aars/$name.res.zip")
    fi
    # ML Kit reads its model catalogue from these.
    [ -d "$d/assets" ] && LIB_ASSETS+=(-A "$d/assets")
    # Only the tablet's ABI: the recogniser alone is 7-9 MB per ABI.
    [ -d "$d/jni/arm64-v8a" ] && cp "$d"/jni/arm64-v8a/*.so "$OUT/lib/arm64-v8a/"
done
for jar in "$LIBS"/*.jar; do LIB_JARS+=("$jar"); done
LIB_CP=$(IFS=:; echo "${LIB_JARS[*]}")
EXTRA_PKGS=$(IFS=:; echo "${LIB_PKGS[*]}")

echo "== aapt2: resources + manifest"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
# DEMO_ID=me.hapke.inkside.demo builds a second, separate install (own data, nothing shared with
# the real app) for screenshots and demos: the application id and every id derived from it change.
MANIFEST=AndroidManifest.xml
RENAME=()
if [ -n "${DEMO_ID:-}" ]; then
    MANIFEST="$OUT/AndroidManifest.demo.xml"
    sed "s/me\.hapke\.inkside\./$DEMO_ID./g" AndroidManifest.xml > "$MANIFEST"
    RENAME=(--rename-manifest-package "$DEMO_ID")
fi
# ${a[@]+...}: macOS's bash 3.2 treats an empty array as unset under `set -u`.
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" ${RENAME[@]+"${RENAME[@]}"} \
    --manifest "$MANIFEST" "${LIB_RES[@]}" -R "$OUT/res.zip" --auto-add-overlay \
    --extra-packages "$EXTRA_PKGS" \
    -A assets "${LIB_ASSETS[@]}" \
    --java "$OUT/gen"

echo "== javac"
find src "$OUT/gen" -name '*.java' | sort > "$OUT/sources.txt"
"$JAVA_BIN/javac" --release 11 -cp "$PLATFORM:$LIB_CP" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8"
"$BT/d8" --release --min-api 30 --lib "$PLATFORM" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') "${LIB_JARS[@]}" 2>&1 \
    | grep -v -E "^(Warning|Info)|Missing class|missing EnclosingMethod|^  " || true

echo "== package"
(cd "$OUT/dex" && zip -q ../base.apk classes*.dex)
(cd "$OUT" && zip -q -r base.apk lib)

echo "== align + sign"
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
    echo "-- generating debug keystore"
    mkdir -p "$HOME/.android"
    "$JAVA_BIN/keytool" -genkeypair -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi
# RELEASE=1 signs with the release key from ~/.config/inkside-release/keystore.env (see
# RELEASING.md); otherwise the debug key is used. Anyone updating an install needs the same key
# every time, so the release key is never in the repository.
if [ "${RELEASE:-0}" = "1" ]; then
    RELEASE_ENV="${INKSIDE_RELEASE_ENV:-$HOME/.config/inkside-release/keystore.env}"
    [ -f "$RELEASE_ENV" ] || { echo "no release key: $RELEASE_ENV (see RELEASING.md)"; exit 1; }
    # shellcheck disable=SC1090
    . "$RELEASE_ENV"
    "$BT/apksigner" sign --ks "$INKSIDE_KEYSTORE" --ks-key-alias "$INKSIDE_KEY_ALIAS" \
        --ks-pass "pass:$INKSIDE_KEYSTORE_PASSWORD" --key-pass "pass:$INKSIDE_KEYSTORE_PASSWORD" \
        --out "$OUT/Inkside.apk" "$OUT/aligned.apk"
else
    "$BT/apksigner" sign --ks "$KS" --ks-key-alias androiddebugkey \
        --ks-pass pass:android --key-pass pass:android \
        --out "$OUT/Inkside.apk" "$OUT/aligned.apk"
fi
"$BT/apksigner" verify "$OUT/Inkside.apk"

echo "== done: $OUT/Inkside.apk"
ls -la "$OUT/Inkside.apk"
