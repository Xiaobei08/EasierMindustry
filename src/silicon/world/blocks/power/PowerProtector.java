package silicon.world.blocks.power;

import arc.Core;
import arc.Events;
import arc.func.Boolf;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.scene.style.NinePatchDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.core.Renderer;
import mindustry.core.UI;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.sandbox.PowerVoid;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.util.MessageSystem;
import silicon.util.MessageSystem.Handshake;
import silicon.util.MessageSystem.Message;
import silicon.util.MessageSystem.MessageType;
import silicon.util.SiliconLog;

import java.util.concurrent.atomic.AtomicBoolean;

import static mindustry.Vars.control;
import static mindustry.Vars.state;
import static mindustry.content.Blocks.powerVoid;
import static silicon.Vars.*;

/**
 * PowerProtector - 电力保护器（电路保护机制版）
 * <p>
 * 核心机制：监控电网电力。当电网电力跌到 0（电池电量为零且供需为负）时切入<b>保护模式</b>，
 * 以电池式供电瞬时补齐电网全部缺口（缺口电力累计记为 totalSpentPower）；退出保护后进入
 * <b>恢复模式</b>，以「等额本金 + 0.1%/s 利息」的速率经临时电网接入点向电网偿还累计消耗。
 * <ul>
 *   <li><b>保护进入</b>：电网电池电量耗尽（powerStored ≤ 0）且电力供需为负（powerChanged &lt; 0）、
 *       且电网非空、存在电池容量时即时介入（一次性，无滞回）。</li>
 *   <li><b>保护退出</b>：到达保护时长上限（默认 5 分钟）、或电网连续电力增长满
 *       {@code exitGrowthTime}（默认 30 秒）、或累计消耗溢出 / 电网清空 / 去容 / 出错时退出，
 *       进入恢复模式。</li>
 *   <li><b>恢复偿还</b>：按保护时长均摊的等额本金（每 tick 还 totalSpent / 保护时长）叠加
 *       剩余欠款的 0.1%/s 利息，作为本保护器的动态消耗请求；实际交付以电网
 *       {@code repayStatus()}（power.status 满足率）折算，只有真正被电网吃掉的电力才冲抵欠款。
 *       恢复期临时接入一个就近的电力节点（PowerNode/BeamNode），恢复完成后自动断开。</li>
 *   <li><b>冲突防护</b>：同电网仅允许一台保护器 —— 放置时经由电力节点可达的电网若已有保护器
 *       则禁止放置；运行期如仍发现其他保护器则立即停机并呈错误态（此状态可正常拆除以解除死锁）。</li>
 *   <li><b>运行保护</b>：保护/恢复期间免疫外部关停（开关方块或逻辑 {@code enabled} 指令不会中断）；
 *       未使用满全部消耗前（status != 0）不可拆除，防止弃债跑路。</li>
 * </ul>
 * 说明：原版 PowerGraph.update() 先于 Building.updateTile() 运行，产出/消耗按标准的
 * 1 帧结算管线推进（本版本保留该行为，不做抢先提交）。
 */
public class PowerProtector extends PowerGenerator {
    /** 保护时长（tick），默认 5 分钟。 */
    public float protectionTime = 5 * 60 * 60f;
    /** 电网连续电力增长持续该时长（tick）后退出保护，默认 30 秒。 */
    public float exitGrowthTime = 30 * 60f;
    /** 恢复期剩余欠款利息（每秒 0.1%），随剩余欠款复利累积。 */
    public float secondRecoveryRate = 0.001f;
    /** warmup（发电预热）展示速度。 */
    public float warmupSpeed = 0.1f;

    private static final Seq<Building> emptySeq = new Seq<>(0);
    /** 恢复期挑选接入点复用的临时排序表（单线程帧内串行使用，无需并发防护）。 */
    private static final Seq<Building> tempBuilds = new Seq<>();

