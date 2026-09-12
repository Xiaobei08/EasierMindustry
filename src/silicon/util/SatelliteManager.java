package silicon.util;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.UnitType;
import mindustry.Vars;
import silicon.content.SatelliteUnits;
import silicon.world.blocks.satellite.SatelliteConsole;
import silicon.world.blocks.satellite.SatelliteLauncher;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalJammer;

/**
 * 卫星系统全局状态（按队伍）：
 * - 待发射卫星：由卫星发射中枢生产（每中枢同时 1 颗），生产完成后登记；燃料（石油）与缓冲电力（10000）均存储于中枢
 * - 在轨卫星：真实引擎单位（SatelliteUnits 四机型），由卫星控制台发射；沿以地图为中心的圆轨道飞行，
 *   覆盖为星下点覆盖圆（半径随图幅短半轴等比缩放，250×250 基准 LEO 40 / MEO 60 / GEO 80 / SSO 25 格，
 *   轨道越高覆盖越大、强度越低），不再全图短路；
 *   只能被 scripted 伤害（unit.damage()，如 ASAT 拦截塔）击落，地面单位/炮塔对其完全失明
 * - 名册 SatelliteRecord（每星一条：unitId/编码/信道/轨道/相位）是卫星语义的唯一载体：
 *   编码决定其为哪条信号提供覆盖，信道在发射时从所选编码的信号源固化（源被拆不影响干扰判定），
 *   相位驱动确定性轨道（位置 = 相位 + 时间的纯函数）。名册由控制台存档块代存（无自定义实体组件），
 *   客机经 sat-state 广播镜像
 * - 在轨数量 = 存活名册条数（卫星可被击落，数量随之下降）；配对计数（中枢/控制台 1:1）只统计地面覆盖，
 *   卫星覆盖仅解锁"远方指派"——全图覆盖不再把所有中枢算进同一范围，消除 MULTI_HUB 自锁
 */
public class SatelliteManager {
    /** 发射结果 */
    public static final int LAUNCH_OK = 0;
    /** 无待发射卫星 */
    public static final int LAUNCH_NO_READY = 1;
    /** 燃料不足（石油少于该轨道需求） */
    public static final int LAUNCH_NO_FUEL = 2;
    /** 缓冲电力不足（< 10000） */
    public static final int LAUNCH_NO_POWER = 3;
    /** 轨道与卫星种类不匹配（信号卫星不能发 SSO） */
    public static final int LAUNCH_ORBIT_FORBIDDEN = 4;
    /** 未绑定卫星发射中枢（无信号/信号范围内无中枢/控制台不在信号范围内） */
    public static final int LAUNCH_NO_HUB = 5;
    /** 所选信号范围内存在多个卫星发射中枢 */
    public static final int LAUNCH_MULTI_HUB = 6;
    /** 所选信号范围内存在多个卫星控制台 */
    public static final int LAUNCH_MULTI_CONSOLE = 7;
    /** 测试卫星仅在沙盒模式可用（非沙盒模式发射测试卫星被拒） */
    public static final int LAUNCH_TEST_SANDBOX = 8;

    /**
     * 在轨卫星名册（按队伍）——权威端维护真值，客机经 sat-state 广播镜像。
     * 记录与卫星实体按 unitId 关联；编码为 null 的记录是"未绑定"兜底（读档名册丢失的
     * 卫星实体对账补建），仅提供覆盖强度、不参与任何编码绑定。
     */
    private static final ObjectMap<Team, Seq<SatelliteRecord>> satRecords = new ObjectMap<>();
    /** 已生产完成、待发射的中枢列表（按队伍）——仅主机使用。注意客机 updateTile 照常运行
     *  （v159 引擎的 Groups.build.update 不排除 net.client()），客机 register() 只写本端镜像、
     *  不参与权威判定（权威值以主机广播的 sat-state 为准） */
    private static final ObjectMap<Team, Seq<SatelliteLauncher.SatelliteLauncherBuild>> readyLaunchers = new ObjectMap<>();
    /** 客机端镜像的待发射数（主机广播 sat-state 填充；主机端直接用 readyLaunchers） */
    private static final ObjectIntMap<Team> readyMirror = new ObjectIntMap<>();
    /** 客机端镜像：待发射第一颗的种类 / 制造中种类（-1=无；主机端实时计算） */
    private static final ObjectIntMap<Team> readyTypeMirror = new ObjectIntMap<>();
    private static final ObjectIntMap<Team> producingTypeMirror = new ObjectIntMap<>();
    /** 状态广播字段分隔符（编码：teamId|sigC|testC|名册|readyC|readyType|producingType；
     *  名册条目 "unitId:code:channel:orbit:phaseBits"，条目间 ';'，空名册为空字段） */
    static final String SEP = "|";
    /** 每星信号强度（覆盖圆内、未被压制时的原始强度；多星对数叠加后扣底噪）——轨道越高覆盖越大、强度越低：
     *  LEO 1.5 / MEO 1.3 / GEO 1.1（首颗扣底噪后 1.0/0.8/0.6，均足以激活中继器转发），SSO 特例 1.5（小覆盖强信号） */
    public static float satelliteStrength(int orbit) {
        switch (orbit) {
            case SatelliteConsole.ORBIT_MEO: return 1.3f;
            case SatelliteConsole.ORBIT_GEO: return 1.1f;
            default: return 1.5f; // LEO 与 SSO
        }
    }

