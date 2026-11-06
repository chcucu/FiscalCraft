# FiscalCraft 使用说明书（v1.0.1）

> Paper 1.21.11 / Java 21 / Vault 经济系统插件
> 功能：银行账户、存款利息、财富税与个税、共享账户、实体银行卡、账户等级、贷款征信、风控反洗钱、财政池与审计。
> 货币底座：Vault 钱包（EssentialsX 等提供者），FiscalCraft 不迁移、不替换、不改写任何其他插件的余额。

---

## 从源码构建

```bash
git clone https://github.com/chcucu/FiscalCraft.git
cd FiscalCraft
mvn clean package
# 产物：target/FiscalCraft-1.0.1.jar
```

要求 JDK 21+、Maven 3.9+。单元测试：

```bash
mvn test
```

---

## 一、安装步骤

### 1. 前置条件

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 1.21.11（build 132 已验证） |
| Java | 21 |
| 经济前置 | Vault 1.7.x + 一个 Economy 提供者（推荐 EssentialsX 2.21+） |
| 权限前置 | LuckPerms（可选，但强烈建议） |
| 数据库 | 默认内置 SQLite（WAL），可选 MySQL 8+ |

### 2. 安装

1. 将 `FiscalCraft-1.0.1.jar` 放入服务端 `plugins/` 目录。
2. 启动服务器一次，生成：
   - `plugins/FiscalCraft/config.yml`
   - `plugins/FiscalCraft/fiscalcraft.db`（SQLite 默认）
   - `plugins/FiscalCraft/backups/`（备份目录）
3. 控制台应出现：

   ```
   [FiscalCraft] 已加载 0 个银行终端
   [FiscalCraft] FiscalCraft 已就绪：数据库 SQLite(WAL)，资金操作单线程串行执行。
   ```

4. 按下方“权限配置”禁用 EssentialsX 自带的 `/pay` 与 `/loan`，保留 `/bal`。
5. 在游戏内看向箱子/木桶执行 `/bank admin terminal create <类型>` 创建银行终端（类型见“银行终端与会话”）。
6. 重启或执行 `/reload` 后，让玩家右键终端开始使用。

> 注意：`/bank` 的配置在启动时读取，修改 `config.yml` 后请重启服务器，避免经济参数热改造成账目不一致。

### 3. 权限配置（禁用 EssentialsX 的 /pay 与 /loan）

FiscalCraft 通过 `PlayerCommandPreprocessEvent` 接管 `/pay`，即使 EssentialsX 的 `/pay` 仍然注册，也会被本插件取消后走自己的转账与税收逻辑。为彻底避免重复扣款，仍建议用 LuckPerms 明确关闭 EssentialsX 的两个权限：

```text
/lp group default permission set essentials.pay false
/lp group default permission set essentials.loan false
```

`/bal` 不做任何处理，继续由 EssentialsX 输出 Vault 余额。

### 4. 首次使用流程

1. 新玩家右键银行终端：插件先检查账户，**不会要求输入密码**，而是提示“你还没有银行账户”，并引导输入 `/bank open`。
2. 输入 `/bank open` → 依次设置支付密码、密保问题、密保答案 → 从 Vault 钱包扣开户费 500。
3. 已有账户的玩家右键终端才会要求输入 6 位支付密码（或手持银行卡免密），建立会话（普通等级 5 分钟）。
4. `/bank deposit 100` 存款免密。
5. `/bank withdraw 100` 使用当前终端会话。
6. `/pay Bob 100` 远程转账：每次输入支付密码，单笔 2000、日累计 5000。
7. 终端内 `/bank transfer Bob 100`：单笔上限按等级（普通 10000），日累计 20000。

---

## 二、指令列表

### 1. 玩家指令

所有 `/bank` 子命令都需要权限 `fiscalcraft.use`（默认开启）。带“终端”标记的指令必须在银行终端 3 格内执行，并且要符合终端类型（见 4.3 终端类型表）。

