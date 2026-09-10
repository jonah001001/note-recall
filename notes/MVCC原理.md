## 一、版本链绘制（基于 undo log）
假设数据行由事务 T99 初始插入：(id=1, val='A')，之后经过两次更新（T100 改 val 为 'B'，T200 改 val 为 'C'），undo log 版本链结构如下：

```java
// 当前行（存储在聚簇索引页中，永远是最新版本）
DataRow(id=1, val='C', trx_id=200, roll_ptr=0x123)  // T200 更新后，最新版本，roll_ptr 指向前一个版本
└── undo log 条目（T200 生成，指向 T100 更新后的版本）
    DataRow(id=1, val='B', trx_id=100, roll_ptr=0x456)  // T100 更新后版本，roll_ptr 指向初始版本
    └── undo log 条目（T100 生成，指向初始版本）
        DataRow(id=1, val='A', trx_id=99, roll_ptr=null)  // T99 初始插入的版本，无前置版本
```

说明：

+ 每个版本包含 trx_id（创建该版本的事务 ID）和 roll_ptr（指向 undo log 中上一个版本的指针），通过 roll_ptr 串联成版本链。
+ trx_id 永远是「创建该版本的事务 ID」，不存在 0 / null 的情况——初始数据也是某个事务（这里是 T99）INSERT 进去的，有它自己的 trx_id。
+ 当前行（最新版本）存储在聚簇索引页中；历史版本存储在 undo log 里。

## 二、核心问题解析
### 1. RC 和 RR 的核心差异是什么？用 ReadView 来解释。
RC（读已提交）和 RR（可重复读）的本质差异是 **ReadView 创建时机不同**，具体区别如下：

+ RC（读已提交）：每次执行 SELECT 语句时，都会创建一个新的 ReadView；因此同一事务内，多次 SELECT 可能读到不同版本的数据（因为每次 ReadView 不同，可见性判断结果不同）。
+ RR（可重复读）：在事务内第一次执行 SELECT 语句时，创建一个 ReadView，后续同一事务内的所有 SELECT 都会复用这个 ReadView；因此同一事务内，多次 SELECT 会读到相同版本的数据，实现“可重复读”。

ReadView 的作用是定义“当前事务能看到哪些版本的数据”，创建时机的不同直接决定了两种隔离级别的可见性差异。

### 2. 实验 1 里，Session A 第二次 SELECT 时，Session B 的 UPDATE 已经 COMMIT 了——为什么 Session A 还看到旧值？用可见性判断算法解释。
核心原因：实验场景为 RR（可重复读）隔离级别，结合可见性判断算法，具体逻辑如下：

+ RR 隔离级别下，Session A 事务内第一次 SELECT 时创建 ReadView，后续第二次 SELECT 复用该旧 ReadView；
+ 可见性判断算法核心规则（基于 ReadView 中的 m_ids、min_trx_id、max_trx_id）：数据版本的 trx_id 若在 ReadView 可见范围内（未提交事务ID集合之外、且不大于当前最大可见事务ID），则可见；
+ Session B 的 UPDATE 事务 COMMIT 后，其 trx_id 不在 Session A 复用的旧 ReadView 的 m_ids（未提交事务ID集合）中，但由于 Session A 复用旧 ReadView，仍会按旧的可见性规则判断，因此只能看到旧版本数据，看不到 Session B 提交的新值。

### 3. MVCC 是不是完全消除了“加锁”？为什么 SELECT ... FOR UPDATE 还要加锁？
MVCC **没有完全消除加锁**，其核心作用是解决“读与读、读与写”的冲突，具体原因如下：

+ MVCC 通过 undo log 历史版本实现“多版本读取”，无需对读取操作加锁，避免了读与写、读与读的冲突，但无法解决“写与写”的冲突；
+ 并发写操作（如两个事务同时更新同一行数据）会出现“丢失更新”问题，需要通过行锁来保证原子性，因此仍需加锁；
+ SELECT ... FOR UPDATE 属于“当前读”（读取最新版本的数据），而非 MVCC 的“快照读”，当前读需要加间隙锁（或行锁），目的是防止幻读，确保读取到的最新数据不会被其他事务修改，保证数据一致性。