    /** 在轨卫星记录：卫星语义的自定义数据载体 */
    public static class SatelliteRecord {
        /** 卫星实体 id（名册与实体按此关联；实体 id 随存档持久化） */
        public int unitId;
        /** 携带的信号编码（null=未绑定兜底记录，不参与编码绑定） */
        public String code;
        /** 发射时固化的信道（从该编码的信号源解析；-1=无法解析/未绑定，此类卫星不可被信道干扰） */
        public int channel = -1;
        /** 发射轨道（SatelliteConsole.ORBIT_*），决定覆盖半径与轨道周期 */
        public int orbit;
        /** 轨道相位（rad，世界时间 0 时刻的轨道角）——保存时推进到当前时刻，读档续接不跳位 */
        public float phase;
    }

    /** 本端是否为状态权威端（dedicated 服务器或 host，或单机）：只有权威端执行发射/生产登记 */
    public static boolean isAuthority() {
        return Vars.net.server() || !Vars.net.active();
    }

    /** 测试卫星是否可用：仅沙盒模式（Gamemode.sandbox）——其他模式不出现（UI 隐藏 + 生产/发射权威拦截） */
    public static boolean testSatelliteAvailable() {
        return Vars.state != null && Vars.state.rules != null && Vars.state.rules.mode() == Gamemode.sandbox;
    }

    /** 卫星发射特效：巨大光柱尾焰 + 向上喷射粒子 + 烟柱（配合方块自绘动画，1.5 秒） */
    public static final Effect launchFx = new Effect(90f, e -> {
        // 特效层（盖过方块，确保可见）
        Draw.z(Layer.effect);
        // 底部光柱（大而明显）
        Draw.color(Pal.lightOrange, Pal.ammo, e.fin());
        Fill.circle(e.x, e.y, 5f + e.finpow() * 12f);
        // 向上喷射粒子（大半径）
        for (int i = 0; i < 12; i++) {
            float ang = 90f + Mathf.range(25f);
            float len = e.fin() * 55f;
            Draw.color(Pal.ammo, Pal.lightOrange, e.fin());
            Fill.circle(e.x + Angles.trnsx(ang, len) * 0.7f, e.y + Angles.trnsy(ang, len) * 0.8f, e.fout() * 6f);
        }
        // 烟柱：向上漂散
        Draw.color(Color.gray, Color.lightGray, e.fin());
        Fill.circle(e.x + Mathf.range(2f), e.y + e.fin() * 45f, e.fout() * 8f);
    });

    /** 世界卸载/重置时清空（ResetEvent：存档读入前、返回主菜单、新开局都会触发——
     *  名册在读档流程中由控制台存档块重建） */
    public static void reset() {
        satRecords.clear();
        readyLaunchers.clear();
        readyMirror.clear();
        readyTypeMirror.clear();
        producingTypeMirror.clear();
    }

