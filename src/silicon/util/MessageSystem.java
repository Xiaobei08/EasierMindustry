package silicon.util;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.func.Prov;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.style.Drawable;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
/*
* 重点！API调用须知！
* 所有来自模组内固定消息内容需要引用本地化键值，请不要将消息文本写成硬编码！
* */
/**
 * 游戏内消息系统的唯一入口：负责消息的<b>管理</b>（增删、清空、上限剔除、时限到期），
 * 与具体显示载体完全解耦。面板只作为「视图」订阅本类的变更通知来向玩家呈现，
 * 不再自行持有或修改消息数据。
 * <p>
 * 状态为单例（{@link #instance}）。通过 {@link #setListener(Listener)} 注册监听方（当前为
 * {@code silicon.ui.MessagePanel} 与 {@link MessageSync} 联网广播），每次数据变更都会以回调通知：
 * 面板据此呈现，联网广播据此把增删/剔除/清空/持续型内容刷新分发给各客户端。
 * <p>
 * 多人游戏：本系统在<b>权威进程</b>（服务器/单机）上接收消息源投递并分配跨进程稳定 uid；
 * 客户端进程不跑世界逻辑，由 {@link MessageSync} 按 uid 应用服务器广播构建镜像（同队/全局过滤发生在服务端广播时）。
 * <p>
 * 消息模型为 {@link Message}（public 嵌套），可经 {@link #newMessage(String, String)} 创建并以链式方法
 * 配置标题颜色、图标、气泡底色、点击回调、时限与覆盖层颜色等。
 */
public class MessageSystem {
    /** 消息行上限设置键 */
    public static final String SET_MAX_MESSAGES = "message-panel.maxMessages";
    /** 消息行上限默认值（防内存无限增长） */
    public static final int DEFAULT_MAX_MESSAGES = 40;
    /** 消息行上限可调范围 */
    public static final int MIN_MAX_MESSAGES = 20;
    public static final int MAX_MAX_MESSAGES = 100;

    /** 单例 */
    public static final MessageSystem instance = new MessageSystem();

    /** 优先级：同一组内保持最新在上；较高优先级始终排在较低优先级之上。 */
    public enum Priority {
        HIGH, MEDIUM, LOW
    }

/**
     * 消息类型分类：
     * <ul>
     *   <li>{@link #TRANSIENT} 瞬时（脉冲式）：当前默认行为——投递到面板后，由<b>每个玩家</b>
     *       在自己面板上按 {@link Message#ttl} 到期（已读后）、上限剔除或手动清空而消失，互不影响。</li>
     *   <li>{@link #PERSISTENT} 持续型：由消息源持有生命周期——在系统中占位，直到消息源显式
     *       {@link Message#handshake} 断开（或系统探测到消息源失联）才会消失；
     *       不参与超上限剔除、也不受清空影响；支持由消息源提供的实时数据更新（{@link Message#contentProvider}）。</li>
     * </ul>
     */
    public enum MessageType {
        /** 瞬时（脉冲式）：投递后按 TTL 到期自动销毁，可被清空/剔除。 */
        TRANSIENT,
        /** 持续型：由消息源控制生命周期，占位直到消息源断开握手机制/失联；忽略 TTL；不参与剔除与清空。 */
        PERSISTENT
    }

    /**
     * 消息源与消息系统之间的<b>握手连接</b>：消息源在投递持续型消息时挂上一个握手，
     * 由消息源主动调用 {@link #disconnect()} 断开，或提供 {@link #probe} 探活器让系统定期探测。
     * <p>
     * 系统每帧 {@link #sweepDisconnected()}：握手已断开、或探活器判定消息源已失联
     * （例如对应方块被拆除、实体被移除）的持续型消息，会被自动清除（走正常移除动画）。
     * <p>
     * 消息源只需断开连接即可清除消息，无需（也不能）经由面板垃圾桶或超上限剔除来移除。
     */
    public static class Handshake {
        /** 主动断开标记（消息源调用 {@link #disconnect()} 置 false）。 */
        private volatile boolean connected = true;
        /** 探活器：判定消息源是否仍然存活（null = 不探测，仅依赖主动断开）。 */
        private final Prov<Boolean> probe;

        /** 仅靠主动断开的握手（消息源必须记得调用 {@link #disconnect()}）。 */
        public Handshake() {
            this(null);
        }

