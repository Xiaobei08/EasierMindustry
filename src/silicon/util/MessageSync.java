package silicon.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import arc.Events;
import arc.audio.Sound;
import arc.graphics.Color;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import silicon.util.MessageSystem.Message;
import silicon.util.MessageSystem.MessageType;

import static mindustry.Vars.net;

/**
 * 消息系统的<b>多人联网同步层</b>：
 * <ul>
 *   <li><b>服务器/房主</b>：把新增消息（含瞬时与持续型）序列化后按 {@link Message#team}/{@link Message#global}
 *       同队/全局过滤，只推送给「有权看到」的在线玩家；持续型消息的内容变化按节流周期推送全量快照，
 *       其生命周期（源断开/失联）同步移除。</li>
 *   <li><b>瞬时消息生命周期完全本地化</b>：服务器只负责把消息完整投递到各玩家面板，之后不再干预——
 *       每个玩家在<b>自己的面板</b>上独立清理（已读后按各自 TTL 到期、各自的面板上限剔除、手动清空），
 *       一个玩家的清理不会影响其他玩家（剔除/清空/已读到期均不广播）。</li>
 *   <li><b>客户端 → 服务器请求</b>：纯客户端的消息源（如非房主的「消息测试」方块）经
 *       {@link #requestAdd(Message)}、{@link #requestRemove(long)}、{@link #requestUpdate(long, Message)}
 *       把投递/撤销/修改请求交给服务器，由服务器作为权威登记并按既有广播分发给全体在线玩家；
 *       客户端本地<b>不</b>直接登记（等服务器 ADD 广播确认后镜像才会出现）。</li>
 *   <li><b>来源清扫</b>：客户端请求投递的持续型消息携带 {@link Message#senderKey}（来源方块坐标），
 *       服务器周期校验来源仍存在且同队，方块被拆/换队即撤销该消息（{@link #sweepClientMessages()}）。</li>
 *   <li><b>客户端</b>：接收服务器二进制包，按 uid 更新本地镜像 {@link MessageSystem}（面板据此渲染，
 *       并本地执行各玩家自己的清理规则）。</li>
 *   <li><b>中途加入</b>：PlayerJoin 时补发当前可见消息全量（持续型 + 仍在 TTL 内的瞬时），保证面板不空。</li>
 * </ul>
 * 单机（无联网）时序不出网，Add/Remove/Clear 直接通知本地面板，行为不变。
 */
public class MessageSync implements MessageSystem.Listener {
    /** 二进制包名（客户端与服务器进程必须一致）。 */
    public static final String PACKET = "silicon-message-sync";

    /** 服务器 → 客户端：变更广播操作码。 */
    private static final byte OP_ADD = 0, OP_REMOVE = 1, OP_TRIM = 2, OP_CLEAR = 3, OP_UPDATE = 4;
    /** 客户端 → 服务器：请求操作码（客户端无权威，不得直接改服务器状态，一律请求服务器代为执行）。 */
    private static final byte REQ_ADD = 100, REQ_REMOVE = 101, REQ_UPDATE = 102;
    /** 持续型消息内容快照的推送节流间隔（秒）：避免逐帧重发整包。 */
    private static final float UPDATE_INTERVAL = 0.5f;

    /** 单例。 */
    public static final MessageSync instance = new MessageSync();

    private static volatile boolean initialized = false;