    /**
     * 全队「电力不足」持续型消息注册表（静态、以队伍为键）。
     * 多台保护器同队同时进入保护时只投递一条；全队停止保护即断开握手由系统清除。
     */
    static final ObjectMap<Team, Message> powerShortageMessages = new ObjectMap<>();

    /** 该队当前是否有保护器正处于保护模式（status == 1）。 */
    static boolean teamHasActiveProtector(Team team) {
        for (Building b : Groups.build) {
            if (b instanceof PowerProtectorBuild ppb && ppb.team == team && ppb.status == 1) {
                return true;
            }
        }
        return false;
    }

    /** 该队仍在保护的保护器中最短剩余保护时间（秒）；无保护器时返回 0。 */
    static float teamRemainingProtectionSeconds(Team team) {
        float min = Float.MAX_VALUE;
        for (Building b : Groups.build) {
            if (b instanceof PowerProtectorBuild ppb && ppb.team == team && ppb.status == 1) {
                float cap = ((PowerProtector) ppb.block).protectionTime;
                min = Math.min(min, Math.max(0f, (cap - ppb.protectionTimer) / 60f));
            }
        }
        return min == Float.MAX_VALUE ? 0f : min;
    }

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
        replaceable = false;
        // 启停 = Boolean 配置类，与逻辑 `enabled` 指令共用同一字段
        config(Boolean.class, (building, value) -> building.enabled = value);
        // 动态消耗：恢复期按 tickRPower（上一帧算好的偿还速率）从电网取电还债，否则为 0。
        consumePowerDynamic((entity) -> ((PowerProtectorBuild) entity).tickRPower).optional(false, false);