    /** 世界加载完成后对账（WorldLoadEvent + app.post 延迟一拍 + 控制器节流兜底）：
     *  给"有实体无名册"的卫星补建未绑定记录（名册丢失兜底，如旧版本存档），然后向在场队伍广播镜像。
     *  注意：不剪除"无实体"的名册记录——存档两侧 unitId 均保留、击落由 UnitDestroyEvent 除名、
     *  跨局由 ResetEvent 清空；且存档读入时 WorldLoadEvent 早于单位读入（units 在 entities 区域），
     *  此刻按 getByID 剪除只会误杀刚从控制台恢复的名册（卫星冻结+无信号的读档 bug） */
    public static void onWorldLoaded() {
        for (Unit u : Groups.unit) {
            if (u.controller() instanceof OrbitSatelliteController && recordOf(u.id) == null) {
                SatelliteRecord r = new SatelliteRecord();
                r.unitId = u.id;
                r.code = null; // 未绑定：仅提供覆盖强度，不参与编码绑定
                r.channel = -1;
                r.orbit = ((OrbitSatelliteController) u.controller()).orbit;
                float cx = Vars.world.unitWidth() / 2f, cy = Vars.world.unitHeight() / 2f;
                // 从当前位置近似续接轨迹：主轴对齐（EW=经度，SSO=纬度，GEO=定点方位角）
                if (r.orbit == SatelliteConsole.ORBIT_GEO) {
                    r.phase = Mathf.atan2(u.y - cy, u.x - cx) / Mathf.PI2;
                } else if (r.orbit == SatelliteConsole.ORBIT_SSO) {
                    r.phase = u.y / Vars.world.unitHeight() - Time.time / orbitPeriod(r.orbit);
                } else {
                    r.phase = u.x / Vars.world.unitWidth() - Time.time / orbitPeriod(r.orbit);
                }
                satRecords.get(u.team, Seq::new).add(r);
            }
        }
        // 向在场队伍广播（服务端；单机无客户端时为无害调用）
        Seq<Team> seen = new Seq<>();
        for (Player p : Groups.player) {
            if (p.con == null || seen.contains(p.team())) continue;
            seen.add(p.team());
            broadcastState(p.team());
        }
    }

    /** 对账节流计数（控制器兜底用） */
    private static int reconcileTick = 0;

    /** 控制器兜底：卫星实体找不到名册记录时调用（读档时序/旧档名册丢失），节流 60 帧做一次全局对账 */
    public static void reconcileMissing() {
        if (++reconcileTick < 60) return;
        reconcileTick = 0;
        onWorldLoaded();
    }

    /** 卫星实体被击落（UnitDestroyEvent；伤害仅可能来自 scripted 伤害）：名册除名并广播 */
    public static void onUnitDestroyed(Unit unit) {
        if (!(unit.controller() instanceof OrbitSatelliteController)) return;
        // 先快照键再改表（同 onWorldLoaded）
        Seq<Team> teams = new Seq<>();
        satRecords.each((t, l) -> teams.add(t));
        for (Team team : teams) {
            Seq<SatelliteRecord> list = satRecords.get(team);
            SatelliteRecord r = list.find(x -> x.unitId == unit.id);
            if (r != null) {
                list.remove(r);
                if (list.isEmpty()) satRecords.remove(team);
                broadcastState(team);
            }
        }
    }

    /** 某队伍在轨卫星总数（存活名册条数；可被击落，随击落下降） */
    public static int launchedCount(Team team) {
        return satellites(team).size;
    }

    /** 某队伍指定种类在轨卫星数（协议兼容近似字段：按轨道近似判定，测试卫星可发任意轨道故非精确；
     *  客机 applyState 不消费此字段，仅用于广播串格式占位） */
    public static int launchedCount(Team team, int type) {
        int n = 0;
        boolean wantTest = type == SatelliteLauncher.TYPE_TEST;
        for (SatelliteRecord r : satellites(team)) {
            if ((r.orbit == SatelliteConsole.ORBIT_SSO) == wantTest) n++;
        }
        return n;
    }

    /** 某队伍全部在轨卫星记录（只读视图；权威端为真值，客机为广播镜像） */
    public static Seq<SatelliteRecord> satellites(Team team) {
        return satRecords.get(team, Seq::new);
    }

    /** 按卫星实体 id 查名册记录（跨队线性扫描，卫星数量级为个位数） */
    public static SatelliteRecord recordOf(int unitId) {
        for (Team team : satRecords.keys()) {
            for (SatelliteRecord r : satRecords.get(team)) {
                if (r.unitId == unitId) return r;
            }
        }
        return null;
    }

    /**
     * 恢复一条名册记录（SatelliteConsole 存档块 v2 读入时调用；权威端与客机都用它引导）。
     * 按 unitId 去重：多台控制台写同一份全局快照，读档时先到先得、并集合并。
     * 实体对账在 onWorldLoaded 统一进行。
     */
    public static void restoreRecord(Team team, int unitId, int channel, int orbit, String code, float phase) {
        if (recordOf(unitId) != null) return;
        SatelliteRecord r = new SatelliteRecord();
        r.unitId = unitId;
        r.code = (code == null || code.isEmpty()) ? null : code;
        r.channel = channel;
        r.orbit = orbit;
        r.phase = phase;
        satRecords.get(team, Seq::new).add(r);
    }