| 指令 | 说明 | 条件 |
| --- | --- | --- |
| `/bank help` | 查看帮助 | 无 |
| `/bank open` | 开户：设置 6 位密码 + 密保问题/答案，扣开户费 | 终端 |
| `/bank close` | 销户第一步，显示风险提示 | 终端会话 |
| `/bank close confirm` | 销户第二步，60 秒内确认 | 终端会话 |
| `/bank deposit <金额>` | 钱包现金存入银行 | 终端，免密 |
| `/bank withdraw <金额>` | 银行取现到钱包 | 终端会话 |
| `/bank transfer <玩家> <金额>` | 终端转账给已开户玩家 | 终端会话 |
| `/bank balance`（别名 `/bank bal`） | 查看银行余额、钱包余额、等级、风险等级 | 终端会话 |
| `/bank history [条数]` | 查看最近流水（上限随等级 20~100 条） | 终端会话 |
| `/bank tax` | 查看欠税与近期税单 | 终端会话 |
| `/bank loan status` | 查看贷款状态、授信额度、资金池可放额度 | 终端会话 |
| `/bank loan apply <7\|30> <金额>` | 申请 7 天或 30 天贷款 | 终端会话 |
| `/bank loan extend` | 申请展期一次（+7 天，利率上浮） | 终端会话 |
| `/bank repay <金额\|all>` | 还贷，支持提前还款 | 终端会话 |
| `/bank card issue` | 办卡（800 + 等级工本费） | 终端 |
| `/bank card reissue <旧卡号>` | 补办（200），旧卡作废，生成新卡号 | 终端 |
| `/bank card list` | 查看自己的银行卡 | 无 |
| `/bank card transfer <卡号> <玩家>` | 发起转让，双方在线，转让方必须持有实体卡 | 无 |
| `/bank card accept <卡号>` | 接受转让（2 分钟内），实体卡自动交付 | 无 |
| `/bank shared create <family\|couple\|public> <名称>` | 创建共享账户（500） | 终端 |
| `/bank shared join <编号>` | 加入共享账户 | 终端 |
| `/bank shared leave <编号>` | 退出并返还均摊份额 | 终端 |
| `/bank shared list` | 查看共享账户、余额、成员数、我的份额 | 无 |
| `/bank shared deposit <编号> <金额>` | 从钱包存入共享账户 | 终端会话 |
| `/bank shared withdraw <编号> <金额>` | 提现到钱包，超出净投入部分计个税收入 | 终端会话 |
| `/bank shared transfer <编号> <玩家> <金额>` | 共享余额划转到成员个人银行账户 | 终端会话 |
| `/bank reset` | 密保验证后重置密码（200） | 终端 |
| `/bank upgrade` | 查看等级进度并刷新评定 | 终端会话 |
| `/bank confirm` | 二次确认中风险大额转账 | 无 |
| `/bank cancel` | 取消待确认交易/输入流程 | 无 |
| `/pay <玩家> <金额>` | 远程转账（需 `fiscalcraft.pay`），每次输入密码 | 无 |

> 金额格式：最多两位小数，例如 `100`、`100.5`、`0.01`。不接受负数、科学计数法。

### 2. 管理员指令（`fiscalcraft.admin`）

| 指令 | 说明 |
| --- | --- |
| `/bank admin report` | 货币供应报表：总供应量、流通现金、银行存款、共享存款、贷款未还、财政池、欠税、账户数、活跃/逾期贷款、可疑记录、Top10 资产与集中度、基尼系数近似 |
| `/bank admin treasury` | 查看财政池 `system.treasury` 余额与发放用法 |
| `/bank admin treasury grant <玩家> <金额> [原因]` | 从财政池发放活动奖励到该玩家银行账户（会记流水与审计） |
| `/bank admin backup` | 立即备份并执行完整性校验 |
| `/bank admin terminal create <类型>` | 看向 6 格内箱子/木桶，创建指定类型的终端；类型：`full`/`account`/`cash`/`transfer`/`loan`/`shared` |
| `/bank admin terminal remove` | 看向终端，删除终端及其全息标识 |
| `/bank admin history <玩家> [条数]` | 全量流水追溯：双方 UUID、金额、前后余额、原因（默认 50，最多 200） |
| `/bank admin unlock <玩家>` | 清除该玩家的密码错误次数与锁定 |
| `/bank admin audit [玩家] [条数]` | 查看审计日志（默认 50 条） |
| `/bank admin suspicious [条数]` | 查看可疑交易日志（默认 30 条） |
| `/bank admin loan <玩家>` | 查看该玩家全部贷款记录 |

> 管理指令建议在游戏内执行。测试环境 Paper 132 控制台执行带参数指令会出现 Paper 上游 `CommandSourceStack.getLevel()` 的 NPE（`/plugins` 同样触发），与本插件无关。

---

## 三、权限节点

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `fiscalcraft.use` | true | 使用 `/bank` 基础功能 |
| `fiscalcraft.pay` | true | 使用远程转账 `/pay` |
| `fiscalcraft.admin` | op | 管理员总权限，包含以下子权限 |
| `fiscalcraft.admin.report` | op | `/bank admin report` / `treasury` |
| `fiscalcraft.admin.terminal` | op | 创建/删除银行终端 |
| `fiscalcraft.admin.backup` | op | 手动备份 |
| `fiscalcraft.admin.loan` | op | 贷款查询 |
| `fiscalcraft.admin.audit` | op | 审计与可疑日志 |
| `fiscalcraft.admin.unlock` | op | 解除密码锁定 |

