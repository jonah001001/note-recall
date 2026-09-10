# CountDownLatch 解析
## 一、核心源码展示
### （一）共享模式核心方法源码
```java
// 共享模式 - 尝试获取共享资源
protected int tryAcquireShared(int acquires) {
    return (getState() == 0) ? 1 : -1;
}

// 共享模式 - 尝试释放共享资源
protected boolean tryReleaseShared(int releases) {
    for (;;) {
        int c = getState();
        if (c == 0)
            return false;
        int nextc = c - 1;
        if (compareAndSetState(c, nextc))
            return nextc == 0;
    }
}
```

## 二、Semaphore 核心解析
### （一）核心逻辑（nonfairTryAcquireShared 方法）
nonfairTryAcquireShared 是 Semaphore 获取许可证的核心逻辑，具体说明如下：

+ 输入参数：需要的许可证数量（上层调用通常为1）；
+ state 含义：不再表示加锁标识，而是**许可证的可用数量**，每次领取1个许可证，state 就减1，最终返回剩余可用的许可证数量；
+ remaing < 0 的原因：Semaphore 可初始化为 new Semaphore(0)，此时领取许可证时，remaing 为 -1，线程会阻塞，需等待许可证释放（release）后才能继续执行；
+ for 循环的作用：Semaphore 是共享许可证，会有多个线程同时领取、释放许可证，循环可保证多线程场景下的并发安全性，实现许可证的持续流转。

## 三、CountDownLatch 核心解析
### （一）tryAcquireShared 方法解析
该方法根据 state 是否为0返回1或-1，具体逻辑如下：

+ state 含义：与 Semaphore 中的 state 类似，可理解为“许可证”，每个线程释放1次许可证，state 就减1；
+ 核心作用：判断所有线程是否已达到同一状态（全部释放许可证）—— 若 state=0（全部释放），返回1；否则返回-1；
+ 上层逻辑：根据返回值判断，若返回值 < 0，中断线程；若返回值 ≥ 0，放行线程。

### （二）tryReleaseShared 方法解析
该方法用于释放许可证，核心逻辑如下：

+ for 循环作用：不停检测当前线程是否已释放许可证，直至 state=0（全部释放）；
+ 返回值意义：当 state=0 时返回 true，上层调用时，若头节点为 SIGNAL 状态，会唤醒该线程；
+ 协同逻辑：结合 tryAcquireShared 方法，统一判断所有线程的状态，确保所有线程完成操作后再继续执行。

## 四、核心问题解答（Q&A）
### Q1. 独占模式和共享模式的核心差异？
独占模式：单个线程占用 state 状态，不同线程之间相互隔离，同一时间只有一个线程能获取资源；

共享模式：state 以“许可证”形式存在，可被多个线程重复获取、释放，多个线程可同时使用资源。

### Q2. Semaphore 一次 release(3) 是怎么“释放 3 个许可”的？
#### （一）核心细节
底层仅调用1次 unparkSuccessor 方法，但该方法处于循环中，每次都会判断链表中是否存在其他节点，若头节点状态为-1（SIGNAL），则唤醒一个节点。

#### （二）关键方法补充
doReleasedShared() 方法中，for 循环内会调用 compareAndSetWaitStatus(h, 0, NODE.PROPAGATE)，将头节点的 waitStatus 设置为 -3（PROPAGATE 状态），该状态表示需要唤醒后续的共享节点。

#### （三）release(3) 传播机制
1. 调用 tryReleaseShared 方法，返还3个许可证，返还成功后调用 doReleaseShared 方法；
2. doReleaseShared 方法唤醒后续节点的线程，该线程被唤醒后，重新获取许可证；
3. 线程进入 doAcquiredShared 方法，调用 tryAcquireShared 方法，返回剩余许可证数量（此时为2）；
4. 因剩余许可证数量 > 0，调用 setHeadAndPropagate 方法，该方法再次调用 doReleaseShared 方法；
5. 上述流程循环执行，持续唤醒后续节点，直至许可证数量为0时停止。

核心总结：传播机制并非由调用方主动通知，而是由被唤醒的节点在循环中持续通知下一个节点实现的。

