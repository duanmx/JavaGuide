# Java 并发不安全的根本原因

> Java 并发不安全的根源不是 Java 语言的设计缺陷，而是**硬件事实**——多核 CPU 的缓存架构、乱序执行机制、线程调度机制，这三个硬件特性共同导致了并发问题。

---

## 一、三个根本原因

| 硬件根源 | 导致的并发问题 | 一句话描述 |
|---------|-------------|----------|
| **多核 CPU 缓存私有** | 可见性问题 | 线程 A 改了变量，线程 B 不一定能立刻看到 |
| **CPU 乱序执行 + 编译器优化** | 有序性问题 | 代码实际执行顺序和源码顺序不一致 |
| **线程时间片切换** | 原子性问题 | 一个操作执行到一半，被调度器切换到其他线程 |

---

## 二、可见性问题——多核 CPU 缓存私有

### 2.1 硬件根源

```
多核 CPU 的缓存架构：

        ┌──────┐  ┌──────┐
        │Core 0│  │Core 1│
        └──┬───┘  └──┬───┘
           │         │
        ┌──┴───┐  ┌──┴───┐
        │ L1   │  │ L1   │  ← 每个核心私有！物理隔离！
        └──┬───┘  └──┬───┘
           │         │
        ┌──┴───┐  ┌──┴───┐
        │ L2   │  │ L2   │  ← 每个核心私有！
        └──┬───┘  └──┬───┘
           └────┬────┘
                │
         ┌──────┴──────┐
         │   主存 DRAM   │  ← 所有核心共享
         └─────────────┘
```

### 2.2 问题场景

```
Core 0 运行线程 A                 Core 1 运行线程 B
─────────────                   ─────────────

x = 1;                          int v = x;
  → 写入 Core 0 的 L1 缓存        → 从 Core 1 的 L1 缓存读
  → x = 1 在 Core 0 的 L1 中      → Core 1 的 L1 中 x 还是旧值 0
  → Core 1 的 L1 不知道              → v = 0 ❌（期望 v = 1）

原因：Core 0 和 Core 1 的 L1/L2 缓存是物理隔离的
      Core 0 的写入不会自动同步到 Core 1 的缓存
```

### 2.3 解决方案

```java
// volatile：通过 MESI 协议 + 内存屏障保证可见性
volatile int x = 0;
x = 1;    // volatile 写 → Store Buffer 刷出 → MESI 通知其他核心缓存失效
int v = x; // volatile 读 → 从主存/最新缓存加载

// synchronized：通过 monitor 获取/释放时的内存屏障保证可见性
synchronized (lock) {
    x = 1;
}
synchronized (lock) {
    int v = x;  // 保证读到 1
}
```

---

## 三、有序性问题——CPU 乱序执行 + 编译器优化

### 3.1 硬件根源

三种重排序来源：

| 类型 | 发生位置 | 原因 |
|------|---------|------|
| **编译器重排序** | 编译期 | 编译器在不改变单线程语义的前提下调整指令顺序 |
| **CPU 重排序** | 运行时 | CPU 乱序执行（Out-of-Order），充分利用流水线 |
| **Store Buffer 重排序** | 运行时 | 写操作先进 Store Buffer，不立即写回缓存 |

### 3.2 问题场景一：编译器/CPU 重排序

```java
// 源码
x = 1;          // A
flag = true;    // B

// 编译器/CPU 可能重排为
flag = true;    // B（先执行）
x = 1;          // A（后执行）

// 线程 B 在 B 和 A 之间读取
boolean f = flag;  // true
int v = x;         // 0！因为 x=1 还没执行
```

### 3.3 问题场景二：Store Buffer 重排序

```
Core 0 写操作的实际路径：

  x = 1 → Store Buffer（还没到 L1）
  flag = true → Store Buffer → L1（flag 先到缓存了）

  Core 1 读到 flag = true（从 L1/L3）
  Core 1 读 x → 还是旧值 0（x 还在 Store Buffer 里！）
```

### 3.4 问题场景三：DCL 半初始化

```java
instance = new Singleton();
// 字节码分解：
// A: 分配内存
// B: 初始化对象
// C: 引用赋值（instance = 地址）
//
// 可能重排为 A → C → B
// 其他线程看到 instance != null，但对象还没初始化完
```

### 3.5 解决方案

```java
// volatile：通过内存屏障禁止重排序
volatile boolean flag;
x = 1;
flag = true;
// [StoreStore 屏障] ← 保证 x=1 在 flag=true 之前刷出
// volatile 写

// volatile 禁止 DCL 的重排序
private static volatile Singleton instance;
// StoreStore 屏障强制 B（初始化）在 C（引用赋值）之前完成
```

---

## 四、原子性问题——线程时间片切换

### 4.1 硬件根源

```
CPU 调度器的时间片机制：

  线程 A 执行 → 时间片用完 → 切换到线程 B
  线程 B 执行 → 时间片用完 → 切换到线程 C
  ...

  切换可以发生在任意两条指令之间
  包括一个"逻辑操作"的中间步骤之间
```

### 4.2 问题场景

```java
int count = 0;
count++;  // 逻辑上是一步，实际是三步
```

```
count++ 分解为：
  ① 读 count（从内存加载到寄存器）
  ② +1（在寄存器中计算）
  ③ 写 count（从寄存器写回内存）

线程 A                          线程 B
────────                        ────────
① 读 count = 0
② 计算 0 + 1 = 1
                                ① 读 count = 0（A 还没写回）
                                ② 计算 0 + 1 = 1
③ 写 count = 1
                                ③ 写 count = 1 ← 覆盖了 A 的写入！

最终 count = 1，期望 count = 2 💥
```

