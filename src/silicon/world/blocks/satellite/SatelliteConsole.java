package silicon.world.blocks.satellite;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import silicon.util.SatelliteManager;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalSource;

/**
 * 卫星控制台（3×3）：卫星的发射终端，仅提供发射操作。
 * 不存储燃料与电力——燃料（石油）与缓冲电力（10000）均由卫星发射中枢提供；
 * 卫星种类由卫星发射中枢选择。点击方块弹出界面选择轨道与信号后发射。
 * 轨道影响中枢燃油需求（LEO 1000 / MEO 2500 / GEO 5000 / SSO 8000）；
 * 信号卫星仅可发射到 LEO/MEO/GEO；SSO 供信号卫星以外的卫星类型使用（轨道划分不同卫星的功能）。
 */
public class SatelliteConsole extends Block {
    /** 卫星种类：信号卫星（与发射中枢保持一致） */
    public static final int TYPE_SIGNAL = 0;

    // —— 发射轨道 ——
    public static final int ORBIT_LEO = 0, ORBIT_MEO = 1, ORBIT_GEO = 2, ORBIT_SSO = 3;
    public static final int ORBIT_COUNT = 4;
    /** 各轨道所需石油（单位）：LEO 1000 / MEO 2500 / GEO 5000 / SSO 8000 */
    public static final int[] ORBIT_FUEL = {1000, 2500, 5000, 8000};
    /** 最大轨道需求（中枢储油上限按此设计） */
    public static final int ORBIT_MAX_FUEL = 8000;
    private static final String[] ORBIT_KEYS = {
            "block.silicon-satellite-console.orbit.leo",
            "block.silicon-satellite-console.orbit.meo",
            "block.silicon-satellite-console.orbit.geo",
            "block.silicon-satellite-console.orbit.sso"
    };

    /** 轨道燃油需求（越界 clamp 到 LEO） */
    public static int fuelFor(int orbit) {
        return ORBIT_FUEL[Math.max(0, Math.min(ORBIT_COUNT - 1, orbit))];
    }

    /** 轨道显示名（低地球轨道 (LEO) 等，bundle） */
    public static String orbitName(int orbit) {
        return Core.bundle.get(ORBIT_KEYS[Math.max(0, Math.min(ORBIT_COUNT - 1, orbit))]);
    }

    /** 卫星种类 × 轨道允许性：信号卫星限 LEO/MEO/GEO（SSO 不对信号卫星开放，供其他卫星类型使用） */
    public static boolean orbitAllowed(int type, int orbit) {
        return !(type == TYPE_SIGNAL && orbit == ORBIT_SSO);
    }

    /** 耗电（/秒，Mindustry 按 /60 tick 计）：100 电力/秒 */
    public static final float POWER_CONSUMPTION = 100f / 60f;

    public SatelliteConsole(String name) {
        super(name);
        buildType = SatelliteConsoleBuild::new;
        size = 3;
        solid = true;
        destructible = true;
        update = true;
        configurable = true;
        // 需要供电：100 电力/秒（选中面板显示原版电力条）
        consumePower(POWER_CONSUMPTION);
        // 卫星所属信号走原版 configure 机制同步（服务器 tileConfig 权威下发，各端 selectedSignal 一致）
        config(String.class, (SatelliteConsoleBuild b, String value) ->
                b.selectedSignal = (value == null || value.isEmpty()) ? null : value);
        // 发射轨道同步
        config(Integer.class, (SatelliteConsoleBuild b, Integer v) ->
                b.selectedOrbit = Math.max(ORBIT_LEO, Math.min(ORBIT_SSO, v == null ? ORBIT_LEO : v)));
    }

    public class SatelliteConsoleBuild extends Building {
        /** 卫星所属信号编码（4 位；null=无归属，全图信号保持蓝色） */
        public String selectedSignal = null;
        /** 发射轨道（默认 LEO） */
        public int selectedOrbit = ORBIT_LEO;
        /** 上次渲染的信号源列表签名（窗口实时刷新用） */
        private String lastSrcSignature = "";
        /** 窗口绑定状态缓存刷新节流（tick） */
        private static final int UI_REFRESH = 8;
        private int uiTick = 0;
        /** 绑定状态缓存：信号范围内唯一中枢（多台/未绑定时为 null） */
        private SatelliteLauncher.SatelliteLauncherBuild boundHub = null;
        private int hubCount = 0;
        private int consoleCount = 0;
        private boolean consoleInRange = false;
        /** SSO 轨道按钮（动态灰化用） */
        private TextButton ssoBtn = null;

