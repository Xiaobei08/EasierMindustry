package silicon.ui;

import arc.Core;
import arc.audio.Sound;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Interp;
import arc.math.Mathf;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.style.Style;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.game.Team;
import mindustry.ui.Styles;
import silicon.util.MessageSystem;
import silicon.util.MessageSystem.Message;
import silicon.util.MessageSystem.MessageType;
import silicon.util.SiliconSounds;

import static mindustry.Vars.ui;

/**
 * 常驻屏幕左边缘、可展开/收起的消息面板。纯展示，数据与优先级排序由 {@link MessageSystem} 负责。
 * <p>
 * 完全由程序绘制（染色 whiteui），不依赖贴图；半透明深色底，标题栏自动取最顶部消息的颜色逐帧刷新。
 * 展开态为可滚动消息列表，收起态为单列彩色小方块（正方形 + 居中图标）。最新消息在上。
 */
public class MessagePanel extends Table implements MessageSystem.Listener {
    /** 展开面板宽度设置键（屏幕宽度百分比）。 */
    public static final String SET_WIDTH = "message-panel.width";
    /** 面板高度设置键（屏幕高度百分比）。 */
    public static final String SET_TOP = "message-panel.top";
    /** 展开宽度默认百分比（屏宽的 20%）。 */
    public static final float DEFAULT_WIDTH_PERCENT = 20.0f;
    /** 面板高度默认百分比（屏高的 40%）。 */
    public static final float DEFAULT_TOP_PERCENT = 40.0f;
    /** 展开宽度范围。 */
    public static final float MIN_WIDTH_PERCENT = 20.0f;
    public static final float MAX_WIDTH_PERCENT = 50.0f;
    /** 面板高度范围。 */
    public static final float MIN_TOP_PERCENT = 20.0f;
    public static final float MAX_TOP_PERCENT = 80.0f;
    /** 收起态面板宽度（单列小方块）。 */
    private static final float COLLAPSED_WIDTH = 48.0f;
    /** 标题栏高度。 */
    private static final float TITLE_HEIGHT = 30.0f;
    /** 收起态切换按钮透明度。 */
    private static final float COLLAPSED_ALPHA = 0.4f;
    /** 行内居中图标尺寸。 */
    private static final float ICON_SIZE = 22.0f;

    /** 是否展开显示（默认收起）。 */
    private boolean expanded = false;
    /** 消息列表（滚动容器内容）：手动纵向布局、支持并发插入动画。 */
    private final MessageList list = new MessageList();
    /** 滚动面板。 */
    private ScrollPane pane;
    /** 顶部标题栏（颜色逐帧按最顶部消息刷新）。 */
    private Table titleBar;
    /** 上一帧标题栏颜色（用于判断是否需要重设底色）。 */
    private Color prevTitleColor;
    /** 已读消息集合：客户端面板视图状态，独立于消息系统；新消息默认未读。 */
    private final ObjectSet<Message> readMessages = new ObjectSet<>();
    /** 上次展开时间（秒）：展开过渡期内不标记已读。 */
    private float expandTime = -1000.0f;
    /** 待处理：下一帧把滚动条钉在顶部（最新在上）。暂停时也能触发。 */
    private boolean scrollToTopPending = false;
    /** 待处理：按当前展开/收起态应用滚动条配置（用真实帧 delta 计时，暂停时也能触发）。 */
    private float scrollModeDelay = -1f;
    /** 展开/收起两阶段切换的状态机。 */
    private TogglePhase togglePhase = TogglePhase.NONE;
    /** 当前切换阶段已流逝时长（秒）。 */
    private float phaseTime = 0f;
    /** 展开阶段气泡是否已开始从左滑入。 */
    private boolean expandBubblesStarted = false;
    /** 收起阶段是否已开始收窄面板宽度（在气泡移出 75% 时置位）。 */
    private boolean narrowStarted = false;
    /** 气泡框固定宽度 = 面板完全展开时的内容宽（不随面板收缩变化）。 */
    private float bubbleWidth = 200f;
    /** 未读消息数标签（脱离表格布局，锚定面板右缘 +10px，随展开/收起平移；仅文本）。 */
    private Label unreadLabel;
    /** 上次显示的未读消息数（避免每帧重建文本）。 */
    private int lastUnreadCount = -1;
    /** 未读标签当前透明度（0=隐藏，1=显示）。 */
    private float unreadLabelAlpha = 0f;
    /** 滚动条当前透明度（淡入淡出动画用）。 */
    private float scrollbarAlpha = 1f;
    /** 滚动条目标透明度（1=显示，0=隐藏）。 */
    private float scrollbarAlphaTarget = 1f;
    /** 滚动条贴图共享染色：alpha 由 {@link #scrollbarAlpha} 逐帧驱动。 */
    private final Color scrollbarTint = new Color(1f, 1f, 1f, 1f);

    /** 展开/收起切换的阶段。 */
    private enum TogglePhase {
        /** 无切换进行中。 */
        NONE,
        /** 展开：面板加宽，气泡在宽度约 75% 时从左滑入。 */
        EXPAND_IN,
        /** 收起：气泡先全部向左滑出屏幕，随后面板收缩。 */
        COLLAPSE_OUT
    }

    public MessagePanel() {
        this.top().left();
        MessageSystem.instance.setListener(this);
        this.list.expireListener = MessageSystem.instance::remove;
        this.list.onRemoveFinished = row -> {
            int i = this.list.getChildren().indexOf(row);
            if (i >= 0) {
                this.list.removeRow(i);
            }
        };
        this.visible(() -> ui != null && ui.hudfrag != null && ui.hudfrag.shown && Vars.state.isGame());
        this.rebuild();
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        this.refreshTitleBarColor();
        if (this.expanded && this.pane != null && Time.globalTime - this.expandTime > 0.35f) {
            this.markVisibleRead();
        }
        if (this.expanded && this.pane != null && Core.scene != null && !this.hasMouse() && Core.scene.getScrollFocus() == this.pane) {
            Core.scene.setScrollFocus(null);
        }
        if (this.scrollToTopPending) {
            this.scrollToTopPending = false;
            if (this.pane != null) {
                this.pane.setScrollYForce(0.0f);
            }
        }
        // 真实帧计时应用滚动条配置（暂停时 Time.run 不触发，改用这里，暂停也能更新 UI）
        if (this.scrollModeDelay >= 0f) {
            this.scrollModeDelay -= delta;
            if (this.scrollModeDelay < 0f) {
                this.scrollModeDelay = -1f;
                this.applyScrollMode();
            }
        }
        this.updateUnreadLabel(delta);
        // 失联消息源的清扫已统一由 MessageSystem 的全局 tick 驱动（服务器权威），此处不再反复占用
        // 滚动条透明度逐帧逼近目标（postpoint: 展开进入动画开始时淡入，收起触发时淡出）
        if (Math.abs(this.scrollbarAlpha - this.scrollbarAlphaTarget) > 0.005f) {
            this.scrollbarAlpha = Mathf.approach(this.scrollbarAlpha, this.scrollbarAlphaTarget, delta / Animated.SCROLLBAR_FADE);
            this.scrollbarTint.a = this.scrollbarAlpha;
        }
        this.updateTogglePhase(delta);
    }

