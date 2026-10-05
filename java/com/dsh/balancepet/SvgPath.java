package com.dsh.balancepet;

import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 SVG path 解析器 → android.graphics.Path。
 *
 * 为什么需要：WhaleWidget 的泡泡轮廓是 SVG path（含椭圆弧 A），
 * 直接照抄 path 数据才能在 Canvas 上画出**一模一样**的形状，而不是自己拼圆角矩形。
 *
 * 支持 M/L/H/V/Q/T/C/S/A/Z（大小写绝对/相对），足够覆盖其模板；
 * 弧线用标准的 endpoint → center 参数化实现（含 x-axis-rotation）。
 */
public final class SvgPath {

    private SvgPath() {}

    public static Path parse(String data) {
        Path path = new Path();
        List<Object> tokens = tokenize(data);
        float cx = 0, cy = 0;          // 当前点
        float sx = 0, sy = 0;          // 子路径起点
        float lastCtrlX = 0, lastCtrlY = 0;
        char lastCmd = 0;
        int i = 0;
        char cmd = 0;
        while (i < tokens.size()) {
            Object t = tokens.get(i);
            if (t instanceof Character) {
                cmd = (Character) t;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    path.close();
                    cx = sx;
                    cy = sy;
                    continue;
                }
            } else if (cmd == 0) {
                return path;   // 非法：没有前置指令
            } else if (cmd == 'M') {
                cmd = 'L';     // 后续隐式坐标按 L 处理
            } else if (cmd == 'm') {
                cmd = 'l';
            }

            boolean rel = Character.isLowerCase(cmd);
            try {
                switch (Character.toUpperCase(cmd)) {
                    case 'M': {
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x += cx; y += cy; }
                        cx = x; cy = y; sx = x; sy = y;
                        path.moveTo(cx, cy);
                        break;
                    }
                    case 'L': {
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x += cx; y += cy; }
                        cx = x; cy = y;
                        path.lineTo(cx, cy);
                        break;
                    }
                    case 'H': {
                        float x = num(tokens, i++);
                        if (rel) x += cx;
                        cx = x;
                        path.lineTo(cx, cy);
                        break;
                    }
                    case 'V': {
                        float y = num(tokens, i++);
                        if (rel) y += cy;
                        cy = y;
                        path.lineTo(cx, cy);
                        break;
                    }
                    case 'C': {
                        float x1 = num(tokens, i++), y1 = num(tokens, i++);
                        float x2 = num(tokens, i++), y2 = num(tokens, i++);
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy; }
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastCtrlX = x2; lastCtrlY = y2;
                        cx = x; cy = y;
                        break;
                    }
                    case 'S': {
                        float x2 = num(tokens, i++), y2 = num(tokens, i++);
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                        char u = Character.toUpperCase(lastCmd);
                        float x1 = (u == 'C' || u == 'S') ? 2 * cx - lastCtrlX : cx;
                        float y1 = (u == 'C' || u == 'S') ? 2 * cy - lastCtrlY : cy;
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastCtrlX = x2; lastCtrlY = y2;
                        cx = x; cy = y;
                        break;
                    }
                    case 'Q': {
                        float x1 = num(tokens, i++), y1 = num(tokens, i++);
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x1 += cx; y1 += cy; x += cx; y += cy; }
                        path.quadTo(x1, y1, x, y);
                        lastCtrlX = x1; lastCtrlY = y1;
                        cx = x; cy = y;
                        break;
                    }
                    case 'T': {
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x += cx; y += cy; }
                        char u = Character.toUpperCase(lastCmd);
                        float x1 = (u == 'Q' || u == 'T') ? 2 * cx - lastCtrlX : cx;
                        float y1 = (u == 'Q' || u == 'T') ? 2 * cy - lastCtrlY : cy;
                        path.quadTo(x1, y1, x, y);
                        lastCtrlX = x1; lastCtrlY = y1;
                        cx = x; cy = y;
                        break;
                    }
                    case 'A': {
                        float rx = num(tokens, i++), ry = num(tokens, i++);
                        float rot = num(tokens, i++);
                        boolean largeArc = num(tokens, i++) != 0;
                        boolean sweep = num(tokens, i++) != 0;
                        float x = num(tokens, i++), y = num(tokens, i++);
                        if (rel) { x += cx; y += cy; }
                        arcTo(path, cx, cy, rx, ry, rot, largeArc, sweep, x, y);
                        cx = x; cy = y;
                        break;
                    }
                    default:
                        return path;   // 不认识的指令：保留已画部分，避免崩
                }
                lastCmd = cmd;
            } catch (IndexOutOfBoundsException e) {
                return path;           // 数据不完整：同样保留已画部分
            }
        }
        return path;
    }

    private static float num(List<Object> tokens, int index) {
        Object t = tokens.get(index);
        if (t instanceof Float) return (Float) t;
        return 0f;
    }

    /** 标准 endpoint → center 参数化，再按 4 段以内的三次贝塞尔逼近。 */
    private static void arcTo(Path path, float x1, float y1, float rx, float ry,
                              float rotationDeg, boolean largeArc, boolean sweep,
                              float x2, float y2) {
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0 || (x1 == x2 && y1 == y2)) {
            path.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(rotationDeg % 360);
        double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);

        // 步骤 1：把端点变换到未旋转的椭圆坐标系
        double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
        double x1p = cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;

        // 步骤 2：修正半径（过小则等比放大）
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx = (float) (rx * s);
            ry = (float) (ry * s);
        }

        // 步骤 3：求圆心
        double rx2 = rx * rx, ry2 = ry * ry;
        double num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
        double den = rx2 * y1p * y1p + ry2 * x1p * x1p;
        double factor = den == 0 ? 0 : Math.sqrt(Math.max(0, num / den));
        if (largeArc == sweep) factor = -factor;
        double cxp = factor * (rx * y1p / ry);
        double cyp = factor * -(ry * x1p / rx);
        double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
        double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;

        // 步骤 4：起止角
        double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        if (sweep && delta < 0) delta += 2 * Math.PI;

        // 步骤 5：按 ≤90° 分段转成三次贝塞尔
        int segments = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2));
        double step = delta / segments;
        double t = (4.0 / 3.0) * Math.tan(step / 4.0);
        double startAngle = theta1;
        double px = x1, py = y1;
        for (int i = 0; i < segments; i++) {
            double endAngle = startAngle + step;
            double cosA = Math.cos(startAngle), sinA = Math.sin(startAngle);
            double cosB = Math.cos(endAngle), sinB = Math.sin(endAngle);

            double ex = cx + rx * cosPhi * cosB - ry * sinPhi * sinB;
            double ey = cy + rx * sinPhi * cosB + ry * cosPhi * sinB;

            double c1x = px + t * (-rx * cosPhi * sinA - ry * sinPhi * cosA);
            double c1y = py + t * (-rx * sinPhi * sinA + ry * cosPhi * cosA);
            double c2x = ex - t * (-rx * cosPhi * sinB - ry * sinPhi * cosB);
            double c2y = ey - t * (-rx * sinPhi * sinB + ry * cosPhi * cosB);

            path.cubicTo((float) c1x, (float) c1y, (float) c2x, (float) c2y, (float) ex, (float) ey);
            px = ex;
            py = ey;
            startAngle = endAngle;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        if (len == 0) return 0;
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return (ux * vy - uy * vx) < 0 ? -a : a;
    }

    /** 把 path 数据切成"指令字符 + 数字"的 token 列表。 */
    private static List<Object> tokenize(String data) {
        List<Object> tokens = new ArrayList<>();
        int i = 0;
        while (i < data.length()) {
            char c = data.charAt(i);
            if (Character.isLetter(c)) {
                tokens.add(c);
                i++;
            } else if (c == ',' || Character.isWhitespace(c)) {
                i++;
            } else {
                int start = i;
                if (c == '-' || c == '+') i++;
                boolean dotSeen = false;
                while (i < data.length()) {
                    char d = data.charAt(i);
                    if (Character.isDigit(d)) {
                        i++;
                    } else if (d == '.' && !dotSeen) {
                        dotSeen = true;
                        i++;
                    } else if ((d == 'e' || d == 'E') && i + 1 < data.length()
                            && (Character.isDigit(data.charAt(i + 1))
                            || data.charAt(i + 1) == '-' || data.charAt(i + 1) == '+')) {
                        i += 2;
                    } else {
                        break;
                    }
                }
                if (i == start) {   // 无法识别的字符：跳过，避免死循环
                    i++;
                    continue;
                }
                try {
                    tokens.add(Float.parseFloat(data.substring(start, i)));
                } catch (NumberFormatException e) {
                    // 忽略坏数字
                }
            }
        }
        return tokens;
    }
}