        /**
         * 带探活器的握手。探活器每帧被系统询问：返回 false 视为消息源失联（如绑定方块的
         * {@code isAdded()/isValid()} 在拆除后变为 false），系统随即清除该持续型消息。
         *
         * @param probe 消息源存活探活器；null = 不探测
         */
        public Handshake(Prov<Boolean> probe) {
            this.probe = probe;
        }

        /** 消息源主动断开握手：系统下一次扫描时清除对应持续型消息。 */
        public void disconnect() {
            this.connected = false;
        }

        /** 握手是否仍然连通（主动未断开 且 探活器判定存活）。 */
        public boolean isConnected() {
            return this.connected && (this.probe == null || this.probe.get());
        }
    }

    // —— 4 个预设模板的颜色（气泡底色，覆盖层自动采用同色） ——
    private static final Color COLOR_EMERGENCY = new Color(0.95f, 0.26f, 0.26f, 0.55f); // 紧急：红
    private static final Color COLOR_WARNING = new Color(1f, 0.82f, 0.22f, 0.55f);       // 警告：黄
    private static final Color COLOR_INFO = new Color(0.34f, 0.6f, 1f, 0.55f);           // 提示：蓝
    private static final Color COLOR_REGULAR = new Color(0.8f, 0.8f, 0.8f, 0.5f);        // 常规：灰白

    static {
        // 世界加载时清空历史消息
        Events.on(EventType.WorldLoadEvent.class, e -> instance.clear());
        // 全局 tick：
        //  - 仅专用托管服务器（无面板）：中继缓冲按瞬时消息 TTL 到期静默淘汰，仅供迟到玩家补发缓存，
        //    绝不会把淘汰广播给在线玩家（各玩家面板的瞬时消息由各自客户端独立清理）。
        //  - 全部进程：失联持续型消息（握手机制）清扫（客户端镜像无握手，为无操作）。
        Events.run(EventType.Trigger.update, () -> {
            if (isLifecycleAuthority()) instance.tickLifecycle();
            instance.sweepDisconnected();
        });
    }

    /** 已收录的消息（源数据，与渲染行解耦；收起态也用它来显示最新图标）。 */
    private final Seq<Message> data = new Seq<>();
    /** 视图/同步监听器（消息面板 + 联网广播）。 */
    private final Seq<Listener> listeners = new Seq<>();
    /** 跨进程稳定消息 ID 计数器（仅权威进程分配）。 */
    private long nextUid = 1;

    /** 数据变更通知接口：把「消息发生了什么」告知视图，由视图自行决定如何呈现。 */
    public interface Listener {
        /** 一条消息插入到指定显示位置（0 = 顶部最新）。 */
        default void messageAdded(Message msg, int index) {}
        /** 一条消息被移除（触发视图移除动画；数据已从本系统中删除）。 */
        default void messageRemoved(Message msg, int index) {}
        /** 一条消息因超上限被从底部（最旧）剔除（视图应立即删除，无动画）。 */
        default void messageTrimmed(Message msg, int index) {}
        /** 全部消息被清空。 */
        default void messagesCleared() {}
    }

    /** 注册视图/同步监听（可多个；消息面板与联网广播共用一套变更通知）。 */
    public void setListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    /** 追加一个监听（等价于 {@link #setListener}，供多名监听方使用）。 */
    public void addListener(Listener l) {
        setListener(l);
    }

    /** 本进程是否为消息 ID 分配权威（服务器或单机）。联网纯客户端仅应用服务器广播，不给本地消息分配 uid。
     *  @apiNote 内部联网用 */
    public static boolean isAuthoritative() {
        return Vars.net == null || !Vars.net.active() || Vars.net.server();
    }

    /**
     * 是否为「生命周期推进权威」：仅<b>专用托管服务器</b>（有服务器但无本地面板）。
     * 房主与单机不在此列——瞬时消息的生命周期由各个玩家自己的面板本地管理（已读后按 TTL 到期、
     * 面板上限剔除、手动清空），互不影响；专用服务器没有面板，这里只做中继缓冲的静默淘汰。
     * @apiNote 内部联网用
     */
    public static boolean isLifecycleAuthority() {
        // 房主的 net.client() 与 net.server() 同源（本 fork 中 client()==!server()&&active()），
        // 仅用 server&&!client 会把房主误判为生命周期权威；必须再排除非 headless（房主有本地面板，
        // 瞬时消息生命周期由面板本地管理，与成员一致）。
        return Vars.headless && Vars.net != null && Vars.net.server();
    }