    /** 刷新未读消息数标签：位置始终绑定面板右缘 +10px（随展开/收起平移），文字更新时重建。未读数为 0 时不显示（淡出/淡入）。 */
    private void updateUnreadLabel(float delta) {
        if (this.unreadLabel == null) {
            return;
        }
        int unread = 0;
        for (Message m : MessageSystem.instance.all()) {
            if (this.visible(m) && !this.readMessages.contains(m)) {
                unread++;
            }
        }
        if (unread != this.lastUnreadCount) {
            this.lastUnreadCount = unread;
            this.unreadLabel.setText("【未读消息：" + unread + "】");
        }
        float targetAlpha = unread > 0 ? 1f : 0f;
        if (Math.abs(this.unreadLabelAlpha - targetAlpha) > 0.005f) {
            this.unreadLabelAlpha = Mathf.approach(this.unreadLabelAlpha, targetAlpha, delta / Animated.UNREAD_FADE);
        } else {
            this.unreadLabelAlpha = targetAlpha;
        }
        this.unreadLabel.color.a = this.unreadLabelAlpha;
        this.unreadLabel.visible = this.unreadLabelAlpha > 0.01f;
        this.unreadLabel.setPosition(this.getWidth() + 10.0f, 8.0f);
    }

    /** 驱动展开/收起两阶段切换：控制气泡左滑入/左滑出的时机，并在完成后重建面板。 */
    private void updateTogglePhase(float delta) {
        if (this.togglePhase == TogglePhase.NONE) return;
        this.phaseTime += delta;
        switch (this.togglePhase) {
            case COLLAPSE_OUT -> {
                float prog = Mathf.clamp(this.phaseTime / Animated.SLIDE_DURATION, 0f, 1f);
                // 气泡移出 75% 后开始收窄面板宽度（与剩余滑出重叠）
                if (!this.narrowStarted && prog >= 0.75f) {
                    this.narrowStarted = true;
                    this.beginPanelNarrow();
                }
                // 气泡完全移出后：重建为收起态，小图标随即播放出现动画
                if (prog >= 1f) {
                    this.togglePhase = TogglePhase.NONE;
                    this.expanded = false;
                    this.rebuild();
                }
            }
            case EXPAND_IN -> {
                // 面板加宽到约 75% 时，让固定宽度的气泡从屏幕左侧向右滑入
                float collW = COLLAPSED_WIDTH;
                float fullW = this.bubbleWidth;
                float threshold = collW + (fullW - collW) * 0.75f;
                boolean wideEnough = this.width >= threshold;
                boolean timeout = this.phaseTime >= Animated.DURATION * 1.5f;
                if ((wideEnough || timeout) && !this.expandBubblesStarted) {
                    this.expandBubblesStarted = true;
                    this.scrollbarAlphaTarget = 1f; // 消息开始进入动画时滚动条淡入
                    this.list.beginEnterSlide();
                }
                if (this.expandBubblesStarted && (this.list.enterSlideDone() || this.phaseTime >= Animated.DURATION * 1.5f + Animated.INSERT_DURATION)) {
                    this.togglePhase = TogglePhase.NONE;
                }
            }
            default -> {
            }
        }
    }

    /** 逐帧把标题栏底色刷新为最顶部消息的优先级色（在变化时才重设，避免每帧重建贴图）。 */
    private void refreshTitleBarColor() {
        if (this.titleBar == null) {
            return;
        }
        Color target = this.titleBarColor();
        if (this.prevTitleColor == null || Math.abs(this.prevTitleColor.r - target.r) > 0.01f || Math.abs(this.prevTitleColor.g - target.g) > 0.01f || Math.abs(this.prevTitleColor.b - target.b) > 0.01f || Math.abs(this.prevTitleColor.a - target.a) > 0.01f) {
            this.titleBar.background(this.tintBar(target));
            this.prevTitleColor = target;
        }
    }

    /**
     * 标记当前可见（≥95% 高度露出在面板可视区内）的未读消息为已读。
     * 坐标系统左上原点、y 向下：topY 为上、bottomY 为下，overlap = 可视区内纵向重叠量。
     * 用 globalTime 门锁保证暂停时也能变更阅读属性。
     */
    private void markVisibleRead() {
        if (!this.expanded || this.pane == null) {
            return;
        }
        float paneH = this.pane.getHeight();
        for (Element e : this.list.getChildren()) {
            MessageRow r = (MessageRow) e;
            if (r.read || r.removing || r.entering) continue;
            float topY = r.localToAscendantCoordinates(this.pane, new Vec2(0.0f, 0.0f)).y;
            float bottomY = r.localToAscendantCoordinates(this.pane, new Vec2(0.0f, r.getHeight())).y;
            float rh = r.getHeight();
            if (rh <= 0.0f) continue;
            float overlap = Math.min(bottomY, paneH) - Math.max(topY, 0.0f);
            if (overlap < rh * 0.95f) continue;
            r.read = true;
            this.readMessages.add(r.msg);
        }
    }

    static float widthPercentSetting() {
        return Core.settings.getInt(SET_WIDTH, 20);
    }

    static float topPercentSetting() {
        return Core.settings.getInt(SET_TOP, 40);
    }

    /** 设置变化后重排面板。 */
    public static void applySettings() {
        if (MessagePanel.instance() != null) {
            MessagePanel.instance().rebuild();
        }
    }

    private static MessagePanel instance() {
        return SiliconMessagePanelHolder.panel;
    }

    /** 本地观看者队伍：单机/普通客户端即 {@code Vars.player.team()}；无人局（如专用服务器，无面板）返回 null。 */
    private Team activeTeam() {
        return Vars.player != null && Vars.player.team() != null ? Vars.player.team() : null;
    }

    /** 本观看者是否可见某消息：全局消息所有玩家可收；同队消息仅同队可收；无队伍消息不限制。多人下由面板（客户端）过滤展示。 */
    private boolean visible(Message m) {
        return m.visibleTo(this.activeTeam());
    }