    // —— 星下点轨迹（拟真模型，确定性：位置 = 进度+时间的纯函数，读档/联机两端一致）——

    /** 地图短半轴（世界像素）：轨道飞行圆与覆盖圆共用的几何锚点 = min(宽,高)/2；
     *  无地图（菜单/预览期）回退 1000px，即 250×250 地图基准 */
    public static float mapHalfPx() {
        if (Vars.world == null || Vars.world.unitWidth() <= 0 || Vars.world.unitHeight() <= 0) return 1000f;
        return Math.min(Vars.world.unitWidth(), Vars.world.unitHeight()) / 2f;
    }

    /** 星下点覆盖半径（世界像素）：LEO/MEO/SSO 随地图短半轴等比缩放（×短半轴：LEO 0.32 / MEO 0.48 / SSO 0.20，
     *  250×250 地图即 40/60/25 格），轨迹全域扫描下覆盖几何在任何图幅恒定；
     *  GEO 定点全图——半径 = 图心到最远角点的距离 + 定点环偏移，单颗即恒定覆盖整个地图（弱强度、易被干扰压制） */
    public static float coverageRadius(int orbit) {
        float half = mapHalfPx();
        switch (orbit) {
            case SatelliteConsole.ORBIT_GEO:
                if (Vars.world == null || Vars.world.unitWidth() <= 0 || Vars.world.unitHeight() <= 0)
                    return 1.47f * half; // 无地图（菜单/预览期）按方形对角线回退
                float w = Vars.world.unitWidth() / 2f, h = Vars.world.unitHeight() / 2f;
                return (float)Math.sqrt(w * w + h * h) + 0.05f * half;
            case SatelliteConsole.ORBIT_MEO: return 0.48f * half;
            case SatelliteConsole.ORBIT_SSO: return 0.20f * half;
            default: return 0.32f * half;
        }
    }

    /** 扫描周期（tick/圈）：卫星沿主轴横穿全图一圈（回绕）的时长——LEO 90s / MEO 150s / SSO 70s；
     *  GEO 定点不用时基（占位 1：其星下点与时间无关） */
    public static float orbitPeriod(int orbit) {
        switch (orbit) {
            case SatelliteConsole.ORBIT_MEO: return 60f * 150f;
            case SatelliteConsole.ORBIT_SSO: return 60f * 70f;
            case SatelliteConsole.ORBIT_GEO: return 1f;
            default: return 60f * 90f;
        }
    }

    /** 轨迹漂移比（无理化）：波形相位按 (1+δ) 失谐推进，相邻两圈星下点错开 2πδ——
     *  轨迹族随时间铺满全图（含现行圆轨道永远扫不到的四角）；δ 取黄金比×0.1，永不严格重复 */
    public static final float SCAN_DRIFT = 0.0618034f;

    /** 轨迹边缘内缩（px）：卫星扫过全部图幅但不越界 */
    public static final float SCAN_MARGIN = 8f;

    /** 扫描进度 u：phase + Time.time/周期，1.0 = 沿主轴横穿全图一圈（回绕）；
     *  位置/保存/发射初始化共用的唯一时间换算入口（读档 Time.time 归零后从存档 u 无缝续接） */
    public static float scanU(SatelliteRecord r) {
        return r.phase + Time.time / orbitPeriod(r.orbit);
    }

    /** 指定轨道与进度 u 的星下点 X：LEO/MEO = 经度回绕（东西向匀速），SSO = 正弦摆动（极轨） */
    public static float scanXAt(int orbit, float u) {
        if (orbit == SatelliteConsole.ORBIT_SSO) {
            float amp = Vars.world.unitWidth() / 2f - SCAN_MARGIN;
            return Vars.world.unitWidth() / 2f + amp * Mathf.sin((1f + SCAN_DRIFT) * u * Mathf.PI2);
        }
        return (u - (float)Math.floor(u)) * Vars.world.unitWidth();
    }

    /** 指定轨道与进度 u 的星下点 Y：LEO/MEO = 正弦摆动（真实 LEO 地面轨迹形态），SSO = 纬度回绕（南北向） */
    public static float scanYAt(int orbit, float u) {
        if (orbit == SatelliteConsole.ORBIT_SSO) {
            return (u - (float)Math.floor(u)) * Vars.world.unitHeight();
        }
        float amp = Vars.world.unitHeight() / 2f - SCAN_MARGIN;
        return Vars.world.unitHeight() / 2f + amp * Mathf.sin((1f + SCAN_DRIFT) * u * Mathf.PI2);
    }

