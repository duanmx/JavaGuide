package com.javaguide.concurrent.volatile_demo;

/**
 * volatile 可见性演示
 *
 * <p>演示 volatile 如何通过内存屏障保证可见性：
 * <ul>
 *   <li>volatile 写：StoreStore 屏障（刷出前面的写）+ StoreLoad 屏障（写对后续读可见）</li>
 *   <li>volatile 读：LoadLoad 屏障（后面读不重排）+ LoadStore 屏障（后面写不重排）</li>
 * </ul>
 *
 * @author JavaGuide
 */
public class VolatileVisibility {

    /** 没有 volatile：线程 B 可能永远看不到修改 */
    static boolean running1 = true;

    /** 有 volatile：线程 B 一定能看到修改 */
    static volatile boolean running2 = true;

    public static void main(String[] args) throws InterruptedException {

        // ===== 测试 1：volatile 保证可见性 =====
        System.out.println("===== 测试 volatile 可见性 =====");

        Thread worker = new Thread(() -> {
            long count = 0;
            while (running2) {
                count++;
            }
            System.out.println("[volatile] worker 线程退出，循环次数: " + count);
        }, "volatile-worker");

        worker.start();
        Thread.sleep(100);  // 让 worker 跑一会儿
        running2 = false;   // volatile 写：刷出到主存，worker 能立刻看到
        worker.join();

        System.out.println("[volatile] 主线程设置 running2 = false 后，worker 正常退出 ✅");

        // ===== 测试 2：不用 volatile 可能看不到修改 =====
        System.out.println("\n===== 测试 没有 volatile 的情况 =====");
        System.out.println("注意：JIT 优化后 worker 可能永远不会退出（需要加 -Xint 禁用 JIT 才能正常退出）");
        System.out.println("这里用 2 秒超时保护，不会卡死");

        Thread workerNoVolatile = new Thread(() -> {
            long count = 0;
            while (running1) {
                count++;
            }
            System.out.println("[no-volatile] worker 线程退出，循环次数: " + count);
        }, "no-volatile-worker");

        workerNoVolatile.start();
        Thread.sleep(100);
        running1 = false;  // 没有 volatile，worker 可能看不到

        // 2 秒超时保护
        workerNoVolatile.join(2000);
        if (workerNoVolatile.isAlive()) {
            System.out.println("[no-volatile] worker 还在运行（看不到 running1=false）💥");
            // 强制停止
            workerNoVolatile.stop();
        } else {
            System.out.println("[no-volatile] worker 已退出（本次没触发 JIT 优化）");
        }
    }
}
