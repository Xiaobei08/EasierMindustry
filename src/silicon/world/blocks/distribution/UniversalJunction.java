package silicon.world.blocks.distribution;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Geometry;
import arc.math.Mathf;
import static mindustry.Vars.tilesize;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.WidgetGroup;
import arc.scene.style.TextureRegionDrawable;
import arc.util.Align;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.BufferItem;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.gen.Teamc;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.DirectionalItemBuffer;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.ui.UniversalJunctionDialog;

import static mindustry.Vars.content;
import static mindustry.Vars.ui;

/**
 * 万向交叉器 (Universal Junction)
 * <p>
 * 1x1 物品交叉器：可为四个输入方向分别配置各输出方向的优先级 (0~4)，
 * 数值越大越优先输出，0 表示不输出；同优先级的方向轮流输出实现均分。
 * 方向满载时短暂等待重试，连续堵塞才降级到次高优先级，且降级后持续生效、
 * 恢复时自动切回。传输速率 50 物品/秒。
 * <p>
 * 方向约定：UI 中 0=上(北) 1=右(东) 2=下(南) 3=左(西)。
 * 注意游戏内 relativeTo()/Building.nearby() 使用角度编码 (0=东 1=北 2=西 3=南)，
 * 因此在收/发物品时分别做 3-rel 与 out^1 转换。
 * <p>
 * 交互：点击方块弹出"配置方向"按钮，点击按钮打开配置界面，
 * 选择输入方向后为四个输出方向分别拖动优先级滑块。
 */
public class UniversalJunction extends Block {
    /** 移动一个物品所需的 tick 数：60 / 1.2 = 50 物品/秒 */
    public float moveTime = 1.2f;
    /** 每个方向的缓冲容量 */
    public int capacity = 16;

    /** 方向图标（标准方位：0=上 1=右 2=下 3=左） */
    public static final TextureRegionDrawable[] dirIcons = {Icon.up, Icon.right, Icon.down, Icon.left};

    /** 方向名称缓存（启动时从 bundle 预加载，避免每次调用都查 bundle） */
    private static String[] dirNames;

    /** 预加载方向名称到缓存（ClientLoadEvent 后调用） */
    public static void initDirNames() {
        dirNames = new String[4];
        for (int i = 0; i < 4; i++) dirNames[i] = Core.bundle.get("universal-junction.dir" + i);
    }

    /** 方向名称（从缓存读取；缓存未初始化时回退到 bundle 直查） */
    public static String dirName(int dir) {
        if (dirNames == null) initDirNames();
        return dirNames[dir];
    }

    /** 角度编码（0=东 1=北 2=西 3=南，relativeTo 返回值）→ 标准方位（物品来源方向） */
    static int angleToSource(int angle) {
        return 3 - angle;
    }

    /** 标准方位 → 角度编码（Building.nearby 参数） */
    static int cardinalToAngle(int dir) {
        return dir ^ 1;
    }

    public UniversalJunction(String name) {
        super(name);
        update = true;
        solid = false;
        underBullets = true;
        group = BlockGroup.transportation;
        unloadable = false;
        floating = true;
        noUpdateDisabled = true;
        hasItems = false;
        size = 1;
        timers = 1;
        configurable = true;
        saveConfig = true;
        copyConfig = true;
        drawArrow = false;

        // 优先级通过 String 配置值同步（20 个逗号分隔的整数，16 矩阵 + 4 全局默认行）
        config(String.class, (UniversalJunctionBuild b, String str) -> b.applyConfig(str));
    }

    @Override
    public boolean outputsItems() {
        return true;
    }