        /** 刷新绑定状态缓存（节流调用） */
        void refreshBinding() {
            consoleInRange = selectedSignal != null && !selectedSignal.isEmpty()
                    && SignalChannel.inSignalRange(team, selectedSignal, x, y);
            Seq<SatelliteLauncher.SatelliteLauncherBuild> hubs = SatelliteManager.hubsInSignal(team, selectedSignal);
            hubCount = hubs.size;
            boundHub = hubCount == 1 ? hubs.first() : null;
            consoleCount = SatelliteManager.consolesInSignal(team, selectedSignal);
            if (ssoBtn != null) ssoBtn.setDisabled(ssoBlocked());
        }

        /** 绑定状态错误键（null=绑定正常可发射） */
        String bindingKey() {
            if (!consoleInRange || hubCount == 0) return "block.silicon-satellite-console.nohub";
            if (hubCount > 1) return "block.silicon-satellite-console.multihub";
            if (consoleCount > 1) return "block.silicon-satellite-console.multiconsole";
            return null;
        }

        /** SSO 是否灰化：绑定的中枢选择了信号卫星（信号卫星不能发 SSO，选中即灰化，与生产进度无关） */
        boolean ssoBlocked() {
            return boundHub != null && boundHub.selectedType == TYPE_SIGNAL;
        }

        /** 发射卫星：本队可点发射。权威端（主机/单机）直接执行；纯客机发请求由主机执行并广播/反馈结果 */
        public void launch() {
            // 关闭（enabled=false，逻辑门/开关控制）：不能发射
            if (!enabled) {
                Vars.ui.showInfoToast(Core.bundle.get("block.silicon-satellite-console.disabled"), 3f);
                return;
            }
            // 纯客机（联网但非主机）：发射请求交给主机（sat-launch），主机校验后执行并广播状态、反馈失败原因
            if (Vars.net.active() && !SatelliteManager.isAuthority()) {
                Call.serverPacketReliable("sat-launch", tileX() + "," + tileY() + "|"
                        + (selectedSignal == null ? "" : selectedSignal) + "|" + selectedOrbit);
                return;
            }
            // 权威端：本地执行（建筑逻辑与卫星状态均在主机/单机计算）
            doLaunch(selectedSignal, selectedOrbit);
        }

        /** 权威端发射执行 + 结果提示（仅控制台本地直发路径；主机的 sat-launch 包处理器走
         *  SatelliteManager.launch 并自行回发 sat-result，不经过这里） */
        public void doLaunch(String signalName, int orbit) {
            int result = SatelliteManager.launch(team, signalName, orbit, x, y);
            String key;
            switch (result) {
                case SatelliteManager.LAUNCH_OK: key = "block.silicon-satellite-console.success"; break;
                case SatelliteManager.LAUNCH_NO_READY: key = "block.silicon-satellite-console.noready"; break;
                case SatelliteManager.LAUNCH_NO_FUEL: key = "block.silicon-satellite-console.nofuel"; break;
                case SatelliteManager.LAUNCH_NO_POWER: key = "block.silicon-satellite-console.nopower"; break;
                case SatelliteManager.LAUNCH_ORBIT_FORBIDDEN: key = "block.silicon-satellite-console.orbitForbidden"; break;
                case SatelliteManager.LAUNCH_NO_HUB: key = "block.silicon-satellite-console.nohub"; break;
                case SatelliteManager.LAUNCH_MULTI_HUB: key = "block.silicon-satellite-console.multihub"; break;
                case SatelliteManager.LAUNCH_MULTI_CONSOLE: key = "block.silicon-satellite-console.multiconsole"; break;
                case SatelliteManager.LAUNCH_TEST_SANDBOX: key = "block.silicon-satellite-console.sandboxOnly"; break;
                default: key = "block.silicon-satellite-console.fail"; break;
            }
            if (result == SatelliteManager.LAUNCH_OK) {
                Vars.ui.showInfoToast(Core.bundle.format(key, SatelliteManager.launchedCount(team)), 3f);
            } else {
                Vars.ui.showInfoToast(Core.bundle.get(key), 3f);
            }
        }

        /** 选中时的小面板：仅一个"打开界面"按钮，点击后打开可拖动窗口 */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.button(Core.bundle.get("block.silicon-satellite-console.open"), Styles.defaultt, () -> {
                // 隐藏原版小面板（此时 showConfig 已完成，hide 动画正常生效），再打开可拖动窗口
                if (Vars.control != null && Vars.control.input != null) {
                    Vars.control.input.config.hideConfig();
                }
                openDialog();
            }).size(160f, 48f).pad(4f);
        }

