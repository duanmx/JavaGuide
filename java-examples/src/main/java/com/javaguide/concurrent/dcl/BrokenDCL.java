package com.javaguide.concurrent.dcl;

/**
 * DCL 错误实现 - 去掉 volatile 的版本
 *
 * <p>警告：这个实现有 bug！仅用于演示问题。
 *
 * <p>没有 volatile，new BrokenDCL() 的步骤 B（初始化）和 C（引用赋值）可能被重排序，
 * 导致其他线程在第一次检查时看到 instance != null，但对象还没初始化完。
 *
 * <p>注意：这个问题在高并发下不一定每次都能复现，
 * 因为重排序取决于 CPU 和 JIT 编译器的优化决策。
 *
 * @author JavaGuide
 */
public class BrokenDCL {

    // 没有 volatile！
    private static BrokenDCL instance;

    private int value;

    private BrokenDCL() {
        // 模拟耗时初始化
        this.value = 42;
    }

    public static BrokenDCL getInstance() {
        if (instance == null) {
            synchronized (BrokenDCL.class) {
                if (instance == null) {
                    // 没有 volatile，没有 StoreStore 屏障
                    // A（分配）→ C（引用赋值）→ B（初始化）可能被重排序
                    // 线程 2 可能在 B 之前看到 instance != null
                    instance = new BrokenDCL();
                }
            }
        }
        return instance;
        // 线程 2 可能拿到 value = 0 而不是 42 的对象
    }

    public int getValue() {
        return value;
    }

    public static void main(String[] args) {
        BrokenDCL obj = BrokenDCL.getInstance();
        // 在高并发下，obj.value 可能是 0（半初始化）而不是 42（完全初始化）
        System.out.println("value = " + obj.getValue());
        System.out.println("注意：单线程下通常不会复现问题，需要高并发才可能触发");
    }
}
