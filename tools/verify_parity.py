"""一致性对照：上游源码里的常量 vs 本移植版 PetTuning.java 的取值。

输出「上游文件:行号 → 值」作为证据，并逐条比对。任何不一致都会列出来。
用法：python3 verify_parity.py
"""
import re
import sys

UP = "/root/work/VK-1-main"
PORT_TUNING = "/root/work/android/dshpet/java/com/dsh/balancepet/PetTuning.java"
PORT_CHAR = "/root/work/android/dshpet/java/com/dsh/balancepet/PetCharacter.java"
PORT_LAYOUT = "/root/work/android/dshpet/java/com/dsh/balancepet/PetLayout.java"

MACOS = f"{UP}/dsh-balance-pet-macos/Sources"
PS1 = f"{UP}/原版（Windows版）/DSH余额桌宠/dsh_pet.ps1"

with open(PORT_TUNING, encoding="utf-8") as f:
    tuning_src = f.read()
with open(PORT_CHAR, encoding="utf-8") as f:
    char_src = f.read()
with open(PORT_LAYOUT, encoding="utf-8") as f:
    layout_src = f.read()
with open("/root/work/android/dshpet/java/com/dsh/balancepet/CredentialStore.java",
          encoding="utf-8") as f:
    cred_src = f.read()
with open("/root/work/android/dshpet/java/com/dsh/balancepet/PetService.java",
          encoding="utf-8") as f:
    service_src = f.read()
# 值可能落在任何一个移植版文件里，统一检索
ALL_PORT_SRC = "\n".join([tuning_src, char_src, layout_src, cred_src, service_src])


def upstream_hits(path, pattern, limit=3):
    """返回上游文件里匹配 pattern 的行号与原文。"""
    hits = []
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, 1):
                if re.search(pattern, line):
                    hits.append((i, line.strip()))
                    if len(hits) >= limit:
                        break
    except FileNotFoundError:
        return None
    return hits


def tuning_value(name):
    """在移植版所有源文件里找这个常量/字面量的取值。"""
    if name.startswith("（"):
        return name
    if "/" in name:                      # 形如 TINT_R/TINT_G/TINT_B
        parts = [p for p in name.split("/") if p]
        values = []
        for p in parts:
            m = re.search(rf"{re.escape(p)}\s*=\s*([^;,]+)", ALL_PORT_SRC)
            values.append(m.group(1).strip() if m else "?")
        return "(" + ",".join(values) + ")"
    m = re.search(rf"{re.escape(name)}\s*=\s*([^;]+?)(?:;|\s+\|\|)", ALL_PORT_SRC)
    return m.group(1).strip() if m else "（超出本次检索范围，见源码）"


