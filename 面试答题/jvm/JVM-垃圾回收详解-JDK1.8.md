# JVM 垃圾回收详解（JDK 1.8）

---

## 一、如何判断对象可以回收？

### 1. 引用计数法（Java 不使用）

```
每个对象维护一个计数器，被引用 +1，引用断开 -1，为 0 时回收
缺陷：无法解决循环引用问题

A → B → A   两个对象互相引用，计数都不为 0，但实际已不可达
```

### 2. 可达性分析（Java 使用）

```
从 GC Roots 出发，沿引用链向下搜索
能被搜索到的对象 → 存活
搜索不到的对象 → 可回收
```

**GC Roots 包括**：

```
├── 虚拟机栈中引用的对象（局部变量）
├── 静态变量引用的对象
├── 常量引用的对象（字符串常量池等）
└── Native 方法引用的对象（JNI）
```

```
GC Roots
  ├── objA → objB → objC    ← 存活
  ├── objD                   ← 存活
  │
  │   objE → objF           ← 不可达，可回收
```

#### 为什么这四种是 GC Roots？

GC Root 的选择逻辑：**所有从 JVM 外部能直接访问到的引用**都必须是 Root。只要对象能从这些 Root 沿着引用链找到，就说明它还在被使用，不能回收。

| GC Root | 为什么 | 示例 |
|---------|------|------|
| **栈中局部变量** | 正在执行的代码直接持有的引用，方法未结束时变量还在用 | `User user = new User()` 中 user 在栈中 |
| **静态变量** | 属于类本身，不依赖任何实例，程序运行期间随时可通过类名访问 | `static Map CACHE = new HashMap()` |
| **常量池** | 全局共享，字节码指令（如 `ldc`）随时可能引用它们 | `String s = "hello"` 中的 "hello" |
| **Native 引用** | JVM 无法追踪 Native 代码内部的引用关系，保守地认为"可能还在用" | JNI 方法传入的 Java 对象 |

类比理解：堆是仓库，对象是货物：
- 栈中局部变量 = **正在被工人搬运用的货物** → 绝对不能扔
- 静态变量 = **登记在册的固定资产** → 公司随时要用
- 常量池 = **共享工具库** → 任何人都可能来借
- Native 引用 = **外包团队借走的设备** → 不知道他们在不在用，不能收回

### 3. 四种引用类型

#### ① 强引用（Strong Reference）

**最普通的引用，就是日常写的代码。**

```java
User user = new User();                // 强引用
List<String> list = new ArrayList<>(); // 强引用
```

**特点**：
- 只要有强引用指向对象，**GC 绝不回收**，宁可抛出 `OutOfMemoryError`
- 这是 Java 内存泄漏的主要原因——长生命周期对象持有短生命周期对象的强引用

```java
// 内存泄漏案例
public class Cache {
    private static Map<String, Object> map = new HashMap<>();  // 静态变量，永远不回收
    public void put(String key, Object value) {
        map.put(key, value);  // value 被强引用，永远不会被 GC
    }
    // 没有 remove 方法 → 内存泄漏
}
```

断开强引用：`user = null;`

---

#### ② 软引用（SoftReference）

**内存够就不回收，内存不足才回收。**

```java
SoftReference<byte[]> softRef = new SoftReference<>(new byte[1024 * 1024]);
byte[] data = softRef.get();  // 可能返回 null（已被回收）
```

GC 行为：
```
内存充足 → 不回收，get() 正常返回对象
内存不足（即将 OOM）→ 回收软引用对象，释放内存
回收后仍不够 → 抛出 OOM
```

**典型用途：内存敏感的缓存**

```java
public class ImageCache {
    private Map<String, SoftReference<Bitmap>> cache = new HashMap<>();

    public Bitmap getImage(String url) {
        SoftReference<Bitmap> ref = cache.get(url);
        if (ref != null) {
            Bitmap bitmap = ref.get();
            if (bitmap != null) return bitmap;   // 缓存命中
            cache.remove(url);                   // 已被 GC 回收
        }
        return downloadImage(url);               // 缓存未命中
    }
}
// 内存充足时缓存生效，内存不足时自动释放图片，不会 OOM
```

---

#### ③ 弱引用（WeakReference）

**不管内存够不够，下次 GC 就回收。**

