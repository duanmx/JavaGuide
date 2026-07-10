# Java 并发编程学习路线

> 从硬件底层到JVM实现，再到并发工具的完整学习路径。

---

## 一、你已经掌握的知识体系

### 1.1 操作系统基础 ⭐⭐⭐⭐⭐

```
进程与线程
  ├── 进程：资源分配单位
  ├── 线程：CPU调度单位
  ├── 1:1线程模型（Java线程 = OS线程）
  └── 上下文切换代价（寄存器保存 + 缓存失效）

CPU缓存体系
  ├── L1/L2/L3 多级缓存
  ├── 缓存行（Cache Line）64字节
  └── 伪共享问题（@Contended注解）

缓存一致性
  ├── MESI协议（Modified/Exclusive/Shared/Invalid）
  ├── 总线嗅探 + ACK握手
  └── 总线仲裁（硬件隐式锁）

管程（Monitor）
  ├── ObjectMonitor结构（_owner, _EntryList, _WaitSet）
  ├── MESA模型（Java采用）
  └── wait/notify的底层流程

用户态与内核态
  ├── 系统调用开销
  ├── futex vs mutex
  │   ├── futex：用户态快速路径 + 内核态等待队列
  │   └── mutex：纯内核态实现
  └── LockSupport.park()的实现
```

### 1.2 Java内存模型（JMM） ⭐⭐⭐⭐⭐

```
主内存与工作内存
  ├── 主内存：共享变量
  ├── 工作内存：线程私有副本
  └── 硬件映射：主存 vs L1/L2缓存+Store Buffer

happens-before八条规则
  ├── 程序顺序规则（as-if-serial语义）
  ├── 监视器锁规则（synchronized）
  ├── volatile变量规则
  ├── 线程启动规则（Thread.start）
  ├── 线程终止规则（Thread.join）
  ├── 中断规则（interrupt）
  ├── 终结器规则（finalize）
  └── 传递性（公理，不是定理）

底层实现统一模式
  ├── 写端：StoreStore + StoreLoad屏障 → 刷出到主存
  └── 读端：LoadLoad + LoadStore屏障 → 从主存加载
```

### 1.3 指令重排序 ⭐⭐⭐⭐

```
三种重排序来源
  ├── 编译器重排序（-O2优化）
  ├── CPU重排序（乱序执行）
  └── Store Buffer重排序（写缓冲延迟）

内存屏障
  ├── StoreStore：禁止写-写重排序
  ├── StoreLoad：禁止写-读重排序（最重）
  ├── LoadLoad：禁止读-读重排序
  └── LoadStore：禁止读-写重排序
```

### 1.4 volatile ⭐⭐⭐⭐⭐

```
volatile写
  ├── [StoreStore屏障] ← 刷出前面所有普通写
  ├── volatile写操作
  └── [StoreLoad屏障] ← 保证对后续读可见

volatile读
  ├── volatile读操作
  ├── [LoadLoad屏障] ← 后面读不能重排到前面
  └── [LoadStore屏障] ← 后面写不能重排到前面

设计意图：发布模式
  └── 一个volatile写带出前面所有依赖数据

平台差异
  ├── x86（强内存模型）：写需lock指令，读无额外指令
  └── ARM（弱内存模型）：读写都需要dmb屏障
```

### 1.5 synchronized ⭐⭐⭐⭐

```
内存语义
  ├── monitorexit：StoreStore + StoreLoad屏障
  └── monitorenter：LoadLoad + LoadStore屏障

与volatile对比
  ├── synchronized：自带屏障，管住整个临界区
  └── volatile：轻量级，只保护单个变量

DCL单例问题
  ├── 问题：new操作重排导致半初始化
  ├── 原因：synchronized屏障在块边界，管不住内部重排
  └── 解决：volatile的StoreStore屏障强制初始化先于引用赋值
```

### 1.6 CAS ⭐⭐⭐⭐