    /** 按显示索引读取一条消息（0 = 顶部最新）。 */
    public Message get(int index) {
        return data.get(index);
    }

    /** 当前消息总数。 */
    public int size() {
        return data.size;
    }

    /** 底层消息表（只读使用；勿直接修改，以免绕过变更通知）。 */
    public Seq<Message> all() {
        return data;
    }

    /**
     * 追加一条消息（消息源投递的<b>唯一入口</b>）。
     * <p>
     * 提交后按 {@link Message#priority} 自动插入其分组顶端（同组内最新在上），并触发全部监听方
     * （本地面板即时呈现；联网层把消息分发给所有在线的可见玩家）。
     * <p>
     * <b>多人游戏注意</b>：消息源通常运行在服务器端（方块/实体逻辑只在服务器执行）。
     * 服务器/房主上调用本方法会把消息分发给各在线玩家；在<b>纯客户端</b>上调用仅影响本地面板，
     * 不会被联网层分发。徒时消息投递后由每个玩家在自己的面板上独立清理，互不影响。
     *
     * @param m 经 {@link #newMessage(String, String)} 或模板方法构建的消息
     */
    public void add(Message m) {
        // 权威进程（服务器/单机）为消息分配跨进程稳定 ID 并记录投递时刻（供专用服务器中继缓冲 TTL 淘汰）
        if (m.uid < 0 && isAuthoritative()) m.uid = nextUid++;
        m.addedAt = Time.time;
        int at = insertIndexFor(m.priority);
        data.insert(at, m);
        for (Listener l : listeners) l.messageAdded(m, at);
        // 剔除超上限的（持续型不参与剔除）；联网环境下本进程按自己的上限本地剔除，不影响他人
        trimOverflow();
    }

    /**
     * 发布消息的便捷入口：等同 {@link #add(Message)}，但返回消息本身，便于消息源保存句柄
     * 以便后续操作——例如对持续型消息调用 {@code handle.handshake.disconnect()} 撤销投递，
     * 或经 {@link #remove(Message)} 直接移除。
     * <p>
     * 典型用法：
     * <pre>{@code
     * // 一次性瞬时消息
     * MessageSystem.post(MessageSystem.info("tooltip", "jobTagline"));
     *
     * // 持续型消息：保存返回的句柄，稍后撤销
     * Message m = MessageSystem.post(MessageSystem.persistent(
     *         Core.bundle.get("job.title"), () -> currentStateText));
     * // ... 消息源失联/被拆除时：
     * m.handshake.disconnect();
     * }</pre>
     *
     * @param m 待发布的消息
     * @return 传入的消息本身（可保存引用）
     * @see #add(Message)
     * @see #remove(Message)
     */
    public Message post(Message m) {
        add(m);
        return m;
    }

    /** 计算某优先级消息应插入的显示位置：其分组顶端（在该优先级所有更高优先级之后、同组旧消息之前）。 */
    private int insertIndexFor(Priority p) {
        int at = 0;
        while (at < data.size && data.get(at).priority.ordinal() < p.ordinal()) at++;
        return at;
    }

    /** 按对象移除一条消息（若存在）。数据立即删除，视图播放移除动画。
     *  <p>持续型消息由本方法移除时，联网层会同步给所有在线玩家（消息源撤销投递的标准方式之一）。
     *  <p>瞬时时消息通常走面板本地清理（已读到期/剔除/清空），无需手动调用本方法。</p> */
    public void remove(Message m) {
        int i = data.indexOf(m);
        if (i >= 0) removeAt(i);
    }

    /** 按显示索引移除一条消息（0 = 顶部最新）。数据立即删除，视图播放移除动画。 */
    public void removeAt(int index) {
        if (index < 0 || index >= data.size) return;
        Message m = data.remove(index);
        for (Listener l : listeners) l.messageRemoved(m, index);
    }

    /** 清除已失去握手连接的持续型消息：握手已断开或探活器判定消息源失联的会被移除（视图播放消失动画）。
     * 由全局 tick 每帧驱动（暂停也触发），无需消息源或面板手动调用。 */
    public void sweepDisconnected() {
        for (int i = data.size - 1; i >= 0; i--) {
            Message m = data.get(i);
            if (m.type == MessageType.PERSISTENT && m.handshake != null && !m.handshake.isConnected()) {
                removeAt(i);
            }
        }
    }