    /** 卫星当前星下点 X（纯函数）：GEO = 定点悬停（发射方位角，距图心 0.05 短半轴），其余 = 扫描轨迹 */
    public static float scanX(SatelliteRecord r) {
        if (r.orbit == SatelliteConsole.ORBIT_GEO) {
            float az = r.phase * Mathf.PI2, r0 = mapHalfPx() * 0.05f;
            return Vars.world.unitWidth() / 2f + Mathf.cos(az) * r0;
        }
        return scanXAt(r.orbit, scanU(r));
    }

    /** 卫星当前星下点 Y（纯函数）：GEO = 定点悬停，其余 = 扫描轨迹 */
    public static float scanY(SatelliteRecord r) {
        if (r.orbit == SatelliteConsole.ORBIT_GEO) {
            float az = r.phase * Mathf.PI2, r0 = mapHalfPx() * 0.05f;
            return Vars.world.unitHeight() / 2f + Mathf.sin(az) * r0;
        }
        return scanYAt(r.orbit, scanU(r));
    }

    /** 保存用相位：GEO 存定点方位角（与时间无关），其余存当前扫描进度 u（读档从该进度续接，卫星不跳位） */
    public static float phaseForSave(SatelliteRecord r) {
        return r.orbit == SatelliteConsole.ORBIT_GEO ? r.phase : scanU(r);
    }

    // —— 卫星信号语义（覆盖/强度/干扰）——

    /** 单条记录在 (wx,wy) 处的有效强度（SINR 比值制）：星下点覆盖圆内原始强度（LEO 1.5 / MEO 1.3 / GEO 1.1 / SSO 1.5）
     *  × 质量因子——SINR = raw / (底噪 + 其固化信道干扰)，SINR ≤ 1（功率压不过噪声+干扰）即无信号；
     *  信道未固化（-1，发射时编码无地面源）则不受信道干扰——"在轨广播"的物理化。
     *  供绑定判定（>0）、覆盖绘制聚合与叠星共用 */
    public static float satelliteEffAt(SatelliteRecord r, float wx, float wy) {
        float raw = satelliteRawAt(r, wx, wy);
        if (raw <= 0f) return 0f;
        float jam = r.channel >= 1 ? SignalJammer.strengthAt(r.channel, wx, wy) : 0f;
        return raw * SignalChannel.sinrQuality(raw, SignalChannel.NOISE_FLOOR + jam);
    }

    /** 单条记录在 (wx,wy) 处的原始强度（未折算干扰）：覆盖圆内为该轨道定值，圆外 0 */
    public static float satelliteRawAt(SatelliteRecord r, float wx, float wy) {
        Unit u = Groups.unit.getByID(r.unitId);
        if (u == null) return 0f;
        if (!u.within(wx, wy, coverageRadius(r.orbit))) return 0f;
        return satelliteStrength(r.orbit);
    }

    /** 对数叠加：最强一星全额计入，其余星合并贡献 ln(1+Σ其余)——叠星仍有收益但边际递减；
     *  单星时 rest=0 → ln(1)=0，语义与线性时代完全一致（首星激活阈值不变） */
    public static float stackEff(float sumEff, float maxEff) {
        float rest = sumEff - maxEff;
        if (rest <= 0f) return maxEff;
        return maxEff + (float)Math.log(1.0 + rest);
    }

    /**
     * 指定编码的卫星信号在 (wx,wy) 处的有效强度（SINR 比值制）：覆盖该点且编码匹配的卫星
     * 各自按信噪比折算有效强度（{@link #satelliteEffAt}），再对数叠加（最强一星全额，其余合并
     * 贡献 ln(1+Σ其余)）——底噪已在每星质量因子内，不再末尾扣减。
     * 干扰压制语义：该编码功率 ≤ 底噪+同信道干扰（SINR ≤ 1）即无信号；随 SINR 升至 3.5 达满质量。
     * 净空单星有效强度：LEO 1.2 / MEO 0.83 / GEO 0.53，均超过中继器激活阈值（>0.5），
     * 单星即可让覆盖圆内的中继器转发；叠星按对数提升抗干扰裕度（边际递减）。
     * code 必须 non-null（中继器按编码判定；全量聚合在绘制层内联，共用 {@link #stackEff}）。
     */
    public static float satelliteStrengthAt(Team team, String code, float wx, float wy) {
        if (code == null || code.isEmpty()) return 0f;
        // 上行门控：该编码的存活地面源全部消失 → 卫星停止广播（删源即断链：绑定该编码的
        // 中继器随之去活，覆盖绘制同步消失；重放同编码源自动恢复）。名册与编码固化不动。
        if (!silicon.world.blocks.signal.SignalChannel.hasLiveSource(team, code)) return 0f;
        float sum = 0f, max = 0f;
        for (SatelliteRecord r : satellites(team)) {
            if (r.code == null || !code.equals(r.code)) continue;
            float e = satelliteEffAt(r, wx, wy);
            if (e <= 0f) continue;
            sum += e;
            if (e > max) max = e;
        }
        return Math.max(0f, stackEff(sum, max));
    }

