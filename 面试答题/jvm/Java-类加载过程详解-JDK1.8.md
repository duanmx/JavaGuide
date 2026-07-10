# Java 类加载过程详解（JDK 1.8）

---

## 一、类加载概述

```
类加载 = 将 .class 文件加载到 JVM 内存，并使其可用的全过程

触发类加载的时机（5 种）：
  ① new 对象、访问静态变量、调用静态方法
  ② 反射调用（Class.forName）
  ③ 加载子类时先加载父类
  ④ JVM 启动时的主类（main 方法所在类）
  ⑤ invokedynamic 指令引用的类

类加载的完整生命周期：
  加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载
  ├──────── 连接（Linking）────────┤
```

---

## 二、加载（Loading）

```
加载阶段做什么：
  ① 通过类的全限定名获取二进制字节流（从 .class 文件、JAR、网络等）
  ② 将字节流转化为方法区的运行时数据结构（运行时常量池、类元信息）
  ③ 在堆中创建 java.lang.Class 对象（作为该类的访问入口）

加载来源：
  - 本地 .class 文件
  - JAR/WAR 包中的 .class
  - 网络下载（如 Applet）
  - 动态生成（CGLIB、ASM 字节码生成）
  - 数据库中读取

加载完成后：
  元空间：类的元信息、运行时常量池
  堆：Class 对象（可以通过 Class 对象访问类的所有信息）
```

---

## 三、验证（Verification）

```
验证阶段：确保 Class 文件的字节码是合法的、安全的

四个子阶段：

① 文件格式验证
  - 魔数是否是 0xCAFEBABE
  - 版本号是否在可接受范围内
  - 常量池中的条目类型是否合法

② 元数据验证
  - 类是否有父类（除了 Object 都必须有）
  - 是否继承了 final 类
  - 抽象方法是否都被实现

③ 字节码验证
  - 操作数栈和局部变量表是否匹配
  - 跳转指令是否跳转到合法位置
  - 类型转换是否安全

④ 符号引用验证
  - 符号引用是否能正确解析为直接引用
  - 访问权限是否合法

验证失败：抛出 VerifyError
  → 恶意篡改的 .class 文件会被拦截
  → 编译器和 JVM 版本不匹配时会报错
```

---

## 四、准备（Preparation）

```
准备阶段：为类的静态变量分配内存并设置零值

注意：只处理静态变量（static），不处理实例变量

  static int a = 100;        → a = 0（零值，不是 100）
  static final int b = 100;  → b = 100（编译期常量直接赋值）
  int c = 200;               → 不处理（实例变量在 new 时初始化）

为什么 static int a 不是 100？
  因为 a = 100 是在初始化阶段（<clinit>）执行的
  准备阶段只是"分配内存 + 设零值"
  真正的赋值要等到初始化阶段

特殊：static final 编译期常量
  static final int b = 100;
  → b 在编译时就是确定的常量
  → 准备阶段直接赋值为 100（不经过零值）
```

---

## 五、解析（Resolution）

```
解析阶段：将符号引用替换为直接引用

符号引用（Symbolic Reference）：
  - 字符串形式的引用（类名、方法名、字段名）
  - 在常量池中（如 CONSTANT_Class、CONSTANT_Methodref）
  - 不依赖内存布局，任何 JVM 都能理解

直接引用（Direct Reference）：
  - 内存中的实际地址（指针或偏移量）
  - 与具体的 JVM 内存布局相关
  - 可以直接定位到目标

解析过程：
  CONSTANT_Class "com/example/User"
    ↓ 解析
  0x00FF（User 类在元空间中的实际地址）

  CONSTANT_Methodref "User.getName"
    ↓ 解析
  0x00AA（getName 方法的入口地址）

解析时机：
  - 可以在类加载时一次性解析（静态解析）
  - 也可以在第一次使用时才解析（延迟解析，HotSpot 默认）
```

---

## 六、初始化（Initialization）— 最重要的一步

```
初始化阶段：执行类的静态变量赋值和静态代码块
即执行 <clinit>() 方法

<clinit> 方法：
  - 编译器自动生成
  - 包含所有 static 变量的赋值语句
  - 包含所有 static {} 代码块
  - 按照源码书写顺序执行
```

### <clinit> 执行顺序示例

```java
public class Demo {
    static int a = 1;          // ① 进入 <clinit>
    static { b = 2; }          // ② 进入 <clinit>
    static int b = 3;          // ③ 进入 <clinit>（覆盖 ② 的值）

    public static void main(String[] args) {
        System.out.println(b); // 输出 3（不是 2）
    }
}
```

### 触发初始化的时机

```
首次主动使用类时触发：
  ① new 对象
  ② 读取/设置静态变量（非 final 编译期常量）
  ③ 调用静态方法
  ④ 反射调用（Class.forName）
  ⑤ 初始化子类时先初始化父类
  ⑥ main 方法所在类

不会触发初始化的情况（被动引用）：
  ① 通过子类引用父类的静态变量 → 只初始化父类
  ② 通过数组定义引用类 → 不触发初始化
     Demo[] arr = new Demo[10];  // 不触发 Demo 的 <clinit>
  ③ 引用编译期常量 → 不触发初始化
     int x = Demo.MAX;  // MAX 是 static final 编译期常量
```

