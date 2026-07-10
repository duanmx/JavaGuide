package com.javaguide.concurrent.volatile_demo;

/**
 * volatile 不保证原子性
 *
 * <p>volatile int count; count++; 不是线程安全的。
 *
 * <p>原因：count++ 分解为三步：
 * <ol>
 *   <li>读 count（volatile 读，从主存加载）</li>
 *   <li>+1（在工作内存中计算）</li>
 *   <li>写 count（volatile 写，刷回主存）</li>
 * </ol>
 *
 * <p>①和③之间可能被其他线程打断，导致丢失更新。
 *
 * @author JavaGuide
 */
public class VolatileNotAtomic {

    static volatile int count = 0;

    public static void main(String[] args) throws InterruptedException {

        int threadCount = 10;
        int incrementPerThread = 10000;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementPerThread; j++) {
                    count++;  // 不是原子操作！
                }
            }, "Thread-" + i);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("期望值: " + (threadCount * incrementPerThread));
        System.out.println("实际值: " + count);
        System.out.println("丢失更新: " + (threadCount * incrementPerThread - count));
        System.out.println("结论: volatile 不保证原子性，count++ 会丢失更新 ❌");
    }
}
