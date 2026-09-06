package silicon.world.blocks.sandbox;

import arc.Core;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.game.Team;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.meta.BlockGroup;
import silicon.util.MessageSync;
import silicon.util.MessageSystem;
import silicon.util.MessageSystem.Message;
import silicon.util.MessageSystem.MessageType;

/**
 * “消息测试”调试方块：用于向 {@link MessageSystem} 手动投递一条测试消息，方便调试消息面板。
 * <p>
 * 配置界面（点选方块后弹出）：
 * <ul>
 *   <li>标题 / 内容：两个文本输入框。</li>
 *   <li>类型：瞬时（脉冲式）/ 持续型（消息源控制生命周期）。选持续型时发送按钮变为切换式
 *       「发送并持续更新 / 取消投递」，已投递的内容/模板/气泡颜色修改会在消息面板中<b>实时同步</b>。</li>
 *   <li>模板：无 / 紧急（红·高）/ 警告（黄·中）/ 提示（蓝·低）/ 常规（灰白·低）五选一；
 *       选模板时颜色、优先级、图标均由模板提供。</li>
 *   <li>气泡颜色：HEX 文本框，仅在选择「无」模板时作为自定义气泡颜色应用
 *       （避免与模板颜色冲突；消失提示覆盖层会自动采用气泡颜色）。</li>
 *   <li>消失时间：滑块，范围 0~10 秒；0 秒表示不消失（常驻）。</li>
 *   <li>发送一条消息：按钮按下即发布到消息系统。</li>
 * </ul>
 * 该方块不做任何游戏逻辑，仅作调试用；置于 sandbox 分组。
 */
public class MessageTest extends Block {
    /** 消失时间滑块最大值（秒） */
    private static final float MAX_LIFE = 10f;

    public MessageTest(String name) {
        super(name);
        update = true;
        solid = true;
        configurable = true;
        group = BlockGroup.logic;
    }

    /** 模板序号 → 消息工厂：0 无（自定义）/ 1 紧急 / 2 警告 / 3 提示 / 4 常规；-1 表示无模板。 */
    private static Message make(int templateIdx, String title, String content) {
        return switch (templateIdx) {
            case 1 -> MessageSystem.emergency(title, content);
            case 2 -> MessageSystem.warning(title, content);
            case 3 -> MessageSystem.info(title, content);
            case 4 -> MessageSystem.normal(title, content);
            default -> MessageSystem.newMessage(title, content); // 无模板
        };
    }

    /**
     * 实际发送一条瞬时消息：标题 + 内容 + 模板/自定义颜色 + 生命时长（0 = 常驻）。由配置界面按钮调用。
     * <p>
     * 颜色冲突处理：
     * <ul>
     *   <li>选择了模板（1~4）→ 使用模板颜色与优先级、图标，HEX 颜色<b>不应用</b>（避免冲突）。</li>
     *   <li>选择了「无」（0）→ 使用 HEX 自定义颜色（默认回退灰白气泡）。</li>
     * </ul>
     * 无论走哪条，气泡底色都会作为消失时间提示覆盖层的颜色。
     */
    private static void post(int templateIdx, String title, String content, Color bubble, boolean hexValid, float life, boolean localized, Team team, boolean global) {
        Message m = make(templateIdx, title, content);
        if (templateIdx == 0 && hexValid) m.background(bubble); // 仅「无模板」时应用自定义颜色
        if (localized) applyLocalized(m);
        m.team(team).global(global);
        m.life(life <= 0.001f ? -1f : life); // 0 → 不设时限（常驻）
        // 权威进程（服务器/单机）直接登记并广播；纯客户端把请求交给服务器，等其广播确认镜像
        if (MessageSystem.isAuthoritative()) {
            MessageSystem.instance.add(m);
        } else {
            MessageSync.requestAdd(m);
        }
    }

    /** 演示本地化键引用：把消息的标题/内容切换为引用 bundle 键（内容为本地化键常量内容；标题用固定国际键）。 */
    private static void applyLocalized(Message m) {
        m.contentKey("block.silicon-message-test.localizedDemo");
        m.titleKey(null); // 标题仍显示原文
    }

