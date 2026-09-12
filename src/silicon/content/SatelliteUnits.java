package silicon.content;

import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.UnitType;
import silicon.world.blocks.satellite.SatelliteConsole;
import silicon.util.OrbitSatelliteController;

/**
 * 卫星实体机型（按轨道一型，共 4 型）：卫星是真实引擎单位（UnitEntity），轨道运动由
 * OrbitSatelliteController 以"相位+时间的纯函数"驱动——控制器无状态，读档即续接。
 * <p>
 * 隔离旗标（全部为 v159.7 引擎现成字段，见各注释）：
 * - targetable/hittable = false：所有索敌查询（Units.java:156/304/326 的 targetable 过滤）
 *   与所有伤害路径（Damage.java 系列的 hittable 过滤）对卫星完全失明——地面单位无法攻击卫星，
 *   子弹直接穿透（UnitComp.collides() 就是 hittable()，只管命中事件，不管物理推挤）。
 * - physics = false：退出异步物理系统（PhysicsProcess.begin 的 type.physics 过滤）——
 *   单位间推挤由 layerFlying/layerGround 物理体实现，与 hittable 无关；不加此旗标卫星
 *   会被编入 flying 物理层与飞行单位互相推挤（实测过的坑）。
 * - playerControllable = false：控制器永远走 aiController（UnitType.java:281），
 *   isCommandable() 恒假（非 CommandAI）→ 玩家框选/指挥无法改变其位置。
 * - logicControllable = false：逻辑处理器不可操控。
 * - allowedInPayloads = false：不可被 payload 方块装载搬运。
 * - drawMinimap = false：小地图不画（MinimapRenderer.java:158 过滤）——敌方小地图看不到卫星过境
 *   （代价：己方小地图也无点，由世界内轨道绘制补偿）。
 * - useUnitCap = false：不占用队伍单位上限，且永远不会触发超限击杀（UnitComp.java:596 的
 *   count() > cap() 分支；原版 eta 机甲/导弹机型同款处理）——卫星是环境实体，不该挤占军队编制。
 * - immunities = 全部状态效果：不受 EMP/减速等影响。
 * - hitSize = 24：右下角悬停信息面板的触发窗 = PlacementFragment.hovered() → Units.closestOverlap(5f)
 *   + 单位 hitbox/2（arc QuadTree 按 hitbox 相交），7px 时窗口仅约 8.5px 且卫星持续移动，鼠标几乎
 *   无法命中导致面板弹不出/不显示名称；24px 使窗口达约 17px。战斗语义不受影响——索敌/伤害/碰撞
 *   均被 targetable/hittable/collides 隔离，命中窗只服务鼠标悬停/拾取类查询。
 * - uiIcon/fullIcon = 程序化生成图标（loadIcon 覆写）：无 sprite 机型原版 loadIcon 会落到 error
 *   白方块，悬停面板/单位图鉴观感异常；生成 32×32 环+核+板图标替代。
 * - 未来武器卫星（激光）的目标选择由控制器驱动并显式排除卫星类型，且 hittable=false 使任何
 *   流弹/激光扫过其他卫星时直接穿透——"不同轨道层卫星互不攻击"由代码保证并双重兜底。
 * <p>
 * 卫星的编码/信道/相位等自定义数据不随单位持久化（无自定义实体组件），由
 * SatelliteConsole 的存档块代存（见 SatelliteManager.restoreRecord）。
 * 唯一 scripted 伤害入口：直接 unit.damage()（ASAT 拦截塔等自定义逻辑使用）。
 */
public class SatelliteUnits {
    public static UnitType signalLeo, signalMeo, signalGeo, testSso;

    public static void load() {
        // 名字不带 mod 前缀：MappableContent 构造时会经 content.transformName 无条件加 "silicon-" 前缀
        // （与 Blocks 同一惯例）；带前缀传入会变成 silicon-silicon-*，导致 bundle/贴图键全部落空
        signalLeo = orbitSatellite("satellite-leo", SatelliteConsole.ORBIT_LEO);
        signalMeo = orbitSatellite("satellite-meo", SatelliteConsole.ORBIT_MEO);
        signalGeo = orbitSatellite("satellite-geo", SatelliteConsole.ORBIT_GEO);
        testSso = orbitSatellite("satellite-sso", SatelliteConsole.ORBIT_SSO);
    }

    /** 按轨道取机型 */
    public static UnitType typeFor(int orbit) {
        switch (orbit) {
            case SatelliteConsole.ORBIT_MEO: return signalMeo;
            case SatelliteConsole.ORBIT_GEO: return signalGeo;
            case SatelliteConsole.ORBIT_SSO: return testSso;
            default: return signalLeo;
        }
    }

