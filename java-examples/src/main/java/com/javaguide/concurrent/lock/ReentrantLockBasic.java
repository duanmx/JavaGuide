package com.javaguide.concurrent.lock;

import java.util.concurrent.locks.ReentrantLock;

/**
 * ReentrantLock 基础用法演示
 *
 * <p>ReentrantLock = AQS 的用户层实现
 * <ul>
 *   <li>lock() → AQS.acquire() → tryAcquire (CAS) → 失败则入 CLH 队列 → park</li>
 *   <li>unlock() → AQS.release() → tryRelease → unparkSuccessor</li>
 * </ul>
 *
 * @author JavaGuide
 */
public class ReentrantLockBasic {

    private static final ReentrantLock lock = new ReentrantLock();
    private static int count = 0;

    public static void main(String[] args) throws InterruptedException {

        // ===== 示例 1：基本用法（lock / unlock）=====
        System.out.println("===== 示例 1：基本用法 =====");

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    lock.lock();         // 加锁（AQS.acquire）
                    try {
                        count++;         // 临界区
                    } finally {
                        lock.unlock();   // 释放锁（AQS.release）
                        // 必须在 finally 中释放，否则异常时锁永远不释放
                    }
                }
            }, "Thread-" + i);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("期望值: 100000");
        System.out.println("实际值: " + count);
        System.out.println(count == 100000 ? "✅ ReentrantLock 保证原子性" : "❌ 结果不正确");

        // ===== 示例 2：tryLock（非阻塞尝试获取锁）=====
        System.out.println("\n===== 示例 2：tryLock =====");

        ReentrantLock lock2 = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            lock2.lock();
            try {
                System.out.println("[t1] 获取到锁，执行 2 秒...");
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock2.unlock();
                System.out.println("[t1] 释放锁");
            }
        }, "t1");

        Thread t2 = new Thread(() -> {
            // tryLock 不会阻塞，获取不到立刻返回 false
            if (lock2.tryLock()) {
                try {
                    System.out.println("[t2] 获取到锁");
                } finally {
                    lock2.unlock();
                }
            } else {
                System.out.println("[t2] 获取锁失败（t1 持有中），不阻塞，继续做其他事");
            }
        }, "t2");

        t1.start();
        Thread.sleep(500);  // 让 t1 先拿到锁
        t2.start();

        t1.join();
        t2.join();
    }
}