    /** 滑块显示格式：0 显示“常驻”，其余带 1 位小数秒。 */
    private static String formatLife(float v) {
        if (v <= 0.001f) return Core.bundle.get("block.silicon-message-test.lifePermanent");
        return Core.bundle.format("block.silicon-message-test.lifeSeconds",
            String.valueOf(Math.round(v * 10f) / 10f));
    }

    /**
     * HEX 颜色是否合法：允许可选的 {@code #} 前缀 + 6 位（RRGGBB）或 8 位（RRGGBBAA）。
     * {@link arc.graphics.Color#valueOf} 可直接解析该格式。
     */
    private static boolean isValidHex(String s) {
        if (s == null) return false;
        int o = !s.isEmpty() && s.charAt(0) == '#' ? 1 : 0;
        int n = s.length() - o;
        return n == 6 || n == 8;
    }

    /** 把 given Color 染成一块纯色 swatch（程序绘制，不依赖贴图）。 */
    private static Drawable swatch(Color c) {
        return ((arc.scene.style.TextureRegionDrawable) Tex.whiteui).tint(c);
    }

    public class MessageTestBuild extends Building {
        /** 标题输入内容（调试用，无需持久化到存档/网络） */
        public String title = Core.bundle.get("block.silicon-message-test.defaultTitle");
        /** 内容输入内容（调试用，无需持久化到存档/网络） */
        public String content = Core.bundle.get("block.silicon-message-test.defaultContent");
        /** 当前所选模板序号：0 无（自定义） / 1 紧急 / 2 警告 / 3 提示 / 4 常规 */
        public int template = 0;
        /** 气泡颜色（由 HEX 文本框解析而来，随消息应用） */
        public Color color = new Color(1f, 0.82f, 0.22f, 0.55f);
        /** 气泡颜色 HEX 输入（RRGGBB 或 RRGGBBAA，可带 # 前缀） */
        public String colorHex = "ffd1388c";
        /** 当前 HEX 是否合法（合规则发送时应用为自定义气泡颜色）。 */
        public boolean hexValid = true;
        /** 消失时间（秒）；0 = 不消失 */
        public float life = 0f;
        /** 消息类型：瞬时（脉冲式）/ 持续型（消息源控制生命周期，实时同步）。 */
        public MessageType type = MessageType.TRANSIENT;
        /** 本地化键切换：用 {@link Core#bundle} 的本地化键（block.silicon-message-test.localizedDemo）代替原文内容。 */
        public boolean localized = false;
        /** 全局可见切换：开 = 全玩家可收；关 = 仅同队可收。 */
        public boolean global = false;
        /** 持续型消息的已投递引用；点「发送/取消」切换：再次点击即由消息源取消投递。 */
        private Message persistent;
        /** 非权威端等待服务器确认投递的来源锚点（-1 = 无未决请求）；服务器广播确认（OP_ADD）后由
         *  {@link #adoptOwnPersistent()} 认领为 {@link #persistent}，在此之前发送按钮显示「取消投递」防重复点击。 */
        private long pendingSenderKey = -1;
        /** 发送按钮容器（瞬时/持续型切换时重建）。 */
        private Table btnHost;

        @Override
        public void updateTile() {
            // 非权威端每帧轮询服务器确认的持续型消息镜像并认领为本块句柄
            adoptOwnPersistent();
        }