    /** 清空全部消息（视图同步清空，无动画）。 */
    public void clear() {
        data.clear();
        for (Listener l : listeners) l.messagesCleared();
    }

    /**
     * 清空面板可视范围内的形时消息（垃圾桶按钮用）：逐条触发移除通知（视图播放每条消息的消失动画）。
     * <p><b>持续型消息除外</b>——它们由消息源控制生命周期，不受面板清空影响。
     * <p><b>多人模式</b>：本方法只清空<b>操作者本地</b>的面板（瞬时消息生命周期本就是各玩家本地管理），
     * 不会影响其他玩家的面板。
     * 从最旧的（底部）开始移除，保证顺序移除时不会因索引移位而跳过消息。
     */
    public void clearAnimated() {
        for (int i = data.size - 1; i >= 0; i--) {
            if (data.get(i).type == MessageType.PERSISTENT) continue;
            removeAt(i);
        }
    }

    /** 把数据裁剪到不超过上限：逐条从底部（最旧）剔除，并即时通知视图。持续型消息不参与剔除（由消息源控制生命周期）。 */
    private void trimOverflow() {
        while (data.size > maxMessagesSetting()) {
            // 从底部向上找非持续型消息剔除；若全是持续型则停止（它们不被上限约束）
            int idx = data.size - 1;
            while (idx >= 0 && data.get(idx).type == MessageType.PERSISTENT) idx--;
            if (idx < 0) return;
            Message removed = data.remove(idx);
            for (Listener l : listeners) l.messageTrimmed(removed, idx);
        }
    }

    /** 消息行上限（默认 40，范围 20~100）。 */
    public static int maxMessagesSetting() {
        return Mathf.clamp(Core.settings.getInt(SET_MAX_MESSAGES, DEFAULT_MAX_MESSAGES), MIN_MAX_MESSAGES, MAX_MAX_MESSAGES);
    }

    /** 专用托管服务器（无面板）的中继缓冲淘汰：瞬时消息按投递时刻 TTL 到期静默移除，仅供迟到玩家补发缓存。
     *  在线玩家面板不受影响——瞬时消息的生命周期由每个客户端本地管理。 */
    public void tickLifecycle() {
        for (int i = data.size - 1; i >= 0; i--) {
            Message m = data.get(i);
            if (m.type == MessageType.PERSISTENT || m.ttl < 0) continue;
            if (Time.time - m.addedAt >= m.ttl) removeAt(i);
        }
    }

    // ---------- 联网镜像应用（仅供 MessageSync 内部调用，业务方勿用） ----------

    /** 按 uid 查消息（客户端镜像）。 @apiNote 内部联网用 */
    public Message byUid(long uid) {
        for (Message m : data) if (m.uid == uid) return m;
        return null;
    }

    /** 应用服务器广播的「新增」：按优先级插入本地镜像。随后按<b>本进程自己的</b>上限本地剔除
     *  （剔除只影响本地面板，不向外广播——各玩家面板上限互不影响）。
     *  <p><b>按 uid 幂等</b>：房主进程已由权威 {@link #add(Message)} 本地登记过，广播层的自回环
     *  防护出现偏差时，这里也必须防住重复插入（重复消息会导致已读/到期/移除行为错乱）。
     *  @apiNote 内部联网用，仅 MessageSync 调用 */
    public void applyNetAdd(Message m) {
        if (m == null || m.uid < 0 || byUid(m.uid) != null) return;
        int at = insertIndexFor(m.priority);
        data.insert(at, m);
        for (Listener l : listeners) l.messageAdded(m, at);
        trimOverflow();
    }

    /** 应用服务器广播的「移除」：仅持续型消息会收到（触发视图移除动画）。
     *  @apiNote 内部联网用，仅 MessageSync 调用 */
    public void applyNetRemove(long uid) {
        Message m = byUid(uid);
        if (m != null) remove(m);
    }

    /** 应用服务器广播的「剔除」（视图立即删除，无动画）。
     *  @apiNote 内部联网用，仅 MessageSync 调用 */
    public void applyNetTrim(long uid) {
        Message m = byUid(uid);
        if (m == null) return;
        int i = data.indexOf(m);
        data.remove(i);
        for (Listener l : listeners) l.messageTrimmed(m, i);
    }

    /** 应用服务器广播的「清空」。
     *  @apiNote 内部联网用，仅 MessageSync 调用 */
    public void applyNetClear() {
        clear();
    }