```java
WeakReference<User> weakRef = new WeakReference<>(new User());
System.gc();
User user = weakRef.get();  // null，已被回收
```

**典型用途一：ThreadLocal 防止内存泄漏**

```java
// ThreadLocal 内部用 WeakReference 存储 key
static class ThreadLocalMap {
    static class Entry extends WeakReference<ThreadLocal<?>> {
        Object value;
        Entry(ThreadLocal<?> k, Object v) {
            super(k);      // key 是弱引用
            value = v;     // value 是强引用
        }
    }
}
// ThreadLocal 外部没有强引用时，下次 GC 就能回收 key
// 避免 ThreadLocal 对象因线程池中长期存活的线程而永远不被回收
```

**典型用途二：WeakHashMap**

```java
WeakHashMap<User, String> map = new WeakHashMap<>();
User user = new User();
map.put(user, "data");

user = null;       // 断开外部强引用
System.gc();       // 触发 GC
// user 被回收，map 中对应的 entry 也会自动清除
map.size();        // 0
```

**典型用途三：监听器/回调注册**

```java
// 用弱引用，当监听器对象没有外部强引用时自动回收，防止内存泄漏
List<WeakReference<EventListener>> listeners = new ArrayList<>();

public void fireEvent() {
    listeners.removeIf(ref -> ref.get() == null);  // 清除已回收的
    listeners.forEach(ref -> ref.get().onEvent());
}
```

---

#### ④ 虚引用（PhantomReference）

**最弱的引用，随时会被回收，甚至无法通过它获取对象。**

```java
ReferenceQueue<User> queue = new ReferenceQueue<>();
PhantomReference<User> phantomRef = new PhantomReference<>(new User(), queue);

User user = phantomRef.get();  // 永远返回 null！
```

**唯一用途：跟踪对象被回收的时机**

```java
// 当对象被 GC 回收时，虚引用会被放入关联的 ReferenceQueue
// 可以从队列中得知"哪个对象被回收了"
new Thread(() -> {
    while (true) {
        Reference<?> polled = queue.poll();
        if (polled != null) {
            System.out.println("对象被回收了，执行清理操作");
            // 释放堆外内存、关闭文件句柄等
        }
    }
}).start();
```

**实际场景：DirectByteBuffer 的堆外内存回收**

```java
// NIO 中的 DirectByteBuffer 分配堆外内存
// JVM 的 GC 只管堆内内存，不知道堆外内存的存在
// 通过虚引用 + Cleaner 机制，在 DirectByteBuffer 被回收时
// 自动释放对应的堆外内存（调用 unsafe.freeMemory）
```

---

#### 四种引用对比总表

| 引用类型 | GC 行为 | `get()` 返回值 | 典型用途 |
|---------|---------|---------------|--------|
| **强引用** | 永不回收 | 始终有值 | 普通对象 |
| **软引用** | 内存不足时回收 | 可能为 null | 缓存（图片、页面） |
| **弱引用** | 下次 GC 必回收 | 可能为 null | ThreadLocal、WeakHashMap、监听器 |
| **虚引用** | 随时回收 | **永远返回 null** | 跟踪回收时机、堆外内存释放 |

#### 为什么实际项目中几乎用不到？

**因为业务代码都是强引用，这是正常的。** 软/弱/虚引用主要存在于**框架和底层库**的内部实现中。

**你其实一直在“用”，只是不知道**：

| 你用的东西 | 底层用了什么引用 |
|-----------|---------------|
| `ThreadLocal` | **弱引用**（key 用 WeakReference 防止内存泄漏） |
| `WeakHashMap` | **弱引用**（key 被回收时自动清除 entry） |
| NIO `DirectByteBuffer` | **虚引用**（PhantomReference + Cleaner 释放堆外内存） |
| Guava Cache / Caffeine | 内部支持**软引用/弱引用**的 value，实现自动过期 |

**什么场景才需要手动使用？**

- **自己实现缓存框架**：value 用 `SoftReference`，内存不足时自动丢弃而不是 OOM
- **自己实现事件总线/插件系统**：监听器用 `WeakReference`，插件卸载后自动消失
- **操作堆外内存**：用 `PhantomReference` 跟踪 DirectByteBuffer 的回收时机

> 但实际上你会直接用 Guava Cache、Spring Event、Netty，它们内部已经帮你处理好了。