### LuckPerms 配置示例

```text
# 全服默认：可使用银行与远程转账，禁用 EssentialsX 的 pay / loan
/lp group default permission set fiscalcraft.use true
/lp group default permission set fiscalcraft.pay true
/lp group default permission set essentials.pay false
/lp group default permission set essentials.loan false

# 保留 /bal（EssentialsX 默认已允许，通常无需改动）
/lp group default permission set essentials.balance true

# 银行管理员组
/lp creategroup bankadmin
/lp group bankadmin permission set fiscalcraft.admin true
/lp group bankadmin permission set fiscalcraft.admin.report true
/lp group bankadmin permission set fiscalcraft.admin.terminal true
/lp group bankadmin permission set fiscalcraft.admin.backup true
/lp group bankadmin permission set fiscalcraft.admin.audit true
/lp group bankadmin permission set fiscalcraft.admin.unlock true
/lp user <管理员名> parent add bankadmin
```

如只希望少数玩家使用远程转账，可先关闭默认权限再单独授权：

```text
/lp group default permission set fiscalcraft.pay false
/lp user Alice permission set fiscalcraft.pay true
```

---

## 四、经济规则速查

### 1. 资金安全模型

- 所有资金变动（存取、转账、利息、税收、贷款、还款、核销）进入**全局单线程串行队列**，操作顺序：行锁 → 读余额 → 校验 → 原子加减 → 写流水 → 事务提交。
- 余额全部使用 `long` 分币，不出现浮点余额。仅在与 Vault 交互的边界使用 `BigDecimal` 转换。
- 成功提示只在事务提交后发送；入队不代表成功。
- 数据库默认 SQLite WAL + `synchronous=FULL` + 单写者；MySQL 使用事务。主线程不做同步 JDBC。
- 条件更新 `WHERE balance >= ?` 与 `CHECK(balance >= 0)` 双重保证不会出现负数余额。
- 跨系统操作（Vault 钱包 ↔ 银行）先写 `wallet_ops` 意图记录，失败冲正，启动时自动恢复未完成操作。
- 收款方必须已开户，否则转账直接拒绝。

### 2. 账户与安全

- 开户、销户、办卡、补办、共享创建/加入/退出、密码重置只能在银行终端办理。
- 支付密码为 6 位数字，bcrypt（cost 12）+ 每个账号独立随机盐。
- 聊天框输入密码时事件在 `LOWEST` 优先级被取消：不广播、不进频道、不写日志；120 秒未输入自动超时，可用 `/bank cancel` 取消。
- 密码错误 5 次锁定账号 10 分钟；同一 IP 10 分钟内最多尝试 5 次。管理员可 `/bank admin unlock`。
- 输入密保答案同样走聊天拦截；重置密码收费 200。
- 销户：需终端会话并二次确认；有未还贷款、欠税、仍在任一共享账户中会被拒绝；成功后余额强制转回钱包、银行卡全部吊销。
- 取款、转账需要密码会话或实体卡会话；存款免密。

### 3. 银行终端类型、标识与会话

- 管理员用 `/bank admin terminal create <类型>` 把箱子/木桶/陷阱箱转换为终端；类型如下：

| 类型 key | 名称 | 可办理业务 |
| --- | --- | --- |
| `full` | 综合终端 | 全部银行业务 |
| `account` | 开户终端 | 开户、销户、密码重置、银行卡、等级、余额/流水/税务 |
| `cash` | 现金终端 | 存款、取款、余额/流水/税务 |
| `transfer` | 转账终端 | 银行转账、余额/流水/税务 |
| `loan` | 贷款终端 | 贷款申请、展期、还贷、额度查询、余额/流水/税务 |
| `shared` | 共享终端 | 共享账户创建/加入/退出/存取/划转、余额/流水/税务 |

- **明显标识**：每个终端上方有持久化全息文字（如“银行终端 · 现金终端 / 存款 / 取款 / 余额 / 流水 / 税务 / 右键使用”），并按终端类型每 2 秒生成彩色粒子；右键有提示音。全息标识在重启、区块重新加载后自动恢复，误删后每分钟自动补齐。
- **新玩家首次右键**：插件先查询账户；未开户不会被要求输入密码，而是提示“你还没有银行账户”，并引导直接输入 `/bank open`（若当前终端不支持开户，会提示前往开户/综合终端）。
- **已有账户右键**：手持本人有效银行卡可免密登录；否则聊天框输入 6 位支付密码。
- 登录成功后消息会显示当前终端类型与可办业务；在不适配的终端执行指令会被拒绝，例如“当前是现金终端，不支持该操作”。
- 会话绑定玩家 UUID + 方块坐标：每 tick 检查距离，超过 3 格立即失效；传送、切换世界、下线立即失效；最长时长按等级 5~15 分钟。
- 拆除终端或终端方块被破坏时，全息标识同步删除。

