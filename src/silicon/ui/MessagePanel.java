package silicon.ui;

import silicon.util.Message;
import silicon.util.MessageSystem;
import silicon.util.MessageType;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Tmp;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

import static mindustry.Vars.*;

/**
 * 消息面板 UI：在屏幕左侧显示消息列表，支持展开/收起。
 * <p>
 * 收起状态：显示一个小方块按钮，颜色为当前最高等级消息的颜色。
 * 展开状态：显示完整消息面板，包含标题栏和消息列表。
 * <p>
 * 用法：
 * <pre>
 *   MessagePanel.create();  // 在 ClientLoadEvent 中调用
 * </pre>
 */
public class MessagePanel {

    /** 面板宽度（展开状态） */
    private static final float PANEL_WIDTH = 320f;
    /** 面板最大高度（占屏幕比例） */
    private static final float MAX_HEIGHT_RATIO = 0.6f;
    /** 收起按钮尺寸 */
    private static final float BUTTON_SIZE = 48f;
    /** 消息项高度 */
    private static final float ITEM_HEIGHT = 60f;
    /** 动画速度 */
    private static final float ANIM_SPEED = 0.15f;

    /** 面板根容器 */
    private static Table panel;
    /** 收起按钮 */
    private static Table collapseButton;
    /** 消息列表容器 */
    private static Table messageList;
    /** 滚动面板 */
    private static ScrollPane scrollPane;

    /** 当前展开状态 */
    private static boolean expanded = false;
    /** 动画进度（0=收起, 1=展开） */
    private static float animProgress = 0f;
    /** 当前最高等级消息颜色 */
    private static Color currentColor = new Color(Color.white);

    private MessagePanel() {}

    /**
     * 创建并显示消息面板（在游戏 HUD 中）。
     * 应在 ClientLoadEvent 中调用。
     */
    public static void create() {
        if (Core.scene == null) return;

        // 初始化面板容器
        panel = new Table();
        panel.touchable = Touchable.childrenOnly;

        // 收起按钮（默认显示）
        collapseButton = createCollapseButton();
        panel.add(collapseButton).size(BUTTON_SIZE);

        // 消息面板（初始隐藏）
        Table contentPanel = createContentPanel();
        panel.add(contentPanel).width(PANEL_WIDTH).height(0f).padLeft(8f);

        // 添加到 HUD
        Vars.ui.hudGroup.addChild(panel);
        panel.setPosition(0f, Core.graphics.getHeight() / 2f, Align.left);

        // 监听消息
        MessageSystem.addListener(MessagePanel::onMessage);

        // 更新循环
        arc.Events.run(mindustry.game.EventType.Trigger.update, MessagePanel::update);
    }

