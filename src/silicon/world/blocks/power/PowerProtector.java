package silicon.world.blocks.power;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.style.NinePatchDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.util.Align;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.core.UI;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.util.MessageSystem;
import silicon.util.MessageSystem.Handshake;
import silicon.util.MessageSystem.Message;
import silicon.util.MessageSystem.MessageType;

import static mindustry.Vars.control;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * PowerProtector - 电力保护器（重写版）
 * <p>
 * 核心功能：参考原版电池的工作方式 —— 当电网电力供应不足且所有电池电量耗尽时，
 * 保护器切入保护模式，动态补足电网中所有无法被满足的 consumer。
 * <p>
 * 工作机制：
 * <ul>
 *   <li><b>保护</b>：电网净缺口存在且电池电量耗尽时，保护器产出的电力恰好补满缺口，
 *       并按 10% 额外损耗计算（缺口 × 1.1）。补出的电力记入该保护器自己的欠款 debt。</li>
 *   <li><b>偿还</b>：电网电力供应充足（富余）时，保护器用富余电力偿还自己的欠款。</li>
 *   <li><b>时间池</b>：每队一个共享时间池（存入存档、跨建筑跨电网共用），限制保护器可
 *       保护的总时长。保护时按参与保护的保护器数量扣减。</li>
 *   <li><b>回充</b>：只要该队任一保护器仍欠下电力，时间池就不回充；全部还清后，
 *       时间池持续恢复（每 5 秒恢复 1 秒可用保护时间）。</li>
 *   <li>每台保护器的欠款互相独立，互不影响。</li>
 * </ul>
 * 说明：原版 PowerGraph.update() 先于 Building.updateTile() 运行，因此本保护器在
 * updateTile() 中算出的产出要到下一帧才进入电网（1 帧延迟）。所有发电机与电池本身
 * 均受此限制，持续 consumer 会在下一帧即被补足，实际效果无缝。
 */
public class PowerProtector extends PowerGenerator {
    /**
     * 队伍级共享时间池注册表（静态、以队伍为键、跨建筑跨电网共享）。
     * <p>
     * 数据通过每台保护器的 write/read 持久化到存档 —— 每台保护器都写入/读取同样的
     * 时间池数值副本。为防跨存档污染，在 WorldLoadEvent 时依据已加载建筑清空并重建。
     */
    static final ObjectMap<Team, TeamPool> teamPools = new ObjectMap<>();

    /**
     * 默认满额保护时间（tick）：新建队伍时间池、新建保护器 state 与默认 protectionTime 共用。
     * 应保持与 protectionTime 默认值一致；块级 protectionTime 更改不影响已存在的时间池。
     */
    static final float defaultProtectionTime = 90 * 60f;

    /** 取得（或创建）某队伍的时间池。 */
    static TeamPool pool(Team team) {
        return teamPools.get(team, TeamPool::new);
    }

    /**
     * 全队「电力不足」持续型消息注册表（静态、以队伍为键）。
     * <p>
     * 多台保护器同队同时进入保护时只投递一条（首个触发者创建、其余复用），
     * 解决「多保护器同时被触发 → 消息面板刷屏/重复」的问题。
     * 该队没有任何保护器在保护时断开握手，由系统清除；WorldLoadEvent 时清空防跨存档污染。
     */
    static final ObjectMap<Team, Message> powerShortageMessages = new ObjectMap<>();

    /** 该队当前是否有保护器正处于保护（峰值锁定介入，nextTickPPower 高于显示阈值）。 */
    static boolean teamHasActiveProtector(Team team) {
        for (Building b : Groups.build) {
            if (b instanceof PowerProtectorBuild ppb && ppb.team == team
                    && ppb.power != null && ppb.power.graph != null
                    && ppb.state.nextTickPPower > 0.05f) {
                return true;
            }
        }
        return false;
    }

    /** 队伍共享时间池：可用保护时间 + 回充累积计时。 */
    static class TeamPool {
        float remainingProtectionTime;
        float restoreTimer = 0f;

        TeamPool() {
            remainingProtectionTime = defaultProtectionTime;
        }

        TeamPool(float remaining, float timer) {
            this.remainingProtectionTime = remaining;
            this.restoreTimer = timer;
        }
    }

    /** 抢先提交标记：Trigger.update 钩子是否已注册（惰性，只注册一次）。 */
    private static boolean submitHookRegistered = false;

    /**
     * 惰性注册 Trigger.update 抢先钩子。
     * <p>
     * Trigger.update 在每个 tick 的最早时刻触发（先于 Time.update、Groups.powerGraph.update、
     * Groups.build.update）。此处遍历当前所有保护器，把上一帧 updateTile() 计算出的
     * nextTickPPower 提交为 tickPPower —— 这样当帧 PowerGraph.update() 读取 getPowerProduction()
     * 时读到的是最新计算值，彻底消除「updateTile 计算 → PowerGraph 读取」之间的 1 帧空转，
     * 使瞬时 consumer 在同帧即被满足。
     */
    private static void registerSubmitHook() {
        if (submitHookRegistered) return;
        submitHookRegistered = true;
        Events.run(EventType.Trigger.update, () -> {
            for (Building b : Groups.build) {
                if (b instanceof PowerProtectorBuild ppb) {
                    ppb.state.tickPPower = ppb.state.nextTickPPower;
                }
            }
        });
    }