### 4. 利息、财富税、个人所得税

每日 00:00（`timezone` 指定时区）按顺序结算：**存款利息 → 财富税 → 个人所得税（前一日）→ 资产快照**。跨日停机后启动会补结算一次，且用 `last_daily_day` 防止重复日结。

| 项目 | 规则 |
| --- | --- |
| 存款利息 | 个人账户按 `daily-percent + 等级加成` 计息；共享账户按基础利率计息。利息优先由财政池支付，不足部分记系统发行。存款、利息不计个税。 |
| 财富税 | 税基 = `max(0, 钱包 + 银行余额 + 共享均摊份额 − 未还贷款本金)`；仅对超过起征点部分按日税率征收。关联账号先合并总额再判断起征点。只从银行余额扣税，不足部分记欠税。 |
| 个人所得税 | 仅对银行体系内“前一日收款 − 前一日付款”的正净额计征；存款、取款、利息、贷款本金、还本金不计税。 |
| 欠税 | 扣税不足不扣成负数，记欠税；下次收入优先补缴；欠税满 7 天锁定共享账户功能。 |
| 财政池 | 开户费、办卡费、手续费、税收均进入 `system.treasury`；管理员可查看、用 `/bank admin treasury grant` 发放活动奖励，并用于贷款坏账核销对冲。 |

### 5. 转账与限额

| 渠道 | 单笔上限 | 日累计 | 手续费 |
| --- | --- | --- | --- |
| 远程 `/pay` | 2000（固定） | 5000（固定） | 按付款方等级 0.5%~1% |
| 银行终端 | 10000 起，按等级提高至 20000 | 20000 | 按付款方等级 0.5%~1% |
| 共享账户存取/划转 | 共用终端单笔限额 | 共用终端日累计 | 划转按发起人等级收费 |

- 收款方不重复收手续费；实际到账金额计入收款方当日个税净收入（若收款方有欠税，先代扣欠税）。
- 超过日累计当天停止，次日 00:00 重置。
- 任何等级都不放开远程限额、税率、贷款授信倍数（红线）。

### 6. 账户等级

- 评定维度：30 天日均综合资产 + 30 天有效流水 + 合规分。
- 综合资产 = 银行余额 + 钱包 + 共享均摊份额 − 未还贷款本金。
- 有效流水只统计 `TRANSFER` / `REMOTE`，排除自己给自己转、关联账号互转、共享账户内部划转。
- 每月 1 号 00:00 自动重算；也可用 `/bank upgrade` 查看并刷新。等级不能用钱直接购买。
- 资产/流水跌破门槛进入 30 天缓冲期，逾期降一级；合规分跌破阈值立即临时降一级。

| 等级 | 日均资产 | 30 天有效流水 | 合规分 | 工本费 | 终端单笔 | 手续费 | 会话 | 查流水 | 利率加成 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 普通 | 0 | 0 | 无要求 | 0 | 10000 | 1.0% | 5 分 | 20 | 0 |
| 银卡 | 10000 | 50000 | ≥80 | 200 | 12000 | 0.9% | 6 分 | 30 | 0 |
| 金卡 | 50000 | 200000 | ≥85 | 500 | 15000 | 0.8% | 8 分 | 50 | +0.002% |
| 白金 | 200000 | 800000 | ≥90 | 1000 | 18000 | 0.6% | 10 分 | 80 | +0.005% |
| 黑金 | 500000 | 2000000 | ≥95 | 2000 | 20000 | 0.5% | 15 分 | 100 | +0.008% |

> 办卡总费用 = `fees.card-issue`（800）+ 当前等级工本费。

### 7. 风险子等级（独立于账户等级）

| 风险等级 | 影响 |
| --- | --- |
| 低风险 | 正常权益 |
| 中风险 | 大额转账需 `/bank confirm` 二次确认（阈值 `risk.medium-confirm-threshold`） |
| 高风险 | 非柜面交易受限，只能银行终端办理 |
| 黑名单 | 冻结转账，仅可取款与还贷 |

- 系统根据可疑标记累计动态升级；标记连续 7 天正常交易后自动解除（`risk.flag-expire-days`）。
- 单笔金额达到终端限额 80%（`risk.large-alert-ratio`）写入 `suspicious_log`，供管理员审查。
- 触发拦截时资金不离开付款方，并记录风控标记。

### 8. 共享账户