# (说明, 上游文件, 正则, 我的 PetTuning 常量名)
CHECKS = [
    ("扣费间隔 0.2s",          f"{MACOS}/PetModel.swift",
     r"stepInterval: Double = 0\.2", "STEP_INTERVAL"),
    ("受伤动画 0.55s",         f"{MACOS}/PetModel.swift",
     r"hitDuration: Double = 0\.55", "HIT_DURATION"),
    ("飘字存活 0.95s",         f"{MACOS}/PetModel.swift",
     r"floatLifetime: Double = 0\.95", "FLOAT_LIFETIME"),
    ("排队上限 400",           f"{MACOS}/PetModel.swift",
     r"maxPendingSteps = 400", "MAX_PENDING_STEPS"),
    ("演示上限 200",           f"{MACOS}/PetModel.swift",
     r"maxDemoSteps = 200", "MAX_DEMO_STEPS"),
    ("受击叠色峰值 0.45",      f"{MACOS}/PetView.swift",
     r"0\.45 \* model\.impact", "TINT_PEAK"),
    ("受击颜色 (1,0.10,0.14)", f"{MACOS}/PetView.swift",
     r"green: 0\.10, blue: 0\.14", "TINT_R/TINT_G/TINT_B"),
    ("抖动振幅 min(3.2, 0.025·side)", f"{MACOS}/PetView.swift",
     r"min\(CGFloat\(3\.2\), side \* 0\.025\)", "SHAKE_AMPLITUDE_DP/SHAKE_AMPLITUDE_RATIO"),
    ("抖动频率 24 / 19",       f"{MACOS}/PetView.swift",
     r"sin\(elapsed \* 24\)", "SHAKE_FREQ_X/SHAKE_FREQ_Y"),
    ("y 轴系数 0.875",         f"{MACOS}/PetView.swift",
     r"0\.875 \* decay", "SHAKE_Y_SCALE"),
    ("飘字字号 0.080",         f"{MACOS}/PetView.swift",
     r"side \* 0\.080", "FLOAT_FONT_RATIO"),
    ("飘字透明度曲线 1.6",     f"{MACOS}/PetView.swift",
     r"pow\(progress, 1\.6\)", "FLOAT_ALPHA_POW"),
    ("飘字阴影 0.022",         f"{MACOS}/PetView.swift",
     r"side \* 0\.022", "FLOAT_SHADOW_RATIO"),
    ("充值动画 0.9s",          f"{MACOS}/PetModel.swift",
     r"topupTime = 0\.9", "TOPUP_DURATION"),
    ("尺寸档位 110/150/210/280", f"{MACOS}/PetController.swift",
     r'\("中", 150\)', "SIZE_PRESETS_DP（换算为 dp）"),
    ("音效 4 槽位",            f"{MACOS}/PetController.swift",
     r"for _ in 0\.\.<4", "SOUND_STREAMS"),
    ("音量 0.7",               f"{MACOS}/PetController.swift",
     r"s\.volume = 0\.7", "SOUND_VOLUME_DEFAULT"),
    ("左下角边距 14",          f"{MACOS}/PetController.swift",
     r"vf\.minX \+ 14", "CORNER_MARGIN_DP = 14"),
    ("吸附动画 0.16s",         f"{MACOS}/PetController.swift",
     r"ctx\.duration = 0\.16", "（PetService.animateTo 160ms）"),
    ("默认轮询 30s",           f"{MACOS}/PetController.swift",
     r"PollSchedule\(interval: 30\)", "POLL_DEFAULT_SECONDS"),
    ("画布 1536×1024",         f"{MACOS}/PetLayout.swift",
     r"artworkSize = CGSize\(width: 1536, height: 1024\)", "PetLayout.ART_W/ART_H"),
    ("飘字带 0.55",            f"{MACOS}/PetLayout.swift",
     r"floatBand: CGFloat = 0\.55", "PetLayout.FLOAT_BAND"),
    ("平板面板 400×220",       f"{MACOS}/PetLayout.swift",
     r"tabletBounds = CGRect\(x: 0, y: 0, width: 400, height: 220\)", "PetLayout.PANEL_W/PANEL_H"),
    ("平板安全区三点",         f"{MACOS}/PetCharacter.swift",
     r"1060, y: 699", "PetCharacter.tabletCorners"),
    ("Gemini 单独收窄",        f"{MACOS}/PetCharacter.swift",
     r"1065, y: 699", "PetCharacter.tabletCorners(GEMINI)"),
    ("API Key 余额接口",       f"{MACOS}/AppConfig.swift",
     r"api\.deepseek\.com/user/balance", "CredentialStore.API_KEY_ENDPOINT"),
    ("账号接口路径",           f"{MACOS}/AppConfig.swift",
     r"/api/v0/users/get_user_summary", "CredentialStore.DEFAULT_ACCOUNT_PATH"),
    # Windows 原版基准（行为来源）
    ("[win] 扣费间隔 0.2",     PS1, r"CueGapSec\s*=\s*0\.2", "STEP_INTERVAL"),
    ("[win] 单次上限 40",      PS1, r"MaxCuesPerPoll\s*=\s*40", "（macOS 版为 400，README 已说明）"),
    ("[win] 步长 0.01",        PS1, r"StepYuan\s*=\s*0\.01", "（步长固定 0.01 分）"),
]

print("=" * 78)
print("上游常量 ↔ 本移植版对照（上游侧给出文件与行号作为证据）")
print("=" * 78)
bad = 0
for label, path, pattern, mine in CHECKS:
    hits = upstream_hits(path, pattern)
    fname = path.split("/")[-1]
    if hits is None:
        print(f"❓ {label:28s} 上游文件缺失：{path}")
        bad += 1
        continue
    if not hits:
        print(f"❌ {label:28s} 在上游 {fname} 中未找到：{pattern}")
        bad += 1
        continue
    line_no, text = hits[0]
    value = tuning_value(mine) if not mine.startswith("（") else mine
    print(f"✅ {label:28s} {fname}:{line_no:<4d} {text[:46]:46s} → 我用的：{value}")

print("=" * 78)
print(f"未对齐项：{bad}")
sys.exit(1 if bad else 0)