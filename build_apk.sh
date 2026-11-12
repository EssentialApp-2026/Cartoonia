#!/usr/bin/env bash
# =====================================================================
#  Compila SAM.apk in locale, senza Gradle, senza Android Studio e senza servizi online.
#  Strumenti: aapt2, dalvik-exchange (dx), zipalign, apksigner (Ubuntu:
#  apt install aapt dalvik-exchange zipalign apksigner), Kotlin 1.9.25,
#  android.jar API 35.
#
#  Uso:  ANDROID_JAR=.../android-35/android.jar RES_JAR=.../android-33/android.jar \
#        KOTLINC=.../kotlinc/bin/kotlinc ./build_apk.sh
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build-manual"
: "${ANDROID_JAR:?imposta ANDROID_JAR (android.jar API 35)}"
: "${KOTLINC:?imposta KOTLINC (kotlinc 1.9.25)}"
# aapt2 di Ubuntu (2.19) non legge le risorse compatte di API 34+: per collegare le risorse
# si usa android.jar API 33 (gli ID pubblici sono stabili); il codice si compila contro API 35.
RES_JAR="${RES_JAR:-$ANDROID_JAR}"
JNA_JAR="$APP/libs/jna-5.12.1.jar"
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

step() { echo; echo "=== $1"; }

rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex,pkg/lib/arm64-v8a}

step "1/7 Manifest per la build"
# aggiunge package ed extractNativeLibs, toglie gli attributi tools: (servono solo a Gradle)
python3 - "$APP/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r'\s+xmlns:tools="[^"]*"', '', s)
s = re.sub(r'\s+tools:\w+="[^"]*"', '', s)
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
              '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.essentialapp.sam"', 1)
s = s.replace('<application', '<application\n        android:extractNativeLibs="true"', 1)
open(sys.argv[2], "w", encoding="utf-8").write(s)
PY

step "2/7 Risorse (aapt2)"
aapt2 compile --dir "$APP/src/main/res" -o "$OUT/res/compiled.zip"
aapt2 link -I "$RES_JAR" --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 31 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" --auto-add-overlay \
    -o "$OUT/base.apk" "$OUT/res/compiled.zip"

step "3/7 Codice (R.java + Kotlin 1.9, lambda come classi per dx)"
javac --release 8 -nowarn -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')
"$KOTLINC" -no-jdk -no-reflect -jvm-target 1.8 \
    -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
    -cp "$ANDROID_JAR:$JNA_JAR:$OUT/classes" \
    -d "$OUT/classes" $(find "$APP/src/main/java" -name '*.kt') 2>&1 | grep -v '^warning:' || true
test -f "$OUT/classes/com/essentialapp/sam/MainActivity.class" || { echo "ERRORE: compilazione Kotlin fallita"; exit 1; }
if grep -rl LambdaMetafactory --include='*.class' "$OUT/classes" >/dev/null; then
    echo "ERRORE: trovate lambda invokedynamic nel codice dell'app"; exit 1
fi

step "4/7 Dex (dx) — app + kotlin-stdlib + JNA"
mkdir -p "$OUT/stdlib" && (cd "$OUT/stdlib" && unzip -q -o "$STDLIB" -x 'META-INF/*')
mkdir -p "$OUT/jna" && (cd "$OUT/jna" && unzip -q -o "$JNA_JAR" '*.class' -x 'META-INF/*')
dalvik-exchange -JXmx3g --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" \
    "$OUT/classes" "$OUT/stdlib" "$OUT/jna"

step "5/7 Impacchettamento (dex + librerie native arm64)"
cp "$APP/src/main/jniLibs/arm64-v8a/"*.so "$OUT/pkg/lib/arm64-v8a/"
cp "$OUT/dex/classes.dex" "$OUT/pkg/"
EXTRA=""
# assets: il modello delle voci di Vosk (riconoscere chi parla), copiato in memoria alla prima accensione
if [ -d "$APP/src/main/assets" ] && [ -n "$(ls -A "$APP/src/main/assets")" ]; then
    cp -r "$APP/src/main/assets" "$OUT/pkg/assets"; EXTRA="assets"
fi
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/pkg" && zip -q -r "$OUT/unsigned.apk" classes.dex lib $EXTRA)

step "6/7 Allineamento e firma"
zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$APP/sam-release.jks" --ks-pass pass:samsam2026 --key-pass pass:samsam2026 \
    --ks-key-alias sam --min-sdk-version 31 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/SAM.apk" "$OUT/aligned.apk"

step "7/7 Verifica"
apksigner verify --min-sdk-version 31 "$OUT/SAM.apk" && echo "firma OK"
ls -la "$OUT/SAM.apk"
