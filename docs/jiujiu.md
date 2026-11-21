# jiujiu 源码维护分支

以 GitHub `v3.x` 的完整源码（`0ed7a4a`）为基础，迁入 2026-11-20 本地维护包的消费账本和异步余额查询。使用现有 Maven 多模块构建，不再依赖反编译参考、固定基线 JAR 或 ASM 补丁。

## 已迁入

- 有界线程池处理余额查询，限制队列和同一发送者的重复请求；通过玩家所属调度器发送结果，停用插件时释放任务。
- `IslandSpendLedger` 保留既有对外接口，供岛屿插件绑定归属、读取消费快照和按原订单退款。扣款与消费统计在同一 SQL 事务提交；换岛后退款扣回原岛贡献。
- 转账、货币兑换、银行存款、管理员扣款和数据库自检不计消费。共享 MySQL 连接上的扣款、入账、转账和仓库查询使用同一连接监视器；转账前后事件在锁外通知。
- MySQL 周期延迟统计默认关闭，可由 `performance.mysql.metrics.enabled` 启用。

## 构建与验证

沿用上游 `pom.xml` 的编译级别和依赖版本。构建环境需要能够读取当前 Paper API 的 JDK（本次使用 JDK 25）。仓库 `AGENTS.md` 的历史 Java 8 要求与上游现有 Java 17 工程和现代 Paper API 不一致，本分支沿用现有构建，不降级 API。

```powershell
mvn -pl ezeconomy-bukkit -am package -DskipTests
mvn -pl ezeconomy-bukkit -am test jacoco:report -Dtest=IslandSpendLedgerTest,BalanceAsyncTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试覆盖 SQLite、H2 MySQL 模式的真实事务、关联退款幂等、统计失败回滚、共享连接上的并发更新及余额异步回复。H2 验证不等同于正式 MySQL 或实服验收。

2026-11-21 本地验证：完整 Bukkit 模块编译、打包成功；上述 4 个专项测试通过，覆盖两种数据库共 60 项账本断言、共享连接并发回滚及 18 项异步余额检查。未运行全部上游测试；当前上游 JaCoCo 配置未生成执行数据，不宣称覆盖率。

## 消费排行运行条件

必须关闭 `performance.mysql.pool.enabled`、`performance.fast-vault-mutations.enabled` 和 `performance.balance-cache.enabled`，并完整重启；已启动的余额后台写入队列也必须停用。账本接入会检查这些条件。保持原有服务器配置和数据库凭据，账本只记录启用后的消费，不回填历史。

`/is top` 命令和 GUI 由配套岛屿、GUI 插件提供；本仓库负责经济侧事务和账本接口。未知第三方退款必须传入原付款引用。