**面试回答建议**：

> “业务代码中都是强引用，但我了解它们的底层应用：ThreadLocal 的 key 用了弱引用防止线程池内存泄漏；Guava Cache 内部支持软引用实现内存敏感的缓存淘汰；NIO DirectByteBuffer 用虚引用 + Cleaner 释放堆外内存。如果项目中需要自己实现缓存，我会在 value 层用 SoftReference 保证内存不足时自动降级。”

---

## 二、垃圾回收算法

### 1. 标记-清除（Mark-Sweep）

```
标记阶段：标记所有存活对象
清除阶段：清除未标记对象

问题：产生内存碎片
┌──────────────────┐
│ ████  ████  ████ │  ← 碎片化，无法分配大对象
└──────────────────┘
```

### 2. 标记-复制（Mark-Copy）

```
将内存分为两块，每次只使用一块
GC 时把存活对象复制到另一块，清空当前块

优点：无碎片，分配快（指针碰撞）
缺点：浪费一半内存

新生代使用此算法（Eden + 两个 Survivor）
```

### 3. 标记-整理（Mark-Compact）

```
标记阶段：标记存活对象
整理阶段：把存活对象向一端移动，清空另一端

优点：无碎片，不浪费空间
缺点：移动对象开销大

老年代使用此算法
```

### 4. 分代收集（Generational Collection）— HotSpot 实际采用

```
根据对象存活周期，将堆分为新生代和老年代，各用不同算法

新生代（对象存活率低，大部分朝生夕灭）
  → 标记-复制算法
  → Eden + Survivor0 + Survivor1（8:1:1）

老年代（对象存活率高，长期存活）
  → 标记-清除 或 标记-整理
```

---

## 三、新生代为什么有两个 Survivor？

### 核心原因：复制算法需要一块"空闲区"来接收存活对象

#### 如果只有 1 个 Survivor

```
GC 前：
┌─────────────┬───────┐
│ Eden ██████ │ S ████│  ← S 中已有存活对象
└─────────────┴───────┘

GC 时：把 Eden 存活对象复制到 S
┌─────────────┬───────┐
│ Eden        │ S ████│  ← S 里原来的对象怎么办？
│             │  + ████│  ← 新旧对象混在一起，无法清空
└─────────────┴───────┘

问题：S 中已有的对象和新复制来的对象混在一起，无法区分，无法清空 Eden
```

#### 有 2 个 Survivor（S0 和 S1）

**关键设计：始终有一个 Survivor 是空的**

```
GC 前：
┌─────────────┬──────┬──────┐
│ Eden ██████ │ S0 ██│ S1   │  ← S1 是空的
└─────────────┴──────┴──────┘

GC 时：把 Eden + S0 中的存活对象 → 复制到 S1
┌─────────────┬──────┬──────┐
│ Eden        │ S0   │ S1 ██│  ← 存活对象全部到 S1
└─────────────┴──────┴──────┘
     ↑ 清空        ↑ 清空

下一次 GC：反过来，从 Eden + S1 → 复制到 S0
┌─────────────┬──────┬──────┐
│ Eden        │ S0 ██│ S1   │  ← S0 和 S1 角色互换
└─────────────┴──────┴──────┘
```

**每次 GC 后，From 区清空，To 区存放存活对象，两者角色互换。**

#### 为什么这样设计？

| 设计目标 | 两个 Survivor 如何实现 |
|---------|---------------------|
| **无碎片** | 复制过去的对象是连续排列的，没有空隙 |
| **分配极快** | 用指针碰撞分配，只需移动指针 |
| **清空简单** | From 区直接整体清空，不需要逐个判断 |

#### 对象的年龄增长过程

```
第 1 次 GC：对象在 Eden 存活 → 复制到 S0，年龄 = 1
第 2 次 GC：对象在 S0 存活  → 复制到 S1，年龄 = 2
第 3 次 GC：对象在 S1 存活  → 复制到 S0，年龄 = 3
...
第 15 次 GC：年龄达到阈值（默认 15）→ 进入老年代
```

每经过一次 GC 在两个 Survivor 之间来回复制，年龄就 +1。

#### 为什么 Survivor 占比只有 10%？

