# jiujiu 源码维护分支

完整 Maven 多模块源码。银行、每日登录奖励和兑换已删除，余额、付款和 Vault 保留。

消费账本通过 `api.spending.SpendingLedger` 在 Bukkit ServicesManager 发布。
本项目只保存调用方提供的记账归属 UUID、付款人、金额和原订单退款关系。
不包含玩法插件的成员管理、名称解析、排行榜排序或界面，也不依赖玩法插件。
数据库实现位于 `service.spending.SqlSpendingLedger`；旧专用接口已删除。

接入说明见 [通用消费记账接口](spending-api.md)。

## 构建

沿用现有 Java 17 字节码和现代 Paper API，构建使用 JDK 25。
`AGENTS.md` 的历史 Java 8 要求与现有工程不一致，不为旧版服务端降级 API。

```powershell
mvn clean package
mvn -pl ezeconomy-bukkit -am test jacoco:report
```

2026-11-21：全模块构建成功；236 项测试，235 通过、1 项因本机没有 Redis 而跳过。
新接口和事务测试覆盖外部归属绑定、不可变快照、金额验证、停用解绑、原订单退款、
并发扣款和 SQL 回滚。通用旧账本导入在 SQLite、H2 MySQL 模式验证通过，重复导入不会重复累计。
专项 JaCoCo 报告已生成，公开接口服务行覆盖率约 92%。

记账需要同步 SQL 后端：关闭 `performance.mysql.pool.enabled`、
`performance.fast-vault-mutations.enabled`、`performance.balance-cache.enabled` 并重启。
调用方应在自己的 IO 工作线程绑定归属、导入旧账本及查询，避免阻塞 Folia 区域线程。

尚未执行真实服务端、正式 MySQL 或代理网络验收。
