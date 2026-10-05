#!/usr/bin/env bash
# 重建 + 校验：签名 / 素材哈希 / dex 标记。
# 用法：bash verify.sh [输出 apk 路径]
# 任何一步失败都退出码非 0，绝不"静默通过"。
set -u
PROJ="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$PROJ/out/dshpet.apk}"
FAIL=0

ok()   { echo "✅ $1"; }
bad()  { echo "❌ $1"; FAIL=1; }

echo "=== 1/5 构建 ==="
if bash "$PROJ/build.sh" "$PROJ" "$APK" > /tmp/dshpet-build.log 2>&1; then
  ok "构建成功：$APK"
else
  bad "构建失败（日志 /tmp/dshpet-build.log）"
  tail -20 /tmp/dshpet-build.log
  exit 1
fi

echo "=== 2/5 签名 ==="
SIG="$(apksigner verify -v "$APK" 2>&1)"
echo "$SIG" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" \
  && ok "v2 签名校验通过" || bad "v2 签名校验失败"
echo "$SIG" | grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" \
  && ok "v3 签名校验通过" || bad "v3 签名校验失败"

echo "=== 3/5 素材与上游一致性（SHA-256） ==="
TMP="$(mktemp -d)"
unzip -q -o "$APK" 'assets/*' 'res/raw/*' -d "$TMP" 2>/dev/null
if [ ! -f "$PROJ/assets.sha256" ]; then
  bad "缺少 assets.sha256 基线文件"
else
  while read -r expected name; do
    [ -z "$name" ] && continue
    case "$name" in
      assets/*)  target="$TMP/$name" ;;
      res/raw/*) target="$TMP/$name" ;;
      *)         target="$TMP/assets/$(basename "$name")" ;;
    esac
    if [ ! -f "$target" ]; then
      bad "$name 未打进 APK"
      continue
    fi
    actual="$(sha256sum "$target" | cut -d' ' -f1)"
    if [ "$actual" = "$expected" ]; then
      ok "$name 与上游逐字节一致"
    else
      bad "$name 与基线不一致（期望 ${expected:0:12}… 实际 ${actual:0:12}…）"
    fi
  done < "$PROJ/assets.sha256"
fi
rm -rf "$TMP"

echo "=== 4/5 dex 关键类标记 ==="
DEXSTR="$(unzip -p "$APK" classes.dex | strings)"
for marker in PetModel PetRenderer PetService PetTuning UpstreamInfo BalanceClient SelfTests \
              PetBubbleEditModel PetDragListLayout FlowLayout PetBubbleEditorActivity PeakValley \
              SpendLedger RichText; do
  echo "$DEXSTR" | grep -q "$marker" && ok "dex 含 $marker" || bad "dex 缺少 $marker"
done

echo "=== 5/5 版本号 ==="
VER="$(grep -o 'VERSION = "[^"]*"' "$PROJ/java/com/dsh/balancepet/MainActivity.java" | head -1)"
COMMIT="$(grep -o 'UPSTREAM_COMMIT = "[^"]*"' "$PROJ/java/com/dsh/balancepet/UpstreamInfo.java" | head -1)"
echo "     $VER"
echo "     上游基线 $COMMIT"
[ -n "$VER" ] && [ -n "$COMMIT" ] && ok "版本与基线已记录" || bad "版本或基线缺失"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "=== 全部通过 ✅ ==="
else
  echo "=== 存在失败项 ❌ ==="
fi
exit "$FAIL"