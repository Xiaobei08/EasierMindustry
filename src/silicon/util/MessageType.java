package silicon.util;

import arc.graphics.Color;

/**
 * 消息类型枚举，定义不同级别的消息及其对应颜色。
 */
public enum MessageType {
    /** 普通信息 */
    INFO("info", Color.valueOf("a9d8f9")),
    /** 警告信息 */
    WARNING("warning", Color.valueOf("f9d87a")),
    /** 错误信息 */
    ERROR("error", Color.valueOf("f97a7a")),
    /** 成功信息 */
    SUCCESS("success", Color.valueOf("7af99d"));

    public final String key;
    public final Color color;

    MessageType(String key, Color color) {
        this.key = key;
        this.color = color;
    }
}
