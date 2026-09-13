# ReentrantLock解析
## 一、核心基础
ReentrantLock 基于 AQS（AbstractQueuedSynchronizer）实现，核心是通过 state 状态控制锁的获取与释放，结合队列管理等待线程。

## 二、lock() 方法流程
### （一）非公平锁实现
1. 尝试 CAS 修改 state 从 0 变为 1：若成功，加锁完成，lock() 方法结束；若失败，调用 acquire 方法继续尝试，或把当前线程包装成 Node 节点插入队列尾部。
2. acquire 方法实现细节： 
    1. 调用 tryAcquire 方法： 
        1. 查询当前 state 是否为 0，若是，重新尝试 CAS 更新 state=1，成功则加锁流程结束；
        2. 若 state≠0，检查当前线程是否已持有锁，若是，直接 setState 修改 state = state+1（当前线程已持锁，无并发竞争，无需 CAS），流程结束；
        3. 若以上条件均不满足，返回 false，调用 acquireQueued 方法继续尝试修改 state，或进入队列等待唤醒。
    2. 调用 acquireQueued 方法： 
        1. 先调用 addWaiter 方法：将当前线程包装成 Node 节点，判断链表尾节点是否存在；存在则将当前节点设为尾节点，调用 enq 方法入队；不存在则直接入队，确保当前节点始终在队列尾部。
        2. 启动无限循环： 
            + 获取当前节点（c 节点）的前驱节点（p 节点）；
            + 若 p 节点是头节点，且 c 节点尝试修改 state 成功：将 c 节点设为头节点，切断 p 节点与链表的双向关联（c.prev = null、p.next = null），帮助快速 GC（减少无效引用，降低 GC 压力）；
            + 若 p 节点不是头节点，或 c 节点修改 state 失败，进入 shouldParkAfterFailedAcquire 方法。
    3. 调用 shouldParkAfterFailedAcquire 方法（判断 p 节点状态，确保 p 释放锁后能唤醒 c 节点）： 
        1. 若 p.waitStatus = -1（可唤醒 c 节点），直接返回 true；
        2. 若 p.waitStatus > 0（CANCELLED 状态）：向前遍历，跳过所有已取消节点，将 c 节点挂到第一个未取消节点后，返回 false，下次循环重新判断新前驱状态；
        3. 若 p.waitStatus = 0（c 节点为新加入，p 状态未修改）：CAS 修改 p.waitStatus 为 -1，等待下次循环将 c 节点挂靠在 p 节点上，实现后续唤醒。
    4. 当 shouldParkAfterFailedAcquire 返回 true（确认前驱为 SIGNAL 状态，前驱释放锁会唤醒 c 节点），调用 parkAndCheckInterrupt 方法，将当前线程真正挂起。
    5. acquireQueued 循环初始化 failed=true：若循环中出现异常，导致 failed 未改为 false，会调用 finally 中的 cancelAcquire 方法，从队列中取消 c 节点。
    6. cancelAcquire 方法（取消节点，确保后续节点能正常唤醒）： 
        1. 跳过已取消的前驱节点（waitStatus > 0 即 CANCELLED），找到第一个正常的前驱节点（n 节点，waitStatus ≤ 0）；将自身（c 节点）的 waitStatus 置为 CANCELLED（取消状态）；
        2. 若 c 节点是尾节点，直接移除自身（无后续节点）；
        3. 若 c 节点不是尾节点且 p 节点不是头节点，将 n 节点与后续节点关联，保证后续唤醒；
        4. 若以上条件均不满足，c 节点直接唤醒后续节点，让其重新参与抢锁，重复加锁流程。

### （二）公平锁实现
与非公平锁实现大体一致，核心区别：tryAcquire 方法中，会调用 hasQueuedPredecessors 方法判断队列中是否已有节点排队获取锁；若有，直接返回失败；若没有，继续执行与非公平锁一致的加锁流程。

## 三、补充说明（关键细节）
cancelAcquire 兜底走 unparkSuccessor 时，会从 tail 开始向前遍历，找到最近的未取消节点，而非从 node.next 向后遍历。

原因：enq 入队过程中，新节点的 prev 指针先于 next 指针被设置（compareAndSetTail 后才设 t.next = node），从 next 链可能看不到刚入队的节点，但 prev 链一定完整，反向遍历可保证不遗漏节点。

## 四、unlock() 方法流程
a. unlock 不区分公平锁与非公平锁，逻辑完全一致。

b. unlock 调用 AQS 的 release 方法来进行解锁，具体流程如下：

1. tryRelease 方法实现： 
    1. 首先判断当前线程是不是持有锁的线程（ReentrantLock 是显示锁，可能被外部线程调用，若不判断会导致数据竞争、锁失效，因此必须判断）；
    2. 若是持有锁的线程，计算当前 c = state - 1；
    3. 若 c == 0，表明锁资源已完全释放，将持有锁线程设置为 null，返回 true；
    4. 若 c != 0，表明存在可重入锁，一次释放未完成，需多次释放，返回 false。
2. 若 tryRelease 返回 true，找到头节点，调用 unparkSuccessor 方法唤醒等待线程。
3. unparkSuccessor 方法实现： 
    1. 先获取头节点的 waitStatus，若 waitStatus < 0，通过 CAS 将其设置为 0；
    2. 查找后续节点，判断后续节点是否为 null 以及 waitStatus 是否大于 0；
    3. 从尾节点开始向前遍历，找到 waitStatus ≤ 0 的节点（加锁时 enq 方法先写 prev、后写 next，后续节点为 null 不代表无后续节点，反向遍历可保证不遗漏节点）；
    4. 唤醒该节点对应的线程。