- 类型：亲情 / 情侣 / 公共，底层同一实现，仅显示标签不同。
- 创建费 500，成员上限 5，每人最多同时加入 3 个。
- 余额按成员数均摊计入各成员财富税；关联账号合并计税时只按该成员已均摊份额计一次。
- 提现到个人钱包：先冲减该成员历史净投入，超出部分计入当日个税收入。
- 所有共享操作记录操作人与流水；存取与划转走终端限额；欠税满 7 天锁定共享功能。

### 9. 贷款与征信

- 准入：已开户、账号年龄 ≥48 小时、信用分 ≥650、无未结清贷款、关联账号无逾期。
- 授信上限 = `min(single-max, 最近 14 天日均银行余额 × credit-multiplier)`，单笔上限 10000。
- 资金池：`min(pool-max − 未还本金, daily-disbursement − 当日已放款)`，参考准备金不全额放出。
- 期限利率：7 天档 0.05%/日；30 天档 0.04%/日。
- 放款先入银行账户；T+1 内禁止取款、转出、共享划转。
- 贷后全周期监控：向所有关联账号**合计**转出超过贷款额 20% 即拦截，按关联簇累计，不能靠拆分账号绕过。
- 允许提前还款无违约金；到期前可申请一次展期，延长 7 天并加息。
- 逾期日罚息 0.1%；到期前 3 天提醒。
- 信用分：按时还款 +5，逾期 −20，进入黑名单 −50。
- 逾期 30 天核销；全服每日核销上限 5000，超限挂账不清零。
- 贷款本金不计个税。

### 10. 风控与反洗钱

- 关联识别：同 IP + 近 7 天资金往来图聚类（不使用设备指纹）；同 IP 超过 4 人视为 NAT 不合并。
- 关联状态参与合并计税、合并授信、贷后合计监控。
- 关联只作观察标记，不立即封号；连续 7 天正常交易自动解除。
- 可疑识别：快进快出、T+1 内大额转出、拆分绕过限额、贷后集中转出。
- 单笔超过终端限额 80% 写 `suspicious_log`。

---

## 五、config.yml 逐项说明

> 金额单位：元（可写两位小数），内部转为分。百分比直接写百分数：`0.025` 即 0.025%。
> 修改后需重启生效。

### 1. 基础

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `currency-symbol` | `$` | 消息中显示的货币符号 |
| `timezone` | `Asia/Shanghai` | 日结、00:00 重置、备份时间所用时区 |

### 2. storage（存储）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `storage.type` | `sqlite` | `sqlite` 或 `mysql` |
| `storage.sqlite.file` | `fiscalcraft.db` | 相对 `plugins/FiscalCraft/` 的数据库文件名 |
| `storage.mysql.host` | `127.0.0.1` | MySQL 地址 |
| `storage.mysql.port` | `3306` | MySQL 端口 |
| `storage.mysql.database` | `fiscalcraft` | 数据库名（需提前创建） |
| `storage.mysql.username` | `fiscalcraft` | 用户名 |
| `storage.mysql.password` | 空 | 密码 |
| `storage.mysql.use-ssl` | `false` | 是否启用 SSL |

### 3. fees（费用，从钱包扣，进入财政池）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `fees.open-account` | 500 | 开户费 |
| `fees.card-issue` | 800 | 办卡基础费（另加等级工本费） |
| `fees.card-reissue` | 200 | 补办费 |
| `fees.shared-create` | 500 | 共享账户创建费 |
| `fees.password-reset` | 200 | 密保重置密码费 |

### 4. interest（存款利息）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `interest.daily-percent` | 0.025 | 存款日利率（%），每日 00:00 结算 |

### 5. wealth-tax（财富税）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `wealth-tax.enabled` | true | 是否启用 |
| `wealth-tax.threshold` | 50000 | 起征点（元），关联账号合并后判断 |
| `wealth-tax.daily-percent` | 0.02 | 超过起征点部分每日税率（%） |

### 6. income-tax（个人所得税）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `income-tax.free` | 500 | 0~500 免税额度 |
| `income-tax.brackets[].up-to` | 500 / 5000 / 20000 / -1 | 累进分档累计上限；`-1` 表示无上限 |
| `income-tax.brackets[].rate-percent` | 0 / 0.5 / 1.0 / 1.5 | 对应档位税率（%） |

### 7. tax

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `tax.arrears-lock-days` | 7 | 欠税满 N 天锁定共享账户功能 |

### 8. security（KYC / 密码安全）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `security.pin-length` | 6 | 支付密码位数（仅数字） |
| `security.fail-limit` | 5 | 单账号连续错误次数上限 |
| `security.lock-minutes` | 10 | 单账号锁定时长（分钟） |
| `security.ip-limit` | 5 | 单 IP 窗口内尝试次数上限 |
| `security.ip-window-minutes` | 10 | 单 IP 统计窗口（分钟） |
| `security.prompt-timeout-seconds` | 120 | 聊天框输入超时（秒） |

