# UniversalJunction（万向交叉器）

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `UniversalJunction` |
| 父类 | `Block` |
| 分类 | Category.distribution |
| 尺寸 | 1x1 |
| 血量 | 默认 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 15 |
| Lead | 10 |
| Graphite | 8 |
| Silicon | 5 |

## Block 属性

- `hasItems`: false（使用 DirectionalItemBuffer，非标准物品容器）
- `hasPower`: false
- `update`: true
- `solid`: false
- `underBullets`: true
- `configurable`: true（支持配置面板）
- `saveConfig`: true
- `copyConfig`: true
- `drawArrow`: false
- `floating`: true
- `noUpdateDisabled`: true
- `alwaysUnlocked`: true

## 机制说明

### 核心机制

1×1 物品交叉器：可为四个输入方向分别配置各输出方向的优先级 (0~4)，数值越大越优先输出，0 表示不输出；同优先级的方向轮流输出实现均分。方向满载时短暂等待重试，连续堵塞才降级到次高优先级，且降级后持续生效、恢复时自动切回。传输速率 50 物品/秒。

### 方向约定

- UI 中：0=上(北) 1=右(东) 2=下(南) 3=左(西)
- 游戏角度编码：0=东 1=北 2=西 3=南（`relativeTo()` 返回值）
- 转换：`angleToSource(angle) = 3 - angle`，`cardinalToAngle(dir) = dir ^ 1`

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `moveTime` | float | 1.2 | 移动一个物品所需的 tick 数（60/1.2=50 物品/秒） |
| `capacity` | int | 16 | 每个方向的缓冲容量 |

### 路由算法

- **优先级 0~4**：0=不输出，4=最高
- **同优先级** → 轮流均分（roundRobin）
- **满载降级** → 连续满载 10 tick 降级到次高优先级
- **恢复探测** → 每次 pickOutput 时探测更高组是否恢复

### 配置面板

点击方块弹出配置界面，包含三个区域：

1. **全局输出优先级**：4 个滑块控制全局默认值，应用到所有输入方向
2. **按方向覆盖**（可折叠）：选择输入方向后，为该方向单独配置 4 个输出优先级
3. **模板管理**：保存/应用/删除自定义配置模板

### 配置序列化

- 格式：20 个逗号分隔整数（16 矩阵 + 4 全局默认行）
- 兼容旧版 16 值格式（无全局默认行，取第一行作默认）
- `config(String.class, ...)` 通过网络同步

## UI 版本

支持两套配置界面，通过 Silicon 设置页「万向交叉器新版界面」开关切换：

- **经典版**（默认）：模板区 → 全局优先级 → 覆盖区
- **新版**：全局优先级 → 覆盖区 → 模板区（常用在前），快捷按钮精简为「均分」+「清零」，模板列表始终可见

## 序列化

- 版本: 2
- 保存字段: weights[4][4]（优先级矩阵）+ defaultRow[4]（全局默认行）+ DirectionalItemBuffer
- 旧存档（revision < 2）：取 weights[0] 作全局默认

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.12.1.0 | 初始创建 |
| a0.12.2.0 | UI 优化（同值折叠增量更新、缓冲区溢出修正、dirName 缓存、tapOpen 跨方向保持、确认弹窗、禁用方向红色标记/连接边视觉反馈）+ 新版 UI 重构：预设模式（9种常用配置一键应用）+ 自定义组号按钮（点击切换层级），零冗余规范化 |