### 4.3 关键理解：单核 CPU 也有原子性问题

```
即使没有多核缓存（没有可见性问题）
即使没有指令重排序（没有有序性问题）
单核 CPU 仍然存在原子性问题：

  线程 A 读 count=0
  → 时间片到了，调度器切换到线程 B
  → 线程 B 读 count=0（还是旧值）
  → 线程 B 写 count=1
  → 切换回线程 A
  → 线程 A 写 count=1（覆盖 B 的写入）
  → count=1，期望 2 💥

原子性问题和多核无关，和缓存无关，和重排序无关
它只和"线程时间片切换"有关
```

### 4.4 解决方案

```java
// synchronized：整个临界区互斥，其他线程进不来
synchronized (lock) {
    count++;  // 读→+1→写 三步不会被其他线程打断
}

// CAS（AtomicInteger）：硬件级原子操作 + 自旋重试
AtomicInteger count = new AtomicInteger(0);
count.incrementAndGet();
// 底层：lock cmpxchg 指令，硬件保证读→比较→写是原子的

// Lock（ReentrantLock）：用户层面的互斥锁
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    count++;
} finally {
    lock.unlock();
}
```

---

## 五、三个问题的独立性

### 5.1 三者互不依赖，各自独立存在

```
只有可见性问题（无原子性、无有序性）：
  volatile boolean flag;  // 单步读/写
  → 只需要保证其他线程能看到最新值

只有有序性问题（无可见性、无原子性）：
  DCL 单例的半初始化问题
  → 只有一个线程写，不需要原子性
  → 但赋值和初始化的顺序被重排了

只有原子性问题（无可见性、无有序性）：
  单核 CPU 上的 count++
  → 没有缓存不一致，没有重排序
  → 但时间片切换导致丢失更新
```

### 5.2 三者可能同时出现

```java
// 多线程 count++ 同时存在三个问题：
volatile int count = 0;
count++;

① 可见性：线程 A 写 count，线程 B 可能读不到最新值
   → volatile 解决
② 有序性：count++ 前后的操作可能被重排序
   → volatile 解决
③ 原子性：count++ 的读→+1→写三步可能被其他线程打断
   → volatile 不解决！需要 CAS 或 synchronized
```

---

## 六、Store Buffer——同时影响可见性和有序性

```
Store Buffer 是 CPU 核心内部的写缓冲区：
  写操作 → Store Buffer（很快）→ L1 缓存（异步，有延迟）

对可见性的影响：
  Core 0 写 x=1 → Store Buffer → 还没到 L1
  Core 1 读 x → 从 L1/L3 读到旧值
  → 可见性问题

对有序性的影响：
  Core 0 写 x=1 → Store Buffer（还没刷出）
  Core 0 写 flag=true → 直接到 L1（flag 先可见）
  Core 1 读到 flag=true 但 x 还是旧值
  → 有序性问题（写操作的实际可见顺序和源码顺序不一致）

StoreStore + StoreLoad 屏障同时解决两个问题：
  强制把 Store Buffer 中所有待写数据刷出到 L1
  → 可见性：其他核心能读到最新值
  → 有序性：写操作的可见顺序和源码顺序一致
```

---

## 七、Java 解决方案全景图

```
                    可见性          有序性          原子性
─────────────────────────────────────────────────────────
volatile            ✅              ✅              ❌
synchronized        ✅              ✅              ✅
CAS                 ✅              ✅              ✅（单变量）
Lock                ✅              ✅              ✅（多变量）
final               ✅（构造方法）   ✅（禁止特定重排） ❌
```

### 选型指南

```
问自己三个问题：

① 这个变量会被多个线程写吗？
   → 不会：不需要任何同步机制
   → 会：继续问 ②

② 是复合操作吗？（如 count++、check-then-act）
   → 是：用 synchronized / Lock / AtomicInteger
          volatile 不够（不保证原子性）
   → 否：继续问 ③

③ 这个变量是"发布信号"吗？（如 flag、状态标志）
   → 是：加 volatile（一个 volatile 写带出前面所有普通写）
   → 否：用 synchronized 保护整个临界区
```

---

## 总结

```
Java 并发不安全 = 三个硬件根源 × 三个问题

  多核 CPU 缓存私有   →  可见性  →  volatile / synchronized
  CPU 乱序执行        →  有序性  →  volatile 内存屏障
  线程时间片切换      →  原子性  →  synchronized / CAS / Lock

  Store Buffer        →  同时影响可见性 + 有序性
                       →  StoreStore + StoreLoad 屏障一次解决

三者独立存在，互不依赖：
  单核 CPU 也有原子性问题（时间片切换）
  单步读写也可能有可见性问题（多核缓存）
  没有并发写也可能有有序性问题（DCL 半初始化）
```

> **面试话术**：Java 并发不安全的根本原因有三个。第一，多核 CPU 的 L1/L2 缓存是核心私有的，一个核心的写入不会自动同步到其他核心的缓存，导致可见性问题，通过 volatile（MESI 协议 + 内存屏障）或 synchronized 解决。第二，CPU 乱序执行和编译器优化可能改变指令的实际执行顺序，导致有序性问题，通过 volatile 的 StoreStore/StoreLoad/LoadLoad/LoadStore 四种内存屏障禁止特定重排序。第三，线程时间片切换可能在一个复合操作的中间步骤打断执行，导致原子性问题，即使单核 CPU 也存在，通过 synchronized（互斥）、CAS（硬件原子操作 + 自旋）或 Lock 解决。三者独立存在，需要根据场景选择合适的同步机制。