### 9. transfer（转账限额）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `transfer.remote-single-limit` | 2000 | 远程 `/pay` 单笔上限（红线，任何等级不放开） |
| `transfer.remote-daily-limit` | 5000 | 远程 `/pay` 日累计上限 |
| `transfer.terminal-daily-limit` | 20000 | 银行终端日累计上限 |

> 终端单笔上限在 `levels[].terminal-single-limit` 中按等级配置。

### 10. cards / shared

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `cards.max-per-player` | 5 | 每人有效银行卡上限 |
| `shared.max-members` | 5 | 单个共享账户成员上限 |
| `shared.max-per-player` | 3 | 每人同时加入的共享账户上限 |

### 11. relation（关联账号识别）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `relation.enabled` | true | 是否启用关联识别 |
| `relation.ip-window-days` | 7 | 同 IP 判定窗口（天） |
| `relation.edge-window-days` | 7 | 资金往来边判定窗口（天） |
| `relation.edge-min-count` | 2 | 两账号转账达到 N 次才建立资金边 |

### 12. risk（风控）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `risk.medium-confirm-threshold` | 5000 | 中风险需二次确认的金额阈值（元） |
| `risk.high-flag-count` | 2 | 累计 N 个可疑标记升级为高风险 |
| `risk.large-alert-ratio` | 0.8 | 单笔达到终端限额的该比例写入可疑日志 |
| `risk.flag-expire-days` | 7 | 正常交易 N 天后解除标记 |

### 13. loan（贷款）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `loan.enabled` | true | 是否开放贷款 |
| `loan.min-account-age-hours` | 48 | 账号最低年龄（小时） |
| `loan.min-credit-score` | 650 | 最低信用分 |
| `loan.avg-window-days` | 14 | 授信基数统计窗口（天） |
| `loan.single-max` | 10000 | 单笔上限（红线，任何等级不放开） |
| `loan.credit-multiplier` | 3 | 授信倍数（红线） |
| `loan.reserve-ratio` | 0.20 | 准备金比例（用于展示/参考，资金池另行限制） |
| `loan.pool-max` | 100000 | 可贷资金池上限 |
| `loan.daily-disbursement` | 50000 | 全服每日放款上限 |
| `loan.terms` | `7: 0.05, 30: 0.04` | 期限天数 : 日利率（%） |
| `loan.extension-days` | 7 | 展期延长天数 |
| `loan.extension-rate-bump-percent` | 0.01 | 展期后日利率上浮（%） |
| `loan.penalty-percent` | 0.1 | 逾期日罚息（%） |
| `loan.reminder-days` | 3 | 到期前提醒天数 |
| `loan.write-off-after-days` | 30 | 逾期核销天数 |
| `loan.write-off-daily-cap` | 5000 | 全服每日核销上限 |

### 14. credit（信用分）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `credit.start` | 600 | 新账户初始信用分 |
| `credit.max` | 900 | 信用分上限 |
| `credit.min` | 300 | 信用分下限 |
| `credit.on-time` | 5 | 按时还款加分 |
| `credit.overdue` | -20 | 逾期扣分 |
| `credit.default` | -50 | 进入黑名单扣分 |

### 15. level / levels（账户等级）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `level.flow-window-days` | 30 | 有效流水统计窗口（天） |
| `level.asset-window-days` | 30 | 日均资产统计窗口（天） |
| `level.grace-days` | 30 | 资产跌破门槛后的缓冲天数 |
| `level.eval-day-of-month` | 1 | 每月重算日 |

`levels[]` 每项字段：

| 字段 | 说明 |
| --- | --- |
| `key` | 内部标识（normal/silver/gold/platinum/black） |
| `label` | 显示名称 |
| `min-avg-assets` | 日均综合资产门槛 |
| `min-flow` | 有效流水门槛 |
| `min-compliance` | 合规分门槛 |
| `card-fee` | 该等级工本费（额外加在 `fees.card-issue` 上） |
| `terminal-single-limit` | 终端单笔限额 |
| `fee-percent` | 转出手续费（%） |
| `session-minutes` | 终端会话时长（分钟） |
| `history-limit` | 可查流水条数 |
| `interest-bonus-percent` | 存款利率加成（%） |

### 16. backup（自动备份）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `backup.enabled` | true | 是否启用每日自动备份 |
| `backup.hour` | 4 | 备份小时（0~23） |
| `backup.minute` | 0 | 备份分钟 |
| `backup.keep` | 7 | 保留最近 N 份备份 |