    /** 保护总时长（tick），默认 90 秒。 */
    public float protectionTime = defaultProtectionTime;
    /** 保护损耗倍率：补足缺口时额外计算的损耗（10%）。 */
    public float lossMultiplier = 1.1f;
    /** 回充间隔秒数：每达到该时长线性恢复 1 秒可用保护时间。 */
    public float restoreInterval = 5f;
    /**
     * 电池耗尽阈值（power/tick 绝对量）。
     * 当电网电池存量低于该值时视为「其他电池电量耗尽」，保护器才介入供电。
     * 介入后电网被恰好补平，电池既不充也不放、存量锁定在耗尽附近，状态稳定。
     */
    public float batteryEmptyPower = 1f;
    /**
     * 峰值保持衰减速率（0~1，每帧乘该系数）。
     * 保护器记忆「曾观测到的缺口峰值」。因 0.999 门控方块（如物品中枢）在欠压瞬间
     * 会彻底停机并清零请求，若保护器完全跟随实时缺口，会在其停机期间误判电网富余而
     * 撤产，形成「中枢停机 → 保护器撤产 → 电压更低 → 中枢永不恢复」的死锁。
     * 峰值保持让保护器在缺口记忆中继续介入，直到电网真正自足（净盈余持续为正）才衰减解除。
     */
    public float peakDecay = 0.98f;
    /**
     * 保护介入阈值（power/tick 绝对量）：峰值缺口高于该值才视为确实需要保护。
     * 用于吸收正常供需抖动，避免因单个 consumer 的瞬时波动误入保护。
     */
    public float protectionGapThreshold = 1f;
    /**
     * 锁定期间最小供电（power/tick 绝对量）。
     * 0.999 门控方块（如物品中枢：欠压即停机、冷却 60 帧后以 PROBE_DRAW=10 探测、下一帧验证
     * 电压是否满格）注定「探测帧请求先于保护器产出浮现」。若锁定期间只跟随实时缺口，
     * 探测请求会在帧内让电压瞬时跌破 0.999 → 探测失败 → 冷却重置 → 永久死锁。
     * 故锁定期间即使实时缺口消失，也维持该地板供电，保证探测请求有电可拿、验证帧电压恒满。
     * 默认 10 对齐物品中枢 PROBE_DRAW；剩余实时缺口较大时以实时缺口为准。
     */
    public float latchFloorPower = 10f;
    /**
     * 进入恢复（偿还）前所需的电网连续富余时长（秒）。
     * 电网波动会让净盈余逐帧正负跳动，若瞬时富余就立即开还，会在下一帧波动到来时被打断，
     * 导致「恢复中 ↔ 保护中」反复横跳、欠款永远还不清。此处要求富余稳定持续一段时间
     * 才开始偿还，抑制瞬时抖动进入恢复会话。
     */
    public float restoreEnterTime = 0.5f;
    /**
     * 恢复会话期间电网缺口持续该时长（秒）才切回保护。
     * 恢复中电网小幅波动不打断偿还会话（欠款照还不受单帧波动影响），仅当缺口真正持续存在
     * （电网确实又不自足）才退出恢复，交由峰值锁定重新介入保护 —— 避免托辞来回乱跳。
     */
    public float protectReturnTime = 0.5f;
    /**
     * 保护会话期间电网连续自足（无缺口）该时长（秒）才确认退出保护。
     * 保护中电网自足后不再产出、不再记债。若只以「瞬时无缺口」判定退出，电网的小幅波动会反复
     * 打断退出；此处要求自足持续稳定一段时间才真正撤产，且退出后清空峰值记忆，防止残留阈值
     * 立即误判重新介入 —— 与恢复的滞回对称。
     */
    public float protectExitTime = 0.5f;
    /** 欠款进度条满格对应的欠款值（仅用于显示归一化）。 */
    public float maxDebt = 100000f;

    // 拆除提示节流（避免 validBreak 轮询时刷屏）
    private static float lastBreakToast = Float.NEGATIVE_INFINITY;

    // 启用按钮样式：与 flatTogglet 相同，但 checked 高亮边框为红色（Pal.remove）。懒加载。
    private static TextButtonStyle redToggle;

    private static TextButtonStyle redToggle() {
        if (redToggle == null) {
            redToggle = new TextButtonStyle(){{
                font = Fonts.def;
                fontColor = Color.white;
                up = Styles.flatTogglet.up;
                over = Styles.flatTogglet.over;
                down = ((NinePatchDrawable)Styles.flatDown).tint(Pal.remove);
                checked = down;
                disabled = Styles.flatTogglet.disabled;
                disabledFontColor = Color.gray;
            }};
        }
        return redToggle;
    }

