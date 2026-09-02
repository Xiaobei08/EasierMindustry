package silicon.util;

/**
 * 消息数据类，存储单条消息的内容、类型、时间戳等信息。
 */
public class Message {
    /** 消息文本 */
    private final String text;
    /** 消息类型 */
    private final MessageType type;
    /** 创建时间戳（毫秒） */
    private final long timestamp;
    /** 来源标识（可选，如方块名称、系统名称等） */
    private final String source;

    public Message(String text, MessageType type, String source) {
        this.text = text;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.source = source;
    }

    public Message(String text, MessageType type) {
        this(text, type, null);
    }

    public String getText() {
        return text;
    }

    public MessageType getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSource() {
        return source;
    }

    /** 是否有来源标识 */
    public boolean hasSource() {
        return source != null && !source.isEmpty();
    }

    @Override
    public String toString() {
        String prefix = hasSource() ? "[" + source + "] " : "";
        return prefix + "[" + type.key + "] " + text;
    }
}
