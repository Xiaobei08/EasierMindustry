package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.event.HandCursorListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Tmp;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.world.blocks.distribution.UniversalJunction;

import static mindustry.Vars.ui;

/**
 * 万向交叉器配置对话框（新 UI）。
 * <p>
 * 布局：每个输入方向一个白色面板区域，区域内：
 * - 最上方：输入方向标题（白字，水平居中）；
 * - 标题下方：可拖动生成的 0~4 个白色槽位框（优先级分级，越靠上权重越大：槽0=4, 槽1=3, 槽2=2, 槽3=1），
 *   空槽位自动移除，最多 4 个；
 * - 区域最下方：红色框（优先级 0，即不输出），初始放置 4 个输出方向按钮（2x2 宫格）。
 * <p>
 * 交互：按住黄色按钮拖动，松手放置——拖到空白处生成新白色槽位；拖到已有槽位则并入该槽位；
 * 拖回红色框则移回红框，所在槽位空时槽位自动消失。每次放置都会同步到 weights[输入][输出] 并写入配置。
 */
public class UniversalJunctionDialog extends BaseDialog {
    /** 单个输入区域最多白色槽位数 */
    private static final int MAX_SLOTS = 4;
    /** 红框（优先级 0 区域）高度基准值：无论内部有无按钮都保持不变、不消失 */
    private static final float RED_H = 140f;
    /** 方向按钮尺寸基准值：红框与白框内一致，保证按钮大小不变 */
    private static final float BTN_W = 140f;
    private static final float BTN_H = 52f;
    /** 白框左侧拖动手柄宽度（基准值） */
    private static final float HANDLE_W = 26f;

    UniversalJunction.UniversalJunctionBuild build;
    /** 四个输入方向的区域状态，供保存按钮一次性同步全部 */
    private final Seq<RegionState> allRegions = new Seq<>();

    public UniversalJunctionDialog(String title) {
        this(title, Core.scene.getStyle(DialogStyle.class));
    }

    public UniversalJunctionDialog(String title, DialogStyle style) {
        super(title, style);
        row();
        add(buttons).growX().name("universal-junction");
        shown(this::setup);
    }

    public UniversalJunctionDialog() {
        this("@universal-junction.title");
    }