    /** 某队伍待发射卫星数（客机读广播镜像，权威端读登记列表） */
    public static int readyCount(Team team) {
        if (!isAuthority()) return readyMirror.get(team, 0);
        Seq<SatelliteLauncher.SatelliteLauncherBuild> list = readyLaunchers.get(team);
        return list == null ? 0 : list.size;
    }

    /** 中枢生产完成时登记（权威端调用）；登记变化向同队客机广播 */
    public static void addReady(SatelliteLauncher.SatelliteLauncherBuild launcher) {
        readyLaunchers.get(launcher.team, Seq::new).add(launcher);
        broadcastState(launcher.team);
    }

    /** 中枢拆除/重置时移除登记（权威端调用）；登记变化向同队客机广播 */
    public static void removeReady(SatelliteLauncher.SatelliteLauncherBuild launcher) {
        Seq<SatelliteLauncher.SatelliteLauncherBuild> list = readyLaunchers.get(launcher.team);
        if (list != null) list.remove(launcher);
        broadcastState(launcher.team);
    }

    /** 某队伍待发射第一颗卫星种类（信号/测试；-1=无）——客机读镜像，权威端读登记列表 */
    public static int readyType(Team team) {
        if (!isAuthority()) return readyTypeMirror.get(team, -1);
        Seq<SatelliteLauncher.SatelliteLauncherBuild> list = readyLaunchers.get(team);
        return list == null || list.isEmpty() ? -1 : list.get(0).selectedType;
    }

    /** 某队伍正在制造的卫星种类（-1=无）——客机读镜像，权威端扫描中枢生产状态 */
    public static int producingType(Team team) {
        if (!isAuthority()) return producingTypeMirror.get(team, -1);
        for (Building b : Groups.build) {
            if (b instanceof SatelliteLauncher.SatelliteLauncherBuild lb
                    && lb.team == team && !lb.produced && lb.progress > 0f) {
                return lb.selectedType;
            }
        }
        return -1;
    }

    /** 编码某队状态为广播串（teamId|sigC|testC|名册|readyC|readyType|producingType） */
    static String encodeState(Team team) {
        StringBuilder roster = new StringBuilder();
        for (SatelliteRecord r : satellites(team)) {
            if (roster.length() > 0) roster.append(';');
            roster.append(r.unitId).append(':')
                    .append(r.code == null ? "" : r.code).append(':')
                    .append(r.channel).append(':')
                    .append(r.orbit).append(':')
                    .append(Float.floatToIntBits(r.phase));
        }
        return team.id + SEP + launchedCount(team, SatelliteLauncher.TYPE_SIGNAL) + SEP
                + launchedCount(team, SatelliteLauncher.TYPE_TEST) + SEP
                + roster + SEP
                + readyCount(team) + SEP + readyType(team) + SEP + producingType(team);
    }

    /**
     * 权威端广播某队卫星状态给同队所有已连接客户端（客机以 applyState 应用）。
     */
    public static void broadcastState(Team team) {
        if (!Vars.net.server()) return; // 仅服务器（host/dedicated）广播；单机无客户端
        String data = encodeState(team);
        for (Player p : Groups.player) {
            if (p.team() == team && p.con != null) {
                Call.clientPacketReliable(p.con, "sat-state", data);
            }
        }
    }

    /** 权威端周期广播所有在场队伍的卫星状态（控制台名称行保鲜；调用方按约 30 tick 周期） */
    public static void periodicBroadcastAll() {
        if (!Vars.net.server()) return;
        Seq<Team> seen = new Seq<>();
        for (Player p : Groups.player) {
            if (p.con == null || seen.contains(p.team())) continue;
            seen.add(p.team());
            broadcastState(p.team());
        }
    }

