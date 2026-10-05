# 构建与验证（无 Gradle）

> 这是**能在没有 Android Studio、没有 Gradle 的机器上**跑通的构建方式：
> `aapt2` 编译资源 → `javac` 编译 → `d8` 转 dex → `zipalign` + `apksigner` 签名。
> 本项目就是在一台 Android 手机上的 proot Ubuntu 里这么构建的。

## 依赖

| 工具 | 说明 |
|---|---|
| JDK 17 | `javac` / `apksigner` 需要 |
| `aapt2` | Debian 的 aapt2 (2.19) 读不了 API 33+ platform jar 的 `resources.arsc`，所以 **targetSdk 固定 32** |
| `android.jar` | API 32 的 platform jar（`-I` 参数） |
| `r8.jar` | 只用里面的 `com.android.tools.r8.D8` 转 dex |
| `zipalign` / `apksigner` | 一般随 build-tools 一起来 |
| keystore | 用**仓库之外**的 release keystore（默认 `/root/work/android/keys/dshpet-release.jks`，密码在同目录 `signing.pass`）；**绝不提交进仓库** |

## 环境变量（都有默认值，按需覆盖）

```sh
ANDROID_JAR=${ANDROID_JAR:-/root/android-sdk/p32/android-12/android.jar}
R8_JAR=${R8_JAR:-/root/tools/r8.jar}
MIN_SDK=26   TARGET_SDK=32
# 签名（release）：默认指向**仓库之外**的 keystore + 密码文件；三者都能用环境变量覆盖
KEYSTORE=${KEYSTORE:-/root/work/android/keys/dshpet-release.jks}
KEY_ALIAS=${KEY_ALIAS:-dshpet}
KS_PASS_FILE=${KS_PASS_FILE:-/root/work/android/keys/signing.pass}
```

## 构建 / 验证

```sh
bash build.sh . out/dshpet.apk      # 只构建
bash verify.sh                      # 构建 + 5 项校验（推荐）
```

`verify.sh` 会依次检查：① 构建成功 ② v2/v3 签名 ③ **素材与上游逐字节一致**（`assets.sha256`）
④ dex 里关键类标记齐全 ⑤ 版本号与上游基线已记录。**任何一步失败都会以非 0 退出，不静默通过。**

## 安装

```sh
cp out/dshpet.apk /sdcard/Download/
# 然后（Shizuku / adb shell）：/sdcard 通常是 noexec，必须先进 /data/local/tmp
cp /sdcard/Download/dshpet.apk /data/local/tmp/ && pm install -r /data/local/tmp/dshpet.apk
```

## 签名（release）

正式签名用的是**仓库之外**的 keystore；`build.sh` 默认读 `/root/work/android/keys/`：

```sh
# 1) 生成（一次性）
mkdir -p /root/work/android/keys && chmod 700 /root/work/android/keys
PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
printf '%s' "$PASS" > /root/work/android/keys/signing.pass && chmod 600 /root/work/android/keys/signing.pass
keytool -genkeypair -v -storetype PKCS12 -keystore /root/work/android/keys/dshpet-release.jks \
  -alias dshpet -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=dshpet-release, O=dshpet, C=CN"

# 2) 日常构建直接用默认值（要覆盖就设 KEYSTORE / KEY_ALIAS / KS_PASS_FILE / KS_PASS）
bash verify.sh
```

⚠️ **keystore 一定要自己备份好**：Android 只认「是不是同一把钥匙」，**丢了就再也发不出可覆盖安装的更新**
（用户必须卸载重装）。仓库里**不含** keystore 与密码（见 `.gitignore`）。

同一把钥匙的证书指纹（核对下载到的 APK 是不是本项目的正式包）：

```
SHA-256: 5C:63:8F:9E:D8:58:8C:99:8A:99:49:6A:FC:4C:49:6F:4E:6D:36:9F:B3:62:CE:DA:22:92:DE:D9:82:C4:16:5F
```

⚠️ **换签名（debug → release，或换一把钥匙）＝ 必须卸载重装**，应用私有目录会被清空 →
请先在 App 里「通用 → 备份与恢复 → 备份到文件」存一份，装完再恢复。

## 自检（904 项离线断言）

装机后（或任何一次运行中）：

```sh
am broadcast -n com.dsh.balancepet/.PetControlReceiver -a com.dsh.balancepet.CONTROL \
  --es token <控制令牌> --es action com.dsh.balancepet.SELFTEST
logcat -d -s DSHPet | grep 自检
```

控制令牌在应用私有目录，启动时也会写进日志（`远程控制令牌：token=…`）。

## ⚠️ 踩过的坑（照抄能省很多时间）

1. **`>/tmp` 是 noexec / 各环境不互通**：跨环境搬文件走 `/sdcard`，别指望 `/tmp` 共享。
2. **不要用 heredoc 写多行脚本**（本环境会吞命令甚至卡死会话）→ 先写文件，再执行。
3. **`pkill -f` 的匹配串要写成 `[p]attern`**，否则会杀掉当前 shell 自己。
4. **APK 会"看起来不存在"**：`build.sh` 在最后一刻才写出 `out/*.apk`。
   装机前**必须**：`md5sum out/… /sdcard/…` 两份一致 **且** `aapt2 dump xmltree <apk> --file AndroidManifest.xml | grep versionCode`
   能看到新版本号 —— 否则可能装到"还没写完/没有版本号"的中间产物（`pm install` 会报 `Update version code 0`）。
5. **改大文件用补丁脚本**：`唯一匹配断言 + dry-run + 任何一处不匹配就整批放弃`（本仓库历史上多次靠这个救回）。
6. `re.sub` 的替换串里写 `\n` 会变成真换行（想写字面量 `\n` 要写 `\\n`）。
7. 装机后 App 数据保留（覆盖安装）；但**换签名**会被拒绝，需要先卸载（用户数据会丢，提前告知）。