    public void setup() {
        cont.table(grid -> {
            grid.margin(10f);
            for (int in = 0; in < 4; in++) {
                final int input = in;
                grid.table(Tex.whitePane, column -> {
                    column.top();
                    column.margin(14f);
                    RegionState rs = new RegionState(input, column);
                    allRegions.add(rs);

                    // 输入方向标题（白字，水平居中）：colspan(4) 跨满整行，growX + labelAlign 居中。
                    column.add(Core.bundle.get("universal-junction.dir" + input))
                            .colspan(4).growX().labelAlign(Align.center).padBottom(10f).row();

                    // 槽位层：标题下方，从上往下堆叠白框
                    column.add(rs.slotLayer).growX().row();

                    // 空占位拉伸，把红框推到区域最下方
                    column.add().expandY().row();

                    // 红框（优先级 0）：置于区域最下方，固定高度（空也不变/不消失）
                    Table redBox = new Table();
                    redBox.background(Tex.whitePane);
                    redBox.setColor(Color.red);
                    redBox.margin(10f);
                    rs.redBox = redBox;
                    column.add(redBox).growX().height(RED_H).padTop(6f).row();

                    // 判定该输入方向是否已配置：全为默认值 2 视为未配置（按钮放红框）；否则按 weights 还原槽位。
                    boolean configured = false;
                    for (int out = 0; out < 4; out++) {
                        if (build.weights[input][out] != 2) { configured = true; break; }
                    }

                    // 从建筑当前权重初始化布局：仅当已配置时还原槽位；未配置（默认全2）按钮放红框。
                    // 「返回再进入」能看到已保存的配置；且未配置时不动 weights，保持默认全 2。
                    if (configured) {
                        for (int out = 0; out < 4; out++) {
                            Direction d = new Direction(rs, out);
                            int w = build.weights[input][out];
                            if (w > 0) {
                                rs.placeInSlotByWeight(d, w);
                            } else {
                                rs.redButtons.add(d);
                            }
                        }
                        build.configure(build.weightsString());
                        rs.rebuildRed();
                        rs.pruneEmptySlots();
                        rs.rebuildSlots();
                    } else {
                        for (int out = 0; out < 4; out++) {
                            rs.redButtons.add(new Direction(rs, out));
                        }
                        rs.rebuildRed();
                        // 未配置：weights 保持默认全 2（内存），不触发 configure 写回，按钮全部显示在红框
                        for (int out = 0; out < 4; out++) {
                            build.weights[input][out] = 2;
                        }
                        build.configure(build.weightsString());
                    }
                }).growX().grow().uniformX().pad(8f);
            }
        }).grow();

        buttons.defaults()
                .size(280f, 60f)
                .left()
                .margin(10f);
        buttons.button("@back", Icon.left, this::hide);
        // 直接保存当前配置：立即把当前布局对应的 weights 写入建筑并持久化，无需命名模板
//        buttons.button("@universal-junction.save", Icon.save, () -> {
//            for (RegionState rs : allRegions) rs.syncWeights();
//            build.configure(build.weightsString());
//            hide();
//        });
        buttons.button("@edit", Icon.edit, () -> {
            BaseDialog dialog = new BaseDialog("@edit");

            dialog.cont.pane(p -> p.table(Tex.button, t -> {
                t.defaults()
                        .size(280f, 60f)
                        .left()
                        .marginLeft(12f)
                        .margin(10f);
                t.button("@clear", Icon.cancel, Styles.flatt, () -> {
                    ui.showConfirm("", () -> {
                        build.setAll(0);
                        cont.clearChildren();
                        buttons.clearChildren();
                        hide();
                        show();
                        invalidateHierarchy();
                    });
                    dialog.hide();
                }).row();
                t.button("@copy.clipboard", Icon.copy, Styles.flatt, () -> {
                    copyToClipboard();
                    dialog.hide();
                }).row();
                t.button("@load.clipboard", Icon.download, Styles.flatt, () -> {
                    loadFromClipboard();
                    dialog.hide();
                }).row();
            }));
            dialog.addCloseButton();
            dialog.show();
        });
    }

    public void copyToClipboard() {
        Core.app.setClipboardText(build.weightsString());
    }

    public void loadFromClipboard() {
        try {
            build.applyConfig(Core.app.getClipboardText());
        } catch (Throwable e) {
            ui.showException(e);
        }
    }

    public void show(UniversalJunction.UniversalJunctionBuild build) {
        this.build = build;
        show();
    }

    // ============================================================
    // 单个输入区域的状态：维护按钮在红框/各槽位中的归属
    // ============================================================

    public class RegionState {
        final int input;
        final Table column;
        final Table slotLayer;                 // 槽位层（从上堆叠）
        final Seq<SlotBox> slotBoxes = new Seq<>();   // 每个白框（含拖动手柄+按钮区）
        final Seq<Seq<Direction>> slotContents = new Seq<>(); // 每个白框内的按钮
        final Seq<Direction> redButtons = new Seq<>(); // 红框内的按钮
        Table redBox;

        RegionState(int input, Table column) {
            this.input = input;
            this.column = column;
            this.slotLayer = new Table();
        }

        /** 重建红框 2x2 布局：红框高度固定，内容垂直居中 */
        void rebuildRed() {
            redBox.clearChildren();
            redBox.defaults().pad(4f);
            Table grid2x2 = new Table();
            for (int i = 0; i < redButtons.size; i++) {
                grid2x2.add(redButtons.get(i)).uniformX().width(BTN_W).height(BTN_H);
                if (i % 2 == 1) grid2x2.row();
            }
            redBox.add(grid2x2).grow().center();
            redBox.invalidateHierarchy();
        }

        /** 重建槽位层（按 slotBoxes 顺序从上往下堆叠，框间加大间隔便于区分/拖放） */
        void rebuildSlots() {
            slotLayer.clearChildren();
            for (int i = 0; i < slotBoxes.size; i++) {
                // 白框高度 = 按钮行数 * 单行高度 + 上下边距；最多 2 列，行数 = ceil(size/2)
                int n = slotContents.get(i).size;
                float rows = Mathf.ceil(n / 2f);
                float h = rows * (BTN_H + 8f) + 14f;
                slotLayer.add(slotBoxes.get(i)).growX().height(h).padBottom(40f).row();
            }
            slotLayer.invalidateHierarchy();
        }

