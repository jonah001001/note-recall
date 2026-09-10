# MYSQL日志
## redo log解析
### 一、为什么需要 redo log？
InnoDB 若每次事务提交时，直接将被修改的数据页刷到磁盘，会存在3个严重问题，redo log 正是为解决这些问题而生：

+ 写放大：MySQL 单个数据页大小为 16kb，即便仅修改数据页中几十个字节，也需重写整个 16kb 的数据页，造成不必要的磁盘写入开销；
+ 随机 IO：数据页在磁盘上的分布是分散的，直接刷数据页会产生大量随机 IO，磁盘访问效率极低；
+ 性能崩溃：高并发场景下，每个事务提交都触发一次随机 IO 刷盘，会导致数据库性能急剧下降，无法支撑高并发请求。

### 二、WAL 机制（redo log 核心实现）
为解决上述问题，InnoDB 引入 WAL（Write-Ahead Logging，预写式日志）机制，核心思想如下：

 事务**执行过程中**,每次修改都**持续地**写入 redo log buffer(内存);事务**提交时**(flush=1),再把 buffer 刷到磁盘的 redo log 文件。数据页的修改则留在 Buffer Pool,由后台异步刷盘。

#### WAL 机制解决的核心问题：
+ 速度提升：redo log 是顺序写（始终追加到日志末尾），速度远超磁盘随机写，大幅提升事务提交效率；
+ 安全性保障：即使数据页未刷盘，只要 redo log 已刷盘，机器崩溃重启后，可通过 redo log 恢复未刷盘的修改，保证事务持久性。

### 三、redo log 核心概念
#### 1. redo log 是什么？
redo log 是 **物理日志**，核心作用是记录“在哪个数据页的哪个位置，做了什么物理修改”（例如：数据页 100 的偏移量 50 处，将值从 A 改为 B）。

其核心价值是保证事务的持久性（ACID 中的 D），服务崩溃后，可通过 redo log 恢复已提交但未刷盘的数据，实现 crash safe。

#### 2. redo log 的结构
redo log 采用 **环形结构 + checkpoint** 设计，具体特点如下：

+ 文件大小固定：redo log 文件是固定大小的，采用循环写的方式；
+ 两个关键位置： 
    - write pos：当前 redo log 的写入位置，标识下一次写入日志的起始位置；
    - checkpoint：当前 redo log 的擦除位置，标识 checkpoint 之前的日志对应的 data 页已刷盘，这部分日志可被覆盖。
+ 空间逻辑：write pos 和 checkpoint 之间的空白区域，是 redo log 可写入的空间；若 write pos 追上 checkpoint，说明 redo log 已写满，此时 InnoDB 会暂停所有写入操作，先将部分数据页刷盘，推进 checkpoint、腾出空间后，再继续写入。

注意：redo log 写满时，数据库会出现卡顿，因此需配置合理的 redo log 大小，避免频繁写满。

#### 3. crash safe 原理
crash safe（崩溃安全）是 redo log 的核心能力，本质的逻辑的是：

服务崩溃后，InnoDB 重启时会检查 redo log，找出“已记录 redo log 但数据页未刷盘”的修改，将这些修改重新应用到数据页上，恢复数据，确保已提交的事务修改不丢失——只要 redo log 成功刷盘，无论数据页是否刷盘，事务的修改都能恢复。

### 四、innodb_flush_log_at_trx_commit 属性（redo log 刷盘控制）
该属性专门控制 redo log 的刷盘时机，同时需明确 WAL 机制中的两种刷盘操作区别：

| **刷盘类型** | **刷的内容** | **刷盘路径** |
| --- | --- | --- |
| redo log 刷盘 | redo log | redo log buffer（内存） → redo log 文件（磁盘） |
| 数据页刷盘 | 脏页（被修改过的数据页） | Buffer Pool（内存） → 数据文件 .ibd（磁盘） |


innodb_flush_log_at_trx_commit 有3个取值，分别对应不同的 redo log 刷盘策略，具体如下：

| **flush 值** | **redo log 刷盘方式** | **数据页刷盘方式** | **备注（数据安全性/性能）** |
| --- | --- | --- | --- |
| 1（默认） | 同步刷盘：每次事务提交时，必刷 redo log 到磁盘 | 异步刷盘：由后台线程异步刷脏页 | 安全性最高，性能略低；即使服务崩溃，无数据丢失 |
| 0 | 异步刷盘：每秒刷一次 redo log 到磁盘，与事务提交无关 | 异步刷盘 | 性能最高，安全性最低；服务崩溃可能丢失近1秒的数据 |
| 2 | 半同步刷盘：事务提交时，先写 OS 缓存，每秒通过 fsync 刷到磁盘 | 异步刷盘 | 性能与安全性均衡；OS 崩溃可能丢失近1秒的数据，MySQL 崩溃无数据丢失 |