        @Override
        public void buildConfiguration(Table table) {
            table.top();

            Table inner = new Table();
            inner.background(Tex.pane);
            inner.margin(6f, 10f, 6f, 10f);
            table.add(inner).growX();

            // 标题输入
            inner.add(Core.bundle.get("block.silicon-message-test.titleLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(1f).row();
            TextField titleField = inner.field(title, text -> {
                title = text;
                // 持续型已投递：实时同步标题到面板
                if (persistent != null) pushOrEdit(() -> persistent.title = text);
            }).growX().height(34f).padBottom(5f).get();
            titleField.setMessageText(Core.bundle.get("block.silicon-message-test.titlePlaceholder"));

            // 内容输入
            inner.add(Core.bundle.get("block.silicon-message-test.contentLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(1f).row();
            TextField contentField = inner.field(content, text -> {
                content = text;
                // 持续型已投递：实时同步内容到面板
                if (persistent != null) pushOrEdit(() -> persistent.content = text);
            }).growX().height(34f).padBottom(5f).get();
            contentField.setMessageText(Core.bundle.get("block.silicon-message-test.contentPlaceholder"));

            // 消息类型选择（瞬时 / 持续）
            inner.add(Core.bundle.get("block.silicon-message-test.typeLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(2f).row();
            ButtonGroup<TextButton> types = new ButtonGroup<>();
            types.setMinCheckCount(1);
            types.setMaxCheckCount(1);
            String[] typeKeys = {
                Core.bundle.get("block.silicon-message-test.typeTransient"),
                Core.bundle.get("block.silicon-message-test.typePersistent")
            };
            inner.table(btns -> {
                for (int i = 0; i < typeKeys.length; i++) {
                    final MessageType t = i == 0 ? MessageType.TRANSIENT : MessageType.PERSISTENT;
                    TextButton b = new TextButton(typeKeys[i], Styles.flatTogglet);
                    types.add(b);
                    b.setChecked(type == t);
                    b.clicked(() -> {
                        type = t;
                        b.setChecked(true); // 单选组内互斥勾选
                        rebuildSendButton();
                    });
                    btns.add(b).growX().height(32f).pad(1f);
                    if ((i + 1) % 2 == 0) btns.row();
                }
            }).growX().padBottom(5f);

            // 本地化键切换：{title}/{content} 文本改为引用 bundle 键（演示消息源发送固定本地化内容）
            inner.add(Core.bundle.get("block.silicon-message-test.localizedLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(2f).row();
            inner.table(btns -> {
                String[] locKeys = {
                    Core.bundle.get("block.silicon-message-test.localizedOn"),
                    Core.bundle.get("block.silicon-message-test.localizedOff")
                };
                for (int i = 0; i < locKeys.length; i++) {
                    final boolean on = i == 0;
                    TextButton b = new TextButton(locKeys[i], Styles.flatTogglet);
                    b.setChecked(localized == on);
                    b.clicked(() -> {
                        localized = on;
                        // 持续型已投递：实时切换本地化键引用（开→挂键；关→回退原文）
                        if (persistent != null) pushOrEdit(() ->
                            persistent.contentKey(on ? "block.silicon-message-test.localizedDemo" : null));
                        rebuildSendButton();
                    });
                    btns.add(b).growX().height(32f).pad(1f);
                    if ((i + 1) % 2 == 0) btns.row();
                }
            }).growX().padBottom(5f);

            // 全局可见切换：开 = 全玩家可收（含敌方）；关 = 仅同队可收
            inner.add(Core.bundle.get("block.silicon-message-test.globalLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(2f).row();
            inner.table(btns -> {
                String[] globalKeys = {
                    Core.bundle.get("block.silicon-message-test.globalOn"),
                    Core.bundle.get("block.silicon-message-test.globalOff")
                };
                for (int i = 0; i < globalKeys.length; i++) {
                    final boolean on = i == 0;
                    TextButton b = new TextButton(globalKeys[i], Styles.flatTogglet);
                    b.setChecked(global == on);
                    b.clicked(() -> {
                        global = on;
                        // 持续型已投递：实时切换全局可见
                        if (persistent != null) pushOrEdit(() -> persistent.global = on);
                        rebuildSendButton();
                    });
                    btns.add(b).growX().height(32f).pad(1f);
                    if ((i + 1) % 2 == 0) btns.row();
                }
            }).growX().padBottom(5f);

            // 模板选择（四选一）
            inner.add(Core.bundle.get("block.silicon-message-test.templateLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(2f).row();
            ButtonGroup<TextButton> templates = new ButtonGroup<>();
            templates.setMinCheckCount(0); // 允许全不选，但对单选不强求
            templates.setMaxCheckCount(1);
            String[] keys = {
                Core.bundle.get("block.silicon-message-test.templateNone"),    // 0 无
                Core.bundle.get("block.silicon-message-test.templateEmergency"), // 1 紧急
                Core.bundle.get("block.silicon-message-test.templateWarning"),   // 2 警告
                Core.bundle.get("block.silicon-message-test.templateInfo"),      // 3 提示
                Core.bundle.get("block.silicon-message-test.templateNormal")     // 4 常规
            };
            inner.table(btns -> {
                for (int i = 0; i < keys.length; i++) {
                    final int idx = i;
                    TextButton b = new TextButton(keys[i], Styles.flatTogglet);
                    templates.add(b);
                    b.setChecked(idx == template);
                    b.clicked(() -> {
                        template = idx;
                        // 持续型已投递：实时同步模板样式（颜色/优先级/图标）
                        if (persistent != null) pushOrEdit(() -> { if (idx > 0) applyTemplate(persistent, idx); });
                    });
                    btns.add(b).growX().height(32f).pad(1f);
                    if ((i + 1) % 2 == 0) btns.row();
                }
            }).growX().padBottom(5f);

            // 气泡颜色输入（HEX：RRGGBB 或 RRGGBBAA，可带 # 前缀）+ 实时预览色块
            inner.add(Core.bundle.get("block.silicon-message-test.bubbleLabel"))
                .color(Pal.accent).left().padLeft(2f).padBottom(1f).padRight(6f);
            inner.imageDraw(() -> swatch(color)).size(24f).padRight(4f);
            TextField colorField = inner.field(colorHex, text -> {
                colorHex = text;
                hexValid = isValidHex(text);
                if (hexValid) {
                    color = Color.valueOf(text); // 合法才更新颜色（非法保持原色，避免报错）
                    // 持续型已投递：实时同步气泡颜色到面板
                    if (persistent != null) pushOrEdit(() -> applyColor(persistent));
                }
            }).growX().height(34f).padBottom(5f).get();
            colorField.setMessageText(Core.bundle.get("block.silicon-message-test.hexPlaceholder"));
            inner.row();

            // 发送/取消按钮（瞬时：单次发送；持续：切换式「发送并持续更新/取消投递」）
            this.btnHost = new Table();
            inner.add(this.btnHost).growX().padBottom(8f).row();

            // 消失时间滑块（底部；瞬时型专用）
            inner.add(Core.bundle.get("block.silicon-message-test.lifeLabel"))
                .color(Color.lightGray).left().padRight(6f);
            Label lifeValue = inner.add("").color(Color.cyan).left().get();
            lifeValue.setText(formatLife(life));
            inner.row();
            inner.slider(0f, MAX_LIFE, 0.5f, life, value -> {
                life = value;
                lifeValue.setText(formatLife(value));
            }).growX().padTop(2f).get();

            this.rebuildSendButton();
        }

        /** 首次打开/切换类型后重建发送按钮：瞬时 = 单次发送；持续型 = 切换式「发送并持续更新/取消投递」。 */
        private void rebuildSendButton() {
            if (this.btnHost == null) return;
            this.btnHost.clearChildren();
            if (this.type == MessageType.TRANSIENT) {
                this.btnHost.button(Core.bundle.get("block.silicon-message-test.send"),
                    mindustry.gen.Icon.edit, () -> post(template, title, content, color, hexValid, life, localized, team, global))
                    .growX().height(40f);
            } else {
                // 已投递（含等待服务器确认中）→ 显示「取消投递」，防止确认到达前重复点击重复投递
                boolean active = this.persistent != null || this.pendingSenderKey >= 0;
                String key = active ? "block.silicon-message-test.cancel" : "block.silicon-message-test.sendPersistent";
                TextButton btn = new TextButton(Core.bundle.get(key), Styles.flatTogglet);
                btn.setChecked(active);
                btn.clicked(() -> {
                    if (this.persistent == null && this.pendingSenderKey < 0) {
                        startPersistent();
                    } else {
                        cancelPersistent();
                    }
                    this.rebuildSendButton();
                });
                this.btnHost.add(btn).growX().height(40f);
            }
        }

        /** 生成「持续型」消息当前期望的完整状态（样式/模板/本地化/队伍/全局/演示变量）。
         *  权威分支（本进程直接登记）与客户端请求（{@link MessageSync#requestAdd}/{@link MessageSync#requestUpdate}）
         *  共用，保证两端构建出的可展示内容一致。 */
        private Message buildPersistentState() {
            Message m = make(template, title, content);
            if (template == 0 && hexValid) m.background(color); // 仅「无模板」时应用自定义颜色
            if (localized) applyLocalized(m);
            m.team(team).global(global);
            m.type(MessageType.PERSISTENT).life(-1f);
            // 演示变量引用：{0}=已显示秒数（实时变化），{1}=每秒帧数（实时变化）；文本中用 {0}/{1} 即可引用。
            // 变量仅客户端本地实时渲染；非权威端传给服务器的是某一时刻的快照文本（面板显示该快照）。
            final float sentAt = Time.time;
            final int[] frames = {0};
            m.var(() -> formatLife(Time.time - sentAt)).var(() -> String.valueOf(frames[0]++));
            return m;
        }

        /**
         * 投递持续型消息。
         * <ul>
         *   <li><b>权威分支</b>（服务器/单机）：本进程登记，挂上与本块绑定的握手
         *       （{@link MessageSystem.Handshake}）——方块拆除/移除（isAdded 变 false）或
         *       点「取消投递」断开握手时，消息系统探测到该持续型消息失联，自动清除它（面板播放消失动画）。</li>
         *   <li><b>非权威分支</b>（纯客户端）：把含来源锚点（{@link Message#senderKey}）的请求交给服务器登记；
         *       服务器广播确认（OP_ADD）后由 {@link #adoptOwnPersistent()} 认领为已投递句柄；方块被拆时由
         *       服务器来源清扫兜底撤销。</li>
         * </ul>
         */
        private void startPersistent() {
            Message m = buildPersistentState();
            if (MessageSystem.isAuthoritative()) {
                m.handshake(new MessageSystem.Handshake(() -> isAdded()));
                this.persistent = MessageSystem.instance.post(m);
            } else {
                m.senderKey = ownKey();
                this.pendingSenderKey = m.senderKey;
                MessageSync.requestAdd(m);
            }
        }

        /** 撤销已投递的持续型消息：权威分支断开握手（系统下一帧扫描失联清除）；非权威分支请求服务器移除。
         *  尚未收到服务器确认的未决请求也一并放弃（服务器若已受理，会经 {@code requestRemove} 或来源清扫撤销）。 */
        private void cancelPersistent() {
            if (this.persistent != null) {
                if (MessageSystem.isAuthoritative()) {
                    this.persistent.handshake.disconnect();
                } else {
                    MessageSync.requestRemove(this.persistent.uid);
                }
                this.persistent = null;
            }
            this.pendingSenderKey = -1;
        }

        /** 非权威端每帧轮询：服务器广播确认的持续型消息镜像（senderKey == 本块坐标）认领为已投递句柄。 */
        private void adoptOwnPersistent() {
            if (this.pendingSenderKey < 0 || this.persistent != null) return;
            for (Message m : MessageSystem.instance.all()) {
                if (m.type == MessageType.PERSISTENT && m.senderKey == this.pendingSenderKey) {
                    this.persistent = m;
                    this.pendingSenderKey = -1;
                    this.rebuildSendButton();
                    return;
                }
            }
        }

        /** 本块在存档网格上的坐标编码（与服务器来源清扫 {@code ((x<<32)|y)} 一致）。 */
        private long ownKey() {
            return ((long) tileX() << 32) | (tileY() & 0xffffffffL);
        }

        /** 对已投递持续型消息做一次修改：权威进程直接改（本地模型即服务器权威，由联网层节流广播）；
         *  非权威进程还会把「当前完整期望状态」请求给服务器，由服务器替换权威记录并立即广播刷新快照。 */
        private void pushOrEdit(Runnable edit) {
            if (this.persistent == null) return;
            edit.run();
            if (MessageSystem.isAuthoritative()) return;
            MessageSync.requestUpdate(this.persistent.uid, buildPersistentState());
        }

        /** 把模板样式（颜色/优先级/图标）实时应用到一个消息上（含持续型已投递消息）。 */
        private void applyTemplate(Message m, int templateIdx) {
            Message t = make(templateIdx, m.title, m.currentContent());
            m.priority(t.priority).icon(t.icon);
            m.background(t.bubbleColor);
        }

        /** 把当前自定义气泡颜色实时应用到一个消息上（持续型已投递消息也同步）。 */
        private void applyColor(Message m) {
            m.background(color);
        }
    }
}