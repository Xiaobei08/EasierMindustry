package silicon.util;

import arc.func.Cons;
import arc.struct.Seq;
import mindustry.gen.Call;
import mindustry.gen.Player;

/**
 * 消息系统：统一管理消息的发送、存储与监听。
 * <p>
 * 其他模块通过静态方法发送消息，UI 层通过 {@link #addListener} 监听新消息。
 * 消息历史自动维护，支持按类型、来源过滤。
 * <p>
 * 用法示例：
 * <pre>
 *   MessageSystem.info("矿物转换器已就绪");
 *   MessageSystem.warn("电力不足", "PowerProtector");
 *   MessageSystem.send(player, "这是一条私信", MessageType.INFO);
 * </pre>
 */
public final class MessageSystem {

    /** 最大消息历史条数 */
    private static final int MAX_HISTORY = 200;
    /** 消息历史（最新在末尾） */
    private static final Seq<Message> history = new Seq<>();
    /** 监听器列表 */
    private static final Seq<Cons<Message>> listeners = new Seq<>();

    private MessageSystem() {}

    // ==================== 发送 API ====================

    /** 发送 INFO 消息（全局广播） */
    public static void info(String text) {
        dispatch(new Message(text, MessageType.INFO));
    }

    /** 发送 INFO 消息（带来源） */
    public static void info(String text, String source) {
        dispatch(new Message(text, MessageType.INFO, source));
    }

    /** 发送 WARNING 消息（全局广播） */
    public static void warn(String text) {
        dispatch(new Message(text, MessageType.WARNING));
    }

    /** 发送 WARNING 消息（带来源） */
    public static void warn(String text, String source) {
        dispatch(new Message(text, MessageType.WARNING, source));
    }

    /** 发送 ERROR 消息（全局广播） */
    public static void error(String text) {
        dispatch(new Message(text, MessageType.ERROR));
    }

    /** 发送 ERROR 消息（带来源） */
    public static void error(String text, String source) {
        dispatch(new Message(text, MessageType.ERROR, source));
    }

    /** 发送 SUCCESS 消息（全局广播） */
    public static void success(String text) {
        dispatch(new Message(text, MessageType.SUCCESS));
    }

    /** 发送 SUCCESS 消息（带来源） */
    public static void success(String text, String source) {
        dispatch(new Message(text, MessageType.SUCCESS, source));
    }

    /** 发送指定类型的消息 */
    public static void send(String text, MessageType type) {
        dispatch(new Message(text, type));
    }

    /** 发送指定类型的消息（带来源） */
    public static void send(String text, MessageType type, String source) {
        dispatch(new Message(text, type, source));
    }

    /** 向指定玩家发送私信（服务器端调用） */
    public static void send(Player player, String text, MessageType type) {
        if (player == null) return;
        Call.infoMessage(player.con, "[" + type.key + "] " + text);
    }

    /** 向所有玩家广播消息（服务器端调用） */
    public static void broadcast(String text, MessageType type) {
        Call.infoMessage(null, "[" + type.key + "] " + text);
    }

    // ==================== 监听器 API ====================

    /** 添加消息监听器（新消息触发回调） */
    public static void addListener(Cons<Message> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** 移除消息监听器 */
    public static void removeListener(Cons<Message> listener) {
        listeners.remove(listener);
    }

    // ==================== 历史查询 API ====================

    /** 获取全部消息历史（只读副本） */
    public static Seq<Message> getHistory() {
        return history.copy();
    }

    /** 获取指定类型的消息历史 */
    public static Seq<Message> getHistory(MessageType type) {
        return history.select(m -> m.getType() == type);
    }

    /** 获取指定来源的消息历史 */
    public static Seq<Message> getHistoryBySource(String source) {
        return history.select(m -> source.equals(m.getSource()));
    }

    /** 获取最近 N 条消息 */
    public static Seq<Message> getRecent(int count) {
        int start = Math.max(0, history.size - count);
        return history.select(start, history.size);
    }

    /** 获取消息总数 */
    public static int getCount() {
        return history.size;
    }

    /** 清空消息历史 */
    public static void clear() {
        history.clear();
    }

    // ==================== 内部实现 ====================

    /** 分发消息：存入历史 → 通知监听器 */
    private static void dispatch(Message message) {
        history.add(message);
        // 超出上限时移除最旧的
        if (history.size > MAX_HISTORY) {
            history.remove(0, history.size - MAX_HISTORY);
        }
        // 通知所有监听器
        for (Cons<Message> listener : listeners) {
            try {
                listener.get(message);
            } catch (Exception e) {
                SiliconLog.warn("MessageSystem listener error: " + e.getMessage());
            }
        }
    }
}
