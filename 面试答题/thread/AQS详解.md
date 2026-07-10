# AQS（AbstractQueuedSynchronizer）详解

> AQS 是 Java 并发包（JUC）的核心框架，ReentrantLock、CountDownLatch、Semaphore、ReentrantReadWriteLock 都基于它实现。理解 AQS，就理解了整个 JUC 锁体系。

---

## 先看这个类比，再读后面的源码

**把 AQS 想象成银行叫号系统：**

```
银行（AQS）的运作方式：

  state（柜台状态）：
    0 = 柜台空闲，可以直接办理
    1 = 有人正在办理
    N = 有人在办理，且里面的人办了 N 次业务（重入）

  CLH 队列（等候区）：
    取号排队的人，按顺序坐在等候区
    每个人（Node）手里拿着号码，知道前面是谁（prev）、后面是谁（next）

  park/unpark（睡觉/叫号）：
    轮不到你 → 在等候区睡觉（park）
    前面的人办完 → 叫你的号（unpark）

  tryAcquire（直接去柜台试试）：
    非公平锁 = 刚到就直接冲柜台，没人就办（插队）
    公平锁 = 先看看等候区有没有人，有就老实排队

  Condition（VIP 等候区）：
    普通等候区 = 等锁的线程
    VIP 等候区 = 等特定条件的线程（比如"账户有钱了再通知我"）
```

**读源码的正确姿势**：

```
❌ 错误姿势：逐行读 AQS 源码，试图理解每个 if/else
✅ 正确姿势：

  第一遍：只看主干流程（本文的流程图和"人话版"注释）
  第二遍：对着代码看核心方法（tryAcquire → addWaiter → acquireQueued）
  第三遍：精读 shouldParkAfterFailedAcquire（最难但最精妙的方法）

  记住：AQS 源码里大量的 CAS + 自旋，本质上都是在做同一件事：
  "在多线程环境下，安全地修改共享变量"
  如果你理解了 CAS（Compare And Swap），AQS 的代码就不难了。
```

---

## 一、AQS 是什么

### 1.1 一句话定义

AQS = **用户层面的 futex 实现**

```
futex（Linux 内核）：
  内核只管 park/unpark（挂起/唤醒）
  排队逻辑由用户态管理

AQS（Java 用户层）：
  就是这个"用户态排队逻辑"的框架
  JVM 自己管理 CLH 队列，内核只负责 park/unpark
```

### 1.2 AQS 和 Monitor 的对比

```
Monitor（synchronized）：
  → JVM 内置，内核管理等待队列（_EntryList, _WaitSet）
  → 不够灵活：只有一个条件队列，不可中断，不可超时

AQS（ReentrantLock）：
  → 用户层实现，JVM 自己管理等待队列（CLH 变体队列）
  → 更灵活：多个 Condition、可中断、可超时、公平/非公平
```

### 1.3 AQS 帮你做的事 vs 你只需要做的事

```
AQS 帮你做的（不需要写）：
  ├── CLH 队列管理（入队/出队）
  ├── park/unpark（挂起/唤醒线程）
  ├── 自旋重试
  ├── 中断处理
  └── 超时处理

你只需要做的（子类实现）：
  ├── tryAcquire：怎么获取锁
  ├── tryRelease：怎么释放锁
  └── isHeldExclusively：当前线程是否持有锁
```

---

## 二、核心数据结构

> 只需要记住两样东西：**一个变量（state）** + **一条队列（CLH）**。
> 所有的锁逻辑都围绕这两样东西展开。

### 2.1 state 变量

**人话版**：state 就是一个"计数器"，用 volatile 修饰保证所有线程都能看到最新值。

```java
// AQS 的核心字段，就这一个变量
private volatile int state;
// volatile 保证可见性（任何线程改了，其他线程立刻能看到）
```

```
独占模式（ReentrantLock）：
  state = 0     → 未锁定
  state = 1     → 被某个线程锁定
  state = 2     → 同一线程重入 2 次
  state = N     → 同一线程重入 N 次

共享模式（Semaphore / CountDownLatch）：
  state = N     → 还有 N 个许可可用
  state = 0     → 许可用完，后续线程等待
```

### 2.2 CLH 变体队列（双向链表）