```
硬件实现
  ├── x86：lock cmpxchg指令
  └── ARM：ldrex + strex + dmb

AtomicInteger实现
  ├── volatile int value（保证可见性）
  └── Unsafe.compareAndSwapInt（保证原子性）

自旋重试机制
  └── CAS失败 → 重新读取 → 再次CAS

与volatile协同
  ├── volatile：保证读到的值是最新的
  └── CAS：保证"读-改-写"是原子的
```

### 1.7 并发不安全三大根源 ⭐⭐⭐⭐⭐

```
可见性问题
  ├── 根源：多核CPU缓存私有（L1/L2各自一份）
  ├── 场景：Core 0写x=1，Core 1读x还是0
  └── 解决：volatile（MESI+屏障）/ synchronized

有序性问题
  ├── 根源：CPU乱序执行 + 编译器优化
  ├── 场景：x=1; flag=true 重排为 flag=true; x=1
  └── 解决：volatile内存屏障

原子性问题
  ├── 根源：线程时间片切换（调度器随时切换）
  ├── 场景：count++的读-改-写被打断
  ├── 特点：单核CPU也存在（与多核无关）
  └── 解决：synchronized / CAS / Lock
```

### 1.8 final字段 ⭐⭐⭐⭐

```
语义
  ├── 构造方法中对final字段的写入 happens-before 引用发布
  └── JMM保证：final字段写入不会被重排到引用赋值之后

与volatile对比
  ├── final：保护"出生时的值"（构造方法）
  └── volatile：保护"活着时的变化"（运行时）

不可变对象
  └── 所有字段都是final → 安全跨线程共享，无需同步
```

---

## 二、下一步学习路线

### 2.1 AQS（AbstractQueuedSynchronizer） ⭐⭐⭐⭐⭐

**重要程度：最高**

你已经理解了futex和CLH队列，AQS就是把它们串起来。

#### 核心知识点

```
AQS的本质
  └── 用户层面的futex实现（排队逻辑JVM自己管理，内核只负责park/unpark）

state变量
  ├── 独占模式：0=未锁定，>0=重入次数
  └── 共享模式：剩余许可数

CLH变体队列（双向链表）
  ├── Node结构：thread, waitStatus, prev, next
  ├── waitStatus状态：
  │   ├── SIGNAL(-1)：后继节点需要被唤醒
  │   ├── CANCELLED(1)：节点已取消
  │   ├── CONDITION(-2)：节点在条件队列中
  │   └── PROPAGATE(-3)：共享模式下传播唤醒
  └── 入队/出队流程

acquire流程（获取锁）
  ├── tryAcquire（子类实现，CAS尝试获取）
  ├── 失败 → addWaiter（封装Node入队）
  ├── acquireQueued（自旋 + park）
  │   ├── shouldParkAfterFailedAcquire（检查前驱状态）
  │   └── parkAndCheckInterrupt（LockSupport.park）
  └── 被唤醒 → 再次tryAcquire → 成功则出队

release流程（释放锁）
  ├── tryRelease（子类实现，CAS释放）
  ├── unparkSuccessor（唤醒后继节点）
  └── LockSupport.unpark

Condition条件队列
  ├── 每个Condition一个等待队列（vs synchronized只有一个_WaitSet）
  ├── await()：释放锁 → 入条件队列 → park
  └── signal()：出条件队列 → 入CLH队列 → 等待重新获取锁

公平锁 vs 非公平锁
  ├── 公平：hasQueuedPredecessors检查队列中是否有等待者
  └── 非公平：直接CAS尝试获取，失败才入队
```

#### 学习的JUC工具（全部基于AQS）

```
ReentrantLock
  ├── Sync（AQS子类）
  ├── NonfairSync / FairSync
  └── lock/unlock/newCondition

CountDownLatch
  ├── state = count
  ├── await()：acquireShared（state>0则等待）
  └── countDown()：releaseShared（state-1，=0时唤醒所有）

Semaphore
  ├── state = permits
  ├── acquire()：acquireShared（state-1）
  └── release()：releaseShared（state+1）

ReentrantReadWriteLock
  ├── state高16位：读锁持有次数
  ├── state低16位：写锁持有次数
  └── 读写互斥、读读共享
```

