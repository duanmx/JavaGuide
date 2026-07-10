
# Java 并发内存模型（JMM）

> JMM 是 Java 并发编程的"宪法"。它定义了多线程环境下，一个线程的写入对另一个线程何时、以什么顺序可见。不理解 JMM，所有并发知识都是空中楼阁。

---

## 一、为什么需要 JMM？

### 1.1 一个"反直觉"的例子

```java
class SharedData {
    int x = 0;
    int y = 0;
}

SharedData data = new SharedData();

// 线程 A
data.x = 1;
data.y = 2;

// 线程 B
int b = data.y;
int a = data.x;
System.out.println("a=" + a + ", b=" + b);
```

**问：线程 B 能输出 `a=0, b=2` 吗？**

直觉上不可能——B 读到 `y=2`，说明 A 已经写完了，那 `x` 肯定也是 1。

**但 JMM 说：这是可能的。** 输出可以是 `a=0, b=2`。

原因：CPU 和编译器可能对 `x=1` 和 `y=2` 进行**指令重排序**，先写 `y` 再写 `x`。如果线程 B 恰好在 `y=2` 写入后、`x=1` 写入前读取，就会读到旧值。

### 1.2 JMM 的存在意义

JMM 不关心 CPU 如何实现缓存，它只定义一套**软件层面的可见性契约**：

> 在什么条件下，线程 A 的写入对线程 B 保证可见？

这套契约就是 **happens-before** 原则。

---

## 二、主内存 vs 工作内存

### 2.1 核心模型

```
┌─────────────────────────────────────────────────┐
│                  主内存（Main Memory）             │
│                                                 │
│  所有共享变量（堆上的对象字段、静态变量）            │
│  x = 0, y = 0, flag = false, ...               │
│                                                 │
└─────────────────────────────────────────────────┘
          ↑                    ↑
    读/写（有延迟）         读/写（有延迟）
          │                    │
┌─────────┴────────┐   ┌──────┴──────────┐
│ 工作内存（线程 A） │   │ 工作内存（线程 B） │
│                  │   │                 │
│ x = 1（本地副本） │   │ x = 0（旧副本）  │
│ y = 2（本地副本） │   │ y = 0（旧副本）  │
│                  │   │                 │
└──────────────────┘   └─────────────────┘
```

### 2.2 关键规则

- 每个线程有自己的**工作内存**（CPU 缓存 + 寄存器 + Store Buffer 的抽象）
- 线程对变量的读写**首先在工作内存中进行**，不直接操作主内存
- 工作内存中的副本**何时刷回主内存**，由 JMM 规则决定

> 这不是"Java 的设计缺陷"，而是**硬件事实**——CPU 有 L1/L2 缓存，数据在缓存里操作比写回主存快 100 倍。JMM 只是对这一硬件事实的软件层抽象。

### 2.3 与硬件的映射

| JMM 概念 | 硬件实现 |
|---------|---------|
| 主内存 | 主存（DRAM） |
| 工作内存 | L1/L2 缓存 + 寄存器 + Store Buffer |
| 工作内存刷回主存 | MESI 协议的写回（Write Back）+ Store Buffer 刷出 |

---

## 三、happens-before 原则（JMM 的灵魂）

### 3.1 定义

如果操作 A **happens-before** 操作 B，那么：
- A 的结果对 B **保证可见**
- A 的执行顺序在 B **之前**

happens-before 不等于"时间上先发生"，它是一种**逻辑上的顺序保证**。

### 3.2 八条 happens-before 规则

> 所有 happens-before 规则的底层实现都是同一个模式：**写端插入刷出屏障 + 读端插入加载屏障**。区别只在于屏障插入的时间点不同。

#### 规则 1：程序顺序规则

同一线程内，前面的操作 happens-before 后面的操作。