    /** 程序化 UI 图标缓存（32×32：太阳能板横条 + 本体环 + 核心）——无 sprite 机型原版 loadIcon
     *  会把 uiIcon 落到 error 白方块，右下角悬停面板/单位图鉴观感异常；四个机型共用一个 */
    private static TextureRegion satelliteIcon;

    static TextureRegion satelliteIcon() {
        if (satelliteIcon != null) return satelliteIcon;
        Pixmap px = new Pixmap(32, 32);
        // 太阳能板横条（中段被本体环覆盖，两侧留出板翼）
        px.fillRect(2, 15, 28, 2, Color.gray.rgba());
        // 本体环 + 核心
        px.drawCircle(16, 16, 8, Color.white.rgba());
        px.fillCircle(16, 16, 4, Color.lightGray.rgba());
        Texture tex = new Texture(px);
        px.dispose();
        return satelliteIcon = new TextureRegion(tex);
    }

    static UnitType orbitSatellite(String name, int orbit) {
        // 匿名子类:实例初始化块集中赋值,再覆写 draw(双花括号写法会把方法吞进 init 块,编译不过)
        return new UnitType(name) {
            {
                flying = true;
                health = 400f; // 只能被 scripted 伤害（ASAT 拦截塔）击落，血量即拦截成本
                armor = 2f;
                speed = 0f; // 位置由控制器直接覆写，不使用自身速度
                crashDamageMultiplier = 0f; // 坠毁不砸地面
                createWreck = false;

                // —— 索敌/伤害/物理全隔离（详见类注释）——
                targetable = false;
                hittable = false;
                physics = false; // 退出异步物理系统（PhysicsProcess.begin 按 type.physics 过滤）：
                                 // 单位间推挤在 layerFlying 物理体间发生，与 hittable 无关——
                                 // 不加此旗标卫星会被编入 flying 物理层，与飞行单位互相推挤
                killable = true; // 保留 scripted 击落能力
                playerControllable = false;
                logicControllable = false;
                allowedInPayloads = false;
                drawMinimap = false;
                useUnitCap = false; // 不占队伍单位上限 + 免疫超限击杀（UnitComp.java:596）
                // 悬停信息面板触发窗 = 5 + hitSize/2（见类注释）；仅影响鼠标悬停/拾取，不影响战斗
                hitSize = 24f;

                // 轨道控制器（按轨道携带周期/半径参数；无状态，读档经 type 工厂重建即续接）
                aiController = () -> new OrbitSatelliteController(orbit);

                // 免疫全部状态效果（含本 mod 的卫星 buff——buff 只上玩家单位，这里只是防御性兜底）
                Vars.content.statusEffects().each(effect -> immunities.add(effect));

                // 未来激光卫星的攻击面：只打地面、永不索敌空中（含卫星）——层间隔离在机型层再锁一道
                targetAir = false;
                targetGround = true;
            }

            @Override
            public void loadIcon() {
                super.loadIcon();
                // 原版 loadIcon 会把无 sprite 机型的图标指到 error 白方块——统一替换为程序化图标
                uiIcon = fullIcon = satelliteIcon();
            }

            @Override
            public void draw(Unit unit) {
                // 无贴图兜底：程序化卫星造型（队色环+核心+太阳能板线）；
                // 作者后续补 sprite（atlas 键 = 机型名）后自动切换为原版贴图绘制
                if (!region.found()) {
                    drawFallback(unit);
                } else {
                    super.draw(unit);
                }
            }

            void drawFallback(Unit unit) {
                // 视觉尺寸与 hitSize 解耦（hitSize=24 只为悬停窗口，造型保持小卫星观感）
                float r = 6.5f;
                Color tc = unit.team.color;
                // 太阳能板横线
                Lines.stroke(1.2f, tc.cpy().mul(0.7f));
                Lines.line(unit.x - r * 2f, unit.y, unit.x + r * 2f, unit.y);
                // 本体环
                Lines.stroke(1.5f, tc);
                Lines.circle(unit.x, unit.y, r);
                // 核心 + 遥测闪烁
                Draw.color(tc);
                Fill.circle(unit.x, unit.y, r * 0.45f);
                Fill.circle(unit.x, unit.y, r * 0.2f + (float) Math.abs(Mathf.sin(unit.id + Time.time / 40f)) * r * 0.15f);
                // 复位笔画宽度（Draw.reset 只复位颜色,Lines.stroke 是独立静态值,残留会影响后续 Lines 绘制）
                Lines.stroke(1f);
                Draw.reset();
            }
        };
    }
}
