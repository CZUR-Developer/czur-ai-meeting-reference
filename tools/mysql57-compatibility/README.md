# MySQL 5.7 数据库能力验证

这是实际 MySQL 5.7 部署条件下的独立、小型 JDBC（Java 数据库连接）探针。
工具用 Java 21 和现有 MySQL Connector/J 驱动，直接运行本机工具，不启动 Docker，
不引入新依赖或修改后端 POM。连接成功后先断言服务器实际版本为 MySQL 5.7，
不能从驱动版本推定数据库版本。

本工具只证明下面的小型数据库实验，不执行完整 V001 基线，也不覆盖 HTTP、SDK、真实模型、
对象存储或旧业务账本；这些结论只属于本探针自身，不代表后端适配状态。客户端直连模型
的会议链已使用同一份 MySQL 5.7 基线，见[数据库基线](../../deploy/migrations/README.md)。

## 运行

先由调用方准备本机原生 MySQL 5.7 的隔离库与测试账号。本工具不安装或启动数据库，
不建库、删库、删表，不连接实际业务库。只接受 `localhost`、`127.0.0.1` 或 `::1`
单地址 JDBC URL，库名必须匹配 `ai_trans_probe_*`。数据库需已创建；账号需在该隔离库
具有建表、添加外键、读写和查询表元信息所需权限。

从仓库根目录执行：

```bash
export MYSQL57_TEST_URL='jdbc:mysql://127.0.0.1:3307/ai_trans_probe_example?useSSL=false&connectionTimeZone=UTC'
export MYSQL57_TEST_USER='probe_user'
export MYSQL57_JDBC_JAR="$HOME/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar"
read -rsp '隔离库密码: ' MYSQL57_TEST_PASSWORD
export MYSQL57_TEST_PASSWORD
printf '\n'
bash tools/mysql57-compatibility/run.sh
unset MYSQL57_TEST_PASSWORD
```

地址、端口、库名和账号均为示例，替换为调用方的本机隔离环境。驱动路径通过环境指定，
不会自动下载。URL 不允许携带 `user`／`password`；凭据只经环境传给 JDBC Properties，
不作为进程参数，不写配置文件。脚本不启用命令回显，Java 日志不打印连接串、账号、
密码或原始异常消息。隔离账号显式允许空密码时，仍须设置 `MYSQL57_TEST_PASSWORD=''`。

仅编译而不连接数据库：

```bash
bash tools/mysql57-compatibility/run.sh --compile-only
```

编译产物只放工具自身的 `target/`（仓库已忽略）。每次实跑产生新的
`ai_trans_probe_<随机短ID>_*` 测试表，并逐张确认实际引擎为 InnoDB；表和数据保留，
日志打印本轮表前缀供独立读回核验。同一隔离库可重复运行，不覆盖先前实验数据。
工具不自动清理，隔离库的生命周期由调用方管理。

## 九项实验及含义

| 实验 | 实际断言 | 适配影响 |
| --- | --- | --- |
| 1 原子建会提交 | 事务前预占冻结 expected ID、未绑定；同事务锁预占→插入会议→按版本及 expected ID 条件绑定→写事实／Outbox→提交，全部读回一致 | 保留双向即时外键，调整写入顺序可行；不能继续依赖双向延迟外键 |
| 2 建会中途回滚 | 插入会议后、写完事实／Outbox 后各注入一次失败；会议、事实及 Outbox 均不存在，原预占仍未绑定且版本未变 | 业务 DML（数据操作语句）可以保持事务原子性；只是两个针对性回归，不是故障矩阵 |
| 3 孤儿立即拒绝 | 在 INSERT 调用时收到外键错误 1452，尚未 COMMIT，孤儿行不存在 | 即时外键不能当作延迟校验使用 |
| 4 同行锁累计 | 两个 JDBC 事务通过 latch 组织顺序，各用同一锁行 `FOR UPDATE`；锁定读值依次为 0、11，最终累计为 25 | 行锁可保护读后累计，无丢失更新；不借助 sleep 推断阻塞时间，不验证旧生产扣费 |
| 5 同键唯一结果 | 普通 INSERT 重复收到 1062，再用普通 SELECT 同键读取，仍只有原结果 | 区分重复键与其他数据库错误；不用 `INSERT IGNORE` 掩盖错误 |
| 6 CHECK 与业务校验 | 声明 `CHECK(used_ms >= 0)` 的表实际接受 -1；Java 业务入口拒绝 -1、正常接受 7 | MySQL 5.7 不执行 CHECK，非负等业务不变量必须有实际入口校验 |
| 7 字段精确读回 | JSON 中文及 emoji、32 字节 SHA-256 digest、显式 UTC `DATETIME(6)` 微秒读回一致；两个大小写 ID 各自唯一 | 用 `utf8mb4` 和二进制排序规则保存标识；UTC 时间约定须由连接及读写映射维持 |
| 8 不可用语法 | `FOR UPDATE SKIP LOCKED`、`DEFERRABLE INITIALLY DEFERRED` 各收到语法错误 1064 | 5.7 不能直接沿用这些锁队列／延迟约束 SQL，正式适配需对应处理 |
| 9 DDL 隐式提交 | 事务内先写标记再 CREATE TABLE，随后写第二标记并 ROLLBACK；独立连接仍看到第一标记和新表，第二标记不存在 | DDL（结构定义语句）与会议 DML 原子性不同；不能承诺整份建表脚本整体回滚 |

第一项的双向关联由先创建两张表、再 `ALTER TABLE` 加第二条外键完成，所有连接保持
`foreign_key_checks=1`。该最小模型没有复制生产会议状态机，不替代全部生产约束。

Java 使用 INFO 记录版本、当前表前缀和各项通过，WARNING 记录已验证的适配限制，
DEBUG 记录预期错误码及回滚位置，ERROR 记录失败步骤及不含凭据的错误分类。
全部通过时打印 `MYSQL57_CAPABILITY_PROBE_OK 9/9` 并返回 0；任一失败返回非零，
保留已创建表供排查。通过标记必须与服务器实际版本、实跑日志和独立验收记录一起解释。