```java
int a = 1;  // A
int b = 2;  // B
// A happens-before B → 单线程下最终结果一定是 a=1, b=2
// 但注意：a 和 b 无数据依赖，CPU/编译器可以重排序！
// happens-before 保证的是“结果等价”，不是“实际不重排序”
```

**为什么必须有这条规则？**

JMM 有一条不可打破的底线——**as-if-serial 语义**：不管底层怎么重排序，单线程程序的执行结果不能被改变。

如果违反程序顺序规则：

```java
int a = 1;     // A
int b = 2;     // B
int c = a + b; // C（依赖 a 和 b）

// 如果允许 C 先于 A 执行 → c = 0 + 0 = 0 ❌
// 如果允许 C 先于 B 执行 → c = 1 + 0 = 1 ❌
// 结果变了！单线程程序的正确性就崩溃了
```

**重排序可以发生，但有严格条件——只能重排序互不依赖的操作：**

| 依赖类型 | 示例 | 能否重排序 |
|---------|------|----------|
| 写后读（RAW） | `a = 1; b = a + 1;` | ❌ b 依赖 a |
| 读后写（WAR） | `b = a; a = 2;` | ❌ b 要读 a 的旧值 |
| 写后写（WAW） | `a = 1; a = 2;` | ❌ 最终 a 必须是 2 |
| 无依赖 | `a = 1; b = 2;` | ✅ 可以重排序，结果不变 |

**程序顺序规则的真正含义**：

```
不是“同一线程的操作严格按源码顺序执行”
而是“同一线程的操作，最终结果看起来像按源码顺序执行”

源码：A → B → C → D
CPU 实际可能：A → C → B → D（如果 B 和 C 互不依赖）
但保证：单线程观察到的结果 = 按 A → B → C → D 的结果
```

**为什么这条规则在多线程下“看似失效”？**

程序顺序规则只保证**执行者自己看到的结果正确**，不保证其他线程看到的顺序：

```java
// 线程 A
x = 1;  // A1
y = 2;  // A2（x 和 y 无依赖，CPU 可能重排序为 A2 → A1）

// 线程 B
int b = y;  // 读到 2
int a = x;  // 可能读到 0！因为 A1 还没执行
```

线程 A 自己看到的结果一定正确（程序顺序规则），但线程 B 可能看到 `y=2, x=0`。要让 B 也看到正确顺序，需要 `volatile` 或 `synchronized`。

#### 规则 2：监视器锁规则（synchronized）

**解锁** happens-before **加锁**（同一个 monitor）。

```java
// 线程 A                        // 线程 B
synchronized(lock) {             synchronized(lock) {
    x = 1;                           int v = x;  // 保证读到 1
}  // ← 解锁                  // ← 加锁
```

**底层发生了什么：**

```
线程 A（解锁方）                          线程 B（加锁方）
─────────────                        ─────────────

synchronized(lock) {
    x = 1;          // 普通写
}
  │
  monitorexit 时：
  ├── 插入 [StoreStore + StoreLoad] 屏障
  │     → 把 x=1 从工作内存刷回主存
  └── 释放 monitor                   monitorexit 时：
                                       ├── 等待获取 monitor
                                       ├── 插入 [LoadLoad + LoadStore] 屏障
                                       │     → 从主存加载最新数据
                                       │     → 包括 x=1
                                       └── int v = x; → 保证读到 1 ✅
```

**关键点**：synchronized 块内的所有修改，退出后对下一个进入同一把锁的线程一定可见。不需要额外加 volatile。

#### 规则 3：volatile 变量规则

**volatile 写** happens-before **volatile 读**。

```java
// 线程 A                     // 线程 B
x = 1;                        boolean f = flag;  // volatile 读
flag = true;  // volatile 写   int v = x;          // 保证读到 1
```

**底层发生了什么：**

