package silicon.world.blocks.signal;

import arc.Core;
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

/**
 * 信号中继器：位于信号覆盖范围（信号源或已激活中继器的 15 格内）时自动激活，
 * 激活后自身同样提供半径 15 格的信号，可级联延长信号覆盖。
 * 信号强度与信号源一致（正态分布衰减，0~15），绑定放置队伍。
 */
public class SignalRelay extends Block {
    /** 中继器信号半径（格） */
    public static final float RADIUS = SignalSource.RADIUS;

    public SignalRelay(String name) {
        super(name);
        // 手动指定建筑类
        buildType = SignalRelayBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        // 需要更新以检测激活状态
        update = true;
        // 需要供电才能工作：50 电力/秒（Mindustry 功耗按 /60 tick 计）
        consumePower(50f / 60f);
        // 可配置：绑定信号源编号（空串=清除绑定）
        configurable = true;
        config(String.class, (SignalRelayBuild b, String value) ->
                b.selectedSource = (value == null || value.isEmpty()) ? null : value);
        // active 状态同步（服务器在激活状态变化时下发；客机应用后 H 覆盖可显示级联段）。
        // 客机伪造的 Boolean 会在下一次 updateActive（20 tick）被服务器重算覆盖，天然自愈。
        config(Boolean.class, (SignalRelayBuild b, Boolean v) -> {
            if (v != null) b.active = v;
        });
    }

    /**
     * 每队中继器缓存（建筑放置/拆除/加载时标记失效重建，避免每帧遍历 Groups.build）。
     */
    private static final ObjectMap<Team, Seq<SignalRelayBuild>> relayCache = new ObjectMap<>();
    private static boolean dirty = true;

    /** 标记缓存失效（建筑增删时调用） */
    public static void markDirty() {
        dirty = true;
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        relayCache.clear();
        for (Building b : Groups.build) {
            if (b instanceof SignalRelayBuild rb) {
                relayCache.get(rb.team, Seq::new).add(rb);
            }
        }
    }

    /** 收集某队伍的所有中继器（走缓存） */
    public static Seq<SignalRelayBuild> allRelays(Team team) {
        rebuildCache();
        return relayCache.get(team, new Seq<>());
    }

