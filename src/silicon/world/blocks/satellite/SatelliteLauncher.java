package silicon.world.blocks.satellite;

import arc.Core;
import arc.func.Boolp;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.graphics.Layer;

import java.util.Locale;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.Styles;
import mindustry.Vars;
import mindustry.world.Block;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.util.SatelliteManager;
import silicon.world.blocks.signal.SignalChannel;

import static mindustry.type.ItemStack.with;

/**
 * 卫星发射中枢（3×3）：选择卫星种类并生产卫星，同时负责发射所需的燃料与电力储备。
 * - 生产材料（选择种类后开始生产时一次性消耗）：铜 5000、硅 5000、塑钢 1250、巨浪合金 1250、冷冻液 1000
 * - 生产阶段消耗 5000 电力/秒（电网）；每中枢同时只能生产 1 颗，完成后停止耗电并显示「可发射卫星」提示
 * - 内置 10000 发射缓冲（电网供电充电）；发射燃料石油（1000）亦储存在本中枢
 * - 卫星由卫星控制台点击发射
 */
public class SatelliteLauncher extends Block {
    /** 信号卫星生产耗时（tick），60 秒 */
    public static final float PRODUCE_TIME_SIGNAL = 60f * 60f;
    /** 测试卫星生产耗时（tick），1 秒 */
    public static final float PRODUCE_TIME_TEST = 60f;
    /** 生产阶段耗电（/秒，Mindustry 按 /60 tick 计） */
    public static final float POWER_CONSUMPTION = 5000f / 60f;
    /** 发射所需缓冲电力 */
    public static final float LAUNCH_POWER = 10000f;
    /** 缓冲充电速率（/秒）：电网供电时向缓冲充电 */
    public static final float CHARGE_RATE = 2000f / 60f;
    /** 石油储油上限（按最高轨道需求 SSO 8000 设计；发射实际消耗按控制台所选轨道 1000~8000） */
    public static final int OIL_CAPACITY = SatelliteConsole.ORBIT_MAX_FUEL;
    /** 生产所需冷冻液 */
    public static final int COST_CRYOFLUID = 1000;
    /** 生产所需物品材料 */
    public static final ItemStack[] PRODUCTION_ITEMS = with(
            Items.copper, 5000,
            Items.silicon, 5000,
            Items.plastanium, 1250,
            Items.surgeAlloy, 1250
    );

    /** 卫星种类：信号卫星 */
    public static final int TYPE_SIGNAL = 0;
    /** 卫星种类：测试卫星（材料 1 硅，效果同信号卫星：星下点覆盖；低成本快速生产，仅用于测试；沙盒模式专属） */
    public static final int TYPE_TEST = 1;

    /** 测试卫星的生产材料（1 硅，无冷冻液） */
    public static final ItemStack[] TEST_PRODUCTION_ITEMS = with(Items.silicon, 1);

    /** 按种类返回生产所需物品材料 */
    public static ItemStack[] productionItems(int type) {
        return type == TYPE_TEST ? TEST_PRODUCTION_ITEMS : PRODUCTION_ITEMS;
    }

    /** 按种类返回生产所需冷冻液 */
    public static int productionCryofluid(int type) {
        return type == TYPE_TEST ? 0 : COST_CRYOFLUID;
    }

    /** 按种类返回生产耗时（测试卫星 1 秒，信号卫星 60 秒） */
    public static float produceTime(int type) {
        return type == TYPE_TEST ? PRODUCE_TIME_TEST : PRODUCE_TIME_SIGNAL;
    }

    /** 数量格式化（原版风格）：>=1000 显示为 x.xk（5000→5.0k、1250→1.3k、1000→1.0k，k 后缀灰色），小于 1000 原样显示 */
    static String formatCount(int amount) {
        return amount >= 1000
                ? String.format(Locale.ROOT, "%.1f[gray]k[]", amount / 1000f)
                : String.valueOf(amount);
    }

    /** 物品不足指示（原版缺失样式）：当条件成立时，在物品图标上绘制一条左上到右下的红色斜线 */
    static class InsufficientLine extends Element {
        /** 不足判断条件（每帧求值） */
        final Boolp condition;

        InsufficientLine(Boolp condition) {
            this.condition = condition;
        }

