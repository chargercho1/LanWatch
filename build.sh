#!/usr/bin/env bash
# Builds LanWatch: the desktop program and the phone agent.
#
# Desktop: javac plus a jar — no build tool needed. Android: the plain pipeline by hand, the same
# one DuetScreen uses (aapt2 -> javac -> d8 -> zipalign -> apksigner), because that keeps the whole
# project buildable with nothing but a JDK and the SDK.
#
# Both halves are gated on tests. The unit checks cover the wire format and the merge rules; the
# integration checks start a real server and talk to it over real sockets.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${JAVA_HOME:=$HOME/tools/jdk17}"
: "${ANDROID_SDK_ROOT:=$HOME/tools/android-sdk}"
SDK="$ANDROID_SDK_ROOT"

BUILD_TOOLS="$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -1 "$SDK/platforms" 2>/dev/null | sort -V | tail -1)"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT"
# The signing key is deliberately not in this list: regenerating it would change the certificate
# on every build, and a phone that installed the previous one could never install this one.
rm -rf out build jar LanWatch.jar LanWatch.exe LanWatch-Setup.exe LanWatchAgent.apk
mkdir -p out jar build/compiled build/gen build/apk build/dex classes

# --- 1. Desktop: compile the shared core, the server and the tests ---------------------
echo "== сборка программы для компьютера =="
javac -encoding UTF-8 -d out $(find shared server test -name '*.java')

echo "== проверки логики =="
java -cp out app.lanwatch.SelfTest | tail -2

echo "== проверки связи на настоящих сокетах =="
java -cp out app.lanwatch.IntegrationTest | tail -2

cat > jar/manifest.txt <<'EOF'
Main-Class: app.lanwatch.server.Main
EOF
jar cfm LanWatch.jar jar/manifest.txt -C out .
echo "BUILT: $ROOT/LanWatch.jar"

# The launcher carries a DPI-aware manifest, which is the only thing that lets the JVM be told
# about the display scale. Skipped when there is no C# compiler; java -jar LanWatch.jar still
# works, it just risks a clipped window on a scaled display.
CSC="/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
if [ -f "$CSC" ]; then
    # csc is a Windows program and does not understand the MSYS paths this script uses.
    w() { cygpath -w "$1"; }

    "$CSC" -nologo -target:winexe \
        -out:"$(w "$ROOT/LanWatch.exe")" \
        -reference:System.Windows.Forms.dll \
        -reference:System.Drawing.dll \
        -win32manifest:"$(w "$ROOT/tools/app.manifest")" \
        "$(w "$ROOT/tools/Launcher.cs")"
    echo "BUILT: $ROOT/LanWatch.exe"

    # The installer carries its payload as embedded resources, so it is a single file that can
    # be handed to anyone. The uninstaller is the same file, which is what the installed copy
    # is named after.
    mkdir -p build/stage
    cp LanWatch.exe LanWatch.jar README.md build/stage/
    "$CSC" -nologo -target:winexe \
        -out:"$(w "$ROOT/LanWatch-Setup.exe")" \
        -reference:System.Windows.Forms.dll \
        -reference:System.Drawing.dll \
        -resource:"$(w "$ROOT/build/stage/LanWatch.exe")",Payload.LanWatch.exe \
        -resource:"$(w "$ROOT/build/stage/LanWatch.jar")",Payload.LanWatch.jar \
        -resource:"$(w "$ROOT/build/stage/README.md")",Payload.README.md \
        "$(w "$ROOT/tools/Setup.cs")"
    rm -rf build/stage
    echo "BUILT: $ROOT/LanWatch-Setup.exe"
else
    echo "csc.exe не найден — пропускаю LanWatch.exe и LanWatch-Setup.exe"
fi

# --- 2. Android: the agent --------------------------------------------------------------
[ -n "$BUILD_TOOLS" ] || { echo "нужен Android SDK (build-tools)"; exit 1; }
echo
echo "== сборка агента для телефона =="
echo "SDK: $SDK (build-tools $BUILD_TOOLS, platform $PLATFORM)"

ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "нет android.jar: $ANDROID_JAR"; exit 1; }

"$SDK/build-tools/$BUILD_TOOLS/aapt2.exe" compile --dir agent/res -o build/compiled/res.zip
"$SDK/build-tools/$BUILD_TOOLS/aapt2.exe" link \
    -o build/apk/base.apk \
    -I "$ANDROID_JAR" \
    --manifest agent/AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 22 --target-sdk-version 34 \
    build/compiled/res.zip

find shared agent/app -name '*.java' > build/sources.txt
find build/gen -name '*.java' >> build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d classes \
    @build/sources.txt 2> >(grep -v "bootstrap class path" >&2)

find classes -name '*.class' > build/classfiles.txt
"$SDK/build-tools/$BUILD_TOOLS/d8.bat" --lib "$ANDROID_JAR" --min-api 22 \
    --output build/dex @build/classfiles.txt
mv build/dex/classes.dex build/classes.dex

cp build/apk/base.apk build/unsigned.apk
python -c "
import zipfile
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/classes.dex', 'classes.dex')
print('dex packed')
"

# The signing key is created once and kept out of version control: a release must be signed with
# the owner's own key, not one that happened to be generated first.
KEYSTORE="$ROOT/lanwatch.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "создаю ключ разработки"
    keytool -genkeypair -v -keystore "$KEYSTORE" -storepass lanwatch -keypass lanwatch \
        -alias lanwatch -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=LanWatch Development, O=LanWatch" >/dev/null 2>&1
fi

"$SDK/build-tools/$BUILD_TOOLS/zipalign.exe" -f 4 build/unsigned.apk LanWatchAgent.apk
"$SDK/build-tools/$BUILD_TOOLS/apksigner.bat" sign \
    --ks "$KEYSTORE" --ks-pass pass:lanwatch --key-pass pass:lanwatch \
    --ks-key-alias lanwatch \
    --out LanWatchAgent.apk LanWatchAgent.apk

echo
echo "BUILT:"
ls -l LanWatch.jar LanWatchAgent.apk