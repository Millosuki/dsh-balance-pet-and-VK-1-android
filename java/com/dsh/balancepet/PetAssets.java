package com.dsh.balancepet;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 立绘资源：解码 + 不透明区域分解。
 *
 * 原版 macOS 用「逐像素 alpha > 8/255」做命中判定，再靠
 * window.ignoresMouseEvents 实现整窗穿透。Android 无法按像素设置窗口输入区域
 * （实测：可触摸悬浮窗的透明像素同样吞掉点击；FLAG_NOT_TOUCHABLE 的跨窗穿透
 * 又依赖厂商 opacity 阈值，alpha 0.75 就被拦），因此这里改为：
 *   把不透明区域分解成若干互不重叠的矩形，每个矩形一个可触摸窗口。
 * 透明处没有任何窗口覆盖，点击天然落到下层应用，效果等价于逐像素穿透。
 *
 * 关键约束（实测）：ColorOS 的 OplusWindowContainerControlService 有窗口数量守卫，
 * 一个包窗口过多时会直接「Too many windows」强杀应用。因此矩形数量被硬性压到
 * MAX_RECTS 以内（16 个窗口已验证可正常工作）。
 */
public final class PetAssets {

    /** 纵向分带数：越多越贴合轮廓，但窗口数越多。 */
    private static final int BANDS = 11;
    /** 列采样步长（图像像素）。 */
    private static final int COLUMN_STEP = 8;
    /** 一列在带内至少有这么多不透明像素才算「有内容」（滤掉单像素噪点）。 */
    private static final int COLUMN_MIN_PIXELS = 2;
    /** 太窄的列段直接忽略。 */
    private static final int MIN_RUN_WIDTH = 16;
    /** 带内最多保留几段（多的按宽度丢弃），避免尾部小装饰把窗口数抬高。 */
    private static final int MAX_RUNS_PER_BAND = 2;
    /** 最终窗口数上限（必须小于厂商守卫阈值）。 */
    private static final int MAX_RECTS = 12;
    /** 纵向合并时允许的左右边界差异（像素）；差异大说明轮廓在变化，不能合并。 */
    private static final int SPAN_TOLERANCE = 10;
    private static final int ALPHA_THRESHOLD = 8;

    public static final class Artwork {
        public final String assetName;
        public final Bitmap bitmap;
        public final int width;
        public final int height;
        /** 归一化矩形（0…1），每 4 个 float 一个矩形：left, top, right, bottom。 */
        public final float[] rects;
        public final boolean offlineArt;   // 抱盆图没有余额文字

        Artwork(String assetName, Bitmap bitmap, float[] rects, boolean offlineArt) {
            this.assetName = assetName;
            this.bitmap = bitmap;
            this.width = bitmap.getWidth();
            this.height = bitmap.getHeight();
            this.rects = rects;
            this.offlineArt = offlineArt;
        }

        /** 归一化矩形 → 窗口坐标（y 向下）。对齐到整数像素，避免分带窗口拼合处出现 1px 接缝。 */
        public List<RectF> screenRects(float windowW, float windowH) {
            RectF sprite = PetLayout.spriteRect(windowW, windowH);
            List<RectF> out = new ArrayList<>(rects.length / 4);
            for (int i = 0; i + 3 < rects.length; i += 4) {
                out.add(new RectF(
                        Math.round(sprite.left + rects[i] * sprite.width()),
                        Math.round(sprite.top + rects[i + 1] * sprite.height()),
                        Math.round(sprite.left + rects[i + 2] * sprite.width()),
                        Math.round(sprite.top + rects[i + 3] * sprite.height())));
            }
            return out;
        }
    }

    private static final Map<String, Artwork> CACHE = new HashMap<>();

    private PetAssets() {}

