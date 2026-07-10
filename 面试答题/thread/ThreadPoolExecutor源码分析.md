# ThreadPoolExecutor 源码分析

## 一、整体架构

```
ThreadPoolExecutor
├── ctl (AtomicInteger)          ← 一个 int 同时存储运行状态 + 线程数量
├── workQueue (BlockingQueue)    ← 任务队列
├── mainLock (ReentrantLock)     ← 保护 workers 集合的全局锁
├── workers (HashSet<Worker>)    ← 所有工作线程集合
├── termination (Condition)      ← 等待线程池终止的条件变量
├── corePoolSize                 ← 核心线程数
├── maximumPoolSize              ← 最大线程数
├── keepAliveTime                ← 非核心线程空闲存活时间
├── threadFactory                ← 线程工厂
└── handler                      ← 拒绝策略
```

---

## 二、ctl：一个 int 编码两个字段

```java
private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
private static final int COUNT_BITS = Integer.SIZE - 3;  // 29
private static final int CAPACITY   = (1 << COUNT_BITS) - 1; // 约5亿
```

**ctl 的位布局（32位 int）：**

```
┌───────────┬────────────────────────────┐
│ 高3位      │ 低29位                      │
│ runState   │ workerCount                │
└───────────┴────────────────────────────┘
```

**为什么要这么做？** 如果用两个变量（一个存状态、一个存线程数），就需要两把锁来保证原子性。合进一个 int，一次 CAS 就能同时更新两个值，性能更好。

**5种运行状态（高3位）：**

| 状态 | 值 | 含义 |
|------|-----|------|
| RUNNING | -1 (111) | 接受新任务，处理队列任务 |
| SHUTDOWN | 0 (000) | 不接受新任务，但处理队列中已有任务 |
| STOP | 1 (001) | 不接受新任务，不处理队列，中断执行中的任务 |
| TIDYING | 2 (010) | 所有任务结束，workerCount=0，准备执行 terminated() |
| TERMINATED | 3 (011) | terminated() 执行完毕 |

**状态转换：**
```
RUNNING → SHUTDOWN        （调用 shutdown()）
RUNNING/SHUTDOWN → STOP   （调用 shutdownNow()）
SHUTDOWN → TIDYING        （队列空 + 线程空）
STOP → TIDYING            （线程空）
TIDYING → TERMINATED      （terminated() 执行完）
```

**位运算工具方法：**
```java
private static int runStateOf(int c)     { return c & ~CAPACITY; }   // 取高3位
private static int workerCountOf(int c)  { return c & CAPACITY; }    // 取低29位
private static int ctlOf(int rs, int wc) { return rs | wc; }         // 合并
```

---

## 三、Worker：工作线程（最精妙的部分）

```java
private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
    final Thread thread;       // 绑定的线程
    Runnable firstTask;        // 第一个任务（可为null）
    volatile long completedTasks; // 已完成任务计数
}
```

### Worker 里的"锁"不是传统意义上的锁

传统锁是保护**数据**的——防止多个线程同时修改同一个变量。但 Worker 里的锁完全是另一种用法：**它是给 shutdown 发的信号，告诉它"我能不能被中断"。**

```
传统锁：
  线程A lock → 操作共享资源 → unlock
  线程B lock → 阻塞等待...        ← 防止 B 和 A 同时操作

Worker 的锁：
  Worker线程 lock   → "我在干活，别中断我"
  Worker线程 unlock → "我闲了，可以中断我"
  shutdown线程 tryLock → "你能被中断吗？"
                        成功 → 中断它
                        失败 → 算了，它在忙
```

两个**不同的线程**在通过锁的状态"对话"：Worker 线程写 state，shutdown 线程读 state。

### AQS 的 state 被赋予了全新语义