#### 学习建议

1. 先读ReentrantLock源码，理解AQS的基本使用
2. 再读CountDownLatch，理解共享模式
3. 最后读ReentrantReadWriteLock，理解state的拆分设计

---

### 2.2 synchronized锁升级 ⭐⭐⭐⭐⭐

**重要程度：最高**

你已经理解了Mark Word和monitor，补上升级流程就完整了。

#### 核心知识点

```
Mark Word结构（64位JVM）
  ├── 无锁：hashCode(31) + 分代年龄(4) + 偏向标志(1) + 锁标志(2)
  ├── 偏向锁：线程ID(54) + epoch(2) + 分代年龄(4) + 偏向标志(1) + 锁标志(2)
  ├── 轻量级锁：指向栈中Lock Record的指针(62) + 锁标志(2)
  └── 重量级锁：指向monitor的指针(62) + 锁标志(2)

锁升级流程（不可逆）
  └── 无锁 → 偏向锁 → 轻量级锁 → 重量级锁

偏向锁
  ├── 场景：只有一个线程进入synchronized块
  ├── 原理：Mark Word记录线程ID，同一线程直接进入，零开销
  ├── 撤销：其他线程竞争时，暂停偏向线程，撤销偏向
  └── JDK 15默认关闭（-XX:-UseBiasedLocking）

轻量级锁
  ├── 场景：两个线程交替执行，竞争不激烈
  ├── 原理：
  │   ├── 栈帧中创建Lock Record
  │   ├── CAS将Mark Word复制到Lock Record（Displaced Mark Word）
  │   ├── CAS成功：获得锁
  │   └── CAS失败：自旋重试
  ├── 自旋策略：
  │   ├── JDK 1.6：固定次数（默认10次）
  │   └── JDK 1.7+：自适应自旋（根据历史成功率动态调整）
  └── 膨胀条件：自旋超过阈值 → 升级为重量级锁

重量级锁
  ├── 场景：多线程激烈竞争
  ├── 原理：创建ObjectMonitor，竞争失败进入_EntryList阻塞
  ├── 阻塞：LockSupport.park（内核态futex）
  └── 唤醒：持有者释放锁时unpark

锁消除
  ├── JIT编译器逃逸分析
  └── 对象不会逃逸出方法 → 删除synchronized

锁粗化
  ├── 相邻的synchronized块使用同一个对象
  └── 合并为一个大synchronized块，减少加锁/解锁开销
```

#### 学习建议

1. 画出Mark Word在不同锁状态下的结构图
2. 理解每次升级的触发条件
3. 理解为什么偏向锁被废弃（撤销成本高）

---

### 2.3 线程池 ⭐⭐⭐⭐⭐

**重要程度：最高（实际开发最常用）**

#### 核心知识点

