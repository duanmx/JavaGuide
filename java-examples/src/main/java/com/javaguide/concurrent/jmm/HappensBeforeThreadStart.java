package com.javaguide.concurrent.jmm;

/**
 * happens-before 线程启动规则演示
 *
 * <p>规则：Thread.start() happens-before 子线程中的任何操作
 *
 * <p>底层：JVM 在 start() 调用时插入刷出屏障，
 * 在子线程入口插入加载屏障，两端配合保证可见性。
 *
 * @author JavaGuide
 */
public class HappensBeforeThreadStart {

    static int x = 0;
    static String message = "";

    public static void main(String[] args) throws InterruptedException {

        // 主线程写数据
        x = 42;
        message = "hello from main";

        // 子线程：保证能看到 x=42 和 message
        Thread child = new Thread(() -> {
            System.out.println("[子线程] x = " + x);           // 保证 42
            System.out.println("[子线程] message = " + message); // 保证 "hello from main"

            if (x == 42 && "hello from main".equals(message)) {
                System.out.println("[子线程] ✅ 线程启动规则成立");
            } else {
                System.out.println("[子线程] ❌ 线程启动规则失败");
            }
        }, "child-thread");

        child.start();  // start() 之前的所有写操作对子线程可见
        child.join();
    }
}