**人话版**：就是一个"等候区"，排不上队的线程在这里等着。用双向链表是因为有时候要从后往前找（比如取消、唤醒后继时）。

```
原版 CLH（单向链表）：
  ┌────┐    ┌────┐    ┌────┐
  │head│ →  │ A  │ →  │ B  │ → tail
  └────┘    └────┘    └────┘
  只看前驱节点的状态来判断是否该自旋

AQS 的 CLH 变体（双向链表）：
  ┌────┐    ┌────┐    ┌────┐    ┌────┐
  │head│ ↔  │ A  │ ↔  │ B  │ ↔  │tail│
  └────┘    └────┘    └────┘    └────┘

  双向链表的优势：
  → 支持从 tail 向前遍历（unparkSuccessor 找不到后继时）
  → 支持取消节点时重新连接前后节点
  → prev 是强保证（先设），next 是弱保证（后设）
```

### 2.3 Node 节点

**人话版**：每个排队的线程都被包装成一个 Node 节点，节点里记录了"我是谁"和"我前面/后面是谁"。

```java
static final class Node {
    volatile int waitStatus;      // 我的状态（该不该被唤醒、是否取消了等）
    volatile Node prev;           // 我前面是谁
    volatile Node next;           // 我后面是谁
    volatile Thread thread;       // 我是哪个线程
    Node nextWaiter;              // 条件队列用（VIP 等候区专用链接）
}
```

**waitStatus 的 5 种状态**（不用全背，记住前 3 个就够用）：

| 值 | 名称 | 人话版 | 类比 |
|----|------|--------|------|
| 0 | 初始 | 刚坐下来排队，啥也没说 | 刚取号 |
| -1 | SIGNAL | "前面的人办完记得叫我" | 跟柜员说"到我了喊一声" |
| 1 | CANCELLED | 不等了，走了 | 放弃排队离开银行 |
| -2 | CONDITION | 在 VIP 等候区等特定条件 | 等"账户有钱了"再排普通队 |
| -3 | PROPAGATE | 共享模式下唤醒要传播 | （高级用法，初学可忽略） |

> **最关键的是 SIGNAL**：当一个节点要 park（睡觉）之前，会先把前驱节点设为 SIGNAL，意思是"你释放锁的时候记得叫醒我"。

---

## 三、acquire 流程（获取锁）

> 获取锁的整体流程其实就 4 步：
> **试一试 → 试不上就排队 → 排队时边等边试 → 被中断了就记下来**

### 3.1 入口：acquire()

**人话版**：这是 AQS 的模板方法，所有获取锁的操作都走这个方法。你不需要写它，只需要写 tryAcquire。

```java
// AQS 的 acquire（模板方法，不可修改）
public final void acquire(int arg) {
    if (!tryAcquire(arg) &&                    // ① 子类实现：尝试获取锁
        acquireQueued(                          // ③ 入队后的自旋+park循环
            addWaiter(Node.EXCLUSIVE), arg      // ② 入队
        ))
        selfInterrupt();                        // ④ 等待期间被中断，补上中断标志
}
```

```
用银行叫号来理解 acquire() 的 4 步：

  ① tryAcquire(arg)          → 直接去柜台试试，没人就办（银行类比你写的）
     成功：办完了，走人
     失败：去取号

  ② addWaiter(Node.EXCLUSIVE) → 取号，坐到等候区（AQS 帮你做）

  ③ acquireQueued(node, arg)  → 在等候区边睡边等（AQS 帮你做）
     快轮到你时，醒来再试一次
     还不行，继续睡

  ④ selfInterrupt()          → 等的时候被人拍了一下（中断），记住这事（AQS 帮你做）
```

### 3.2 ReentrantLock 的 lock() 入口

**人话版**：非公平锁上来就抢，抢到就赢了，抢不到再排队。公平锁老实排队。

```java
// 非公平锁
final void lock() {
    // 上来就 CAS 尝试获取（插队！这就是"非公平"）
    if (compareAndSetState(0, 1)) {
        setExclusiveOwnerThread(Thread.currentThread());
    } else {
        acquire(1);  // CAS 失败 → 走 AQS 标准流程
    }
}

// 公平锁
final void lock() {
    acquire(1);  // 不插队，直接排队
}
```