        @Override
        public void draw() {
            if (!condition.get()) return;
            Draw.color(Pal.remove);
            Draw.rect(Core.atlas.find("white"), x + width / 2f, y + height / 2f, Math.max(2f, width * 0.07f), height * 1.35f, 45f);
            Draw.color();
        }
    }

    public SatelliteLauncher(String name) {
        super(name);
        buildType = SatelliteLauncherBuild::new;
        size = 3;
        solid = true;
        destructible = true;
        update = true;
        configurable = true;
        // 生产阶段耗电（电网直耗）；发射用 10000 缓冲由本方块充电积累
        consumePower(POWER_CONSUMPTION);
        // 材料储存（物品 + 液体：石油/冷冻液）
        hasItems = true;
        acceptsItems = true;
        itemCapacity = 5000 + 5000 + 1250 + 1250;
        hasLiquids = true;
        liquidCapacity = OIL_CAPACITY + COST_CRYOFLUID;
        // 卫星种类走 configure 同步（服务器权威下发，各端选中类型一致）
        config(Integer.class, (SatelliteLauncherBuild b, Integer v) ->
                b.selectedType = Math.max(TYPE_SIGNAL, Math.min(TYPE_TEST, v == null ? TYPE_SIGNAL : v)));
        // 运行时快照（battery|progress|produced）：服务器周期下发，客机应用镜像，使面板/提示与主机一致
        config(String.class, (SatelliteLauncherBuild b, String s) -> b.applySnapshot(s));
    }

    /** 快照字段分隔符 */
    private static final char SNAP_SEP = '|';