| 传统 AQS（如 ReentrantLock） | Worker 的 AQS |
|---|---|
| state=0 → 没锁 | state=0 → **空闲**（可以被 shutdown 中断） |
| state=1 → 被锁住了 | state=1 → **正在执行任务**（不能被中断） |
| state>1 → 重入次数 | 不可能（不可重入） |
| lock/unlock → 保护临界区 | lock/unlock → **标记忙碌/空闲** |
| 谁 lock 谁 unlock（同一线程） | **不同线程操作**：Worker 线程 lock，shutdown 线程 tryLock 探测 |

### state 的三个值

| state 值 | 含义 | 能否被 shutdown 中断 |
|----------|------|---------------------|
| **-1** | 初始态（线程刚创建，还没进入 runWorker） | 不能 |
| **0** | 空闲（在 getTask() 里等任务） | 能 |
| **1** | 正在执行任务（runWorker 中 w.lock() 了） | 不能 |

### 为什么构造时设 state = -1？

```java
Worker(Runnable firstTask) {
    setState(-1);  // ← 启动保护罩
    this.firstTask = firstTask;
    this.thread = getThreadFactory().newThread(this);
}
```

从 Worker 创建到进入 `runWorker()` 有一段时间（`addWorker` 里还要加 `mainLock`、加入集合等）。如果这期间别的线程调了 `shutdown()`，`tryLock()` 要求 state==0 才能成功，state=-1 时失败，所以不会被误中断。

### 为什么 runWorker 开头 w.unlock() 没有先 w.lock()？

```java
final void runWorker(Worker w) {
    w.unlock(); // state: -1 → 0  ← 没有 lock 就直接 unlock？
    ...
}
```

因为 `unlock()` 内部调的是 `tryRelease()`，而 `tryRelease` 里是 **无条件** `setState(0)`：

```java
protected boolean tryRelease(int unused) {
    setExclusiveOwnerThread(null);
    setState(0);     // ← 不管当前 state 是什么，直接改成 0
    return true;
}
```

所以这里不是"解锁"的意思，而是**借了 `unlock()` 这个现成方法来做 `setState(0)`**。为什么这么写？因为 `setState()` 是 AQS 的 protected 方法，`runWorker` 作为外部方法调不到，但 `unlock()` 是 public 的。Doug Lea 就巧妙地复用了它。

> Doug Lea 自己的注释原文：*"This class opportunistically extends AbstractQueuedSynchronizer"* —— "投机取巧地"继承了 AQS。

### 为什么不用 ReentrantLock？

Worker 故意把锁设计成**不可重入**的。如果任务代码里调了 `setCorePoolSize()` 之类的线程池方法，可重入锁会重入成功，导致 `tryLock()` 误判为"这个线程在干活"。不可重入锁就不会有这个问题。

### shutdown 怎么用 tryLock 判断能不能中断？

```java
// interruptIdleWorkers() 中的关键代码
for (Worker w : workers) {
    Thread t = w.thread;
    if (!t.isInterrupted() && w.tryLock()) {  // ← 无损探测
        try { t.interrupt(); }
        catch (SecurityException ignore) {}
        finally { w.unlock(); }
    }
}
```

- `tryLock` 成功 → state 从 0→1 → 说明线程空闲（阻塞在队列上）→ 放心中断
- `tryLock` 失败 → 说明 state 不是 0（-1 还没启动 或 1 正在执行）→ 跳过，不打扰

---

## 四、execute()：任务提交的入口

用**餐厅**来理解：核心线程 = 正式员工，非核心线程 = 临时工，队列 = 等候区。

