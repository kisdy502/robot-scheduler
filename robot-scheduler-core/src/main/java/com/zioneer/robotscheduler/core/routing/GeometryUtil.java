package com.zioneer.robotscheduler.core.routing;

/**
 * 平面几何工具（SLAM 坐标系，米制）：线段-多边形相交与点包含判定，用于路径段的区域约束计算。
 */
public final class GeometryUtil {

    private GeometryUtil() {
    }

    /**
     * 线段是否与多边形相交（相交或任一端点在多边形内部）。
     */
    public static boolean segmentIntersectsPolygon(double x1, double y1, double x2, double y2,
                                                   double[][] polygon) {
        if (polygon == null || polygon.length < 3) {
            return false;
        }
        // 端点在多边形内 → 相交
        if (pointInPolygon(x1, y1, polygon) || pointInPolygon(x2, y2, polygon)) {
            return true;
        }
        // 与任意边相交
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            if (segmentsIntersect(x1, y1, x2, y2, a[0], a[1], b[0], b[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 射线法判定点是否在多边形内（边界按内处理）。
     */
    public static boolean pointInPolygon(double x, double y, double[][] polygon) {
        if (polygon == null || polygon.length < 3) {
            return false;
        }
        boolean inside = false;
        int count = polygon.length;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double xi = polygon[i][0];
            double yi = polygon[i][1];
            double xj = polygon[j][0];
            double yj = polygon[j][1];
            boolean onBoundary = (x == xi && y == yi) || (x == xj && y == yj)
                    || pointOnSegment(x, y, xi, yi, xj, yj);
            if (onBoundary) {
                return true;
            }
            if ((yi > y) != (yj > y)) {
                double intersectX = (xj - xi) * (y - yi) / (yj - yi) + xi;
                if (x < intersectX) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * 两线段是否相交（严格相交，含端点接触）。
     */
    public static boolean segmentsIntersect(double x1, double y1, double x2, double y2,
                                            double x3, double y3, double x4, double y4) {
        double d1 = cross(x3, y3, x4, y4, x1, y1);
        double d2 = cross(x3, y3, x4, y4, x2, y2);
        double d3 = cross(x1, y1, x2, y2, x3, y3);
        double d4 = cross(x1, y1, x2, y2, x4, y4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && pointOnSegment(x1, y1, x3, y3, x4, y4))
                || (d2 == 0 && pointOnSegment(x2, y2, x3, y3, x4, y4))
                || (d3 == 0 && pointOnSegment(x3, y3, x1, y1, x2, y2))
                || (d4 == 0 && pointOnSegment(x4, y4, x1, y1, x2, y2));
    }

    /**
     * 点到线段的最短距离。
     */
    public static double pointToSegmentDistance(double px, double py,
                                                double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private static boolean pointOnSegment(double px, double py, double x1, double y1, double x2, double y2) {
        return px >= Math.min(x1, x2) - 1e-9 && px <= Math.max(x1, x2) + 1e-9
                && py >= Math.min(y1, y2) - 1e-9 && py <= Math.max(y1, y2) + 1e-9;
    }

    private static double cross(double x1, double y1, double x2, double y2, double x3, double y3) {
        return (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1);
    }
}
