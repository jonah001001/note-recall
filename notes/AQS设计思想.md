# AQS设计思想
## 1. waitStatus编码：

```java
static final int CANCELLED =  1;   // 取消
static final int SIGNAL    = -1;   // 后继需要被唤醒
static final int CONDITION = -2;   // 在 condition 队列上
static final int PROPAGATE = -3;   // 共享模式下传播
// 0 表示初始状态
```

waitStatus中的状态设计，只有CANCELED=1，其他状态都<=0， 这是"用数值结构编码语义"的设计思想，有3个优点：

a.因为设计中有很多需要按状态分组判断的场景（如'是否取消'、'是否未取消'），用数值符号承载分组信息后，单条比较指令就能完成分组判断，这是底层并发库常用的微优化思路。

b.方便扩展，如果以后要新增其他状态，只需要继续加一个-4，或者-5这类的负数就行，取消判断场景完全不需要修改。

c.语义自洽，读者可以很显著的判断节点的情况，<= 0就是正常的节点，>0表示已被取消，如果是同一类数字，需要根据具体的值来判断对应的状态，可能需要读者经常回顾状态定义。

## 2. head虚拟

head是一个虚拟节点，只是表示当前持有锁的线程所在节点的占位，它本身的thread属性是null，因为在lock方法中，调用acquireQueued方法时，由我们显示设置的，node拿到锁时，setHead会设置node为新的head，然后setHead内部会清理掉node的thread字段。 如下：

```java
private void setHead(Node node) {
    head = node;
    node.thread = null;   // ← 清掉 thread
    node.prev = null;
}
```

另外一个问题， 为什么用"占位 head + 第一个等待者是 head.next"，而不是"head 直接指向第一个等待者"？  这是无锁队列的思想，将唤醒后续节点和修改head节点这两件事解耦，职责分开，让每个操作只修改自己关心的状态，避免多方协调。 具体来说，head 的更新是"获得锁的线程"的责任，不是"释放锁的线程"的责任。这是下面讲的 CLH 变种和"传统队列"的本质区别：传统队列里释放方要修改队列状态，CLH 变种里释放方只负责唤醒，"修改队列"由获取方自己完成。 



## 3. CLH队列设计

a. 原生的CLH锁问题：不支持java， Java 必须支持"拿不到锁就挂起", 而原生的CLH拿不到锁就会一直自旋，这会导致CPU空转。 其次，纯 prev 链不支持取消，CLH 队列只有 prev 指针，没有 next 指针，在线程取消的场景下会崩溃。

b. CLH变种：

改造 1：从"自旋"到"自旋 + 阻塞", 不再纯自旋。节点的 waitStatus 字段决定要不要挂起：

        * 检查前驱是不是 head + tryAcquire（自旋一次）
        * 拿不到锁 → 把前驱的 waitStatus 设成 SIGNAL → 自己 park()
        * 前驱释放锁时，根据自己的 waitStatus 决定要不要 unpark 后继

改造 2：增加 next 指针，变成"双向链表"。原始 CLH 只有 prev, ** **新的变种CLH增加了next，变成双向链。next的作用：

        1. 支持唤醒后继——unparkSuccessor 要知道"叫谁"。没有 next 指针，head 不知道该唤醒谁。
        2. 支持节点取消——cancelAcquire 要修复链表，需要双向访问。
        3. 支持 Condition 队列迁移——signal 时要把节点从 condition 队列搬到 sync 队列。

c. 比无锁队列的优势：

 普通无锁队列（如 ConcurrentLinkedQueue）虽然也能存放等待节点，但它的设计目标是高效的出队/入队，AQS需要准确高效（本地）的判断当前节点的前驱节点状态，这是普通无锁队列很难做到的。

通篇AQS源码，核心问题在于线程的挂起/唤醒，其他设计都是在这基础上做的优化，比如缓存友好，可读性强，反向通知后继节点，还有head虚拟节点这些，具体来说，waitStatus状态值，占位 head + 第一个等待者是 head.next，增加next指针等设计方式，都是完美契合CLH队列的优化，所以用CLH队列是很合理的。