    /** 计算某消息在“本观看者可见列表”中的序号（数据含不可见消息时，用于把行插到正确可见位置）。 */
    private int visibleIndexOf(Message target) {
        int vi = 0;
        for (int i = 0; i < MessageSystem.instance.size(); i++) {
            Message m = MessageSystem.instance.get(i);
            if (m == target) return vi;
            if (this.visible(m)) vi++;
        }
        return vi;
    }

    public static void setInstance(MessagePanel panel) {
        SiliconMessagePanelHolder.panel = panel;
    }

    /** 目标宽度：展开 = 屏宽百分比，收起 = 固定窄条。 */
    private float targetWidth() {
        if (this.expanded) {
            float w = Core.graphics.getWidth();
            return Mathf.clamp(this.widthPercentSetting() / 100.0f, 0.05f, 0.9f) * w;
        }
        return 48.0f;
    }

        /** 收起阶段：在气泡移出 75% 后开始收窄面板宽度（仍显示剩余的气泡，与其滑出重叠）。 */
    private void beginPanelNarrow() {
        if (this.width <= COLLAPSED_WIDTH + 1f) {
            return;
        }
        this.clearActions();
        this.actions(Actions.sizeTo(COLLAPSED_WIDTH, this.height, Animated.DURATION, Animated.INTERP));
    }

    /** 收起态的滚动条配置：隐藏滚动条并禁用滚动。 */
    private void applyCollapsedScrollMode() {
        if (this.pane == null) {
            return;
        }
        this.pane.setScrollBarPositions(false, false);
        this.pane.setForceScroll(false, false);
        this.pane.setFadeScrollBars(true);
        this.pane.setScrollingDisabled(true, true);
        this.pane.invalidateHierarchy();
    }

    /** 按展开/收起态应用滚动条配置（展开始终显示垂直滚动条并可滚动，收起隐藏滚动条并禁用滚动）。 */
    private void applyScrollMode() {
        if (this.pane == null) {
            return;
        }
        if (this.expanded) {
            this.pane.setScrollBarPositions(false, false); // 垂直滚动条放在左侧（vScrollOnRight=false）
            this.pane.setForceScroll(false, true);          // 始终显示垂直滚动条（即使无溢出）
            this.pane.setFadeScrollBars(false);             // 不淡出，始终不透明可见
            this.pane.setScrollingDisabled(false, false);
        } else {
            this.applyCollapsedScrollMode();
        }
    }

    /** 重建面板结构（展开/收起共用；保留旧行的 age 使倒计时不重置）。 */
    void rebuild() {
        float h = Core.graphics.getHeight();
        float panelHeight = Math.max(40.0f, Mathf.clamp(this.topPercentSetting() / 100.0f, 0.0f, 1.0f) * h);
        float fromWidth = this.width > 0.0f ? this.width : Core.graphics.getWidth();
        float toWidth = this.targetWidth();
        float contentWidth = toWidth - 8.0f; // 面板内容宽（去掉两侧 padding）
        this.clearChildren();
        this.prevTitleColor = null;
        this.setSize(fromWidth, panelHeight);
        this.margin(0.0f);
        this.background(Styles.black3);
        this.table(this.tintBar(this.titleBarColor()), bar -> {
            this.titleBar = bar;
            bar.left();
            if (this.expanded) {
                bar.add(this.titleLabel()).growX().left().padLeft(8.0f);
                this.addToggleButton(bar, 1.0f);
            } else {
                this.addToggleButton(bar, 0.4f);
            }
        }).growX().height(TITLE_HEIGHT).row();
        if (this.expanded) {
            this.addTrashButton(panelHeight);
        }
        ObjectMap<Message, MessageRow> oldByMsg = new ObjectMap<>();
        for (Element e : this.list.getChildren()) {
            oldByMsg.put(((MessageRow) e).msg, (MessageRow) e);
        }
        this.list.clearChildren();
        this.list.setExpanded(this.expanded);
        for (int i = 0; i < MessageSystem.instance.size(); i++) {
            Message m = MessageSystem.instance.get(i);
            if (!this.visible(m)) continue; // 非本观看者可收的消息（异地队伍/非全局）不渲染
            MessageRow row = this.buildRow(m);
            MessageRow prev = oldByMsg.get(m);
            if (prev != null) {
                row.age = prev.age;
            }
            this.list.addVoidChild(row);
        }
        // 展开切换：把气泡停靠在屏幕左侧（宽度约 75% 时才滑入），避免随面板加宽一起平铺
        if (this.togglePhase == TogglePhase.EXPAND_IN) {
            this.list.parkBubbles(Animated.DURATION * 1.5f);
        }
        this.list.setHeight(panelHeight);
        // 自定义滚动条贴图：tint 引用共享颜色，可整体淡入/淡出滚动条（收起/展开触发点驱动）
        ScrollPane.ScrollPaneStyle style = new ScrollPane.ScrollPaneStyle(Styles.defaultPane);
        if (style.vScroll instanceof TextureRegionDrawable track) {
            style.vScroll = new ScrollbarTintDrawable(track.getRegion(), this.scrollbarTint);
        }
        if (style.vScrollKnob instanceof TextureRegionDrawable knob) {
            style.vScrollKnob = new ScrollbarTintDrawable(knob.getRegion(), this.scrollbarTint);
        }
        this.pane = new ScrollPane(this.list, style);
        // 立即按展开/收起态配置滚动条（收起重建期间不得再出现滚动条）：
        // 展开时左侧始终显示的竖直滚动条会由 pane 内部预留宽度并把内容区整体右移；
        // 先强制排版一次，才能可靠读出滚动条宽度（getScrollBarWidth 依赖 layout 后的 scrollY）。
        this.applyScrollMode();
        this.pane.layout();
        float scrollW = this.expanded ? this.pane.getScrollBarWidth() : 0f;
        // 气泡行宽 = 内容宽 - 右侧 pad(4) - 滚动条宽（左侧滚动条占宽已由 pane 预留并右移内容区）
        this.bubbleWidth = Math.max(0.0f, contentWidth - 4.0f - scrollW);
        this.list.setBubbleWidth(this.bubbleWidth);
        this.scrollModeDelay = 0.27f; // 稍后（真实帧计时，暂停也触发）按展开/收起态应用滚动条配置
        this.add(this.pane).grow().pad(this.expanded ? 4.0f : 2.0f, 4.0f, 4.0f, 4.0f).padBottom(6.0f);
        if (this.expanded) {
            this.expandTime = Time.globalTime;
        }
        // 未读消息数标签：脱离布局、跟随面板右缘平移（每帧在 act 中刷新位置）
        if (this.unreadLabel == null) {
            this.unreadLabel = new Label("", Styles.outlineLabel);
        }
        this.addChild(this.unreadLabel);
        this.setPosition(0.0f, 0.0f);
        this.invalidateHierarchy();
        this.setTransform(true);
        this.clearActions();
        this.actions(Actions.sizeTo(toWidth, panelHeight, Animated.DURATION, Animated.INTERP));
    }