### 4. 线上场景——批量数据导出功能，事务开了几小时不提交，系统出现“undo log 暴涨、磁盘快满”的告警。这是为什么？怎么解决？
#### （1）告警原因
事务长期未提交，会持续持有 ReadView；InnoDB 清理 undo log 的规则是：只有当 undo log 对应的版本，不再被任何 ReadView 引用时，才能被清理。

长期未提交的事务，其 ReadView 会一直引用所有相关的 undo log 历史版本；同时，其他事务持续修改数据，会不断生成新的 undo log，旧的 undo log 无法清理，导致 undo log 暴涨，最终占满磁盘。

#### （2）解决方案
+ 紧急方案：立即查询长期未提交的事务，强行中断，释放 ReadView，让 InnoDB 清理 undo log。 执行 SQL 查看未提交事务：`select * from information_schema.INNODB_TRX order by trx_started asc;` 找到执行时间过长的事务，中断对应线程。 
+ 长期方案：优化代码，拆分大事务，采用流式读取，具体如下： 
    - 拆分大事务：将事务范围缩小，仅包含需要保证一致性的写操作，排除外部接口调用、文件 IO 等耗时操作（此类操作无需事务保证）；
    - 流式读取：用流式读取替代全量读取，避免一次性读取大量数据导致事务长时间未提交；
    - 监控优化：添加监控告警，对运行时间超过阈值（如 10 分钟）的事务及时告警，避免事务长期未提交。

## 三、可见性判断算法实操案例
### 可见性判断的 4 条规则
ReadView 的 4 个字段：

+ m_creator_trx_id：创建该 ReadView 的事务自己的 ID
+ m_ids：创建 ReadView 时，所有「活跃（已开始但未提交）」事务的 ID 列表
+ min_trx_id：m_ids 中的最小值（活跃事务里最小的 ID）。若 m_ids 为空，则等于 max_trx_id
+ max_trx_id：系统下一个将要分配的事务 ID

对版本链上某个版本，设它的 trx_id = X，按顺序判断（命中即停）：

1. X == m_creator_trx_id → 可见（自己改的）
2. X < min_trx_id → 可见（该事务在所有活跃事务之前，早已提交）
3. X ≥ max_trx_id → 不可见（该事务在 ReadView 创建之后才开始）
4. min_trx_id ≤ X < max_trx_id：
    - X 在 m_ids 中 → 不可见（活跃事务，尚未提交）
    - X 不在 m_ids 中 → 可见（已提交）

注意：m_ids 是「离散的 ID 列表」，不是区间。且 min_trx_id 恒等于 m_ids 的最小值——若 m_ids=[200,400]，则 min_trx_id 必为 200，不可能是别的值（这一点是判断参数是否自洽的关键）。

---

共用版本链（从新到旧）：D(trx_id=400) → C(trx_id=300) → B(trx_id=200) → A(trx_id=100)

### 案例1：事务 T500 查询
ReadView：m_creator_trx_id=500，m_ids=[200, 400]，min_trx_id=200，max_trx_id=501

从最新版本 D 开始，逐版本套用规则：

+ D 版本（trx_id=400）：400≠500（规则1不中）；400 不< 200（规则2不中）；400 不≥ 501（规则3不中）；进入规则4，200≤400<501，且 400 在 m_ids 中 → 不可见
+ C 版本（trx_id=300）：300≠500；300 不< 200；300 不≥ 501；进入规则4，200≤300<501，且 300 不在 m_ids 中 → 可见

结论：T500 查到的是 C 版本，val = 'C'。

### 案例2：事务 T500 自己更新后再查询
T500 自己执行 UPDATE 把 val 改为 'E'，版本链头部新增 DataRow(val='E', trx_id=500)。

判断：新版本 trx_id=500 == m_creator_trx_id(500) → 命中规则1 → 可见。

结论：T500 查到自己更新后的值，val = 'E'。（事务能看到自己的修改）

### 案例3：事务 T600 查询
ReadView：m_creator_trx_id=600，m_ids=[300, 600]，min_trx_id=300，max_trx_id=601  
版本链同上：D(400) → C(300) → B(200) → A(100)

从 D 开始：

+ D 版本（trx_id=400）：400≠600；400 不< 300；400 不≥ 601；进入规则4，300≤400<601，且 400 不在 m_ids=[300,600] 中 → 可见

结论：T600 查到的是 D 版本，val = 'D'。