    public PowerProtector(String name) {
        super(name);
        update = true;
        solid = true;
        hasPower = true;
        consumesPower = true;
        outputsPower = true;
        size = 2;
        health = 600;
        envEnabled = Env.any;
        configurable = true;
        saveConfig = false;
        displayFlow = false;
        drawArrow = false;
        // 配置走标准 configure 链路（联网时经 Call.tileConfig 广播到全端一致）：
        // 启停 = Boolean 配置类（与逻辑 `enabled` 指令共用同一字段）；恢复电池占比 = Float 配置类（滑块提交）。
        config(Boolean.class, (building, value) -> building.enabled = value);
        config(Float.class, (building, value) ->
            ((PowerProtectorBuild) building).state.restoreBatteryPercent = Mathf.clamp(value, 0f, 1f));
        // 不可被其他方块覆盖替换（放置时红色无效）
        replaceable = false;
        // 动态消耗：偿还时按 tickRPower（上一帧算好的偿还速率）消耗电网电力还债，否则为 0。
        consumePowerDynamic(entity -> {
            PowerProtectorBuild ppb = (PowerProtectorBuild) entity;
            return ppb.state.tickRPower;
        }).optional(false, false);

        // 世界（重新）加载时依据已加载建筑重建队伍时间池注册表，避免跨存档污染。
        // WorldLoadEvent 在建筑构造（read 已执行、state 已恢复）之后触发，故既能保留
        // 已存档数据，又能清掉上一局残留的池。
        Events.on(EventType.WorldLoadEvent.class, e -> {
            teamPools.clear();
            powerShortageMessages.clear();
            for (Building b : Groups.build) {
                if (b instanceof PowerProtectorBuild ppb && !teamPools.containsKey(ppb.team)) {
                    teamPools.put(ppb.team, new TeamPool(ppb.state.remainingProtectionTime, ppb.state.restoreTimer));
                }
            }
        });

        // 惰性注册 Trigger.update 抢先提交钩子（只注册一次）。
        registerSubmitHook();
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.repairTime, protectionTime / (60 * 60), StatUnit.minutes);
    }

    @Override
    public void setBars() {
        super.setBars();

        addBar("status", (PowerProtectorBuild entity) -> new Bar(
                entity::modeText,
                entity::modeColor,
                () -> 1f));

        addBar("available", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.get("block.silicon-power-protector.ui.availableTime"),
                () -> Color.cyan,
                () -> Mathf.clamp(entity.state.remainingProtectionTime / protectionTime)));

        addBar("debt", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.get("block.silicon-power-protector.ui.totalSpent"),
                () -> Pal.powerBar,
                () -> Mathf.clamp((float) (entity.state.debt / maxDebt))));
    }

    @Override
    public boolean canBreak(Tile tile) {
        // 电网冲突中的保护器强制停机、未在保护亦无法偿还，需允许拆除以解除冲突；否则
        // 若双方均欠债将无法拆除任何一台而陷入死锁。其余情况欠债保护器禁止拆除（防弃债跑路）。
        if (tile != null && tile.build instanceof PowerProtectorBuild ppb && ppb.state != null
                && ppb.state.debt > 0 && !ppb.state.conflict) {
            if (Time.time - lastBreakToast >= 90f) {
                lastBreakToast = Time.time;
                if (!mindustry.Vars.headless && !state.isMenu()) {
                    ppb.showCannotBreakBanner();
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        return true;
    }

    /**
     * 显示模式（纯信息展示，不参与任何运行决策）。
     * <p>
     * 运行完全由内部状态自行驱动（峰值锁定 latching、产出 nextTickPPower、偿还 tickRPower、
     * 原版禁用开关 enabled），Mode 仅在 updateMode() 末尾由这些运行数值派生一次，
     * 供徽章/字体颜色等 UI 阅读。任何运行逻辑不得读取 Mode；UI 逻辑如需判断状态，
     * 也应直接读取运行字段而非 Mode，模式只提供文案与颜色的映射。
     */
    public enum Mode {
        Normal,      // 待机（未保护、未偿还）
        Protecting,  // 保护中（正在供电补缺）
        Recovering,  // 偿还中（正在消耗电网电力还清欠款）
        Stopped,     // 已禁用（UI 启停按钮或逻辑 `enabled` 指令关闭，不保护、不偿还）
        Error        // 电网冲突：同电网存在其他保护器，强制停机直至仅剩本保护器
    }

    /** 状态数据（存档字段 + 运行时临时变量），每台保护器一个实例。 */
    public static class State {
        // ===== 存档字段 =====
        /** 全队共享可用保护时间（tick），为全队时间池副本。 */
        public float remainingProtectionTime = defaultProtectionTime;
        /** 全队回充累积器（tick），为全队时间池副本。 */
        public float restoreTimer = 0f;
        /** 自家欠下电力（每台保护器独立计算与偿还）。 */
        public double debt = 0;
        /** 进入恢复模式所需的电网电池电量占比（0~1），由玩家在 UI 滑块调整并存入存档。
         *  每台保护器独立配置，互不影响。默认 25%。 */
        public float restoreBatteryPercent = 0.25f;
        // ===== 运行时临时变量（不存档）=====
        public Mode mode = Mode.Normal;                   // 显示模式（仅用于 UI 文案/颜色，不参与运行）
        public float tickPPower = 0f;                     // 保护供电（由 Trigger.update 抢先提交后的当帧产出）
        public float nextTickPPower = 0f;                 // 缓冲：本帧 updateTile 算出的目标产出，供下一帧 Trigger 提交
        public float tickRPower = 0f;                     // 偿还消耗（自家）
        public float peakGap = 0f;                        // 曾观测到的缺口峰值（每帧衰减，电网真自足后解除）
        public boolean latching = false;                  // 是否处于峰值锁定期（介入记忆，防止冷却撤产）
        public boolean restoring = false;                 // 恢复（偿还会话）滞回标志：一旦进入持续到欠款还清或电网真缺电
        public float restoreHold = 0f;                    // 连续富余计时（秒），达到 restoreEnterTime 才进入恢复
        public float gapHold = 0f;                        // 恢复会话中连续缺口计时（秒），达到 protectReturnTime 才退出恢复
        public float protectExitHold = 0f;                // 保护会话中连续自足计时（秒），达到 protectExitTime 才确认退出保护
        public float batteryStoredPrev = -1f;             // 上一帧结算后的电网电池存量（用于推算本帧电池净吸入量；-1 表示未初始化）
        public boolean conflict = false;                   // 电网中存在其他保护器（运行时派生，不存档）：强制停机并在 UI 显示错误
    }

    public class PowerProtectorBuild extends GeneratorBuild {
        @Override
        public void updateTile() {
            // 每帧把全局队伍时间池刷到本地副本
            TeamPool tp = pool(team);
            state.remainingProtectionTime = tp.remainingProtectionTime;
            state.restoreTimer = tp.restoreTimer;

            // 全队「电力不足」持续型消息管理：多保护器同队共用一条，全队停止保护即撤下
            manageTeamWarnMessage();

            if (power == null || power.graph == null) {
                state.nextTickPPower = 0f;
                state.tickRPower = 0f;
                state.batteryStoredPrev = -1f;
                // 电网离场：冲突标记需清除，待重连电网后由 gridHasOtherProtector() 重新判定
                state.conflict = false;
                updateMode();
                refreshConfigUI();
                return;
            }

            // —— 电网冲突检测：同电网存在其他保护器则强制停机 ——
            // 优先级高于禁用：即使逻辑/UI 已启用也强制停机，直至电网仅剩本保护器，
            // 之后本帧继续按后续派驻逻辑直接恢复运行（不再需要手动重新启用）。
            if (gridHasOtherProtector()) {
                stopFromConflict();
                updateMode();
                refreshConfigUI();
                return;
            }
            state.conflict = false;

            // —— 禁用（逻辑 `enabled` 指令 / UI 启停按钮共用同一字段）——
            // 禁用时保护器立即离场：撤产出、清保护/恢复会话，但保留欠款与全队时间池。
            // 逻辑处理器通过原版 enabled 字段即可暂停本方块，与原版「应该消费才消费」的语义一致。
            if (!enabled) {
                state.nextTickPPower = 0f;
                state.tickRPower = 0f;
                state.latching = false;
                state.peakGap = 0f;
                state.restoring = false;
                state.restoreHold = 0f;
                state.gapHold = 0f;
                state.protectExitHold = 0f;
                // 禁用期间电网可能继续充电；把电池存量快照重置为续接点，重新启用后不计入禁用期变化
                state.batteryStoredPrev = -1f;
                manageTeamTimePool();
                updateMode();
                refreshConfigUI();
                return;
            }

            // —— 电池式结算信号 ——
            // 原版 PowerGraph.update() 在 consumer 有需求且发电不足时用电池补缺：
            //   useBatteries(powerNeeded - powerProduced)
            // 这里以同一天的权威结算值（getPowerNeeded/getPowerProduced）为信号，
            // 复刻「发电不足且有 consumer → 供电」的电池行为，而非自行判断状态。
            float needed = power.graph.getPowerNeeded();
            float produced = power.graph.getPowerProduced();
            float selfP = state.tickPPower;                            // 自身已提交产出
            float selfC = state.tickRPower;                            // 自身偿还消耗
            // 真实电网净盈余（扣除本保护器自身的产出/消耗，避免自我反馈放大）：
            //   produced 已含 selfP，needed 已含 selfC
            float netSurplus = (produced - selfP) - (needed - selfC);

            // 峰值保持缺口：记忆此前的电网亏空。
            // 0.999 门控方块（如物品中枢）欠压时会彻底停机、冷却并清零请求，电网瞬时“看起来”富余；
            // 若完全跟随实时缺口会在此刻误判富余而撤产，形成「停机 → 撤产 → 电压更低 → 永不恢复」死锁。
            // peakGap 让保护器在峰值记忆期间保持介入，待电网真正自足后才指数衰减解除。
            float currentGap = Math.max(0f, -netSurplus);
            state.peakGap = Math.max(currentGap, state.peakGap * peakDecay);

            // 电池耗尽才介入供电：仅当其他电池电量耗尽（存量低于阈值）时，本保护器放「电」。
            // 介入后电网被恰好补平 → 电池既不充也不放、存量锁定在耗尽附近 → 状态稳定。
            boolean batteriesEmpty = power.graph.getBatteryStored() <= batteryEmptyPower;

            // —— 恢复（偿还会话）滞回 ——
            // 逐帧瞬时判定会让恢复在电网波动时反复横跳：只要有一帧富余就开还，下一帧小缺口又立即
            // 停还并切回保护，欠款永远还不清、模式在「恢复中 ↔ 保护中」来回抖动。改为会话式：
            // 电网连续富余 restoreEnterTime 才进入恢复，且电网电池需恢复到玩家设定占比
            // （restoreBatteryPercent，每台独立配置）——只有电池真的回充到一定水平，
            // 才说明电网富余是可靠的、足以支撑偿还会话。进入后小幅波动（净缺口未持续
            // protectReturnTime）不打断偿还会话（有富余就还、无富余则挂起）；放宽重进保护的条件：
            // 仅当「缺口确实持续存在 且 电池也确已耗尽」才退出恢复、交由保护介入 —— 只有电网
            // 又开始真实吃紧（电池被榨干）才会打断偿还，防止小波动把恢复会话掐断。
            boolean surplusNow = netSurplus > 0f;
            // 电网电池电量占比（无电池按充足处理，避免无电池电网永远无法恢复）
            float batteryRatio = power.graph.getTotalBatteryCapacity() > Mathf.FLOAT_ROUNDING_ERROR
                    ? power.graph.getBatteryStored() / power.graph.getTotalBatteryCapacity()
                    : 1f;

            if (state.restoring) {
                // 恢复会话中：连续缺口计时，达到阈值且电池确已耗尽才退出恢复（保护接管）
                state.gapHold = surplusNow ? 0f : state.gapHold + Time.delta;
                if (state.debt <= 0f || !enabled || (batteriesEmpty && state.gapHold >= protectReturnTime)) {
                    state.restoring = false;
                    state.restoreHold = 0f;
                    state.gapHold = 0f;
                }
            } else {
                // 未在恢复：电网富余稳定了一段时间，且电池电量达到设定占比，才进入恢复。
                // 仅在保护已退出（!state.latching）后才允许进入，避免「保护中却同时进入恢复」的错乱
                if (surplusNow && !state.latching && enabled && state.debt > 0f
                        && batteryRatio >= state.restoreBatteryPercent) {
                    state.restoreHold += Time.delta;
                    if (state.restoreHold >= restoreEnterTime) {
                        state.restoring = true;
                        state.restoreHold = 0f;
                    }
                } else {
                    state.restoreHold = 0f;
                }
            }

            // —— 保护（介入）会话：进入即时、退出带自足确认，与恢复滞回对称 ——
            // 进入：真实缺口（peakGap 超标）且电池耗尽才介入。保持：不随 batteryStored 波动退出，
            // 转为要求「电网自身（剔除本保护器）连续自足保护 protectExitTime」才确认撤产。
            // 与恢复对称地防止模式在「保护中 ↔ 正常」之间因瞬时波动抖动：退出是稳定确认而非瞬时判定。
            // 自足 = 剔除本保护器后仍有富余（netSurplus >= 0），该富余期间我们会继续产出，
            // 待确认窗口走完后才真正停手，电网不会瞬间失去支撑。
            boolean wantProtect = state.peakGap > protectionGapThreshold
                    && state.remainingProtectionTime > Mathf.FLOAT_ROUNDING_ERROR
                    && !state.restoring
                    && batteriesEmpty;

            if (state.latching) {
                // 禁用或保护时间耗尽：无论电网状态如何都立即撤产（不被自足确认窗口耽搁）。
                if (!enabled || state.remainingProtectionTime <= Mathf.FLOAT_ROUNDING_ERROR) {
                    state.latching = false;
                    state.peakGap = 0f;
                    state.protectExitHold = 0f;
                }
                // 电网持续自足才确认退出；任何瞬时缺口都会重置确认计时。
                else if (netSurplus >= 0f) {
                    state.protectExitHold += Time.delta;
                    if (state.protectExitHold >= protectExitTime) {
                        state.latching = false;
                        state.peakGap = 0f;
                        state.protectExitHold = 0f;
                    }
                } else {
                    state.protectExitHold = 0f;
                }
            } else {
                // 未在保护：满足进入条件即介入（保持即时性，覆盖 0.999 门控方块的探测帧）
                state.latching = wantProtect;
                state.protectExitHold = 0f;
            }

            // 目标产出：以峰值记忆缺口为准（而非瞬时缺口）。
            // 0.999 门控方块（如物品中枢）带 6Hz 批量搬运与 30 帧平滑窗口，需求周期性跳变；
            // 若跟随瞬时缺口会在波峰帧「产出 < 需求」→ coverage<0.999 → 立即停机。而原版
            // 电池在 PowerGraph.update() 结算内瞬间补缺、coverage 恒=1。保护器无法同帧补缺，
            // 只能让产出始终覆盖「近期需求峰值」（peakGap），再以地板供电兜底探测请求。
            float count = Math.max(1f, activeProtectorCount());
            float target = state.latching
                    ? Math.max(state.peakGap, latchFloorPower) / count
                    : 0f;

            // 产出直接贴合目标（无趋近）：峰值记忆本身已含衰减平滑，避免滞后再次诱发欠压
            state.nextTickPPower = target;

            // —— 欠款记账：以「真实被电网吸收的电力」为准 ——
            // 峰值锁定/地板供电可能让本帧产出 selfP 超过电网实时所需。多余电力去向有二：
            //   a) 电网电池有容量 → 被电池吸走充电（有效储能，算作真实消耗，应记账）；
            //   b) 无电池 / 电池已满 → 白白浪费（不算消耗，不记账）。
            // 因此「真实消耗」分两部分：
            //   1) 补缺口、被 consumer 直接消耗的部分：
            //      无保护器缺口 = needed - (produced - selfP)（produced 已含 selfP，减去后即电网自身缺口）
            //      真实满足 = min(selfP, 无保护器缺口)
            //   2) 过供部分（selfP 超出缺口）中被电池吸走充电的份额。
            float baseGap = Math.max(0f, needed - (produced - selfP));
            float realServed = Math.min(selfP, baseGap);
            float overSupply = Math.max(0f, selfP - baseGap);

            // 本帧电网电池净吸入量：结算后存量与上一帧存量之差（负数说明电池在放电，无吸入）。
            float batteryStored = power.graph.getBatteryStored();
            float batteryDelta = state.batteryStoredPrev >= 0f
                    ? batteryStored - state.batteryStoredPrev
                    : 0f;
            state.batteryStoredPrev = batteryStored;
            float batteryCharged = Math.max(0f, batteryDelta);

            // 过供中被本保护器“负责”的电池充入：按图上各保护器过供占比分摊整图电池吸入，
            // 避免多台保护器各自把同一份电池充入重复记满。仅电池存在且确实在充电时才会计入。
            float served = realServed;
            if (overSupply > 0f && batteryCharged > 0f) {
                float totalOver = 0f;
                // 图上所有参与介入的本队保护器过供之和（用各自 tickPPower 相对同一 needed/produced 计算）
                for (Building b : power.graph.all) {
                    if (b instanceof PowerProtectorBuild ppb && ppb.team == team && ppb.power != null && ppb.power.graph == power.graph) {
                        float s = ppb.state.tickPPower;
                        float gap = Math.max(0f, needed - (produced - s));
                        totalOver += Math.max(0f, s - gap);
                    }
                }
                if (totalOver > 0f) {
                    served += batteryCharged * (overSupply / totalOver);
                }
            }

            if (state.nextTickPPower > 0f) {
                // 10% 额外损耗计入债务，偿还时按此还
                if (served > 0f) {
                    state.debt = Math.min(state.debt + served * lossMultiplier, Double.MAX_VALUE);
                }
            }

            // —— 偿还（电池式「充电」）：仅恢复会话中、电网确实富余时用富余电力还债 ——
            if (state.restoring && surplusNow && enabled) {
                state.tickRPower = Math.min((float) state.debt, netSurplus);
                state.debt -= state.tickRPower;
                if (state.debt < 0) state.debt = 0;
            } else {
                state.tickRPower = 0f;
            }

            // 维护全队共享时间池（统一写回全局注册表）
            manageTeamTimePool();

            updateMode();

            refreshConfigUI();
        }

        /** 若本保护器配置面板打开则刷新 UI 数据（可用时间/状态/供电等）。
         *  暂停、电网冲突、断图等早退分支也必须调用，否则面板数据会冻结在停用前的值。 */
        private void refreshConfigUI() {
            if (configTable != null && control.input.config.isShown() && control.input.config.getSelected() == this) {
                updateConfigUI();
            }
        }

        /**
         * 收尾派生显示模式：在 updateTile() 末尾调用一次，仅从运行字段
         * （enabled / nextTickPPower / tickRPower）推导 Mode，供 UI 文案与颜色使用。
         * 本方法不对任何运行逻辑产生副作用 —— 运行只由 latching / nextTickPPower / tickRPower 等
         * 内部状态驱动，Mode 始终是「从运行结果向后看」的信息视图。
         */
        private void updateMode() {
            if (state.conflict) {
                // 电网冲突（强制停机）显示优先级最高：即使被禁用也如实显示错误根因
                state.mode = Mode.Error;
            } else if (!enabled) {
                state.mode = Mode.Stopped;
            } else if (state.nextTickPPower > 0.05f) {
                state.mode = Mode.Protecting;
            } else if (state.restoring) {
                // 恢复会话级判定：会话一经进入持续到欠款还清或电网真缺电，
                // 期间小幅电网波动不会让模式在「恢复中 ↔ 保护中」之间抖动。
                state.mode = Mode.Recovering;
            } else {
                state.mode = Mode.Normal;
            }
        }

        /** 本电网中同队伍、且当前处于峰值锁定介入状态的保护器数量（用于分摊缺口，避免合并过供）。
         *  仅统计锁定中的保护器：未锁定的保护器不摊薄供电。 */
        private int activeProtectorCount() {
            int n = 0;
            for (Building b : power.graph.all) {
                if (b instanceof PowerProtectorBuild ppb && ppb.team == team
                        && ppb.state.latching) {
                    n++;
                }
            }
            return n;
        }

        /** 本电网中是否存在其他保护器（任意队伍，仅以块类型判定）。
         *  若存在则本保护器检测到电网冲突，必须强制停机：同电网仅允许单个保护器运行。 */
        private boolean gridHasOtherProtector() {
            for (Building b : power.graph.all) {
                if (b instanceof PowerProtectorBuild && b != this) {
                    return true;
                }
            }
            return false;
        }

        /** 电网冲突强制停机：清空所有介入/偿还会话与产出缓存。
         *  仅保留欠款（待到电网趋于正常且仅剩本保护器后仍可恢复偿还）与全队共享时间池。 */
        private void stopFromConflict() {
            state.nextTickPPower = 0f;
            state.tickRPower = 0f;
            state.latching = false;
            state.peakGap = 0f;
            state.restoring = false;
            state.restoreHold = 0f;
            state.gapHold = 0f;
            state.protectExitHold = 0f;
            // 冲突期间电网电池可能已被其他保护器充放；把快照重置为续接点，恢复后不计入冲突期变化
            state.batteryStoredPrev = -1f;
            state.conflict = true;
        }

        /** 维护全队共享时间池：保护时按参与保护的保护器数扣减；全队无任何欠款才回充。
         *  结果写入全局注册表，各保护器共用。 */
        private void manageTeamTimePool() {
            TeamPool tp = pool(team);

            float activeCnt = 0f;
            boolean anyDebt = false;
            for (Building b : Groups.build) {
                if (b instanceof PowerProtectorBuild ppb && ppb.team == team) {
                    if (ppb.state.nextTickPPower > 0.05f
                            && ppb.power != null && ppb.power.graph != null) {
                        activeCnt += 1f;
                    }
                    if (ppb.state.debt > 0) anyDebt = true;
                }
            }

            // 消耗：正在保护的每台保护器按 1x 速率扣减共享时间池
            if (activeCnt > 0f) {
                tp.remainingProtectionTime = Math.max(0f, tp.remainingProtectionTime - activeCnt * Time.delta);
            }

            // 回充：全队无任何欠款且未满时线性恢复，每 restoreInterval 秒恢复 1 秒
            if (!anyDebt && tp.remainingProtectionTime < protectionTime) {
                tp.remainingProtectionTime = Math.min(protectionTime, tp.remainingProtectionTime + Time.delta / restoreInterval);
            }
        }

        /**
         * 全队「电力不足」持续型消息管理：每台保护器每帧调用一次（多保护器并存时均执行，
         * 幂等协作，不重复投递）。
         * <ul>
         *   <li>该队有保护器正在保护 且 尚无消息 → 投递一条持续型紧急消息：
         *       红底（emergency 模板）、游戏内置电源图标、高优先级、同队可见；
         *       内容中的可用保护时间经「{0}」占位符实时刷新（读全队时间池，不绑定某台实例）。</li>
         *   <li>该队没有保护器在保护 → 断开已有消息握手，由系统下一帧扫描自动清除。</li>
         * </ul>
         * 消息随保护开始出现、随全队保护结束消失；被清除后地图重新触发保护会再次投递。
         */
        private void manageTeamWarnMessage() {
            // 纯客户端不本地创建「电力不足」消息：服务器权威进程创建并广播，客户端面板只呈现服务器推送的镜像，
            // 避免「本地模拟创建 + 服务器广播」造成同队重复消息。
            if (!MessageSystem.isAuthoritative()) return;
            boolean active = teamHasActiveProtector(team);
            Message m = powerShortageMessages.get(team);
            if (active) {
                if (m != null && !m.handshake.isConnected()) {
                    // 旧消息已失联（被上一局清空/系统清扫），丢弃并重新投递
                    powerShortageMessages.remove(team);
                    m = null;
                }
                if (m == null) {
                    m = MessageSystem.emergency(
                            Core.bundle.get("block.silicon-power-protector.announce.lowPower.title"),
                            Core.bundle.get("block.silicon-power-protector.announce.lowPower.content"))
                        .titleKey("block.silicon-power-protector.announce.lowPower.title")
                        .contentKey("block.silicon-power-protector.announce.lowPower.content")
                        .icon(Icon.power)
                        .type(MessageType.PERSISTENT)
                        .team(team)
                        // 消息弹出时播放警示音效（面板在该消息到达时播放；以资源名指定，专用服务器等无音频进程也能跨进程传名）
                        .sound("power-protector")
                        // 可用保护时间实时刷新：直读全队时间池，避免绑定某台可能被拆除的保护器
                        .var(() -> Strings.fixed(Math.max(0f, pool(team).remainingProtectionTime / 60f), 1))
                        // 探活器：只要该队仍有保护器在保护，消息就保持占位
                        .handshake(new Handshake(() -> teamHasActiveProtector(team)));
                    powerShortageMessages.put(team, m);
                    MessageSystem.instance.post(m);
                }
            } else if (m != null) {
                m.handshake.disconnect();
                powerShortageMessages.remove(team);
            }
        }

        @Override
        public float getPowerProduction() {
            return state.tickPPower;
        }

        /** 配置取回：启停状态（Boolean 配置类）。滑块（Float 配置类）不走本取回，仅启停按钮状态依赖。 */
        @Override
        public Object config() {
            return enabled;
        }

        @Override
        public float warmup() {
            return state.nextTickPPower > 0.05f ? 1f : 0f;
        }

        @Override
        public byte version() {
            return 19;
        }

        // ===== UI 配置面板 =====
        private Table configTable = null;
        private Label statusLabel = null, remainingLabel = null, debtLabel = null, supplyLabel = null, restorePercentLabel = null;
        private TextButton stopButton = null;
        private Slider restorePercentSlider = null;
        private Table breakBannerTable = null;

        /** 当前显示模式文案（与方块进度条共用） */
        public String modeText() {
            return switch (state.mode) {
                case Protecting -> Core.bundle.get("block.silicon-power-protector.protection");
                case Recovering -> Core.bundle.get("block.silicon-power-protector.recovery");
                case Stopped -> Core.bundle.get("block.silicon-power-protector.stopped");
                case Error -> Core.bundle.get("block.silicon-power-protector.ui.errorConflict");
                default -> Core.bundle.get("block.silicon-power-protector.normal");
            };
        }

        /** 当前显示模式颜色 */
        public Color modeColor() {
            return switch (state.mode) {
                case Protecting -> Color.green;
                case Recovering -> Color.cyan;
                case Stopped -> Color.gray;
                case Error -> Color.scarlet;
                default -> Color.white;
            };
        }

        @Override
        public void buildConfiguration(Table table) {
            this.configTable = table;
            table.top();

            Table inner = new Table();
            inner.background(Tex.pane);
            inner.margin(8f, 12f, 8f, 12f);
            table.add(inner).growX();

            // 状态徽章
            inner.table(status -> {
                Image dot = status.image(Tex.whiteui).size(10f).padRight(6f).get();
                dot.update(() -> dot.setColor(modeColor()));
                statusLabel = status.add("").style(Styles.outlineLabel).get();
            }).colspan(2).center().padBottom(8f).row();

            // 可用保护时间
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.availableTime"))
                    .color(Color.lightGray).left().growX();
                remainingLabel = t.add("").color(Color.cyan).right().get();
            }).colspan(2).growX().padBottom(4f).row();

            // 欠下电力
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.totalSpent"))
                    .color(Color.lightGray).left().growX();
                debtLabel = t.add("").color(Pal.powerBar).right().get();
            }).colspan(2).growX().padBottom(4f).row();

            // 当前供电
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.currentSupply"))
                    .color(Color.lightGray).left().growX();
                supplyLabel = t.add("").right().get();
            }).colspan(2).growX().padBottom(8f).row();

            // 恢复电池占比滑块（每台保护器独立配置，默认 25%）
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.restoreBatteryPercent"))
                    .color(Color.lightGray).left();
                restorePercentLabel = t.add("").color(Color.cyan).right().get();
            }).colspan(2).growX().padBottom(2f).row();
            inner.table(t -> {
                // slider(min, max, step, value, listener)：step 固定 5%，value 为当前配置初始值
                restorePercentSlider = t.slider(0f, 1f, 0.05f, state.restoreBatteryPercent,
                        val -> configure(val)
                ).left().growX().get();
            }).colspan(2).growX().padBottom(8f).row();

            // 启停按钮：与逻辑处理器 `enabled` 指令共用同一字段，行为一致。
            // 走标准 configure 链路：本端经 configured() 立即生效，联网时经 Call.tileConfig 广播到全端一致。
            stopButton = inner.button("", redToggle(), () -> {
                configure(!enabled);
                updateConfigUI();
            }).colspan(2).height(40f).growX().get();
            stopButton.getLabel().setAlignment(Align.center);
            stopButton.getLabel().setFontScale(1.1f);

            updateConfigUI();
        }

        @Override
        public void onConfigureClosed() {
            configTable = null;
            statusLabel = null;
            remainingLabel = null;
            debtLabel = null;
            supplyLabel = null;
            restorePercentLabel = null;
            restorePercentSlider = null;
            stopButton = null;
        }

        private void updateConfigUI() {
            if (configTable == null) return;

            // 电网冲突：强制停机期间禁用启停按钮与恢复占比滑块（逻辑 `enabled` 仍可控制，
            // 但冲突判定优先级更高，UI 上如实外显错误并阻止调整）。
            boolean conflict = state.conflict;
            if (stopButton != null) {
                stopButton.setDisabled(conflict);
                if (conflict) {
                    stopButton.setText(Core.bundle.get("block.silicon-power-protector.ui.errorConflict"));
                } else {
                    stopButton.setChecked(!enabled);
                    stopButton.setText(!enabled
                        ? Core.bundle.get("block.silicon-power-protector.ui.disableRun")
                        : Core.bundle.get("block.silicon-power-protector.ui.enableRun"));
                }
            }
            if (restorePercentSlider != null) {
                restorePercentSlider.setDisabled(conflict);
            }

            if (statusLabel != null) {
                statusLabel.setText(modeText());
                statusLabel.setColor(modeColor());
            }

            if (remainingLabel != null) {
                float sec = Math.max(0f, state.remainingProtectionTime / 60f);
                remainingLabel.setText(Strings.fixed(sec, 1) + "s");
            }

            if (debtLabel != null) debtLabel.setText(UI.formatAmount((long) state.debt));

            if (restorePercentLabel != null) {
                restorePercentLabel.setText(Strings.fixed(state.restoreBatteryPercent * 100f, 0) + "%");
            }

            if (supplyLabel != null) {
                // 直接以运行字段判断（与 Mode 解耦）：显示本帧目标产出的实时供电
                boolean protecting = state.nextTickPPower > 0.05f;
                float supply = protecting ? state.nextTickPPower * 60f : 0f;
                supplyLabel.setText(Strings.fixed(supply, 1) + "/s");
                supplyLabel.setColor(protecting ? Color.green : Color.gray);
            }
        }

        /** 禁止拆除提示横幅：短暂显示后消失 */
        private void showCannotBreakBanner() {
            if (breakBannerTable != null) return;
            Table t = new Table(Styles.black3);
            t.touchable = Touchable.disabled;
            t.margin(8f);
            Label label = t.add(Core.bundle.get("block.silicon-power-protector.ui.cannotBreak"))
                    .style(Styles.outlineLabel).padLeft(2f).get();
            label.setAlignment(Align.left);
            t.update(() -> {
                t.pack();
                t.setPosition(6f, Core.graphics.getHeight() * 0.6f - 24f, Align.topLeft);
                if (mindustry.Vars.state.isMenu() || !ui.hudfrag.shown) {
                    if (breakBannerTable == t) breakBannerTable = null;
                    t.remove();
                }
            });
            t.actions(Actions.fadeOut(2.4f), Actions.run(() -> {
                if (breakBannerTable == t) breakBannerTable = null;
            }), Actions.remove());
            breakBannerTable = t;
            t.pack();
            t.act(0.1f);
            ui.hudGroup.addChild(t);
        }

        // ===== 存档 =====
        // enabled 写入存档：重载同一存档时保持 UI/逻辑设置的状态；每台保护器的 enabled 都是
        // 各自独立的存档字节流，A 存档的改动只存在 A 存档里，不会污染 B 存档。
        @Override
        public void write(Writes write) {
            super.write(write);
            write.f(state.remainingProtectionTime);
            write.f(state.restoreTimer);
            write.d(state.debt);
            write.f(state.restoreBatteryPercent);
            write.b(enabled ? 1 : 0);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            state.remainingProtectionTime = read.f();
            state.restoreTimer = read.f();
            state.debt = read.d();
            if (revision >= 19) {
                state.restoreBatteryPercent = read.f();
            } else {
                // 旧版无该配置，保持默认 25%
                state.restoreBatteryPercent = 0.25f;
            }
            if (revision <= 15) {
                // 旧版（revision 15）额外写入 state.stopped：丢弃以对齐流长度。禁用状态不迁移，
                // 加载即为启用。
                read.b();
            } else if (revision == 17) {
                // 过渡版（revision 17）误未写入 enabled：加载即为启用。
                enabled = true;
            } else {
                // revision 16 与 18/19 均写入 enabled 字节。
                enabled = read.b() == 1;
            }
        }

        // ===== 实例状态（存档 + 运行时）=====
        public final State state = new State();
    }
}