    /** 注册多人群服务器钩子；仅在 SELECT 模式（服务器上）可用，安全可重入。 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        // 服务器：处理客户端的投递/撤销/修改请求
        registerServerHandler();
        // 客户端：接收服务器广播
        registerClientHandler();
        // 客户端每次连接世界后，把镜像状态与授权列表对齐（服务器始终是权威）
        MessageSystem.instance.addListener(instance);
        Events.run(EventType.Trigger.update, MessageSync::broadcastLiveUpdates);
        Events.run(EventType.Trigger.update, MessageSync::sweepClientMessages);
        Events.on(EventType.PlayerJoin.class, e -> {
           if (net.server()) replayTo(e.player);
        });
        // 专用服务器可能在 mod init 时网络系统尚未创建（netServer/netClient 为 null）：
        // 世界加载后网络必然就绪，此时补注册处理器（仅当时未注册的才会真正注册）。
        Events.on(EventType.WorldLoadEvent.class, e -> {
           registerServerHandler();
           registerClientHandler();
        });
    }

    private static boolean serverHandlerRegistered = false;
    private static boolean clientHandlerRegistered = false;

    /** 注册服务器端请求处理器；网络未就绪时不锁存，留给 WorldLoad 钩子补注册。 */
    private static void registerServerHandler() {
        if (serverHandlerRegistered || Vars.netServer == null) return;
        serverHandlerRegistered = true;
        Vars.netServer.addBinaryPacketHandler(PACKET, MessageSync::applyServerRequest);
    }

    /** 注册客户端广播处理器；网络未就绪时不锁存，留给 WorldLoad 钩子补注册。 */
    private static void registerClientHandler() {
        if (clientHandlerRegistered || Vars.netClient == null) return;
        clientHandlerRegistered = true;
        Vars.netClient.addBinaryPacketHandler(PACKET, MessageSync::applyClientPacket);
    }

    // ---------- 服务器：处理客户端请求 ----------