### 3.3 tryAcquire()（子类实现）

**人话版**：这个方法由 ReentrantLock 实现，核心逻辑就是 3 个场景：锁空闲就抢、是自己的就重入、别人的就失败。

```java
// 非公平锁的 tryAcquire（人话版：抢锁的 3 种情况）
final boolean nonfairTryAcquire(int acquires) {
    Thread current = Thread.currentThread();
    int c = getState();                       // 读取 state（volatile 读）

    if (c == 0) {
        // 场景 1：锁空闲 → CAS 尝试获取
        if (compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    else if (current == getExclusiveOwnerThread()) {
        // 场景 2：重入 → state + 1
        int nextc = c + acquires;
        setState(nextc);  // 不需要 CAS（只有持有者能改 state）
        return true;
    }

    // 场景 3：被其他线程持有 → 返回 false → 排队
    return false;
}
```

```
公平锁的唯一区别：多了 hasQueuedPredecessors() 检查

if (!hasQueuedPredecessors() && compareAndSetState(0, acquires))
     ↑ 队列里有没有人排在我前面？
       有 → 不插队，返回 false
       没有 → CAS 尝试获取
```

### 3.4 addWaiter()（入队）

**人话版**：把当前线程包装成一个节点，加到等候区末尾。先试着快速插入（CAS），失败再走慢速路径。

```java
// addWaiter：取号坐到等候区末尾
private Node addWaiter(Node mode) {
    Node node = new Node(Thread.currentThread(), mode);

    // 快速路径：一次 CAS 完成入队（无竞争时极快）
    Node pred = tail;
    if (pred != null) {
        node.prev = pred;
        if (compareAndSetTail(pred, node)) {
            pred.next = node;
            return node;
        }
    }

    // 慢速路径：竞争或队列为空时走 enq
    enq(node);
    return node;
}
```

**入队操作的三步不是原子的（这是理解 AQS 的关键细节）**：

```
为什么 prev 比 next 可靠？

node.prev = pred;                    // ① 先设 prev（普通写，一定成功）
compareAndSetTail(pred, node);       // ② CAS 设 tail
pred.next = node;                    // ③ 后设 next（可能延迟）

想象两个人 A、B 同时往等候区末尾加人：
  A 先设了 prev，CAS 成功成了新 tail
  但 A 还没来得及设 next，B 就插进来了

  结果：B 知道前面是 A（通过 prev），但 A 的 next 可能还没指向 B
  → 所以从后往前找（prev）一定找得到
  → 从前往后找（next）可能找不到最后一个
  → 这就是为什么唤醒后继时要从 tail 往前找
```

### 3.5 enq()（完整入队）

**人话版**：addWaiter 的快速路径失败时，用无限循环保证一定能入队。第一次可能要先创建哨兵节点。

```java
private Node enq(final Node node) {
    for (;;) {                          // 无限循环，保证一定成功
        Node t = tail;

        if (t == null) {
            // 队列为空 → 创建 head 哨兵节点
            if (compareAndSetHead(new Node()))
                tail = head;
            // 继续循环！这轮不入队，下轮才入
        } else {
            // 队列非空 → CAS 入队
            node.prev = t;
            if (compareAndSetTail(t, node)) {
                t.next = node;
                return t;              // 唯一出口
            }
            // CAS 失败 → 自旋重试
        }
    }
}
```

**哨兵节点的作用**：

```
哨兵节点就是一个"假人"，永远占在 head 位置，简化边界处理：

没有哨兵：head = tail = 第一个真实节点
  → 入队/出队需要特殊处理"只有一个节点"的边界

有哨兵：head = 空节点，后面才是真实等待的线程
  → 逻辑统一，不需要特殊处理边界
  → head 永远代表"当前持有锁的节点"（或空）
```

### 3.6 acquireQueued()（自旋 + park）

**人话版**：这是排队的核心逻辑——"醒了就试试，试不上就继续睡"。只有前驱是 head 时才有资格尝试获取锁（避免所有人同时抢）。