```java
public void execute(Runnable command) {
    int c = ctl.get();

    // ========== 第一步：正式员工有空位吗？ ==========
    if (workerCountOf(c) < corePoolSize) {
        if (addWorker(command, true))  // 招正式员工，直接服务这个顾客
            return;
        c = ctl.get(); // 招人失败（并发竞争），重新看现状
    }

    // ========== 第二步：正式员工满了，让顾客去等候区坐 ==========
    if (isRunning(c) && workQueue.offer(command)) {
        int recheck = ctl.get();

        // 回头检查A：顾客坐下这期间，餐厅关门了？
        if (!isRunning(recheck) && remove(command))
            reject(command);  // 关门了 → 把顾客请走

        // 回头检查B：员工都走光了？
        else if (workerCountOf(recheck) == 0)
            addWorker(null, false);  // 赶紧招一个临时工来干活
    }

    // ========== 第三步：等候区也坐满了，招临时工 ==========
    else if (!addWorker(command, false))
        reject(command);  // 临时工也招满了 → 拒绝
}
```

**为什么要"二次检查"？** 因为 `execute()` 可能被多个线程同时调用。在你 `offer` 入队的瞬间，可能发生了：
1. 别的线程调了 `shutdown()`，线程池关了 → 任务在队列里但没人处理
2. 正在干活的线程恰好全抛异常死了 → 队列里有任务但没有线程

所以入队后必须**再看一眼状态**，确保不会出现"任务在队列里但永远没人处理"的情况。

**流程图：**
```
execute(task)
    │
    ├─ 线程数 < core ? ──是──→ addWorker(task, core=true) ──成功──→ return
    │                              │
    │                            失败 → 继续往下走
    │
    ├─ 队列能放入 ? ──是──→ offer 到队列
    │         │                  │
    │         │                  ├─ 二次检查：pool 已关闭？ → 移除并拒绝
    │         │                  └─ 线程数为0？ → addWorker(null, false)
    │         │
    │        否（队列满）
    │
    └─ addWorker(task, core=false) ──成功──→ return
              │
            失败（线程数 >= max）
              │
              └─ reject(task)  ← 触发拒绝策略
```

---

## 五、addWorker()：创建工作线程

```java
private boolean addWorker(Runnable firstTask, boolean core) {
    retry:
    for (;;) {
        int c = ctl.get();
        int rs = runStateOf(c);

        // 检查线程池状态是否允许添加
        if (rs >= SHUTDOWN &&
            !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty()))
            return false;

        // CAS 增加 workerCount（内层自旋）
        for (;;) {
            int wc = workerCountOf(c);
            if (wc >= CAPACITY || wc >= (core ? corePoolSize : maximumPoolSize))
                return false;
            if (compareAndIncrementWorkerCount(c))
                break retry; // CAS 成功，跳出双层循环
            c = ctl.get();   // CAS 失败，重新读取 ctl
            if (runStateOf(c) != rs)
                continue retry; // 状态变了，外层重试
        }
    }

    // 创建 Worker 并启动线程
    Worker w = new Worker(firstTask);  // state = -1（启动保护罩）
    Thread t = w.thread;
    // ... mainLock 保护下加入 workers 集合 ...
    t.start(); // 启动线程 → Worker.run() → runWorker(this)
}
```

**双层循环的设计：**
- 内层：CAS 自旋增加 workerCount，失败了就重试
- 外层：如果 CAS 失败是因为 runState 变了（比如线程池突然 shutdown），回到外层重新判断状态

---

## 六、runWorker()：工作线程的主循环

```java
final void runWorker(Worker w) {
    Thread wt = Thread.currentThread();
    Runnable task = w.firstTask;
    w.firstTask = null;
    w.unlock(); // state: -1 → 0，解除启动保护罩，允许中断

    boolean completedAbruptly = true;
    try {
        while (task != null || (task = getTask()) != null) {
            w.lock();   // 标记"正在执行任务"，防止被 shutdown 中断

            // 如果线程池已 STOP，确保线程被中断
            if ((runStateAtLeast(ctl.get(), STOP) ||
                 (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                && !wt.isInterrupted())
                wt.interrupt();

            try {
                beforeExecute(wt, task);     // 钩子：执行前
                task.run();                   // 执行任务（注意：是 run 不是 start）
                afterExecute(task, null);     // 钩子：执行后（正常）
            } catch (Throwable ex) {
                afterExecute(task, ex);       // 钩子：执行后（异常）
                throw ex;
            } finally {
                task = null;
                w.completedTasks++;
                w.unlock();  // 标记"空闲"
            }
        }
        completedAbruptly = false;
    } finally {
        processWorkerExit(w, completedAbruptly);
    }
}
```

