package com.javaguide.concurrent.dcl;

/**
 * DCL（双重检查锁定）单例模式 - 正确实现
 *
 * <p>关键点：instance 必须用 volatile 修饰
 *
 * <p>原因：new Singleton() 在字节码层面分解为：
 * <ol>
 *   <li>A: 分配内存（new 指令）</li>
 *   <li>B: 初始化对象（invokespecial 调用构造方法）</li>
 *   <li>C: 引用赋值（putstatic instance = 地址）</li>
 * </ol>
 *
 * <p>B 和 C 没有数据依赖，可能被重排序为 A → C → B，
 * 导致其他线程看到 instance != null 但对象还没初始化完（半初始化对象）。
 *
 * <p>volatile 通过在引用赋值（C）之前插入 StoreStore 屏障，
 * 强制初始化（B）必须在引用赋值（C）之前完成。
 *
 * @author JavaGuide
 */
public class DCLSingleton {

    /**
     * 必须加 volatile：防止 B（初始化）和 C（引用赋值）重排序
     */
    private static volatile DCLSingleton instance;

    private int value;

    /**
     * 私有构造方法，防止外部 new
     */
    private DCLSingleton() {
        // 模拟耗时的初始化过程
        this.value = 42;
        System.out.println("[" + Thread.currentThread().getName() + "] 构造方法执行，value = " + value);
    }

    /**
     * 获取单例实例
     *
     * @return 单例实例
     */
    public static DCLSingleton getInstance() {
        // 第一次检查：在 synchronized 外面，避免不必要的加锁
        // 如果没有 volatile，这里可能读到半初始化对象
        if (instance == null) {
            // synchronized：保证只创建一个实例
            synchronized (DCLSingleton.class) {
                // 第二次检查：防止两个线程同时通过第一次检查后重复创建
                if (instance == null) {
                    // volatile 写：StoreStore 屏障保证 B 在 C 之前完成
                    instance = new DCLSingleton();
                }
            }
        }
        return instance;
    }

    public int getValue() {
        return value;
    }

    /**
     * 测试：100 个线程同时获取单例，验证是否都是同一个实例
     */
    public static void main(String[] args) throws InterruptedException {
        int threadCount = 100;
        Thread[] threads = new Thread[threadCount];
        DCLSingleton[] results = new DCLSingleton[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = DCLSingleton.getInstance();
            }, "Thread-" + i);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        // 验证：所有线程拿到的是同一个实例
        boolean allSame = true;
        for (int i = 1; i < threadCount; i++) {
            if (results[i] != results[0]) {
                allSame = false;
                break;
            }
        }

        System.out.println("所有线程拿到同一实例: " + allSame);
        System.out.println("value = " + results[0].getValue());
        System.out.println("实例地址: " + results[0]);
    }
}