        /** 移除全部空白槽位（无按钮即消失） */
        void pruneEmptySlots() {
            for (int i = slotBoxes.size - 1; i >= 0; i--) {
                if (slotContents.get(i).size == 0) {
                    slotBoxes.remove(i);
                    slotContents.remove(i);
                }
            }
            rebuildSlots();
        }

        /** 由当前布局同步 weights[input][out]：槽 i 权重 = 4-i，红框权重 = 0，并写入配置 */
        void syncWeights() {
            for (int i = 0; i < slotContents.size; i++) {
                int w = MAX_SLOTS - i; // 槽0=4, 槽1=3, ...
                for (Direction d : slotContents.get(i)) {
                    build.weights[input][d.dir] = w;
                }
            }
            for (Direction d : redButtons) {
                build.weights[input][d.dir] = 0;
            }
            build.configure(build.weightsString());
        }

        /** 按钮当前所在：红框 offset=-1；槽位 i 返回其索引 */
        int locate(Direction d) {
            for (int i = 0; i < slotContents.size; i++) {
                if (slotContents.get(i).contains(d)) return i;
            }
            return -1;
        }

        /** 从当前归属处移除按钮，并立即重排来源白框（删除后剩余按钮重新居中） */
        void removeFrom(Direction d) {
            int i = locate(d);
            if (i >= 0) {
                slotContents.get(i).remove(d);
                rebuildSlotContents(i);
            }
            redButtons.remove(d);
        }

        /** 放入指定槽位 i */
        void placeInSlot(Direction d, int i) {
            removeFrom(d);
            slotContents.get(i).add(d);
            rebuildSlotContents(i);
            pruneEmptySlots();
            syncWeights();
        }