**Worker 锁的状态变化时间线：**

```
构造 Worker          → state = -1（保护罩，谁都中断不了我）
t.start() → runWorker
w.unlock()           → state = 0 （保护罩解除，可以被中断了）
    │
    ▼ getTask() 阻塞在队列上...  此时 shutdown() 能中断我（唤醒我去检查状态）
    │
w.lock()             → state = 1 （我在忙，shutdown 别动我）
    │
    ▼ 执行 task.run()...         此时 shutdown() 看到 tryLock 失败，跳过
    │
w.unlock()           → state = 0 （忙完了，又可以被中断了）
    │
    ▼ getTask() 继续阻塞...
    │
  ... 循环 ...
```

---

## 七、getTask()：从队列取任务

**核心线程和非核心线程的区别，就体现在这里。**

```java
private Runnable getTask() {
    boolean timedOut = false;

    for (;;) {
        int c = ctl.get();

        // 线程池关闭中 + 队列为空 → 退出
        if (runStateOf(c) >= SHUTDOWN && (runStateOf(c) >= STOP || workQueue.isEmpty())) {
            decrementWorkerCount();
            return null;
        }

        int wc = workerCountOf(c);
        // 是否需要超时淘汰？（线程数 > core 或 allowCoreThreadTimeOut）
        boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

        // 线程数超限 或 已超时 → 退出（减少线程）
        if ((wc > maximumPoolSize || (timed && timedOut))
            && (wc > 1 || workQueue.isEmpty())) {
            if (compareAndDecrementWorkerCount(c))
                return null; // 返回 null → runWorker 退出循环 → 线程死亡
            continue;
        }

        try {
            // ★ 核心区别就在这里 ★
            Runnable r = timed ?
                workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :  // 非核心：超时等待
                workQueue.take();                                        // 核心：永久阻塞
            if (r != null) return r;
            timedOut = true; // poll 超时了，下次循环会被淘汰
        } catch (InterruptedException retry) {
            timedOut = false; // 被中断了（shutdown），重置，重新循环检查状态
        }
    }
}
```

**核心线程 vs 非核心线程：**
- 核心线程：`take()` — 永久阻塞等任务，线程池不回收它
- 非核心线程：`poll(keepAliveTime)` — 超时没拿到任务就返回 null → 线程退出 → 被回收

所以"核心线程不会被回收"的本质是：**它调用的是 take() 而不是 poll()**。

---

## 八、shutdown() 与 shutdownNow()

### shutdown()：优雅关闭

```java
public void shutdown() {
    mainLock.lock();
    try {
        checkShutdownAccess();       // 权限检查
        advanceRunState(SHUTDOWN);   // RUNNING → SHUTDOWN
        interruptIdleWorkers();      // 只中断空闲线程（tryLock 成功的）
    } finally { mainLock.unlock(); }
    tryTerminate();                  // 尝试转入 TERMINATED
}
```

### shutdownNow()：立即关闭

```java
public List<Runnable> shutdownNow() {
    mainLock.lock();
    try {
        advanceRunState(STOP);       // → STOP
        interruptWorkers();          // 中断所有线程（包括执行中的！）
        tasks = drainQueue();        // 清空队列，返回未执行的任务
    } finally { mainLock.unlock(); }
    tryTerminate();
    return tasks;
}
```

**对比：**

