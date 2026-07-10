package com.javaguide.concurrent.lock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * 自定义锁：基于 AQS 实现一个最简单的互斥锁
 *
 * <p>核心思路：
 * <ul>
 *   <li>state = 0 → 未锁定</li>
 *   <li>state = 1 → 已锁定</li>
 *   <li>只需要实现 tryAcquire 和 tryRelease 两个方法</li>
 * </ul>
 *
 * <p>AQS 帮你做的事（你不需要写的）：
 * <ul>
 *   <li>CLH 队列管理（入队/出队）</li>
 *   <li>park/unpark（挂起/唤醒线程）</li>
 *   <li>自旋重试</li>
 *   <li>中断处理</li>
 * </ul>
 *
 * <p>你只需要告诉 AQS：
 * <ul>
 *   <li>tryAcquire：怎么判断"能不能获取锁"</li>
 *   <li>tryRelease：怎么判断"能不能释放锁"</li>
 * </ul>
 *
 * @author JavaGuide
 */
public class MyLock implements Lock {

    /**
     * 内部同步器：继承 AQS，实现 tryAcquire 和 tryRelease
     *
     * <p>这是自定义锁的核心，所有锁的逻辑都在这里
     */
    private static class Sync extends AbstractQueuedSynchronizer {

        /**
         * 尝试获取锁（AQS 的 acquire 会调用这个方法）
         *
         * <p>流程：
         * <ol>
         *   <li>state == 0 → CAS(0, 1) 尝试获取</li>
         *   <li>CAS 成功 → 设置 owner，返回 true</li>
         *   <li>CAS 失败 → 返回 false → AQS 自动入队 + park</li>
         * </ol>
         */
        @Override
        protected boolean tryAcquire(int arg) {
            // CAS 尝试把 state 从 0 改为 1
            if (compareAndSetState(0, 1)) {
                // 成功：记录当前线程为锁持有者
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            // 失败：返回 false，AQS 会自动把当前线程加入 CLH 队列并 park
            return false;
        }

        /**
         * 尝试释放锁（AQS 的 release 会调用这个方法）
         *
         * <p>流程：
         * <ol>
         *   <li>检查当前线程是否是 owner（不是则抛异常）</li>
         *   <li>state 设为 0</li>
         *   <li>清除 owner</li>
         *   <li>返回 true → AQS 自动 unpark 后继线程</li>
         * </ol>
         */
        @Override
        protected boolean tryRelease(int arg) {
            // 只有持有锁的线程才能释放
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                throw new IllegalMonitorStateException();
            }
            // 清除 owner
            setExclusiveOwnerThread(null);
            // 释放锁（state 设为 0）
            setState(0);
            return true;
            // AQS 会自动 unpark CLH 队列中的下一个线程
        }

        /**
         * 判断当前线程是否独占持有锁
         *
         * <p>ConditionObject.await() 会调用这个方法检查：
         * 当前线程是否持有锁？不持有就调 await 是非法的。
         *
         * <p>不 override 这个方法，调用 newCondition().await() 会抛
         * IllegalMonitorStateException。
         */
        @Override
        protected boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /**
         * 创建条件变量
         *
         * <p>ConditionObject 是 AQS 的内部类，实现了 Condition 接口。
         * 在 Sync 中暴露这个方法，给外部 MyLock 调用。
         */
        Condition newCondition() {
            return new ConditionObject();
        }
    }

    /** 内部同步器实例 */
    private final Sync sync = new Sync();

    // ======================== Lock 接口实现 ========================

    /**
     * 加锁
     * <p>调用 AQS 的 acquire(1)，内部会调用 tryAcquire
     */
    @Override
    public void lock() {
        sync.acquire(1);
    }

    /**
     * 加锁（可中断）
     */
    @Override
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    /**
     * 非阻塞尝试获取锁
     */
    @Override
    public boolean tryLock() {
        return sync.tryAcquire(1);
    }

    /**
     * 超时尝试获取锁
     */
    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(time));
    }

    /**
     * 释放锁
     * <p>调用 AQS 的 release(1)，内部会调用 tryRelease
     */
    @Override
    public void unlock() {
        sync.release(1);
    }

    /**
     * 创建条件变量
     */
    @Override
    public Condition newCondition() {
        return sync.newCondition();
    }

    // ======================== 测试 ========================

    public static void main(String[] args) throws InterruptedException {

        MyLock lock = new MyLock();
        int[] count = {0};

        // 10 个线程并发 count++ 10000 次
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    lock.lock();
                    try {
                        count[0]++;
                    } finally {
                        lock.unlock();
                    }
                }
            }, "Thread-" + i);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("期望值: 100000");
        System.out.println("实际值: " + count[0]);
        System.out.println(count[0] == 100000
                ? "✅ 自定义锁正确！"
                : "❌ 自定义锁有 bug");

        // ===== 测试 Condition（await / signal）=====
        System.out.println("\n===== 测试 Condition =====");

        MyLock condLock = new MyLock();
        Condition condition = condLock.newCondition();
        boolean[] ready = {false};
        String[] message = {""};

        // 等待线程
        Thread waiter = new Thread(() -> {
            condLock.lock();
            try {
                while (!ready[0]) {
                    System.out.println("[waiter] 条件不满足，await 等待...");
                    condition.await();  // 释放锁 + 挂起
                }
                System.out.println("[waiter] 被唤醒！message = " + message[0]);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                condLock.unlock();
            }
        }, "waiter");

        // 通知线程
        Thread signaler = new Thread(() -> {
            condLock.lock();
            try {
                ready[0] = true;
                message[0] = "hello from signaler";
                System.out.println("[signaler] 设置条件，signal 唤醒 waiter");
                condition.signal();  // 唤醒 waiter
            } finally {
                condLock.unlock();
            }
        }, "signaler");

        waiter.start();
        Thread.sleep(500);  // 让 waiter 先进入 await
        signaler.start();

        waiter.join();
        signaler.join();
        System.out.println("✅ Condition 测试完成");
    }
}