```
线程 A（写端）                              线程 B（读端）
─────────────                            ─────────────

x = 1;                // 普通写，进 Store Buffer
                                          boolean f = flag;  // volatile 读
[StoreStore 屏障]       // 把 x=1 刷出到 L1     ├── 从主存/最新缓存加载 flag=true
flag = true;          // volatile 写        ├── [LoadLoad 屏障]
[StoreLoad 屏障]       // 刷出 volatile 写    │     后面的读不能重排到 flag 前面
                                          └── int v = x; → 保证读到 1 ✅
                                              [LoadStore 屏障]
                                                后面的写不能重排到 flag 前面
```

**volatile 的设计意图——发布（Publication）模式**：

```
写端：普通写 A、B、C → volatile 写（发布信号：“数据准备好了”）
      StoreStore 屏障把 A、B、C 一起刷出

读端：volatile 读（接收信号）→ 使用 A、B、C
      保证看到最新值

不需要给 A、B、C 每个都加 volatile
一个 volatile 写就能把所有依赖数据带出去
```

**volatile 的四种屏障完整布局**：

```
普通写 A
普通写 B
── StoreStore 屏障 ──   ← 把前面的写从 Store Buffer 刷出到 L1
volatile 写 C             ← 写入 Store Buffer
── StoreLoad 屏障 ──      ← 把 volatile 写也刷出，确保后续读可见
普通读 D                    ← 保证读到最新值
```

| 屏障 | 位置 | 作用 | x86 需要硬件指令？ |
|------|------|------|:---:|
| StoreStore | volatile 写之前 | 前面的写必须刷出 | 合并到 lock 指令 |
| StoreLoad | volatile 写之后 | 写对后续读可见 | lock 指令 |
| LoadLoad | volatile 读之后 | 后面的读不能重排到前面 | 不需要（x86 天然保证） |
| LoadStore | volatile 读之后 | 后面的写不能重排到前面 | 不需要（x86 天然保证） |

#### 规则 4：线程启动规则

`Thread.start()` happens-before 该线程中的任何操作。

```java
// 主线程                     // 子线程
x = 10;
t.start();  ──────────────▶  int v = x;  // 保证读到 10
```

**底层发生了什么：**

```
主线程                                子线程
────────                              ────────
x = 10

t.start() 内部：
  ├── 创建 OS 线程
  ├── 插入 [StoreStore + StoreLoad] 屏障
  │     → 把 x=10 刷回主存
  └── 启动子线程
                                      子线程入口：
                                        ├── 插入 [LoadLoad + LoadStore] 屏障
                                        │     → 从主存加载最新数据
                                        │     → 包括 x=10
                                        └── int v = x; → 保证读到 10 ✅
```

> 不只是“从主存读”。底层是 JVM 在 start() 调用时和子线程入口分别插入了刷出屏障和加载屏障，两端配合保证可见性。

#### 规则 5：线程终止规则

线程中的**所有操作** happens-before `join()` 返回。

```java
// 子线程                     // 主线程
x = 10;
// 线程结束  ──────────────▶  t.join();
                              int v = x;  // 保证读到 10
```

**底层发生了什么：**

```
子线程                                主线程
────────                              ────────
x = 10
                                      t.join() 阻塞等待

子线程执行完毕：
  ├── 插入 [StoreStore + StoreLoad] 屏障
  │     → 把 x=10 刷回主存
  └── 子线程退出
                                      join() 返回：
                                        ├── 插入 [LoadLoad + LoadStore] 屏障
                                        │     → 从主存加载最新数据
                                        │     → 包括 x=10
                                        └── int v = x; → 保证读到 10 ✅
```

#### 规则 6：中断规则

`interrupt()` happens-before 被中断线程检测到中断。

```java
// 主线程                          // worker 线程
worker.interrupt();  ────────▶    while (!isInterrupted()) {
                                      // 保证能检测到中断 ✅
                                  }
```

**底层发生了什么：**