| | shutdown() | shutdownNow() |
|---|---|---|
| 状态 | → SHUTDOWN | → STOP |
| 新任务 | 拒绝 | 拒绝 |
| 队列任务 | 继续执行 | 清空返回 |
| 执行中的任务 | 等它执行完（tryLock 失败，跳过） | 直接中断（不管在干嘛） |
| 空闲线程 | 中断 | 中断 |

---

## 九、四种拒绝策略

```java
// 1. AbortPolicy（默认）：抛异常
throw new RejectedExecutionException("Task rejected");

// 2. CallerRunsPolicy：调用者线程直接执行（反压机制，让提交者自己干，自然就慢了）
r.run();

// 3. DiscardPolicy：静默丢弃（空方法体，什么都不做）
public void rejectedExecution(Runnable r, ThreadPoolExecutor e) { }

// 4. DiscardOldestPolicy：丢弃队头最旧的任务，重试当前任务
e.getQueue().poll();  // 丢弃队头
e.execute(r);         // 重试
```

---

## 十、三个扩展钩子

```java
protected void beforeExecute(Thread t, Runnable r) { }  // 任务执行前
protected void afterExecute(Runnable r, Throwable t) { } // 任务执行后
protected void terminated() { }                          // 线程池终止时
```

典型用途：日志记录、统计耗时、ThreadLocal 清理等。

---

## 十一、关键设计总结

| 设计点 | 方案 | 原因 |
|--------|------|------|
| 状态+线程数合为一个 int | ctl 位运算 | 一次 CAS 原子更新两个值，避免两把锁 |
| Worker 继承 AQS | state 标记空闲/忙碌 | **不是传统锁，是中断控制信号**——两个不同线程通过 state 状态"对话" |
| Worker 用不可重入锁 | tryAcquire CAS(0,1) | 防止任务调 pool 方法时重入，导致 tryLock 误判 |
| 构造时 state=-1 | 启动保护罩 | 线程创建到进入 runWorker 之间不会被误中断 |
| runWorker 开头 unlock 没有先 lock | 借 unlock 做 setState(0) | setState 是 protected，unlock 是 public，巧妙复用 |
| mainLock 全局锁 | ReentrantLock | 保护 workers HashSet，序列化 interruptIdleWorkers 避免中断风暴 |
| getTask 区分 take/poll | 核心永久阻塞，非核心超时 | "核心线程不回收"的本质就是调的是 take 不是 poll |
| execute 二次检查 | offer 后 recheck ctl | 防止并发场景下任务入队后永远没人处理 |

---

## 十二、Java 线程中断机制详解

> 线程池的 shutdown/shutdownNow 都依赖线程中断，这里系统梳理 Java 中断的核心知识。

### 中断的本质：一个布尔标志

中断**不是**"强制停止线程"，而是**给线程设一个标记位**，线程自己决定怎么响应。

```java
// 线程内部有一个标志位（伪代码，实际在 JVM 底层）
private volatile boolean interrupted = false;
```

### 三个核心 API

| 方法 | 类型 | 作用 | 是否清除标志位 |
|------|------|------|---------------|
| `t.interrupt()` | 实例方法 | **设置**中断标志位 + 唤醒阻塞 | — |
| `t.isInterrupted()` | 实例方法 | **读取** t 的中断标志位 | **不清除** |
| `Thread.interrupted()` | **静态方法** | 读取**当前线程**的标志位 | **清除为 false** |

```java
Thread.currentThread().interrupt();       // 设标志位 = true

Thread.interrupted();                     // 读 + 清除 → 返回 true
Thread.interrupted();                     // 再读 → 返回 false（已被清除）

Thread.currentThread().isInterrupted();   // 读，不清除 → 返回 true
Thread.currentThread().isInterrupted();   // 再读 → 还是 true
```

### 中断对不同状态的线程，效果不同

这是最容易搞混的地方：

