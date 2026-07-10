package com.javaguide.concurrent.volatile_demo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * AtomicInteger 演示：volatile + CAS 解决原子性
 *
 * <p>AtomicInteger 内部：
 * <ul>
 *   <li>volatile int value：保证可见性</li>
 *   <li>CAS（compareAndSwapInt）：保证原子性</li>
 * </ul>
 *
 * <p>CAS 自旋流程：
 * <ol>
 *   <li>volatile 读：获取当前值</li>
 *   <li>CAS 尝试更新：如果值没变就更新，否则重试</li>
 * </ol>
 *
 * @author JavaGuide
 */
public class AtomicIntegerDemo {

    static AtomicInteger count = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {

        int threadCount = 10;
        int incrementPerThread = 10000;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementPerThread; j++) {
                    count.incrementAndGet();  // CAS 操作，线程安全
                }
            }, "Thread-" + i);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("期望值: " + (threadCount * incrementPerThread));
        System.out.println("实际值: " + count.get());
        System.out.println("结论: AtomicInteger（volatile + CAS）保证原子性 ✅");
    }
}
