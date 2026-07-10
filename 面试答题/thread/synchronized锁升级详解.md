# synchronized 锁升级：从偏向锁到重量级锁的完整路径

> synchronized 是 Java 最基础的同步机制。JDK 1.6 之前它是一把"大铁锁"——每次加锁都要进入内核态，性能很差。JDK 1.6 引入了锁升级机制，让 synchronized 在不同竞争场景下自动选择最轻量的加锁方式。理解锁升级，就理解了 synchronized 的全部底层原理。

---

## 先看这个类比，再读后面的原理

**把 synchronized 想象成办公室的门锁：**

```
偏向锁 = 你办公室只有你自己用
  → 门上贴了你的名字（偏向线程 ID）
  → 你进出不需要锁门，推门就进
  → 别人来了才发现门有主人 → 撤销偏向

轻量级锁 = 两三个人偶尔用这间办公室
  → 谁要用就在门上贴个"有人"的便签（CAS 操作 Mark Word）
  → 便签贴上 = 拿到锁，撕掉 = 释放锁
  → 两个人同时贴 → 自旋等一会儿
  → 等太久 → 升级为重量级锁

重量级锁 = 很多人在抢这间办公室
  → 门口排起长队（Monitor 的 _EntryList）
  → 用的人不出来，外面的人只能等（BLOCKED 状态）
  → 里面的人出来了，叫下一个（unpark）
```

---

## 一、前置知识：Mark Word 结构

### 1.1 对象头是什么

每个 Java 对象在内存中都有三部分：

```
┌─────────────────────────────────────────┐
│              Java 对象内存布局            │
├─────────────────────────────────────────┤
│  对象头（Header）                        │
│    ├── Mark Word（8 字节，64 位）        │ ← 锁信息都在这里
│    └── 类型指针（4/8 字节）              │ ← 指向 Class 元数据
├─────────────────────────────────────────┤
│  实例数据（Instance Data）               │ ← 对象的字段
├─────────────────────────────────────────┤
│  对齐填充（Padding）                     │ ← 补齐到 8 的倍数
└─────────────────────────────────────────┘
```

**Mark Word 是对象头的核心**，64 位（8 字节）的空间里塞进了锁状态、hashcode、GC 年龄等信息。

### 1.2 Mark Word 的 64 位布局

```
64 位 Mark Word 的 5 种状态：

┌──────────────────────────────────────────────────────┐
│                      64 bit                          │
├──────────────────────────────────────────────────────┤

无锁（初始状态）：
│   unused (25)  │ hashcode (31) │ unused (1) │ age (4) │ 0 │ 01 │
                  ↑ 对象的 identityHashCode              ↑↑
                                                   分代年龄 锁标志

偏向锁：
│ threadId (54)  │ epoch (2)     │ unused (1) │ age (4) │ 1 │ 01 │
   ↑ 偏向线程 ID   ↑ 偏向时间戳                          ↑↑
                                                      偏向标志 锁标志

轻量级锁：
│           指向栈中 Lock Record 的指针 (62)             │ 00 │
                                                         ↑↑
                                                       锁标志

重量级锁：
│           指向 Monitor 对象的指针 (62)                 │ 10 │
                                                         ↑↑
                                                       锁标志

GC 标记（对象被 GC 移动时临时使用）：
│           空 (62)                                     │ 11 │
                                                         ↑↑
                                                       锁标志
```

### 1.3 关键细节：为什么 hashcode 和偏向锁不能共存？

```
无锁状态下：hashcode 占 31 位
偏向锁状态下：threadId 占 54 位，把 hashcode 的位置也占了

→ 两者共用同一段空间，不可能同时存在
→ 这意味着：

  如果对象先算过 hashcode → 不能偏向
    因为偏向需要 54 位存 threadId，但 hashcode 占了 31 位

  如果对象先偏向 → 计算 hashcode 时必须撤销偏向
    因为偏向状态下没有地方存 hashcode

实际影响：
  → 调用了 obj.hashCode() 或 obj.getClass()
  → 偏向锁会被撤销，升级为无锁或轻量级锁
  → 这是偏向锁被撤销的常见原因之一
```

### 1.4 Mark Word 状态转换全景图

```
                    ┌──────────┐
         创建对象 → │  无锁     │ ← 初始状态
                    └────┬─────┘
                         │ 开启偏向
                         ↓
                    ┌──────────┐        hashcode()
                    │  偏向锁   │ ──────────────────→ 无锁/轻量级锁
                    └────┬─────┘        撤销偏向
                         │ 出现竞争（第二个线程）
                         ↓
                    ┌──────────┐        竞争加剧
                    │ 轻量级锁  │ ──────────────────→ 重量级锁
                    └────┬─────┘        自旋失败
                         │ 自旋超过阈值
                         ↓
                    ┌──────────┐
                    │ 重量级锁  │
                    └──────────┘

注意：锁升级是单向的，不能降级（除了 GC 时 STW 会重置偏向锁）
```