- SQLite 备份使用 `VACUUM INTO` 并执行 `PRAGMA integrity_check`。
- MySQL 备份调用系统 `mysqldump`（需服务器已安装并可用），密码通过临时环境变量传入。

### 17. treasury（财政池）

| 配置项 | 默认 | 说明 |
| --- | ---: | --- |
| `treasury.enabled` | true | 是否启用财政池 |
| `treasury.pay-interest-first` | true | 利息优先由财政池支付，不足部分记系统发行 |

---

## 六、数据库、备份与还原

### 1. SQLite（默认）

- 文件：`plugins/FiscalCraft/fiscalcraft.db`（同时存在 `-wal`、`-shm`）。
- 不要直接编辑数据库；不要只复制 `.db` 而漏掉 `-wal`。
- 使用 `/bank admin backup` 或每日自动备份生成一致性快照。

### 2. 备份

- 目录：`plugins/FiscalCraft/backups/`
- 文件名含时间戳；`backup.keep` 控制保留数量。
- 手动备份：`/bank admin backup`（游戏内）或由控制台执行（如遇 Paper 控制台 NPE，请在游戏内执行）。

### 3. 还原（SQLite）

1. 停服（必须）。
2. 备份当前 `fiscalcraft.db` 与 `-wal`、`-shm`。
3. 将备份文件复制为 `plugins/FiscalCraft/fiscalcraft.db`。
4. 删除旧的 `fiscalcraft.db-wal`、`fiscalcraft.db-shm`（如存在）。
5. 启动服务器，检查控制台无数据库错误。

### 4. MySQL

- 还原使用 `mysql -u<user> -p<db> < backup.sql`。
- 备份前请确认 `mysqldump` 在服务端 PATH 中可执行。

---

## 七、常见问题 FAQ

**Q1：`/pay` 还是 EssentialsX 的行为？**
A：插件在 `LOWEST` 优先级取消 `/pay` 并接管。若仍异常，确认没有其他插件更高优先级强制拦截；并在 LuckPerms 中把 `essentials.pay` 设为 false。`/minecraft:pay` 同样被接管。

**Q2：`/bal` 会不会被影响？**
A：不会。`/bal` 继续由 EssentialsX 输出 Vault 余额，FiscalCraft 不注册、不覆盖该指令。

**Q3：为什么转账提示“收款方未开户”？**
A：本插件要求收款方必须已在 FiscalCraft 开户，否则拒绝转账，避免资金进入无账户玩家。

**Q4：输入密码时聊天框没反应？**
A：这是正常现象：消息被取消，不会广播、不会进频道、不会写日志。请输入 6 位数字；120 秒未输入会超时，可 `/bank cancel` 取消。

**Q5：密码错误被锁定怎么办？**
A：等待 10 分钟；或管理员执行 `/bank admin unlock <玩家>`。同 IP 10 分钟最多 5 次尝试。

**Q6：开户/办卡提示余额不足？**
A：开户费、办卡费、补办费、共享创建费、密码重置费均从 Vault 钱包扣除，钱包不足则失败，不会产生负数余额。

**Q7：终端突然失效？**
A：离开终端超过 3 格、传送、切换世界、下线、会话超时都会失效，重新右键终端登录即可。

**Q8：为什么不能销户？**
A：有未结清贷款、欠税、仍在任一共享账户中任一项都会被拒绝。需先还清、补税、退出共享账户。

**Q9：财富税为什么按全家合并计算？**
A：为防止小号拆分免税，关联账号先合并资产总额再判断起征点；共享账户余额按成员均摊份额计入，且关联合并只计一次，不会重复计税。

**Q10：贷款为什么申请失败？**
A：检查：已开户、账号年龄 ≥48 小时、信用分达标、无未结清贷款、关联账号无逾期、金额不超过按 14 天日均余额计算的授信额度、资金池与每日放款额度未用尽。

**Q11：贷款后为什么不能马上取款/转账？**
A：放款后 T+1 内禁止取款、转出、共享划转；贷后全周期向所有关联账号合计转出超过本金 20% 会被拦截。

**Q12：共享账户提现为什么要交个税？**
A：提现先冲减该成员历史净投入；超出净投入部分视为当日收入，计入个税净额。存款、内部划转不计。

**Q13：备份文件如何验证？**
A：SQLite 备份在生成时会执行 `PRAGMA integrity_check`，结果为 `ok` 才判定成功；也可用 `sqlite3 backup.db "PRAGMA integrity_check;"` 手动校验。

**Q14：控制台执行 `/bank admin report` 报 NPE？**
A：测试环境 Paper 132 控制台对带参数指令存在上游 `getLevel()` NPE（`/plugins` 同样出现），请在游戏内以管理员身份执行。

