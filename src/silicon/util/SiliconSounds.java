package silicon.util;

import arc.Core;
import arc.audio.Sound;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.mod.Mods;
import silicon.Silicon;

/**
 * 模组音效<b>唯一入口</b>（自动检测 + 注册 + 播放）。所有模组自带的 {@code .ogg} 都集中在本类管理，
 * 其他方块 / 机器一律通过本类的 API 取音效（{@link #get}/{@link #load}）或直接播放（{@link #play...}）——
 * 不要再直接触碰文件与资源系统，也不要另开音效加载逻辑。
 * <p>
 * <b>自动注册</b>：首次访问时惰性扫描本模组资源目录 {@code assets/sounds/} 下全部 {@code .ogg}，
 * 按文件名（不含扩展名）预载入注册表。新增音效只需把 {@code .ogg} 放进 {@code assets/sounds/}，
 * 即可用 {@code SiliconSounds.play("名称")} 按需调用，无需再手写注册方法。
 * <p>
 * <b>防重叠属性</b>：声明为 {@code exclusive} 的音效（如 {@code power-protector}、{@code clean-panel}），
 * 在上一次播放未结束前拒绝新的播放请求；未声明的音效（如 {@code new-message}）允许重叠。
 * <p>
 * 实现说明：防重叠用标记位 + {@link Timer} 自动复位，不依赖 {@link Sound#getLength()} 时长判定。
 * 调用早于模组装载完成 / 音频未就绪时自动跳过并留待下次调用重试。
 * 专用服务器 / 音频不可用 / 文件缺失时，取音效返回 {@code null}，播放静默跳过。
 */
public class SiliconSounds {
    // ==================== 声音注册表：便捷访问器 ====================

    /** 收到新消息时面板播放的默认音效 {@code sounds/new-message.ogg}。 */
    public static Sound newMessage() { return get("new-message"); }

    /** 垃圾桶按钮（清空面板）音效 {@code sounds/clean-panel.ogg}。 */
    public static Sound cleanPanel() { return get("clean-panel"); }

    /** 电力保护器告警音效 {@code sounds/power-protector.ogg}。 */
    public static Sound powerProtector() { return get("power-protector"); }

    // ==================== 防重叠属性 ====================

    /** 禁止重叠播放的音效名集合。同一音效的上一次播放未结束前，拒绝所有新的播放请求。 */
    private static final ObjectSet<String> exclusive = new ObjectSet<>();

    /** 当前正在播放中的 exclusive 音效名（播放开始时添加，Timer 到期后移除）。 */
    private static final ObjectSet<String> playing = new ObjectSet<>();

    /** 各 exclusive 音效的预计持续时间（秒），播放开始后作为 Timer 延迟自动清除 playing 标记。 */
    private static final ObjectMap<String, Float> exclusiveDuration = new ObjectMap<>();

    static {
        exclusive.add("power-protector");
        exclusive.add("clean-panel");
        exclusiveDuration.put("power-protector", 3f);
        exclusiveDuration.put("clean-panel", 2f);
    }

    // ==================== 自动检测与注册 ====================

    private static final ObjectMap<String, Sound> cache = new ObjectMap<>();
    private static boolean scanned = false;

    private SiliconSounds() {}

    /** 自动检测并注册 {@code assets/sounds/} 下全部 {@code .ogg}：首次访问时惰性扫描，成功后置位；
     *  音频未就绪 / 模组尚未装载完成时返回且不置位，下次调用自动重试。 */
    private static void ensureScanned() {
        if (scanned) return;
        if (Vars.headless || Core.audio == null || !Core.audio.initialized()) return;
        try {
            Mods.LoadedMod mod = Silicon.MOD;
            Fi dir = mod != null ? findSoundsDir(mod.root) : null;
            if (dir == null || !dir.isDirectory()) return;
            for (Fi f : dir.list()) {
                if (f.name().endsWith(".ogg")) register(f.nameWithoutExtension(), f);
            }
            scanned = true;
        } catch (Throwable e) {
            Log.err("[Silicon] Failed to scan sounds/: @", e);
        }
    }

    private static Fi findSoundsDir(Fi root) {
        Fi a = root.child("assets").child("sounds");
        if (a.exists() && a.isDirectory()) return a;
        Fi b = root.child("sounds");
        return b.exists() && b.isDirectory() ? b : null;
    }

    /** 流式预载（支持 OGG），放入缓存。 */
    private static void register(String name, Fi file) {
        if (cache.containsKey(name) || !file.exists()) return;
        try {
            Sound sound = new Sound();
            sound.file = file;
            sound.load(file.readBytes(), true);
            cache.put(name, sound);
        } catch (Throwable e) {
            Log.err("[Silicon] Failed to load sound '@': @", name, e);
        }
    }

    // ==================== 通用获取与播放 API ====================

    /** 取注册表内的模组音效（按文件名，不含扩展名）；未注册 / 不可用时返回 null。 */
    public static Sound get(String name) {
        ensureScanned();
        return cache.get(name);
    }

    /** 兼容接口：等价于 {@link #get(String)}。 */
    public static Sound load(String name) { return get(name); }

    /** 模组音效正向解析（序列化用）：返回音效的资源名（不含扩展名）；无法识别返回空串。 */
    public static String nameOf(Sound s) {
        if (s == null) return "";
        for (ObjectMap.Entry<String, Sound> e : cache) {
            if (e.value == s) return e.key;
        }
        return s.file != null ? s.file.nameWithoutExtension() : "";
    }

    /** 播放一条消息的到达音效（消息面板调用）：未设定回退默认 {@code new-message}；不可用时静默。 */
    public static int playMessage(Sound custom) {
        Sound s = custom != null ? custom : newMessage();
        if (s == null) return -1;
        return guarded(nameOf(s), s, 1f, 0f, 0f, false);
    }

    /** 以默认音量播放一个模组音效。 */
    public static int play(String name) { return play(name, 1f); }

    /** 以指定音量播放一个模组音效。 */
    public static int play(String name, float volume) {
        Sound s = get(name);
        return s != null ? guarded(name, s, volume, 0f, 0f, false) : -1;
    }

    /** 在世界坐标播放一个模组音效，随距离做音量/声像衰减。 */
    public static int play(String name, float x, float y) { return play(name, x, y, 1f); }

    /** 在世界坐标以指定音量播放一个模组音效。 */
    public static int play(String name, float x, float y, float volume) {
        Sound s = get(name);
        return s != null ? guarded(name, s, volume, x, y, true) : -1;
    }

    // ==================== 内部：防重叠调度 ====================

    /** 带防重叠策略的播放核心：exclusive 音效在播放期间（由 Timer 标记）拒绝新请求，非 exclusive 自由重叠。 */
    private static int guarded(String name, Sound s, float volume, float x, float y, boolean positional) {
        if (exclusive.contains(name) && playing.contains(name)) {
            return -1; // 上一次播放未结束，拒绝本次请求
        }
        int id = positional ? s.at(x, y, volume) : s.play(volume);
        if (id >= 0 && exclusive.contains(name)) {
            playing.add(name);
            Float dur = exclusiveDuration.get(name);
            if (dur == null || dur <= 0) dur = 3f;
            final String n = name;
            Timer.schedule(() -> playing.remove(n), dur);
        }
        return id;
    }
}