    /** 生成某消息对应的行；已读状态从客户端集合继承。 */
    private MessageRow buildRow(Message m) {
        MessageRow row = new MessageRow(m);
        row.read = this.readMessages.contains(m);
        row.left();
        return row;
    }

    private Label titleLabel() {
        return new Label(Core.bundle.get("message-panel.title"), Styles.outlineLabel);
    }

    /** 在标题栏右侧放展开/收起切换按钮。 */
    private void addToggleButton(Table host, float alpha) {
        TextureRegionDrawable icon = this.expanded ? Icon.leftOpen : Icon.rightOpen;
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle();
        style.up = this.tint(1.0f, 1.0f, 1.0f, 0.2f);
        style.down = this.tint(0.5f, 0.8f, 1.0f, 0.4f);
        style.over = style.up;
        style.imageUp = icon;
        style.imageUpColor = Color.white;
        ImageButton btn = new ImageButton(style);
        btn.resizeImage(14.0f);
        btn.setColor(1.0f, 1.0f, 1.0f, alpha);
        btn.clicked(this::toggle);
        btn.touchable = Touchable.enabled;
        host.add(btn).size(26.0f);
    }

    /** 在面板左侧上方放「清空所有消息」垃圾桶按钮（仅展开态，脱离面板定位）。 */
    private void addTrashButton(float panelHeight) {
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle();
        style.up = this.tint(0.95f, 0.25f, 0.25f, 0.35f);
        style.down = this.tint(1.0f, 0.15f, 0.15f, 0.55f);
        style.over = style.up;
        style.imageUp = Icon.trash;
        style.imageUpColor = Color.white;
        ImageButton btn = new ImageButton(style);
        btn.resizeImage(14.0f);
        btn.setSize(26.0f, 26.0f);
        btn.touchable = Touchable.enabled;
        btn.clicked(() -> {
            // 按下垃圾桶按钮的即时反馈音（先播音效再清空，清空本身会逐条播移除动画）
            SiliconSounds.play("clean-panel");
            MessageSystem.instance.clearAnimated();
        });
        btn.setPosition(2.0f, panelHeight + 6.0f);
        btn.setColor(1.0f, 1.0f, 1.0f, 0.0f);
        btn.actions(Actions.alpha(1.0f, Animated.DURATION, Animated.INTERP));
        this.addChild(btn);
    }

    /** 标题栏颜色：取最顶部（最高优先级）<b>本观看者可收</b>的消息的气泡色，透明度按优先级取固定值。 */
    private Color titleBarColor() {
        Message top = null;
        for (int i = 0; i < MessageSystem.instance.size(); i++) {
            Message m = MessageSystem.instance.get(i);
            if (this.visible(m)) {
                top = m;
                break;
            }
        }
        if (top != null) {
            float alpha = switch (top.priority) {
                case HIGH -> 0.75f;   // 紧急：更不透明
                case MEDIUM -> 0.55f; // 警告：基准透明度
                case LOW -> 0.38f;    // 提示/常规：更透明
            };
            return new Color(top.bubbleColor.r, top.bubbleColor.g, top.bubbleColor.b, alpha);
        }
        return new Color(0.15f, 0.15f, 0.15f, 0.55f);
    }

    private Drawable tintBar(Color tint) {
        return ((TextureRegionDrawable) Tex.whiteui).tint(tint);
    }

    private static Drawable tintStatic(float r, float g, float b, float a) {
        return ((TextureRegionDrawable) Tex.whiteui).tint(new Color(r, g, b, a));
    }

    private Drawable tint(float r, float g, float b, float a) {
        return MessagePanel.tintStatic(r, g, b, a);
    }

    /** 展开/收起切换：两阶段动画（收起：气泡先左滑出屏幕再收缩；展开：面板加宽到约 75% 时气泡左滑入）。 */
    public void toggle() {
        if (this.togglePhase != TogglePhase.NONE) {
            return;
        }
        if (this.expanded) {
            // 收起：气泡向左滑出，移出 75% 时开始收窄面板宽度；完全移出后重建为收起态（小图标出现动画）
            this.togglePhase = TogglePhase.COLLAPSE_OUT;
            this.phaseTime = 0f;
            this.narrowStarted = false;
            this.scrollbarAlphaTarget = 0f; // 收起触发：滚动条淡出（在面板开始收窄前完成）
            this.applyCollapsedScrollMode(); // 气泡一开始滑出即隐藏滚动条
            this.list.beginCollapseSlide();
        } else {
            // 展开：面板加宽，气泡停靠屏幕左侧，宽度约 75% 时滑入
            this.expanded = true;
            this.togglePhase = TogglePhase.EXPAND_IN;
            this.phaseTime = 0f;
            this.expandBubblesStarted = false;
            // 滚动条初始隐藏：等消息开始进入动画时淡入
            this.scrollbarAlpha = 0f;
            this.scrollbarAlphaTarget = 0f;
            this.scrollbarTint.a = 0f;
            this.rebuild();
        }
    }

    @Override
    public void messageAdded(Message msg, int index) {
        if (!this.visible(msg)) return; // 本观看者不可收的消息（异地队伍/非全局）不渲染
        this.playArrivalSound(msg);
        this.list.insertRow(this.visibleIndexOf(msg), this.buildRow(msg));
        this.scrollToTopSoon();
    }

    /** 触发「消息到达」音效：静音消息不播；按消息源设定经 {@link SiliconSounds#playMessage} 播放
     *  自定义音效，未设定回退面板默认 {@code new-message}（自动注册表不可用时静默）。 */
    private void playArrivalSound(Message msg) {
        if (msg == null || msg.silent || Vars.headless) return;
        SiliconSounds.playMessage(msg.sound);
    }

    @Override
    public void messageRemoved(Message msg, int index) {
        MessageRow row = this.findRow(msg);
        if (row == null || row.removing) {
            return;
        }
        row.removing = true;
        row.exitTime = 0.0f;
        row.removedFinished = false;
    }

