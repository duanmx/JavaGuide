# Java 类文件结构详解（JDK 1.8）

---

## 一、Class 文件概述

```
Class 文件是 Java 字节码的二进制存储格式
  - 由 javac 编译器生成（.java → .class）
  - 与平台无关（任何 JVM 都能执行）
  - 与语言无关（Kotlin、Scala、Groovy 都编译为 .class）

查看 Class 文件：
  javap -v Demo.class        # 查看完整结构
  javap -c Demo.class        # 只看字节码指令
  hexdump -C Demo.class      # 查看原始十六进制
```

---

## 二、Class 文件整体结构

```
Class 文件 = 一组 8 位字节为基础的二进制流

┌──────────────────────────────────────────┐
│ ① 魔数（Magic Number）                    │  4 字节
├──────────────────────────────────────────┤
│ ② 版本号（Version）                       │  4 字节
├──────────────────────────────────────────┤
│ ③ 常量池（Constant Pool）                 │  变长
├──────────────────────────────────────────┤
│ ④ 访问标志（Access Flags）                │  2 字节
├──────────────────────────────────────────┤
│ ⑤ 当前类索引（This Class）                │  2 字节
├──────────────────────────────────────────┤
│ ⑥ 父类索引（Super Class）                 │  2 字节
├──────────────────────────────────────────┤
│ ⑦ 接口索引集合（Interfaces）              │  变长
├──────────────────────────────────────────┤
│ ⑧ 字段表集合（Fields）                    │  变长
├──────────────────────────────────────────┤
│ ⑨ 方法表集合（Methods）                   │  变长
├──────────────────────────────────────────┤
│ ⑩ 属性表集合（Attributes）                │  变长
└──────────────────────────────────────────┘
```

---

## 三、各部分详解

### ① 魔数（Magic Number）— 4 字节

```
固定值：0xCAFEBABE

作用：标识这是一个 Java Class 文件
  → JVM 加载时首先检查魔数
  → 如果不是 CAFEBABE，直接拒绝加载

为什么是 CAFEBABE？
  Java 团队喜欢咖啡（Java = 咖啡），Cafe = 咖啡馆，Babe = 宝贝
  同时 0xCAFE 在十六进制中容易辨识
```

### ② 版本号（Version）— 4 字节

```
高 2 字节：次版本号（Minor Version）
低 2 字节：主版本号（Major Version）

JDK 版本与主版本号的对应关系：
  JDK 1.1 → 45
  JDK 1.2 → 46
  ...
  JDK 1.8 → 52
  JDK 9   → 53
  JDK 11  → 55
  JDK 17  → 61
  JDK 21  → 65

例如 JDK 1.8 编译的 Class：
  Minor = 0x0000（0）
  Major = 0x0034（52）
  → 高版本 JVM 可以运行低版本 Class（向下兼容）
  → 低版本 JVM 不能运行高版本 Class（UnsupportedClassVersionError）
```

### ③ 常量池（Constant Pool）— 变长

```
常量池是 Class 文件的核心数据结构
存储编译时确定的所有常量

结构：
  constant_pool_count    // 常量池数量（实际数量 = count - 1）
  cp_info[]              // 常量池条目数组
```

**常量池条目类型（JDK 1.8 共 14 种）**：

| 类型 | 标记值 | 说明 |
|------|--------|------|
| `CONSTANT_Utf8` | 1 | UTF-8 字符串 |
| `CONSTANT_Integer` | 3 | int 常量 |
| `CONSTANT_Float` | 4 | float 常量 |
| `CONSTANT_Long` | 5 | long 常量 |
| `CONSTANT_Double` | 6 | double 常量 |
| `CONSTANT_Class` | 7 | 类引用 |
| `CONSTANT_String` | 8 | 字符串引用 |
| `CONSTANT_Fieldref` | 9 | 字段引用 |
| `CONSTANT_Methodref` | 10 | 方法引用 |
| `CONSTANT_InterfaceMethodref` | 11 | 接口方法引用 |
| `CONSTANT_NameAndType` | 12 | 名称+描述符 |
| `CONSTANT_MethodHandle` | 15 | 方法句柄 |
| `CONSTANT_MethodType` | 16 | 方法类型 |
| `CONSTANT_InvokeDynamic` | 18 | 动态调用 |

```
注意：
  - Long 和 Double 占两个位置（index n 和 n+1）
  - 所以常量池实际条目数 = constant_pool_count - 1
```

#### 三个“常量池”的区别

很多面试者混淆，实际上有三个不同层次的概念：

| | Class 文件常量池 | 运行时常量池 | 字符串常量池 |
|--|----------------|------------|------------|
| **位置** | 磁盘（.class 文件） | 元空间（本地内存） | 堆（JVM 内存） |
| **数量** | 每个 class 一份 | 每个类一份 | 全局唯一 |
| **内容** | 字面量 + 符号引用 | Utf8 字节 + 索引 | String 对象 |
| **动态性** | 静态（编译时确定） | 可动态添加 | 可通过 intern() 添加 |
| **生命周期** | 随文件存在 | 随类卸载而回收 | 随 GC 回收 |