        /** 打开可拖动窗口 */
        void openDialog() {
            BaseDialog dialog = new BaseDialog(Core.bundle.get("block.silicon-satellite-console.title"));
            // 可拖动式窗口（原版对话框默认可拖标题栏移动）；不铺满全屏
            dialog.setFillParent(false);
            dialog.setMovable(true);
            // 尺寸按屏幕比例动态计算（大屏封顶 660×580，小屏按比例缩小；内容增加轨道区后调高上限）
            float w = Math.min(660f, Core.graphics.getWidth() * 0.6f);
            float h = Math.min(580f, Core.graphics.getHeight() * 0.82f);
            dialog.cont.pane(content -> rebuildFull(content, dialog)).width(w).height(h).pad(10f);
            dialog.buttons.button(Core.bundle.get("block.silicon-satellite-console.close"), Styles.defaultt, dialog::hide)
                    .size(120f, 40f).padTop(6f);
            dialog.show();
        }

        /** 卫星种类短名（信号卫星 / 测试卫星，bundle） */
        String typeShortName(int type) {
            return type == TYPE_SIGNAL
                    ? Core.bundle.get("block.silicon-satellite-console.type.short.signal")
                    : Core.bundle.get("block.silicon-satellite-console.type.short.test");
        }

        /** 名称行：绑定的中枢所准备发射/制造中的卫星（动态；未绑定或异常时显示 —） */
        void addSatelliteNameRow(Table table) {
            table.label(() -> {
                String r = Core.bundle.get("block.silicon-satellite-console.name.none");
                String m = Core.bundle.get("block.silicon-satellite-console.name.none");
                if (boundHub != null) {
                    if (boundHub.produced) r = typeShortName(boundHub.selectedType);
                    if (!boundHub.produced && boundHub.progress > 0f) m = typeShortName(boundHub.selectedType);
                }
                return Core.bundle.format("block.silicon-satellite-console.name.line", r, m);
            }).color(Color.lightGray).pad(2f);
        }

        /** 轨道选择按钮行（4 单选）；绑定的中枢选择信号卫星时 SSO 即灰化（信号卫星不能发 SSO） */
        void rebuildOrbitRow(Table table) {
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.orbit.current", orbitName(selectedOrbit)))
                    .color(arc.graphics.Color.lightGray).padTop(6f);
            table.row();
            table.label(() -> Core.bundle.get("block.silicon-satellite-console.orbit.title")).color(Color.lightGray).pad(2f);
            table.row();
            Table row = new Table();
            ButtonGroup<TextButton> group = new ButtonGroup<>();
            group.setMinCheckCount(0); // 允许全不选（默认 1 会在 add() 时强制勾选第一个按钮，且无法取消）
            ssoBtn = null;
            for (int o = ORBIT_LEO; o < ORBIT_COUNT; o++) {
                final int orbit = o;
                TextButton btn = new TextButton(orbitKeyShort(orbit), Styles.flatTogglet);
                btn.setChecked(selectedOrbit == orbit);
                btn.clicked(() -> {
                    selectedOrbit = orbit;
                    configure(orbit);
                });
                group.add(btn);
                if (orbit == ORBIT_SSO) ssoBtn = btn;
                row.add(btn).size(110f, 40f).pad(2f);
            }
            table.add(row).pad(2f);
        }

        /** 轨道按钮短标签（LEO 等） */
        static String orbitKeyShort(int orbit) {
            switch (orbit) {
                case ORBIT_LEO: return "LEO";
                case ORBIT_MEO: return "MEO";
                case ORBIT_GEO: return "GEO";
                default: return "SSO";
            }
        }