    @Override
    public void messageTrimmed(Message msg, int index) {
        MessageRow row = this.findRow(msg);
        if (row == null) {
            return;
        }
        this.list.getChildren().remove(row);
        row.remove();
        this.list.invalidateHierarchy();
    }

    @Override
    public void messagesCleared() {
        this.list.clearChildren();
        this.list.setHeight(0.0f);
        this.list.invalidateHierarchy();
        if (this.pane != null) {
            this.pane.invalidate();
            this.pane.invalidateHierarchy();
            this.pane.setScrollYForce(0.0f);
        }
    }

    /** 按消息对象查找其行（不用数据索引映射列表索引：移除动画期间两者的索引会错位）。 */
    private MessageRow findRow(Message msg) {
        for (Element e : this.list.getChildren()) {
            MessageRow row = (MessageRow) e;
            if (row.msg == msg) {
                return row;
            }
        }
        return null;
    }

    /** 手动把某消息标记为已读：收起态点击小方块触发。已读后取消未读高亮并开始时限倒计时（瞬时型消息）。 */
    private void markRead(Message m) {
        if (m == null) {
            return;
        }
        if (this.readMessages.add(m)) {
            MessageRow r = this.findRow(m);
            if (r != null) {
                r.read = true;
            }
        }
    }

    private void scrollToTopSoon() {
        this.scrollToTopPending = true;
    }

    /** 动画常量：收起/展开/插入/移除/行滑动的时长与缓动。 */
    private static final class Animated {
        /** 是否启用面板滑宽过渡。 */
        static final boolean SLIDE = true;
        /** 面板宽度过渡时长（秒）。 */
        static final float DURATION = 0.25f;
        /** 面板宽度过渡缓动。 */
        static final Interp INTERP = Interp.fade;
        /** 是否启用新消息插入动画。 */
        static final boolean INSERT = true;
        /** 插入动画时长（秒）。 */
        static final float INSERT_DURATION = 0.3f;
        /** 插入缓动。 */
        static final Interp INSERT_INTERP = Interp.pow2Out;
        /** 移除动画时长（秒）。 */
        static final float REMOVE_DURATION = 0.25f;
        /** 展开/收起时行级滑动动画时长（秒）。 */
        static final float SLIDE_DURATION = 0.2f;
        /** 展开/收起时行级滑动缓动。 */
        static final Interp SLIDE_INTERP = Interp.pow2Out;
        /** 展开/收起时逐条进入/出现的间隔（秒；6 tick）。 */
        static final float STAGGER = 0.1f;
        /** 新消息插入时，下方消息纵向平滑的响应速度（帧插值因子 = 1-exp(-delta*SPEED)；越大越快到位）。 */
        static final float SHIFT_SPEED = 12f;
        /** 滚动条淡入淡出时长（秒；需在收起收窄开始前 0.15s 内完成淡出）。 */
        static final float SCROLLBAR_FADE = 0.12f;
        /** 未读标签淡入淡出时长（秒）。 */
        static final float UNREAD_FADE = 0.3f;

        private Animated() {
        }
    }

    /**
     * 手动纵向布局的消息列表：每一条 {@link MessageRow} 独立漂浮，每帧向目标位置逼近。
     * 因此多条消息可并发播放插入动画；索引 0 = 顶部（标题下方的最新位）。
     * 展开/收起共用本布局：行仅在「是否显示文字」上不同，收/展时行级滑动切换。
     */
    private static class MessageList extends Group {
        /** 当前是否展开（决定行内文字/图标与滑入动画）。 */
        private boolean expanded = true;
        private static final float ROW_GAP = 4.0f;
        /** 气泡框固定宽度 = 面板完全展开时的内容宽（不随面板收缩变化）。 */
        private float bubbleW = 200f;
        /** 到期回调（一般转发给消息系统触发移除）。 */
        Cons<Message> expireListener;
        /** 移除动画完成回调（真正从列表删除该行）。 */
        Cons<MessageRow> onRemoveFinished;

        private MessageList() {
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            this.reflow(delta);
            if (this.expireListener != null) {
                ArrayList<MessageRow> expired = new ArrayList<>();
                this.tickTimers(delta, expired);
                for (MessageRow r : expired) {
                    this.expireListener.get(r.msg);
                }
            }
            if (this.onRemoveFinished != null) {
                ArrayList<MessageRow> finished = new ArrayList<>();
                for (Element e : this.getChildren()) {
                    MessageRow r = (MessageRow) e;
                    if (r.removing && r.removedFinished) {
                        finished.add(r);
                    }
                }
                for (MessageRow r : finished) {
                    this.onRemoveFinished.get(r);
                }
            }
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
            int i = 0;
            for (Element e : this.getChildren()) {
                MessageRow r = (MessageRow) e;
                // 收起时小图标出现动画逐条间隔 STAGGER 秒（顶部最新一条先出现）
                r.setCollapsed(!expanded, expanded ? 0f : i * Animated.STAGGER);
                i++;
            }
            this.invalidateHierarchy();
        }

        void setBubbleWidth(float w) {
            this.bubbleW = w;
        }

        /** 展开切换：把全部气泡停靠到屏幕左侧（x = -bubbleW），等待宽度约 75% 时再滑入。 */
        void parkBubbles(float holdDuration) {
            for (Element e : this.getChildren()) {
                MessageRow r = (MessageRow) e;
                r.entering = true;
                r.enterTime = -holdDuration;
                r.yassigned = false;
            }
        }

        /** 收起切换：让（仍旧完整）的气泡全部向左滑出屏幕左边缘。 */
        void beginCollapseSlide() {
            for (Element e : this.getChildren()) {
                MessageRow r = (MessageRow) e;
                r.collapsing = true;
                r.collapseTime = 0f;
                r.entering = false;
            }
        }

        /** 展开切换：让已停靠的气泡从屏幕左侧向右按顺序滑入（逐条间隔 STAGGER 秒）。 */
        void beginEnterSlide() {
            int i = 0;
            for (Element e : this.getChildren()) {
                MessageRow r = (MessageRow) e;
                r.entering = true;
                r.enterTime = -i * Animated.STAGGER; // 每条推迟 i*0.2s 再开始滑入，排队进场
                i++;
            }
        }

        boolean enterSlideDone() {
            for (Element e : this.getChildren()) {
                if (((MessageRow) e).entering) return false;
            }
            return true;
        }

        void insertRow(int index, MessageRow row) {
            int at = Mathf.clamp(index, 0, this.getChildren().size);
            this.addChildAt(at, row);
            row.setCollapsed(!this.expanded);
            row.entering = true;
            this.invalidateHierarchy();
        }