    /** 生产进度/缓冲/完成状态同步周期（tick） */
    private static final int SNAPSHOT_INTERVAL = 15;

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.powerCapacity, LAUNCH_POWER, StatUnit.powerSecond);
        stats.add(Stat.productionTime, produceTime(TYPE_SIGNAL) / 60f, StatUnit.seconds);
        for (ItemStack stack : PRODUCTION_ITEMS) {
            stats.add(Stat.input, stack);
        }
    }

    public class SatelliteLauncherBuild extends Building {
        /** 当前选择的卫星种类（0=信号卫星） */
        public int selectedType = TYPE_SIGNAL;
        /** 生产进度（tick） */
        public float progress = 0f;
        /** 发射缓冲电量（0~10000，电网供电时充电积累，发射时一次性消耗） */
        public float battery = 0f;
        /** 本中枢是否已生产完成一颗（待发射） */
        public boolean produced = false;
        /** 发射动画计时（tick，-1=未在发射）：由 SatelliteManager 在发射成功时启动，方块自绘特效（绕开 Effect 渲染管线，保证可见） */
        public float launchAnim = -1f;
        /** 石油需求显示用轨道：绑定本中枢的唯一控制台的所选轨道（无唯一绑定控制台为 -1 → 面板显示区间） */
        private int displayOrbit = -1;
        /** 控制台轨道扫描节流（显示面板帧） */
        private int orbitScanTick = 0;
        /** 是否已登记到待发射队列 */
        private boolean registered = false;
        /** 选中面板需求材料行（切换种类时重建） */
        private final Table materialTable = new Table();
        /** 上次显示的种类（用于检测切换并重建材料行） */
        private int lastShownType = -1;
        /** 运行状态快照同步计时（服务器每 SNAPSHOT_INTERVAL tick 向客机下发一次） */
        private int snapshotTimer = 0;

        /** 服务器构造本中枢运行快照（整数化减小包体） */
        String snapshot() {
            return (int) battery + "" + SNAP_SEP + (int) progress + SNAP_SEP + (produced ? "1" : "0");
        }

        /** 客机应用主机下发的运行快照（battery|progress|produced）；解析失败忽略（防伪造串） */
        void applySnapshot(String s) {
            // 主机权威守卫:该处理器挂在 tileConfig 双向通道上,任何同队客户端都能向服务器
            // 发包走这里——若不拦截,发一条 "10000|0|1" 即可在主机上凭空造出跳过全部
            // 材料与充电的"已就绪"卫星。快照只允许 服务器下发→客机应用 单向流动:
            // 服务器侧(含主机自身本地回环)一律忽略,权威值本来就在服务器字段里。
            if (Vars.net.server()) return;
            try {
                String[] p = s.split("\\" + SNAP_SEP, -1);
                if (p.length != 3) return;
                battery = Math.max(0f, Math.min(LAUNCH_POWER, Integer.parseInt(p[0])));
                progress = Math.max(0f, Math.min(produceTime(selectedType), Integer.parseInt(p[1])));
                produced = p[2].equals("1");
            } catch (NumberFormatException ignored) {
            }
        }

        @Override
        public void updateTile() {
            // 材料行随种类实时更新（切换种类即时重建）
            if (selectedType != lastShownType) {
                lastShownType = selectedType;
                rebuildMaterialTable();
            }
            // 运行快照周期下发（仅服务器，按队定向——不再 tileConfig(null) 全员广播，
            // 敌队客户端不再收到我方中枢电量/进度明文）。客机 updateTile 照常运行(v159 Logic.java
            // 的 Groups.build.update 不排除 net.client()),客机进入本分支但 net.server() 为假不会发送;
            // 即便伪造 tileConfig 顶到服务器,applySnapshot 的服务端守卫也会拦截
            if (Vars.net.server() && ++snapshotTimer >= SNAPSHOT_INTERVAL) {
                snapshotTimer = 0;
                silicon.util.NetSync.sendTeamConfig(this, snapshot());
            }
            // 关闭（enabled=false，逻辑门/开关控制）：不充电、不生产（进度与已生产状态保留）
            if (!enabled) return;
            // 电网有电时向发射缓冲充电（发射储备）
            if (power != null && power.status > 0.001f && battery < LAUNCH_POWER) {
                battery = Math.min(LAUNCH_POWER, battery + CHARGE_RATE * delta());
            }
            if (produced) {
                // 保持登记（发射后由 SatelliteManager 重置）
                register();
                return;
            }
            // 断电不生产（进度保留）
            if (power == null || power.status <= 0.001f) return;
            // 测试卫星沙盒专属：非沙盒模式不生产（配置被存档/原理图带入时兜底；不消耗任何材料）
            if (selectedType == TYPE_TEST && !SatelliteManager.testSatelliteAvailable()) return;
            // 生产开始：检查并一次性扣除材料（进度 > 0 表示已扣）
            if (progress <= 0f) {
                if (!hasProductionMaterials()) return;
                consumeProductionMaterials();
            }
            progress += delta();
            if (progress >= produceTime(selectedType)) {
                progress = produceTime(selectedType);
                produced = true;
                register();
            }
        }

        /** 生产材料是否充足（按当前所选种类：物品 + 冷冻液） */
        public boolean hasProductionMaterials() {
            for (ItemStack stack : productionItems(selectedType)) {
                if (items.get(stack.item) < stack.amount) return false;
            }
            return liquids.get(Liquids.cryofluid) >= productionCryofluid(selectedType);
        }

        /** 扣除生产材料（一次性，按当前所选种类） */
        public void consumeProductionMaterials() {
            for (ItemStack stack : productionItems(selectedType)) {
                items.remove(stack.item, stack.amount);
            }
            liquids.remove(Liquids.cryofluid, productionCryofluid(selectedType));
        }

        void register() {
            if (!registered) {
                SatelliteManager.addReady(this);
                registered = true;
            }
        }

        void unregister() {
            if (registered) {
                SatelliteManager.removeReady(this);
                registered = false;
            }
        }

        /** 物品输入：仅接受生产所需材料（铜/硅/塑钢/巨浪合金），且未满库存（override 默认的 consumesItem 检查） */
        @Override
        public boolean acceptItem(Building source, Item item) {
            if (items.get(item) >= itemCapacity) return false;
            for (ItemStack stack : PRODUCTION_ITEMS) {
                if (stack.item == item) return true;
            }
            return false;
        }

        /** 液体输入：仅接受石油（燃料）与冷冻液（生产材料） */
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (liquids.get(liquid) >= liquidCapacity) return false;
            return liquid == Liquids.oil || liquid == Liquids.cryofluid;
        }

        /** 发射前资源检查（按所选轨道所需石油）：返回 LAUNCH_OK 或缺失原因 */
        public int checkLaunchResources(int fuelOil) {
            if (liquids.get(Liquids.oil) < fuelOil) return SatelliteManager.LAUNCH_NO_FUEL;
            if (battery < LAUNCH_POWER) return SatelliteManager.LAUNCH_NO_POWER;
            return SatelliteManager.LAUNCH_OK;
        }

        /** 发射：扣除该轨道所需石油与缓冲电力，重置本中枢使其可再生产（由 SatelliteManager 调用） */
        public void consumeLaunchResources(int fuelOil) {
            liquids.remove(Liquids.oil, fuelOil);
            battery = Math.max(0f, battery - LAUNCH_POWER);
            // 同步从电网电池扣除（模拟真实消耗，电网无电池则仅清空本缓冲）
            if (power != null) power.graph.useBatteries(LAUNCH_POWER);
            resetForLaunch();
        }

        /** 卫星发射后重置，使本中枢可再生产 */
        public void resetForLaunch() {
            produced = false;
            progress = 0f;
            registered = false;
        }

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            // 读档恢复：已生产完成的中枢重新登记
            if (produced) register();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            unregister();
        }

        /** 绘制：生产完成时方块上方悬浮「可发射」提示；发射时自绘光柱尾焰发射特效（方块可见即特效可见） */
        @Override
        public void draw() {
            super.draw();
            // 发射特效：方块自绘（光柱尾焰 + 向上粒子 + 烟柱），绕开引擎 Effect 渲染管线
            if (launchAnim >= 0f) {
                launchAnim += Time.delta;
                if (launchAnim > 90f) {
                    launchAnim = -1f;
                } else {
                    float t = Math.min(1f, launchAnim / 90f);
                    Draw.z(Layer.effect);

                    // —— 地面段：起喷白闪 + 扩散冲击环（前 45%，加色混合）——
                    if (t < 0.45f) {
                        float rt = t / 0.45f;
                        Draw.blend(Blending.additive);
                        Draw.color(Color.white, 0.55f * (1f - rt));
                        Fill.circle(x, y, 4f + rt * 16f);
                        Draw.color(Pal.lightOrange, 0.7f * (1f - rt));
                        Lines.stroke(0.5f + 3f * (1f - rt));
                        Lines.circle(x, y, 6f + rt * 36f);
                        Draw.blend(Blending.normal);
                    }

                    // —— 垂直光柱：外层辉光 → 橙色主体 → 白色内核，底宽顶窄、随时间收束（加色混合）——
                    float bh = 130f * Mathf.pow(t, 0.3f);          // 柱高快速长成
                    float bw = 5.5f * (1f - t * 0.55f);            // 底半宽随时间收束
                    Draw.blend(Blending.additive);
                    Draw.color(Pal.lightOrange, 0.22f * (1f - t * 0.35f));
                    Fill.quad(x - bw, y, x + bw, y, x + bw * 0.55f, y + bh, x - bw * 0.55f, y + bh);
                    Draw.color(Pal.lightOrange, 0.65f * (1f - t * 0.45f));
                    Fill.quad(x - bw * 0.42f, y, x + bw * 0.42f, y, x + bw * 0.16f, y + bh, x - bw * 0.16f, y + bh);
                    Draw.color(Color.white, 0.5f * (1f - t));
                    Fill.quad(x - bw * 0.14f, y, x + bw * 0.14f, y, x + bw * 0.05f, y + bh * 0.9f, x - bw * 0.05f, y + bh * 0.9f);

                    // —— 上升粒子流：种子化确定性轨迹（初相错落 + 正弦横漂 + 升高缩小），替代逐帧随机抖动 ——
                    for (int i = 0; i < 14; i++) {
                        float pt = (t * 1.7f + i * 0.37f) % 1f;    // 粒子生命周期 0..1（初相错落）
                        float px = x + Mathf.sin((pt * 7f + i * 1.9f) * Mathf.pi) * (1.5f + pt * 4.5f);
                        float py = y + 2f + pt * bh;
                        float pr = 0.6f + (1f - pt) * 2.8f;
                        float pa = (pt < 0.1f ? pt / 0.1f : 1f) * (1f - pt);
                        Draw.color(i % 3 == 0 ? Pal.ammo : Pal.lightOrange, pa * 0.8f);
                        Fill.circle(px, py, pr);
                    }
                    Draw.blend(Blending.normal);

                    // —— 上升卫星体（15%~85%）：星体+双太阳翼剪影沿柱升空，渐远渐小渐淡，带锥形尾迹 ——
                    if (t > 0.15f && t < 0.85f) {
                        float st = (t - 0.15f) / 0.7f;
                        float sy = y + 10f + st * (bh + 26f);
                        float ss = 1.25f - st * 0.65f;             // 渐小=升远
                        float sa = st < 0.18f ? st / 0.18f : 1f - (st - 0.18f) / 0.82f; // 淡出于天际
                        Draw.color(Pal.lightOrange, sa * 0.45f);
                        Fill.quad(x - 1.8f * ss, y + 8f, x + 1.8f * ss, y + 8f, x + 0.5f * ss, sy, x - 0.5f * ss, sy);
                        Draw.color(Color.lightGray, sa);
                        Fill.square(x, sy, 3.2f * ss, 0f);                  // 星体
                        Fill.rect(x - 5.6f * ss, sy, 4.2f * ss, 1.6f * ss); // 左太阳翼
                        Fill.rect(x + 5.6f * ss, sy, 4.2f * ss, 1.6f * ss); // 右太阳翼
                    }

                    // —— 贴地喷射云（全程，普通混合）：两侧翻腾扩张 ——
                    Draw.color(Color.lightGray, 0.45f * (1f - t));
                    Fill.circle(x - 5f, y + 1f, 3f + t * 10f);
                    Fill.circle(x + 5f, y + 1f, 2.5f + t * 8f);
                    Draw.reset();
                }
            }
        }

        /** 配置面板：选择卫星种类（生产所需种类） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.add(Core.bundle.get("block.silicon-satellite-launcher.type")).pad(4f);
            table.row();
            ButtonGroup<TextButton> group = new ButtonGroup<>();
            TextButton signalBtn = new TextButton(Core.bundle.get("block.silicon-satellite-launcher.type.signal"), Styles.flatTogglet);
            signalBtn.setChecked(selectedType == TYPE_SIGNAL);
            // configure 同步（服务器权威下发，各端选中类型一致）；乐观先设本地保证即时反馈
            signalBtn.clicked(() -> { selectedType = TYPE_SIGNAL; configure(TYPE_SIGNAL); });
            group.add(signalBtn);
            table.add(signalBtn).size(200f, 44f).pad(3f);
            table.row();
            // 测试卫星沙盒专属：非沙盒模式不出现该选项（配置被带入时由生产/发射权威端兜底拦截）
            if (SatelliteManager.testSatelliteAvailable()) {
                TextButton testBtn = new TextButton(Core.bundle.get("block.silicon-satellite-launcher.type.test"), Styles.flatTogglet);
                testBtn.setChecked(selectedType == TYPE_TEST);
                testBtn.clicked(() -> { selectedType = TYPE_TEST; configure(TYPE_TEST); });
                group.add(testBtn);
                table.add(testBtn).size(200f, 44f).pad(3f);
            } else if (selectedType == TYPE_TEST) {
                // 非沙盒模式下面板只显示信号卫星选项，选中态归位
                signalBtn.setChecked(true);
            }
        }

        /** 选中面板（按原版空军工厂样式）：需求材料+石油（图标+数量角标下边缘居中）、进度条、石油条、电力条（长度与原版 bar 一致） */
        @Override
        public void display(Table table) {
            super.display(table);
            table.row();
            // info 表撑满面板宽度，使各 bar 长度与原版（生命值等）bar 一致，而非随物品行宽度变化
            table.table(info -> {
                info.left();
                // 需求材料 + 石油（图标横排，需求数量角标覆盖在物品下边缘居中；切换种类即时重建）
                info.add(materialTable);
                info.row();
                // 卫星制造进度条（上方留白与原版一致，避免与材料行/相邻 bar 挤在一起）
                float total = produceTime(selectedType);
                info.add(new Bar(
                        () -> produced ? Core.bundle.get("block.silicon-satellite-launcher.ready")
                                : Core.bundle.format("block.silicon-satellite-launcher.progress", (int) (Math.min(1f, progress / total) * 100f)),
                        () -> produced ? Pal.accent : Pal.ammo,
                        () -> produced ? 1f : Math.min(1f, progress / total)))
                        .height(18f).growX().padTop(8f);
                info.row();
                // 石油储备条（储量到最高轨道需求；实际发射消耗按控制台所选轨道）
                info.add(new Bar(
                        () -> Core.bundle.format("block.silicon-satellite-launcher.fuel", (int) liquids.get(Liquids.oil), OIL_CAPACITY),
                        () -> Pal.ammo,
                        () -> Math.min(1f, liquids.get(Liquids.oil) / OIL_CAPACITY)))
                        .height(18f).growX().padTop(8f);
                info.row();
                // 电力条（单独显示：发射缓冲 Bar，与其他 bar 长度统一，带说明文字：发射缓冲 xx%）
                info.add(new Bar(
                        () -> Core.bundle.format("block.silicon-satellite-launcher.power", (int) (battery / LAUNCH_POWER * 100f)),
                        () -> Pal.power,
                        () -> battery / LAUNCH_POWER))
                        .height(18f).growX().padTop(8f);
            }).growX().left();
        }

        /** 重建需求材料行（按原版：图标 + 需求数量角标（左下角，千位 k 格式），不足时红色斜线；切换种类即时重建） */
        void rebuildMaterialTable() {
            materialTable.clearChildren();
            materialTable.left();
            for (ItemStack stack : productionItems(selectedType)) {
                materialTable.table(r -> {
                    r.left();
                    r.stack(
                            new Image(stack.item.uiIcon),
                            new InsufficientLine(() -> items.get(stack.item) < stack.amount),
                            new Table(t -> t.add(new Label(formatCount(stack.amount), Styles.outlineLabel) {{
                                setFontScale(0.95f);
                            }}).expand().bottom().left().padBottom(2f).padLeft(2f))
                    ).size(40f);
                }).padRight(4f);
            }
            if (productionCryofluid(selectedType) > 0) {
                materialTable.table(r -> {
                    r.left();
                    r.stack(
                            new Image(Liquids.cryofluid.uiIcon),
                            new InsufficientLine(() -> liquids.get(Liquids.cryofluid) < COST_CRYOFLUID),
                            new Table(t -> t.add(new Label(formatCount(COST_CRYOFLUID), Styles.outlineLabel) {{
                                setFontScale(0.95f);
                            }}).expand().bottom().left().padBottom(2f).padLeft(2f))
                    ).size(40f);
                }).padRight(4f);
            }
            // 石油（发射燃料）：与材料同栏同风格展示——数量取自绑定本中枢的唯一控制台的所选轨道
            // （与控制台侧 boundHub 判定对称，节流 30 帧扫描）；无唯一绑定控制台时回退最低轨道 LEO（1.0k），
            // 不足判断取该轨道需求
            materialTable.table(r -> {
                r.left();
                r.stack(
                        new Image(Liquids.oil.uiIcon),
                        new InsufficientLine(() -> liquids.get(Liquids.oil) < SatelliteConsole.fuelFor(displayOrbit >= 0 ? displayOrbit : SatelliteConsole.ORBIT_LEO)),
                        new Table(t -> {
                            Label l = new Label("", Styles.outlineLabel);
                            l.setFontScale(0.95f);
                            l.update(() -> {
                                if ((orbitScanTick = (orbitScanTick + 1) % 30) == 0) refreshDisplayOrbit();
                                l.setText(formatCount(SatelliteConsole.fuelFor(displayOrbit >= 0 ? displayOrbit : SatelliteConsole.ORBIT_LEO)));
                            });
                            t.add(l).expand().bottom().left().padBottom(2f).padLeft(2f);
                        })
                ).size(40f);
            }).padRight(4f);
        }

        /** 刷新石油需求显示轨道：找绑定本中枢的控制台（与控制台侧 boundHub 判定完全对称——
         *  信号有效、控制台自身在信号范围内、且本中枢为其信号"地面覆盖"内唯一中枢），取其所选轨道 */
        void refreshDisplayOrbit() {
            displayOrbit = -1;
            for (Building b : Groups.build) {
                if (!(b instanceof SatelliteConsole.SatelliteConsoleBuild cb) || cb.team != team) continue;
                String sig = cb.selectedSignal;
                if (sig == null || sig.isEmpty()) continue;
                if (!SignalChannel.inSignalRange(team, sig, cb.x, cb.y)) continue;
                Seq<SatelliteLauncher.SatelliteLauncherBuild> hubs = SatelliteManager.hubsInSignal(team, sig);
                if (hubs.size == 1 && hubs.first() == this) {
                    displayOrbit = cb.selectedOrbit;
                    return;
                }
            }
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(selectedType);
            write.f(progress);
            write.bool(produced);
            write.f(battery);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            selectedType = read.i();
            progress = read.f();
            produced = read.bool();
            battery = read.f();
        }
    }
}