### <clinit> 的线程安全

```
JVM 保证 <clinit> 在多线程环境下只执行一次
  → 通过加锁机制保证
  → 如果一个线程正在执行 <clinit>，其他线程会阻塞等待
  → 这也是"饿汉式单例"线程安全的原因

死锁风险：
  如果两个类的 <clinit> 互相引用，可能死锁
  class A { static { new B(); } }
  class B { static { new A(); } }
  → A 初始化时触发 B 初始化，B 初始化时触发 A 初始化 → 死锁
```

---

## 七、类加载器（ClassLoader）

```
类加载器负责"加载"阶段（第一步）

三种内置类加载器：

┌─────────────────────────────────────────────────────┐
│ 类加载器              │ 加载范围                      │
├─────────────────────────────────────────────────────┤
│ Bootstrap ClassLoader │ rt.jar（java.lang.* 等核心类）│
│ （C++ 实现）          │ JDK 内部类                    │
├─────────────────────────────────────────────────────┤
│ Extension ClassLoader │ ext/*.jar（扩展类）           │
│ （ExtClassLoader）    │ jre/lib/ext 目录              │
├─────────────────────────────────────────────────────┤
│ Application ClassLoader│ 用户类路径（classpath）      │
│ （AppClassLoader）    │ 项目中的 .class 文件          │
└─────────────────────────────────────────────────────┘

父子关系：
  Bootstrap ← Extension ← Application
  ↑ 父加载器    ↑ 子加载器

查看类加载器：
  System.out.println(String.class.getClassLoader());
  // null（Bootstrap ClassLoader，C++ 实现，Java 中表示为 null）

  System.out.println(com.sun.crypto.provider.DESKey.class.getClassLoader());
  // ExtClassLoader

  System.out.println(Demo.class.getClassLoader());
  // AppClassLoader
```

---

## 八、双亲委派模型（Parent Delegation Model）

```
双亲委派：类加载请求先委托父加载器，父加载器无法加载时才自己加载

加载流程：
  加载 Demo.class
    ↓
  AppClassLoader 收到请求
    ↓ 委托给父加载器
  ExtClassLoader 收到请求
    ↓ 委托给父加载器
  Bootstrap ClassLoader 收到请求
    ↓ 尝试加载 rt.jar 中的类
    ↓ 找不到 Demo.class
  ExtClassLoader 尝试加载
    ↓ 找不到 Demo.class
  AppClassLoader 尝试加载
    ↓ 在 classpath 中找到 Demo.class
    ↓ 加载成功
```

### 为什么需要双亲委派？

```
① 安全：防止核心类被篡改
   → 自定义 java.lang.String 不会被加载
   → 因为 Bootstrap 会先加载 rt.jar 中的 String

② 避免重复加载：父加载器已加载的类，子加载器不会再加载

③ 保证类的唯一性：同一个类在同一个 ClassLoader 中只有一份
```

### 打破双亲委派的场景

#### ① SPI 机制（JDBC、SLF4J）

**核心矛盾**：接口定义在高层加载器，实现在低层加载器，高层加载器无法加载低层加载器的类。

```
JDBC 的困境：
  java.sql.Connection     → 接口，在 rt.jar 中（Bootstrap 加载）
  java.sql.DriverManager  → 工厂类，在 rt.jar 中（Bootstrap 加载）
  com.mysql.cj.jdbc.Driver→ 实现，在 mysql-connector.jar 中（AppClassLoader 加载）

  DriverManager（Bootstrap）需要加载 Driver 实现类
    ↓ 但 Bootstrap 只能加载 rt.jar
    ↓ 找不到 mysql-connector.jar 中的 com.mysql.cj.jdbc.Driver
    ↓ 父加载器需要用到子加载器才能加载的类 → 违反双亲委派
```

**解决方案：线程上下文类加载器**

```java
// DriverManager 实际源码（简化版）
ClassLoader cl = Thread.currentThread().getContextClassLoader();
// 默认返回 AppClassLoader（应用类加载器）

// 用 AppClassLoader 加载 mysql 驱动
Class<?> driverClass = Class.forName("com.mysql.cj.jdbc.Driver", true, cl);
// AppClassLoader 在 mysql-connector.jar 中找到 → 加载成功 ✅
```

```
正常双亲委派（失败）：
  DriverManager（Bootstrap）
    → Class.forName("com.mysql.cj.jdbc.Driver")
    → Bootstrap 在 rt.jar 中找 → 找不到 → ClassNotFoundException ❌

打破双亲委派（成功）：
  DriverManager（Bootstrap）
    → 获取 Thread.currentThread().getContextClassLoader()
    → 拿到 AppClassLoader（子加载器）
    → 用 AppClassLoader 加载 → 在 mysql-connector.jar 中找到 → 成功 ✅
```