        void addVoidChild(MessageRow row) {
            int idx = this.getChildren().size; // 重建时按加入顺序取 index，用于收起动画错峰
            this.addChild(row);
            // 展开态：重建后的新行滑入；收起态：由 setCollapsed 改为滑入动画（覆盖此处的 entering）
            row.entering = this.expanded;
            // 收起态：小图标按 index 错峰从左侧滑入（顶部最新一条先滑）；展开态：无延迟
            row.setCollapsed(!this.expanded, this.expanded ? 0f : idx * Animated.STAGGER);
        }

        void removeRow(int index) {
            if (index < 0 || index >= this.getChildren().size) {
                return;
            }
            this.getChildren().remove(index).remove();
            this.invalidateHierarchy();
        }

        @Override
        public float getPrefHeight() {
            return this.rowsTotal();
        }

        @Override
        public float getPrefWidth() {
            return 40.0f;
        }

        private float rowsTotal() {
            float h = 0.0f;
            for (Element e : this.getChildren()) {
                h += ((MessageRow) e).layoutHeight() + ROW_GAP;
            }
            return h;
        }

        /** 每帧把每行放置到目标位置，并驱动插入/移除/收展滑动动画（气泡固定宽度，不随面板收缩）。 */
        private void reflow(float delta) {
            float contentW = this.bubbleW;
            float inset = this.expanded ? 4.0f : 0.0f; // 气泡起始 x（左侧滚动条已由 pane 内部预留位）
            Seq<MessageRow> rows = new Seq<>();
            for (Element e : this.getChildren()) {
                rows.add((MessageRow) e);
            }
            for (MessageRow r : rows) {
                r.setWidth(Math.max(0.0f, contentW));
                r.validate();
            }
            float total = this.rowsTotal();
            float anchorTop = this.expanded ? Math.max(this.getHeight(), total) : this.getHeight();
            this.setHeight(anchorTop);
            float cursor = anchorTop;
            for (MessageRow r : rows) {
                float h = r.layoutHeight();
                r.setHeight(h);
                r.validate();
                float targetY = cursor - h;
                int state = 0; // 0=静止, 1=移出(到期), 2=滑入(插入/展开), 3=收起滑出
                if (r.removing) {
                    state = 1;
                } else if (r.collapsing) {
                    state = 3;
                } else if (r.entering) {
                    state = 2;
                }
                switch (state) {
                    case 1 -> {
                        // 到期移除：向左滑出屏幕并淡出
                        r.exitTime += delta;
                        float p = Math.min(1.0f, r.exitTime / Animated.REMOVE_DURATION);
                        float e2 = Animated.INSERT_INTERP.apply(p);
                        r.x = inset - (inset + contentW) * e2;
                        r.color.a = 1.0f - e2;
                        if (p >= 1.0f) {
                            r.removedFinished = true;
                        }
                    }
                    case 2 -> {
                        // 插入/展开：从屏幕左侧 (x=-contentW) 向右滑入到位
                        r.enterTime += delta;
                        float p = Mathf.clamp(r.enterTime / Animated.INSERT_DURATION, 0f, 1f);
                        float e2 = Animated.INSERT_INTERP.apply(p);
                        r.x = -contentW + (inset + contentW) * e2;
                        r.color.a = e2;
                        if (p >= 1.0f) {
                            r.entering = false;
                            r.color.a = 1.0f;
                            r.x = inset;
                        }
                    }
                    case 3 -> {
                        // 收起切换：气泡全部向左滑出屏幕左边缘
                        r.collapseTime += delta;
                        float p = Math.min(1.0f, r.collapseTime / Animated.SLIDE_DURATION);
                        float e3 = Animated.SLIDE_INTERP.apply(p);
                        r.x = inset - (inset + contentW) * e3;
                        if (p >= 1.0f) {
                            r.collapsing = false;
                            r.x = -contentW;
                        }
                    }
                    default -> {
                        r.x = inset;
                    }
                }
                // 纵向：平滑逼近目标位（插入新行时下方行被下推、移除后下方行上移都有动画；首帧直接定位）。
                // 使用传入的真实帧 delta（暂停时也能走位），而非 Time.delta。
                if (!r.yassigned) {
                    r.y = targetY;
                    r.yassigned = true;
                } else {
                    float t = 1f - (float) Math.exp(-delta * Animated.SHIFT_SPEED);
                    r.y = Mathf.lerp(r.y, targetY, t);
                    if (Math.abs(r.y - targetY) < 0.01f) r.y = targetY;
                }
                cursor -= h + ROW_GAP;
            }
        }

        @Override
        public void draw() {
            super.draw();
        }

        /** 推进已读且带时限消息的存活倒计时，到点记入 expiredOut。持续型消息不受时限约束（由消息源控制生命周期）。 */
        void tickTimers(float delta, List<MessageRow> expiredOut) {
            for (Element e : this.getChildren()) {
                MessageRow r = (MessageRow) e;
                if (r.msg.type == MessageType.PERSISTENT) continue;
                if (!r.read || !(r.msg.ttl >= 0.0f) || r.expired) continue;
                r.age += delta;
                if (r.age < r.msg.ttl) continue;
                r.expired = true;
                expiredOut.add(r);
            }
        }
    }

    /** 可整体淡入/淡出的滚动条贴图：tint 引用共享颜色，alpha 由面板逐帧驱动。 */
    private static class ScrollbarTintDrawable extends TextureRegionDrawable {
        /** 共享颜色（只有 alpha 会变化，RGB 恒白）。 */
        final Color shared;

        ScrollbarTintDrawable(TextureRegion region, Color shared) {
            super(region);
            this.shared = shared;
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            this.tint.set(this.shared);
            super.draw(x, y, width, height);
        }

        @Override
        public void draw(float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation) {
            this.tint.set(this.shared);
            super.draw(x, y, originX, originY, width, height, scaleX, scaleY, rotation);
        }
    }