```java
// acquireQueued：在等候区边睡边等的核心循环
final boolean acquireQueued(final Node node, int arg) {
    boolean failed = true;
    try {
        boolean interrupted = false;

        for (;;) {
            final Node p = node.predecessor();

            // 前驱是 head → 有资格尝试获取锁
            if (p == head && tryAcquire(arg)) {
                setHead(node);         // 获取成功：设为新 head
                p.next = null;         // 断开旧 head（帮助 GC）
                failed = false;
                return interrupted;    // 返回（是否被中断过）
            }

            // 获取失败 → 判断是否该 park
            if (shouldParkAfterFailedAcquire(p, node) &&
                parkAndCheckInterrupt())
                interrupted = true;
        }
    } finally {
        if (failed)
            cancelAcquire(node);       // 异常时取消节点
    }
}
```

**为什么只有 `p == head` 才能尝试获取锁？**

```
避免所有等待线程同时 CAS 竞争（惊群效应）
只有"排在最前面的"才能竞争
获取成功后新节点成为 head，下一个才有资格
形成 FIFO 顺序
```

### 3.7 shouldParkAfterFailedAcquire()（最精妙的设计）

> 这是 AQS 里最难理解的方法，但想明白了会觉得很巧妙。
> 核心问题：**怎么确保"park 之后一定有人来 unpark 我"？**

**人话版**：这个方法只做一件事——检查前驱节点的状态，决定"我现在能不能安心睡觉"。

```
三种情况：

  前驱是 SIGNAL（-1）→ "前面说了会叫我" → 安心 park ✅
  前驱已取消（>0）→ "前面的人走了" → 跳过他们，找更前面的人
  前驱是 0/PROPAGATE → 还没约好 → 跟前驱约一下，再自旋一次检查
```
```java
// shouldParkAfterFailedAcquire：决定"我现在能不能安心睡觉"
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;

    if (ws == Node.SIGNAL)
        return true;          // 前驱说"我释放时叫你" → 安心 park

    if (ws > 0) {
        // 前驱已取消 → 跳过所有取消节点
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
        return false;         // 不 park，重新检查新的前驱
    }

    // 前驱状态是 0 或 PROPAGATE
    compareAndSetWaitStatus(pred, ws, Node.SIGNAL);  // 设为 SIGNAL
    return false;             // 不 park，再自旋一次
}
```

**为什么 park 之前要先自旋一次？（用时间线看懂不 park 的原因）**

```
场景：线程 B 想 park，前驱是 A

时间线：
  T1: B 第一次自旋 → 前驱 A 的状态是 0（还没约好）
      B 把 A 设为 SIGNAL → 返回 false（不 park，再自旋一次）

  T2: B 第二次自旋 → 前驱 A 的状态是 SIGNAL（约好了）
      → 返回 true → B 安心 park

如果 A 在 T1 和 T2 之间释放了锁：
  T1.5: A 释放锁 → 看到 A 自己是 SIGNAL → unpark(B)
  T2:   B 的 park 会立刻返回（因为 unpark 已经给了 permit）
  → 不会死锁 ✅

如果 B 直接 park 不设 SIGNAL：
  A 释放锁时不知道后面有人在等 → 不会 unpark(B)
  → B 永远睡着 → 死锁 ❌

结论：必须先约好（SIGNAL），再 park。多自旋一次就是为了这个约定。
```

### 3.8 parkAndCheckInterrupt()

```java
private final boolean parkAndCheckInterrupt() {
    LockSupport.park(this);              // futex 挂起（你已经理解）
    return Thread.interrupted();         // 被唤醒后检查是否被中断
}
```

---

## 四、release 流程（释放锁）

> 释放锁比获取锁简单得多，就两步：
> **释放锁 → 叫醒下一个**

### 4.1 入口：release()

**人话版**：办完业务了，释放柜台，然后叫下一个人的号。

```java
// release：释放锁，然后叫醒下一个等待的人
public final boolean release(int arg) {
    if (tryRelease(arg)) {              // ① 子类实现：尝试释放
        Node h = head;
        if (h != null && h.waitStatus != 0)
            unparkSuccessor(h);         // ② 唤醒后继节点
        return true;
    }
    return false;
}
```

### 4.2 tryRelease()（子类实现）

**人话版**：每次 unlock 就把 state 减 1，减到 0 才是真正释放（支持重入）。