    /** 客户端应用主机广播的某队卫星状态（镜像；不修改权威端数据） */
    public static void applyState(String data) {
        String[] parts = data.split("\\" + SEP, -1);
        if (parts.length != 7) return;
        try {
            Team team = Team.get(Integer.parseInt(parts[0]));
            Seq<SatelliteRecord> list = satRecords.get(team, Seq::new);
            list.clear();
            if (!parts[3].isEmpty()) {
                for (String entry : parts[3].split(";")) {
                    String[] f = entry.split(":", -1);
                    if (f.length != 5) continue;
                    SatelliteRecord r = new SatelliteRecord();
                    r.unitId = Integer.parseInt(f[0]);
                    r.code = f[1].isEmpty() ? null : f[1];
                    r.channel = Integer.parseInt(f[2]);
                    r.orbit = Integer.parseInt(f[3]);
                    r.phase = Float.intBitsToFloat(Integer.parseInt(f[4]));
                    list.add(r);
                }
            }
            readyMirror.put(team, Integer.parseInt(parts[4]));
            readyTypeMirror.put(team, Integer.parseInt(parts[5]));
            producingTypeMirror.put(team, Integer.parseInt(parts[6]));
        } catch (NumberFormatException ignored) {
        }
    }

    /** 指定信号"地面覆盖"范围内的本队卫星发射中枢（中枢位置处于该信号地面有效范围内）。
     *  卫星覆盖不参与配对计数——卫星只解锁绑定可达性，1:1 配对仍约束地面基建布局 */
    public static Seq<SatelliteLauncher.SatelliteLauncherBuild> hubsInSignal(Team team, String signal) {
        Seq<SatelliteLauncher.SatelliteLauncherBuild> out = new Seq<>();
        if (signal == null || signal.isEmpty()) return out;
        for (Building b : Groups.build) {
            if (b instanceof SatelliteLauncher.SatelliteLauncherBuild lb && lb.team == team
                    && SignalChannel.inGroundSignalRange(team, signal, lb.x, lb.y)) {
                out.add(lb);
            }
        }
        return out;
    }

    /** 指定信号"地面覆盖"范围内的本队卫星控制台数量（含自身；>1 即"存在多个控制台"） */
    public static int consolesInSignal(Team team, String signal) {
        int n = 0;
        if (signal == null || signal.isEmpty()) return 0;
        for (Building b : Groups.build) {
            if (b instanceof SatelliteConsole.SatelliteConsoleBuild cb && cb.team == team
                    && SignalChannel.inGroundSignalRange(team, signal, cb.x, cb.y)) {
                n++;
            }
        }
        return n;
    }

    /** 从所选编码的现存信号源解析信道（发射时固化；之后源信道变更/拆除都不影响本卫星） */
    static int resolveChannel(Team team, String code) {
        for (silicon.world.blocks.signal.SignalSource.SignalSourceBuild sb
                : silicon.world.blocks.signal.SignalSource.allSources(team)) {
            if (sb.signal != null && code.equals(sb.signal.name)) return sb.channel;
        }
        return -1;
    }