```
线程当前在干什么？
    │
    ├─ 正在运行（执行普通代码）
    │     → 仅设置标志位 = true
    │     → 线程不会停！需要自己检查标志位
    │
    └─ 正在阻塞（sleep / wait / take / poll / join / park）
          → 设置标志位 = true
          → 同时唤醒线程，抛出 InterruptedException
          → 标志位被自动清除回 false（!）
```

### 场景演示

**场景 1：线程在跑普通代码（不检查就永远不会停）**

```java
Thread t = new Thread(() -> {
    while (true) {
        System.out.println("还在跑...");
        // 不调 isInterrupted()，线程永远不会停
    }
});
t.start();
t.interrupt();  // 设了标志位，但线程根本不 care
```

**正确写法：自己检查标志位**

```java
Thread t = new Thread(() -> {
    while (!Thread.currentThread().isInterrupted()) {  // ← 检查标志位
        System.out.println("还在跑...");
    }
    System.out.println("收到中断信号，退出");
});
t.start();
t.interrupt();  // 线程检查到标志位，退出循环
```

**场景 2：线程在 sleep（会被唤醒 + 抛异常）**

```java
Thread t = new Thread(() -> {
    try {
        Thread.sleep(10000);           // 睡10秒
        System.out.println("睡完了");   // ← 不会执行到这里
    } catch (InterruptedException e) {
        System.out.println("被中断了！"); // ← 走这里
        // 注意：此时中断标志位已经被清除为 false！
    }
});
t.interrupt();  // 唤醒 sleep，抛 InterruptedException
```

**场景 3：sleep 被中断后，必须恢复中断信号**

```java
// ❌ 错误写法：吞掉中断信号，外层循环永远不停
while (!Thread.currentThread().isInterrupted()) {
    try {
        Thread.sleep(1000);
    } catch (InterruptedException e) {
        // 中断标志被清除了，外层 while 检查不到，继续跑！
    }
}

// ✅ 正确写法：恢复中断信号
while (!Thread.currentThread().isInterrupted()) {
    try {
        Thread.sleep(1000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // ← 重新设置标志位
    }
}
```

### 哪些方法会响应中断（阻塞时抛 InterruptedException）

| 会响应中断 | 不会响应中断 |
|-----------|-------------|
| `Thread.sleep()` | `synchronized` 等锁获取 |
| `Object.wait()` | `ReentrantLock.lock()`（用 `lockInterruptibly()` 代替） |
| `Thread.join()` | IO 操作（`InputStream.read()`） |
| `BlockingQueue.take()` / `poll(timeout)` | 普通代码执行 |
| `LockSupport.park()`（唤醒但不抛异常） | |
| `Condition.await()` | |
| `Semaphore.acquire()` | |
| `CountDownLatch.await()` | |

### 中断的正确使用模式

**模式 1：循环中检查**
```java
while (!Thread.currentThread().isInterrupted()) {
    // 业务逻辑
}
```

**模式 2：捕获异常后恢复中断**
```java
try {
    queue.take();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();  // 恢复中断信号
    // 然后退出或做清理
}
```

**模式 3：Future.cancel(true)**
```java
Future<?> future = executor.submit(task);
future.cancel(true);  // true = 中断执行中的线程
```

### 回到 ThreadPoolExecutor：中断的完整链路

