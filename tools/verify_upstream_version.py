"""核对：本移植版对应的上游版本是否就是记录的基线。"""
import base64
import hashlib
import json
import subprocess
import sys

REPO = "VKmich16/VK-1"
LOCAL = "/root/work/VK-1-main"
RECORDED_COMMIT = None

# 1) 记录在代码里的基线
with open("/root/work/android/dshpet/java/com/dsh/balancepet/UpstreamInfo.java",
          encoding="utf-8") as f:
    for line in f:
        if "UPSTREAM_COMMIT =" in line:
            RECORDED_COMMIT = line.split('"')[1]
            break
print(f"[1] 代码里记录的基线 commit : {RECORDED_COMMIT}")

def api(path):
    out = subprocess.run(
        ["curl", "-sS", "-H", "Accept: application/vnd.github+json",
         f"https://api.github.com/repos/{REPO}{path}"],
        capture_output=True, text=True, timeout=60).stdout
    return json.loads(out)

# 2) 远端 main 现在的 commit
head = api("/commits/main")
remote_sha = head["sha"]
print(f"[2] 远端 main 当前 commit     : {remote_sha}")
print(f"    日期                     : {head['commit']['committer']['date']}")
print(f"    提交信息                 : {head['commit']['message'].splitlines()[0]}")
print(f"    main 与基线一致          : {'是 ✅' if remote_sha == RECORDED_COMMIT else '否 ❌（上游已更新）'}")

def blob_sha1(data: bytes) -> str:
    """git 的 blob 对象哈希：sha1('blob <len>\\0' + content)。"""
    header = f"blob {len(data)}\0".encode()
    return hashlib.sha1(header + data).hexdigest()

# 3) 逐文件比对：本地文件 vs 远端该 commit 下同一文件
def check_dir(remote_dir, local_dir, names):
    print(f"\n[3] 逐文件内容比对：{remote_dir}")
    listing = api(f"/contents/{remote_dir}?ref={RECORDED_COMMIT}")
    by_name = {item["name"]: item for item in listing}
    all_ok = True
    for name in names:
        item = by_name.get(name)
        if item is None:
            print(f"    ❌ 远端没有 {name}")
            all_ok = False
            continue
        with open(f"{local_dir}/{name}", "rb") as f:
            data = f.read()
        local_sha = blob_sha1(data)
        ok = local_sha == item["sha"] and len(data) == item["size"]
        all_ok &= ok
        print(f"    {'✅' if ok else '❌'} {name:32s} size={len(data):>9d} "
              f"blob={local_sha[:12]}…")
    return all_ok

ok_assets = check_dir(
    "dsh-balance-pet-macos/Resources",
    f"{LOCAL}/dsh-balance-pet-macos/Resources",
    ["sprite.png", "sprite-gpt.png", "sprite-claude.png", "sprite-gemini.png",
     "sprite-deepseek-offline.png", "hit.mp3"])

ok_src = check_dir(
    "dsh-balance-pet-macos/Sources",
    f"{LOCAL}/dsh-balance-pet-macos/Sources",
    ["PetModel.swift", "PetView.swift", "PetController.swift", "PetLayout.swift",
     "PetCharacter.swift", "PetAssets.swift", "BalanceClient.swift", "AppConfig.swift",
     "PollSchedule.swift"])

# 4) 上游仓库自己写的版本字样
print("\n[4] 上游仓库里的版本标识")
readme = subprocess.run(["curl", "-sS",
    f"https://api.github.com/repos/{REPO}/contents/README.md?ref={RECORDED_COMMIT}"],
    capture_output=True, text=True, timeout=60).stdout
readme_text = base64.b64decode(json.loads(readme)["content"]).decode("utf-8")
import re
versions = sorted(set(re.findall(r"v\d+\.\d+\.\d+", readme_text)))
print(f"    README 出现的版本号      : {', '.join(versions)}")
line = [l for l in readme_text.splitlines() if "当前版本" in l]
print(f"    README『当前版本』行     : {line[0].strip() if line else '(未找到)'}")

print("\n[5] 结论")
print(f"    基线 commit {RECORDED_COMMIT[:12]}…")
print(f"    远端 main {'未移动（基线仍是最新）' if remote_sha == RECORDED_COMMIT else '已前移，需要重新对照'}")
print(f"    素材逐文件一致   : {'全部一致 ✅' if ok_assets else '有差异 ❌'}")
print(f"    源码逐文件一致   : {'全部一致 ✅' if ok_src else '有差异 ❌'}")
sys.exit(0 if (ok_assets and ok_src and remote_sha == RECORDED_COMMIT) else 1)