        /** 窗口内容：卫星名称 + 状态 + 当前信号 + 信号选择 + 轨道选择 + 绑定状态 + 发射 */
        void rebuildFull(Table table, BaseDialog dialog) {
            table.clearChildren();
            table.top();
            // 卫星名称行（绑定的中枢准备发射/制造中）——动态
            addSatelliteNameRow(table);
            table.row();
            // 状态（动态刷新）
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.status.ready",
                    SatelliteManager.readyCount(team))).color(Color.lightGray).pad(2f);
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.status.orbit",
                    SatelliteManager.launchedCount(team))).color(Color.lightGray).pad(2f);
            table.row();
            // 当前卫星所属信号（与中继器"当前编号"风格一致）
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.signal.current",
                    selectedSignal == null || selectedSignal.isEmpty()
                            ? Core.bundle.get("block.silicon-satellite-console.nobind") : selectedSignal))
                    .pad(2f);
            table.row();
            // 信号选择区（参考信号中继器：搜索框模糊过滤 + 滚轮按钮网格 + 清除）
            Table srcTable = new Table();
            TextField search = table.field("", text -> rebuildSourceButtons(srcTable, text.trim()))
                    .width(280f).padTop(2f).get();
            search.setMessageText(Core.bundle.get("block.silicon-satellite-console.signal.search"));
            search.setMaxLength(4);
            table.row();
            ScrollPane pane = new ScrollPane(srcTable, Styles.noBarPane);
            pane.setScrollingDisabled(true, false); // 禁水平滚动，垂直滚轮翻页
            table.add(pane).height(130f).growX().padTop(2f);
            table.row();
            // 清除按钮
            table.button(Core.bundle.get("block.silicon-satellite-console.signal.clear"), Styles.defaultt, () -> {
                selectedSignal = null;
                configure("");
                rebuildSourceButtons(srcTable, search.getText().trim());
            }).size(88f, 40f).padTop(2f);
            // 轨道区
            rebuildOrbitRow(table);
            table.row();
            // 绑定状态行（未绑定/存在多个…时红字提示；正常显示已绑定）
            table.label(() -> {
                String k = bindingKey();
                String t = k == null ? Core.bundle.get("block.silicon-satellite-console.bound")
                        : Core.bundle.get(k);
                return k == null ? t : "[scarlet]" + t + "[]";
            }).pad(2f);
            table.row();
            // 发射按钮（状态/名称为动态 label，发射后自动刷新，无需重建窗口）
            table.button(Core.bundle.get("block.silicon-satellite-console.launch"), Styles.defaultt, this::launch)
                    .size(280f, 56f).padTop(8f);
            // 实时刷新：绑定状态缓存（节流）+ 信号源列表变化时重建按钮区（保持搜索过滤）
            lastSrcSignature = "";
            uiTick = 0;
            pane.update(() -> {
                if (++uiTick >= UI_REFRESH) {
                    uiTick = 0;
                    refreshBinding();
                }
                String sig = sourceSignature();
                if (!sig.equals(lastSrcSignature)) {
                    lastSrcSignature = sig;
                    rebuildSourceButtons(srcTable, search.getText().trim());
                }
            });
            refreshBinding();
            // 初始填充全部信号源
            rebuildSourceButtons(srcTable, "");
        }

        /** 模糊匹配：query 的字符按顺序出现在 code 中（子序列匹配，忽略大小写）；空 query 匹配一切（与中继器一致） */
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
            ButtonGroup<TextButton> group = new ButtonGroup<>();
            group.setMinCheckCount(0); // 允许未选择信号（默认 1 会在 add() 时强制勾选第一个按钮，清除后依然残留）
            int perRow = 5, count = 0;
            for (SignalSource.SignalSourceBuild sb : srcs) {
                String code = sb.signal == null ? "----" : sb.signal.name;
                if (!filter.isEmpty() && !fuzzyMatch(code, filter)) continue;
                any = true;
                TextButton btn = new TextButton(code, Styles.flatTogglet);
                btn.setChecked(code.equals(selectedSignal));
                // configure 走网络同步（服务器权威下发，各端一致）；乐观先设本地并刷新按钮选中态
                btn.clicked(() -> {
                    selectedSignal = code;
                    configure(code);
                    rebuildSourceButtons(srcTable, filter);
                });
                group.add(btn);
                srcTable.add(btn).size(88f, 40f).pad(1f);
                if (++count % perRow == 0) srcTable.row();
            }
            if (!any) {
                srcTable.add(Core.bundle.get("block.silicon-satellite-console.signal.none"))
                        .color(Color.lightGray).pad(2f);
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

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(selectedSignal == null ? "" : selectedSignal);
            write.i(selectedOrbit);
            // v2:追加本队卫星名册快照。卫星实体的编码/信道/相位无处随单位持久化（无自定义实体组件），
            // 由控制台代存——所有控制台写同一份全局快照，读侧按 unitId 去重并集，任一存活控制台即可恢复。
            // 相位在保存时推进到当前时刻（扫描进度 u，GEO 为定点方位角）：读档后 Time.time 归零，轨迹位置以存档进度续接，卫星不跳位
            arc.struct.Seq<SatelliteManager.SatelliteRecord> list = SatelliteManager.satellites(team);
            write.i(list.size);
            for (SatelliteManager.SatelliteRecord r : list) {
                write.i(r.unitId);
                write.i(r.channel);
                write.i(r.orbit);
                write.str(r.code == null ? "" : r.code);
                write.i(Float.floatToIntBits(SatelliteManager.phaseForSave(r)));
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            String s = read.str();
            selectedSignal = s.isEmpty() ? null : s;
            if (revision >= 1) {
                selectedOrbit = Math.max(ORBIT_LEO, Math.min(ORBIT_SSO, read.i()));
            }
            if (revision >= 2) {
                int n = read.i();
                for (int i = 0; i < n; i++) {
                    int unitId = read.i();
                    int channel = read.i();
                    int orbit = read.i();
                    String code = read.str();
                    float phase = Float.intBitsToFloat(read.i());
                    SatelliteManager.restoreRecord(team, unitId, channel, orbit, code, phase);
                }
            }
        }

        @Override
        public byte version() {
            return 2;
        }
    }
}