```java
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;       // state - 1

    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();

    boolean free = false;
    if (c == 0) {
        free = true;
        setExclusiveOwnerThread(null);   // 清除 owner
    }
    setState(c);                         // 设置新 state
    return free;                         // c==0 → 完全释放
}
```

```
重入释放过程：
  state = 3 → unlock → state = 2 → 返回 false（不完全释放）
  state = 2 → unlock → state = 1 → 返回 false
  state = 1 → unlock → state = 0 → 返回 true（完全释放）→ unparkSuccessor
```

### 4.3 unparkSuccessor()（唤醒后继）

**人话版**：叫醒下一个等待的人。注意要从后往前找（因为 next 可能还没设好）。

```java
private void unparkSuccessor(Node node) {
    // 重置 head 的 waitStatus 为 0
    int ws = node.waitStatus;
    if (ws < 0)
        compareAndSetWaitStatus(node, ws, 0);

    // 找后继节点
    Node s = node.next;

    // 后继为空或已取消 → 从 tail 向前找第一个非取消的
    if (s == null || s.waitStatus > 0) {
        s = null;
        for (Node t = tail; t != null && t != node; t = t.prev)
            if (t.waitStatus <= 0)
                s = t;
    }

    // 唤醒后继线程
    if (s != null)
        LockSupport.unpark(s.thread);   // futex 唤醒
}
```

**为什么从 tail 向前找？**

```
因为 next 是弱保证（后设的）：
  入队时 node.prev = pred 先设
  入队时 pred.next = node 后设

  如果在两步之间被打断：
    tail 已经是新节点了
    但前一个节点的 next 还没指向新节点
    → 从 head 向后遍历可能找不到最后一个节点
    → 从 tail 向前遍历一定能找到（prev 是先设的）
```

---

## 五、完整流程串起来

> 用银行叫号的完整场景，把 acquire 和 release 串起来：

```
线程 A（获取锁）：
  lock() → CAS(0,1) 成功 → 获得锁 ✅

线程 B（获取锁失败）：
  lock() → CAS 失败
  → acquire(1)
    → tryAcquire() → 失败
    → addWaiter() → 快速路径 CAS 入队：head ↔ [B] ↔ tail
    → acquireQueued()
      → 第 1 次自旋：前驱是 head，tryAcquire 失败
      → shouldParkAfterFailedAcquire：把 head 设为 SIGNAL，返回 false
      → 第 2 次自旋：前驱是 head，tryAcquire 失败
      → shouldParkAfterFailedAcquire：前驱是 SIGNAL，返回 true
      → parkAndCheckInterrupt() → park(B) 挂起

线程 A（释放锁）：
  unlock() → release(1)
    → tryRelease() → state=0，owner=null，返回 true
    → unparkSuccessor(head) → unpark(B)

线程 B（被唤醒）：
  park 返回 → 继续自旋
    → 前驱是 head，tryAcquire() → CAS(0,1) 成功
    → setHead(B) → B 成为新 head，出队
    → 获得锁 ✅
```

---

## 六、Condition 条件队列

> Condition 可以理解为"VIP 等候区"。
> 普通 CLH 队列是"等锁的"，Condition 队列是"等特定条件的"。
> 比如生产者-消费者场景：notFull 条件、notEmpty 条件各一个等候区。

### 6.1 Condition vs synchronized 的 wait/notify

```
synchronized（Monitor）：
  只有一个 _WaitSet
  notify() 只能随机唤醒一个
  无法区分线程等待的是什么条件

ReentrantLock + Condition（AQS）：
  可以创建多个 Condition（多个条件等待队列）
  signal() 精确唤醒特定条件上的线程
  例如：notFull 条件、notEmpty 条件
```

### 6.2 ConditionObject 的核心方法

**人话版**：
- await() = "我要等的条件还没满足，先去 VIP 等候区睡觉，条件满足了再叫我"
- signal() = "条件满足了，把 VIP 等候区的第一个人转回普通等候区"

