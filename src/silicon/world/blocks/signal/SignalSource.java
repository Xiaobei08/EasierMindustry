package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.ui.Styles;
import mindustry.world.Block;
import silicon.util.SignalOverlay;
import silicon.world.meta.Signal;

/**
 * 信号源：放置后注册一个信号（名称 4 个字母或数字，绑定放置队伍）。
 * 在半径 15 格的圆内广播信号，强度随距离线性衰减（最大 15，最小 0）。
 * 按 H 键可查看信号覆盖（缩放视角较小时逐格显示强度数字，较大时显示绿色范围）。
 */
public class SignalSource extends Block {
    /** 信号覆盖半径（格） */
    public static final float RADIUS = 15f;
    /** 信号最大强度 */
    public static final int MAX_STRENGTH = 15;
    /** 信号名称长度 */
    public static final int NAME_LENGTH = 4;

    public SignalSource(String name) {
        super(name);
        // 手动指定建筑类（Mindustry 的反射自动检测对非静态内部类不可靠）
        buildType = SignalSourceBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        // 必须 update=true：Groups.build（活动建筑组）只包含需要更新的建筑，否则放置后无法被查询/绘制
        update = true;
        configurable = true;
        // 需要供电才能广播信号：150 电力/秒（Mindustry 功耗按 /60 tick 计）
        consumePower(150f / 60f);
        // 用于客户端同步信号名（服务器通过 tileConfig 下发）
        config(String.class, (SignalSourceBuild b, String value) -> {
            // tileConfig 双向通道同队客户端可发包:必须校验格式(4 位大写字母/数字),
            // 否则超长/任意字符串会进入 SignalOverlay 颜色缓存与各处 UI
            if (value == null || value.length() != NAME_LENGTH) return;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
                if (!ok) return;
            }
            b.signal = new Signal(value);
            // 客机编码经 tileConfig 到位会改变上行门控判定（placed 时 signal 还是 null，缓存可能已记 false）
            SignalChannel.invalidateLiveSources();
        });
        // 信道（1~5）
        config(Integer.class, (SignalSourceBuild b, Integer v) -> b.channel = Math.max(1, Math.min(SignalJammer.CHANNEL_MAX, v)));
    }

    /**
     * 以 (cx, cy) 为信号源中心、指定世界坐标 (wx, wy) 处的信号强度（世界坐标为像素，1 格 = 8px）。
     * 覆盖半径外（无信号区域）强度为 0；覆盖内按归一化正态分布衰减：
     * 中心最强（15），随距离平滑衰减，半径 R 处精确归零（无悬崖断环）。
     * 通用方法：信号源与信号中继器共用。
     */
    public static float strengthAt(float cx, float cy, float wx, float wy) {
        float dist = Mathf.dst(wx, wy, cx, cy) / 8f; // 像素 → 格
        if (dist >= RADIUS) return 0f; // 无信号区域强度为 0
        // 正态分布衰减：σ = 6 格；减去边缘值并归一化，使 R 处精确为 0（消除旧版 0.66 的悬崖断环）
        float sigma = 6f;
        float g = (float) Math.exp(-(dist * dist) / (2f * sigma * sigma));
        float edge = (float) Math.exp(-(RADIUS * RADIUS) / (2f * sigma * sigma));
        return MAX_STRENGTH * (g - edge) / (1f - edge);
    }

    /**
     * 每队信号源缓存（建筑放置/拆除/加载时标记失效重建，避免每帧遍历 Groups.build）。
     */
    private static final ObjectMap<Team, Seq<SignalSourceBuild>> sourceCache = new ObjectMap<>();
    private static boolean dirty = true;

    /** 标记缓存失效（建筑增删时调用）；同步失效卫星上行门控缓存（SignalChannel.hasLiveSource） */
    public static void markDirty() {
        dirty = true;
        SignalChannel.invalidateLiveSources();
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        sourceCache.clear();
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb) {
                sourceCache.get(sb.team, Seq::new).add(sb);
            }
        }
    }

    /** 收集某队伍的所有信号源（走缓存；get 的 Supplier 形式避免缓存未命中之外也分配） */
    public static Seq<SignalSourceBuild> allSources(Team team) {
        rebuildCache();
        return sourceCache.get(team, Seq::new);
    }

    /** 生成一个未被使用的 4 字符信号名（大写字母 A-Z + 数字 0-9） */
    public static String generateUniqueName() {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 200; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < NAME_LENGTH; i++) {
                sb.append(chars.charAt(Mathf.random(chars.length() - 1)));
            }
            String candidate = sb.toString();
            if (!isNameUsed(candidate)) return candidate;
        }
        // 兜底:随机 200 次未命中(理论仅当 36^4≈168 万空间近乎耗尽)时,
        // 一次性收集占用名后按字典序找第一个未用编码,避免固定返回 "ZZZZ" 造成重复编码。
        // 空间真耗尽(不可达)时维持旧兜底
        arc.struct.ObjectSet<String> used = new arc.struct.ObjectSet<>();
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb && sb.signal != null) {
                used.add(sb.signal.name);
            }
        }
        int base = chars.length();
        for (int n = 0; n < base * base * base * base; n++) {
            String c = new String(new char[]{
                    chars.charAt(n / (base * base * base) % base),
                    chars.charAt(n / (base * base) % base),
                    chars.charAt(n / base % base),
                    chars.charAt(n % base)});
            if (!used.contains(c)) return c;
        }
        return "ZZZZ";
    }

    static boolean isNameUsed(String name) {
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb && sb.signal != null && name.equals(sb.signal.name)) {
                return true;
            }
        }
        return false;
    }

    /** 放置预览（拖拽放置时）显示信号覆盖范围，同原版电力节点（x/y 为格坐标，转像素） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        Draw.color(SignalOverlay.SIGNAL_COLOR, 0.5f);
        Drawf.circles(x * 8 + 4f, y * 8 + 4f, RADIUS * 8f);
        Draw.reset();
    }

    public class SignalSourceBuild extends Building {
        /** 本源注册的信号（null 表示未生成/未同步） */
        public Signal signal;
        /** 信道（1~5，默认 1）：同信道信号互相隔离；被干扰器压制时失效 */
        public int channel = 1;

        @Override
        public void placed() {
            super.placed();
            // 部分放置路径不会自动调用 add()（建筑不在活动组），手动补偿加入
            if (!added) {
                add();
            }
            // 服务器端生成唯一信号；客户端等待 tileConfig 同步（按队定向,不向敌队广播）
            if (Vars.net.client()) return;
            signal = new Signal(generateUniqueName());
            if (Vars.net.server()) {
                silicon.util.NetSync.sendTeamConfig(this, signal.name);
            }
        }

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            markDirty();
            // 尽力向客户端重发信号名。注意:读档时 onProximityAdded 先于 read() 执行,
            // 此时 signal 还是 null,此处的重发实际不生效——真正的客机同步靠 MP 世界
            // 快照(自定义 write() 数据随 writeMap 下发),此调用仅覆盖运行期信号重建的边缘情形
            if (signal != null && Vars.net.server()) {
                silicon.util.NetSync.sendTeamConfig(this, signal.name);
            }
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            markDirty();
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            // 夺取/换队：旧队的 (team, code) 缓存必须失效，否则旧队中继器/卫星
            // 依据残留缓存继续广播一个已不属于它的编码，直到下一次增删源才纠正
            markDirty();
        }

        /** 供电是否充足（power.status：0=无电，1=满电） */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        /** 本源在指定世界坐标处的原始信号强度（0~15；无信号、断电或被关闭（enabled=false）时为 0；干扰由 SignalChannel 统一计算） */
        public float strengthAt(float wx, float wy) {
            if (signal == null || !hasPower() || !enabled) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy);
        }

        /** 配置面板（选择信道界面，灰底面板）：顶部显示本信号源编号，下方选信道 */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                // 编号跨满整行（避免挤占首列导致按钮间距不均），居中
                t.label(() -> Core.bundle.format("block.silicon-signal-source.code",
                        signal == null ? "----" : signal.name)).colspan(SignalJammer.CHANNEL_MAX).center().pad(2f);
                t.row();
                // 标题居中，原版黄色
                t.add(Core.bundle.get("block.silicon-signal-source.channel")).colspan(SignalJammer.CHANNEL_MAX).center()
                        .color(mindustry.graphics.Pal.accent).pad(2f);
                t.row();
                arc.scene.ui.ButtonGroup<arc.scene.ui.TextButton> group = new arc.scene.ui.ButtonGroup<>();
                for (int i = 1; i <= SignalJammer.CHANNEL_MAX; i++) {
                    arc.scene.ui.TextButton btn = new arc.scene.ui.TextButton(String.valueOf(i), Styles.flatTogglet);
                    btn.setChecked(channel == i);
                    int ch = i;
                    btn.clicked(() -> configure(ch));
                    group.add(btn);
                    t.add(btn).size(44f, 40f).pad(1f);
                }
            }).pad(4f);
        }

        /** 选中时显示信号覆盖范围（供电=深蓝，断电=灰色；填充圆 + 圆环，类似电力节点；半径为像素，15 格 = 120px） */
        @Override
        public void drawSelect() {
            super.drawSelect();
            boolean on = hasPower();
            Draw.color(on ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, on ? 0.07f : 0.04f);
            Fill.poly(x, y, 64, RADIUS * 8f);
            Draw.color(on ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, signal == null || !on ? 0.3f : 0.7f);
            Lines.stroke(2f);
            Lines.circle(x, y, RADIUS * 8f);
            Draw.reset();
        }

        /** 存档版本：1 = str(signal) + i(channel)；覆写 version() 使读档时 revision 正确（否则 channel 不读取） */
        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(signal == null ? "" : signal.name);
            write.i(channel);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            String name = read.str();
            signal = name.isEmpty() ? null : new Signal(name);
            if (revision >= 1) {
                channel = read.i();
            }
        }
    }
}