```
主线程                                worker 线程
────────                              ────────────
                                      while (!isInterrupted())
                                        → 读中断标志（工作内存 = false）

worker.interrupt()：
  ├── 设置中断标志 = true
  ├── 插入 [StoreStore + StoreLoad] 屏障
  │     → 把中断标志刷回主存
  └── 发送中断信号（OS 层面）
                                      worker 被中断信号唤醒：
                                        ├── 插入 [LoadLoad + LoadStore] 屏障
                                        │     → 从主存加载最新中断标志
                                        └── isInterrupted() → true ✅
```

三种检测中断的方式，中断规则都保证可见：

```java
// 方式 1：isInterrupted()（不重置标志）
while (!Thread.currentThread().isInterrupted()) { }

// 方式 2：interrupted()（重置标志为 false）
while (!Thread.interrupted()) { }

// 方式 3：捕获 InterruptedException
try { Thread.sleep(1000); }
catch (InterruptedException e) { /* 检测到中断 */ }
```

#### 规则 7：终结器规则

对象的构造方法 happens-before 该对象的 `finalize()` 方法。

```java
class MyResource {
    int value;
    MyResource() { this.value = 42; }       // 构造方法
    protected void finalize() {
        System.out.println(value);           // 保证读到 42 ✅
    }
}
```

**底层发生了什么：**

```
构造方法执行时（某业务线程）：         GC 线程执行 finalize() 时：
  this.value = 42                        finalize() 入口：
  [StoreStore + StoreLoad] 屏障            [LoadLoad + LoadStore] 屏障
    → 把 value=42 刷回主存                    → 从主存加载 value=42
  对象创建完成                             System.out.println(value) → 42 ✅
```

**为什么需要这条规则？** finalize() 由 GC 线程调用，和业务线程不是同一个线程。如果没有屏障，GC 线程可能看到 value 的零值（0），而不是构造方法初始化的 42。

> 注意：finalize() 在 JDK 9+ 已标记为 @Deprecated，推荐使用 AutoCloseable + try-with-resources 替代。

#### 规则 8：传递性

A happens-before B，B happens-before C → A happens-before C。

**直白理解**：如果 A 的结果对 B 保证可见，B 的结果对 C 保证可见，那么 A 的结果对 C 也保证可见。

```java
// 配置线程                         // 服务线程                         // 请求线程
dbUrl = "jdbc:...";  // ①          while (!configReady) { }           while (!serviceReady) { }
configReady = true;  // ② volatile   boolean f = configReady; // ③      if (serviceReady) {  // ⑤
                                   serviceReady = true;  // ④ volatile    use(dbUrl);  // ⑥
                                                                        }

传递性链条：① → ② → ③ → ④ → ⑤ → ⑥
→ 请求线程保证能看到 dbUrl 的值 ✅
```

**传递性是公理，不是定理**：

```
传递性不能从前 7 条规则推导出来
它是 JMM 设计者直接定义的公理
作用是让 7 条规则能“串联”成链条

类比：
  “张三比李四高” + “李四比王五高” = “张三比王五高”
  身高关系天然具有传递性，不需要额外证明

  happens-before 也需要显式声明传递性
  否则无法推导跨多个线程的可见性链条
```

**底层为什么传递性自然成立？**

传递性不需要额外的硬件机制，它就是内存屏障 + MESI 的自然结果：配置线程把 dbUrl 刷出到主存后，它一直在主存里，不管经过多少个 volatile 信号传递，最终请求线程读的时候一定能读到。

**实际开发中不需要每次推导**：

```
日常开发只需要记住一条经验法则：
  volatile 写之前的所有写，对 volatile 读之后的所有读保证可见
  不需要画 happens-before 链条

只有以下场景才需要推导：
  ① 面试
  ② 排查并发 bug
  ③ 设计无锁数据结构
```

### 3.3 八条规则的底层实现统一模式

```
所有 happens-before 规则的底层都是同一个模式：

  写端：插入 [StoreStore + StoreLoad] 屏障 → 刷出到主存
  读端：插入 [LoadLoad + LoadStore] 屏障 → 从主存加载

区别只在于屏障插入的时间点：
```