    /**
     * 发射一颗卫星（权威端调用）：控制台与卫星发射中枢必须绑定——
     * 控制台须处于所选信号范围内（地面源或在轨卫星覆盖均可），且该信号"地面覆盖"范围内
     * 恰好一台中枢与一台控制台（1:1 配对；卫星覆盖不参与配对，避免全图短路自锁），
     * 由绑定的中枢扣除该轨道所需石油与缓冲电力（10000），生成卫星实体入轨并登记名册，并向全图播报。
     * @param signalName 卫星所属信号编码（4 位）
     * @param orbit 发射轨道（SatelliteConsole.ORBIT_*），决定燃油需求与覆盖半径
     * @param consoleX/consoleY 控制台世界坐标（像素）——判定控制台自身是否在该信号范围内
     * @return 发射结果（LAUNCH_*）
     */
    public static int launch(Team team, String signalName, int orbit, float consoleX, float consoleY) {
        // —— 绑定校验 ——
        if (signalName == null || signalName.isEmpty()) return LAUNCH_NO_HUB;
        if (!SignalChannel.inSignalRange(team, signalName, consoleX, consoleY)) return LAUNCH_NO_HUB;
        Seq<SatelliteLauncher.SatelliteLauncherBuild> hubs = hubsInSignal(team, signalName);
        if (hubs.isEmpty()) return LAUNCH_NO_HUB;
        if (hubs.size > 1) return LAUNCH_MULTI_HUB;
        if (consolesInSignal(team, signalName) > 1) return LAUNCH_MULTI_CONSOLE;
        SatelliteLauncher.SatelliteLauncherBuild launcher = hubs.first();
        int type = launcher.selectedType;
        // 测试卫星沙盒专属：非沙盒模式拒发（中枢配置可被跨存档/原理图带入，UI 隐藏不够，权威端兜底）
        if (type == SatelliteLauncher.TYPE_TEST && !testSatelliteAvailable()) return LAUNCH_TEST_SANDBOX;
        if (!SatelliteConsole.orbitAllowed(type, orbit)) return LAUNCH_ORBIT_FORBIDDEN;
        if (!launcher.produced) return LAUNCH_NO_READY;
        int fuel = SatelliteConsole.fuelFor(orbit);
        int reason = launcher.checkLaunchResources(fuel);
        if (reason != LAUNCH_OK) return reason;
        // 启动方块自绘发射动画（绕开 Effect 渲染管线，方块可见即特效可见）
        launcher.launchAnim = 0f;
        // 扣该轨道所需石油与缓冲电力，重置该中枢使其可再生产
        launcher.consumeLaunchResources(fuel);
        Seq<SatelliteLauncher.SatelliteLauncherBuild> list = readyLaunchers.get(team);
        if (list != null) list.remove(launcher);
        // —— 生成卫星实体并入册 ——
        SatelliteRecord rec = new SatelliteRecord();
        rec.code = signalName;
        rec.channel = resolveChannel(team, signalName);
        rec.orbit = orbit;
        UnitType ut = SatelliteUnits.typeFor(orbit);
        Unit unit = ut.create(team);
        unit.set(launcher.x, launcher.y);
        unit.add();
        rec.unitId = unit.id;
        // 初始相位：取星下点轨迹上距中枢最近的点作为出生点（与发射特效衔接）；
        // GEO 定点于中枢方位角（距图心 0.05 短半轴的定点环，多颗自然散开）
        float cx = Vars.world.unitWidth() / 2f, cy = Vars.world.unitHeight() / 2f;
        if (orbit == SatelliteConsole.ORBIT_GEO) {
            rec.phase = Mathf.atan2(launcher.y - cy, launcher.x - cx) / Mathf.PI2;
        } else {
            float best = 0f, bestD = Float.MAX_VALUE;
            for (int i = 0; i < 96; i++) {
                float u = i / 96f;
                float d = Mathf.dst(scanXAt(orbit, u), scanYAt(orbit, u), launcher.x, launcher.y);
                if (d < bestD) {
                    bestD = d;
                    best = u;
                }
            }
            rec.phase = best - Time.time / orbitPeriod(orbit);
        }
        satRecords.get(team, Seq::new).add(rec);
        // 发射特效（在发射中枢位置，全图广播）：原版火箭发射喷发 + 原版发射舱升空 + 原版大范围
        // 冲击环（launchAccelerator 160px / launch 120px——launchPod 的细条纹会被光柱淹没，用大环保证
        // 原版特效可辨识）+ 自定义尾焰 + 冲击波/烟雾
        Call.effect(Fx.padlaunch, launcher.x, launcher.y, 0f, null);
        Call.effect(Fx.launchPod, launcher.x, launcher.y, 0f, null); // 原版发射舱上升（舱体+烟迹升空）
        Call.effect(Fx.launchAccelerator, launcher.x, launcher.y, 0f, null); // 原版加速器大环（160px 扩散）
        Call.effect(Fx.launch, launcher.x, launcher.y, 0f, null); // 原版发射环（120px 扩散）
        Call.effect(launchFx, launcher.x, launcher.y + 10f, 0f, null);
        Call.effect(Fx.shockwave, launcher.x, launcher.y, 0f, null);
        Call.effect(Fx.explosion, launcher.x, launcher.y, 0f, null);
        Call.effect(Fx.smokeCloud, launcher.x, launcher.y, 0f, null);
        Call.effect(Fx.bigShockwave, launcher.x, launcher.y, 0f, null);
        // 发射音效（核心发射音，全图可听）
        Call.soundAt(Sounds.coreLaunch, launcher.x, launcher.y, 1f, 1f);
        // 全图播报：xx队发射了一颗xx卫星到xx轨道
        String teamName = Core.bundle.get("team." + team.name + ".name", team.name);
        String typeKey;
        switch (type) {
            case SatelliteLauncher.TYPE_TEST: typeKey = "block.silicon-satellite-console.type.short.test"; break;
            default: typeKey = "block.silicon-satellite-console.type.short.signal"; break;
        }
        Call.sendMessage(Core.bundle.format("satellite.launch.message", teamName,
                Core.bundle.get(typeKey), SatelliteConsole.orbitName(orbit)));
        // 在轨/待发射变化 → 广播同队客户端（launch 仅在权威端被调用）
        broadcastState(team);
        return LAUNCH_OK;
    }
}