    /** 应用服务器广播的「持续型内容/样式刷新」：按 uid 逐字段原地替换镜像（面板下一帧刷新显示）。
     *  非线上字段（uid/addedAt/onClick/contentProvider/vars/handshake）保持不变。
     *  @apiNote 内部联网用，仅 MessageSync 调用 */
    public void applyNetUpdateFull(Message net) {
        Message m = byUid(net.uid);
        if (m == null) return;
        m.type = net.type;
        m.priority = net.priority;
        m.team = net.team;
        m.global = net.global;
        m.ttl = net.ttl;
        m.titleColor = net.titleColor;
        m.contentColor = net.contentColor;
        m.bubbleColor = net.bubbleColor;
        m.overlayColor = net.overlayColor;
        m.titleScale = net.titleScale;
        m.contentScale = net.contentScale;
        m.icon = net.icon;
        m.titleKey = net.titleKey;
        m.contentKey = net.contentKey;
        m.title = net.title;
        m.content = net.content;
        m.silent = net.silent;
        m.sound = net.sound;
        m.soundName = net.soundName;
    }

    /** 生成一条默认内容样式的消息（展开/收起共用结构，收起时仅显示图标）。 */
    public static Message newMessage(String title, String content) {
        return new Message(title, content);
    }

    /**
     * 生成一条<b>持续型</b>消息：由消息源持有生命周期，在系统中占位直到消息源撤销。
     * 撤销方式（二选一）：
     * <ul>
     *   <li><b>推荐</b>：经 {@link Message#handshake(Handshake)} 挂握手，消息源失联/主动
     *       {@code handshake.disconnect()} 时由系统自动清除（与方块拆除等失联场景兼容）；</li>
     *   <li>{@link #remove(Message)} 直接移除。</li>
     * </ul>
     * 忽略 TTL，不参与超上限剔除与面板清空。
     * <p>
     * 内容支持实时更新：{@code contentProvider} 为消息源提供的 lambda，面板每帧调用它以刷新显示内容
     * （内容返回 null 时显示静态 content）。消息源可在 lambda 内闭包捕获自身状态实现实时数据。
     *
     * @param title           固定标题
     * @param content         静态内容（contentProvider 返回 null 时作为后备显示）
     * @param contentProvider 实时内容提供器（每次调用返回当前内容）
     * @see Message#handshake(Handshake)
     */
    public static Message persistent(String title, String content, Prov<String> contentProvider) {
        return new Message(title, content).type(MessageType.PERSISTENT)
            .contentProvider(contentProvider).icon(Icon.info);
    }

    /**
     * 生成一条<b>持续型</b>消息，内容完全由消息源提供的 lambda 实时生成。
     *
     * @see #persistent(String, String, Prov)
     */
    public static Message persistent(String title, Prov<String> contentProvider) {
        return persistent(title, null, contentProvider);
    }

    /**
     * 生成一条<b>紧急</b>模板消息（高优先级，气泡红色，警告图标）。
     *
     * @param title    标题
     * @param content  内容
     * @return 配置完成的紧急消息；未设时限（常驻），如需到期消失请用 {@link Message#life(float)}
     *         或 {@link #emergency(String, String, float)}
     */
    public static Message emergency(String title, String content) {
        return new Message(title, content).priority(Priority.HIGH)
            .background(COLOR_EMERGENCY).titleColor(Color.white).icon(Icon.warning);
    }

    /** 紧急模板 + 设时限：等同 {@link #emergency(String, String)} 再 {@link Message#life(float)}。 */
    public static Message emergency(String title, String content, float life) {
        return emergency(title, content).life(life);
    }

    /**
     * 生成一条<b>警告</b>模板消息（中优先级，气泡黄色，警告图标）。
     * 同一时刻存在紧急与常规通知时，警告会插入在紧急与常规之间。
     */
    public static Message warning(String title, String content) {
        return new Message(title, content).priority(Priority.MEDIUM)
            .background(COLOR_WARNING).titleColor(Color.white).icon(Icon.warning);
    }

    /** 警告模板 + 设时限。 */
    public static Message warning(String title, String content, float life) {
        return warning(title, content).life(life);
    }

    /**
     * 生成一条<b>提示</b>模板消息（低优先级，气泡蓝色，信息图标）。
     */
    public static Message info(String title, String content) {
        return new Message(title, content).priority(Priority.LOW)
            .background(COLOR_INFO).titleColor(Color.white).icon(Icon.info);
    }

