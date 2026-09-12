# PowerProtector

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `PowerProtector` |
| 父类 | `PowerGenerator` |
| 分类 | Category.power |
| 尺寸 | 2x2 |
| 血量 | 600 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 150 |
| Lead | 100 |
| Graphite | 80 |
| Silicon | 70 |
| Thorium | 50 |
| Plastanium | 40 |
| Phase Fabric | 20 |

## Block 属性

- `hasItems`: false
- `hasPower`: true
- `consumesPower`: true
- `outputsPower`: true
- `update`: true
- `solid`: true
- `configurable`: true（新版加入配置面板：启用/禁用开关、剩余保护时间、欠款、当前供电）
- `replaceable`: false（不可被其他方块替换放置）

## 机制说明

### 核心机制

电力保护器：电网亏电时补电（记欠款），电网富余时偿还欠款。新版采用**存档级共享状态**架构：

1. **共享 `State`**：同一电网同队伍的保护器共享同一个 `State` 引用（模式/冲突/提示/会话时间，由最早放置者作 Master 维护）。数据字段写入每台保护器的存档（write/read），属存档层面而非全局静态。
2. **全队时间池**：全队共享"可用保护时间" `remainingProtectionTime`，跨电网由队伍 Master 统一维护消耗与回充。保护时按每台 1x 扣减；全队无欠款才回充（每 `restoreInterval` 秒恢复 1 秒可用时间）。
3. **欠款（debt）**：每台保护器独立计算与偿还（不同步）。
4. **模式（纯显示）**：`Normal / Protecting / Recovering / Blocked / Error / Stopped`，根据实际供电/消耗/空闲/阻塞状态自动推导，不控制逻辑。
5. **供电/消耗逻辑独立**：满足条件即执行，不受模式切换影响。

### 具体逻辑

- **保护**：电网净盈余为负（`gridNet < 0`）&& 无存储电力 && 无冲突/阻塞 && 全队时间池有剩余 → 填补电网缺口，累计 `debt`。
- **恢复**：自家有欠款 && 电网富余（`gridNet > 0`）→ 从电网富余 + 电池按"均摊本金 + 利息 + 手续费"偿还欠款。利率 `recoveryRatePerSecond`、手续费 `recoverySurcharge`。
- **冲突检测**：同电网同队伍存在 >1 台保护器 → `error=true`，停止工作（阻塞）。
- **阻塞**：同电网其他保护器正在 保护/恢复 → 本机 `Blocked`，不工作。
- **PowerVoid 检测**：电网存在 PowerVoid 时停止工作。
- **拆除限制**：欠款（`debt > 0`）时不可拆除（防通过拆除抹掉欠款），经消息系统发送禁止拆除提示（瞬时消息·淡灰色气泡·×图标·低优先级，`lastBreakToast` 节流防刷屏）。
- **手动停止**：配置面板可启用/禁用（切换 `stopped`），停止后不供电不恢复。
- **持续型缺电消息**：进入保护时向本队玩家投递一条持续型消息（红底·电源图标·高优先级），内容实时显示全队剩余可用保护时间（直读全队时间池）；消息弹出时播放警示音效 `sounds/power-protector.ogg`（经 Message 内置音效机制，联网时同步资源名）。全队无任何保护器在保护时自动撤下。多台保护器同时触发时会去重，全队始终保持仅一条。

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `protectionTime` | float | 90 * 60f | 全队可用保护总时长（tick），默认 90 秒 |
| `recoveryRatePerSecond` | float | 0.02f | 恢复利率（每秒 2%） |
| `recoverySurcharge` | float | 0.1f | 偿还手续费比例（10%） |
| `restoreInterval` | float | 5f | 每满该秒数线性恢复 1 秒可用保护时间 |
| `maxDebt` | float | 100000f | 欠款进度条满格对应的归一化值（仅显示） |
| `warmupSpeed` | float | 0.1f | 预热速度 |

## 电力系统

- **消耗方式**: `consumePowerDynamic` 动态消耗（恢复阶段按 `state.tickRPower` 消耗）
- **产出方式**: `getPowerProduction` 保护阶段返回 `state.tickPPower`，否则 0
- **恢复公式**: 均摊本金 `debt / max(protectionTimer,1)` + 利息 `debt * recoveryRate / 60`，乘 `(1+recoverySurcharge)`

## 物品处理

- 无物品处理

## 状态栏 (Bars)

1. **状态**: 当前模式文本（Normal/Protecting/Recovering/Blocked/Error/Stopped），颜色随模式
2. **可用保护时间**: 青色，`remainingProtectionTime / protectionTime`
3. **欠下电力**: 淡橙，`debt / maxDebt`

## 配置面板

- 状态徽章（颜色随模式）
- 可用保护剩余时间（秒）
- 欠下电力
- 当前供电（/s）
- 启用/禁用切换按钮（红色高亮 checked）

## 序列化

- 版本: 14
- 保存字段: `remainingProtectionTime`、`debt`、`restoreTimer`、`error`（均来自 `state`）

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
| a0.8.5.0 | 修复禁用时自动恢复enabled的行为、日志改用SiliconLog |
| a0.12.1.0 | 修复恢复模式频繁断开/重连电网导致物品传输中枢跳变 |
| a0.12.3.0 | **重写为存档级共享 State 架构**：全队共享可用保护时间池、独立欠款计费与偿还、模式枚举（Normal/Protecting/Recovering/Blocked/Error/Stopped）、配置 UI 面板（启用/禁用/剩余时间/欠款/供电）、电力不足与禁止拆除提示横幅、警报音效、欠款期禁止拆除、不可replaceable |