        // 世界（重新）加载时清空消息注册表，避免跨存档污染
        Events.on(EventType.WorldLoadEvent.class, e -> {
            powerShortageMessages.clear();
            lastBreakToast = Float.NEGATIVE_INFINITY;
        });
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.repairTime, protectionTime / (60 * 60), StatUnit.minutes);
    }

    @Override
    public void setBars() {
        super.setBars();

        addBar("power", (PowerProtectorBuild entity) -> new Bar(() ->
                Core.bundle.format("bar.power1", entity.status == 1 ?
                        Strings.fixed(entity.getPowerProduction() * 60 * entity.timeScale(), 1) :
                        Strings.fixed(entity.tickRPower * 60 * entity.timeScale() * entity.repayStatus(), 1)),
                () -> Pal.powerBar,
                () -> entity.productionEfficiency));

        addBar("spent-power", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.format("bar.spent-power", UI.formatAmount((long) (entity.totalSpentPower))),
                () -> Pal.powerBar,
                () -> entity.totalSpentPower > 0 ? 1f : 0f
        ));

        addBar("remaining", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.get("block.silicon-power-protector.ui.remainingTime"),
                () -> Color.cyan,
                () -> Mathf.clamp(1f - entity.protectionTimer / ((PowerProtector) entity.block).protectionTime)));

        addBar("protection", (PowerProtectorBuild entity) -> new Bar(
                () -> entity.modeText(),
                () -> entity.modeColor(),
                () -> 1f)
        );
    }

    @Override
    public boolean canBreak(Tile tile) {
        // 保护/恢复中的保护器禁止拆除（防弃债跑路）；错误态（同电网冲突强制停机）允许拆除，
        // 否则冲突双方互斥将陷入无法拆除的死锁。
        if (tile != null && tile.build instanceof PowerProtectorBuild b && b.status != 0 && !b.error) {
            if (Time.time - lastBreakToast >= 90f) {
                lastBreakToast = Time.time;
                postCannotBreakMessage();
            }
            return false;
        }
        return true;
    }

    /** 经消息系统发送禁止拆除提示：瞬时消息、淡灰色气泡、× 图标、低优先级。 */
    private static void postCannotBreakMessage() {
        if (mindustry.Vars.headless || state.isMenu()) return;
        MessageSystem.instance.post(
            MessageSystem.normal(
                Core.bundle.get("block.silicon-power-protector.announce.cannotBreak.title"),
                Core.bundle.get("block.silicon-power-protector.announce.cannotBreak.content"))
            .titleKey("block.silicon-power-protector.announce.cannotBreak.title")
            .contentKey("block.silicon-power-protector.announce.cannotBreak.content")
            .icon(Icon.cancel)
            .life(5f));
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        // 禁止在已存在保护器的可达电网上放置新的保护器：分别检查电力节点/光束节点可达电网，
        // 及其相邻铺地范围内节点可达电网中是否已有保护器。
        AtomicBoolean canPlace = new AtomicBoolean(true);
        PowerNode.getNodeLinks(tile, this, team, other -> {
            for (Building e : other.power.graph.consumers.items) {
                if (e instanceof PowerProtectorBuild) {
                    canPlace.set(false);
                    return;
                }
            }
        });
        BeamNode.getNodeLinks(tile, this, team, other -> {
            for (Building e : other.power.graph.consumers.items) {
                if (e instanceof PowerProtectorBuild) {
                    canPlace.set(false);
                    return;
                }
            }
        });
        for (Point2 p : Edges.getEdges(size)) {
            Tile t = tile.nearby(p);
            if (t != null && t.build != null && t.build.power != null && canPlace.get()) {
                for (Building e : t.build.power.graph.consumers.items) {
                    if (e instanceof PowerProtectorBuild) {
                        canPlace.set(false);
                    }
                }
            }
        }

        return canPlace.get();
    }

    public class PowerProtectorBuild extends GeneratorBuild {
        /** 状态：0=待机，1=保护中，-1=恢复中。 */
        private byte status = 0;
        /** 本回合保护已运行时长（tick）。 */
        private float protectionTimer = 0f;
        /** 电网连续电力增长的计时（tick），达到 exitGrowthTime 退出保护。 */
        private float growthTimer = 0f;
        /** 保护期间累计消耗（欠款，double 防溢出）。 */
        private double totalSpentPower = 0f;
        /** 本帧产出供电（power/tick，保护模式）。 */
        private float tickPPower = 0f;
        private float lastTickPPower = 0f;
        /** 恢复期等额本金（tick），保护结束时按 totalSpent / 保护时长 结算。 */
        private double rPowerPrincipal = 0f;
        /** 本帧偿还消耗请求（power/tick，恢复模式）。 */
        private float tickRPower = 0f;
        private float lastTickRPower = 0f;
        /** 错误态：同电网存在其他保护器，强制停机。 */
        private boolean error = false;
        /** 恢复期临时接入的电力节点（连接电网取电偿还）。 */
        private Building node = null;

        @Override
        public void updateTile() {
            // 电网离场保护：断开电网时清空全部运行状态，避免对空图引用访问。
            if (power == null || power.graph == null) {
                tickPPower = lastTickPPower = 0f;
                tickRPower = lastTickRPower = 0f;
                protectionTimer = 0f;
                growthTimer = 0f;
                status = 0;
                node = null;
                refreshConfigUI();
                return;
            }

            // #23 保护/恢复期间免疫外部关停（开关方块、逻辑控制等）：
            // 被关停会把 status 清零进入空闲态，进而可被直接拆除
            if (status == 1 || status == -1) {
                if (!enabled) enabled = true;
            }
            if (!enabled && status == 0) {
                refreshConfigUI();
                return;
            }
            if (!enabled && status != 0) { status = 0; }

            // 同电网存在其他保护器（同队判定，防止两台同队保护器并存互相消耗）：
            // 强制停机并呈错误态。错误态允许拆除，避免互斥死锁。
            for (Building b : team.data().buildingTypes.get(block, emptySeq)) {
                if (power.graph.all.contains(b) && b != this) {
                    error = true;
                    status = 0;
                    tickPPower = lastTickPPower = 0f;
                    tickRPower = lastTickRPower = 0f;
                    node = null;
                    refreshConfigUI();
                    return;
                }
            }
            error = false;

            // 电网含有能量吸收器（PowerVoid）：保护没有意义，保持待机。
            for (Building b : team.data().buildingTypes.get(powerVoid, emptySeq)) {
                if (b.block instanceof PowerVoid && power.graph.all.contains(b)) {
                    tickPPower = lastTickPPower = 0f;
                    tickRPower = lastTickRPower = 0f;
                    protectionTimer = 0f;
                    growthTimer = 0f;
                    status = 0;
                    refreshConfigUI();
                    return;
                }
            }

            // 进入保护：电网电池电量耗尽且供需为负（电力不足）时切入保护模式
            if (status == 0 && powerStored.get(this) <= Mathf.FLOAT_ROUNDING_ERROR &&
                    power.graph.all.items.length > 0 && powerChanged.get(this) < 0f
                    && powerCapacity.get(this) > 0 && !error) {
                enterProtectionMode();
            }

            // 保护模式处理与退出条件
            if (status == 1) {
                handleProtectionMode();

                if (protectionTimer >= protectionTime || growthTimer >= exitGrowthTime
                        || totalSpentPower >= Float.MAX_VALUE || power.graph.all.items.length == 0
                        || powerCapacity.get(this) == 0 || error || Double.isNaN(totalSpentPower)) {
                    exitProtectionMode();
                }
            } else if (status == -1) {
                handleRecoveryMode();
            }

            // 全队「电力不足」持续型消息管理
            manageTeamWarnMessage();

            refreshConfigUI();
        }

        /** 切入保护模式：重置本回合计时，进入保护状态。 */
        private void enterProtectionMode() {
            lastTickPPower = tickPPower = tickRPower = lastTickRPower = growthTimer = protectionTimer = 0f;
            status = 1;
            SiliconLog.info("Power Protector entered protection mode.");
        }

        /** 保护模式每帧：累计保护时长与连续增长计时，按电网缺口计算产出并累计消耗。 */
        private void handleProtectionMode() {
            protectionTimer += Time.delta;
            lastTickPPower = tickPPower;
            if (powerChanged.get(this) > 0f) {
                growthTimer += Time.delta;
            } else {
                growthTimer = 0f;
            }

            tickPPower = Math.max(-(powerChanged.get(this) - lastTickPPower) - powerStored.get(this), 0f);

            totalSpentPower = Mathf.clamp(tickPPower + (float) totalSpentPower,
                    Mathf.FLOAT_ROUNDING_ERROR, Float.MAX_VALUE);
        }

        /** 退出保护：转为恢复模式，结算恢复期等额本金（tick）。 */
        private void exitProtectionMode() {
            growthTimer = tickPPower = lastTickPPower = 0f;
            status = -1;
            // 恢复期与保护期等长：按总消耗 / 保护时长 得到每 tick 等额本金
            float rTime = Math.max(protectionTimer, 1f);
            protectionTimer = 0f;
            rPowerPrincipal = totalSpentPower / rTime;
            SiliconLog.info("Power Protector exited protection, entering recovery mode.");
        }

        /** 恢复模式每帧：按等额本金 + 利息偿还欠款；临时接入电网直至还清。 */
        private void handleRecoveryMode() {
            if (totalSpentPower > 0) {
                updateTick();
            }

            // 进入恢复时建立电网连接（仅首次或断开后重连），之后保持不中断
            if (node == null || !power.graph.all.contains(node)) {
                getLink(team, other -> {
                    node = other;
                    other.power.links.addUnique(pos());
                    if (team == other.team) {
                        power.links.addUnique(other.pos());
                    }
                    power.graph.addGraph(other.power.graph);
                });
            }

            // 还清欠款退出恢复
            if (totalSpentPower <= 0 || Double.isNaN(totalSpentPower)) {
                status = 0;
                totalSpentPower = 0f;
                tickRPower = 0f;
                // 恢复完成：断开临时电网连接
                if (node != null) {
                    node.configureAny(pos());
                    node = null;
                }
                SiliconLog.info("Power Protector finished recovery.");
            }
        }

        /** 计算本帧偿还速率：等额本金 + 剩余欠款 0.1%/s 利息，按电网实际交付比例折算。 */
        private void updateTick() {
            if (status != -1) {
                tickRPower = 0f;
                return;
            }
            // 按电网实际交付比例偿还（本块为纯消费，generator efficiency 恒 0 曾致恢复期不耗电）
            lastTickRPower = tickRPower * repayStatus();
            // 冲抵实际交付的欠款
            totalSpentPower -= lastTickRPower;

            // 剩余欠款按 0.1%/s 计息
            double interestPerSecond = totalSpentPower * secondRecoveryRate / 60;
            double interestPerTick = interestPerSecond / 60;
            totalSpentPower += interestPerTick;
            double dP = rPowerPrincipal + interestPerTick;
            if (dP < Float.MAX_VALUE) {
                tickRPower = (float) Mathf.clamp(powerStored.get(this) / 2 + powerChanged.get(this) + lastTickRPower,
                        Math.max(Mathf.FLOAT_ROUNDING_ERROR, dP), Float.MAX_VALUE);
            } else {
                tickRPower = Float.MAX_VALUE;
            }
        }

        /** 是否处于保护模式。 */
        public boolean isInProtectionMode() {
            return status == 1;
        }

        /** #1 恢复偿还的电网交付比例（0~1）：动态消费的实际满足率，替代恒为 0 的 generator efficiency。 */
        public float repayStatus() {
            return power == null ? 0f : Mathf.clamp(power.status);
        }

        /** 是否处于恢复模式。 */
        public boolean isInRecoveryMode() {
            return status == -1;
        }

        public boolean isError() {
            return error;
        }

        /** 当前显示模式文案（与方块进度条共用）。 */
        public String modeText() {
            return error ? Core.bundle.get("block.silicon-power-protector.error")
                    : status == 1 ? Core.bundle.get("block.silicon-power-protector.protection")
                    : status == -1 ? Core.bundle.get("block.silicon-power-protector.recovery")
                    : !enabled ? Core.bundle.get("block.silicon-power-protector.stopped")
                    : Core.bundle.get("block.silicon-power-protector.normal");
        }

        /** 当前显示模式颜色。 */
        public Color modeColor() {
            return error ? Color.red
                    : status == 1 ? Color.green
                    : status == -1 ? Color.orange
                    : Color.white;
        }

        @Override
        public float getPowerProduction() {
            return tickPPower;
        }

        @Override
        public float warmup() {
            return warmupSpeed;
        }

        @Override
        public byte version() {
            return 30;
        }

        @Override
        public void draw() {
            super.draw();

            if (Mathf.zero(Renderer.laserOpacity) || isPayload() || team == Team.derelict) return;

            Draw.z(Layer.power);
            setupColor(power.graph.getSatisfaction());

            if (node != null && team.data().buildings.contains(node)) {
                if (node instanceof PowerNode.PowerNodeBuild p) {
                    ((PowerNode) p.block).drawLaser(x, y, node.x, node.y, size, node.block.size);
                }
                if (node instanceof BeamNode.BeamNodeBuild p) {
                    ((BeamNode) p.block).drawLaser(x, y, node.x, node.y, size, node.block.size);
                }
            }

            Draw.reset();
        }

        protected void setupColor(float satisfaction) {
            Draw.color(Tmp.c1.set(Color.white).lerp(Pal.powerLight,
                    (1f - satisfaction) * 0.86f + Mathf.absin(3f, 0.1f)).a(Renderer.laserOpacity));
        }

        /** 逻辑处理器传感器访问：电网存储、电网容量、效率。 */
        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.powerNetStored) return powerStored.get(this);
            if (sensor == LAccess.powerNetCapacity) return powerCapacity.get(this);
            if (sensor == LAccess.efficiency) return shouldConsume() ? efficiency : 0f;
            return super.sense(sensor);
        }

        /** 恢复期挑选就近且「有电可借」的电力节点（优先级排序），回调返回接入目标。 */
        private void getLink(Team team, Cons<Building> others) {
            Boolf<Building> valid = other -> (powerCapacity.get(other) > Mathf.FLOAT_ROUNDING_ERROR &&
                    powerStored.get(other) > Mathf.FLOAT_ROUNDING_ERROR) ||
                    powerChanged.get(other) > Mathf.FLOAT_ROUNDING_ERROR;

            tempBuilds.clear();

            Seq<Building> buildings = team.data().buildings;
            if (buildings != null) {
                buildings.each(b -> b instanceof PowerNode.PowerNodeBuild p && p.power.links.size < ((PowerNode) p.block).maxNodes, tempBuilds::add);
                buildings.each(b -> b instanceof BeamNode.BeamNodeBuild p && p.power.links.size < ((PowerNode) p.block).maxNodes, tempBuilds::add);
            }

            tempBuilds.sort((a, b) -> {
                int type = -Boolean.compare(valid.get(a), valid.get(b));
                if (type != 0) return type;
                if (a.power.graph == b.power.graph) return 0;
                float pA = powerStored.get(a) + powerChanged.get(a) * 60f;
                float pB = powerStored.get(b) + powerChanged.get(b) * 60f;
                if (a.power.graph == power.graph) pA += lastTickRPower * 60f;
                if (b.power.graph == power.graph) pB += lastTickRPower * 60f;
                return -Float.compare(pA, pB);
            });

            if (tempBuilds.size > 0 && tempBuilds.first() instanceof PowerNode.PowerNodeBuild p) {
                others.get(p);
            }
        }

        /**
         * 全队「电力不足」持续型消息管理：每台保护器每帧调用一次（多保护器并存时均执行，幂等协作）。
         * 该队有保护器正在保护 → 投递一条持续型紧急消息；全队停止保护 → 断开握手由系统清除。
         */
        private void manageTeamWarnMessage() {
            if (!MessageSystem.isAuthoritative()) return;
            boolean active = teamHasActiveProtector(team);
            Message m = powerShortageMessages.get(team);
            if (active) {
                if (m != null && !m.handshake.isConnected()) {
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
                        .sound("power-protector")
                        // 剩余保护时间（该队仍在保护的保护器中最短剩余值，秒）实时刷新
                        .var(() -> Strings.fixed(teamRemainingProtectionSeconds(team), 0))
                        .handshake(new Handshake(() -> teamHasActiveProtector(team)));
                    powerShortageMessages.put(team, m);
                    MessageSystem.instance.post(m);
                }
            } else if (m != null) {
                m.handshake.disconnect();
                powerShortageMessages.remove(team);
            }
        }

        /** 配置取回：启停状态（Boolean 配置类）。 */
        @Override
        public Object config() {
            return enabled;
        }

        // ===== UI 配置面板 =====
        private Table configTable = null;
        private Label statusLabel = null, remainingLabel = null, spentLabel = null, supplyLabel = null;
        private TextButton stopButton = null;

        /** 若本保护器配置面板打开则刷新 UI 数据。早退分支也必须调用，避免面板数据冻结。 */
        private void refreshConfigUI() {
            if (configTable != null && control.input.config.isShown()
                    && control.input.config.getSelected() == this) {
                updateConfigUI();
            }
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

            // 剩余保护时间
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.remainingTime"))
                    .color(Color.lightGray).left().growX();
                remainingLabel = t.add("").color(Color.cyan).right().get();
            }).colspan(2).growX().padBottom(4f).row();

            // 累计消耗（欠款）
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.totalSpent"))
                    .color(Color.lightGray).left().growX();
                spentLabel = t.add("").color(Pal.powerBar).right().get();
            }).colspan(2).growX().padBottom(4f).row();

            // 当前供电 / 偿还
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.currentSupply"))
                    .color(Color.lightGray).left().growX();
                supplyLabel = t.add("").right().get();
            }).colspan(2).growX().padBottom(8f).row();

            // 启停按钮：与逻辑处理器 `enabled` 指令共用同一字段，走标准 configure 链路。
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
            spentLabel = null;
            supplyLabel = null;
            stopButton = null;
        }

        private void updateConfigUI() {
            if (configTable == null) return;

            if (stopButton != null) {
                stopButton.setDisabled(error);
                if (error) {
                    stopButton.setText(Core.bundle.get("block.silicon-power-protector.ui.errorConflict"));
                } else {
                    stopButton.setChecked(!enabled);
                    stopButton.setText(!enabled
                        ? Core.bundle.get("block.silicon-power-protector.ui.disableRun")
                        : Core.bundle.get("block.silicon-power-protector.ui.enableRun"));
                }
            }

            if (statusLabel != null) {
                statusLabel.setText(modeText());
                statusLabel.setColor(modeColor());
            }

            if (remainingLabel != null) {
                float sec = Math.max(0f, (protectionTime - protectionTimer) / 60f);
                remainingLabel.setText(Strings.fixed(sec, 1) + "s");
            }

            if (spentLabel != null) spentLabel.setText(UI.formatAmount((long) totalSpentPower));

            if (supplyLabel != null) {
                boolean protecting = status == 1;
                boolean recovering = status == -1;
                float supply = protecting ? tickPPower * 60f
                        : recovering ? tickRPower * 60f * repayStatus() : 0f;
                supplyLabel.setText(Strings.fixed(supply, 1) + "/s");
                supplyLabel.setColor(protecting ? Color.green : recovering ? Color.orange : Color.gray);
            }
        }

        // ===== 存档 =====
        @Override
        public void write(Writes write) {
            super.write(write);
            write.b(status);
            write.f(protectionTimer);
            write.f(growthTimer);
            write.d(totalSpentPower);
            write.f(tickPPower);
            write.f(lastTickPPower);
            write.d(rPowerPrincipal);
            write.f(tickRPower);
            write.f(lastTickRPower);
            write.b(enabled ? 1 : 0);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 30) {
                // 新版（revision 30 起，电路保护机制）：状态、计时、累计消耗、产出/偿还缓存、启用状态
                status = read.b();
                protectionTimer = read.f();
                growthTimer = read.f();
                totalSpentPower = read.d();
                tickPPower = read.f();
                lastTickPPower = read.f();
                rPowerPrincipal = read.d();
                tickRPower = read.f();
                lastTickRPower = read.f();
                enabled = read.b() == 1;
            } else {
                // 旧版存档（旧机制：无尽电池 + 欠款 + 恢复滞回 + 时间池）：旧格式数据废弃。
                // 仅按旧版字段顺序对齐读取旧流，随后以全新待机状态起步。
                consumeLegacy(read, revision);
                status = 0;
                protectionTimer = 0f;
                growthTimer = 0f;
                totalSpentPower = 0f;
                tickPPower = 0f;
                lastTickPPower = 0f;
                rPowerPrincipal = 0f;
                tickRPower = 0f;
                lastTickRPower = 0f;
                enabled = true;
            }
        }

        /** 按旧版（revision ≤21）字段布局消费旧存档数据，仅用于对齐流位置，读数全部丢弃。 */
        private static void consumeLegacy(Reads read, byte revision) {
            read.f(); // remainingProtectionTime
            read.f(); // restoreTimer
            if (revision >= 21) {
                read.f(); // battery
                read.d(); // debt
                read.f(); // restoreBatteryPercent
                read.b(); // enabled
            } else if (revision == 20) {
                read.f(); // battery
                read.b(); // enabled
            } else {
                read.d(); // debt
                if (revision >= 19) {
                    read.f(); // restoreBatteryPercent
                }
                if (revision <= 15) {
                    read.b(); // stopped
                } else if (revision != 17) {
                    read.b(); // enabled
                }
            }
        }
    }
}