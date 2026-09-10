源码如下：

```java
final int nonfairTryAcquireShared(int acquires) {
    for (;;) {
        int available = getState();
        int remaining = available - acquires;
        if (remaining < 0 ||
            compareAndSetState(available, remaining))
            return remaining;
    }
}
```

这是Semaphore获取许可证的核心逻辑，输入需要的许可证数量（看上层调用是1），这里AQS的state不再表示加锁的标识，而是许可证的可用数量，每次领取一个许可证，state就减1，最后返回剩余可用的许可证数量。为什么要判断remaing < 0, 因为Semaphore初始化的时候，可以设置new Semaphore(0)，这时候领取许可证时，remaning就为-1了，此时线程会阻塞。需要等待许可证释放（release）后，线程才继续往下走。

为什么这里是for循环，而不是单次CAS，是因为这是共享的许可证，有很多线程进来拿到许可证，执行完后会释放许可证，然后下一批线程继续进来领取，源源不断。