    /** 单条消息行：展开 = 完整气泡（标题/内容/图标），收起 = 彩色小方块 + 居中图标。 */
    private static class MessageRow extends Table {
        final Message msg;
        /** 标题标签引用（持续型消息每帧刷新其文本）。 */
        private Label titleLabel;
        /** 内容标签引用（持续型消息每帧刷新其文本）。 */
        private Label contentLabel;
        /** 上次应用的气泡填充色（持续型消息实时刷新用，避免每帧重建）。 */
        private Color lastFill;
        /** 是否为收起态（仅显示小方块）。 */
        private boolean collapsed = false;
        /** 正在播放插入（从左侧滑入淡入）动画。 */
        boolean entering = false;
        /** 是否已分配过纵向位置（首帧定位，后续由 reflow 平滑逼近）。 */
        boolean yassigned = false;
        /** 插入动画已播放时长（秒，delta 累加）。 */
        float enterTime = 0.0f;
        /** 是否已读（客户端视图状态）。 */
        boolean read = false;
        /** 已存活时长（秒，delta 累加；仅已读时限消息推进）。 */
        float age = 0.0f;
        /** 是否已到期（等待真正移除）。 */
        boolean expired = false;
        /** 正在播放移除（向左滑出淡出）动画。 */
        boolean removing = false;
        /** 移除动画已播放时长（秒，delta 累加）。 */
        float exitTime = 0.0f;
        /** 移除动画完成，等待列表本次回调真正删除。 */
        boolean removedFinished = false;
        /** 正在播放收起切换动画（气泡向左滑出屏幕左边缘）。 */
        boolean collapsing = false;
        /** 收起切换动画已播放时长（秒）。 */
        float collapseTime = 0.0f;
        /** 收起态悬停提示（仅收起态触发，样式参考游戏内选项的悬浮文本提示）。 */
        private Tooltip tooltip;
        /** 悬停提示中的标题标签（持续型消息标题实时变化时同步刷新）。 */
        private Label tooltipLabel;
        /** 收起态铺满小方块的交互按钮：透明底，承载悬停提示与「点击=已读」。逐帧对齐方块边界。 */
        private Button squareBtn;

        MessageRow(Message msg) {
            this.msg = msg;
            this.setTransform(true);
            // 先建 tooltip，后重建内容：收起态重建需要把 tooltip 挂到按钮上
            this.setupTooltip();
            this.rebuildContent();
        }

        /** 创建收起态悬停提示：光标放在小方块上即显示该消息标题。 */
        private void setupTooltip() {
            this.tooltip = new Tooltip(t -> {
                t.background(Styles.black8);
                t.touchable = Touchable.disabled;
                t.margin(4.0f);
                String title = this.msg.currentTitle();
                this.tooltipLabel = new Label(title != null ? title : "", Styles.outlineLabel);
                this.tooltipLabel.setColor(this.msg.titleColor);
                this.tooltipLabel.setWrap(true);
                t.add(this.tooltipLabel).width(280.0f).left();
            }) {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    // 不消费点击：避免吞掉消息行的 onClick
                    return false;
                }

                @Override
                protected void setContainerPosition(Element element, float x, float y) {
                    // 与 UI$3 一致：把提示锚定到目标元素左上角（排除鼠标跟随定位导致的显示问题）
                    this.targetActor = element;
                    if (element.getScene() == null) {
                        return;
                    }
                    this.container.pack();
                    arc.math.geom.Vec2 pos = element.localToStageCoordinates(new arc.math.geom.Vec2().set(0.0f, 0.0f));
                    this.container.setPosition(pos.x, pos.y, arc.util.Align.topLeft);
                    this.container.setOrigin(0.0f, element.getHeight());
                }

                @Override
                public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
                    // 仅收起态（小方块）悬停时显示；移除/到期中的行不提示
                    if (!MessageRow.this.collapsed || MessageRow.this.removing || MessageRow.this.expired) {
                        return;
                    }
                    super.enter(event, x, y, pointer, fromActor);
                }