```
shutdown()
    │
    ├─ advanceRunState(SHUTDOWN)
    │
    └─ interruptIdleWorkers()
          │
          └─ 遍历 workers
                │
                ├─ w.tryLock() 成功 (state=0, 空闲)
                │     → t.interrupt()
                │     → 线程从 take()/poll() 中被唤醒
                │     → catch(InterruptedException) 重置 timedOut
                │     → 重新循环 → 检查 runState >= SHUTDOWN
                │     → return null → runWorker 退出 → 线程死亡
                │
                └─ w.tryLock() 失败 (state=1, 忙碌 或 state=-1, 未启动)
                      → 跳过，不打扰

shutdownNow()
    │
    ├─ advanceRunState(STOP)
    │
    ├─ interruptWorkers()
    │     │
    │     └─ 遍历 workers → interruptIfStarted()
    │           │
    │           ├─ state >= 0 → t.interrupt()（包括正在执行任务的！）
    │           └─ state == -1 → 跳过（还没启动）
    │
    └─ drainQueue() 清空队列

runWorker() 中
    │
    └─ 每次执行任务前检查：
          if (runState >= STOP || (Thread.interrupted() && runState >= STOP))
              → wt.interrupt()  ← 确保 STOP 状态下的线程最终被中断

getTask() 中
    │
    └─ catch (InterruptedException)
          → 不退出！重置 timedOut，重新循环检查 runState
          → 如果线程池已关闭 → 下次循环头部 return null → 线程死亡
          → 如果线程池还在跑 → 继续 take/poll 等任务
```

**一句话总结：Java 的中断是"协作式"的——我告诉你该停了，但停不停由你自己决定。线程池通过 Worker 的 AQS state 来判断"能不能中断"，只中断空闲线程，保护正在执行任务的线程。**

---

## 十三、Worker 线程是如何死亡的？

### 死亡的唯一路径

```
getTask() 返回 null
    │
    ▼
runWorker() 退出 while 循环
    │
    ▼
processWorkerExit(w, completedAbruptly)  ← 清理 + 可能补线程
    │
    ▼
run() 方法结束 → 线程自然消亡
```

**线程不是被"杀死"的，而是 `getTask()` 返回 null 后自己退出循环，线程自然结束。**

### getTask() 返回 null 的 4 种场景

```java
private Runnable getTask() {
    boolean timedOut = false;

    for (;;) {
        int c = ctl.get();

        // ====== 场景 1/2：线程池正在关闭 ======
        if (runStateOf(c) >= SHUTDOWN && (runStateOf(c) >= STOP || workQueue.isEmpty())) {
            decrementWorkerCount();
            return null;  // ← 死亡
        }

        int wc = workerCountOf(c);
        boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

        // ====== 场景 3：线程数超限 ======
        // ====== 场景 4：非核心线程超时 ======
        if ((wc > maximumPoolSize || (timed && timedOut))
            && (wc > 1 || workQueue.isEmpty())) {
            if (compareAndDecrementWorkerCount(c))
                return null;  // ← 死亡
            continue;
        }

        try {
            Runnable r = timed ?
                workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                workQueue.take();
            if (r != null) return r;
            timedOut = true;  // poll 超时，下次循环触发场景 4
        } catch (InterruptedException retry) {
            timedOut = false;
        }
    }
}
```

| # | 触发条件 | 怎么死的 |
|---|---------|--------|
| **1** | `shutdown()` + 队列空了 | 线程被中断唤醒 → 重新循环 → 检查到 SHUTDOWN + 队列空 → return null |
| **2** | `shutdownNow()` | 状态变 STOP → 中断所有线程 → 重新循环 → 检查到 STOP → return null |
| **3** | 线程数 > maximumPoolSize | `setMaximumPoolSize()` 调小了 → 下次循环检查到超限 → return null |
| **4** | 非核心线程超时 | `poll(keepAliveTime)` 超时 → `timedOut=true` → 下次循环 → return null |

### 异常死亡 vs 正常死亡

```java
boolean completedAbruptly = true;  // 默认假设异常退出
try {
    while (task != null || (task = getTask()) != null) {
        // ... 执行任务
    }
    completedAbruptly = false;  // ← 正常退出循环，改为 false
} finally {
    processWorkerExit(w, completedAbruptly);
}
```

| | 正常死亡 | 异常死亡 |
|---|---|---|
| 触发 | `getTask()` 返回 null | `task.run()` 抛出未捕获异常 |
| `completedAbruptly` | false | true |
| `decrementWorkerCount` | 已在 getTask() 中减过 | processWorkerExit 中补减 |
| 是否补线程 | 看线程数是否够 | **一定补**（除非线程池已停） |

