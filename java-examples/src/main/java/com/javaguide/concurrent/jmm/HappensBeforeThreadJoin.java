package com.javaguide.concurrent.jmm;

/**
 * happens-before 线程终止规则演示
 *
 * <p>规则：线程中的所有操作 happens-before join() 返回
 *
 * <p>底层：JVM 在子线程退出时插入刷出屏障，
 * 在 join() 返回时插入加载屏障，两端配合保证可见性。
 *
 * @author JavaGuide
 */
public class HappensBeforeThreadJoin {

    static int result = 0;

    public static void main(String[] args) throws InterruptedException {

        Thread worker = new Thread(() -> {
            // 子线程中做计算
            int sum = 0;
            for (int i = 1; i <= 100; i++) {
                sum += i;
            }
            result = sum;  // 写入共享变量
            System.out.println("[子线程] 计算完成，result = " + result);
        }, "worker-thread");

        worker.start();
        worker.join();  // join() 返回后，子线程的所有写操作对主线程可见

        // 主线程：保证能看到 result = 5050
        System.out.println("[主线程] result = " + result);
        if (result == 5050) {
            System.out.println("[主线程] ✅ 线程终止规则成立");
        } else {
            System.out.println("[主线程] ❌ 线程终止规则失败");
        }
    }
}