| 规则 | 写端（刷出） | 读端（加载） | 屏障插入点 |
|------|-------------|-------------|----------|
| **程序顺序** | 同一线程前面的写 | 同一线程后面的读 | as-if-serial 保证 |
| **监视器锁** | monitorexit 刷出 | monitorenter 加载 | synchronized 边界 |
| **volatile** | volatile 写刷出 | volatile 读加载 | volatile 变量读写时 |
| **线程启动** | start() 刷出 | 子线程入口加载 | Thread.start() |
| **线程终止** | 子线程退出刷出 | join() 返回加载 | Thread.join() |
| **中断** | interrupt() 刷出 | 检测中断时加载 | interrupt/isInterrupted |
| **终结器** | 构造方法刷出 | finalize() 加载 | 构造方法/finalize() |
| **传递性** | 公理，串联上述所有规则 | 不需要额外硬件机制 | 内存屏障 + MESI 自然保证 |

### 3.4 happens-before 规则总览

```
程序顺序规则 ──┐
监视器锁规则 ──┤
volatile 规则 ─┤──┐
线程启动规则 ──┤  ├──▶ 保证可见性的所有路径
线程终止规则 ──┤  │
中断规则 ──────┤  │
终结器规则 ────┤  │
传递性 ────────┘  │
                  ▼
        如果 A happens-before B
        → A 的结果对 B 保证可见
```

---

## 四、指令重排序

### 4.1 三种重排序类型

| 类型 | 发生位置 | 原因 |
|------|---------|------|
| **编译器重排序** | 编译期 | 编译器在不改变单线程语义的前提下调整指令顺序 |
| **CPU 重排序** | 运行时（CPU） | CPU 乱序执行（Out-of-Order Execution），充分利用流水线 |
| **内存系统重排序** | 运行时（Store Buffer） | 写操作先进 Store Buffer，不立即写回缓存 |

### 4.2 编译器重排序示例

```java
// 源码
x = 1;
y = 2;

// 编译器可能优化为（x 和 y 互不依赖）：
y = 2;  // 先写 y
x = 1;  // 后写 x
// 单线程下结果一样，但多线程下可能被其他线程观察到
```

### 4.3 CPU 重排序（乱序执行）

```
现代 CPU 的流水线：

指令 1：从内存加载 a（耗时 100 个时钟周期）
指令 2：b = a + 1（依赖指令 1，必须等）
指令 3：c = 2（不依赖指令 1，可以先执行！）

CPU 实际执行顺序：
  指令 1 开始加载...
  指令 3 先执行（不等指令 1）← CPU 重排序
  指令 1 加载完成
  指令 2 执行
```

### 4.4 Store Buffer 导致的重排序

```
CPU 核心写操作的实际路径：

① 写操作 → Store Buffer（很快，几纳秒）
② Store Buffer → L1 缓存（异步，有延迟）
③ L1 缓存 → 通过 MESI 协议通知其他核心

问题：
  Core 0 写 x=1 → Store Buffer（还没到 L1）
  Core 0 写 y=2 → Store Buffer → L1（y 先到缓存了）

  Core 1 读到 y=2（从 L1/L3 读到了）
  Core 1 读 x → 还是旧值 0（x 还在 Store Buffer 里！）

  → 结果：Core 1 看到 y=2, x=0
  → 这就是 Store Buffer 导致的重排序
```

### 4.5 as-if-serial 语义

JMM 的底线：**不管怎么重排序，单线程程序的执行结果不能改变**。

```java
int x = 1;     // A
int y = 2;     // B
int z = x + y; // C

// A 和 B 可以重排序（互不依赖）
// C 必须在 A 和 B 之后（C 依赖 x 和 y）
// 这就是 as-if-serial：看起来像串行执行，实际可能乱序
```

---

