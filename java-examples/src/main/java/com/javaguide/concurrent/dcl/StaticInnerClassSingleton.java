package com.javaguide.concurrent.dcl;

/**
 * 单例模式：静态内部类实现（推荐写法）
 *
 * <p>利用类加载机制保证线程安全，不需要 synchronized 和 volatile。
 *
 * <p>原理：
 * <ul>
 *   <li>JVM 保证类的静态字段只初始化一次</li>
 *   <li>类加载过程是线程安全的</li>
 *   <li>Holder 类只有在第一次调用 getInstance() 时才被加载（懒加载）</li>
 * </ul>
 *
 * @author JavaGuide
 */
public class StaticInnerClassSingleton {

    /**
     * 私有构造方法
     */
    private StaticInnerClassSingleton() {
        System.out.println("[" + Thread.currentThread().getName() + "] 构造方法执行");
    }

    /**
     * 静态内部类：只有 getInstance() 被调用时才会加载
     */
    private static class Holder {
        // final 保证构造完成后对所有线程可见
        static final StaticInnerClassSingleton INSTANCE = new StaticInnerClassSingleton();
    }

    /**
     * 获取单例实例
     *
     * @return 单例实例
     */
    public static StaticInnerClassSingleton getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 测试
     */
    public static void main(String[] args) throws InterruptedException {
        int threadCount = 100;
        Thread[] threads = new Thread[threadCount];
        StaticInnerClassSingleton[] results = new StaticInnerClassSingleton[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = StaticInnerClassSingleton.getInstance();
            }, "Thread-" + i);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        boolean allSame = true;
        for (int i = 1; i < threadCount; i++) {
            if (results[i] != results[0]) {
                allSame = false;
                break;
            }
        }

        System.out.println("所有线程拿到同一实例: " + allSame);
    }
}