```java
// await()：释放锁 → 入条件队列 → park
public final void await() throws InterruptedException {
    Node node = addConditionWaiter();     // 加入条件队列
    int savedState = fullyRelease(node);  // 完全释放锁（state 归零）
    int interruptMode = 0;
    while (!isOnSyncQueue(node)) {        // 不在 CLH 队列中
        LockSupport.park(this);           // 挂起
        // 被 signal 后会转移到 CLH 队列，退出循环
    }
    // 重新竞争锁
    if (acquireQueued(node, savedState))
        interruptMode = THROW_IE;
}

// signal()：从条件队列取出 → 转移到 CLH 队列
public final void signal() {
    if (!isHeldExclusively())             // 必须持有锁才能 signal
        throw new IllegalMonitorStateException();
    Node first = firstWaiter;
    if (first != null)
        doSignal(first);                  // 转移到 CLH 队列
}
```

### 6.3 自定义锁必须 override isHeldExclusively()

**人话版**：使用 Condition 前必须证明"我确实持有锁"，否则直接抛异常。

```java
// ConditionObject.await() 内部会调用 isHeldExclusively() 检查
// 不 override → await() 直接抛 IllegalMonitorStateException

@Override
protected boolean isHeldExclusively() {
    return getExclusiveOwnerThread() == Thread.currentThread();
}
```

### 6.4 await/signal 流程

```
用银行叫号来理解 Condition：

线程 A（等待条件）：
  lock() → 获取锁（办业务）
  condition.await()
    → 释放锁（离开柜台）
    → 加入 VIP 等候区（不是普通等候区）
    → park(A) 挂起（睡觉等条件满足）

线程 B（通知条件）：
  lock() → 获取锁（办业务）
  修改条件（比如“账户有钱了”）
  condition.signal()
    → 从 VIP 等候区把 A 取出来
    → 转到普通等候区（CLH 队列）
  unlock() → 释放锁（离开柜台）
    → unparkSuccessor → unpark(A)（叫 A 的号）

线程 A（被唤醒）：
  park 返回（醒了）
  → 发现自己在普通等候区了
  → acquireQueued → 重新排队竞争锁
  → 获取成功 → 从 await() 返回（继续办业务）
```

---

## 七、公平锁 vs 非公平锁

### 7.1 区别

```
非公平锁（默认）：
  lock() → 先 CAS 插队 → 失败才排队
  tryAcquire 不检查队列

公平锁：
  lock() → 直接排队
  tryAcquire 先检查 hasQueuedPredecessors()

hasQueuedPredecessors()：
  → 队列中有没有人排在我前面？
  → 有 → 不插队，返回 false → 排队
  → 没有 → CAS 尝试获取
```

### 7.2 对比

| 维度 | 非公平锁 | 公平锁 |
|------|---------|--------|
| 性能 | 更高（减少线程切换） | 稍低（每次都要排队） |
| 公平性 | 可能饿死等待很久的线程 | 先来先得，不会饿死 |
| 适用场景 | 大部分场景 | 需要严格公平时 |

---

## 八、基于 AQS 的 JUC 工具

### 8.1 ReentrantLock

```
模式：独占
state：0=未锁定，>0=重入次数
tryAcquire：CAS(0,1) + 重入判断
tryRelease：state-1，==0 时释放
```

### 8.2 CountDownLatch

```
模式：共享
state：count（倒计数）
await()：acquireShared（state>0 则等待）
countDown()：releaseShared（state-1，==0 时唤醒所有等待线程）
一次性使用，不能重置
```

### 8.3 Semaphore

```
模式：共享
state：permits（许可数）
acquire()：acquireShared（state-1）
release()：releaseShared（state+1）
用于控制并发访问的线程数量（限流）
```

### 8.4 ReentrantReadWriteLock

```
模式：独占（写锁）+ 共享（读锁）
state 拆分：
  高 16 位：读锁持有次数
  低 16 位：写锁持有次数

规则：
  读读共享：多个线程可以同时持有读锁
  读写互斥：持有读锁时不能获取写锁
  写写互斥：同一时刻只有一个线程持有写锁

锁降级：
  持有写锁 → 获取读锁 → 释放写锁
  → 从独占降级为共享
```

---

## 九、自定义锁示例

> 看完上面的原理，自己写一个锁其实很简单——只需要实现 3 个方法。
> 下面是一个最简单的独占锁（不支持重入）：