## undo log解析
### 一、undo log的作用
undo log有两个作用：

+ 构建MVCC版本链：undo log里保存修改前的旧值，顺着roll_pointer串成版本链，用于ReadView判断可见性。
+ 数据回滚：保证事务的原子性（A）,事务每做一次修改，InnoDB会记录一次反向操作的undo log，如事务 insert，则undo log记录delete，事务delete，则undo log记录insert，事务update a = b, 则undo log记录update b =a。

**注意：undo log是逻辑日志，执行跟事务相反的SQL操作，redo log是物理日志，记录的是数据页的物理修改。**

## **binlog解析**
### 一、binlog是什么？
**binlog是server层的日志，记录所有对数据库的更改操作，所有存储引擎都有。而redo log/undo log都是InnoDB引擎层的日志。**

### **二、binlog的作用？**
+ 主从复制：从库读取主库的binlog，在自己这边重放一遍，保证跟主库数据一致。
+ 数据恢复（基于时间点）：数据误删后，用全量备份+binlog重做，先恢复到上次备份的状态，然后用binlog恢复到误删的前一刻，误删后的操作不处理，也叫point-in-time recovery

### 三、binlog的三种格式
| 格式 | 记录 |
| --- | --- |
| statement | SQL语句原文 |
| row | 每一行的实际变更 |
| mixed | MySQL自己选 |


MySQL8.0以后，默认是row格式，而不是statement格式，因为在主从复制的场景下，有些查询条件会导致主从数据不一致，如NOW(), 不带order by的limit等。

### 四、binlog的写入方式
binlog是追加写，写满一个文件（binlog.000001）后继续写下一个文件（binlog.000002）,**老文件不会被覆盖**。

redo log是循环写，文件大小固定，旧数据会被覆盖。

这个做的原因是：binlog是为了主从同步，需要归档，主从复制等，日志需要长期保存。而redo log是为了崩溃恢复，数据恢复后，旧数据就可以被覆盖了。

对比如下表：

|  | redo log | binlog |
| --- | --- | --- |
| 层级 | InnoDB引擎层 | Server层 |
| 持有 | 只有InnoDB | 所有存储引擎 |
| 类型 | 物理日志（页的物理修改） | 逻辑日志（SQL/行变更） |
| 写入方式 | 循环写（固定大小，可覆盖） | 追加写（写满换文件，不覆盖） |
| 作用 | 崩溃恢复（crash safe） | 主从复制，数据恢复等 |


**redo log 和binlog都能做数据恢复，他们有什么区别？**

binlog不能做崩溃恢复，因为它不记录哪些数据已经刷盘了，崩溃后无法确定从binlog的哪个点开始恢复。

redo log有checkpoint, 精确的知道哪些修改已落盘，崩溃后可以快速恢复。

### 五、两阶段提交（2PC）
#### 为什么需要2PC？
一个事务提交，需要写两个日志，redo log和binlog，这两个日志必须保持一致，如果不一致，会导致最后主从库的数据不一致。

#### 2PC的实现思路
把redo log拆成两个阶段，prepare和commit。

事务提交时，执行顺序

```plain
① redo log 写入,标记为 prepare 状态
         ↓
② binlog 写入磁盘
         ↓
③ redo log 标记改为 commit 状态
```

事务提交判断：

**① **redo log 是 commit 状态， 表示整个流程走完了，正常恢复,提交

**② **redo log 是 prepare 状态(说明崩溃发生在 ③ 之前) , 这时要去看 binlog:

    - binlog 里有这个事务、且完整, 说明 ② 写成功了,只是没来得及做 ③ , 那么提交这个事务
    - binlog 里没有这个事务、或不完整 ，说明崩在 ② 之前, binlog 没写成， 那么回滚这个事务

**注意：两阶段提交以binlog的写入状态为准，因为binlog 一旦写入就可能被从库读走,所以 binlog 写成功 = 事务对外已发生 = 必须提交  **

### 六、特别注意
① 崩溃恢复的流程 :先用 redo log **全部重做**(不区分已提交/未提交,目的是到达完整状态,也顺带恢复 undo log);再用 undo log **回滚未提交事务**。结果:已提交的保留(D),未提交的撤销(A)。  

**② undo log 自己也被 redo log 保护**，也就是说，写 undo log 这个动作本身也会产生 redo log,保证 undo log 自己也 crash safe。  

③  组提交(group commit)，将多事务的 fsync 合并,优化 2PC 的多次刷盘性能  