```
新生代比例：Eden : S0 : S1 = 8 : 1 : 1

原因：
- 大部分对象朝生夕灭（98% 以上），存活率极低
- Survivor 只需要容纳少量存活对象
- 如果 Survivor 太大 → 浪费新生代空间
- 如果 Survivor 太小 → 存活对象放不下，直接进入老年代（不好）
```

可通过 `-XX:SurvivorRatio=8` 调整比例。

---

## 四、Minor GC 与 Major GC

```
Minor GC（新生代 GC）
  触发：Eden 区空间不足
  过程：Eden + 当前 Survivor 中存活对象 → 另一个 Survivor
        年龄 +1，超过阈值（默认 15）→ 进入老年代
  频率：非常频繁（应用分配对象主要在新生代）
  速度：快（新生代小，且大部分对象直接回收）

Major GC / Full GC（老年代 GC）
  触发：老年代空间不足 / 元空间不足 / 显式调用 System.gc()
  过程：对整个堆进行回收
  频率：较少
  速度：慢（老年代大，且对象存活率高）
```

**对象进入老年代的条件**：
1. 年龄达到阈值（默认 15，`-XX:MaxTenuringThreshold`）
2. Survivor 区放不下的同龄对象（动态年龄判断）
3. 大对象直接进入老年代（`-XX:PretenureSizeThreshold`）

#### 条件一详解：年龄达到阈值

对象每次在 Minor GC 中存活，年龄 +1：

```
Eden 创建对象（年龄 0）
    ↓ Minor GC 存活
S0（年龄 1）→ S1（年龄 2）→ S0（年龄 3）→ ...
    ↓ 重复 15 次
年龄 = 15 → 进入老年代
```

为什么默认是 15？对象头中分代年龄只有 **4 bit**，最大值就是 15（2^4 - 1）。如果一个对象经历了 15 次 GC 还存活，说明它是"长寿对象"，放在新生代反复复制反而浪费性能。

#### 条件二详解：动态年龄判断

不看单个对象的年龄，看**同龄对象的总量**。

规则：如果 Survivor 区中，某个年龄及以下的所有对象总大小 > Survivor 空间的一半，那么大于等于该年龄的对象全部直接进入老年代。

```
Survivor 空间大小：10MB

当前 Survivor 中对象分布：
年龄 1 的对象：2MB
年龄 2 的对象：1MB
年龄 3 的对象：3MB  ← 累计 2+1+3 = 6MB > 10MB/2 = 5MB
年龄 4 的对象：1MB

结论：年龄 ≥ 3 的对象全部直接进入老年代，不需要等到 15 岁
```

设计目的：Survivor 空间有限，当同龄对象累积过多时，自动让年龄较大的对象提前进入老年代，给新对象腾出空间。

#### 条件三详解：大对象直接进入老年代

大对象在新生代来回复制太浪费，直接放老年代。

```java
byte[] bigArray = new byte[10 * 1024 * 1024]; // 10MB 的大对象
```

```
新生代使用复制算法：
  每次 Minor GC 都要复制 10MB 的数据
  复制 15 次 = 150MB 的复制开销 → 非常浪费

老年代用标记-整理算法：
  大对象原地不动，零复制开销
```

通过参数控制：
```bash
-XX:PretenureSizeThreshold=1048576   # 大于 1MB 的对象直接进入老年代
-XX:PretenureSizeThreshold=0         # 默认 0，表示不启用（由 JVM 自动判断）
```

> 注意：`PretenureSizeThreshold` 只对 **Serial** 和 **ParNew** 收集器生效。G1 中有专门的 Humongous Region（H 区），对象大小 > Region 大小的 50% 时自动放入 H 区（属于老年代），不需要设置此参数。

#### 三个条件的完整流程

```
new 对象
    ↓
对象大小 > PretenureSizeThreshold？
├── 是 → 直接进入老年代（条件三）
└── 否 → 进入 Eden 区
            ↓
        Minor GC → 对象存活？
        ├── 否 → 回收
        └── 是 → 年龄 +1
                    ↓
                年龄 >= MaxTenuringThreshold？
                ├── 是 → 进入老年代（条件一）
                └── 否 → 复制到 Survivor
                            ↓
                        同龄对象总量 > Survivor 一半？
                        ├── 是 → 进入老年代（条件二）
                        └── 否 → 留在 Survivor，等下次 GC
```

---

## 五、JDK 1.8 可用的垃圾收集器