    /** 提示模板 + 设时限。 */
    public static Message info(String title, String content, float life) {
        return info(title, content).life(life);
    }

    /**
     * 生成一条<b>常规</b>模板消息（低优先级，气泡灰白）。
     */
    public static Message normal(String title, String content) {
        return new Message(title, content).priority(Priority.LOW)
            .background(COLOR_REGULAR).titleColor(Color.lightGray).icon(Icon.info);
    }

    /** 常规模板 + 设时限。 */
    public static Message normal(String title, String content, float life) {
        return normal(title, content).life(life);
    }

    /**
     * 一条消息的数据模型：标题、内容、标题/内容颜色、右侧图标、气泡底色，
     * 以及可选的点击回调与时限。字段设为公开可变，便于追加更多属性/功能。
     * <p>
     * 气泡颜色经 {@link #background(Color)} 设置：该颜色会同时用作
     * <ul>
     *   <li>气泡本身的底色（用传入色的透明度填充）；</li>
     *   <li>时限消息覆盖层（消失时间提示）的颜色——自动采用气泡颜色。</li>
     * </ul>
     * <p>
     * <b>变量引用（占位符）</b>：标题与内容文本中可用 {@code {0}}、{@code {1}}… 占位符引用
     * 由消息源经 {@link #var(Prov)} / {@link #var(String)} 提供的变量。面板每帧重新取值渲染，
     * 因此变量值可实时变化（如已运行秒数、资源量）；未提供对应索引的占位符保持原样。
     */
    public static class Message {
        public String title;
        public String content;
        /** 标题本地化键（可空）：非空时显示文本来自 {@code Core.bundle.get(titleKey)}，缺失回退 {@link #title}。 */
        public String titleKey;
        /** 内容本地化键（可空）：非空时显示文本来自 {@code Core.bundle.get(contentKey)}，缺失回退 {@link #content}；优先于 contentProvider。 */
        public String contentKey;
        /** 占位符变量表：{索引} 依次对应本表元素（提供器每帧重新取值）。 */
        public final Seq<Prov<String>> vars = new Seq<>();
        public Color titleColor = Color.white;
        public Color contentColor = Color.lightGray;
        public float titleScale = 1f;
        public float contentScale = 0.8f;
        public Drawable icon = Icon.info;
        public Drawable background = tint(0.45f, 0.45f, 0.45f, 0.4f);
        /** 气泡的基底颜色（rgb + 满透明度）；覆盖层默认自动采用它。 */
        public Color bubbleColor = new Color(0.45f, 0.45f, 0.45f, 1f);
        public Runnable onClick;
        /** 收到消息时播放的音效（由面板在该消息到达时触发）；null = 使用默认 {@code new-message} 音效。 */
        public Sound sound;
        /** 到达音效的资源名（{@code assets/sounds/} 下，不含扩展名）：网络/镜像序列化的规范键。
         *  权威进程即使音频不可用（如专用服务器）也能预先保留名字，由客户端按名解析；null/空 = 未指定（回退默认音效）。 */
        public String soundName;
        /** 静音：将该消息标记为不播放任何到达音效（优先级高于 {@link #sound}）。 */
        public boolean silent;
        /** 徒时消息的显示时限（秒）：玩家已读后到点由<b>各自面板</b>本地移除；&lt;0 表示不设时限（常驻）。持续型消息忽略本设置。 */
        public float ttl = -1f;
        /** 时限消息覆盖层（消失时间提示）的颜色。默认随气泡颜色自动更新。 */
        public Color overlayColor = new Color(0.45f, 0.45f, 0.45f, 1f);
        /** 优先级（决定分组排序；普通 {@link #newMessage} 默认低优先级）。 */
        public Priority priority = Priority.LOW;
        /** 消息类型分类（瞬时/持续型），决定生命周期由谁控制。 */
        public MessageType type = MessageType.TRANSIENT;
        /** 实时内容提供器（持续型消息用）：面板每帧调用以刷新显示内容。 */
        public Prov<String> contentProvider;
        /** 持续型消息与消息源的握手机制：断开/失联后由系统自动清除本消息。 */
        public Handshake handshake;
        /** 所属队伍（投递方）：null = 不设队伍（视为通用消息，任何玩家可收）。 */
        public Team team;
        /** 全局可见：true 时所有玩家（含敌方阵营）都可收取；false 时仅 {@link #team} 同队玩家可见。 */
        public boolean global;
        /** 跨进程稳定 ID：权威进程（服务器/单机）投递时分配，联网客户端镜像沿用；本地未同步消息为 -1。 */
        public long uid = -1;
        /**
         * 来源键：仅「客户端请求投递」的持续型消息携带，编码来源方块的格子坐标
         * （{@code (x&lt;&lt;32)|y}）。服务器凭它周期校验来源是否仍存在（方块被拆/玩家离线即撤销）。
         * 权威进程（服务器/单机）本地投递的消息为 -1。
         */
        public long senderKey = -1;
        /** 投递时刻（权威进程时钟 Time.time 秒）：服务器据此做 TTL 到期清除。 */
        public float addedAt;
        /** 上次同步到客户端的标题快照（服务器内部判断内容变化用）。 */
        public String lastSyncTitle;
        /** 上次同步到客户端的内容快照（服务器内部判断内容变化用）。 */
        public String lastSyncContent;
        /** 上次内容同步时刻（秒，服务器内部节流用，避免持续型消息逐帧全量重发）。 */
        public float lastSyncTime = -1f;

