# ConcurrentHashMap原理
## 一、核心基础问题解析
### 1. CHM 1.8 的 Node[] 数组，元素都是 Node 类型吗？还是有不同子类？
CHM 中的 Node[] 数组，元素不全是 Node 类型，还包含 TreeNode、ForwardingNode、ReservationNode、TreeBin 四种子类，因此 CHM 存储数据量变化时，可能带来数据结构的变化。

| **节点类型** | **hash 值** | **含义** |
| --- | --- | --- |
| Node | ≥ 0 | 普通链表节点 |
| TreeBin | -2 | 红黑树的根包装（放在桶里），MOVED = -1 占用 |
| TreeNode | ≥ 0 | 红黑树节点（挂在 TreeBin 下） |
| ForwardingNode | -1（MOVED） | 扩容中标记 |
| ReservationNode | -3（RESERVED） | computeIfAbsent 占位 |


### 2. sizeCtl 字段在不同时机有不同含义，几种含义分别是什么？
sizeCtl 用于表初始化和扩容的控制，不同取值对应不同含义，具体如下：

| **sizeCtl 值** | **含义** |
| --- | --- |
| -1 | 正在初始化 |
| -(1+N) | 正在扩容，N 个线程参与扩容 |
| 0 | 默认值，未初始化（用 DEFAULT_CAPACITY = 16 初始化） |
| 正数，初始化前 | 用户构造时指定的初始容量 |
| 正数，初始化后 | 下次扩容的阈值（table 长度 × 0.75） |


### 3. CounterCell[] 是干嘛的？为什么不直接用一个 int 计数？
CounterCell[] 的核心作用是解决高并发下的计数性能问题，具体原因如下：

+ 若直接使用一个 int 计数，高并发下多个线程同时 put 时，需执行 size.incrementAndGet()，而 AtomicInteger 内部依赖 CAS 循环，会导致大量线程失败并反复重试；
+ 多线程争抢同一个变量，会导致 CPU 缓存行频繁失效（遵循 MESI 协议），引发严重的性能损耗；
+ 基于 LongAddr 思想，JDK 1.8 引入 baseCount + CounterCell[] 组合计数：每个线程进来后，根据自身 hash 路由到不同的 CounterCell 中，最终计算 size 时，通过 baseCount + 所有 CounterCell 元素值求和得到结果，避免了并发争抢。

## 二、putVal 源码相关疑问解析
### 1. 为什么“桶位置为空”用 CAS，但“桶位置有节点”用 synchronized？为什么不全用 CAS？
核心原因是 CAS 与 synchronized 的原子性保障范围不同：

+ 桶位置为空时，仅需执行“插入节点”这一个单步操作，CAS 可保证该单操作的原子性，直接插入即可；
+ 桶位置有节点时，需执行“查找、插入、更新”等多步操作，CAS 仅能保证单步操作原子性，无法保证多步操作的原子性，因此需要用 synchronized 加锁，确保整个操作流程的原子性。

### 2. synchronized 锁的是什么对象？
synchronized 锁的是当前桶的头节点，而非整个桶或全局对象，锁粒度更细，能减少并发竞争。

### 3. Forwarding 节点是什么？它出现意味着什么？
Forwarding 节点（ForwardingNode）是 CHM 扩容过程中的标记节点，其出现意味着当前桶已经迁移完成，CHM 正在进行扩容操作，具体表现为：

+ 线程执行 put 操作时，若发现桶头节点是 Forwarding 节点，会调用 helpTransfer 方法协助扩容；
+ 线程执行 get 操作时，若发现桶头节点是 Forwarding 节点，会调用 ForwardingNode.find 方法，到新的 table 中查询数据。

### 4. 为什么把锁从 ReentrantLock 换成 synchronized？
JDK 1.8 中 CHM 替换锁的核心原因是性能、内存开销和代码简洁性的优化，具体如下：

