# DCL 双重检查锁定单例详解

> DCL（Double-Checked Locking）是 Java 并发编程中最经典的单例实现方式。但要写对 DCL，必须理解 `volatile` 为什么不可或缺——这涉及到 JMM 的指令重排序和内存屏障。

---

## 一、DCL 代码

```java
class Singleton {
    private static volatile Singleton instance;  // ← 必须加 volatile

    public static Singleton getInstance() {
        if (instance == null) {                  // 第一次检查（synchronized 外）
            synchronized (Singleton.class) {
                if (instance == null) {          // 第二次检查（synchronized 内）
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

---

## 二、为什么需要两次检查？

### 第一次检查（synchronized 外）

```java
if (instance == null) {  // 第一次检查
```

**目的：避免每次都加锁，提升性能。**

单例创建完成后，`instance` 永远不为 null，后续所有调用都在这一步直接返回，不进入 synchronized 块。

```
99% 的调用路径：
  if (instance == null) → false → 直接 return instance
  完全不需要加锁 ✅
```

### 第二次检查（synchronized 内）

```java
synchronized (Singleton.class) {
    if (instance == null) {  // 第二次检查
```

**目的：防止两个线程同时通过第一次检查后，都创建实例。**

```
没有第二次检查时：
  线程 A：第一次检查 → null → 进入 synchronized → 创建实例
  线程 B：第一次检查 → null（A 还没创建完）→ 等待锁
  线程 A：创建完成，释放锁
  线程 B：获得锁 → 又创建了一个实例！💀 破坏了单例

有第二次检查时：
  线程 A：第一次检查 → null → 进入 synchronized → 第二次检查 → null → 创建实例
  线程 B：第一次检查 → null → 等待锁
  线程 A：创建完成，释放锁
  线程 B：获得锁 → 第二次检查 → instance != null → 不创建 ✅
```

---

## 三、`new Singleton()` 不是原子操作

### 完整字节码拆解

```java
instance = new Singleton();
```

编译后的字节码：

```
0: new           #2    // ① 检查类是否已加载
                        // ② 在堆上分配内存
                        // ③ 零值初始化（字段全为 0/null）
                        // ④ 设置对象头（Mark Word + Klass Pointer）
4: dup                  // ⑤ 复制栈顶引用（一份给 <init>，一份给 putstatic）
5: invokespecial #3     // ⑥ 调用构造方法（执行字段赋值 + 构造方法体）
8: putstatic      #4    // ⑦ 把引用赋给 instance
```

共 4 条指令、7 个内部步骤。

### 重排序视角的三步简化

从**重排序**的角度看，真正关键的是三个有**内存写入效果**的步骤：

| 字节码指令 | 完整步骤 | 重排序视角 |
|-----------|---------|-----------|
| `new` | ① 类加载检查 | **步骤 A**（分配内存） |
| `new` | ② 分配堆内存 | **步骤 A**（分配内存） |
| `new` | ③ 零值初始化 | **步骤 A**（分配内存） |
| `new` | ④ 设置对象头 | **步骤 A**（分配内存） |
| `dup` | ⑤ 复制栈顶引用 | 栈操作，不参与重排序 |
| `invokespecial` | ⑥ 调用构造方法 | **步骤 B**（初始化对象） |
| `putstatic` | ⑦ 赋值给 instance | **步骤 C**（引用赋值） |

```
步骤 A：new           → 在堆上分配内存（对象存在了，但字段全是零值）
步骤 B：invokespecial → 调用构造方法，给字段赋真实值
步骤 C：putstatic     → 把对象地址赋给 instance
```

### 哪些步骤之间可以重排序？

```
A 内部（①②③④）：不能重排序
  → JVM 底层固定流程，有严格的先后依赖

A 和 B 之间：不能重排序
  → B 依赖 A 的结果（构造方法要在已分配的内存上执行）

A 和 C 之间：不能重排序
  → C 依赖 A 的结果（需要 A 返回的对象地址）

B 和 C 之间：可以重排序！ ← DCL 问题的全部来源
  → B 是写堆内存（初始化字段）
  → C 是写静态变量（instance = 地址）
  → 两者写的是不同的内存位置，没有数据依赖
  → 编译器/CPU 可以重排为 C → B
```

---

## 四、不加 volatile 会发生什么？

### 重排序场景

```
线程 1（在 synchronized 内）              线程 2（在 synchronized 外）
────────────────────                    ────────────────────
A: 分配内存
C: instance = 地址 ← 引用已有值！
                                       if (instance == null) → false
                                       return instance
                                       → 拿到字段全是零值的半初始化对象 💥
B: 初始化对象（还没执行）
```

### synchronized 为什么管不住？

**synchronized 的内存屏障只保证"退出时所有写操作刷回主存"，不管块内部操作的顺序。**

```
synchronized 块内：
  A: 分配内存           ← 普通写
  C: instance = 地址    ← 普通写（被重排到 B 前面了）
  B: 初始化对象          ← 普通写

monitorexit 时的屏障：
  → 把 A、C、B 的结果全部刷回主存 ✅
  → 但 A、C、B 之间的顺序已经是错的了！

更关键的问题：
  线程 2 的第一次检查在 synchronized 外面
  → 根本没有进入 synchronized 块
  → 没有任何内存屏障保护
  → 直接读到了半初始化对象
```

### 为什么 synchronized 的屏障不能保护外部的读取？

```
synchronized 屏障的保护范围：

  monitorenter（加锁）时：
    [LoadLoad + LoadStore] 屏障
    → 保证进入 synchronized 后能看到最新数据

  monitorexit（解锁）时：
    [StoreStore + StoreLoad] 屏障
    → 保证退出 synchronized 前数据刷回主存

  但：线程 2 的 if (instance == null) 在 synchronized 外面
      → 不经过 monitorenter
      → 没有 LoadLoad 屏障
      → 可以看到 instance 的中间状态
```

---

## 五、加了 volatile 为什么就解决了？

### volatile 的屏障插入规则

`instance` 被声明为 `volatile`，所以 `instance = 地址`（步骤 C）是 **volatile 写**。

volatile 写的屏障规则是固定的：

```
volatile 写之前 → 插入 [StoreStore] 屏障
volatile 写之后 → 插入 [StoreLoad] 屏障
```

### 屏障插入后的实际执行顺序

```
A: new（分配内存）           ← 普通写
B: <init>（初始化对象）      ← 普通写

┌─────────────────────────────────────────────────┐
│ StoreStore 屏障                                  │
│                                                 │
│ 语义：前面的 A 和 B 必须全部完成并刷出             │
│       后面的 C 才能执行                           │
│                                                 │
│ x86 实现：lock addl $0, 0(%rsp)                 │
│ 效果：Store Buffer 全部刷入 L1 缓存               │
└─────────────────────────────────────────────────┘

C: instance = 地址           ← volatile 写

┌─────────────────────────────────────────────────┐
│ StoreLoad 屏障                                   │
│                                                 │
│ 语义：C 的结果必须对后续的读操作可见                │
└─────────────────────────────────────────────────┘
```

### 为什么这样就安全了？

```
加了 volatile 后，编译器/CPU 必须保证：

  A（分配内存）一定在 C（引用赋值）之前 ✅
  B（初始化对象）一定在 C（引用赋值）之前 ✅

  因为 StoreStore 屏障强制 A 和 B 在 C 之前完成

  → C 执行时，对象一定已经初始化完毕
  → 线程 2 看到 instance != null 时，对象一定是完整的 ✅
```

---

## 六、完整流程对比

### 不加 volatile（错误）

```
线程 1                              线程 2
────────                            ────────
A: 分配内存
                                    if (instance == null) → true（还没赋值）
C: instance = 地址 ← 被重排到 B 前面
                                    if (instance == null) → false
                                    return instance → 半初始化对象 💥
B: 初始化对象
```

### 加了 volatile（正确）

```
线程 1                              线程 2
────────                            ────────
A: 分配内存
B: 初始化对象
── StoreStore 屏障 ──
C: instance = 地址
                                    if (instance == null) → false
                                    return instance → 完整对象 ✅
```

---

## 七、常见追问

### Q1：可以把 synchronized 去掉，只用 volatile 吗？

```java
// ❌ 错误：没有 synchronized
if (instance == null) {
    instance = new Singleton();  // 两个线程可能同时创建
}
```

不行。volatile 只保证可见性和有序性，不保证原子性。两个线程可能同时通过 `instance == null` 检查，各自创建一个实例。

### Q2：可以用静态内部类代替 DCL 吗？

```java
class Singleton {
    private Singleton() {}

    private static class Holder {
        static final Singleton INSTANCE = new Singleton();
    }

    public static Singleton getInstance() {
        return Holder.INSTANCE;
    }
}
```

可以。这是**推荐写法**。利用类加载机制保证线程安全，不需要 synchronized 和 volatile。

原理：JVM 保证类的静态字段只初始化一次，且类加载过程是线程安全的。`Holder` 类只有在第一次调用 `getInstance()` 时才被加载（懒加载）。

### Q3：可以用枚举实现单例吗？

```java
enum Singleton {
    INSTANCE;
}
```

可以，而且是**最安全**的写法。天然防止反射攻击和反序列化破坏单例。Effective Java 作者推荐的方式。

---

## 总结

### 三句话记住 DCL

```
1. DCL 的问题不在 synchronized，在于第一次检查在 synchronized 外面，
   没有任何屏障保护，可能读到半初始化对象。

2. new Singleton() 的字节码分解为 7 个步骤，从重排序视角简化为
   A（分配内存）→ B（初始化）→ C（引用赋值），
   其中 B 和 C 没有数据依赖，可以被重排序为 C → B。

3. volatile 通过在"引用赋值"（C）之前插入 StoreStore 屏障，
   强制"初始化"（B）必须在"引用赋值"（C）之前完成，
   从根本上杜绝了半初始化问题。
```

### DCL 单例的正确写法

```java
class Singleton {
    // ① 必须 volatile：防止 B 和 C 重排序
    private static volatile Singleton instance;

    private Singleton() {}

    public static Singleton getInstance() {
        // ② 第一次检查：避免不必要的加锁
        if (instance == null) {
            // ③ synchronized：保证只创建一个实例
            synchronized (Singleton.class) {
                // ④ 第二次检查：防止重复创建
                if (instance == null) {
                    // ⑤ volatile 写：StoreStore 屏障保证初始化先于引用赋值
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

> **面试话术**：DCL 中 `instance = new Singleton()` 在字节码层面分解为 `new`（分配内存+零值初始化+设置对象头）→ `dup`（栈操作）→ `invokespecial`（构造方法）→ `putstatic`（引用赋值）。从重排序视角简化为三步：分配（A）、初始化（B）、引用赋值（C）。B 和 C 没有数据依赖，可被重排序为 A→C→B。synchronized 的内存屏障只保证退出时数据刷回主存，不禁止块内部重排序，且第一次检查在 synchronized 外面，没有屏障保护。volatile 通过在引用赋值（C）之前插入 StoreStore 屏障，强制初始化（B）必须在引用赋值（C）之前完成，从根本上解决半初始化问题。