```
7个核心参数
  ├── corePoolSize：核心线程数（即使空闲也不回收）
  ├── maximumPoolSize：最大线程数
  ├── keepAliveTime：非核心线程空闲存活时间
  ├── unit：时间单位
  ├── workQueue：任务队列
  │   ├── ArrayBlockingQueue：有界队列
  │   ├── LinkedBlockingQueue：无界队列（默认，慎用）
  │   └── SynchronousQueue：不存储任务，直接交接
  ├── threadFactory：线程工厂（自定义线程名）
  └── handler：拒绝策略
      ├── AbortPolicy（默认）：抛异常
      ├── CallerRunsPolicy：调用者线程执行
      ├── DiscardPolicy：静默丢弃
      └── DiscardOldestPolicy：丢弃最老任务

工作流程
  └── 新任务提交
      ├── 当前线程数 < corePoolSize → 创建核心线程
      ├── 当前线程数 >= corePoolSize → 放入workQueue
      ├── workQueue已满 且 线程数 < maximumPoolSize → 创建非核心线程
      └── workQueue已满 且 线程数 = maximumPoolSize → 执行拒绝策略

线程池状态
  ├── RUNNING：接受新任务，处理队列任务
  ├── SHUTDOWN：不接受新任务，处理队列任务
  ├── STOP：不接受新任务，不处理队列任务，中断执行中任务
  ├── TIDYING：所有任务终止，workerCount=0
  └── TERMINATED：terminated()方法执行完成

为什么不用Executors
  ├── newFixedThreadPool/newSingleThread：LinkedBlockingQueue无界 → OOM
  ├── newCachedThreadPool：maximumPoolSize=Integer.MAX_VALUE → 创建大量线程
  └── newScheduledThread：DelayedWorkQueue无界 → OOM

线程池大小设置
  ├── CPU密集型：N + 1（N=CPU核心数）
  ├── IO密集型：2N 或 N / (1 - 阻塞系数)
  └── 最佳实践：压测确定，不要套公式

关闭方式
  ├── shutdown()：优雅关闭，等待已提交任务执行完
  └── shutdownNow()：立即关闭，中断执行中任务
```

#### 学习建议

1. 手写一个自定义线程池，理解参数含义
2. 画出任务提交的完整流程
3. 理解为什么阿里巴巴规范要求手动创建线程池

---

### 2.4 ThreadLocal ⭐⭐⭐⭐

**重要程度：高（面试常问）**

#### 核心知识点

```
ThreadLocal的作用
  └── 线程私有存储，每个线程有自己的变量副本

底层实现
  ├── Thread类中的ThreadLocalMap
  │   ├── Entry[] table（哈希表）
  │   └── Entry继承WeakReference<ThreadLocal>
  ├── ThreadLocal作为key（弱引用）
  ├── 值作为value（强引用）
  └── set/get/remove操作

内存泄漏问题
  ├── 原因：
  │   ├── key是弱引用，GC后变成null
  │   ├── value是强引用，不会被回收
  │   └── Entry变成(key=null, value) → 无法访问也无法回收
  ├── 解决：
  │   ├── 每次使用后调用remove()
  │   └── ThreadLocal内部会清理key=null的Entry（但不是立即）
  └── 最佳实践：
      └── try-finally中调用remove()

InheritableThreadLocal
  ├── 子线程可以继承父线程的值
  ├── 原理：Thread初始化时复制父线程的InheritableThreadLocalMap
  └── 问题：线程池复用线程，值不会更新

TransmittableThreadLocal（阿里开源）
  ├── 解决线程池场景下的值传递
  └── 原理：在任务提交时捕获，执行时恢复

应用场景
  ├── 数据库连接（每个线程一个Connection）
  ├── Session管理（每个线程一个用户信息）
  ├── 日期格式化（SimpleDateFormat非线程安全）
  └── 链路追踪（TraceId传递）
```

#### 学习建议

1. 画出ThreadLocalMap的内存结构
2. 理解弱引用导致的内存泄漏
3. 手写ThreadLocal的set/get/remove实现

---

### 2.5 JUC工具类 ⭐⭐⭐⭐

**重要程度：高（基于AQS，理解AQS后这些都是自然推导出来的）**

#### 核心知识点

