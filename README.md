# JiuJiuEco

JiuJiuEco 在服务器启动阶段向 Vault 注册 J 点钱包，使用 JiuJiuLib 2.4.4 的命令、语言、任务调度和 PlaceholderAPI 接口。U 点由 PlayerPoints 管理。

## 安装

停服，将旧钱包 JAR 替换为 `JiuJiuEco-1.0.2.jar`，并更新 JiuJiuLib 至 2.4.4。保留 Vault 和现有 JiuJiuEco 数据目录。默认库为 `plugins/JiuJiuEco/jiujiueco.db`。如需 MySQL，启动前在 `plugins/JiuJiuEco/config.yml` 设置 `storage: mysql` 和连接信息。钱包表名为 `jiujiueco_wallet`。

## 命令

- `/balance` 或 `/bal` 查看自己的余额。`/balance <玩家>` 需要 `jiujiueco.user.balance.others`。
- `/pay <玩家> <金额>` 一次完成扣款和入账。付款人可见剩余余额，在线收款人会收到通知。默认玩家拥有 `jiujiueco.user.pay`。
- `/eco give|take|set <玩家> <金额>` 调整余额。管理员可使用 `jiujiueco.eco` 或单项权限 `jiujiueco.eco.give`、`jiujiueco.eco.take`、`jiujiueco.eco.set`。
- `/eco reload` 重新加载钱包的语言文件，需要 `jiujiueco.reload`（默认 OP）。

金额最多保留八位小数。SQLite 和 MySQL 都在同一数据库事务内完成转账。

输入 `/eco` 或 `/eco help [页码]` 打开统一帮助页。Tab 按当前位置补全子命令、可见的在线玩家或示例金额。点击帮助条目可填入对应命令。

## 语言

在 `plugins/JiuJiuEco/lang/zh_CN.yml` 修改命令说明、转账回执和错误提示。`completion.amounts` 指定 Tab 金额示例，用空格分隔。在 `plugins/JiuJiuEco/language.yml` 选择其他 locale 时，可在对应的 `lang/<locale>.yml` 中按需覆盖条目，缺项使用中文。保存后执行 `/eco reload`。帮助页外观与翻页格式由 JiuJiuLib 的 `command.help` 设置统一控制。

## PlaceholderAPI

安装 PlaceholderAPI 后即可使用 `%jiujiueco_balance%` 和 `%jiujiueco_balance_formatted%`，例如 `1250.5` 和 `1250.5 J`。启动时载入已有余额。本服钱包命令和 Vault 交易完成后，下一次解析即返回新余额。共享 MySQL 时，其他实例写入的余额按已查询账户每 20 tick 异步核对。

## 构建

使用 JDK 25。在 JiuJiuLib 工程运行 `gradlew :jiujiulib-api:publishToMavenLocal`，然后使用同一本地 Maven 仓库在本工程运行 `mvn clean verify`。成品为 `target/JiuJiuEco-1.0.2.jar`。