三者的关系：
```
编译时                         类加载时                        运行时
┌─────────────┐            ┌─────────────┐            ┌─────────────┐
│ Demo.class  │  加载到     │ 运行时常量池 │  ldc 指令   │ 字符串常量池 │
│ 中的常量池  │ ────────→  │ （元空间）   │ ────────→  │ （堆中）    │
│             │            │             │            │             │
│ #5 String   │            │ #5 String   │  创建/查找  │ "hello"     │
│   → #29     │            │   → #29     │  String对象 │ String 对象 │
│ #29 Utf8    │            │ #29 Utf8    │            │             │
│  "hello"    │            │  "hello"    │            │             │
└─────────────┘            └─────────────┘            └─────────────┘
  静态的（磁盘）             动态的（元空间）            Java 对象（堆）
  每个 class 一份           每个类一份                全局唯一
```

执行 `String s = "hello"` 时：
1. 在运行时常量池中找到 Utf8 "hello" 的字节数据
2. 用这些字节去堆的字符串常量池中查找/创建 String 对象
3. 将 String 对象的引用赋给 s

> 元空间存的是“描述信息”（原始字节），堆里存的是“真正的对象”（String 实例）。

### ④ 访问标志（Access Flags）— 2 字节

```
描述类或接口的访问权限

标志位：
  ACC_PUBLIC       0x0001  public
  ACC_FINAL        0x0010  final
  ACC_SUPER        0x0020  允许 invokespecial 语义
  ACC_INTERFACE    0x0200  接口
  ACC_ABSTRACT     0x0400  abstract
  ACC_SYNTHETIC    0x1000  编译器生成
  ACC_ANNOTATION   0x2000  注解类型
  ACC_ENUM         0x4000  枚举类型

例如：public final class Demo
  → ACC_PUBLIC | ACC_FINAL = 0x0001 | 0x0010 = 0x0011
```

### ⑤ 当前类索引（This Class）— 2 字节

```
指向常量池中 CONSTANT_Class 类型的条目
表示当前类的全限定名

例如：com/example/Demo
  → This Class = #3（指向常量池 #3 号条目）
  → #3 = CONSTANT_Class → #27
  → #27 = CONSTANT_Utf8 "com/example/Demo"
```

### ⑥ 父类索引（Super Class）— 2 字节

```
指向常量池中 CONSTANT_Class 类型的条目
表示父类的全限定名

特殊情况：
  - java.lang.Object 的 Super Class = 0（没有父类）
  - 接口的 Super Class = java.lang.Object
  - 所有类最终都继承自 java.lang.Object
```

### ⑦ 接口索引集合（Interfaces）— 变长

```
结构：
  interfaces_count     // 接口数量
  interfaces[]         // 接口索引数组

每个接口索引指向常量池中的 CONSTANT_Class 条目
记录当前类实现的所有接口
```

### ⑧ 字段表集合（Fields）— 变长

```
描述类中声明的所有字段（不包括方法内的局部变量）

结构：
  fields_count         // 字段数量
  field_info[]         // 字段信息数组

每个 field_info 包含：
  ┌────────────────────────────────────┐
  │ access_flags    │ 访问标志          │  2 字节
  │ name_index      │ 字段名（常量池索引）│ 2 字节
  │ descriptor_index│ 描述符（常量池索引）│ 2 字节
  │ attributes_count│ 属性数量          │  2 字节
  │ attributes[]    │ 属性表            │  变长
  └────────────────────────────────────┘

字段描述符（Descriptor）：
  B → byte       C → char
  D → double     F → float
  I → int        J → long
  S → short      Z → boolean
  Lxxx; → 对象类型（如 Ljava/lang/String;）
  [xxx → 数组类型（如 [I 表示 int[]）
```

### ⑨ 方法表集合（Methods）— 变长

```
描述类中声明的所有方法（包括构造方法 <init> 和静态初始化 <clinit>）

结构：
  methods_count        // 方法数量
  method_info[]        // 方法信息数组

每个 method_info 包含：
  ┌────────────────────────────────────┐
  │ access_flags    │ 访问标志          │  2 字节
  │ name_index      │ 方法名（常量池索引）│ 2 字节
  │ descriptor_index│ 描述符（常量池索引）│ 2 字节
  │ attributes_count│ 属性数量          │  2 字节
  │ attributes[]    │ 属性表            │  变长
  └────────────────────────────────────┘

方法描述符示例：
  int add(int a, int b)       → (II)I
  void setName(String name)   → (Ljava/lang/String;)V
  String[] getNames()         → ()[Ljava/lang/String;
  long calculate(double x)    → (D)J
```

### ⑩ 属性表集合（Attributes）— 变长

```
Class 文件、字段、方法中都可以携带属性
属性是可扩展的，JVM 不认识的属性会直接忽略
```

#### 方法表 vs 属性表的区别

**方法表是“目录”（有哪些方法，叫什么名字，什么参数），属性表是“内容”（方法具体怎么执行）。属性表不是独立的一级结构，它挂在方法表、字段表、Class 文件下面。**