```
新生代收集器                    老年代收集器
├── Serial（单线程）             ├── Serial Old（单线程，标记-整理）
├── ParNew（多线程）             ├── CMS（多线程，标记-清除）
└── Parallel Scavenge（多线程）  └── Parallel Old（多线程，标记-整理）

整堆收集器
└── G1（Garbage First，JDK 9 默认，JDK 1.8 可用）
```

### 各收集器对比

| 收集器 | 线程 | 算法 | 适用区域 | 特点 |
|--------|------|------|---------|------|
| **Serial** | 单线程 | 复制 | 新生代 | 最简单，STW，适合客户端 |
| **ParNew** | 多线程 | 复制 | 新生代 | Serial 的多线程版，常配合 CMS |
| **Parallel Scavenge** | 多线程 | 复制 | 新生代 | 关注**吞吐量**（自适应调节） |
| **Serial Old** | 单线程 | 整理 | 老年代 | CMS 的后备方案 |
| **CMS** | 并发 | 清除 | 老年代 | **低停顿**，但有碎片 |
| **Parallel Old** | 多线程 | 整理 | 老年代 | 配合 Parallel Scavenge |
| **G1** | 并发 | 分区复制+整理 | 整堆 | JDK 9 默认，可预测停顿时间 |

### 常用组合

```
JDK 1.8 默认：Parallel Scavenge + Parallel Old（吞吐量优先）
低延迟场景：ParNew + CMS（停顿时间优先）
推荐方案：G1（兼顾吞吐和延迟）
```

---

## 六、CMS 收集器详解

**CMS（Concurrent Mark Sweep）：以最短停顿时间为目标的老年代收集器**

### 四个阶段

```
① 初始标记（STW）
   仅标记 GC Roots 直接关联的对象
   停顿时间：很短

② 并发标记
   从 GC Roots 出发，遍历整个对象图
   与用户线程同时执行，不停顿

③ 重新标记（STW）
   修正并发标记期间因用户线程运行导致的标记变动
   停顿时间：较短（比并发标记短得多）

④ 并发清除
   清除未标记对象
   与用户线程同时执行，不停顿
```

### CMS 的缺点

| 问题 | 说明 |
|------|------|
| **内存碎片** | 使用标记-清除，不做压缩，长期运行产生碎片 |
| **CPU 敏感** | 并发阶段占用 CPU，影响应用吞吐量 |
| **浮动垃圾** | 并发清除阶段新产生的垃圾无法本次回收，留到下次 GC |
| **Concurrent Mode Failure** | 老年代空间不足以存放新对象时，退化为 Serial Old，停顿时间暴涨 |

---

## 七、G1 收集器详解

**G1（Garbage First）：JDK 1.8 可用，JDK 9 起默认**

### 核心思想：把堆划分为多个 Region

```
传统分代：
┌─────────────────────┬─────────────────────┐
│      新生代          │       老年代         │
└─────────────────────┴─────────────────────┘

G1 分区：
┌───┬───┬───┬───┬───┬───┬───┬───┐
│ E │ E │ S │ O │ O │ H │ E │ O │  ← 每个 Region 可以是
└───┴───┴───┴───┴───┴───┴───┴───┘     Eden/Survivor/Old/Humongous
E=Eden  S=Survivor  O=Old  H=Humongous（大对象）
```

### G1 的 GC 过程

```
Young GC（新生代回收）
  → 回收所有 Eden + Survivor Region
  → 存活对象复制到新的 Survivor Region
  → STW（Stop The World）

Mixed GC（混合回收，老年代+新生代）
  → 回收所有新生代 Region + 部分老年代 Region
  → "Garbage First"：优先回收垃圾最多的 Region
  → 通过 -XX:MaxGCPauseMillis 控制停顿时间

Full GC（降级，单线程，尽量避免）
  → 当 Mixed GC 跟不上对象分配速度时触发
  → 性能差，停顿时间长
```

### G1 关键参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-XX:+UseG1GC` | 启用 G1 | JDK 9+ 默认 |
| `-XX:MaxGCPauseMillis` | 期望最大停顿时间 | 200ms |
| `-XX:G1HeapRegionSize` | Region 大小 | 自动（1~32MB） |
| `-XX:InitiatingHeapOccupancyPercent` | 老年代占比触发并发标记 | 45% |

---