```java
public class MyLock implements Lock {

    private static class Sync extends AbstractQueuedSynchronizer {

        // 获取锁：CAS(0, 1)
        @Override
        protected boolean tryAcquire(int arg) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        // 释放锁：state 归零
        @Override
        protected boolean tryRelease(int arg) {
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        // Condition 需要
        @Override
        protected boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        // 暴露 Condition
        Condition newCondition() {
            return new ConditionObject();
        }
    }

    private final Sync sync = new Sync();

    @Override public void lock()                  { sync.acquire(1); }
    @Override public void unlock()                { sync.release(1); }
    @Override public boolean tryLock()            { return sync.tryAcquire(1); }
    @Override public Condition newCondition()     { return sync.newCondition(); }
    @Override public void lockInterruptibly()     throws InterruptedException { sync.acquireInterruptibly(1); }
    @Override public boolean tryLock(long t, TimeUnit u) throws InterruptedException { return sync.tryAcquireNanos(1, u.toNanos(t)); }
}
```

**和 ReentrantLock 的区别**：MyLock 不支持重入。要支持重入，只需在 tryAcquire 中加上重入判断：

```java
if (current == getExclusiveOwnerThread()) {
    setState(getState() + 1);
    return true;
}
```

---

## 总结

```
AQS 的核心 = state + CLH 队列 + acquire/release 模板

  state：
    volatile int，表示锁的状态（未锁定/已锁定/重入次数/许可数）

  CLH 变体队列：
    双向链表，prev 强保证 + next 弱保证
    哨兵节点简化边界处理

  acquire 流程：
    tryAcquire → 失败 → addWaiter 入队 → acquireQueued 自旋+park

  release 流程：
    tryRelease → 成功 → unparkSuccessor 唤醒后继

  子类只需实现：
    tryAcquire / tryRelease / isHeldExclusively
    其余全部由 AQS 框架完成
```

> **面试话术**：AQS 是 JUC 的核心框架，通过 volatile state 变量 + CLH 变体双向队列 + acquire/release 模板方法，为各种锁和同步器提供统一的基础设施。子类只需实现 tryAcquire（如何获取锁）和 tryRelease（如何释放锁），AQS 负责所有排队、park/unpark、自旋重试、中断处理等复杂机制。ReentrantLock 是独占模式的实现（state 表示重入次数），CountDownLatch/Semaphore 是共享模式的实现（state 表示倒计数/许可数），ReentrantReadWriteLock 将 state 拆分为高 16 位读锁和低 16 位写锁实现读写分离。

---

## 附录：读 AQS 源码的正确姿势

> 觉得 AQS 源码绕是正常的，因为它用了大量的 CAS + 自旋 + volatile，都是多线程下最容易懵的写法。
> 但只要抓住主线，就不会迷失在细节里。

```
第 1 步：先看懂单线程流程（不考虑并发）
  lock() → tryAcquire 成功 → 完事
  unlock() → tryRelease 成功 → 完事
  这是最简单的情况，理解了这个就理解了 80%。

第 2 步：加入并发（抢不上怎么办）
  tryAcquire 失败 → addWaiter 入队 → acquireQueued 自旋+park
  tryRelease 成功 → unparkSuccessor 叫醒下一个

第 3 步：理解“安全”（为什么不会死锁）
  shouldParkAfterFailedAcquire 的 SIGNAL 机制
  → 确保 park 之前一定有人会在释放时 unpark 你
  → 这是整个 AQS 最精妙的设计

第 4 步：理解“边界”（各种特殊处理）
  哨兵节点、取消节点、中断处理、超时处理
  这些是源码里最绕的部分，但都是“边界情况”，不影响主线。

关键心法：
  源码里看到 for(;;) → 就是在重试，直到成功
  源码里看到 CAS → 就是在多线程下安全地改变量
  源码里看到 volatile → 就是保证其他线程能看到最新值
  源码里看到 waitStatus → 就是在协调“什么时候可以睡觉”
```

```
最后记住这句话：
  AQS 源码看着绕，不是因为逻辑复杂，
  而是因为它要在多线程环境下保证安全，所以加了很多“防御性代码”。
  如果去掉并发安全的要求，AQS 的核心逻辑其实只有几十行。
```
