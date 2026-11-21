# 通用消费记账接口

通过 `Bukkit.getServicesManager().load(SpendingLedger.class)` 获取服务。
消费方只编译依赖公共 `api.spending`、`api.storage` 契约；不要访问存储实现或私有字段。
服务由经济插件注册、停用时注销。旧服务实例的 `available()` 返回 false，调用方需重新获取。

## 归属和读取

`attach(owner, subjectOf, currency)` 绑定调用方维护的 UUID 映射。
`subjectOf` 必须线程安全，只读取不可变数据，不得访问在线玩家、世界或菜单。
经济侧不判断归属的业务含义；归属为空的消费不进入归属统计。

`snapshot(maximum)` 返回起始时间和 `Row(subject, payer, amount)` 列表。
快照不可修改，不按金额排行；超出上限会报错，避免将截断数据误当完整账目。
调用方负责成员显示、排序、分页和刷新时间。

```java
SpendingLedger ledger = Bukkit.getServicesManager().load(SpendingLedger.class);
// 以下在调用方已有的有界 IO 工作线程执行：
AutoCloseable registration = ledger.attach(this, id -> immutableSubjects.get(id), "j");
SpendingLedger.Snapshot snapshot = ledger.snapshot(100_000);
// 停用调用方时关闭 registration；不会删除账目。
```

## 结算

`balance(payer, currency)` 查询余额。
`withdraw(order, payer, currency, BigDecimal amount)` 通过原存储事务扣款，
已绑定的归属和消费记录与余额一并提交；订单引用参与现有账本幂等检查。
`withdraw(order, subject, payer, currency, amount)` 由调用方直接提供订单归属，
即使快照读取方尚未绑定，也会原子保存消费并防止订单重复扣款。
`refund(refund, sources, originalTotal, recipient, currency, amount)` 根据原订单退款，
扣回原归属的消费，不使用退款时的新归属。重复退款引用不会重复入账。
普通收入、转账和管理员扣款不等同于消费退款。

这些调用涉及数据库，必须由调用方的 IO 工作线程执行。
金额必须为正、最多八位小数，且可被现有余额后端精确表示，否则拒绝结算。
`validateSettlement()` 由经济侧核验运行后端；调用方不应通过版本白名单推测兼容性。

## 旧数据接入

`importLegacy(tablePrefix, subjectColumn)` 接收消费方提供的旧表前缀和归属列名。
通用表名为 `ezeconomy_spend`、`ezeconomy_spend_orders`、
`ezeconomy_spend_refunds`、`ezeconomy_spend_meta`。
旧表应使用相同后缀和账本字段，归属列名可由参数指定。

导入在同一 SQL 事务内复制消费汇总、付款、退款指纹和起始时间，并记录完成标记。
不会删除旧表；重复调用跳过；SQL 失败会回滚全部复制。
已有新账目且未完成导入时会拒绝混入，需由管理员核对。
旧表不存在时无需迁移。请在开始绑定归属前调用。