        /** 计算落点(stage坐标)应插入的槽位索引：与每个槽中心精确比较（stage y-up，越大越靠上） */
        int insertIndexFor(float sx, float sy) {
            for (int i = 0; i < slotBoxes.size; i++) {
                Vec2 v = slotBoxes.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f));
                float centerY = v.y + slotBoxes.get(i).getHeight() / 2f;
                if (sy > centerY) return i; // 落点在该槽中心上方 → 插入到该槽位置（其前）
            }
            return slotBoxes.size; // 落点在所有槽中心之下 → 追加末尾
        }

        /** 生成新槽位（插入到两框之间）并放入按钮 */
        void createSlotFor(Direction d, float sx, float sy) {
            removeFrom(d); // 先移除按钮并重排来源框视觉
            pruneEmptySlots(); // 立即清掉腾空的来源框，让索引基于剔除后的列表
            if (slotBoxes.size >= MAX_SLOTS) {
                placeInRed(d); // 仍无空位则退回红框
                return;
            }
            int insert = insertIndexFor(sx, sy);
            SlotBox box = new SlotBox(this);
            Seq<Direction> contents = new Seq<>();
            contents.add(d);
            slotBoxes.insert(insert, box);
            slotContents.insert(insert, contents);
            rebuildSlotContents(insert);
            rebuildSlots();
            syncWeights();
        }

        /** 放入红框 */
        void placeInRed(Direction d) {
            removeFrom(d);
            redButtons.add(d);
            rebuildRed();
            pruneEmptySlots();
            syncWeights();
        }

        /** 按权重放入对应槽位（初始化时用）：w=4→槽0, 3→槽1, 2→槽2, 1→槽3；槽不存在则创建 */
        void placeInSlotByWeight(Direction d, int w) {
            int idx = MAX_SLOTS - w;
            if (idx < 0 || idx >= MAX_SLOTS) {
                placeInRed(d);
                return;
            }
            while (slotBoxes.size <= idx) {
                slotBoxes.add(new SlotBox(this));
                slotContents.add(new Seq<Direction>());
            }
            removeFrom(d);
            slotContents.get(idx).add(d);
            rebuildSlotContents(idx);
        }

        /** 重建某个槽位的内部按钮布局：按钮组整体水平居中（用两侧expandX空白吸收剩余宽度） */
        void rebuildSlotContents(int i) {
            SlotBox box = slotBoxes.get(i);
            box.rebuildButtons(slotContents.get(i));
        }

        /** 整框换位：把 srcIdx 的白框整体移动到落点位置（拖动手柄触发） */
        void reorderBox(int srcIdx, float sx, float sy) {
            int insert = insertIndexFor(sx, sy); // 当前含来源框的列表中的插入点
            SlotBox box = slotBoxes.remove(srcIdx);
            Seq<Direction> contents = slotContents.remove(srcIdx);
            if (insert > srcIdx) insert--; // 移除后索引前移
            insert = Mathf.clamp(insert, 0, slotBoxes.size);
            slotBoxes.insert(insert, box);
            slotContents.insert(insert, contents);
            rebuildSlotContents(insert);
            rebuildSlots();
            syncWeights();
        }

        /** 单个白框：左侧拖动手柄（整框排序），右侧按钮区（按钮可单独拖出/拖入） */
        class SlotBox extends Table {
            /** 按钮区：只有按钮会被重排，手柄常驻不受影响 */
            final Table content = new Table();
            /** 拖动手柄 */
            final Table handle = new Table();

            SlotBox(RegionState rs) {
                // 白框底
                background(Tex.whitePane);
                setColor(Color.white);
                margin(2f);

                // 拖动手柄：三横杠抓手，拖动整框换位
                handle.background(Tex.whiteui);
                handle.setColor(Color.gray);
                handle.touchable = Touchable.enabled;
                handle.margin(4f);
                handle.defaults().growX().height(3f).pad(2f);
                for (int i = 0; i < 3; i++) {
                    Table bar = new Table();
                    bar.background(Tex.whitePane);
                    bar.setColor(Color.lightGray);
                    handle.add(bar).row();
                }

                handle.addListener(new InputListener() {
                    private Table ghost; // 整框拖拽影（仅拖动时创建，单击不出现）
                    private float downX, downY; // 按下时的指针(stage)坐标，用于判定是否开始拖动

                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        if (button == KeyCode.mouseMiddle) return false;
                        downX = event.stageX;
                        downY = event.stageY;
                        return true;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        // 指针移动超过阈值才判定为「拖动」，此时才生成整框影并开始换位
                        if (ghost == null) {
                            if (Math.abs(event.stageX - downX) + Math.abs(event.stageY - downY) < 8f) return;
                            ghost = new Table();
                            ghost.background(Tex.whitePane);
                            ghost.setColor(1f, 1f, 1f, 0.7f);
                            ghost.setSize(SlotBox.this.getWidth(), SlotBox.this.getHeight());
                            ghost.touchable = Touchable.disabled;
                            ghost.setPosition(event.stageX - ghost.getWidth() / 2f,
                                    event.stageY - ghost.getHeight() / 2f);
                            Core.scene.root.addChild(ghost);
                            ghost.toFront();
                        } else {
                            ghost.setPosition(event.stageX - ghost.getWidth() / 2f,
                                    event.stageY - ghost.getHeight() / 2f);
                        }
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        if (ghost == null) return; // 单击（未拖动）：不换位、无痕迹
                        float sx = event.stageX;
                        float sy = event.stageY;
                        ghost.remove();
                        ghost = null;
                        int srcIdx = slotBoxes.indexOf(SlotBox.this);
                        if (srcIdx >= 0) reorderBox(srcIdx, sx, sy);
                    }
                });

                // 布局：左手柄 + 右按钮区
                content.touchable = Touchable.childrenOnly;
                add(handle).width(HANDLE_W).growY().padRight(6f);
                add(content).grow();
            }

            /** 重建按钮区（手柄不受影响）：按钮按 2 列宫格排列，宽度有上限，避免多按钮撑宽整列 */
            void rebuildButtons(Seq<Direction> buttons) {
                content.clearChildren();
                Table grid = new Table();
                for (int i = 0; i < buttons.size; i++) {
                    grid.add(buttons.get(i)).width(BTN_W).height(BTN_H).pad(2f);
                    if (i % 2 == 1) grid.row();
                }
                // 左右弹性空白：把按钮宫格整体推向正中
                Table holder = new Table();
                holder.add().expandX();
                holder.add(grid);
                holder.add().expandX();
                content.add(holder).grow();
                content.invalidateHierarchy();
                invalidateHierarchy();
            }
        }
    }

    // ============================================================
    // 黄色按钮：可按住拖动，松手按落点放置
    // ============================================================

    public class Direction extends Table {
        final RegionState rs;
        final int dir;
        Table ghost; // 拖拽中的浮动影子

        public Direction(RegionState rs, int dir) {
            this.rs = rs;
            this.dir = dir;

            background(Tex.whitePane);
            setColor(Color.gold);
            margin(0f);
            touchable = Touchable.enabled;

            table(Tex.whiteui, t -> {
                t.color.set(color);
                t.addListener(new HandCursorListener());
                t.margin(6f);
                t.touchable = Touchable.enabled;
                t.add("@universal-junction.dir" + dir).style(Styles.outlineLabel).name("statement-name").color(color).padRight(8f);
            }).growX().height(38f);

            row();

            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    // 不响应菜单按钮内的按压
                    if (event.targetActor instanceof Image) return false;
                    if (button == KeyCode.mouseMiddle) return false;
                    return dragStart(event);
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (ghost != null) {
                        ghost.setPosition(event.stageX - ghost.getWidth() / 2f,
                                event.stageY - ghost.getHeight() / 2f);
                    }
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    if (ghost == null) return;
                    float sx = event.stageX;
                    float sy = event.stageY;
                    Table slot = hitSlot(sx, sy);
                    boolean inRed = inRect(rs.redBox, sx, sy);
                    clearGhost();
                    visible = true;

                    int idx = -1;
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        if (rs.slotBoxes.get(i) == slot) { idx = i; break; }
                    }
                    if (slot != null && idx >= 0) {
                        rs.placeInSlot(Direction.this, idx);
                    } else if (inRed) {
                        rs.placeInRed(Direction.this);
                    } else if (rs.slotBoxes.size < MAX_SLOTS) {
                        rs.createSlotFor(Direction.this, sx, sy);
                    } else {
                        // 槽位已满且未落在任何目标：放回原位（重放当前归属）
                        int cur = rs.locate(Direction.this);
                        if (cur >= 0) rs.placeInSlot(Direction.this, cur);
                        else rs.placeInRed(Direction.this);
                    }
                }
            });
        }

        private boolean dragStart(InputEvent event) {
            if (build == null) return false;
            // 生成拖拽影子（同外观、不可交互），隐藏本体，影子跟随指针
            ghost = new Table();
            ghost.background(Tex.whitePane);
            ghost.setColor(color);
            ghost.margin(0f);
            ghost.table(Tex.whiteui, t -> {
                t.color.set(color);
                t.margin(6f);
                t.touchable = Touchable.disabled;
                t.add("@universal-junction.dir" + dir).style(Styles.outlineLabel).color(color).padRight(8f);
            }).growX().height(38f);
            ghost.setSize(getWidth(), getHeight());
            ghost.touchable = Touchable.disabled;
            ghost.setPosition(event.stageX - getWidth() / 2f, event.stageY - getHeight() / 2f);
            Core.scene.root.addChild(ghost);
            toFront();
            ghost.toFront();
            visible = false;
            return true;
        }

        private void clearGhost() {
            if (ghost != null) {
                ghost.remove();
                ghost = null;
            }
        }

        /** 命中测试：指针(stage坐标)落在哪个槽位内 */
        private Table hitSlot(float sx, float sy) {
            for (Table box : rs.slotBoxes) {
                if (inRect(box, sx, sy)) return box;
            }
            return null;
        }

        private boolean inRect(Table t, float sx, float sy) {
            if (t == null) return false;
            Vec2 v = t.localToStageCoordinates(Tmp.v1.set(0f, 0f));
            float x = v.x, y = v.y;
            return sx >= x && sx <= x + t.getWidth() && sy >= y && sy <= y + t.getHeight();
        }

        @Override
        public void draw() {
            float pad = 5f;
            Fill.dropShadow(x + width / 2f, y + height / 2f, width + pad, height + pad, 10f, 0.9f * parentAlpha);

            Draw.color(0, 0, 0, 0.3f * parentAlpha);
            Fill.crect(x, y, width, height);
            Draw.reset();

            super.draw();
        }
    }
}