## 五、内存屏障（Memory Barrier）

### 5.1 JMM 定义的四种内存屏障

| 屏障类型 | 作用 | 禁止什么重排序 |
|---------|------|--------------|
| **StoreStore** | 保证前面的写操作在屏障之前的写操作全部刷出 | 写-写重排序 |
| **StoreLoad** | 保证前面的写操作在屏障之后的读操作之前可见 | 写-读重排序（最重量级） |
| **LoadLoad** | 保证后面的读操作在屏障之前的读操作全部完成 | 读-读重排序 |
| **LoadStore** | 保证后面的写操作在屏障之前的读操作之后执行 | 读-写重排序 |

### 5.2 volatile 的内存屏障插入策略

```
volatile 写操作：
  [StoreStore Barrier]  ← 禁止前面的写与当前写重排序
  volatile 写
  [StoreLoad Barrier]   ← 禁止当前写与后面的读重排序

volatile 读操作：
  volatile 读
  [LoadLoad Barrier]    ← 禁止当前读与后面的读重排序
  [LoadStore Barrier]   ← 禁止当前读与后面的写重排序
```

### 5.3 对应到 x86 CPU 指令

```
x86 架构的内存模型特性：
  - 写操作不会和写操作重排序（x86 天然保证）
  - 读操作不会和读操作重排序（x86 天然保证）
  - 写操作可能和后面的读操作重排序（Store Buffer 导致）

因此在 x86 上：
  volatile 写 → lock 前缀指令（如 lock addl $0, 0(%rsp)）
                效果：把 Store Buffer 全部刷入 L1 缓存（全屏障）

  volatile 读 → 不需要额外 CPU 指令（x86 读天然有序）
                但 JVM 在编译层面仍禁止编译器重排序
```

> 注意：x86 是强内存模型（TSO，Total Store Order），很多重排序天然不会发生。但 ARM/RISC-V 是弱内存模型，JMM 的屏障在 ARM 上会生成更多的硬件屏障指令。

---

## 六、volatile 详解

### 6.1 volatile 的两个语义

| 语义 | 说明 | 如何实现 |
|------|------|---------|
| **可见性** | volatile 写后立即对其他线程可见 | 内存屏障 + Store Buffer 刷出 |
| **有序性** | 禁止 volatile 前后的指令重排序 | 内存屏障阻止编译器/CPU 重排序 |

**volatile 不保证原子性**：

```java
volatile int count = 0;
count++;  // 不是原子操作！
```

```
count++ 分解为三步：
  ① 读 count（volatile 读，从主存加载）
  ② +1（在工作内存中计算）
  ③ 写 count（volatile 写，刷回主存）

两个线程同时执行 count++：
  线程 A 读 count=0
  线程 B 读 count=0（此时 A 还没写回）
  线程 A 写 count=1
  线程 B 写 count=1  ← 丢失一次自增！
```

### 6.2 volatile 的正确使用场景

```java
// ✅ 场景 1：状态标志
volatile boolean running = true;
while (running) { /* 工作 */ }
// 其他线程设置 running = false 后，工作线程能及时感知

// ✅ 场景 2：双重检查锁定（DCL）
private static volatile Singleton instance;
public static Singleton getInstance() {
    if (instance == null) {
        synchronized (Singleton.class) {
            if (instance == null) {
                instance = new Singleton();  // volatile 禁止重排序
            }
        }
    }
    return instance;
}

// ❌ 场景 3：计数器（不保证原子性）
volatile int count = 0;
count++;  // 线程不安全！
```

### 6.3 DCL 为什么必须加 volatile？

```java
instance = new Singleton();
```

这行代码在字节码层面分三步：