## 八、GC 调优实战

### 关键指标

```
吞吐量：应用运行时间 / (应用运行时间 + GC 时间)
        目标 > 99%

停顿时间：单次 GC 的 STW 时长
          交互式应用要求 < 100ms

内存占用：堆使用量
          越小越好（但会影响吞吐和停顿）
```

### 常见 GC 问题排查

```bash
# 查看 GC 日志
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log

# 查看 GC 原因
-XX:+PrintGCCause

# 查看对象年龄分布
-XX:+PrintTenuringDistribution
```

### 调优决策树

```
GC 停顿时间过长？
├── 新生代太大 → 减小新生代（-Xmn）
├── 老年代 GC 频繁 → 增大堆（-Xmx）
└── 使用 G1 并设置 -XX:MaxGCPauseMillis

GC 频率过高？
├── 对象创建过快 → 优化代码，减少临时对象
├── 大对象太多 → -XX:PretenureSizeThreshold 让大对象直接进老年代
└── 增大堆内存

Full GC 频繁？
├── 检查内存泄漏（MAT 分析 heap dump）
├── 老年代太小 → 增大老年代（减小 -Xmn）
└── 检查是否有大量大对象进入老年代
```

---

## 九、安全点与安全区域

```
安全点（Safepoint）
  → JVM 中特定的位置，GC 只能在安全点暂停线程
  → 通常在方法调用、循环跳转、异常跳转处设置
  → 线程太少安全点 → GC 等待时间长
  → 线程太多安全点 → 运行时开销大

安全区域（Safe Region）
  → 线程处于 Sleep/Blocked 状态时，无法主动到达安全点
  → 安全区域是安全点的扩展，线程进入安全区域时标记自己
  → GC 可以忽略处于安全区域的线程
```

---

## 十、JDK 17 / 21 / 25 垃圾回收演进

### JDK 17（2021，LTS 版本）

**CMS 被彻底移除**（JEP 363）：
```
JDK 9：CMS 标记废弃
JDK 14：CMS 代码移除
JDK 17：-XX:+UseConcMarkSweepGC 会直接报错
替代方案：低延迟场景用 ZGC 或 Shenandoah
```

**ZGC 正式生产可用**（JEP 377）：
```
目标：停顿时间 < 1ms，与堆大小无关
原理：着色指针（Colored Pointers）+ 读屏障（Load Barriers）
支持堆大小：几 MB 到几 TB

启用：-XX:+UseZGC
```

**Shenandoah GC 正式生产可用**（JEP 379）：
```
Red Hat 开发，与 ZGC 类似追求低延迟
使用 Brooks Pointer 实现并发压缩
仅支持 x86_64（不支持 ARM）

启用：-XX:+UseShenandoahGC
```

**G1 改进**：Full GC 从单线程变为**多线程并行**（JEP 307），大幅减少 Full GC 停顿时间。

---

### JDK 21（2023，LTS 版本）

**ZGC 分代模式**（JEP 439）：
```
之前 ZGC 是整堆收集，不区分新生代/老年代
JDK 21 新增分代 ZGC：
  - 新生代单独回收，更频繁但更快
  - 老年代回收频率降低
  - 停顿时间仍然 < 1ms
  - 吞吐量比非分代 ZGC 提升 20%+

启用：-XX:+UseZGC -XX:+ZGenerational
```

**虚拟线程对 GC 的影响**（JEP 444）：
```
虚拟线程（Project Loom）：一个 JVM 可运行数百万个虚拟线程

对 GC 的影响：
  - 虚拟线程创建的临时对象更多
  - GC Roots 扫描范围更大
  - ZGC/Shenandoah 的并发标记更适合虚拟线程场景
  - G1 在高虚拟线程数下可能停顿增加
```

---

### JDK 25（预计 2025，LTS 版本）

**ZGC 可能成为推荐 GC**：
```
目前 G1 仍是默认 GC
JDK 25 可能将 ZGC Generational 作为推荐 GC
目标：让“低延迟”成为 Java 的默认体验
```

**Epsilon GC 成熟**：
```
“不做任何回收”的 GC，内存用完直接 OOM
用途：性能测试、极短生命周期应用、手动内存管理

启用：-XX:+UseEpsilonGC
```

---

### JDK 版本 GC 演进总表

