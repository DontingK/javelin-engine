package com.javelin.editor.swing.dock;

/**
 * Dock 二分分割节点：左右两子 + 分割比重（Editor_Swing_Design §5.1）。
 *
 * <p>恒两子——任意布局由二分递归表达（Unity/IntelliJ 同构），渲染直接映射
 * {@code JSplitPane}（非连续布局：拖完才 relayout，规避逐像素交换链重建）。
 * weight = <b>右（下）子占比</b>（0~1），分割条拖动回写。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class SplitNode extends DockNode {

    /** 分割方向 */
    public enum Direction {
        /** 水平分割：左右两子 */
        HORIZONTAL,
        /** 垂直分割：上下两子（left=上、right=下） */
        VERTICAL
    }

    private Direction direction;
    private DockNode left;
    private DockNode right;
    /** 右（下）子占比 0~1 */
    private double weight;

    public SplitNode(Direction direction, DockNode left, DockNode right, double weight) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("SplitNode 两子不可为空");
        }
        this.direction = direction;
        this.left = left;
        this.right = right;
        this.weight = clamp(weight);
    }

    /** 水平分割（rightWeight=右占比） */
    public static SplitNode horizontal(DockNode left, DockNode right, double rightWeight) {
        return new SplitNode(Direction.HORIZONTAL, left, right, rightWeight);
    }

    /** 垂直分割（bottomWeight=下占比；left=上、right=下） */
    public static SplitNode vertical(DockNode top, DockNode bottom, double bottomWeight) {
        return new SplitNode(Direction.VERTICAL, top, bottom, bottomWeight);
    }

    public Direction getDirection() {
        return direction;
    }

    public DockNode getLeft() {
        return left;
    }

    public void setLeft(DockNode left) {
        if (left == null) {
            throw new IllegalArgumentException("SplitNode 子节点不可为空");
        }
        this.left = left;
    }

    public DockNode getRight() {
        return right;
    }

    public void setRight(DockNode right) {
        if (right == null) {
            throw new IllegalArgumentException("SplitNode 子节点不可为空");
        }
        this.right = right;
    }

    /** 右（下）子占比 0~1 */
    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = clamp(weight);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
