package silicon.util;

import arc.Core;
import arc.audio.Sound;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.Vars;

/**
 * 模组音效<b>唯一入口</b>（注册 + 播放）。所有模组自带的 {@code sounds/*.ogg} 都集中在本类注册，
 * 其他方块 / 机器一律通过本类的 API 取音效（{@link #load}）或直接播放（{@link #play...}）——
 * 不要再直接触碰文件与资源系统，也不要另开音效加载逻辑。
 * <p>
 * 新增音效步骤：把 {@code .ogg} 放进模组根目录的 {@code sounds/} 文件夹，再在本类顶部的
 * 「声音注册表」中加一个命名访问方法即可（如 {@link #powerProtector()}）。
 * <p>
 * 实现说明：v8 会把模组的 {@code sounds/} 文件夹注册进 {@link Vars#tree}（键为 {@code sounds/<名称>.ogg}），
 * 本类即从该树取文件，并用 {@link Sound#load(byte[], boolean)} 的流模式（支持 ogg）<strong>立即</strong>
 * 载入，避免懒加载导致首次播放无声；结果按文件名缓存。专用服务器 / 音频不可用 / 文件缺失时，
 * 取音效返回 {@code null}，播放静默跳过（调用方无需判空）。音效只在客户端播放。
 */
public class SiliconSounds {
    // ==================== 声音注册表：集中定义所有模组音效 ====================

    /** 收到新消息时面板播放的默认音效 {@code sounds/new-message.ogg}。 */
    public static Sound newMessage() {
        return load("new-message");
    }

    /** 垃圾桶按钮（清空面板）音效 {@code sounds/clean-panel.ogg}。 */
    public static Sound cleanPanel() {
        return load("clean-panel");
    }

    /** 电力保护器告警音效 {@code sounds/power-protector.ogg}。 */
    public static Sound powerProtector() {
        return load("power-protector");
    }

    // ==================== 通用获取与播放 API（方块 / 机器使用） ====================

    private static final ObjectMap<String, Sound> cache = new ObjectMap<>();

    private SiliconSounds() {}

    /** 按文件名（不含扩展名）取模组音效并把该音效注册进缓存；未注册 / 不可用时返回 null。 */
    public static Sound load(String name) {
        Sound cached = cache.get(name);
        if (cached != null) return cached;
        Sound sound = null;
        if (!Vars.headless && Core.audio != null && Core.audio.initialized()) {
            try {
                Fi file = Vars.tree.get("sounds/" + name + ".ogg");
                if (file != null && file.exists()) {
                    sound = new Sound();
                    sound.file = file;
                    sound.load(file.readBytes(), true); // 流式载入：支持 ogg
                } else {
                    Log.warn("[Silicon] Sound 'sounds/@.ogg' not found in file tree.", name);
                }
            } catch (Throwable e) {
                Log.err("[Silicon] Failed to load sound '@': @", name, e);
            }
        }
        if (sound != null) {
            cache.put(name, sound);
        }
        return sound;
    }

    /** 以默认音量播放一个模组音效；未注册 / 不可用 / 非客户端时静默跳过。返回播放的声音格位（voice），失败为 -1。 */
    public static int play(String name) {
        return play(name, 1f);
    }

    /** 以指定音量（0~1）播放一个模组音效。 */
    public static int play(String name, float volume) {
        Sound s = load(name);
        return s != null ? s.play(volume) : -1;
    }

    /** 在世界坐标（屏幕像素）播放一个模组音效，随距离做音量/声像衰减（用于方块与机器发声）。 */
    public static int play(String name, float x, float y) {
        return play(name, x, y, 1f);
    }

    /** 在世界坐标（屏幕像素）以指定音量播放一个模组音效。 */
    public static int play(String name, float x, float y, float volume) {
        Sound s = load(name);
        return s != null ? s.at(x, y, volume) : -1;
    }
}