| JDK 版本 | 发布时间 | 默认 GC | 重大变化 |
|---------|---------|---------|--------|
| JDK 8 | 2014 | Parallel | CMS 成熟，G1 可用 |
| JDK 9 | 2017 | **G1** | G1 成为默认，CMS 标记废弃 |
| JDK 11 | 2018 | G1 | ZGC/Shenandoah 实验性引入 |
| JDK 15 | 2020 | G1 | ZGC/Shenandoah 可用于生产 |
| **JDK 17** | 2021 | G1 | **CMS 彻底移除**，ZGC/Shenandoah 正式生产可用 |
| **JDK 21** | 2023 | G1 | **ZGC 分代模式**，虚拟线程影响 GC |
| **JDK 25** | 2025（预计） | G1（可能改 ZGC） | ZGC 可能成为推荐 GC |

---

### 生产环境 GC 选择建议（2024）

```
通用场景（80% 的应用）：
  → G1 + -XX:MaxGCPauseMillis=200

极致低延迟（金融交易、实时通信）：
  → ZGC Generational（JDK 21+）
  → 或 Shenandoah（JDK 17+，x86 平台）

吞吐量优先（批处理、离线计算）：
  → Parallel GC（-XX:+UseParallelGC）

测试/基准性能：
  → Epsilon GC（不回收，测完即弃）
```

---

## 十一、G1 / ZGC / Shenandoah 的缺点

### G1 的缺点

- **内存占用高**：RSet 记录跨 Region 引用，总开销约占堆的 10~20%
- **Full GC 性能差**：JDK 10 之前是单线程，触发 Full GC 停顿可能达数秒
- **停顿时间“软保证”**：`-XX:MaxGCPauseMillis=200` 只是目标，高负载时可能超标
- **不适合小堆**：堆 < 2GB 时 Region 太小，RSet 开销占比更高，Parallel GC 反而更好
- **大对象处理局限**：H Region 不能移动，大量大对象时产生碎片

### ZGC 的缺点

- **CPU 开销大**：读屏障增加约 5~15% CPU 开销，吞吐量下降 10~20%
- **内存占用高**：着色指针 + 转发表，额外开销约堆大小的 10~25%
- **JDK 21 之前不支持分代**：整堆收集，短命/长命对象混合，效率不如分代收集
- **社区生态不如 G1**：JDK 15 才正式可用，调优经验和监控工具相对较少

### Shenandoah 的缺点

- **仅支持 x86_64**：不支持 ARM64（Apple Silicon、AWS Graviton）
- **Brooks Pointer 开销**：每个对象头额外 +8 字节，每次访问对象都要间接寻址
- **吞吐量下降最明显**：写屏障比 ZGC 读屏障更重，吞吐量下降 15~30%
- **Red Hat 主导，Oracle 支持有限**：长期看 ZGC 更可能成为标准方案

### 三者对比

| 缺点 | G1 | ZGC | Shenandoah |
|------|-----|-----|-----------|
| 内存开销 | 中（RSet 10~20%） | 高（着色指针 + 转发表） | 高（Brooks Pointer） |
| CPU 开销 | 低 | 中高（读屏障 5~15%） | 高（写屏障 15~30%） |
| 停顿保证 | 软保证 | **硬保证 < 1ms** | 硬保证 < 10ms |
| 架构支持 | 全平台 | x86 + ARM | **仅 x86** |
| 吞吐量影响 | 基准 | 下降 10~20% | 下降 15~30% |

### 这么多缺点，为什么还要升级？

**因为“缺点”是相对的，关键看你的痛点是什么。** 每个新 GC 解决的都是上一代的致命问题。

**CMS → G1**：
- 代价：多占 10~20% 内存
- 收益：无碎片 + 停顿可预测
- **值**：内存便宜，稳定性无价

**G1 → ZGC**：
- 代价：多花 10~15% CPU
- 收益：停顿从 100ms 降到 < 1ms，与堆大小无关
- **值**（低延迟场景）：确定性延迟比 CPU 利用率重要

**GC 调优的“不可能三角”**：低停顿、高吞吐、低内存只能选两个。
- G1：低停顿 + 高吞吐（代价：内存）
- ZGC：极低停顿 + 低内存（代价：吞吐）
- Parallel：高吞吐 + 低内存（代价：停顿）