类比理解：CEO（Bootstrap）需要基层员工（AppClassLoader）手上的文件，正常流程拿不到，通过“线程上下文类加载器”这个电话直接向基层员工要。

SLF4J 也是同样的问题：`slf4j-api.jar` 中的 `LoggerFactory` 需要加载 `logback.jar` 中的实现，同样用线程上下文类加载器反向加载。

#### ② OSGi / 热部署

```
同一个类的不同版本需要同时存在
每个模块有自己的 ClassLoader，不委派给父加载器
```

#### ③ Tomcat

```
每个 Web 应用有自己的 ClassLoader
优先加载自己 WEB-INF/classes 下的类
```

---

## 九、类加载的完整时间线

```
① 加载（Loading）
   通过全限定名找到 .class 文件，读入字节流
   → 元空间：运行时常量池、类元信息
   → 堆：Class 对象

② 验证（Verification）
   检查字节码合法性、安全性

③ 准备（Preparation）
   静态变量分配内存 + 设零值
   （static final 编译期常量直接赋值）

④ 解析（Resolution）
   符号引用 → 直接引用

⑤ 初始化（Initialization）
   执行 <clinit>（静态变量赋值 + 静态代码块）
   → 类加载完成，可以使用

⑥ 使用（Using）
   正常运行

⑦ 卸载（Unloading）
   满足三个条件时卸载：
   - 该类所有实例已被回收
   - 加载该类的 ClassLoader 已被回收
   - 该类的 Class 对象没有被任何地方引用
```

---

## 十、自定义类加载器

### 什么时候需要自定义类加载器？

```
① 从非标准来源加载类（数据库、网络、加密文件）
② 热部署/热加载（不重启 JVM 更新代码）
③ 类隔离（同一类的不同版本共存，如 Tomcat）
④ 类的加密/解密（保护源码）
⑤ 动态加载（运行时按需加载，如 OSGi）
```

### 方式一：重写 findClass（推荐，遵循双亲委派）

```java
public class MyClassLoader extends ClassLoader {
    private String classPath;

    public MyClassLoader(String classPath) {
        this.classPath = classPath;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] classData = loadClassData(name);
        if (classData == null) {
            throw new ClassNotFoundException();
        }
        return defineClass(name, classData, 0, classData.length);
    }

    private byte[] loadClassData(String name) {
        String path = classPath + "/" + name.replace(".", "/") + ".class";
        try (InputStream is = new FileInputStream(path);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            int b;
            while ((b = is.read()) != -1) {
                baos.write(b);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}

// 使用
MyClassLoader loader = new MyClassLoader("/tmp/classes");
Class<?> clazz = loader.loadClass("com.example.User");
Object user = clazz.newInstance();
```

### 方式二：重写 loadClass（打破双亲委派）

```java
public class HotDeployClassLoader extends ClassLoader {
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        // 先尝试自己加载（不先委托给父加载器）
        try {
            byte[] data = loadClassData(name);
            if (data != null) {
                return defineClass(name, data, 0, data.length);
            }
        } catch (Exception e) { }
        // 自己加载不了，再委托给父加载器
        return super.loadClass(name);
    }
}
```

### 实战案例：热部署

```java
// 热部署流程：
// 1. 开发者修改代码并编译
// 2. 将新 .class 文件放到 deployDir
// 3. 创建新的 ClassLoader 实例
// 4. 用新 ClassLoader 加载新版本的类
// 5. 旧的 ClassLoader 和旧类会被 GC 回收

HotDeployClassLoader v1 = new HotDeployClassLoader("/deploy/v1");
Class<?> serviceV1 = v1.loadClass("com.example.Service");

HotDeployClassLoader v2 = new HotDeployClassLoader("/deploy/v2");
Class<?> serviceV2 = v2.loadClass("com.example.Service");
// v1 和 v2 是同一个类的不同版本，互不干扰
```

### 注意事项

```
① 重写 findClass vs loadClass
   - findClass：遵循双亲委派，安全（推荐）
   - loadClass：打破双亲委派，灵活但需注意安全

② 类隔离条件（三个条件缺一不可）：
   - 两个 ClassLoader 不同
   - 加载同一个类名
   - 得到两个不同的 Class 对象

③ ClassLoader 泄漏（热部署最常见的内存泄漏）：
   - 如果 ClassLoader 被静态变量/ThreadLocal 引用
   - ClassLoader 无法被 GC 回收
   - 它加载的所有类也无法卸载
   → 最终 OOM: Metaspace
```

---

## 十一、一句话总结

> 类加载过程分为**加载 → 连接（验证 → 准备 → 解析）→ 初始化**五个阶段。加载阶段将 .class 文件读入内存并创建 Class 对象；准备阶段为静态变量分配内存并设零值；初始化阶段执行 `<clinit>` 完成静态变量赋值和静态代码块。类加载器通过**双亲委派模型**保证核心类安全和类的唯一性，SPI 和热部署场景需要打破委派。