---

## 二、偏向锁（Biased Locking）

### 2.1 核心思想

```
大量 synchronized 代码实际上只有一个线程在用：

  StringBuilder sb = new StringBuilder();
  synchronized (sb) {         // 只有一个线程在用 sb
      sb.append("hello");     // 每次加锁都是同一个线程
  }

  → 如果每次都是同一个线程进入，加锁/解锁完全是浪费
  → 偏向锁：直接把锁"偏向"这个线程，以后它来都不用加锁
```

### 2.2 偏向锁的获取过程

```
线程 A 第一次进入 synchronized：

  ① 检查 Mark Word 的锁标志位 → 是 01（无锁或偏向）
  ② 检查偏向标志位 → 是 0（还没偏向任何线程）
  ③ CAS 把 Mark Word 改成偏向状态：
     → threadId = A 的线程 ID
     → 偏向标志 = 1
     → epoch = 当前类的偏向时间戳
  ④ 获取锁成功，进入临界区

线程 A 再次进入 synchronized：

  ① 检查 Mark Word → 是 01（偏向状态）
  ② 检查偏向标志 → 是 1（已偏向）
  ③ 检查 threadId → 就是我自己！
  ④ 直接获取锁，不需要任何 CAS 操作
  → 这就是偏向锁的"零开销"
```

### 2.3 偏向锁的撤销

```
撤销偏向的 4 种触发条件：

  ① 另一个线程尝试获取锁
     → 线程 B 检查 threadId → 不是自己
     → 必须撤销 A 的偏向，升级为轻量级锁

  ② 调用了 obj.hashCode()
     → 需要 31 位存 hashcode，偏向锁没空间了
     → 撤销偏向，变回无锁状态（hashcode 存在 Mark Word 里）

  ③ 调用了 obj.wait() / obj.notify()
     → 这些方法依赖 Monitor（重量级锁的底层结构）
     → 偏向锁没有 Monitor，必须升级到重量级锁

  ④ 批量撤销（Bulk Revoke）
     → JVM 发现某个类的对象被撤销偏向太多次
     → 认为"这个类不适合偏向"
     → 把该类所有对象的偏向锁全部撤销，且以后不再偏向
```

### 2.4 偏向锁撤销的代价（为什么不能乱用）

```
撤销偏向不是免费的，需要：

  ① 暂停所有线程（SafePoint / STW）
     → JVM 必须在一个安全点暂停所有线程
     → 检查被偏向的线程是否还在执行临界区
     → 如果还在执行 → 等它出来才能撤销

  ② 遍历所有线程的栈帧
     → 找到持有这个偏向锁的栈帧
     → 把锁从偏向状态改成轻量级锁或无锁

  → 这就是为什么偏向锁在"多线程竞争频繁"的场景下反而更慢：
    频繁撤销的 STW 开销 > 重量级锁的开销
```

### 2.5 epoch 机制：批量重偏向

```
epoch（偏向时间戳）的作用：

  问题：批量撤销后要重新开启偏向，怎么办？
  方案：不一个个撤销，而是直接改 epoch

  每个类有一个 epoch 值（类级别）
  每个偏向锁对象也有一个 epoch 值（对象级别）

  批量重偏向时：
    → 把类的 epoch 加 1
    → 下次线程获取锁时：
      对象 epoch ≠ 类 epoch → 认为偏向已过期
      → 直接 CAS 把 threadId 改成当前线程（不用撤销！）
    → 对象 epoch == 类 epoch → 偏向有效，正常使用

  → 避免了遍历所有对象逐个撤销的开销
```

---

## 三、轻量级锁（Lightweight Locking）

### 3.1 核心思想

```
偏向锁失效后（有第二个线程来竞争），升级为轻量级锁。

轻量级锁的核心操作：
  → 在对象的 Mark Word 和线程栈帧之间建立一条"锁记录"（Lock Record）
  → 用 CAS 把 Mark Word 替换成指向 Lock Record 的指针
  → CAS 成功 = 拿到锁
  → CAS 失败 = 有竞争，先自旋，自旋失败再膨胀为重量级锁
```

### 3.2 Lock Record 是什么