**什么时候不值得升级？**
- 堆 < 4GB，停顿不敏感 → Parallel GC 就够
- CPU 已经打满（>80%）→ 不要上 ZGC
- JDK 8 老项目短期不升级 → 继续用 CMS/G1

---

## 十二、垃圾回收面试题集（深度版）

### 1. 如何判断对象是否死亡（两种方法）

**引用计数法**：每个对象维护计数器，被引用 +1，断开 -1，为 0 时死亡。优点是简单高效，缺点是**无法解决循环引用**。Python 用引用计数 + 循环检测，Java 不使用。

**可达性分析（Java 采用）**：从 GC Roots 出发沿引用链搜索，不可达的对象可回收。**天然解决循环引用**——即使 A 和 B 互引用，只要都无法从 GC Roots 到达就会被回收。Java 选择可达性分析是因为对象图复杂、循环引用极其常见。

### 2. 四种引用类型及软引用的好处

- **强引用**：GC 绝不回收，是内存泄漏的根源（静态 Map 不 remove、监听器不注销）
- **软引用**：内存不足时才回收。好处是**实现内存敏感缓存而不 OOM**，无需手动管理缓存大小
- **弱引用**：下次 GC 必回收，用于 ThreadLocal key、WeakHashMap、监听器注册
- **虚引用**：`get()` 永远返回 null，唯一用途是**跟踪对象被回收的时机**（如 DirectByteBuffer 释放堆外内存）

### 3. 如何判断常量是废弃常量

常量池中的常量如果没有被任何存活对象、类、ClassLoader 引用，就会被标记为可回收。类卸载时会连带卸载其运行时常量池。JDK 1.7+ 常量池在堆中，废弃常量可被 Minor GC 回收。

### 4. 如何判断类是无用的类

同时满足三个条件：① 该类所有实例已被回收 ② 加载该类的 ClassLoader 已被回收 ③ 该类的 Class 对象没有被任何地方引用。缺一不可。热部署场景下旧 ClassLoader 未释放会导致类无法卸载，最终 OOM: Metaspace。

### 5. 垃圾收集算法及特点

- **标记-清除**：简单但有碎片（CMS）
- **标记-复制**：无碎片、分配快，但浪费空间（新生代，优化为 Eden:S0:S1 = 8:1:1）
- **标记-整理**：无碎片不浪费，但移动对象开销大（老年代）
- **分代收集**：组合使用，新生代用复制，老年代用整理/清除（HotSpot 实际采用）

### 6. 为什么要分新生代和老年代

基于**弱代假说**：>98% 对象朝生夕灭。新生代用复制算法（存活率低，复制少量存活对象即可）；老年代用标记-整理（存活率高，复制成本太高）。不分代的话 GC 必须扫描整个堆，效率远不如分代。

### 7. 常见垃圾回收器

Serial（单线程/新生代）、ParNew（多线程/新生代）、Parallel Scavenge（吞吐量优先/新生代）、Serial Old（单线程/老年代）、CMS（低停顿/老年代/JDK 17 移除）、Parallel Old（多线程/老年代）、G1（通用默认/整堆）、ZGC（<1ms 延迟/整堆）、Shenandoah（低延迟/x86）

### 8. CMS 和 G1 的区别

CMS 只管老年代，需配合 ParNew 管新生代；G1 管理整个堆。CMS 有碎片（标记-清除），G1 无碎片（Region 内复制）。CMS 停顿不可控，G1 停顿可预测（MaxGCPauseMillis）。CMS 内存开销低，G1 有 RSet 开销 10~20%。

### 9. Minor GC 和 Full GC 的区别

Minor GC 只回收新生代（Eden+Survivor），速度快、频率高、停顿短。Full GC 回收整个堆+元空间，速度慢、频率低、停顿长。触发 Full GC 的场景：老年代不足、元空间不足、System.gc()、CMS Concurrent Mode Failure、G1 Mixed GC 跟不上。

---

## 一句话总结

> JDK 1.8 的 GC 核心是**分代收集**：新生代用复制算法（快速回收短命对象），老年代用标记-清除/整理（处理长命对象）。CMS 追求低停顿，G1 追求停顿时间可预测，生产环境推荐 G1 + `-XX:MaxGCPauseMillis=200`。调优的核心是**平衡吞吐量、停顿时间和内存占用**这三个互相制约的指标。