        Message(String title, String content) {
            // 单行 TextField 无法输入真实换行；用户输入 \n 两个字符需转为实际换行符
            this.title = title != null ? title.replace("\\n", "\n") : null;
            this.content = content != null ? content.replace("\\n", "\n") : null;
        }

        /** 设置消息类型分类（瞬时/持续型）。 */
        public Message type(MessageType t) { this.type = t; return this; }
        /** 设置实时内容提供器（持续型消息用）：面板每帧调用以刷新显示内容；返回 null 时显示静态 content。 */
        public Message contentProvider(Prov<String> p) { this.contentProvider = p; return this; }
        /** 指定标题为本地化键值：显示时经 {@code Core.bundle.get(key)} 翻译；键缺失时回退 {@link #title} 原文。 */
        public Message titleKey(String key) { this.titleKey = key; return this; }
        /** 指定内容为本地化键值：显示时经 {@code Core.bundle.get(key)} 翻译；键缺失时回退 {@link #content} 原文。 */
        public Message contentKey(String key) { this.contentKey = key; return this; }
        /** 追加一个变量（占位符 {索引} 依次对应追加顺序）：面板每帧重新调用以获取当前值。 */
        public Message var(Prov<String> v) { this.vars.add(v); return this; }
        /** 追加一个固定变量（占位符 {索引} 依次对应追加顺序）。 */
        public Message var(String value) { this.vars.add(() -> value); return this; }
        /** 挂上消息源与消息系统的握手机制：断开/失联后由系统自动清除本持续型消息。 */
        public Message handshake(Handshake h) { this.handshake = h; return this; }
        /** 指定消息所属队伍（投递方）。同队消息默认仅同队玩家可收取。 */
        public Message team(Team t) { this.team = t; return this; }
        /** 标记为全局可见：所有玩家（含敌方）都可收取。 */
        public Message global() { this.global = true; return this; }
        /** 设置全局可见标记。 */
        public Message global(boolean g) { this.global = g; return this; }

        /** 该消息是否对指定的观看者可见：全局消息所有玩家可收；带队伍的消息仅同队可收；未带队伍的消息不限制（通用消息）。 */
        public boolean visibleTo(Team viewer) {
            return global || team == null || team == viewer;
        }