```
Lock Record 是线程栈帧中的一个结构，存在栈上（不是堆上）：

线程 A 的栈：                     对象 obj：
┌──────────────────┐            ┌──────────────────┐
│ 方法 frame       │            │ Mark Word         │
│   Lock Record    │ ← 指针 ─── │ (存了指针，不是     │
│   ┌────────────┐ │            │  原来的 Mark Word) │
│   │ displaced  │ │            └──────────────────┘
│   │ Mark Word  │ │ ← 保存了原来的 Mark Word
│   │ owner = obj│ │ ← 指向被锁的对象
│   └────────────┘ │
└──────────────────┘
```

**为什么需要 displaced Mark Word（ displaced = 被替换掉的）？**

```
释放锁时要把原来的 Mark Word 还回去：

  加锁：Mark Word → 被替换成指针 → 原 Mark Word 存到 Lock Record
  解锁：从 Lock Record 取回原 Mark Word → CAS 写回对象头

  如果不保存原 Mark Word：
    → 释放锁时不知道原来的 hashcode、age 等信息
    → 对象头数据丢失
```

### 3.3 轻量级锁的加锁过程

```java
Object obj = new Object();
synchronized (obj) {
    // 临界区
}
```

```
字节码层面：
  monitorenter  → 加锁
  monitorexit   → 释放锁

JVM 执行 monitorenter 时的完整流程：

  ① 在当前线程栈帧创建 Lock Record
     → displaced Mark Word = obj 当前的 Mark Word
     → owner = obj

  ② CAS 尝试把 Mark Word 替换成 Lock Record 的指针
     → CAS(obj.MarkWord, 原值, LockRecord地址)
     → 成功：加锁成功，Mark Word 现在是指向 Lock Record 的指针
     → 失败：进入第 ③ 步

  ③ CAS 失败 → 检查 Mark Word 是否已经指向自己的 Lock Record
     → 是：重入（同一个线程再次进入 synchronized）
     → 在栈上创建一个新的 Lock Record（重入记录）
     → 不是：有竞争 → 进入自旋

  ④ 自旋（Adaptive Spinning）
     → 循环执行 CAS 尝试获取锁
     → 自旋次数由 JVM 自适应调整（上一次自旋成功 → 多自旋；失败 → 少自旋）
     → 自旋成功：加锁成功
     → 自旋失败：膨胀为重量级锁
```

### 3.4 轻量级锁的释放过程

```
monitorexit 执行时：

  ① 从栈帧取出 Lock Record
  ② CAS 把 Mark Word 从指针换回原来的 displaced Mark Word
     → CAS(obj.MarkWord, LockRecord地址, displacedMarkWord)
     → 成功：释放锁
     → 失败：说明有其他线程在竞争（锁已经膨胀了）
             → 调用 inflate_and_exit() → 膨胀为重量级锁
```

### 3.5 自适应自旋（Adaptive Spinning）

```
JDK 1.6 引入的优化，核心思想：

  "上次自旋成功了 → 这次也大概率成功 → 多自旋几次"
  "上次自旋失败了 → 这次也大概率失败 → 少自旋几次，早点 park"

实现方式：
  → JVM 为每个锁对象维护一个"自旋成功率"统计
  → 成功率高 → 自旋次数增加（最多几十次）
  → 成功率低 → 自旋次数减少（甚至直接 park）

为什么这样设计：
  → 如果锁持有时间很短（比如只做一个赋值），自旋比 park 快得多
    park 的开销：用户态 → 内核态 → 调度 → 再切回来 ≈ 几微秒
    自旋的开销：空转几次 ≈ 几十纳秒
  → 如果锁持有时间很长，自旋就是浪费 CPU
```

---

## 四、重量级锁（Heavyweight Locking）

### 4.1 什么时候膨胀为重量级锁

```
三种情况会膨胀为重量级锁：

  ① 轻量级锁自旋失败
     → 自旋超过阈值（JVM 自适应判断）
     → 说明竞争激烈或锁持有时间长
     → 膨胀为重量级锁

  ② 多个线程同时 CAS 失败
     → 两个以上线程同时竞争
     → 轻量级锁只能处理"两个线程交替"的场景
     → 多人抢就升级为重量级锁

  ③ 调用了 wait() / notify()
     → 这些方法需要 Monitor 的支持（_WaitSet）
     → 轻量级锁没有 Monitor
     → 直接膨胀为重量级锁
```

### 4.2 膨胀过程