    @Override
    public void load() {
        super.load();
        // assets/sprites/blocks/distribution/universal-junction.png（四向箭头 + 中心青色智能中枢）
        region = Core.atlas.find(name);
        generatedIcons = new TextureRegion[]{region};
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.itemsMoved, 60f / moveTime, StatUnit.itemsSecond);
    }

    public class UniversalJunctionBuild extends Building {
        /** 优先级矩阵 [输入方向][输出方向]，0~4；0 = 不输出 */
        public final int[][] weights = new int[4][4];
        /** 全局默认输出优先级（应用到所有未单独覆盖的输入方向）；通过配置 String 尾部 4 值同步 */
        public int[] defaultRow = {2, 2, 2, 2};
        /** 四方向物品缓冲（下标为标准方位：0=上 1=右 2=下 3=左） */
        public final DirectionalItemBuffer buffer = new DirectionalItemBuffer(capacity);
        /** 各输入方向的轮询指针（同优先级方向轮流输出，实现均分） */
        public final int[] roundRobin = new int[4];
        /** 各输入方向的连续满载计数（用于延迟降级，避免高吞吐下传送带抖动） */
        public final int[] blockCount = new int[4];
        /** 各输入方向当前生效的最高优先级（降级时降低，探测到更高组恢复时回升） */
        public final int[] activePriority = new int[4];
        /** 连续满载多少 tick 后才降级到次高优先级 */
        public static final int BLOCK_THRESHOLD = 10;


        // 插入点 UI 状态
        Table currentInsertBox = null;
        int currentInsertPosition = -1;
        /** 输入方向轮询指针（各输入方向轮流服务，避免高压方向饿死其他方向） */
        public int inputRobin;
        /** 配置节流间隔（秒）：拖动滑块期间合并多次改动为一次网络发送 */
        public float configInterval = 0.25f;
        /** 上次发送配置的时间 */
        public float lastConfigTime;
        /** 是否有待发送的配置改动 */
        public boolean configDirty;

        private static final int timerMove = 0;

        /** 默认所有方向优先级 2 */
        {
            setAll(2);
        }



        @Override
        public boolean acceptItem(Building source, Item item) {
            int rel = source.relativeTo(tile);
            if (rel == -1) return false;
            int srcDir = angleToSource(rel);
            // 该输入方向的所有输出优先级均为 0（完全禁用）：拒绝接收，物品留在来源处
            for (int d = 0; d < 4; d++) {
                if (weights[srcDir][d] > 0) return buffer.accepts(srcDir);
            }
            return false;
        }

        @Override
        public int acceptStack(Item item, int amount, Teamc source) {
            return 0; // 不接收整叠物品，与原版 Junction 一致
        }

        @Override
        public void handleItem(Building source, Item item) {
            int rel = source.relativeTo(tile);
            if (rel != -1) {
                buffer.accept(angleToSource(rel), item);
            }
        }

        @Override
        public void updateTile() {
            if (timer(timerMove, moveTime)) {
                moveItem();
            }
            // 兜底补发：滑块松手后若还有未发送的配置改动，在窗口结束后发送
            if (configDirty && Time.time >= lastConfigTime + configInterval) {
                flushConfig();
            }
        }

        /** 每 moveTime 从输入方向轮流服务一个物品 */
        void moveItem() {
            int input = pickInput();
            if (input == -1) return;

            // 防止缓冲区索引溢出：截断并丢弃多余数据，保留前 capacity 项
            if (buffer.indexes[input] > capacity) {
                System.arraycopy(buffer.buffers[input], 0, buffer.buffers[input], 0, capacity);
                buffer.indexes[input] = capacity;
            }

            long l = buffer.buffers[input][0];
            Item item = content.item(BufferItem.item(l));
            if (item == null) return;

            int out = pickOutput(input, item);
            if (out == -1) return; // 所有输出均阻塞，物品留在缓冲中等待

            Building dest = nearby(cardinalToAngle(out));
            // 防御：pickOutput 已校验 dest 非空，此处仅防未来逻辑变更导致的 NPE
            if (dest == null) return;
            dest.handleItem(this, item);
            System.arraycopy(buffer.buffers[input], 1, buffer.buffers[input], 0, buffer.indexes[input] - 1);
            buffer.indexes[input]--;
        }

        /** 选择要服务的输入方向：从轮询指针开始找第一个有物品的方向（轮流服务，公平分配） */
        int pickInput() {
            for (int n = 0; n < 4; n++) {
                int i = (inputRobin + n) % 4;
                if (buffer.indexes[i] > 0) {
                    inputRobin = (i + 1) % 4;
                    return i;
                }
            }
            return -1;
        }

        /**
         * 按优先级选择输出方向，规则：
         * - 优先尝试当前生效的最高优先级组，同组方向轮流输出实现均分；
         * - 组内方向不可用（无建筑/异队）立即降级到次高优先级；
         * - 组内方向存在但暂时满载（如传送带逐 tick 移动）先等待重试，
         *   连续满载超过阈值才降级，避免高吞吐下物品在最高与次高方向之间抖动；
         * - 降级是持久的（activePriority 记录），但每次调用会探测更高组是否恢复，
         *   恢复即可切回，防止永久堵塞时次高方向吞吐崩坏。
         */
        int pickOutput(int input, Item item) {
            int cfgBest = 0;
            for (int d = 0; d < 4; d++) cfgBest = Math.max(cfgBest, weights[input][d]);
            if (cfgBest <= 0) return -1; // 该输入方向未配置任何输出

            // 初始或全部降级归零时，重置为配置的最高优先级，重新走降级流程
            if (activePriority[input] <= 0) activePriority[input] = cfgBest;

            // 探测：更高优先级组是否已恢复可接收 → 逐级切回（循环直至最高可接收组）
            while (activePriority[input] < cfgBest) {
                int higher = nextHigher(input, activePriority[input]);
                if (higher <= 0 || !groupUsable(input, higher, item)) break;
                activePriority[input] = higher;
            }

            int p = activePriority[input];
            if (p <= 0) return -1; // 所有优先级组均不可用，等待下个 tick 重试

            boolean anyDest = false; // 当前组内是否存在有效目标（哪怕暂时满载）
            for (int n = 0; n < 4; n++) {
                int d = (roundRobin[input] + n) % 4;
                if (weights[input][d] != p) continue;
                Building dest = nearby(cardinalToAngle(d));
                if (dest == null || dest.team != team) continue; // 不可用 → 组内下一个
                anyDest = true;
                if (dest.acceptItem(this, item)) {
                    blockCount[input] = 0; // 发送成功，清零阻塞计数
                    roundRobin[input] = (d + 1) % 4; // 推进轮询指针，下次从下一方向开始
                    return d;
                }
            }

            if (!anyDest) {
                // 组内全不可用（无建筑/异队）：立即持久降级
                blockCount[input] = 0;
                activePriority[input] = nextLower(input, p);
                return -1;
            }

            // 组内方向存在但全部暂时满载：等待重试，连续阻塞超过阈值才持久降级
            blockCount[input]++;
            if (blockCount[input] < BLOCK_THRESHOLD) return -1;
            blockCount[input] = 0;
            activePriority[input] = nextLower(input, p);
            return -1;
        }

        /** 组内是否存在至少一个可接收该物品的方向 */
        boolean groupUsable(int input, int p, Item item) {
            for (int d = 0; d < 4; d++) {
                if (weights[input][d] != p) continue;
                Building dest = nearby(cardinalToAngle(d));
                if (dest != null && dest.team == team && dest.acceptItem(this, item)) return true;
            }
            return false;
        }

        /** 低于 p 的最高配置优先级；无则返回 0 */
        int nextLower(int input, int p) {
            int next = 0;
            for (int d = 0; d < 4; d++) {
                int w = weights[input][d];
                if (w < p && w > next) next = w;
            }
            return next;
        }

        /** 高于 p 的最低配置优先级；无则返回 0 */
        int nextHigher(int input, int p) {
            int next = 0;
            for (int d = 0; d < 4; d++) {
                int w = weights[input][d];
                if (w > p && (next == 0 || w < next)) next = w;
            }
            return next;
        }

        // ---------- 配置 ----------

        public void setAll(int v) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) weights[i][j] = v;
            }
        }

        /** 仅设置指定输入方向的 4 个输出优先级（快捷按钮作用域：当前选中的输入方向） */
        public void setAllFor(int in, int v) {
            for (int j = 0; j < 4; j++) weights[in][j] = v;
        }

        /** 该输入方向是否已单独配置（与全局默认行不同） */
        boolean isOverride(int in) {
            for (int j = 0; j < 4; j++) {
                if (weights[in][j] != defaultRow[j]) return true;
            }
            return false;
        }

        /** 恢复指定输入方向为全局默认（取消覆盖） */
        void resetToDefault(int in) {
            System.arraycopy(defaultRow, 0, weights[in], 0, 4);
        }

        /** 该输入方向是否所有输出优先级均为 0（完全禁用） */
        boolean isDisabled(int in) {
            for (int d = 0; d < 4; d++) {
                if (weights[in][d] > 0) return false;
            }
            return true;
        }

        // ---------- 组号系统（零冗余） ----------

        /**
         * 规范化组号：将 groups[4] 中的组号重新编号为连续的 0,1,2,...
         * groups[d]: 0=最高, 1, 2, 3=最低, -1=禁用
         */
        void canonicalizeGroups(int in, int[] groups) {
            int[] map = new int[5]; // 原组号 → 规范组号
            java.util.Arrays.fill(map, -1);
            int next = 0;
            for (int g = 0; g <= 3; g++) {
                boolean used = false;
                for (int d = 0; d < 4; d++) {
                    if (groups[d] == g) { used = true; break; }
                }
                if (used) map[g] = next++;
            }
            for (int d = 0; d < 4; d++) {
                groups[d] = groups[d] < 0 ? -1 : map[groups[d]];
            }
        }

        /** 组号 → weights：0→4, 1→3, 2→2, 3→1, -1→0 */
        void groupsToWeights(int in, int[] groups) {
            for (int d = 0; d < 4; d++) {
                weights[in][d] = groups[d] < 0 ? 0 : 4 - groups[d];
            }
        }

        /** weights → 组号（用于 UI 初始化） */
        int[] weightsToTiers(int in) {
            int[] tiers = new int[4];
            java.util.TreeSet<Integer> vals = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
            for (int d = 0; d < 4; d++) {
                if (weights[in][d] > 0) vals.add(weights[in][d]);
            }
            java.util.Map<Integer, Integer> valToTier = new java.util.HashMap<>();
            int tier = 0;
            for (int v : vals) valToTier.put(v, tier++);
            for (int d = 0; d < 4; d++) {
                tiers[d] = weights[in][d] > 0 ? valToTier.get(weights[in][d]) : -1;
            }
            return tiers;
        }

        // ---------- 同值折叠判定（数值相同组折叠为文字，滑块常驻占位、hover/tap 淡入淡出） ----------

        /** 与 d 同值的最小方向序（组代表；上=0 最小） */
        int repOf(int[] data, int d) {
            int v = data[d];
            int best = d;
            for (int j = 0; j < 4; j++) {
                if (j < best && data[j] == v) best = j;
            }
            return best;
        }

        /** 该方向的值在 4 个输出中是否唯一 */
        boolean isUnique(int[] data, int d) {
            int v = data[d];
            for (int j = 0; j < 4; j++) {
                if (j != d && data[j] == v) return false;
            }
            return true;
        }

        /** 该方向是否为同值组的代表（组内最小方向序，始终显示滑块） */
        boolean isRepresentative(int[] data, int d) {
            return repOf(data, d) == d;
        }

        /** 折叠文字：0 组显示"禁用"，非 0 重复组显示"与{代表}平均输出" */
        String foldText(int[] data, int d) {
            int v = data[d];
            if (v == 0) return Core.bundle.get("universal-junction.disabled");
            return Core.bundle.format("universal-junction.evenWith", dirName(repOf(data, d)));
        }

        /**
         * 渲染一行输出配置：唯一值/组代表常显滑块；
         * 重复值折叠为文字（覆盖层），滑块仍在原位（透明占位）——
         * hover 或点击时文字与滑块交叉淡入淡出，布局恒定不抖动。
         * <p>
         * 优化：通过 lastFoldText 跟踪折叠文字变化，per-frame lambda 自动更新
         * 折叠文字和 force/show 状态，onChanged 不再需要重建兄弟行。
         *
         * @param foldLabelOut  用于存储折叠文字 Label 引用的数组（按 out 索引），可为 null
         * @param valLabelOut   用于存储数值 Label 引用的数组（按 out 索引），可为 null
         */
        void renderRow(Table row, int[] data, int out, boolean[] tapOpen, boolean[] hoverOpen,
                       java.util.function.IntConsumer onChanged,
                       Label[] foldLabelOut, Label[] valLabelOut) {
            row.clearChildren();
            boolean force = isUnique(data, out) || isRepresentative(data, out); // 始终显示滑块
            boolean show = force || tapOpen[out] || hoverOpen[out];

            // 数值列（先创建引用，滑块拖动时更新文本；透明度随展开状态）
            Label val = new Label(String.valueOf(data[out]));
            val.setAlignment(Align.center);
            val.setColor(1f, 1f, 1f, show ? 1f : 0f);
            if (valLabelOut != null) valLabelOut[out] = val;

            // 三列布局：标签 / 重叠组（文字↔滑块交叉淡入淡出）/ 数值
            Label dirL = new Label(dirName(out) + " →");
            dirL.setAlignment(Align.right);
            dirL.setColor(Color.lightGray);
            dirL.clicked(() -> tapOpen[out] = !tapOpen[out]); // 点击标签固定/收起（仅状态标志）
            row.add(dirL).width(46f).height(40f).padRight(4f);

            // 重叠组：文字与滑块同起点（x=12），严格对齐
            WidgetGroup group = new WidgetGroup();
            group.setSize(0f, 40f);

            Label textL = new Label("▾ " + foldText(data, out));
            textL.setAlignment(Align.left);
            textL.setColor(1f, 1f, 1f, show ? 0f : 1f);
            textL.clicked(() -> tapOpen[out] = !tapOpen[out]); // tap 固定展开/收起
            if (foldLabelOut != null) foldLabelOut[out] = textL;

            Table sg = new Table();
            sg.marginLeft(12f);
            sg.marginRight(12f);
            Slider sl = new Slider(0f, 4f, 1f, false);
            sl.setValue(data[out]);
            sl.changed(() -> {
                int v = (int) sl.getValue();
                data[out] = v;
                val.setText(String.valueOf(v));
                onChanged.accept(v);
            });
            sg.add(sl).grow();
            sg.setColor(1f, 1f, 1f, show ? 1f : 0f);

            group.addChild(textL);
            group.addChild(sg);
            final int[] lastVal = {data[out]};
            final String[] lastFoldText = {textL.getText().toString()};
            group.update(() -> {
                // 每帧同步子元素 bounds 到组实际宽度（growX 自适应），并交叉淡入淡出
                float w = Math.max(group.getWidth(), 0f);
                textL.setBounds(12f, 0f, Math.max(w - 24f, 0f), 40f);
                sg.setBounds(0f, 0f, w, 40f);
                // 数据变化或组结构变化时更新折叠文字
                String newFold = "▾ " + foldText(data, out);
                if (data[out] != lastVal[0] || !newFold.equals(lastFoldText[0])) {
                    lastVal[0] = data[out];
                    lastFoldText[0] = newFold;
                    textL.setText(newFold);
                }
                // 实时重算 force（值变化可能使该行成为唯一值/组代表）
                boolean f = isUnique(data, out) || isRepresentative(data, out);
                boolean s = f || tapOpen[out] || hoverOpen[out];
                // 禁用方向（值=0）折叠文字用红色强调
                boolean disabled = data[out] == 0;
                float cr = disabled ? 0.85f : 1f;
                float cg = disabled ? 0.25f : 1f;
                float cb = disabled ? 0.25f : 1f;
                textL.setColor(cr, cg, cb, Mathf.lerp(textL.color.a, s ? 0f : 1f, 0.25f));
                sg.setColor(1f, 1f, 1f, Mathf.lerp(sg.color.a, s ? 1f : 0f, 0.25f));
                val.setColor(1f, 1f, 1f, Mathf.lerp(val.color.a, s ? 1f : 0f, 0.25f));
                textL.touchable = s ? Touchable.disabled : Touchable.enabled;
                sg.touchable = s ? Touchable.enabled : Touchable.disabled;
            });
            row.add(group).growX().height(40f);
            row.add(val).width(32f).height(40f);
        }

        /** 重载：不存储引用的版本（模板管理区等不需要增量更新的场景） */
        void renderRow(Table row, int[] data, int out, boolean[] tapOpen, boolean[] hoverOpen,
                       java.util.function.IntConsumer onChanged) {
            renderRow(row, data, out, tapOpen, hoverOpen, onChanged, null, null);
        }

        /**
         * 应用模板：恢复完整配置——16 个矩阵值（[输入][输出]）+ 4 个全局默认行。
         * 覆盖关系由数据自动决定（行 ≠ 全局行即覆盖）。
         */
        void applyTemplate(int[] tpl) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) weights[i][j] = tpl[i * 4 + j];
            }
            for (int j = 0; j < 4; j++) defaultRow[j] = tpl[16 + j];
        }

        /** 当前完整配置（16 矩阵 + 4 全局行），用于保存模板 */
        int[] currentTemplate() {
            int[] tpl = new int[20];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) tpl[i * 4 + j] = weights[i][j];
            }
            for (int j = 0; j < 4; j++) tpl[16 + j] = defaultRow[j];
            return tpl;
        }

        // ---------- 模板持久化（玩家全局偏好，存于游戏设置） ----------

        static final String TEMPLATES_KEY = "silicon-uj-templates";
        /** 内置模板：均分 / 全右 / 主右备左 / 上下直通（20 值 = 16 矩阵 + 4 全局行，方向顺序 上右下左） */
        static final String[] BUILTIN_TEMPLATE_KEYS = {"universal-junction.tpl.even", "universal-junction.tpl.east", "universal-junction.tpl.eastwest", "universal-junction.tpl.ns"};
        static final int[][] BUILTIN_TEMPLATE_ROWS = {
            {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2},
            {0, 4, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0},
            {0, 4, 0, 2, 0, 4, 0, 2, 0, 4, 0, 2, 0, 4, 0, 2, 0, 4, 0, 2},
            {4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0}
        };

        /** 读取自定义模板表（LinkedHashMap：名称 → 20 值完整配置；兼容旧版 4 值全局行格式） */
        static java.util.Map<String, int[]> loadTemplates() {
            java.util.Map<String, int[]> map = new java.util.LinkedHashMap<>();
            String raw = (String) Core.settings.get(TEMPLATES_KEY, "");
            if (raw.isEmpty()) return map;
            for (String part : raw.split(";")) {
                if (part.isEmpty()) continue;
                String[] kv = part.split(":", 2);
                if (kv.length != 2) continue;
                String[] vals = kv[1].split(",");
                try {
                    if (vals.length == 20) {
                        // 新格式：完整配置
                        int[] tpl = new int[20];
                        for (int i = 0; i < 20; i++) tpl[i] = Mathf.clamp(Integer.parseInt(vals[i].trim()), 0, 4);
                        map.put(kv[0], tpl);
                    } else if (vals.length == 4) {
                        // 旧格式：仅全局行 → 展开为所有输入方向相同
                        int[] row = new int[4];
                        for (int i = 0; i < 4; i++) row[i] = Mathf.clamp(Integer.parseInt(vals[i].trim()), 0, 4);
                        int[] tpl = new int[20];
                        for (int i = 0; i < 4; i++) {
                            for (int j = 0; j < 4; j++) tpl[i * 4 + j] = row[j];
                        }
                        for (int j = 0; j < 4; j++) tpl[16 + j] = row[j];
                        map.put(kv[0], tpl);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return map;
        }

        /** 保存自定义模板（20 值完整配置） */
        static void saveTemplate(String name, int[] tpl) {
            java.util.Map<String, int[]> map = loadTemplates();
            // 过滤非法字符（分隔符）
            String clean = name.replace(":", "").replace(";", "").replace(",", "").trim();
            if (clean.isEmpty()) return;
            map.put(clean, tpl.clone());
            saveTemplates(map);
        }

        /** 删除自定义模板 */
        static void deleteTemplate(String name) {
            java.util.Map<String, int[]> map = loadTemplates();
            if (map.remove(name) != null) {
                saveTemplates(map);
            }
        }

        /** 持久化自定义模板表（存于游戏设置，跨存档保留；每模板 20 值） */
        static void saveTemplates(java.util.Map<String, int[]> map) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, int[]> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append(':');
                for (int i = 0; i < 20; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(e.getValue()[i]);
                }
            }
            Core.settings.put(TEMPLATES_KEY, sb.toString());
        }

        /** 名称截断：超过 max 字符显示省略号（避免长模板名溢出面板） */
        static String clip(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max) + "…";
        }

        /** 模板下拉选项：内置名 + 自定义名 */
        static String[] templateNames() {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (String key : BUILTIN_TEMPLATE_KEYS) list.add(Core.bundle.get(key));
            list.addAll(loadTemplates().keySet());
            return list.toArray(new String[0]);
        }

        /** 按名称查模板行；内置优先 */
        static int[] findTemplate(String name) {
            for (int i = 0; i < BUILTIN_TEMPLATE_KEYS.length; i++) {
                if (Core.bundle.get(BUILTIN_TEMPLATE_KEYS[i]).equals(name)) return BUILTIN_TEMPLATE_ROWS[i];
            }
            return loadTemplates().get(name);
        }

        /** 将优先级序列化为 20 个逗号分隔的整数（16 矩阵 + 4 全局默认行） */
        public String weightsString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    if (i > 0 || j > 0) sb.append(',');
                    sb.append(weights[i][j]);
                }
            }
            for (int j = 0; j < 4; j++) {
                sb.append(',').append(defaultRow[j]);
            }
            return sb.toString();
        }

        /** 标记配置已改动：窗口内合并发送，超过窗口立即发送 */
        void markConfigDirty() {
            configDirty = true;
            if (Time.time >= lastConfigTime + configInterval) {
                flushConfig();
            }
        }

        /** 立即发送当前配置（若确有未发送改动） */
        void flushConfig() {
            if (!configDirty) return;
            configDirty = false;
            lastConfigTime = Time.time;
            configure(weightsString());
        }

        /** 解析配置字符串，非法时忽略；兼容旧版 16 值（无全局默认行）格式 */
        public void applyConfig(String str) {
            if (str == null) return;
            String[] parts = str.split(",");
            if (parts.length != 16 && parts.length != 20) return;
            try {
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        weights[i][j] = Mathf.clamp(Integer.parseInt(parts[i * 4 + j].trim()), 0, 4);
                    }
                }
                if (parts.length == 20) {
                    for (int j = 0; j < 4; j++) {
                        defaultRow[j] = Mathf.clamp(Integer.parseInt(parts[16 + j].trim()), 0, 4);
                    }
                } else {
                    defaultRow = weights[0].clone(); // 旧格式：取第一行作全局默认
                }
            } catch (NumberFormatException e) {
                return; // 网络通道畸形输入安全忽略（a3993c5 加固，保留）
            }

            // 配置变更后重置路由瞬态状态，避免沿用旧配置的降级/轮询状态
            for (int i = 0; i < 4; i++) {
                activePriority[i] = 0;
                blockCount[i] = 0;
                roundRobin[i] = 0;
            }
        }

        @Override
        public String config() {
            return weightsString();
        }

        // ---------- 配置界面 ----------

        /** 配置面板分发器：根据设置切换新版/经典界面 */
        @Override
        public void buildConfiguration(Table table) {
            if (Core.settings.getBool("universal-junction.newUI", false)) {
                // 新版：齿轮按钮打开全屏配置（参考逻辑处理器的铅笔按钮）
                table.button(Icon.settings, Styles.cleari, () -> {
                    UniversalJunctionDialog dialog = new UniversalJunctionDialog();
                    dialog.show(this);
                }).size(40f);
            } else {
                buildConfigurationLegacy(table);
            }
        }

        /** 新版配置面板 v8：最简拖拽测试 */