```
CountDownLatch（倒计数器）
  ├── 场景：主线程等待多个子线程完成
  ├── 原理：state=count，countDown()释放一个，await()等待state=0
  └── 一次性使用，不能重置

CyclicBarrier（循环栅栏）
  ├── 场景：多个线程互相等待，全部到达后一起执行
  ├── 原理：不基于AQS，使用ReentrantLock + Condition
  └── 可重复使用，到达后重置

Semaphore（信号量）
  ├── 场景：控制并发访问的线程数量（限流）
  ├── 原理：state=permits，acquire()获取一个，release()释放一个
  └── 可用于资源池（连接池、对象池）

ReentrantReadWriteLock（读写锁）
  ├── 场景：读多写少
  ├── 原理：state高16位读锁，低16位写锁
  ├── 读写互斥，读读共享，写写互斥
  └── 锁降级：持有写锁 → 获取读锁 → 释放写锁

StampedLock（邮戳锁，JDK 1.8）
  ├── 场景：比ReadWriteLock更高性能
  ├── 三种模式：
  │   ├── 写锁（write）
  │   ├── 悲观读锁（read）
  │   └── 乐观读（optimisticRead，无锁）
  └── 乐观读流程：
      ├── long stamp = tryOptimisticRead()
      ├── 读取数据
      ├── if (!validate(stamp)) → 升级为悲观读锁
      └── 不支持重入，不支持Condition

Exchanger（交换器）
  ├── 场景：两个线程交换数据
  └── 原理：不基于AQS，使用CAS + park/unpark
```

#### 学习建议

1. 先学CountDownLatch和Semaphore（最简单的AQS应用）
2. 再学ReentrantReadWriteLock（理解state拆分）
3. 最后学StampedLock（理解乐观读优化）

---

## 三、进阶学习（选学）

### 3.1 ConcurrentHashMap ⭐⭐⭐⭐

```
JDK 1.7：分段锁（Segment数组）
  └── 16个Segment，每个Segment一把锁

JDK 1.8：CAS + synchronized
  ├── 数组+链表+红黑树
  ├── 空桶：CAS插入
  ├── 非空桶：synchronized锁住头节点
  └── size()：baseCount + CounterCell数组（避免伪共享）

核心方法
  ├── put：计算hash → 定位桶 → CAS/synchronized插入
  ├── get：计算hash → 定位桶 → 遍历链表/红黑树
  └── size：sumCount() = baseCount + sum(CounterCell)
```

### 3.2 阻塞队列 ⭐⭐⭐

```
ArrayBlockingQueue
  ├── 数组实现，有界
  ├── 一把锁（ReentrantLock）
  └── 两个Condition（notFull, notEmpty）

LinkedBlockingQueue
  ├── 链表实现，可选有界
  ├── 两把锁（takeLock, putLock）→ 更高并发
  └── 两个Condition

SynchronousQueue
  ├── 不存储元素
  ├── 生产者直接交给消费者
  └── 用于CachedThreadPool

PriorityBlockingQueue
  ├── 优先级队列（堆实现）
  ├── 无界
  └── 一把锁
```

### 3.3 CompletableFuture ⭐⭐⭐

```
异步编排
  ├── supplyAsync：有返回值
  ├── runAsync：无返回值
  └── 默认使用ForkJoinPool.commonPool()

链式调用
  ├── thenApply：同步转换（有返回值）
  ├── thenAccept：同步消费（无返回值）
  ├── thenRun：同步执行（无参数无返回值）
  └── thenCompose：扁平化（返回CompletableFuture）

组合
  ├── thenCombine：两个都完成，合并结果
  ├── thenAcceptBoth：两个都完成，消费结果
  ├── applyToEither：任一完成，转换结果
  └── acceptEither：任一完成，消费结果

批量
  ├── allOf：全部完成
  └── anyOf：任一完成

异常处理
  ├── exceptionally：异常时返回默认值
  ├── handle：正常/异常都处理
  └── whenComplete：正常/异常都处理（无返回值）
```

### 3.4 Fork/Join ⭐⭐

```
工作窃取算法（Work-Stealing）
  ├── 每个线程一个双端队列
  ├── 从自己队列头部取任务执行
  └── 自己队列为空时，从其他线程队列尾部窃取

核心类
  ├── ForkJoinPool：线程池
  ├── ForkJoinTask：任务抽象
  ├── RecursiveAction：无返回值任务
  └── RecursiveTask<V>：有返回值任务

使用模式
  ├── fork()：异步执行子任务
  ├── join()：等待子任务完成
  └── invoke()：同步执行任务

适用场景
  ├── 可以递归拆分的任务
  ├── 子任务之间无依赖
  └── 例如：归并排序、MapReduce
```