                @Override
                public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
                    super.exit(event, x, y, pointer, toActor);
                }
            };
        }

        @Override
        public boolean remove() {
            // 行被移除/重建时若提示仍显示则立即隐藏（exit 事件不一定触发）
            if (this.tooltip != null) {
                this.tooltip.hide();
            }
            return super.remove();
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            // 收起态：非单元格子项不受 Table 布局管理，逐帧对齐方块位置与尺寸，保证按钮与小方块完全一致
            if (this.collapsed && this.squareBtn != null) {
                this.squareBtn.setPosition(0.0f, 0.0f);
                this.squareBtn.setSize(this.getWidth(), this.getHeight());
            }
            // 持续型消息：内容/属性由消息源提供，每帧刷新以支持实时数据更新
            if (this.msg.type == MessageType.PERSISTENT) {
                this.refreshLive();
            }
            // 收起态：同步刷新悬停提示标题（持续型消息标题可实时变化）
            if (this.collapsed && this.tooltipLabel != null) {
                String t = this.msg.currentTitle();
                if (t != null && !t.equals(this.tooltipLabel.getText().toString())) {
                    this.tooltipLabel.setText(t);
                }
            }
        }

        /** 持续型消息实时同步：每帧比较并应用消息源对标题/内容/颜色/图标的最新修改。 */
        private void refreshLive() {
            if (this.collapsed) return;
            boolean changed = false;
            if (this.titleLabel != null) {
                String t = this.msg.currentTitle();
                if (t != null && !t.equals(this.titleLabel.getText().toString())) {
                    this.titleLabel.setText(t);
                    changed = true;
                }
            }
            String c = this.msg.currentContent();
            if (this.contentLabel != null && c != null && !c.equals(this.contentLabel.getText().toString())) {
                this.contentLabel.setText(c);
                changed = true;
            }
            // 按实际显示颜色（bubbleColor + 时限折半）比较，避免每帧因新 Drawable 实例而重建
            Color want = this.bubbleFillColor();
            if (this.lastFill == null || !sameColor(this.lastFill, want)) {
                this.background(tintStatic(want.r, want.g, want.b, want.a));
                this.lastFill = want;
                changed = true;
            }
            if (changed) {
                this.invalidateHierarchy();
            }
        }

        /** 颜色是否逐通道近似相等（避免每帧微小浮点抖动触发重建）。 */
        private static boolean sameColor(Color a, Color b) {
            return Math.abs(a.r - b.r) < 0.001f && Math.abs(a.g - b.g) < 0.001f && Math.abs(a.b - b.b) < 0.001f && Math.abs(a.a - b.a) < 0.001f;
        }

        @Override
        public void draw() {
            this.validate();
            if (this.isTransform()) {
                this.applyTransform(this.computeTransform());
                this.drawBackground(0.0f, 0.0f);
                this.drawCountdown();
                if (!this.read) {
                    this.drawUnreadHighlight();
                }
                this.drawChildren();
                this.resetTransform();
            } else {
                super.draw();
            }
        }

        /** 气泡填充色：时限消息透明度折半（让倒计时条更醒目）。 */
        private Color bubbleFillColor() {
            float a = this.msg.bubbleColor.a * (this.msg.ttl >= 0.0f ? 0.5f : 1.0f);
            return new Color(this.msg.bubbleColor.r, this.msg.bubbleColor.g, this.msg.bubbleColor.b, a);
        }

        /** 画时限倒计时条：展开为满高条、收起为底部细条（仅已读瞬时型时限消息；持续型不受时限约束）。 */
        private void drawCountdown() {
            if (this.msg.type == MessageType.PERSISTENT || this.msg.ttl < 0.0f || !this.read || this.expired) {
                return;
            }
            float alpha = this.color.a * this.parentAlpha;
            if (alpha <= 0.0f) {
                return;
            }
            float frac = Mathf.clamp(1.0f - this.age / this.msg.ttl, 0.0f, 1.0f);
            float remW = this.getWidth() * frac;
            if (remW <= 0.5f) {
                return;
            }
            float tintA = this.msg.bubbleColor.a * 0.5f;
            Draw.color(this.msg.overlayColor.r, this.msg.overlayColor.g, this.msg.overlayColor.b, alpha * tintA);
            float barH = this.collapsed ? 4.0f : this.getHeight();
            float barY = this.collapsed ? barH / 2.0f : this.getHeight() / 2.0f;
            Draw.rect(Core.atlas.white(), remW / 2.0f, barY, remW, barH);
            Draw.color();
        }

        /** 画未读高亮：收起 = 白边框，展开 = 消息自身色边框。 */
        private void drawUnreadHighlight() {
            float w = this.getWidth();
            float h = this.getHeight();
            if (w <= 0.5f || h <= 0.5f) {
                return;
            }
            float alpha = this.color.a * this.parentAlpha * 0.6f;
            if (alpha <= 0.01f) {
                return;
            }
            float edge = 2.5f;
            if (this.collapsed) {
                Draw.color(1.0f, 1.0f, 1.0f, alpha);
            } else {
                Draw.color(this.msg.bubbleColor.r, this.msg.bubbleColor.g, this.msg.bubbleColor.b, alpha);
            }
            Draw.rect(Core.atlas.white(), w / 2.0f, edge / 2.0f, w, edge);
            Draw.rect(Core.atlas.white(), w / 2.0f, h - edge / 2.0f, w, edge);
            Draw.rect(Core.atlas.white(), edge / 2.0f, h / 2.0f, edge, h);
            Draw.rect(Core.atlas.white(), w - edge / 2.0f, h / 2.0f, edge, h);
            Draw.color();
        }

        void setCollapsed(boolean collapsed) {
            this.setCollapsed(collapsed, 0f);
        }

        void setCollapsed(boolean collapsed, float appearDelay) {
            if (this.collapsed == collapsed) {
                return;
            }
            this.collapsed = collapsed;
            this.rebuildContent();
            // 从展开转为收起：小图标改为「从左侧滑入」的平移动画（与插入动画一致，逐条错峰 appearDelay 秒）
            if (collapsed) {
                this.entering = true;
                this.enterTime = -appearDelay;
                this.color.a = 0f;
            } else {
                this.entering = false;
                this.enterTime = 0f;
                this.color.a = 1f;
                // 切回展开态：收起小方块的悬停提示已无用，立即隐藏
                this.tooltip.hide();
            }
        }

        /** 重建行内内容：收起 = 居中图标；展开 = 标题/内容 + 右侧图标。 */
        private void rebuildContent() {
            this.clear();
            this.squareBtn = null;
            this.titleLabel = null;
            this.contentLabel = null;
            this.lastFill = null;
            if (this.collapsed) {
                // 收起态：方块底色画在行上，图标与交互由铺满整方格的按钮负责（非单元格子项，逐帧对齐方块位置/尺寸）。
                // 按钮承载悬停提示（Tooltip 挂按钮上，与标题栏切换按钮同一机制）与「点击=已读」。
                this.background(tintStatic(this.msg.bubbleColor.r, this.msg.bubbleColor.g, this.msg.bubbleColor.b, this.msg.bubbleColor.a));
                Button.ButtonStyle style = new Button.ButtonStyle();
                style.up = tintStatic(1.0f, 1.0f, 1.0f, 0.0f);     // 平时透明，露出方块底色
                style.over = tintStatic(1.0f, 1.0f, 1.0f, 0.22f);  // 悬停微微提亮，兼作 hover 反馈
                style.down = tintStatic(1.0f, 1.0f, 1.0f, 0.35f);
                this.squareBtn = new Button(style);
                this.squareBtn.touchable = Touchable.enabled;
                this.squareBtn.clicked(() -> MessagePanel.instance().markRead(MessageRow.this.msg));
                if (MessageRow.this.tooltip != null) {
                    this.squareBtn.addListener(MessageRow.this.tooltip);
                }
                this.addChild(this.squareBtn);
                this.squareBtn.add(new Image(this.msg.icon)).size(ICON_SIZE).center();
            } else {
                Color fill = this.bubbleFillColor();
                this.background(tintStatic(fill.r, fill.g, fill.b, fill.a));
                this.lastFill = fill;
                this.margin(6.0f, 8.0f, 6.0f, 8.0f);
                Table text = new Table();
                text.left().top();
                this.titleLabel = new Label(this.msg.currentTitle(), Styles.outlineLabel);
                this.titleLabel.setColor(this.msg.titleColor);
                this.titleLabel.setWrap(true);
                this.titleLabel.setFontScale(this.msg.titleScale);
                text.add(this.titleLabel).growX().left().wrap().row();
                String content = this.msg.currentContent();
                if (this.msg.contentProvider != null || (content != null && !content.isEmpty())) {
                    this.contentLabel = new Label(content == null ? "" : content, Styles.outlineLabel);
                    this.contentLabel.setColor(this.msg.contentColor);
                    this.contentLabel.setWrap(true);
                    this.contentLabel.setFontScale(this.msg.contentScale);
                    text.add(this.contentLabel).growX().left().wrap().padTop(2.0f);
                }
                this.add(text).growX().left().padRight(8.0f);
                this.image(this.msg.icon).size(ICON_SIZE).right();
            }
            if (this.msg.onClick != null) {
                this.clicked(this.msg.onClick);
            }
        }

        /** 行高：收起 = 宽度（正方形）；展开 = 内容自然高度（下限 34）。 */
        float layoutHeight() {
            if (this.collapsed) {
                return this.getWidth();
            }
            return Math.max(34.0f, this.getPrefHeight());
        }
    }

    private static class SiliconMessagePanelHolder {
        static volatile MessagePanel panel;

        private SiliconMessagePanelHolder() {
        }
    }
}