**Q15：能修改 config 后热生效吗？**
A：不能。经济参数在启动时加载，修改后请重启，避免结算口径不一致。

**Q16：第一次右键终端为什么没让我输密码？**
A：这是正确行为。插件会先检查是否开户；未开户玩家直接引导 `/bank open`，避免新玩家陷入“没有账户却要求登录”的死循环。已有账户右键才会要求输入密码。

**Q17：为什么在某个终端存不了款/贷不了款？**
A：终端已按业务拆分。现金终端只能存取，贷款终端只能贷款/还贷，转账终端只能转账；余额、流水、税务查询在所有终端通用。看不到所需业务时，请前往对应的功能终端或综合终端。

---

## 八、自测记录（交付前回归）

### 1. 构建与单元测试

- 构建命令：`mvn test` / `mvn -DskipTests package`
- 测试结果：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
- 产物：`FiscalCraft-1.0.1.jar`（约 19 MB，已内置 SQLite/MySQL 驱动与 bcrypt）

| 测试用例 | 覆盖内容 |
| --- | --- |
| `openDepositWithdrawTransferHistory` | 开户、存、取、终端转账、流水查询全流程；手续费与余额核对 |
| `rejectionsAreEnforced` | 空账户取款、收款方未开户、远程超单笔限额、密码错误 5 次锁定均被拒绝 |
| `restartKeepsBalancesTransactionsLoansAndCredit` | 关闭并重开数据库后余额、流水条数、信用分、合规分完整保留 |
| `concurrentTransfersKeepBooksBalanced` | 8 线程 × 100 笔转账经单线程队列执行，全部成功；玩家+财政池总账对平；无负数；付款方余额归零 |
| `interestWealthAndIncomeTaxFollowConfig` | 利息按 0.025%/日、财富税按起征点与税率、个税按累进分档结算 |
| `sharedShareCountedOnceForRelatedCluster` | 共享账户按成员均摊、关联簇合并计税只计一次，不重复 |
| `loanTPlusOneAndRelatedOutflowLimit` | 贷款 T+1 禁取/禁转、关联簇合计转出超 20% 拦截 |
| `levelGraceAndComplianceDowngrade` | 等级门槛评定、资产不足 30 天缓冲、合规分跌破立即降级 |
| `dailyLimitAndArrearsRules` | 终端单笔/日累计限额、收款方欠税优先补缴 |
| `closeAccountReturnsBalanceToWallet` | 销户余额强制转回钱包、账户记录删除 |
| `terminalTypesRestrictOperations` | 综合/开户/现金/转账/贷款/共享终端的业务边界 |
| `backupCanBeRestored` | 备份完整性校验通过；用备份替换数据库后余额一致 |

### 2. Paper 真实环境冒烟

环境：Paper 1.21.11 build 132 + Vault 1.7.3 + EssentialsX 2.21.0 + Java 21。

- 插件 Remap / Load / Enable 成功；
- SQLite WAL 初始化成功，日志输出“FiscalCraft 已就绪”；
- `/bank` 无参帮助正常输出全部子命令；
- Vault Economy 正常注册（EssentialsX 提示服务器版本不受支持，但其 Vault 经济服务仍正常工作）；
- 服务器正常 `Done` 启动、正常停服。

### 3. 审计重点验证结论

| 审计点 | 结论 |
| --- | --- |
| 存款利率 vs 财富税套利 | 利息 0.025%/日、财富税仅对超 50000 部分按 0.02%/日；不存在“负余额套利”或闭环空转；边际净收益极小，可通过 config 调整 |
| 日切前存入银行规避财富税 | 税基已包含钱包+银行+共享份额−贷款本金，搬运资金不改变税基，无法规避 |
| 共享均摊与关联合并重复计税 | 按成员份额计入一次，簇内合计一次，税额按 gross 权重分配，不会重复 |
| 关联账号拆分绕过贷后 20% | 按关联簇累计转出，跨账号拆分同样计入合计，测试已验证拦截 |
| 并发双花 / 负余额 / 崩溃丢单 | 单线程队列 + 条件扣款 + CHECK 约束 + 事务 + wallet_ops 意图与启动恢复；100 笔并发对平 |
| 聊天框密码安全 | `ChatEvent` LOWEST 取消，不广播/不进频道/不写日志；bcrypt + 每账号独立盐；账号与 IP 双限流 |

---

## 九、文件与目录速查

```text
plugins/FiscalCraft/
├── config.yml          # 配置
├── fiscalcraft.db      # SQLite 主库（WAL 模式另有 -wal/-shm）
└── backups/            # 自动/手动备份
```

---

**FiscalCraft v1.0.1** · 所有资金变动串行、可审计、可恢复。