    /** 放置预览显示信号范围（同信号源） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        Draw.color(SignalOverlay.SIGNAL_COLOR, 0.5f);
        Drawf.circles(x * 8 + 4f, y * 8 + 4f, RADIUS * 8f);
        Draw.reset();
    }

    public class SignalRelayBuild extends Building {
        /** 是否已激活（在所选信号源覆盖范围内） */
        public boolean active = false;
        /** 绑定的信号源编号（4 位；null/空=未绑定，不发射） */
        public String selectedSource = null;
        /** 中继信道（兼容字段：未绑定时用；绑定后信道跟随所选信号源） */
        public int channel = 1;
        /** 上次渲染的信号源列表签名（配置面板实时刷新用） */
        private String lastSrcSignature = "";
        /** 配置面板源按钮组（选中态实时同步用；面板关闭后无引用也无妨） */
        private arc.scene.ui.ButtonGroup<arc.scene.ui.TextButton> srcBtnGroup = null;
        private int timer = 0;

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            SignalRelay.markDirty();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            SignalRelay.markDirty();
        }

        @Override
        public void updateTile() {
            // 每 20 tick 检测一次激活状态（级联传播：逐级激活）
            if (++timer >= 20) {
                timer = 0;
                updateActive();
            }
        }

        /** 供电是否充足（power.status：0=无电，1=满电） */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        /** 查找绑定的信号源（按编号） */
        public SignalSource.SignalSourceBuild findSource() {
            if (selectedSource == null || selectedSource.isEmpty()) return null;
            for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
                if (sb.signal != null && selectedSource.equals(sb.signal.name)) return sb;
            }
            return null;
        }

        /** 发射信道：绑定信号源后与其保持一致；绑定卫星编码时用在轨卫星发射时固化的信道
         *  （源被拆不影响卫星信道的固化值；但卫星广播有上行门控——编码无存活地面源时卫星静默，
         *  本中继器也会随之去活，此方法仅在激活状态下被广播路径调用）；未绑定用自身 channel */
        public int signalChannel() {
            SignalSource.SignalSourceBuild src = findSource();
            if (src != null) return src.channel;
            // 卫星中继：绑定编码有在轨卫星 → 用其发射时固化的信道（ SatelliteManager 名册）
            for (silicon.util.SatelliteManager.SatelliteRecord r : silicon.util.SatelliteManager.satellites(team)) {
                if (r.code != null && r.code.equals(selectedSource) && r.channel >= 1) return r.channel;
            }
            return channel;
        }

        void updateActive() {
            boolean newActive = false;
            // 被禁用（如开关控制）或断电时不激活
            if (enabled && hasPower()) {
                // 必须绑定信号源，且该源存在并供电
                SignalSource.SignalSourceBuild src = findSource();
                if (src != null && src.power != null && src.power.status > 0.001f) {
                    // 在所选信号源覆盖范围内（或其同源级联转发范围内）才能发射
                    if (Mathf.dst(x, y, src.x, src.y) <= RADIUS * 8f) {
                        newActive = true;
                    } else {
                        // 级联：其他绑定同一信号源且已激活的中继器
                        for (SignalRelayBuild rb : SignalRelay.allRelays(team)) {
                            if (rb == this || !rb.active) continue;
                            if (selectedSource != null && selectedSource.equals(rb.selectedSource)
                                    && Mathf.dst(x, y, rb.x, rb.y) <= RADIUS * 8f) {
                                newActive = true;
                                break;
                            }
                        }
                    }
                }
                // 卫星中继：所选编码存在在轨卫星，且卫星信号在中继器位置有效
                // （星下点覆盖圆内、未被其固化信道干扰压制；总和扣底噪后需 >0.5——
                // 首颗卫星有效强度 1.0 达标，覆盖圆内中继器即可被激活转发，叠星提升抗干扰裕度）。
                // 上行门控在 satelliteStrengthAt 内部：编码无存活地面源（如源被拆）时返回 0 → 中继器去活
                if (!newActive) {
                    float satEff = silicon.util.SatelliteManager.satelliteStrengthAt(team, selectedSource, x, y);
                    if (satEff > 0.5f) newActive = true;
                }
            }
            if (newActive != active) {
                active = newActive;
                SignalRelay.markDirty();
                // 激活状态变化 → 服务器下发到客机（active 是 mod 自定义字段，不随实体网络同步；
                // 按队定向,敌队客户端不再收到我方中继器激活时机）
                if (Vars.net.server()) silicon.util.NetSync.sendTeamConfig(this, active);
            }
        }

        /** 模糊匹配：query 的字符按顺序出现在 code 中（子序列匹配，忽略大小写）；空 query 匹配一切 */
        static boolean fuzzyMatch(String code, String query) {
            int qi = 0;
            for (int i = 0; i < code.length() && qi < query.length(); i++) {
                if (Character.toUpperCase(code.charAt(i)) == Character.toUpperCase(query.charAt(qi))) qi++;
            }
            return qi == query.length();
        }

        /** 重建源按钮区（按搜索模糊过滤；无匹配显示提示） */
        void rebuildSourceButtons(Table srcTable, String filter) {
            srcTable.clearChildren();
            srcTable.center();
            Seq<SignalSource.SignalSourceBuild> srcs = SignalSource.allSources(team);
            boolean any = false;
            arc.scene.ui.ButtonGroup<arc.scene.ui.TextButton> group = new arc.scene.ui.ButtonGroup<>();
            group.setMinCheckCount(0); // 允许全不选（默认 1 会在 add() 时强制勾选第一个按钮，且无法取消）
            int perRow = 5, count = 0;
            for (SignalSource.SignalSourceBuild sb : srcs) {
                String code = sb.signal == null ? "----" : sb.signal.name;
                if (!filter.isEmpty() && !fuzzyMatch(code, filter)) continue;
                any = true;
                arc.scene.ui.TextButton btn = new arc.scene.ui.TextButton(code, Styles.flatTogglet);
                btn.setChecked(code.equals(selectedSource));
                btn.clicked(() -> configure(code));
                group.add(btn);
                srcTable.add(btn).size(88f, 40f).pad(1f);
                if (++count % perRow == 0) srcTable.row();
            }
            srcBtnGroup = group;
            if (!any) {
                srcTable.add(Core.bundle.get("block.silicon-signal-relay.search.none"))
                        .color(arc.graphics.Color.lightGray).pad(2f);
            }
        }

        /** 信号源列表签名（数量 + 编号集合），用于检测列表变化 */
        String sourceSignature() {
            StringBuilder sb = new StringBuilder();
            Seq<SignalSource.SignalSourceBuild> srcs = SignalSource.allSources(team);
            sb.append(srcs.size).append(':');
            for (SignalSource.SignalSourceBuild s : srcs) {
                sb.append(s.signal == null ? "----" : s.signal.name).append(',');
            }
            return sb.toString();
        }

        /** 配置面板（与信号源面板风格一致：顶部当前编号 + 居中黄色标题 + 搜索 + 按钮行；多信号源时按钮区限高滚轮翻页） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                // 顶部：当前转发编号（跨满整行居中，与信号源编号显示风格一致）
                t.label(() -> Core.bundle.format("block.silicon-signal-relay.source.current",
                        selectedSource == null || selectedSource.isEmpty() ? Core.bundle.get("block.silicon-signal-relay.nobind") : selectedSource))
                        .colspan(SignalJammer.CHANNEL_MAX).center().pad(2f);
                t.row();
                // 标题居中，原版黄色（跨满整行，避免挤占首列导致按钮间距不均）
                t.add(Core.bundle.get("block.silicon-signal-relay.source")).colspan(SignalJammer.CHANNEL_MAX).center()
                        .color(mindustry.graphics.Pal.accent).pad(2f);
                t.row();
                // 源按钮区（先声明，供搜索框回调引用）：ScrollPane 限制高度，每行 5 个换行，按钮网格居中
                Table srcTable = new Table();
                // 搜索框（标题下方）：按编号过滤信号源
                arc.scene.ui.TextField search = t.field("", text -> rebuildSourceButtons(srcTable, text.trim()))
                        .colspan(SignalJammer.CHANNEL_MAX).width(280f).padTop(2f).get();
                search.setMessageText(Core.bundle.get("block.silicon-signal-relay.search"));
                search.setMaxLength(4);
                t.row();
                arc.scene.ui.ScrollPane pane = new arc.scene.ui.ScrollPane(srcTable, Styles.noBarPane);
                pane.setScrollingDisabled(true, false); // 禁水平滚动，允许垂直滚轮翻页
                // 跨满整行（与标题同宽），限高
                t.add(pane).height(160f).colspan(SignalJammer.CHANNEL_MAX).growX().padTop(2f);
                t.row();
                // 清除按钮（跨满整行居中，与标题/按钮对齐）
                t.button(Core.bundle.get("block.silicon-signal-relay.source.clear"), Styles.defaultt,
                        () -> configure("")).colspan(SignalJammer.CHANNEL_MAX).center().size(88f, 40f).padTop(2f);
                t.row();
                // 信号频谱：本点 5 信道占用/干扰/可用强度（当前转发信道行高亮）
                silicon.ui.SignalSpectrum.buildSection(t, this, () -> signalChannel());
                // 实时刷新：信号源列表变化（增删/编号变更）时重建按钮区（保持搜索过滤；点击不受影响）
                lastSrcSignature = "";
                pane.update(() -> {
                    String sig = sourceSignature();
                    if (!sig.equals(lastSrcSignature)) {
                        lastSrcSignature = sig;
                        rebuildSourceButtons(srcTable, search.getText().trim());
                    }
                    // 选中态实时同步：清除绑定/外部 configure 变更不触发重建，这里让按钮高亮始终
                    // 跟随 selectedSource（未绑定=全部不亮，杜绝"未绑定却残留选中黄框"）
                    if (srcBtnGroup != null) {
                        for (arc.scene.ui.TextButton b : srcBtnGroup.getButtons()) {
                            b.setChecked(selectedSource != null && selectedSource.contentEquals(b.getText()));
                        }
                    }
                });
                // 初始填充全部信号源
                rebuildSourceButtons(srcTable, "");
            }).pad(4f);
        }

        /** 本中继器在指定世界坐标处的原始信号强度（0~15，激活时；干扰由 SignalChannel 统一计算） */
        public float strengthAt(float wx, float wy) {
            if (!active) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy);
        }

        /** 选中时显示信号范围（激活=深蓝，未激活=灰色） */
        @Override
        public void drawSelect() {
            super.drawSelect();
            Draw.color(active ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, active ? 0.6f : 0.3f);
            Lines.stroke(2f);
            Lines.circle(x, y, RADIUS * 8f);
            Draw.reset();
        }

        /** 选中显示：仅保留原版 bar（生命/电力）+ 信号唯一编号（绑定源编号） */
        @Override
        public void display(Table table) {
            super.display(table);
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-signal-relay.source.current",
                    selectedSource == null || selectedSource.isEmpty() ? Core.bundle.get("block.silicon-signal-relay.nobind") : selectedSource)).pad(2f);
        }

        /** 存档版本：2 = bool(active) + i(channel) + str(selectedSource)；覆写 version() 使读档时绑定/信道不丢失 */
        @Override
        public byte version() {
            return 2;
        }

        /** 存档/网络同步 active 字段（host 上由 updateActive 重算，保证一致性） */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(active);
            write.i(channel);
            write.str(selectedSource == null ? "" : selectedSource);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            active = read.bool();
            if (revision >= 1) {
                channel = read.i();
            }
            if (revision >= 2) {
                String s = read.str();
                selectedSource = s.isEmpty() ? null : s;
            }
        }
    }
}