    /**
         * 从**文件**装载立绘（自定义角色，v1.13.0）。缓存键带上修改时间，换图不会命中旧位图。
         */
        public static Artwork get(java.io.File file, boolean offlineArt) {
            if (file == null || !file.isFile()) return null;
            String key = "file:" + file.getAbsolutePath() + "@" + file.lastModified();
            Artwork cached = CACHE.get(key);
            if (cached != null) return cached;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                Log.write("自定义角色图片解码失败：" + file);
                return null;
            }
            Artwork art = fromBitmap(key, bitmap, offlineArt);
            if (art != null) CACHE.put(key, art);
            return art;
        }
        /** 位图 → Artwork（转换像素格式、算穿透矩形、写日志）。 */
        private static Artwork fromBitmap(String key, Bitmap bitmap, boolean offlineArt) {
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (converted != null) {
                    bitmap.recycle();
                    bitmap = converted;
                }
            }
            float[] rects = decompose(bitmap);
            Log.write(String.format(java.util.Locale.US,
                    "已装载 %s %dx%d，穿透矩形 %d 个", key,
                    bitmap.getWidth(), bitmap.getHeight(), rects.length / 4));
            return new Artwork(key, bitmap, rects, offlineArt);
        }
        public static Artwork get(String assetName, boolean offlineArt) {
        Artwork cached = CACHE.get(assetName);
        if (cached != null) return cached;
        Artwork art = load(assetName, offlineArt);
        if (art != null) CACHE.put(assetName, art);
        return art;
    }

    /** 释放非当前使用的立绘，避免多张 6 MB 位图同时驻留。 */
    public static void releaseOthers(String keepAsset) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        if (keepAsset != null && !keepAsset.isEmpty()) keep.add(keepAsset);
        releaseExcept(keep);
    }
    /** 当前缓存里的素材名（自检用：只释放自检期间新装载的那些）。 */
    public static java.util.Set<String> cachedKeys() {
        return new java.util.HashSet<>(CACHE.keySet());
    }

    /** 释放除 keep 之外的所有立绘。 */
    public static void releaseExcept(java.util.Set<String> keep) {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Artwork> e : CACHE.entrySet()) {
            if (keep == null || !keep.contains(e.getKey())) drop.add(e.getKey());
        }
        for (String name : drop) {
            Artwork art = CACHE.remove(name);
            if (art != null && art.bitmap != null && !art.bitmap.isRecycled()) art.bitmap.recycle();
        }
    }

    private static Artwork load(String assetName, boolean offlineArt) {
        Bitmap bitmap = decode(assetName, 1);
        if (bitmap == null) {
            Log.write(assetName + " 缺失或无法解码；" + listAssets());
            return null;
        }
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (converted != null) {
                bitmap.recycle();
                bitmap = converted;
            }
        }
        float[] rects = decompose(bitmap);
        Log.write(String.format(java.util.Locale.US,
                "已装载 %s %dx%d，穿透矩形 %d 个", assetName,
                bitmap.getWidth(), bitmap.getHeight(), rects.length / 4));
        return new Artwork(assetName, bitmap, rects, offlineArt);
    }

    private static Bitmap decode(String assetName, int sampleSize) {
        AssetManager assets = PetPaths.context().getAssets();
        InputStream in = null;
        try {
            in = assets.open(assetName);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inSampleSize = sampleSize;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) {
                Log.write("解码 " + assetName + " 返回 null（sampleSize=" + sampleSize + "）");
                return sampleSize == 1 ? decode(assetName, 2) : null;
            }
            return b;
        } catch (IOException e) {
            Log.write("打开素材 " + assetName + " 失败: " + e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.write("素材 " + assetName + " 解码内存不足，降采样重试");
            return sampleSize == 1 ? decode(assetName, 2) : null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** 诊断：APK 里 asset 根目录到底有哪些条目。 */
    public static String listAssets() {
        try {
            String[] names = PetPaths.context().getAssets().list("");
            StringBuilder sb = new StringBuilder("assets 根目录 " + names.length + " 项：");
            for (int i = 0; i < Math.min(names.length, 12); i++) sb.append(" ").append(names[i]);
            return sb.toString();
        } catch (IOException e) {
            return "assets 无法列出: " + e;
        }
    }

    /**
     * 横向分带 + 每带按列段切分，再做纵向合并与「最小面积增量」兜底合并。
     * 输出矩形互不重叠、覆盖所有主要不透明区域。
     */
    private static float[] decompose(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        int rowChunk = Math.max(1, 512 * 1024 / Math.max(1, w));
        for (int y0 = 0; y0 < h; y0 += rowChunk) {
            int rows = Math.min(rowChunk, h - y0);
            bitmap.getPixels(pixels, y0 * w, w, 0, y0, w, rows);
        }

        // 不透明包围盒
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                if ((pixels[base + x] >>> 24) > ALPHA_THRESHOLD) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < top || right < left) {
            // 全透明图：退回整幅图，至少保证可拖动
            return new float[]{0f, 0f, 1f, 1f};
        }

        int bandHeight = Math.max(1, (bottom - top + 1) / BANDS);
        List<int[]> rects = new ArrayList<>();

        for (int band = 0; band < BANDS; band++) {
            int y0 = top + band * bandHeight;
            int y1 = Math.min(bottom + 1, band == BANDS - 1 ? bottom + 1 : y0 + bandHeight);
            if (y1 <= y0) continue;

            // 每列的覆盖统计：带内只要出现不透明像素就算该列有内容，
            // 这样「带跨度」就是该高度上轮廓的真实左右边界（楼梯式贴合）。
            int columns = (right - left) / COLUMN_STEP + 1;
            boolean[] covered = new boolean[columns];
            for (int c = 0; c < columns; c++) {
                int x0 = left + c * COLUMN_STEP;
                int x1 = Math.min(right + 1, x0 + COLUMN_STEP);
                int count = 0;
                for (int y = y0; y < y1 && count < COLUMN_MIN_PIXELS; y++) {
                    int base = y * w;
                    for (int x = x0; x < x1; x++) {
                        if ((pixels[base + x] >>> 24) > ALPHA_THRESHOLD) {
                            count++;
                            break;
                        }
                    }
                }
                covered[c] = count >= COLUMN_MIN_PIXELS;
            }

            // 连续列段：允许每带留 1～2 段（例如身体 + 分离的尾巴）
            List<int[]> runs = new ArrayList<>();
            int start = -1;
            for (int c = 0; c <= columns; c++) {
                boolean on = c < columns && covered[c];
                if (on && start < 0) start = c;
                if (!on && start >= 0) {
                    runs.add(new int[]{left + start * COLUMN_STEP,
                            Math.min(right + 1, left + c * COLUMN_STEP)});
                    start = -1;
                }
            }
            // 丢弃太窄的段
            List<int[]> kept = new ArrayList<>();
            for (int[] run : runs) {
                if (run[1] - run[0] >= MIN_RUN_WIDTH) kept.add(run);
            }
            // 只保留最宽的两段
            while (kept.size() > MAX_RUNS_PER_BAND) {
                int narrowest = 0;
                for (int i = 1; i < kept.size(); i++) {
                    if (kept.get(i)[1] - kept.get(i)[0] < kept.get(narrowest)[1] - kept.get(narrowest)[0]) {
                        narrowest = i;
                    }
                }
                kept.remove(narrowest);
            }
            // 过窄就整带用全长（避免出现空洞）
            if (kept.isEmpty()) kept.add(new int[]{left, right + 1});

            for (int[] run : kept) rects.add(new int[]{run[0], y0, run[1], y1});
        }

        // 纵向合并：只有上下相邻且左右边界几乎一致的矩形才合并
        // （楼梯式轮廓的每一级边界都在变化，因此不会被糊成一个大包围盒）
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < rects.size() && !merged; i++) {
                for (int j = i + 1; j < rects.size(); j++) {
                    int[] a = rects.get(i), b = rects.get(j);
                    boolean touching = a[3] >= b[1] && b[3] >= a[1];
                    boolean sameLeft = Math.abs(a[0] - b[0]) <= SPAN_TOLERANCE;
                    boolean sameRight = Math.abs(a[2] - b[2]) <= SPAN_TOLERANCE;
                    if (touching && sameLeft && sameRight) {
                        rects.set(i, new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                                Math.max(a[2], b[2]), Math.max(a[3], b[3])});
                        rects.remove(j);
                        merged = true;
                        break;
                    }
                }
            }
        }

        // 兜底：仍超限就挑「并起来最省面积」的一对合并
        while (rects.size() > MAX_RECTS) {
            int bestI = -1, bestJ = -1;
            long bestCost = Long.MAX_VALUE;
            for (int i = 0; i < rects.size(); i++) {
                for (int j = i + 1; j < rects.size(); j++) {
                    long union = area(union(rects.get(i), rects.get(j)));
                    long cost = union - area(rects.get(i)) - area(rects.get(j));
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            if (bestI < 0) break;
            rects.set(bestI, union(rects.get(bestI), rects.get(bestJ)));
            rects.remove(bestJ);
        }

        float[] out = new float[rects.size() * 4];
        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            out[i * 4] = r[0] / (float) w;
            out[i * 4 + 1] = r[1] / (float) h;
            out[i * 4 + 2] = r[2] / (float) w;
            out[i * 4 + 3] = r[3] / (float) h;
        }
        return out;
    }

    private static int[] union(int[] a, int[] b) {
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    private static long area(int[] r) {
        return (long) Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]);
    }
}