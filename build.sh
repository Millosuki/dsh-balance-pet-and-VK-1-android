#!/usr/bin/env bash
# Generic Android APK builder (no Gradle / no AndroidX).
# Usage: build.sh <project_dir> [out_apk]
#   <project_dir> must contain: AndroidManifest.xml, res/, java/  (java optional)
set -u

PROJ="$(cd "${1:?project dir required}" && pwd)"
OUT="${2:-$PROJ/build/app.apk}"
case "$OUT" in
  /*) : ;;                       # 已是绝对路径
  *)  OUT="$(pwd)/$OUT" ;;       # 相对路径要先固化，否则后续 cd 会把它解析错
esac
MIN_SDK="${MIN_SDK:-26}"
# NOTE: Debian's aapt2 (2.19) cannot read the resources.arsc of API 33+ platform
# jars ("RES_TABLE_TYPE_TYPE entry offsets overlap"), so API 32 is the maximum
# usable -I framework jar in this offline toolchain.
TARGET_SDK="${TARGET_SDK:-32}"
ANDROID_JAR="${ANDROID_JAR:-/root/android-sdk/p32/android-12/android.jar}"
R8_JAR="${R8_JAR:-/root/tools/r8.jar}"
# 签名（release）：keystore / 别名 / 密码文件都在仓库之外，可用环境变量覆盖。
# 仓库里**不含**任何 keystore 与密码（见 .gitignore、docs/BUILD.md）。
KEYSTORE="${KEYSTORE:-/root/work/android/keys/dshpet-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-dshpet}"
KS_PASS_FILE="${KS_PASS_FILE:-/root/work/android/keys/signing.pass}"
KS_PASS="${KS_PASS:-$(cat "$KS_PASS_FILE" 2>/dev/null || echo android)}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"

for f in "$ANDROID_JAR" "$R8_JAR" "$KEYSTORE"; do
  [ -e "$f" ] || { echo "missing required file: $f" >&2; exit 2; }
done

WORK="$PROJ/build"
rm -rf "$WORK"
mkdir -p "$WORK/res-compiled" "$WORK/gen" "$WORK/classes" "$WORK/dex"
mkdir -p "$(dirname "$OUT")"

echo "[1/6] aapt2 compile"
aapt2 compile --dir "$PROJ/res" -o "$WORK/res.zip" || exit 1

echo "[2/6] aapt2 link"
aapt2 link -o "$WORK/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --java "$WORK/gen" \
  --auto-add-overlay \
  "$WORK/res.zip" || exit 1

if [ -d "$PROJ/assets" ] && [ -n "$(ls -A "$PROJ/assets" 2>/dev/null)" ]; then
  echo "[2b] assets"
  # 注意：APK 内部 assets 必须位于 zip 的 assets/ 前缀下，
  # 直接塞到根目录 AssetManager 是看不到的（实测：list("") 只剩系统目录）。
  ( cd "$PROJ" && zip -q -r -X "$WORK/base.apk" assets ) || exit 1
fi

SRCS=$(find "$PROJ/java" "$WORK/gen" -name '*.java' 2>/dev/null)
if [ -n "$SRCS" ]; then
  echo "[3/6] javac"
  # shellcheck disable=SC2086
  if ! javac -nowarn -encoding UTF-8 --release 11 -classpath "$ANDROID_JAR" \
        -d "$WORK/classes" $SRCS > "$WORK/javac.log" 2>&1; then
    echo "javac FAILED:" >&2
    cat "$WORK/javac.log" >&2
    exit 1
  fi
  grep -v '^Note:' "$WORK/javac.log" || true
  if [ -z "$(find "$WORK/classes" -name '*.class' 2>/dev/null)" ]; then
    echo "javac produced no classes" >&2; exit 1
  fi

  echo "[4/6] d8 (dex)"
  CLASSES=$(find "$WORK/classes" -name '*.class')
  # shellcheck disable=SC2086
  java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" \
       --lib "$ANDROID_JAR" --output "$WORK/dex" $CLASSES || exit 1
else
  echo "[3/6] no java sources; skipping javac+d8"
  mkdir -p "$WORK/dex"
fi

echo "[5/6] package"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
if [ -f "$WORK/dex/classes.dex" ]; then
  ( cd "$WORK/dex" && zip -q -X "$WORK/unsigned.apk" classes.dex ) || exit 1
fi
zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk" || exit 1

echo "[6/6] sign"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$WORK/aligned.apk" || exit 1
apksigner verify -v "$OUT" | head -5
ls -la "$OUT"