#!/usr/bin/env bash
# 纯命令行构建脚本：aapt2 + javac + d8 + zipalign + apksigner，不依赖 Gradle / Android Studio。
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT_DIR:-$PROJ/build}"
SDK="${ANDROID_SDK_ROOT:-$HOME/apkbuild/sdk}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/apkbuild/tc/jdk}"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
export PATH="$JAVA_HOME/bin:$PATH"

APK_NAME="aiqiandao-v1.7.1.apk"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$JAVAC"; do
  [ -e "$f" ] || { echo "缺少构建组件: $f"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] 编译资源 aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "[2/7] 链接资源 aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[3/7] 编译 Java"
find "$PROJ/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
set +e
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e
grep -v "bootstrap class path" "$OUT/javac.log" || true
if [ "$JAVAC_RC" -ne 0 ]; then
  echo "Java 编译失败，详见 $OUT/javac.log"
  exit 1
fi

echo "[4/7] 转换 dex d8"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/7] 打包 classes.dex"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

echo "[6/7] 对齐 zipalign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
# 签名证书固定放在工程目录，不随 build 清理，保证每个版本可覆盖安装
mkdir -p "$PROJ/keystore"
KS="$PROJ/keystore/signin.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias signin -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=Marvis Signin, OU=Dev, O=Marvis, L=Shenzhen, ST=Guangdong, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME"
ls -lh "$OUT/$APK_NAME"
echo "构建完成: $OUT/$APK_NAME"
