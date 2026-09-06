package silicon.world.blocks.sandbox;

import arc.Core;
import arc.func.Cons;
import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Collapser;
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
import silicon.util.MessageSystem.Priority;

/**
 * “消息测试”调试方块：用于向 {@link MessageSystem} 手动投递一条测试消息，方便调试消息面板。
 * <p>
 * 配置界面（点选方块后弹出，表单式排版——每行左侧标签、右侧控件）：
 * <ul>
 *   <li>类型 / 接收范围 / 本地化键演示：双选项组，用切换按钮（互斥单选）。</li>
 *   <li>优先级 / 模板选择：多选项组，「方案1」下拉展开——右侧按钮显示当前值，
 *       点击展开选项（Collapser），选中即收起。</li>
 *   <li>标题 / 内容：两个文本输入框。</li>
 *   <li>气泡颜色：HEX 文本框 + 实时色块预览；选择非「无」模板时<b>禁用</b>
 *       （颜色由模板提供）；消失提示覆盖层会自动采用气泡颜色。</li>
 *   <li>消失时间：滑块，范围 0~10 秒；0 秒表示不消失（常驻）。持续型消息<b>禁用</b>。</li>
 *   <li>类型选持续型时发送按钮变为切换式「发送并持续更新 / 取消投递」，
 *       已投递的内容/模板/气泡颜色/优先级修改会在消息面板中<b>实时同步</b>。</li>
 *   <li>发送一条消息：面板底部主操作按钮，按下即发布到消息系统。</li>
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
     *   <li>选择了模板（1~4）→ 使用模板颜色与图标，HEX 颜色<b>不应用</b>（避免冲突）。</li>
     *   <li>选择了「无」（0）→ 使用 HEX 自定义颜色（默认回退灰白气泡）。</li>
     * </ul>
     * 优先级由配置面板「优先级」行手动控制，独立于模板（始终覆盖模板默认优先级）。
     * 无论走哪条，气泡底色都会作为消失时间提示覆盖层的颜色。
     */
    private static void post(int templateIdx, String title, String content, Color bubble, boolean hexValid, float life, boolean localized, Team team, boolean global, Priority priority) {
        Message m = make(templateIdx, title, content);
        if (templateIdx == 0 && hexValid) m.background(bubble); // 仅「无模板」时应用自定义颜色
        if (localized) applyLocalized(m);
        m.team(team).global(global).priority(priority); // 优先级手动控制（覆盖模板默认）
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
        /** 优先级（手动控制，覆盖模板默认）：决定消息在面板中的分组排序。 */
        public Priority priority = Priority.LOW;
        /** 持续型消息的已投递引用；点「发送/取消」切换：再次点击即由消息源取消投递。 */
        private Message persistent;
        /** 非权威端等待服务器确认投递的来源锚点（-1 = 无未决请求）；服务器广播确认（OP_ADD）后由
         *  {@link #adoptOwnPersistent()} 认领为 {@link #persistent}，在此之前发送按钮显示「取消投递」防重复点击。 */
        private long pendingSenderKey = -1;
        /** 发送按钮容器（瞬时/持续型切换时重建）。 */
        private Table btnHost;
        /** 发送按钮样式缓存（懒加载，见 {@link #sendStyle()}）。 */
        private arc.scene.ui.TextButton.TextButtonStyle sendStyleCache;
        /** 气泡颜色输入框引用（选择模板时禁用）。 */
        private TextField colorInput;
        /** 消失时间滑块引用（持续型消息禁用）。 */
        private Slider lifeInput;

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

            // —— 类型（双选 → 切换按钮） ——
            toggleRow(inner,
                Core.bundle.get("block.silicon-message-test.typeLabel"),
                new String[]{
                    Core.bundle.get("block.silicon-message-test.typeTransient"),
                    Core.bundle.get("block.silicon-message-test.typePersistent")
                },
                new boolean[]{type == MessageType.TRANSIENT, type == MessageType.PERSISTENT},
                new Runnable[]{
                    () -> { type = MessageType.TRANSIENT; updateLifeDisabled(); rebuildSendButton(); },
                    () -> { type = MessageType.PERSISTENT; updateLifeDisabled(); rebuildSendButton(); }
                }
            );

            // —— 标题 ——
            formField(inner,
                Core.bundle.get("block.silicon-message-test.titleLabel"),
                title, "block.silicon-message-test.titlePlaceholder", text -> {
                    title = text;
                    // 持续型已投递：实时同步标题到面板
                    if (persistent != null) pushOrEdit(() -> persistent.title = text);
                });

            // —— 内容 ——
            formField(inner,
                Core.bundle.get("block.silicon-message-test.contentLabel"),
                content, "block.silicon-message-test.contentPlaceholder", text -> {
                    content = text;
                    // 持续型已投递：实时同步内容到面板
                    if (persistent != null) pushOrEdit(() -> persistent.content = text);
                });

            // —— 气泡颜色（选择模板时禁用）+ 实时预览色块 ——
            formRow(inner, Core.bundle.get("block.silicon-message-test.bubbleLabel"), row -> {
                row.imageDraw(() -> swatch(color)).size(22f).padRight(6f);
                colorInput = row.field(colorHex, text -> {
                    colorHex = text;
                    hexValid = isValidHex(text);
                    if (hexValid) {
                        color = Color.valueOf(text); // 合法才更新颜色（非法保持原色，避免报错）
                        // 持续型已投递：实时同步气泡颜色到面板
                        if (persistent != null) pushOrEdit(() -> applyColor(persistent));
                    }
                }).growX().height(34f).get();
            });
            colorInput.setMessageText(Core.bundle.get("block.silicon-message-test.hexPlaceholder"));
            colorInput.setDisabled(template != 0); // 选择了模板：颜色由模板提供，禁用自定义输入

            // —— 优先级（多选 → 方案1 下拉展开） ——
            multiSelectRow(inner,
                Core.bundle.get("block.silicon-message-test.priorityLabel"),
                new String[]{
                    Core.bundle.get("block.silicon-message-test.priorityHigh"),
                    Core.bundle.get("block.silicon-message-test.priorityMedium"),
                    Core.bundle.get("block.silicon-message-test.priorityLow")
                },
                new Priority[]{Priority.HIGH, Priority.MEDIUM, Priority.LOW},
                () -> priority,
                p -> {
                    priority = p;
                    // 持续型已投递：实时同步优先级到面板
                    if (persistent != null) pushOrEdit(() -> persistent.priority = p);
                }
            );

            // —— 模板选择（多选 → 方案1 下拉展开） ——
            multiSelectRow(inner,
                Core.bundle.get("block.silicon-message-test.templateLabel"),
                new String[]{
                    Core.bundle.get("block.silicon-message-test.templateNone"),     // 0 无
                    Core.bundle.get("block.silicon-message-test.templateEmergency"), // 1 紧急
                    Core.bundle.get("block.silicon-message-test.templateWarning"),   // 2 警告
                    Core.bundle.get("block.silicon-message-test.templateInfo"),      // 3 提示
                    Core.bundle.get("block.silicon-message-test.templateNormal")     // 4 常规
                },
                new Integer[]{0, 1, 2, 3, 4},
                () -> template,
                idx -> {
                    template = idx;
                    colorInput.setDisabled(idx != 0); // 回到「无」模板时恢复自定义颜色输入
                    if (persistent != null) pushOrEdit(() -> applyTemplate(persistent, idx));
                }
            );

            // —— 接收范围（双选 → 切换按钮） ——
            toggleRow(inner,
                Core.bundle.get("block.silicon-message-test.globalLabel"),
                new String[]{
                    Core.bundle.get("block.silicon-message-test.globalOn"),
                    Core.bundle.get("block.silicon-message-test.globalOff")
                },
                new boolean[]{global, !global},
                new Runnable[]{
                    () -> { global = true; if (persistent != null) pushOrEdit(() -> persistent.global = true); },
                    () -> { global = false; if (persistent != null) pushOrEdit(() -> persistent.global = false); }
                }
            );

            // —— 本地化键演示（双选 → 切换按钮） ——
            toggleRow(inner,
                Core.bundle.get("block.silicon-message-test.localizedLabel"),
                new String[]{
                    Core.bundle.get("block.silicon-message-test.localizedOn"),
                    Core.bundle.get("block.silicon-message-test.localizedOff")
                },
                new boolean[]{localized, !localized},
                new Runnable[]{
                    () -> {
                        localized = true;
                        if (persistent != null) pushOrEdit(() -> persistent.contentKey("block.silicon-message-test.localizedDemo"));
                    },
                    () -> {
                        localized = false;
                        if (persistent != null) pushOrEdit(() -> persistent.contentKey(null));
                    }
                }
            );

            // —— 消失时间（滑块；持续型消息禁用） ——
            Label lifeValue = new Label(formatLife(life), Styles.defaultLabel);
            lifeValue.setColor(Color.cyan);
            formRow(inner, Core.bundle.get("block.silicon-message-test.lifeLabel"), row -> {
                lifeInput = row.slider(0f, MAX_LIFE, 0.5f, life, value -> {
                    life = value;
                    lifeValue.setText(formatLife(value));
                }).growX().height(30f).get();
                row.add(lifeValue).color(Color.cyan).right().width(64f).padLeft(6f);
            });
            updateLifeDisabled();

            // —— 发送/取消按钮（面板底部主操作；瞬时：单次发送；持续：切换式「发送并持续更新/取消投递」） ——
            this.btnHost = new Table();
            inner.add(this.btnHost).growX().padTop(4f).row();

            this.rebuildSendButton();
        }

        /** 当前类型是否为持续型（决定消失时间滑块是否禁用）。 */
        private void updateLifeDisabled() {
            if (lifeInput != null) lifeInput.setDisabled(type == MessageType.PERSISTENT);
        }

        /** 新增一行「左侧标签列 + 右侧控件列」的表单行：控件由调用方 add 进 row（通常 growX 占满右侧）。 */
        private void formRow(Table host, String labelKey, Cons<Table> row) {
            Table form = new Table();
            form.left();
            form.add(labelKey).color(Pal.accent).left().minWidth(100f).padRight(6f);
            row.get(form);
            host.add(form).growX().padBottom(6f);
            host.row();
        }

        /** 常规文本输入行：标签 + 输入框（growX 自适应宽），并设置占位提示文本。 */
        private void formField(Table host, String labelKey, String init, String placeholderKey, Cons<String> onChanged) {
            final TextField[] ref = new TextField[1];
            formRow(host, labelKey, row -> {
                ref[0] = row.field(init, onChanged).growX().height(34f).get();
            });
            ref[0].setMessageText(Core.bundle.get(placeholderKey));
        }

        /** 双选项行（类型/接收范围/本地化键）：右侧为互斥切换按钮组（至少一个保持选中）。 */
        private void toggleRow(Table host, String labelKey, String[] options, boolean[] checks, Runnable[] actions) {
            formRow(host, labelKey, row -> {
                ButtonGroup<TextButton> group = new ButtonGroup<>();
                group.setMinCheckCount(1);
                group.setMaxCheckCount(1);
                for (int i = 0; i < options.length; i++) {
                    TextButton b = new TextButton(options[i], Styles.flatTogglet);
                    group.add(b);
                    b.setChecked(checks[i]);
                    b.clicked(actions[i]);
                    row.add(b).growX().height(30f).pad(1f);
                }
            });
        }

        /** 「方案1」多选行（优先级/模板选择）：右侧为「当前值 ▼」按钮，点击展开选项（Collapser），选中即收起、按钮文字更新为所选。
         *  Collapser 用 Boolp 驱动 + 无动画切换（Mindustry 标准用法），收起/展开瞬间在 0 与完整高度间跳变，避免动画部分高度导致的只渲染一半/错位。 */
        private <T> void multiSelectRow(Table host, String labelKey, String[] optionTexts, T[] values, Prov<T> current, Cons<T> onPick) {
            final boolean[] open = {false};

            Table options = new Table();
            options.left();

            TextButton field = new TextButton("", Styles.flatBordert);
            Runnable refreshField = () -> {
                String text = "";
                for (int i = 0; i < values.length; i++) {
                    if (values[i].equals(current.get())) text = optionTexts[i];
                }
                field.setText(text + (open[0] ? " ▲" : " ▼"));
            };
            field.clicked(() -> {
                open[0] = !open[0];
                refreshField.run();
            });
            refreshField.run();

            ButtonGroup<TextButton> group = new ButtonGroup<>();
            group.setMinCheckCount(1);
            group.setMaxCheckCount(1);
            for (int i = 0; i < optionTexts.length; i++) {
                final String text = optionTexts[i];
                final T value = values[i];
                TextButton b = new TextButton(text, Styles.flatTogglet);
                group.add(b);
                b.setChecked(value.equals(current.get()));
                b.clicked(() -> {
                    onPick.get(value);
                    open[0] = false;
                    field.setText(text + " ▼");
                });
                options.add(b).growX().height(28f).pad(1f);
                if ((i + 1) % 3 == 0) options.row();
            }

            // Boolp 驱动 + 无动画：Collapser 每帧检测 open，收起=0 高度、展开=完整高度，瞬时切换（无中间动画帧）
            Collapser collapser = new Collapser(options, true);
            collapser.setCollapsed(() -> !open[0]);
            collapser.setEnforceMinSize(true);

            formRow(host, labelKey, row -> row.add(field).growX().height(30f));
            host.add(collapser).growX().padBottom(6f);
            host.row();
        }

        /** 发送按钮样式：从 flatTogglet 派生，文字用主题色，作为面板底部的主操作按钮。
         *  懒加载——不能在类初始化时创建（加载方块阶段 UI 样式尚未构建，Styles.flatTogglet 仍为 null）。 */
        private arc.scene.ui.TextButton.TextButtonStyle sendStyle() {
            if (sendStyleCache == null) {
                sendStyleCache = new arc.scene.ui.TextButton.TextButtonStyle(Styles.flatTogglet);
                sendStyleCache.fontColor = Pal.accent;
                sendStyleCache.overFontColor = Color.white;
                sendStyleCache.checkedFontColor = Pal.accent;
            }
            return sendStyleCache;
        }

        /** 首次打开/切换类型后重建发送按钮：瞬时 = 单次发送；持续型 = 切换式「发送并持续更新/取消投递」。 */
        private void rebuildSendButton() {
            if (this.btnHost == null) return;
            this.btnHost.clearChildren();
            if (this.type == MessageType.TRANSIENT) {
                // 瞬时：高亮主操作按钮，点击即发送一次
                this.btnHost.button(Core.bundle.get("block.silicon-message-test.send"),
                    mindustry.gen.Icon.edit, sendStyle(), () -> post(template, title, content, color, hexValid, life, localized, team, global, priority))
                    .growX().height(40f);
            } else {
                // 已投递（含等待服务器确认中）→ 显示「取消投递」，防止确认到达前重复点击重复投递
                boolean active = this.persistent != null || this.pendingSenderKey >= 0;
                String key = active ? "block.silicon-message-test.cancel" : "block.silicon-message-test.sendPersistent";
                TextButton btn = new TextButton(Core.bundle.get(key), active ? sendStyle() : Styles.flatTogglet);
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
            m.team(team).global(global).priority(priority); // 优先级手动控制（覆盖模板默认）
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

        /** 把模板样式（颜色/图标）实时应用到一个消息上（含持续型已投递消息）；优先级由「优先级」行手动控制。 */
        private void applyTemplate(Message m, int templateIdx) {
            Message t = make(templateIdx, m.title, m.currentContent());
            m.icon(t.icon);
            m.background(t.bubbleColor);
        }

        /** 把当前自定义气泡颜色实时应用到一个消息上（持续型已投递消息也同步）。 */
        private void applyColor(Message m) {
            m.background(color);
        }
    }
}