```
轻量级锁 → 重量级锁的膨胀：

  ① 创建 ObjectMonitor 对象（C++ 层面，存在堆中）
  ② 把 Monitor 的指针写入 Mark Word（锁标志位改为 10）
  ③ 把当前持有轻量级锁的线程设为 Monitor 的 owner
  ④ 其他竞争的线程进入 Monitor 的 _EntryList 等待

膨胀前的 Mark Word：
│       指向 Lock Record 的指针 (62)           │ 00 │

膨胀后的 Mark Word：
│       指向 ObjectMonitor 的指针 (62)         │ 10 │
```

### 4.3 ObjectMonitor 的结构

```
ObjectMonitor（HotSpot C++ 实现）：

  ObjectMonitor {
    ObjectWaiter * _EntryList;    // 等待锁的线程队列
    ObjectWaiter * _WaitSet;      // 调用了 wait() 的线程集合
    int            _count;        // 等待线程数
    int            _recursions;   // 重入次数
    Object *       _object;       // 被锁的对象
    Thread *       _owner;        // 当前持有锁的线程
  }

  和 AQS 的区别：
    ObjectMonitor → C++ 实现，JVM 管理
    AQS → Java 实现，用户层管理
    ObjectMonitor → 不可中断、不可超时
    AQS → 可中断、可超时
```

### 4.4 重量级锁的加锁/解锁流程

```
加锁（monitorenter）：
  → 检查 _owner 是否为空
  → 空：CAS 设为自己，_count++
  → 不为空：检查是不是自己（重入 → _recursions++）
  → 不是自己：进入 _EntryList，park 当前线程

解锁（monitorexit）：
  → _recursions > 0 → _recursions--（重入释放）
  → _recursions == 0 → _owner = null
  → 从 _EntryList 取一个线程 → unpark 唤醒它
```

---

## 五、锁升级的完整路径

### 5.1 一张图串起来

```
创建对象
  │
  ↓
无锁（01）
  │ 第一次 synchronized 进入
  ↓
偏向锁（01 + 偏向标志=1）
  │ 第二个线程来竞争
  ↓
轻量级锁（00）← CAS 替换 Mark Word
  │ 自旋失败 / 多人竞争
  ↓
重量级锁（10）← 创建 ObjectMonitor
  │
  ↓
（结束，不能降级）
```

### 5.2 每一步的性能开销

```
操作              开销           说明
─────────────────────────────────────────────
偏向锁获取        ~1ns          读 Mark Word 比较 threadId，纯内存操作
偏向锁撤销        ~100μs        需要 SafePoint 暂停所有线程
轻量级锁获取      ~10ns         CAS 操作 Mark Word（一次 CPU 指令）
轻量级锁自旋      ~10-100ns     循环 CAS，取决于自旋次数
轻量级锁释放      ~10ns         CAS 还原 Mark Word
重量级锁获取      ~1-10μs       进入 Monitor，可能涉及内核态切换
重量级锁等待      线程 BLOCKED   操作系统调度，开销最大
重量级锁释放      ~1μs          唤醒后继，可能涉及内核态
```

---

## 六、锁消除（Lock Elimination）

### 6.1 核心思想

```
JIT 编译器发现"这个锁不可能被多个线程访问"→ 直接把锁删掉

例子：
  public String concat(String a, String b) {
      StringBuffer sb = new StringBuffer();   // sb 是局部变量
      sb.append(a);                            // StringBuffer.append 内部有 synchronized
      sb.append(b);
      return sb.toString();
  }

  sb 是方法内的局部变量，不可能被其他线程访问
  → JIT 编译器通过逃逸分析发现 sb 不会逃逸出方法
  → 直接删除 StringBuffer.append 里的 synchronized
  → 变成了无锁操作
```

### 6.2 逃逸分析（Escape Analysis）

```
逃逸分析判断一个对象是否"逃逸"出当前方法/线程：

  方法逃逸：对象被返回、赋值给全局变量、传给其他线程
    → 逃逸了 → 不能消除锁

  线程逃逸：对象被其他线程访问
    → 逃逸了 → 不能消除锁

  不逃逸：对象只在当前方法内使用
    → 不逃逸 → 可以消除锁 + 可以做栈上分配

JVM 参数：
  -XX:+DoEscapeAnalysis    // 开启逃逸分析（默认开启）
  -XX:+EliminateLocks      // 开启锁消除（默认开启）
```

### 6.3 锁消除的实际效果

```java
// 这段代码里的 synchronized 会被 JIT 消除
public int compute() {
    Vector<Integer> list = new Vector<>();    // Vector 的方法都有 synchronized
    list.add(1);                               // synchronized 被消除
    list.add(2);                               // synchronized 被消除
    list.add(3);                               // synchronized 被消除
    return list.size();                        // synchronized 被消除
}

// list 是局部变量，不会逃逸出方法
// JIT 发现没有任何线程能访问 list → 删除所有 synchronized
// 性能等同于用 ArrayList
```