### processWorkerExit()：善后处理

```java
private void processWorkerExit(Worker w, boolean completedAbruptly) {
    // 1. 异常退出 → 补减 workerCount（正常退出已在 getTask 中减过）
    if (completedAbruptly)
        decrementWorkerCount();

    // 2. 加锁清理
    mainLock.lock();
    try {
        completedTaskCount += w.completedTasks;  // 累计完成数
        workers.remove(w);                         // 从集合中移除
    } finally {
        mainLock.unlock();
    }

    // 3. 尝试让线程池进入 TERMINATED 状态
    tryTerminate();

    // 4. 判断是否需要补一个替代线程
    if (runStateLessThan(c, STOP)) {
        if (!completedAbruptly) {
            // 正常退出：看线程数够不够
            int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
            if (workerCountOf(c) >= min) return; // 够了，不补
        }
        // 异常退出：一定补
        addWorker(null, false);
    }
}
```

### 完整死亡时间线

```
线程活着：
  getTask() → take()/poll() 阻塞等任务 → 拿到任务 → 执行 → 循环

线程怎么死：

  ① shutdown() → 中断空闲线程 → take() 抛 InterruptedException
     → catch 后重新循环 → 检查到 SHUTDOWN + 队列空 → return null → 死

  ② 非核心线程 poll 超时 → timedOut=true
     → 下次循环检查 → wc > core && timedOut → return null → 死

  ③ task.run() 抛异常 → while 循环异常退出
     → completedAbruptly=true → processWorkerExit 补一个替代线程

  ④ setMaximumPoolSize(调小) → 下次循环 wc > max → return null → 死
```

**一句话总结：核心线程用 `take()` 永远不超时所以不会死，非核心线程用 `poll(timeout)` 超时就死。shutdown 通过中断唤醒阻塞在队列上的线程，让它们重新检查状态后自行退出。**

---

## 十四、如何判断是核心线程？

**Worker 身上没有任何标记说自己是"核心"还是"非核心"。** 区分完全是在 `getTask()` 里动态判断的：

```java
boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

Runnable r = timed ?
    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :  // "非核心"行为
    workQueue.take();                                        // "核心"行为
```

**判断逻辑就一行：**

```
当前线程数 > corePoolSize  →  timed=true  →  poll(超时)  →  "非核心"行为
当前线程数 <= corePoolSize →  timed=false →  take(永久阻塞) →  "核心"行为
```

**"核心"和"非核心"不是线程的固有属性，而是它在那一刻的行为：**

| 时刻 | 线程数 vs corePoolSize | 行为 | 身份 |
|------|----------------------|------|------|
| T1 | 3个线程，core=5 | `take()` | "核心"（因为 3 < 5） |
| T2 | 新增3个，变成6个，core=5 | `poll(timeout)` | "非核心"（因为 6 > 5） |
| T3 | 死了2个，变回4个，core=5 | `take()` | 又变回"核心" |

**同一个线程，可能一会儿是"核心"行为，一会儿是"非核心"行为。**

举个例子（corePoolSize=2, maximumPoolSize=5）：

```
初始：0 个线程

任务1 进来 → 线程数(0) < core(2) → 创建线程A → A 调 take() → "核心"
任务2 进来 → 线程数(1) < core(2) → 创建线程B → B 调 take() → "核心"
任务3 进来 → 线程数(2) = core(2) → 入队列
...队列满了...
任务N 进来 → 创建线程C → wc(3) > core(2) → C 调 poll(timeout) → "非核心"

如果 C 超时死了，线程数变回 2，A 和 B 继续 take()，依然是"核心"。
```

**一句话：不存在"核心线程"这个实体，只存在"核心行为"——当前线程数 <= corePoolSize 时调 `take()`，> 时调 `poll(timeout)`。**
