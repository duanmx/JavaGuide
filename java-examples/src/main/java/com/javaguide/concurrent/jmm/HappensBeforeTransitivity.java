package com.javaguide.concurrent.jmm;

/**
 * happens-before 传递性演示
 *
 * <p>线程 A 写 x=10，通过 volatile flag 发布给线程 B；
 * 线程 B 读到 flag 后，通过 volatile y 发布给主线程；
 * 主线程读到 y=20 后，保证能看到 x=10。
 *
 * <p>happens-before 链条：
 * <pre>
 * ① x=10 → ② flag=true（程序顺序）
 * ② flag=true → ③ f=flag（volatile 写→读）
 * ③ f=flag → ④ y=20（程序顺序）
 * ④ y=20 → ⑤ y==20（volatile 写→读）
 * ⑤ y==20 → ⑥ v=x（程序顺序）
 * 传递性：① → ⑥
 * </pre>
 *
 * @author JavaGuide
 */
public class HappensBeforeTransitivity {

    static int x = 0;
    static volatile boolean flag = false;
    static volatile int y = 0;

    public static void main(String[] args) throws InterruptedException {

        // 线程 A：写 x，通过 flag 发布
        Thread threadA = new Thread(() -> {
            x = 10;             // ① 普通写
            flag = true;        // ② volatile 写（发布信号）
            System.out.println("[线程 A] x=10, flag=true");
        }, "Thread-A");

        // 线程 B：读 flag，通过 y 再次发布
        Thread threadB = new Thread(() -> {
            while (!flag) {
                // 自旋等待 flag
            }
            boolean f = flag;   // ③ volatile 读（接收信号）
            y = 20;             // ④ volatile 写（再次发布）
            System.out.println("[线程 B] f=" + f + ", y=20");
        }, "Thread-B");

        // 先启动 B（等待 flag），再启动 A（写数据）
        threadB.start();
        threadA.start();

        threadA.join();
        threadB.join();

        // 主线程：读 y，验证传递性
        while (y != 20) {
            // 等待 y 变为 20
        }
        // ⑤ volatile 读（y==20 成立）
        if (y == 20) {
            int v = x;     // ⑥ 普通读
            System.out.println("[主线程] y=20, x=" + v);
            if (v == 10) {
                System.out.println("[主线程] ✅ 传递性成立：x=10 对主线程可见");
            } else {
                System.out.println("[主线程] ❌ 传递性失败：x=" + v);
            }
        }
    }
}