```
步骤 A：分配内存空间（allocate）
步骤 B：初始化对象（调用构造方法，<init>）
步骤 C：把引用指向内存地址（instance = 地址）

正常顺序：A → B → C

编译器可能重排序为：A → C → B
  （因为 C 和 B 在单线程下没有数据依赖）

问题：
  线程 1 执行到 A → C（instance 已有值，但对象还没初始化完）
  线程 2 进来，检查 instance != null → 直接返回
  → 线程 2 拿到一个半初始化的对象！💥

加了 volatile 后：
  volatile 禁止 A → C → B 的重排序
  强制按 A → B → C 执行
  线程 2 要么看到 null（进入等待），要么看到完全初始化的对象
```

---

## 七、synchronized 的内存语义

### 7.1 synchronized 自带 happens-before

很多人不知道：**synchronized 块内的修改，退出后对其他线程一定可见**，不需要额外加 volatile。

```
monitorenter（加锁）时：
  JVM 插入 [LoadLoad + LoadStore] 屏障
  → 把工作内存失效，从主内存重新加载最新数据

monitorexit（解锁）时：
  JVM 插入 [StoreStore + StoreLoad] 屏障
  → 把工作内存的所有修改刷回主内存
```

### 7.2 synchronized 的可见性保证

```java
// 线程 A                          // 线程 B
synchronized (lock) {              synchronized (lock) {
    x = 1;                             // 保证读到 x=1
    y = 2;                             // 保证读到 y=2
    list.add("hello");                  // 保证看到 list 的变化
}  // ← 解锁，刷回主存          // ← 加锁，从主存加载
```

### 7.3 synchronized vs volatile 的内存语义对比

| 维度 | synchronized | volatile |
|------|-------------|---------|
| 可见性 | ✅ 加锁时刷新，解锁时刷回 | ✅ 写时刷回，读时刷新 |
| 有序性 | ✅ 临界区内可以自由重排序（对外不可见） | ✅ 禁止 volatile 前后的重排序 |
| 原子性 | ✅ 整个临界区互斥 | ❌ 不保证复合操作的原子性 |
| 性能开销 | 较大（可能进内核） | 较小（用户态内存屏障） |

---

## 八、final 字段的内存语义

### 8.1 final 的 happens-before 保证

构造方法中对 final 字段的写入，happens-before 任何其他线程对该对象的访问。

```java
class FinalExample {
    final int x;
    int y;

    FinalExample() {
        x = 42;  // final 字段
        y = 10;  // 非 final 字段
    }
}

// 线程 A
FinalExample obj = new FinalExample();

// 线程 B（拿到 obj 引用后）
int a = obj.x;  // 保证读到 42 ✅（final 保证）
int b = obj.y;  // 可能读到 0 ❌（非 final，无保证）
```

### 8.2 final 禁止的重排序

```java
// 没有 final 时，构造方法可能重排序为：
obj = 分配内存;      // 1. 分配内存
obj.x = 42;         // 2. 初始化
// 可能重排序为：
obj = 分配内存;      // 1. 分配内存
// obj 引用已发布！其他线程可能拿到半初始化对象
obj.x = 42;         // 2. 初始化（还没执行到这里）

// 有 final 时，JMM 保证：
obj = 分配内存;
obj.x = 42;         // final 字段初始化必须在引用发布之前完成
// 然后才能把 obj 引用发布给其他线程
```

### 8.3 final 的限制

```java
// final 只保证直接字段的可见性
// 如果 final 字段是引用类型，引用的对象内部不保证！
class Container {
    final int[] data;
    Container() {
        data = new int[10];
        data[0] = 42;  // 数组元素的可见性不被 final 保证！
    }
}

// 要保证引用对象内部的可见性，仍然需要 volatile 或 synchronized
```

---

## 九、JMM 与硬件的完整映射