---

## 四、学习顺序建议

```
第一阶段（1-2周）：AQS
  ├── Day 1-3：读ReentrantLock源码
  ├── Day 4-5：读CountDownLatch源码
  ├── Day 6-7：读Semaphore源码
  └── Day 8-10：读ReentrantReadWriteLock源码

第二阶段（3-5天）：synchronized锁升级
  ├── Day 1-2：Mark Word结构
  ├── Day 3-4：偏向锁/轻量级锁/重量级锁流程
  └── Day 5：锁消除和锁粗化

第三阶段（3-5天）：线程池
  ├── Day 1-2：7个参数和工作流程
  ├── Day 3-4：手写自定义线程池
  └── Day 5：线程池大小设置和关闭方式

第四阶段（2-3天）：ThreadLocal
  ├── Day 1：ThreadLocalMap结构
  ├── Day 2：内存泄漏问题
  └── Day 3：InheritableThreadLocal和TTL

第五阶段（3-5天）：JUC工具类
  ├── Day 1-2：CountDownLatch/Semaphore/Exchanger
  ├── Day 3-4：ReentrantReadWriteLock/StampedLock
  └── Day 5：CyclicBarrier

第六阶段（选学）：
  ├── ConcurrentHashMap（2-3天）
  ├── 阻塞队列（1-2天）
  ├── CompletableFuture（2-3天）
  └── Fork/Join（1-2天）
```

---

## 五、学习方法建议

### 5.1 源码阅读方法

```
1. 先理解设计意图（解决什么问题）
2. 画出核心数据结构
3. 跟踪主流程（acquire/release）
4. 理解边界情况（中断、超时、公平/非公平）
5. 对比其他实现（为什么这样设计）
```

### 5.2 面试准备方法

```
1. 能用一句话总结每个知识点
2. 能画出底层流程图
3. 能对比相似概念（volatile vs synchronized vs final）
4. 能举出实际应用场景
5. 能回答"为什么"（设计原因，不是"是什么"）
```

### 5.3 实践方法

```
1. 手写核心实现（AQS简化版、线程池、ThreadLocal）
2. 写测试代码验证理论（DCL问题、CAS自旋、锁升级）
3. 在真实项目中使用（线程池、读写锁、CountDownLatch）
4. 用JMH做性能测试（对比不同方案）
```

---

## 六、推荐资源

### 6.1 书籍

```
入门：
  ├── 《Java并发编程的艺术》（方腾飞）
  └── 《Java并发编程实战》（Brian Goetz）

进阶：
  ├── 《深入理解Java虚拟机》（周志明）第12-13章
  └── 《Java性能优化权威指南》（Scott Oaks）
```

### 6.2 源码

```
JDK源码：
  ├── java.util.concurrent.locks（AQS、Lock）
  ├── java.util.concurrent（线程池、JUC工具）
  └── java.lang.Thread（线程基础）

调试工具：
  ├── jstack：查看线程栈和锁状态
  ├── jconsole/jvisualvm：图形化监控
  └── Java Mission Control（JMC）：高级分析
```

---

## 七、总结

你已经建立了扎实的并发基础，从硬件底层到JVM实现都有深入理解。接下来的学习重点是：

1. **AQS**：把futex和CLH队列串起来，理解JUC工具的统一框架
2. **synchronized锁升级**：补上Mark Word和monitor的完整流程
3. **线程池**：掌握实际开发中最常用的并发工具
4. **ThreadLocal**：理解线程私有存储和内存泄漏问题
5. **JUC工具类**：基于AQS的各种工具，理解设计模式

按照这个路线学习，你的Java并发知识体系将非常完整，无论是面试还是实际开发都能游刃有余。

**记住：理解底层原理是为了更好地使用上层工具，而不是为了炫技。**