```
Class 文件
├── 字段表（Fields）
│   └── 每个字段有自己的属性表 ← 附属
├── 方法表（Methods）
│   └── 每个方法有自己的属性表 ← 附属
└── 属性表（Attributes）← Class 级别的属性
```

| | 方法表（Methods） | 属性表（Attributes） |
|--|------------------|---------------------|
| **描述什么** | 类有哪些方法 | 方法/字段/类的附加信息 |
| **包含内容** | 方法名、参数类型、返回值类型、访问权限 | 字节码指令、行号表、异常表等 |
| **层级** | Class 文件的一级结构 | 挂在方法/字段/Class 下的附属结构 |
| **最重要** | 方法的签名（名称+描述符） | **Code 属性**（字节码指令） |

#### 常见属性

```
Class 级别属性：
  SourceFile          → 源文件名（如 Demo.java）
  InnerClasses        → 内部类信息
  Signature           → 泛型签名
  BootstrapMethods    → 引导方法（invokedynamic 用）

方法级别属性：
  Code                → 字节码指令（最重要的属性）
  LineNumberTable     → 行号表（用于调试和异常堆栈）
  LocalVariableTable  → 局部变量表（用于调试）
  Exceptions          → 声明抛出的异常
  RuntimeVisibleAnnotations → 运行时可见注解

字段级别属性：
  ConstantValue       → final 常量的值
```

---

## 四、Code 属性详解（字节码指令）

```
Code 属性是方法表中最核心的属性
存储方法的字节码指令

Code 属性结构：
  ┌────────────────────────────────────┐
  │ max_stack       │ 操作数栈最大深度  │  2 字节
  │ max_locals      │ 局部变量表最大槽数│  2 字节
  │ code_length     │ 字节码长度       │  4 字节
  │ code[]          │ 字节码指令数组    │  变长
  │ exception_table_length│ 异常表长度  │  2 字节
  │ exception_table[]│ 异常表           │  变长
  │ attributes_count│ 属性数量          │  2 字节
  │ attributes[]    │ 属性（如行号表）  │  变长
  └────────────────────────────────────┘

常见字节码指令：
  加载/存储：iload、istore、aload、astore、ldc、bipush
  算术运算：iadd、isub、imul、idiv
  类型转换：i2l、i2f、checkcast、instanceof
  对象操作：new、getfield、putfield、invokevirtual
  控制转移：ifeq、ifne、goto、tableswitch
  方法调用：invokestatic、invokevirtual、invokeinterface、invokedynamic
  返回：ireturn、areturn、return
```

---

## 五、完整 Class 文件示例

```java
public class Demo {
    private int age = 25;

    public int getAge() {
        return age;
    }
}
```

```
javap -v Demo.class 输出：

Classfile Demo.class
  Compiled from "Demo.java"
public class Demo
  minor version: 0
  major version: 52                    ← JDK 1.8
  flags: ACC_PUBLIC, ACC_SUPER

Constant pool:
   #1 = Methodref    #4.#20            // java/lang/Object."<init>":()V
   #2 = Fieldref     #3.#21            // Demo.age:I
   #3 = Class        #22               // Demo
   #4 = Class        #23               // java/lang/Object
   #5 = Utf8         age
   #6 = Utf8         I
  ...
  #22 = Utf8         Demo
  #23 = Utf8         java/lang/Object

{
  public Demo();                       ← 编译器自动生成的默认构造方法
    descriptor: ()V
    flags: ACC_PUBLIC
    Code:
      stack=2, locals=1
       0: aload_0
       1: invokespecial #1             // Object."<init>"
       4: aload_0
       5: bipush        25
       7: putfield      #2             // this.age = 25
      10: return

  public int getAge();
    descriptor: ()I
    flags: ACC_PUBLIC
    Code:
      stack=1, locals=1
       0: aload_0
       1: getfield      #2             // this.age
       4: ireturn
}
```

---

## 六、Class 文件加载到 JVM 的过程

```
Demo.class 文件（磁盘）
    ↓ ClassLoader 加载
字节流读入内存
    ↓ 验证魔数和版本号
解析常量池 → 运行时常量池（元空间）
    ↓ 解析类结构
创建 java.lang.Class 对象（堆中）
    ↓ 解析字段和方法
创建字段和方法的元数据（元空间）
    ↓ 准备阶段
静态变量分配内存并设零值（堆中）
    ↓ 解析阶段
符号引用 → 直接引用
    ↓ 初始化阶段
执行 <clinit>（静态变量赋值 + 静态代码块）
    ↓
类加载完成，可以 new 对象
```

---

## 七、一句话总结

> Class 文件是以 **0xCAFEBABE** 开头的二进制文件，核心结构为：**魔数 → 版本号 → 常量池 → 访问标志 → 类/父类/接口索引 → 字段表 → 方法表 → 属性表**。常量池存储编译时确定的所有字面量和符号引用，方法表中的 Code 属性存储字节码指令。Class 文件由 ClassLoader 加载后，常量池变为运行时常量池（元空间），Class 对象创建在堆中。