    /**
     * 创建收起按钮。
     */
    private static Table createCollapseButton() {
        Table btn = new Table();
        btn.background(Tex.whitePane);
        btn.setColor(0.2f, 0.2f, 0.2f, 0.8f);
        btn.touchable = Touchable.enabled;

        // 中间的小方块（显示最高等级颜色）
        Table indicator = new Table();
        indicator.background(Tex.whitePane);
        indicator.setColor(currentColor);
        btn.add(indicator).size(BUTTON_SIZE * 0.5f);

        btn.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                toggle();
            }
        });

        return btn;
    }

    /**
     * 创建展开后的内容面板。
     */
    private static Table createContentPanel() {
        Table container = new Table();
        container.background(Tex.whitePane);
        container.setColor(0.15f, 0.15f, 0.15f, 0.7f);

        // 标题栏
        Table titleBar = createTitleBar();
        container.add(titleBar).growX().height(40f).pad(4f);
        container.row();

        // 消息列表
        messageList = new Table();
        messageList.top();
        messageList.defaults().growX().height(ITEM_HEIGHT).pad(4f);

        scrollPane = new ScrollPane(messageList, Styles.largePane);
        scrollPane.setScrollingDisabled(true, false);
        container.add(scrollPane).grow().pad(4f);

        return container;
    }

    /**
     * 创建标题栏。
     */
    private static Table createTitleBar() {
        Table titleBar = new Table();
        titleBar.background(Tex.whitePane);
        titleBar.setColor(0.25f, 0.25f, 0.25f, 0.9f);

        // 标题文本
        Label title = new Label("硅 - 消息面板", Styles.outlineLabel);
        title.setColor(Color.white);
        title.setFontScale(0.9f);
        titleBar.add(title).growX().left().padLeft(8f);

        // 菱形图标（展开时变为不透明）
        Table diamond = createDiamondIcon();
        titleBar.add(diamond).size(32f).padRight(8f);

        // 点击标题栏可收起
        titleBar.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                toggle();
            }
        });

        return titleBar;
    }

    /**
     * 创建菱形图标。
     */
    private static Table createDiamondIcon() {
        Table diamond = new Table();
        diamond.background(Tex.whitePane);
        diamond.setColor(1f, 0.3f, 0.3f, 0.5f);  // 半透明红色
        return diamond;
    }

    /**
     * 切换展开/收起状态。
     */
    public static void toggle() {
        expanded = !expanded;
    }

    /**
     * 展开面板。
     */
    public static void expand() {
        expanded = true;
    }

    /**
     * 收起面板。
     */
    public static void collapse() {
        expanded = false;
    }

    /**
     * 更新动画和显示。
     */
    private static void update() {
        if (panel == null) return;

        // 更新动画进度
        float target = expanded ? 1f : 0f;
        animProgress = Mathf.lerpDelta(animProgress, target, ANIM_SPEED * 60f * Core.graphics.getDeltaTime());

        // 更新收起按钮颜色（显示最高等级消息颜色）
        collapseButton.setColor(Tmp.c1.set(currentColor).a(0.2f + 0.6f * (1f - animProgress)));

        // 更新面板高度
        float maxHeight = Core.graphics.getHeight() * MAX_HEIGHT_RATIO;
        float targetHeight = expanded ? maxHeight : 0f;
        float currentHeight = panel.getChildren().get(1).get_HEIGHT();
        float newHeight = Mathf.lerp(currentHeight, targetHeight, ANIM_SPEED * 60f * Core.graphics.getDeltaTime());

        // 更新面板尺寸和位置
        panel.getCells().get(1).height(newHeight);
        panel.invalidate();
    }

    /**
     * 收到新消息时的回调。
     */
    private static void onMessage(Message message) {
        // 更新最高等级颜色
        updateHighestColor(message.getType());

        // 如果面板展开，添加消息到列表
        if (messageList != null) {
            Table item = createMessageItem(message);
            messageList.add(item).row();
            messageList.invalidateHierarchy();

            // 滚动到底部
            if (scrollPane != null) {
                scrollPane.setScrollPercentY(1f);
            }
        }
    }

    /**
     * 更新最高等级消息颜色。
     * 优先级：ERROR > WARNING > SUCCESS > INFO
     */
    private static void updateHighestColor(MessageType type) {
        Color newColor = type.color;
        // 简单优先级判断：ERROR 最高，INFO 最低
        if (type == MessageType.ERROR) {
            currentColor.set(newColor);
        } else if (type == MessageType.WARNING && currentColor != MessageType.ERROR.color) {
            currentColor.set(newColor);
        } else if (type == MessageType.SUCCESS && currentColor == MessageType.INFO.color) {
            currentColor.set(newColor);
        } else if (currentColor.r == 0.5f && currentColor.g == 0.5f && currentColor.b == 0.5f) {
            // 初始灰色，替换为新颜色
            currentColor.set(newColor);
        }
    }

    /**
     * 创建单条消息项。
     */
    private static Table createMessageItem(Message message) {
        Table item = new Table();
        item.background(Tex.whitePane);

        // 根据消息类型设置边框颜色
        Color borderColor = message.getType().color;
        item.setColor(borderColor);

        // 消息文本
        Label label = new Label(message.getText(), Styles.outlineLabel);
        label.setColor(Color.white);
        label.setFontScale(0.85f);
        label.setWrap(true);
        label.setAlignment(Align.left | Align.top);
        item.add(label).grow().pad(8f);

        // 右侧图标
        Table icon = createMessageIcon(message.getType());
        item.add(icon).size(48f).padRight(8f);

        return item;
    }

    /**
     * 创建消息类型图标。
     */
    private static Table createMessageIcon(MessageType type) {
        Table icon = new Table();
        icon.background(Tex.whitePane);

        switch (type) {
            case ERROR:
                // 红色三角形（向上）
                icon.setColor(1f, 0.3f, 0.3f, 0.9f);
                // 使用 Image 或自定义绘制
                break;
            case WARNING:
                // 黄色倒三角形
                icon.setColor(1f, 0.85f, 0.4f, 0.9f);
                break;
            case SUCCESS:
                // 绿色圆形
                icon.setColor(0.4f, 0.9f, 0.6f, 0.9f);
                break;
            case INFO:
            default:
                // 蓝色方形
                icon.setColor(0.4f, 0.7f, 0.9f, 0.9f);
                break;
        }

        return icon;
    }

    /**
     * 清空消息列表。
     */
    public static void clearMessages() {
        if (messageList != null) {
            messageList.clearChildren();
        }
        MessageSystem.clear();
        currentColor.set(Color.gray);
    }

    /**
     * 获取当前展开状态。
     */
    public static boolean isExpanded() {
        return expanded;
    }
}