```
JMM 概念                     硬件实现
─────────────────────────────────────────────────────
主内存                        主存（DRAM）
工作内存                      L1/L2 缓存 + 寄存器 + Store Buffer
happens-before               内存屏障指令
  volatile 写                 lock 前缀指令（x86）/ DMB ST（ARM）
  volatile 读                 编译屏障（x86）/ DMB LD（ARM）
  synchronized 解锁           lock 前缀指令 + 全屏障
  synchronized 加锁           加载屏障
指令重排序
  编译器重排序                 编译器优化（-O2）
  CPU 重排序                  CPU 乱序执行（Out-of-Order）
  内存系统重排序               Store Buffer 延迟刷出
MESI 缓存一致性               volatile 可见性的硬件基础
```

### 不同 CPU 架构的内存模型强度

```
x86（强内存模型 TSO）：
  ✅ 写不会和写重排序
  ✅ 读不会和读重排序
  ❌ 写可能和后面的读重排序（Store Buffer）
  → volatile 写需要 lock 指令，volatile 读不需要额外指令

ARM / RISC-V（弱内存模型）：
  ❌ 写可能和写重排序
  ❌ 读可能和读重排序
  ❌ 写可能和读重排序
  → volatile 读写都需要显式的屏障指令（DMB）
  → 性能开销比 x86 更大
```

---

## 十、常见面试题

### Q1：volatile 能保证原子性吗？

不能。`volatile int count; count++;` 仍然不是线程安全的，因为 `count++` 是读-改-写三步操作，volatile 只保证每步的可见性，不保证三步的原子性。

解决方案：`AtomicInteger`（CAS）或 `synchronized`。

### Q2：为什么 DCL 单例必须加 volatile？

`new Singleton()` 分三步：分配内存 → 初始化对象 → 引用赋值。编译器可能把后两步重排序为"先赋值再初始化"，导致其他线程拿到半初始化对象。volatile 禁止这种重排序。

### Q3：synchronized 块内的修改，退出后其他线程一定能看到吗？

是的。`monitorexit`（解锁）时插入 StoreStore + StoreLoad 屏障，强制把工作内存刷回主存。后续的 `monitorenter`（加锁）会插入 LoadLoad + LoadStore 屏障，从主存加载最新数据。

### Q4：Thread.start() 之前对变量的修改，子线程能看到吗？

是的。线程启动规则保证 `Thread.start()` happens-before 子线程的任何操作。所以 start 之前主线程对共享变量的修改，子线程都能看到。

### Q5：final 字段为什么不需要 volatile？

final 字段在构造方法中赋值后，JMM 保证构造方法的写入不会和对象引用的发布发生重排序。其他线程拿到对象引用时，final 字段一定已经初始化完毕。

---

## 总结

### 如何建立 JMM 的完整认知

```
第一层：理解"为什么"
  → CPU 有缓存 + 乱序执行，线程间可见性不是理所当然的
  → 工作内存是硬件事实，不是 Java 的设计缺陷

第二层：理解"是什么"
  → JMM = 主内存/工作内存模型 + happens-before 规则
  → 定义了哪些操作对其他线程保证可见

第三层：理解"怎么做"
  → volatile 通过内存屏障实现 happens-before
  → synchronized 通过 monitor 获取/释放时插入屏障
  → final 通过禁止特定重排序实现安全发布

第四层：理解"底层对应"
  → happens-before → 内存屏障指令（mfence / lock / dmb）
  → 内存屏障 → Store Buffer 刷出 + MESI 协议
  → 指令重排序 → CPU 乱序执行 + 编译器优化 + Store Buffer
```

> **面试话术**：JMM 是 Java 并发编程的内存可见性规范。它定义了主内存和工作内存的抽象模型，通过 happens-before 原则规定哪些操作对其他线程可见，通过内存屏障禁止特定的指令重排序。volatile 在写操作时插入 StoreStore + StoreLoad 屏障，读操作时插入 LoadLoad + LoadStore 屏障，保证可见性和有序性。synchronized 在加锁和解锁时自带内存屏障，保证临界区内的修改对后续获取锁的线程可见。JMM 本质上是 MESI 缓存一致性协议和 CPU 乱序执行机制的软件层面抽象。