    /** 处理客户端发来的投递/撤销/修改请求（客户端无权威，一律由服务器代为执行并经正常监听广播确认）。 */
    private static void applyServerRequest(Player player, byte[] data) {
        if (player == null || !net.server()) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = Math.min(Math.max(in.readInt(), 0), 1024);
            for (int i = 0; i < count; i++) {
                switch (in.readByte()) {
                    case REQ_ADD -> {
                        Message m = readMessage(in, true);
                        // 服务器强制归属：uid 由服务器重新分配；队伍一律落回请求者队伍（防伪造他人队伍消息）
                        m.uid = -1;
                        if (m.team == null || m.team != player.team()) m.team = player.team();
                        // 来源归属校验：声称存在来源方块（senderKey>=0）时，要求该坐标方块存在且属于请求者本人；
                        // 防止自报任意坐标伪造来源（source 清扫会据此定位方块，非法坐标亦可被用来冒认他人方块/消息）。
                        // 校验失败直接拒绝本次投递（与服务器未确认等价，客户端保持 pending 等待），而非抹平 senderKey——
                        // 抹平会让客户端按坐标认领失败而永久停在「等待服务器确认」，并留下无来源的孤立消息。
                        if (m.senderKey >= 0 && !ownsSourceBlock(player, m.senderKey)) continue;
                        MessageSystem.instance.add(m);
                    }
                    case REQ_REMOVE -> removeRequested(player, in.readLong());
                    case REQ_UPDATE -> updateRequested(player, readMessage(in, true));
                    default -> { // 版本不匹配：未知操作码，直接结束
                        return;
                    }
                }
            }
        } catch (IOException e) {
            SiliconLog.info("MessageSync: server request failed: " + e);
        }
    }

    /** 客户端请求撤销一条持续型消息：仅<b>客户端来源</b>（{@link Message#senderKey} >= 0）、且对请求者可见
     *  （全局/同队/通用）才执行。服务器/系统来源的持续型消息（如 PowerProtector 警示，senderKey 恒为 -1）
     *  由权威进程自管生命周期，客户端无权撤销。 */
    private static void removeRequested(Player player, long uid) {
        Message m = MessageSystem.instance.byUid(uid);
        if (m == null || m.type != MessageType.PERSISTENT || m.senderKey < 0) return;
        if (!m.visibleTo(player.team())) return;
        MessageSystem.instance.remove(m);
    }

    /** 客户端请求更新一条持续型消息：仅<b>客户端来源</b>（{@link Message#senderKey} >= 0）、且对请求者可见才执行，
     *  按请求的完整期望状态替换服务器权威记录，并立即广播刷新快照。服务器/系统来源的消息不可被客户端篡改
     *  （防止冒充系统样式/音效、把队伍消息提升为全局泄给敌方）。 */
    private static void updateRequested(Player player, Message requested) {
        Message m = MessageSystem.instance.byUid(requested.uid);
        if (m == null || m.type != MessageType.PERSISTENT || m.senderKey < 0) return;
        if (!m.visibleTo(player.team())) return;
        copyRequestedFields(m, requested);
        push(m, OP_UPDATE); // 立即推全量快照，绕过节流
    }

    /** 把请求的期望字段复制到服务器权威记录；uid/addedAt/handshake/senderKey/vars/onClick/contentProvider 等
     *  「不在线上」的字段保持服务器权威值（本地进程引用不能跨进程传输）。 */
    private static void copyRequestedFields(Message target, Message src) {
        target.type = src.type;
        target.priority = src.priority;
        target.global = src.global;
        target.ttl = src.ttl;
        target.titleColor = src.titleColor;
        target.contentColor = src.contentColor;
        target.bubbleColor = src.bubbleColor;
        target.overlayColor = src.overlayColor;
        target.titleScale = src.titleScale;
        target.contentScale = src.contentScale;
        target.icon = src.icon;
        target.titleKey = src.titleKey;
        target.contentKey = src.contentKey;
        target.title = src.title;
        target.content = src.content;
        target.silent = src.silent;
        target.sound = src.sound;
        target.soundName = src.soundName;
    }

    /** 校验请求者确实持有 {@code senderKey} 指向的来源方块（坐标解码与 {@link #sweepClientMessages()} 一致）。
     *  校验失败即拒绝受理，杜绝自报任意来源坐标伪造归属/冒认他人方块。 */
    private static boolean ownsSourceBlock(Player player, long senderKey) {
        int x = (int) (senderKey >> 32);
        int y = (int) (senderKey & 0xffffffffL);
        Building b = Vars.world.build(x, y);
        return b != null && b.team == player.team();
    }

    // ---------- 服务器：变更广播 ----------

    @Override
    public void messageAdded(Message msg, int index) {
        push(msg, OP_ADD);
    }

    @Override
    public void messageRemoved(Message msg, int index) {
        // 仅持续型消息：消息源（服务器权威）决定了生命周期，必须同步给所有在线玩家。
        // 瞬时消息的移除是<b>每个玩家本地的事</b>（各自的已读到期、面板上限剔除、手动清空），不广播。
        if (msg.type == MessageType.PERSISTENT) push(msg, OP_REMOVE);
    }

    @Override
    public void messageTrimmed(Message msg, int index) {
        // 若为持续型（几乎不会发生），同步；瞬时消息的剔除仅发生在各自面板本地，不广播。
        if (msg.type == MessageType.PERSISTENT) push(msg, OP_TRIM);
    }

    @Override
    public void messagesCleared() {
        // 每个进程在世界加载时都自行清空；面板清空按钮只影响操作者本地视图。无需广播 CLEAR。
    }

    /** 把单条消息事件按同队/全局可见性分发给各客户端连接（OP_ADD/OP_UPDATE 发全量记录，键不外发）。
     *  跳过房主自己的本地连接：房主进程已由权威 {@code add()} 本地登记过，回环广播只会让房主面板重复插入。 */
    private static void push(Message m, byte op) {
        if (!net.server() || m == null) return;
        byte[] data = record(op, m, false);
        for (Player p : Groups.player) {
            if (p == Vars.player || p.con == null || !m.visibleTo(p.team())) continue;
            Call.clientBinaryPacketReliable(p.con, PACKET, data);
        }
    }

    /** 持续型消息内容快照节流推送：仅当标题/内容相对上次快照变化且超过节流间隔才重发（全量记录，键不外发）。 */
    private static void broadcastLiveUpdates() {
        if (!net.server() || !Vars.state.isGame()) return;
        float now = Time.time;
        for (Message m : MessageSystem.instance.all()) {
            if (m.type != MessageType.PERSISTENT) continue;
            String t = m.currentTitle();
            String c = m.currentContent();
            boolean changed = !eq(t, m.lastSyncTitle) || !eq(c, m.lastSyncContent);
            if (changed && now - m.lastSyncTime >= UPDATE_INTERVAL) {
                m.lastSyncTitle = t;
                m.lastSyncContent = c;
                m.lastSyncTime = now;
                byte[] data = record(OP_UPDATE, m, false);
                for (Player p : Groups.player) {
                    // 同样跳过房主本地玩家：其持续型消息由本地面板直接用权威对象渲染
                    if (p == Vars.player || p.con == null || !m.visibleTo(p.team())) continue;
                    Call.clientBinaryPacketReliable(p.con, PACKET, data);
                }
            }
        }
    }

    /** 客户端请求投递的持续型消息来源清扫（仅服务器执行）：解码 {@link Message#senderKey} 定位来源方块，
     *  方块被拆除（或该队不再拥有来源）即撤销该消息——消息源失联的标准兜底。 */
    private static void sweepClientMessages() {
        if (!net.server()) return;
        Seq<Message> data = MessageSystem.instance.all();
        for (int i = data.size - 1; i >= 0; i--) {
            Message m = data.get(i);
            if (m.type != MessageType.PERSISTENT || m.senderKey < 0) continue;
            int x = (int) (m.senderKey >> 32);
            int y = (int) (m.senderKey & 0xffffffffL);
            Building b = Vars.world.build(x, y);
            if (b == null || b.team != m.team) MessageSystem.instance.removeAt(i);
        }
    }

    /** 中途加入的玩家：补发当前可见消息全量（尊重同队/全局过滤；瞬时跳过已过期的，只补仍在 TTL 内的）。
     *  房主自己的加入不补发——其本地消息已在权威登记时入库。 */
    private static void replayTo(Player p) {
        if (!net.server() || p == null || p == Vars.player || p.con == null) return;
        int count = 0;
        for (Message m : MessageSystem.instance.all()) {
            if (m.visibleTo(p.team()) && replayable(m)) count++;
        }
        if (count == 0) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(count * 128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(count);
            for (Message m : MessageSystem.instance.all()) {
                if (m.visibleTo(p.team()) && replayable(m)) writeMessage(out, m, false);
            }
        } catch (IOException e) {
            SiliconLog.info("MessageSync: replay serialize failed: " + e);
            return;
        }
        Call.clientBinaryPacketReliable(p.con, PACKET, bytes.toByteArray());
    }

    /** 补发判定：持续型 = 始终补发；瞬时 = 仅在 TTL 内（迟到加入不重播已过期瞬时）。 */
    private static boolean replayable(Message m) {
        return m.type == MessageType.PERSISTENT || m.ttl < 0 || Time.time - m.addedAt < m.ttl;
    }

    // ---------- 序列化 ----------

    /** 单事件包：包内固定 1 条记录。
     *  @param includeKeys 是否携带 titleKey/contentKey：仅客户端→服务器请求 true（服务器权威记录需保留键）；
     *                     服务器→客户端恒 false——键引用的消息发给镜像的是<b>服务器端渲染快照</b>
     *                     （如 PowerProtector 的 {0} 变量由服务器代渲染，键外发反而会破坏变量的实时性）。 */
    private static byte[] record(byte op, Message m, boolean includeKeys) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeByte(op);
            switch (op) {
                case OP_ADD, OP_UPDATE -> writeMessage(out, m, includeKeys);
                case OP_REMOVE, OP_TRIM -> out.writeLong(m.uid);
                default -> {
                }
            }
        } catch (IOException e) {
            SiliconLog.info("MessageSync: record serialize failed: " + e);
        }
        return bytes.toByteArray();
    }

    /** 写一条完整消息记录（不含操作码；包首为记录数）。详情见 {@link #readMessage(DataInputStream, boolean)}。 */
    private static void writeMessage(DataOutputStream out, Message m, boolean includeKeys) throws IOException {
        out.writeLong(m.uid);
        out.writeByte(m.type.ordinal());                          // 0=TRANSIENT 1=PERSISTENT
        out.writeByte(m.priority.ordinal());                      // 0=HIGH 1=MEDIUM 2=LOW
        out.writeInt(m.team != null ? m.team.id : -1);
        out.writeBoolean(m.global);
        out.writeFloat(m.ttl);
        out.writeInt(m.titleColor.rgba8888());
        out.writeInt(m.contentColor.rgba8888());
        out.writeInt(m.bubbleColor.rgba8888());
        out.writeInt(m.overlayColor.rgba8888());
        out.writeFloat(m.titleScale);
        out.writeFloat(m.contentScale);
        out.writeUTF(iconName(m.icon));
        if (includeKeys) {
            out.writeUTF(m.titleKey != null ? m.titleKey : "");
            out.writeUTF(m.contentKey != null ? m.contentKey : "");
        }
        out.writeUTF(m.currentTitle() != null ? m.currentTitle() : "");
        out.writeUTF(m.currentContent() != null ? m.currentContent() : "");
        out.writeBoolean(m.silent);
        out.writeUTF(wireSoundName(m));
        out.writeLong(m.senderKey);
    }

    /** 读一条完整消息记录，构建镜像/请求消息。
     *  titleKey/contentKey/vars/onClick/contentProvider 不在线上传输：服务器→客户端直接用渲染快照文本
     *  （{@code includeKeys=false}）；客户端→服务器请求额外读回键（{@code includeKeys=true}）。 */
    private static Message readMessage(DataInputStream in, boolean includeKeys) throws IOException {
        long uid = in.readLong();
        MessageType type = in.readByte() == 1 ? MessageType.PERSISTENT : MessageType.TRANSIENT;
        MessageSystem.Priority priority = MessageSystem.Priority.values()[Math.min(2, Math.max(0, in.readByte()))];
        int teamId = in.readInt();
        boolean global = in.readBoolean();
        float ttl = in.readFloat();
        Color titleColor = new Color().rgba8888(in.readInt());
        Color contentColor = new Color().rgba8888(in.readInt());
        Color bubbleColor = new Color().rgba8888(in.readInt());
        Color overlayColor = new Color().rgba8888(in.readInt());
        float titleScale = in.readFloat();
        float contentScale = in.readFloat();
        String iconName = in.readUTF();
        String titleKey = "", contentKey = "";
        if (includeKeys) {
            titleKey = in.readUTF();
            contentKey = in.readUTF();
        }
        String title = in.readUTF();
        String content = in.readUTF();
        boolean silent = in.readBoolean();
        String soundName = in.readUTF();
        long senderKey = in.readLong();

        Message m = new Message(title, content);
        m.type(type).priority(priority).ttl = ttl;
        m.titleColor = titleColor;
        m.contentColor = contentColor;
        m.bubbleColor = bubbleColor;
        m.overlayColor = overlayColor;
        m.titleScale = titleScale;
        m.contentScale = contentScale;
        m.icon = resolveIcon(iconName);
        m.team = teamId >= 0 && teamId < Team.all.length ? Team.get(teamId) : null;
        m.global = global;
        m.uid = uid;
        if (includeKeys) {
            m.titleKey = titleKey.isEmpty() ? null : titleKey;
            m.contentKey = contentKey.isEmpty() ? null : contentKey;
        }
        m.silent = silent;
        m.soundName = soundName;
        m.sound = !silent ? resolveSound(soundName) : null;
        m.senderKey = senderKey;
        return m;
    }

    // ---------- 客户端：应用服务器广播 ----------

    /** 客户端收到二进制包：按记录逐条应用到本地镜像 MessageSystem（面板随之刷新）。 */
    private static void applyClientPacket(byte[] data) {
        if (data == null || !Vars.net.client()) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = Math.min(Math.max(in.readInt(), 0), 4096);
            for (int i = 0; i < count; i++) {
                switch (in.readByte()) {
                    case OP_ADD -> MessageSystem.instance.applyNetAdd(readMessage(in, false));
                    case OP_REMOVE -> MessageSystem.instance.applyNetRemove(in.readLong());
                    case OP_TRIM -> MessageSystem.instance.applyNetTrim(in.readLong());
                    case OP_CLEAR -> MessageSystem.instance.applyNetClear();
                    case OP_UPDATE -> MessageSystem.instance.applyNetUpdateFull(readMessage(in, false));
                    default -> { // 版本不匹配：忽略未知操作码（包尾可能残留，直接结束）
                        return;
                    }
                }
            }
        } catch (IOException e) {
            SiliconLog.info("MessageSync: apply packet failed: " + e);
        }
    }

    // ---------- 客户端：请求服务器 ----------

    /** 客户端请求服务器登记/发布一条消息（含瞬时与持续型）。由服务器受理并广播确认；
     *  请求者本地<b>不</b>登记，等服务器 ADD 广播确认后镜像才会在其面板出现。
     *  单机/服务器进程不应调用（本地 {@link MessageSystem#add(Message)} 即可）。 */
    public static void requestAdd(Message m) {
        if (!Vars.net.client()) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeByte(REQ_ADD);
            writeMessage(out, m, true);
        } catch (IOException e) {
            SiliconLog.info("MessageSync: requestAdd failed: " + e);
            return;
        }
        // 注意：客户端→服务器必须走 Call.serverBinaryPacketReliable；
        // NetClient.clientBinaryPacketReliable 只是本地 customBinaryPacketHandlers 回环分发，
        // 不会真正发往服务器（成员端发送曾因此静默丢失）。
        Call.serverBinaryPacketReliable(PACKET, bytes.toByteArray());
    }

    /** 客户端请求撤销一条<b>自己来源</b>（{@link Message#senderKey}&gt;=0）的持续型消息
     *  （服务器校验后移除并广播；系统/服务器来源消息不可由客户端撤销）。 */
    public static void requestRemove(long uid) {
        if (!Vars.net.client()) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeByte(REQ_REMOVE);
            out.writeLong(uid);
        } catch (IOException e) {
            SiliconLog.info("MessageSync: requestRemove failed: " + e);
            return;
        }
        Call.serverBinaryPacketReliable(PACKET, bytes.toByteArray());
    }

    /** 客户端请求以指定完整状态替换某<b>自己来源</b>的持续型消息（服务器校验后复制期望字段到权威记录并立即广播刷新快照）。 */
    public static void requestUpdate(long uid, Message state) {
        if (!Vars.net.client()) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeByte(REQ_UPDATE);
            state.uid = uid;
            writeMessage(out, state, true);
        } catch (IOException e) {
            SiliconLog.info("MessageSync: requestUpdate failed: " + e);
            return;
        }
        Call.serverBinaryPacketReliable(PACKET, bytes.toByteArray());
    }

    // ---------- 工具 ----------

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 图标 → 名称（优先 Icon.icons 反向查找；回退到纹理区域名）。 */
    private static String iconName(Drawable icon) {
        for (ObjectMap.Entry<String, TextureRegionDrawable> e : Icon.icons) {
            if (e.value == icon) return e.key;
        }
        if (icon instanceof TextureRegionDrawable tr) {
            if (tr.getRegion() instanceof AtlasRegion ar) return ar.name;
        }
        return "info";
    }

    /** 名称 → 图标（按 Icon.icons 查找；未注册的回退信息图标，避免区域查找抛异常）。 */
    private static Drawable resolveIcon(String name) {
        Drawable d = Icon.icons.get(name);
        return d != null ? d : Icon.info;
    }

    /** 消息 → 声音资源名（序列化用）：优先消息源显式保存的名字（音频不可用的权威进程也可靠），
     *  否则回退经 {@link SiliconSounds#nameOf} 从音效对象推导。 */
    private static String wireSoundName(Message m) {
        if (m.soundName != null && !m.soundName.isEmpty()) return m.soundName;
        return SiliconSounds.nameOf(m.sound);
    }

    /** 资源名 → 音效（客户端解析模组音频）；空串/null 表示未指定（由面板播放默认 new-message） */
    private static Sound resolveSound(String name) {
        return name == null || name.isEmpty() ? null : SiliconSounds.load(name);
    }
}