//        void showConfigDialog() {
//            BaseDialog dialog = new BaseDialog(Core.bundle.get("universal-junction.title"));
//            dialog.addCloseButton();
//
//            final String[] items = {"上", "右"};
//            final int[] order = {0, 1};
//            final int[] dragIdx = {-1};
//
//            // 浮动元素（拖拽时跟随鼠标）
//            final Table floating = new Table();
//            floating.background(Tex.paneSolid);
//            floating.setColor(Color.yellow);
//            floating.margin(8f);
//            floating.visible = false;
//            floating.touchable = Touchable.disabled;
//
//            Table content = new Table();
//            content.background(Tex.pane2);
//            content.margin(8f);
//
//            rebuildBlocks(content, items, order, dragIdx, floating);
//
//            // 将浮动元素添加到对话框最上层
//            dialog.cont.addChild(floating);
//
//            dialog.cont.add(content).grow();
//            dialog.show();
//        }

        void rebuildBlocks(Table content, String[] items, int[] order, int[] dragIdx, Table floating) {
            content.clearChildren();
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                final int itemIdx = order[i];
                Table block = new Table();
                block.background(Tex.paneSolid);
                block.setColor(Pal.accent);
                block.margin(8f);
                block.touchable = Touchable.enabled;

                Label label = new Label(items[itemIdx], Styles.defaultLabel);
                label.setFontScale(1.2f);
                label.setColor(Color.white);
                block.add(label).width(80f).height(40f);

                block.addListener(new arc.scene.event.InputListener() {
                    @Override
                    public boolean touchDown(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button) {
                        dragIdx[0] = itemIdx;
                        block.setColor(Color.yellow);

                        // 显示浮动元素
                        floating.clearChildren();
                        Label fl = new Label(items[itemIdx], Styles.defaultLabel);
                        fl.setFontScale(1.2f);
                        fl.setColor(Color.white);
                        floating.add(fl).width(80f).height(40f);
                        floating.visible = true;
                        floating.setPosition(event.stageX - 40f, event.stageY - 20f);
                        return true;
                    }

                    @Override
                    public void touchDragged(arc.scene.event.InputEvent event, float x, float y, int pointer) {
                        // 浮动元素跟随鼠标
                        floating.setPosition(event.stageX - 40f, event.stageY - 20f);
                    }

                    @Override
                    public void touchUp(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button) {
                        if (dragIdx[0] < 0) return;
                        int from = dragIdx[0];
                        dragIdx[0] = -1;
                        floating.visible = false;

                        // 交换
                        for (int j = 0; j < 2; j++) {
                            if (order[j] == from) {
                                order[j] = order[1 - j];
                                order[1 - j] = from;
                                break;
                            }
                        }
                        rebuildBlocks(content, items, order, dragIdx, floating);
                    }
                });

                block.update(() -> {
                    block.setColor(dragIdx[0] == itemIdx ? Color.yellow : Pal.accent);
                });

                content.add(block).size(100f, 50f).pad(10f).row();
            }
        }

        /** 统计指定组的方向数量 */
        int countGroupSize(int[][] groups, int in, int group) {
            int count = 0;
            for (int d = 0; d < 4; d++) {
                if (groups[in][d] == group) count++;
            }
            return count;
        }

        /** 找到最大组号 */
        int findMaxGroup(int[][] groups, int in) {
            int max = -1;
            for (int d = 0; d < 4; d++) {
                if (groups[in][d] > max) max = groups[in][d];
            }
            return max;
        }

        /** 组号数组（临时存储，用于落点检测） */
        final int[][] groupNums = new int[1][4];

        /** 检测落点是否在某个组框内 */
        int hitTestGroup(float sceneX, float sceneY) {
            // 需要在 rebuild 中设置 groupBoxes
            return -1; // 占位，实际在 rebuild 中实现
        }

        /** 找到插入点位置 */
        int findInsertPosition(float sceneY) {
            return -1; // 占位，实际在 rebuild 中实现
        }

        /** 更新插入点显示 */
        void updateInsertPoint(int position) {
            // 占位
        }

        /** 清除插入点 */
        void clearInsertBoxes() {
            if (currentInsertBox != null) {
                currentInsertBox.remove();
                currentInsertBox = null;
                currentInsertPosition = -1;
            }
        }


        /** 经典配置面板：模板一键应用 + 全局输出优先级 + 按方向覆盖（折叠高级层） */
        void buildConfigurationLegacy(Table table) {
            // 背景放在内容包裹表 bg 上：bg 尺寸完全由内容决定，
            // 覆盖层展开时 bg 随之变高，背景才能拉长覆盖全部内容
            // （外层 table 由游戏配置面板容器固定尺寸，背景画在其上不会跟随内容增长）
            table.clearChildren();
            Table bg = new Table();
            bg.background(Tex.pane2); // 原版建造菜单（方块选单）同款面板边框
            bg.margin(10f);
            table.add(bg);

            final int[] selDir = {0};
            final boolean[] expanded = {false};
            final boolean[] manageOpen = {false};
            // #11: tapOpen 改为 [输入方向][输出方向]，跨方向切换保持折叠状态
            final boolean[][] tapOpen = new boolean[4][4];
            final boolean[] hoverOpen = new boolean[4];
            Table globalTable = new Table();
            Table noteTable = new Table();
            Table overrideTable = new Table();
            Table manageTable = new Table();

            // 重建函数存于数组，避免 lambda 循环引用（r0=全局 r1=覆盖 r2=全量 r3=管理区）
            final Runnable[] r = new Runnable[4];

            // 重建全局区覆盖提示行（被单独配置的输入方向列表）
            Runnable noteR = () -> {
                noteTable.clearChildren();
                StringBuilder sb = new StringBuilder();
                for (int in = 0; in < 4; in++) {
                    if (isOverride(in)) sb.append(dirName(in)).append("、");
                }
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                    noteTable.add(Core.bundle.format("universal-junction.overriddenDirs", sb.toString())).color(Pal.accent).padBottom(4f).row();
                }
                noteTable.invalidateHierarchy(); // 局部刷新提示行
            };

            // 重建覆盖层（展开时显示方向选择 + 该方向滑块 + 快捷按钮）
            r[1] = () -> {
                overrideTable.clearChildren();
                if (!expanded[0]) return;
                overrideTable.table(inputs -> {
                    for (int d = 0; d < 4; d++) {
                        final int dir = d;
                        Button btn = inputs.button(b -> {
                            b.image(dirIcons[dir]).padRight(4f);
                            b.add(dirName(dir));
                        }, () -> {
                            selDir[0] = dir;
                            r[1].run();
                        }).size(72f, 36f).pad(3f).get();
                        btn.update(() -> btn.setChecked(selDir[0] == dir));
                    }
                }).padBottom(6f).row();

                int in = selDir[0];
                overrideTable.add(Core.bundle.format("universal-junction.from", dirName(in))).color(Pal.accent).padBottom(4f).row();
                // #12: 存储折叠文字/数值 Label 引用，per-frame lambda 自动增量更新
                final Label[] oFoldLabels = new Label[4];
                final Label[] oValLabels = new Label[4];
                final Table[] rows = new Table[4];
                for (int d = 0; d < 4; d++) {
                    final int out = d;
                    Table row = new Table();
                    row.hovered(() -> hoverOpen[out] = true);
                    row.exited(() -> hoverOpen[out] = false);
                    rows[out] = row;
                    renderRow(row, weights[in], out, tapOpen[in], hoverOpen, v -> {
                        weights[in][out] = v;
                        // #12: 不再重建兄弟行——per-frame lambda 自动检测折叠文字变化并更新
                        noteR.run();
                        markConfigDirty();
                    }, oFoldLabels, oValLabels);
                    overrideTable.add(row).growX().padBottom(2f).row();
                }
                overrideTable.table(quick -> {
                    quick.button(Core.bundle.get("universal-junction.even"), () -> {
                        setAllFor(selDir[0], 2);
                        r[1].run();
                        noteR.run();
                        flushConfig();
                    }).size(96f, 32f).pad(3f);
                    quick.button(Core.bundle.get("universal-junction.clear"), () -> {
                        setAllFor(selDir[0], 0);
                        r[1].run();
                        noteR.run();
                        flushConfig();
                    }).size(96f, 32f).pad(3f);
                    quick.button(Core.bundle.get("universal-junction.reset"), () -> {
                        resetToDefault(selDir[0]);
                        r[1].run();
                        noteR.run();
                        flushConfig();
                    }).size(96f, 32f).pad(3f);
                }).padTop(4f).row();
                // 全部恢复为全局：加确认弹窗防误触
                overrideTable.button(Core.bundle.get("universal-junction.resetAll"), () -> {
                    for (int i = 0; i < 4; i++) resetToDefault(i);
                    r[1].run();
                    r[0].run();
                    noteR.run();
                    table.invalidateHierarchy();
                    flushConfig();
                }).size(220f, 32f).padTop(4f);
                overrideTable.invalidateHierarchy();
            };

            // 重建全局层（全局默认行 4 个滑块；覆盖提示行在 noteTable，由 noteR 单独刷新）
            r[0] = () -> {
                globalTable.clearChildren();
                final boolean[] gtap = new boolean[4];
                final boolean[] ghover = new boolean[4];
                // #12: 存储全局行的折叠文字/数值 Label 引用
                final Label[] gFoldLabels = new Label[4];
                final Label[] gValLabels = new Label[4];
                final Table[] grows = new Table[4];
                for (int d = 0; d < 4; d++) {
                    final int out = d;
                    Table row = new Table();
                    row.hovered(() -> ghover[out] = true);
                    row.exited(() -> ghover[out] = false);
                    grows[out] = row;
                    renderRow(row, defaultRow, out, gtap, ghover, v -> {
                        defaultRow[out] = v;
                        // 无条件跟随：全局修改穿透所有输入方向的该输出维度（含被覆盖方向），
                        // 覆盖方向的其他输出维度仍保持独立
                        for (int in = 0; in < 4; in++) weights[in][out] = v;
                        // #12: 不再重建兄弟行——per-frame lambda 自动检测折叠文字变化并更新
                        r[1].run();
                        noteR.run();
                        markConfigDirty();
                    }, gFoldLabels, gValLabels);
                    globalTable.add(row).growX().padBottom(2f).row();
                }
            };

            // 重建模板管理区（展开时列出模板：使用/删除）
            r[3] = () -> {
                manageTable.clearChildren();
                if (!manageOpen[0]) return;
                manageTable.add(Core.bundle.get("universal-junction.customTemplates")).color(Pal.accent).padBottom(3f).row();
                java.util.Map<String, int[]> custom = loadTemplates();
                if (custom.isEmpty()) {
                    manageTable.add(Core.bundle.get("universal-junction.noTemplates")).color(Color.gray).padBottom(3f).row();
                }
                for (java.util.Map.Entry<String, int[]> e : custom.entrySet()) {
                    final String name = e.getKey();
                    final int[] row = e.getValue();
                    manageTable.table(t -> {
                        t.add(clip(name, 10)).left().padRight(8f);
                        t.button(Core.bundle.get("universal-junction.use"), () -> {
                            applyTemplate(row);
                            r[2].run();
                            flushConfig();
                        }).size(56f, 28f).pad(2f);
                        t.button(Core.bundle.get("universal-junction.delete"), () -> {
                            deleteTemplate(name);
                            r[3].run();
                        }).size(56f, 28f).pad(2f);
                    }).padBottom(3f).row();
                }
                manageTable.add(Core.bundle.get("universal-junction.builtinTemplates")).color(Pal.accent).padBottom(3f).padTop(4f).row();
                for (int i = 0; i < BUILTIN_TEMPLATE_KEYS.length; i++) {
                    final String name = Core.bundle.get(BUILTIN_TEMPLATE_KEYS[i]);
                    final int[] row = BUILTIN_TEMPLATE_ROWS[i];
                    manageTable.table(t -> {
                        t.add(name).left().padRight(8f);
                        t.button(Core.bundle.get("universal-junction.use"), () -> {
                            applyTemplate(row);
                            r[2].run();
                            flushConfig();
                        }).size(56f, 28f).pad(2f);
                    }).padBottom(3f).row();
                }
            };

            // 全量重建
            r[2] = () -> {
                r[0].run();
                noteR.run();
                r[1].run();
                r[3].run();
                table.invalidateHierarchy();
            };

            // 模板区：仅 [保存] [管理] 两个按钮（模板使用与删除均在管理区）
            bg.table(top -> {
                top.button(Core.bundle.get("universal-junction.save"), () -> {
                    Vars.ui.showTextInput("", Core.bundle.get("universal-junction.saveTitle"), 12, "", text -> {
                        String name = text.trim();
                        if (!name.isEmpty()) {
                            saveTemplate(name, currentTemplate()); // 保存完整 4 方向权重矩阵
                            r[3].run();
                        }
                    });
                }).size(88f, 40f).padRight(6f);
                TextButton manage = new TextButton(Core.bundle.get("universal-junction.manage"), Styles.defaultt);
                manage.update(() -> manage.setText(Core.bundle.get(manageOpen[0] ? "universal-junction.manageClose" : "universal-junction.manage")));
                manage.clicked(() -> {
                    manageOpen[0] = !manageOpen[0];
                    r[3].run();
                    table.invalidateHierarchy();
                });
                top.add(manage).size(88f, 40f);
            }).padBottom(8f).row();

            // 模板管理区（折叠）
            bg.add(manageTable).padBottom(8f).row();

            // 全局输出优先级：标题与配置行同宽（growX 自适应，背景随内容自然定宽）
            bg.add(Core.bundle.get("universal-junction.global")).color(Pal.accent).growX().padBottom(4f).row();
            bg.add(noteTable).padBottom(2f).row();
            bg.add(globalTable).growX().padBottom(6f).row();

            // 按方向覆盖（折叠开关）
            TextButton fold = new TextButton("", Styles.defaultt);
            fold.update(() -> fold.setText(Core.bundle.get(expanded[0] ? "universal-junction.collapse" : "universal-junction.expand")));
            fold.clicked(() -> {
                expanded[0] = !expanded[0];
                r[2].run();
            });
            bg.add(fold).size(220f, 34f).padTop(2f).row();

            bg.add(overrideTable).growX().padTop(4f);

            r[2].run(); // 初始渲染
        }

        // ---------- 禁用方向视觉反馈 ----------

        @Override
        public void drawSelect() {
            super.drawSelect();
            // 在禁用方向的连接边处标记红色短线（装卸器风格），提示该方向输出被禁用
            for (int d = 0; d < 4; d++) {
                if (!isDisabled(d)) continue;
                int angle = cardinalToAngle(d);
                Building near = nearby(angle);
                if (near == null || near.team != team) continue;
                // 连接边中点（方块交界处）
                float cx = x + Geometry.d4x[angle] * tilesize * 0.5f;
                float cy = y + Geometry.d4y[angle] * tilesize * 0.5f;
                // 沿边方向画一条短线（垂直于连接方向的短横杠）
                float hw = 5f; // 半宽
                float hh = 1.5f; // 半厚
                float cos = Geometry.d4y[angle]; // 垂直方向 x 分量
                float sin = Geometry.d4x[angle]; // 垂直方向 y 分量
                Draw.color(Color.red, 0.7f);
                // 画旋转的矩形：沿边方向为长轴
                Draw.rect(Core.atlas.white(), cx + cos * hw * 0.5f, cy + sin * hw * 0.5f, hw, hh, (float) Math.atan2(sin, cos) * Mathf.radDeg);
                Draw.rect(Core.atlas.white(), cx - cos * hw * 0.5f, cy - sin * hw * 0.5f, hw, hh, (float) Math.atan2(sin, cos) * Mathf.radDeg);
                Draw.color();
            }
        }

        // ---------- 存档 ----------

        @Override
        public void write(Writes write) {
            super.write(write);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) write.s((short) weights[i][j]);
            }
            for (int j = 0; j < 4; j++) write.s((short) defaultRow[j]); // v2 起
            buffer.write(write);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) weights[i][j] = read.s();
            }
            if (revision >= 2) {
                for (int j = 0; j < 4; j++) defaultRow[j] = read.s();
            } else {
                defaultRow = weights[0].clone(); // 旧存档：取第一行作全局默认
            }
            buffer.read(read, revision == 0);
        }

        @Override
        public byte version() {
            return 2;
        }

        @Override
        public void draw() {
            Draw.rect(region, x, y);
        }
    }
}