+ 性能优化：JDK 1.6 后，synchronized 引入轻量锁、偏向锁、自旋锁等优化，在低竞争场景下性能大幅提升，优于 ReentrantLock；
+ 内存开销优化：CHM 1.8 去掉 Segment 锁，改用 Bucket 锁（锁桶头节点），锁粒度更细，竞争不激烈；而 ReentrantLock 需创建大量实例，桶数量较大时会占用大量内存，synchronized 无额外内存开销；
+ 代码简洁性：ReentrantLock 需通过 try-finally 手动加锁、解锁，代码繁琐；synchronized 自动完成加锁、解锁，代码更简洁。

## 三、get 源码相关疑问解析
### 1. get 全程没加任何锁，它怎么保证读到的是“最新写入”的数据？
核心原因是 CHM 中存储数据的 Node 节点，其 value 字段和 next 字段均被 volatile 修饰；volatile 关键字可保证变量的可见性，即一个线程对该变量的更新，会立即对所有其他线程可见，因此 get 操作无需加锁，就能读取到最新写入的数据。

## 四、helpTransfer 与 transfer 源码相关疑问解析
### 1. helpTransfer 中 U.compareAndSetInt(this, SIZECTL, sc, sc+1) 这一行，为什么把 sizeCtl 加 1？
sizeCtl 为负数时，表示 CHM 正在扩容，其值为 -(1 + 参与扩容的线程数)；当新线程调用 helpTransfer 协助扩容时，参与扩容的线程数增加 1，因此需要将 sizeCtl 加 1，更新参与扩容的线程数量。

### 2. transfer 方法相关疑问
#### Q1: stride 是什么？它的作用是什么？
stride 表示每个线程一次认领的桶的数量，其取值和作用如下：

+ 取值：单核 CPU 时，stride = 旧桶的 size；多核 CPU 时，stride 默认值为 16；
+ 作用：平衡线程认领桶的效率与负载： 
    - stride 过大：线程认领桶的次数少，但线程间负载不均衡；
    - stride 过小：线程认领桶的次数多，负载均衡但 CAS 竞争频繁；
    - 默认值 16 是权衡后的阈值，既避免 CAS 竞争过于频繁，也避免单线程负担过重。

#### Q2: 链表分组那段（for (Node<K,V> p = f; ; p = p.next) 那块），它做了一个优化——找到“最后一段连续相同分组的子链”。这个优化的目的是什么？
未找到完全匹配的代码片段，最接近的是 for (Node<K,V> p = f.next; p != null; p = p.next)，暂无法明确该优化的具体目的。

#### Q3: 收尾逻辑：最后一个完成的线程要做什么“特殊处理”？为什么要特殊处理？
最后一个完成扩容的线程，需执行以下特殊处理：

+ 清空 nextTable 字段（nextTable 是扩容时的临时新表）；
+ 将 table 字段引用切换为新表；
+ 将 sizeCtl 改为旧表 size 的 1.5 倍（即下次扩容的阈值）。

特殊处理的原因：确保扩容完成后的数据一致性，清理临时资源，为下一次扩容做好准备，避免出现数据脏读、资源泄露等问题。

## 五、transfer 协助扩容机制核心总结
transfer 是 CHM 1.8 的协助扩容机制，核心思想：多个线程通过 CAS 抢占 transferIndex，认领一段桶进行数据迁移；每个线程的 stride 默认 16，既保证负载均衡，又减少 CAS 竞争。

### 三个关键设计
1. 位运算分组：通过 hash & oldCap 决定节点迁移到新表的低位（原位）或高位（原位 + oldCap），沿用 HashMap 的优化，比 1.7 重新计算 hash 更高效；
2. lastRun 优化：链表迁移时，找到“最后一段连续相同分组”的子链，整段复用该子链，减少新建节点的开销；
3. sizeCtl 状态机：以 resizeStamp + 2 为基础值，每增加一个协助扩容的线程，sizeCtl 加 1；最后一个退出扩容的线程负责收尾工作（切换 table 引用、清空 nextTable、设置新扩容阈值）。

协助扩容的好处：经过的线程都会参与协助扩容，而非堆积等待，这与 AQS 共享模式的“接力唤醒”属于同一设计哲学，提升扩容效率。