        /** 设置标题颜色。 */
        public Message titleColor(Color c) { this.titleColor = c; return this; }
        /** 设置正文颜色。 */
        public Message contentColor(Color c) { this.contentColor = c; return this; }
        /** 设置标题字体缩放（默认 1）。 */
        public Message titleScale(float s) { this.titleScale = s; return this; }
        /** 设置正文字体缩放（默认 0.8）。 */
        public Message contentScale(float s) { this.contentScale = s; return this; }
        /** 设置图标：<b>展开行右侧</b>与<b>收起态小方块中央</b>均显示该图标。 */
        public Message icon(Drawable i) { this.icon = i; return this; }
        /** 设置优先级（决定分组排序）。 */
        public Message priority(Priority p) { this.priority = p; return this; }
        /** 直接指定气泡背景贴图（覆盖默认底色）。 */
        public Message background(Drawable d) { this.background = d; return this; }
        /**
         * 设置气泡颜色：同时用作气泡底色与消失时间覆盖层颜色（覆盖层<b>自动采用气泡颜色</b>）。
         * 传入色的透明度即气泡底色透明度；若需再单独微调覆盖层，可随后调用 {@link #overlayColor(Color)} 覆盖。
         */
        public Message background(Color c) {
            this.bubbleColor = c;
            this.overlayColor = c; // 覆盖层自动采用气泡颜色
            this.background = tint(c.r, c.g, c.b, c.a);
            return this;
        }
        /**
         * 设置点击回调：玩家在展开的气泡上点击时触发。
         * 注意回调在客户端进程执行；多人模式下为<b>本地表现</b>，不会同步给其他玩家。
         */
        public Message onClick(Runnable r) { this.onClick = r; return this; }
        /** 指定收到消息时播放的音效（面板在该消息到达时触发）；null = 回退默认 {@code new-message} 音效。
         *  常用模组音效见 {@link SiliconSounds}，也可用原版 {@code Sounds.*}/自定义 {@code arc.audio.Sound}。
         *  序列化按 {@link SiliconSounds#nameOf} 推导资源名跨进程传递；在<b>音频不可用</b>的权威进程
         *  （如专用服务器）上创建的消息请改用 {@link #sound(String)} 以名字指定。 */
        public Message sound(Sound s) {
            this.sound = s;
            this.soundName = s != null ? SiliconSounds.nameOf(s) : null;
            return this;
        }
        /** 按<b>资源名</b>指定收到消息时播放的音效（{@code assets/sounds/} 下某 {@code .ogg} 的文件名，不含扩展名）。
         *  即使本进程音频不可用（如专用服务器）也会保留名字，由各客户端按名解析播放；名字为空 / 未注册时
         *  回退默认 {@code new-message} 音效。 */
        public Message sound(String name) {
            this.soundName = name;
            this.sound = name != null ? SiliconSounds.get(name) : null;
            return this;
        }
        /** 静音：该消息到达时不播放任何音效（优先级高于 {@link #sound(Sound)}）。
         *  用于不希望打扰玩家的后台级消息。 */
        public Message silent() { this.silent = true; return this; }
        /** 设定徒时消息的显示时限（秒）：玩家已读后到点由<b>各自面板</b>自动移除；
         *  &lt;0 表示不设时限（常驻）；0 也视为常驻。持续型消息忽略本设置。 */
        public Message life(float seconds) { this.ttl = seconds; return this; }
        /** 单独覆盖覆盖层（消失时间提示）的颜色，允许与气泡颜色不同。 */
        public Message overlayColor(Color c) { this.overlayColor = c; return this; }

        /** 当前要展示的标题：本地化键优先（缺失回退原文），再替换 {索引} 占位符为变量当前值。 */
        public String currentTitle() {
            return format(titleKey != null ? Core.bundle.get(titleKey, title) : title);
        }

        /** 当前要展示的内容：本地化键优先（缺失回退原文）；否则持续型取实时提供器结果（null 回退静态 content）；最后替换占位符。 */
        public String currentContent() {
            if (contentKey != null) return format(Core.bundle.get(contentKey, content));
            if (contentProvider != null) {
                String live = contentProvider.get();
                if (live != null) return format(live);
            }
            return format(content);
        }

        /** 把 {0}、{1}… 占位符替换为 {@link #vars} 对应变量的当前值；越界/未提供保持原样。 */
        private String format(String text) {
            if (text == null || text.indexOf('{') < 0 || vars.isEmpty()) return text;
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    int j = i + 1;
                    int index = 0;
                    while (j < text.length() && Character.isDigit(text.charAt(j))) {
                        index = index * 10 + (text.charAt(j) - '0');
                        j++;
                    }
                    if (j > i + 1 && j < text.length() && text.charAt(j) == '}'
                        && index < vars.size && vars.get(index) != null) {
                        String v = vars.get(index).get();
                        out.append(v != null ? v : "");
                        i = j;
                        continue;
                    }
                }
                out.append(c);
            }
            return out.toString();
        }
    }

    /** 用染色 whiteui 生成指定 RGBA 的纯色底条（程序绘制，不依赖贴图）。 */
    private static Drawable tint(float r, float g, float b, float a) {
        return ((arc.scene.style.TextureRegionDrawable) Tex.whiteui).tint(new Color(r, g, b, a));
    }
}