---

## 七、锁粗化（Lock Coarsening）

### 7.1 核心思想

```
问题：相邻的多个锁操作，每次加锁/解锁太浪费

  sb.append("a");   // synchronized
  sb.append("b");   // synchronized
  sb.append("c");   // synchronized
  → 加了 3 次锁，解了 3 次锁

锁粗化：把多个锁合并成一个大锁

  synchronized(sb) {      // 只加一次锁
      sb.append("a");
      sb.append("b");
      sb.append("c");
  }                        // 只解一次锁
  → 加了 1 次锁，解了 1 次锁
```

### 7.2 锁粗化的触发条件

```
JIT 编译器检测到：
  ① 多个相邻的加锁操作针对同一个对象
  ② 中间没有耗时的操作（如 IO、网络）
  ③ 这些操作在同一段代码中连续出现

→ 把多个锁的临界区合并成一个大的临界区
```

### 7.3 锁粗化 vs 锁消除

```
锁消除：
  → 锁根本不需要 → 删掉
  → 前提：对象不逃逸

锁粗化：
  → 锁需要，但太碎了 → 合并
  → 前提：多个锁操作相邻且对同一对象

两者可以同时生效：
  → 先粗化（合并多个锁）
  → 再消除（如果合并后发现不需要锁，直接删除）
```

---

## 八、JDK 15+ 的变化：偏向锁被废弃

### 8.1 为什么废弃

```
JDK 15（JEP 374）开始废弃偏向锁，JDK 18 彻底移除：

  原因一：维护成本高
    → 偏向锁的撤销需要 SafePoint，代码极其复杂
    → C2 编译器中偏向锁相关的代码占了大量比例
    → 维护成本 > 性能收益

  原因二：现代硬件 CAS 足够快
    → 早期 CPU 的 CAS 很慢，偏向锁有优势
    → 现代 CPU 的 CAS 只需要几十纳秒
    → 偏向锁的"零开销"优势几乎消失了

  原因三：多线程场景越来越多
    → 偏向锁在多线程下反而更慢（频繁撤销）
    → 现代应用几乎都是多线程的
    → 偏向锁的适用场景越来越少
```

### 8.2 对开发者的影响

```
JDK 15+ 之后的锁升级路径变了：

  JDK 8-14：偏向锁 → 轻量级锁 → 重量级锁
  JDK 15+ ：轻量级锁 → 重量级锁（没有偏向锁了）

  影响：
    → 单线程 synchronized 不再有"零开销"优势
    → 但轻量级锁的 CAS 开销也很小（~10ns），影响不大
    → 如果你在用 JDK 17+，不需要考虑偏向锁了

  JVM 参数（JDK 15-17，还可以手动开启）：
    -XX:+UseBiasedLocking    // 开启偏向锁（JDK 15 后默认关闭）
```

---

## 总结

```
synchronized 锁升级的核心 = 根据竞争程度选择最轻量的加锁方式

  无竞争：
    偏向锁（JDK 8-14）→ 读 threadId 比较，~1ns
    轻量级锁（JDK 15+）→ CAS Mark Word，~10ns

  低竞争（偶尔有人抢）：
    轻量级锁 → CAS + 自适应自旋
    自旋成功 → ~10-100ns
    自旋失败 → 膨胀为重量级锁

  高竞争（很多人抢）：
    重量级锁 → Monitor + park/unpark
    ~1-10μs，但能保证公平和正确性

  编译器优化：
    锁消除 → 对象不逃逸 → 直接删锁
    锁粗化 → 多个相邻锁 → 合并成一个大锁
```

> **面试话术**：synchronized 的锁升级是 JDK 1.6 引入的性能优化。对象头的 Mark Word（64 位）存储锁状态信息，锁从无锁状态开始，第一次进入 synchronized 时升级为偏向锁（把线程 ID 写入 Mark Word，后续同一线程进入无需 CAS）。当出现竞争时升级为轻量级锁（在栈帧中创建 Lock Record，用 CAS 替换 Mark Word），配合自适应自旋处理短暂竞争。竞争加剧后膨胀为重量级锁（创建 ObjectMonitor，竞争线程进入 _EntryList 等待，涉及操作系统级别的 park/unpark）。此外，JIT 编译器会通过逃逸分析进行锁消除（删除不可能竞争的锁）和锁粗化（合并相邻的锁操作）。JDK 15 后偏向锁被废弃，锁升级路径简化为轻量级锁 → 重量级锁。
