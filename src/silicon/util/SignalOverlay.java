package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.ui.Label;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Player;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalJammer;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalRelay.SignalRelayBuild;
import silicon.world.blocks.signal.SignalSource;
import silicon.world.blocks.signal.SignalSource.SignalSourceBuild;
import silicon.util.SatelliteManager;

/**
 * 信号覆盖显示：H 键查看信号源覆盖。
 * 无论设置开关如何，按住 H 键始终显示信号强度；
 * 设置开启「切换」时，按一下 H 键可切换显示/隐藏（按住仍优先显示）。
 * 进入显示模式时屏幕下方中间显示一行提示小字。
 * 缩放视角较小时（视野 &gt; 阈值）逐格以数字显示信号强度；
 * 缩放视角较大时以绿色显示信号范围（强度随距离变淡），无信号显示为灰色。
 */
public class SignalOverlay {
    /** 信号显示颜色渐变：高强度=深蓝，低强度=浅蓝（不同强度颜色差异明显） */
    public static final Color DEEP_BLUE = Color.valueOf("1e4fb0");
    public static final Color LIGHT_BLUE = Color.valueOf("9dc3ff");
    /** 信号源选中/放置预览的范围圆颜色（深蓝） */
    public static final Color SIGNAL_COLOR = Color.valueOf("3a6fe0");
    /** 无信号颜色（灰色） */
    public static final Color NO_SIGNAL_COLOR = Color.valueOf("9a9a9a");
    /** 信号源区分色板（备用：无编码时兜底；有编码时按 HSV 色相生成，颜色数量不限） */
    public static final Color[] SOURCE_COLORS = {
            Color.valueOf("e05555"), // 红
            Color.valueOf("e08a3a"), // 橙
            Color.valueOf("e0c43a"), // 黄
            Color.valueOf("9ec42a"), // 黄绿
            Color.valueOf("5fb04c"), // 绿
            Color.valueOf("2fbf8f"), // 青绿
            Color.valueOf("3ac0c0"), // 青
            Color.valueOf("3aa8e0"), // 天蓝
            Color.valueOf("4a6fe0"), // 蓝
            Color.valueOf("6a4ae0"), // 紫蓝
            Color.valueOf("8a4ae0"), // 紫
            Color.valueOf("bf4ae0"), // 品红
            Color.valueOf("e04a9a"), // 粉
            Color.valueOf("d07a4a"), // 棕
            Color.valueOf("9a9a9a"), // 灰
            Color.valueOf("c0c0c0"), // 银
    };
    /** 信号专属颜色缓存（编码 → Color），避免每帧分配 */
    private static final ObjectMap<String, Color> colorCache = new ObjectMap<>();
    /** 已分配的色相（度），用于为新信号选择与已有颜色差异最大的色相（保证颜色明显不同） */
    private static final Seq<Float> usedHues = new Seq<>();
    /** 色相使用次数：同色系（色相接近）时按次数交替亮度，进一步拉开区分 */
    private static final ObjectIntMap<Float> hueCount = new ObjectIntMap<>();
    /** 缩放阈值（相机视野宽度，像素）：视野宽于该值（缩小视角）显示蓝色范围，否则显示数字 */
    public static final float ZOOM_THRESHOLD_WIDTH = 600f;
    /** 预计算的强度数字字符串（0~15），避免每帧分配 */
    private static final String[] NUMBER_STRINGS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"};

    /** 信号专属颜色：色相动态分配——新编码选择与所有已用颜色色相距离最大、且与所在区块背景色相差异大的色相
     *  （避开背景相近色，优先补色方向；bgHue=-1 表示背景无彩/未知，不限制）。
     *  信号间区分：色相最远点（主）；信号极多导致色相被迫接近（同色系）时，按色相使用次数交替亮度（0.75/0.55/0.95）
     *  与地图区分：有彩背景按色相避开 ±45°，无彩背景靠固定亮度（0.75）天然对比。结果缓存复用 */
    public static Color signalColor(String code, float bgHue) {
        Color cached = colorCache.get(code);
        if (cached != null) return cached;
        float hue;
        float minDist; // 与已用色相的最小距离（用于判断是否同色系）
        if (usedHues.isEmpty()) {
            // 第一个：取编码哈希色相，若与背景色相近则偏移到补色方向
            int h0 = code.hashCode() & 0x7fffffff;
            hue = h0 % 360f;
            if (bgHue >= 0f && hueDist(hue, bgHue) < 45f) {
                hue = (bgHue + 180f) % 360f;
            }
            minDist = 360f;
        } else {
            // 贪心最远点：遍历候选色相（5° 步进），剔除与背景色相相近的候选，
            // 选与已用色相环距离最小者最大化的候选（颜色间必然明显可辨）
            float bestHue = 0f, bestMin = -1f;
            for (float cand = 0f; cand < 360f; cand += 5f) {
                if (bgHue >= 0f && hueDist(cand, bgHue) < 45f) continue; // 与背景太近，跳过
                float curMin = 360f;
                for (int i = 0; i < usedHues.size; i++) {
                    float d = hueDist(cand, usedHues.get(i));
                    if (d < curMin) curMin = d;
                }
                if (curMin > bestMin) {
                    bestMin = curMin;
                    bestHue = cand;
                }
            }
            hue = bestHue;
            minDist = bestMin;
        }
        usedHues.add(hue);
        // 亮度：默认 75（0~100，arc HSVtoRGB 的 value 为百分比）；同色系（色相最小距离 < 30°）时
        // 按该色相使用次数交替 100/40/75（亮/暗/标准），明暗差异明显
        int count = hueCount.get(hue, 0);
        float value = 75f;
        if (minDist < 30f) {
            int m = count % 3;
            value = m == 1 ? 40f : (m == 2 ? 100f : 75f);
        }
        hueCount.put(hue, count + 1);
        // 饱和度 85%（0~100）；亮度按上取值——注意 arc HSVtoRGB 的 s/v 是 0~100 百分比（0~1 会导致近黑）
        Color c = Color.HSVtoRGB(hue, 85f, value);
        colorCache.put(code, c);
        return c;
    }

    /** 色相环最短距离（0~180） */
    static float hueDist(float a, float b) {
        float d = Math.abs(a - b);
        return d > 180f ? 360f - d : d;
    }

    /** RGB 颜色 → 色相（度，0~360；无彩/近灰返回 -1） */
    static float hueOf(Color c) {
        float r = c.r, g = c.g, b = c.b;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 0.01f) return -1f;
        float h;
        if (max == r) h = 60f * ((g - b) / (max - min));
        else if (max == g) h = 60f * (2f + (b - r) / (max - min));
        else h = 60f * (4f + (r - g) / (max - min));
        if (h < 0f) h += 360f;
        return h;
    }

    /** 世界坐标处地形区块颜色色相（基于地面 mapColor；无地面/无彩返回 -1） */
    static float groundHue(float wx, float wy) {
        mindustry.world.Tile t = Vars.world.tileWorld(wx, wy);
        if (t == null || t.floor() == null) return -1f;
        return hueOf(t.floor().mapColor);
    }

    /** 信号源颜色：基于信号编码 + 所在区块背景色相生成（同一信号源颜色稳定） */
    public static Color sourceColor(SignalSourceBuild sb) {
        if (sb.signal == null) return SOURCE_COLORS[0];
        return signalColor(sb.signal.name, groundHue(sb.x, sb.y));
    }

    /** 中继器颜色：基于世界坐标 + 该处区块背景色相生成（标识不同转发来源，稳定） */
    public static Color relayColor(SignalRelayBuild rb) {
        return signalColor("R" + ((int) rb.x * 7 + (int) rb.y * 13), groundHue(rb.x, rb.y));
    }

    /** 卫星覆盖颜色：按编码专属色（与所选信号源同色，编码相同命中同一缓存）；
     *  无编码（未绑定兜底记录）→ 浅蓝→深蓝强度渐变 */
    static Color satelliteColor(String code, float t, Color out) {
        if (code != null) {
            out.set(signalColor(code, -1f));
        } else {
            out.set(LIGHT_BLUE).lerp(DEEP_BLUE, t);
        }
        return out;
    }

    /** 覆盖中某建筑的信号颜色：中继器绑定信号源后与所选源同色（信号身份一致），未绑定用自身色 */
    static Color buildingColor(Building b) {
        if (b instanceof SignalSourceBuild sb) return sourceColor(sb);
        if (b instanceof SignalRelayBuild rb) {
            SignalSource.SignalSourceBuild src = rb.findSource();
            return src != null ? sourceColor(src) : relayColor(rb);
        }
        return LIGHT_BLUE;
    }

    private static boolean visible = false;
    private static boolean toggleVisible = false;
    private static boolean prevDown = false;
    /** 淡入淡出透明度（0~1，每帧向目标过渡） */
    private static float displayAlpha = 0f;
    /** 当前显示模式（true=范围，false=数字），用于切换时淡入淡出 */
    private static boolean lastRangeMode = false;
    /** 底部提示标签 */
    private static Label hintLabel;

    public static void init() {
        // 无头服务器跳过（无渲染循环/无 UI），避免访问 Vars.ui.hudGroup 崩溃
        if (Vars.headless) return;
        // 渲染循环最末段绘制（drawOver：全部世界渲染——方块/单位/子弹/特效——之后触发），
        // 覆盖层画在子弹与枪口加色光之上，避免射击时被冲淡
        Events.run(EventType.Trigger.drawOver, SignalOverlay::update);
        // 客户端加载完成后创建底部提示标签
        Events.on(EventType.ClientLoadEvent.class, e -> {
            // 模组重载等场景重复触发时先移除旧标签，避免泄漏
            if (hintLabel != null) hintLabel.remove();
            hintLabel = new Label(Core.bundle.get("signal.overlay.hint"), Styles.outlineLabel);
            hintLabel.setFontScale(0.7f);
            hintLabel.visible = false;
            Vars.ui.hudGroup.addChild(hintLabel);
        });
    }

    /** 世界加载时重置：清空颜色缓存/色相分配，避免跨世界累积（新地图重新分配颜色、缓存不泄漏） */
    public static void reset() {
        colorCache.clear();
        usedHues.clear();
        hueCount.clear();
        // 重置显示状态，避免上一个世界的 H 键切换状态残留
        visible = false;
        toggleVisible = false;
        prevDown = false;
        displayAlpha = 0f;
        // 显示模式一并复位,否则跨图首帧 rangeMode 与残留的 lastRangeMode 不一致
        // 会触发一次无意义的淡入重启
        lastRangeMode = false;
    }

    static void update() {
        // 先取局部引用再判空，避免 null 检查与 team() 调用之间玩家断线导致的空指针
        Player player = Vars.player;
        if (player == null) return;
        Team team = player.team();
        boolean toggleMode = Core.settings.getBool("signal.hkey.toggle", true);
        // H 键走官方 KeyBind 通道（Silicon.init 注册，默认 H，可在按键设置重绑定；
        // headless 下 bind 未注册/无输入源时恒为 false，覆盖层在专用服务器本就不绘制）
        boolean hold = silicon.Silicon.keySignalView != null && Core.input.keyDown(silicon.Silicon.keySignalView);
        // 无论设置开关如何，按住 H 始终显示信号强度
        if (toggleMode) {
            // 切换模式：按一下 H 翻转切换状态（按住优先显示）
            if (hold && !prevDown) toggleVisible = !toggleVisible;
            prevDown = hold;
            visible = hold || toggleVisible;
        } else {
            // 按住模式：按住显示，松开隐藏
            visible = hold;
        }
        // 淡入淡出：透明度每帧向目标过渡（约 6 帧完成）
        displayAlpha = Mathf.lerp(displayAlpha, visible ? 1f : 0f, 0.15f);
        if (displayAlpha > 0.01f) {
            drawOverlay(team, displayAlpha);
        }
        if (visible) {
            showHint();
        } else if (displayAlpha < 0.01f) {
            hideHint();
        }
    }

    /** 显示底部提示小字（屏幕下方中间） */
    static void showHint() {
        if (hintLabel == null) return;
        hintLabel.setPosition(Core.graphics.getWidth() / 2f - hintLabel.getPrefWidth() / 2f, 40f);
        hintLabel.visible = true;
    }

    static void hideHint() {
        if (hintLabel != null) hintLabel.visible = false;
    }

    static void drawOverlay(Team team, float alpha) {
        // 显式抬高绘制层级：drawOver 时点当前 z 不确定，锁定在 overlayUI 之上保证压过所有世界内容
        Draw.z(mindustry.graphics.Layer.overlayUI + 1f);
        // 视野宽（缩小视角）显示蓝色范围；视野窄（放大视角）显示数字
        boolean rangeMode = Core.camera.width >= ZOOM_THRESHOLD_WIDTH;
        // 模式切换时重新淡入（数字 ↔ 范围淡入淡出）
        if (rangeMode != lastRangeMode) {
            lastRangeMode = rangeMode;
            displayAlpha = 0f;
        }
        if (rangeMode) {
            // 范围模式：逐格合成绘制（地面信号源与在轨卫星同一模型）——每格只画最强一路，
            // 卫星信号与信号源共用同一透明度公式与强度标度，不再有独立的卫星覆盖盘二次叠画
            drawRangeComposite(team, alpha);
        } else {
            // 数字模式：逐格取各信道最大有效信号（含底噪/CCI/ACI/干扰器），每格只绘制一次
            drawNumbersOverlay(team, alpha);
        }
        Draw.reset();
    }

    /** 每信道有效强度/最强源缓冲（静态复用） */
    private static final float[] effBuf = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final Building[] srcBuf = new Building[SignalJammer.CHANNEL_MAX + 1];
    /** 最强来源/最强卫星编码出参复用（渲染线程内串行使用,两处 draw 循环共享一份） */
    private static final Building[] bestSrcTmp = new Building[1];
    private static final String[] bestCodeTmp = new String[1];

    /** 每格最大有效信号（一次遍历所有信道，与卫星层 RSS 功率合成）；返回有效强度、最强来源与最强卫星编码。
     *  卫星层模型与绑定/中继一致（SINR 比值制）：覆盖该格的卫星各自按信噪比折算有效强度
     *  （satelliteEffAt，底噪在质量因子内）后对数叠加（stackEff），不再末尾扣底噪 */
    static float bestSignal(Team team, float wx, float wy, Building[] bestSrcOut, String[] bestCodeOut) {
        // 批量计算所有信道（一次遍历全部源，按信道分摊——比逐信道调用快约 5 倍）
        SignalChannel.effectiveAll(team, wx, wy, effBuf, srcBuf);
        float bestStr = 0f;
        Building bestSrc = null;
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            if (effBuf[ch] > bestStr) {
                bestStr = effBuf[ch];
                bestSrc = srcBuf[ch];
            }
        }
        float groundStr = bestStr; // 地面层合成前强度（RSS 合成保留双方功率，着色归属按贡献较大方）
        // 卫星层：覆盖该格的在轨卫星各自按信噪比折算有效强度（底噪在质量因子内）、对数叠加；
        // 记录最强贡献者的编码用于着色。
        // 上行门控：有编码的卫星在其地面源全部消失后停止广播（未绑定记录无编码语义，仍提供原始覆盖）
        float satSum = 0f, satBest = 0f;
        String satTop = null;
        for (SatelliteManager.SatelliteRecord r : SatelliteManager.satellites(team)) {
            if (r.code != null && !SignalChannel.hasLiveSource(team, r.code)) continue;
            float e = SatelliteManager.satelliteEffAt(r, wx, wy);
            if (e <= 0f) continue;
            satSum += e;
            if (e > satBest) {
                satBest = e;
                satTop = r.code;
            }
        }
        float satStr = Math.max(0f, SatelliteManager.stackEff(satSum, satBest));
        // 卫星×地面 RSS 功率合成：total = √(g² + s²)——同信道功率相加，卫星对已有地面覆盖的
        // 区域仍是真实增益（抗干扰裕度实质提升），不再是"地面弱时的替补"。
        // 着色归属保持贡献较大的一方：地面=建筑专属色，卫星=编码色（未绑定蓝渐变）
        bestStr = (float) Math.sqrt((double) groundStr * groundStr + (double) satStr * satStr);
        if (satStr > groundStr) {
            bestSrc = null; // 卫星层贡献占优
            bestCodeOut[0] = satTop; // 最强贡献卫星的编码（未绑定记录为 null → 蓝渐变）
        } else {
            bestCodeOut[0] = null; // 地面层占优（或全零）：编码出参清空
        }
        bestSrcOut[0] = bestSrc;
        return bestStr;
    }

    /** 数字模式：可见区域内逐格取各信道最大有效信号，每格只绘制一次（字号覆盖一格 8px）；颜色取最强来源的专属色 */
    static void drawNumbersOverlay(Team team, float alpha) {
        Rect view = Core.camera.bounds(Tmp.r1);
        int x0 = (int) (view.x / 8f) - 1, x1 = (int) ((view.x + view.width) / 8f) + 1;
        int y0 = (int) (view.y / 8f) - 1, y1 = (int) ((view.y + view.height) / 8f) + 1;
        float digitAlpha = Core.settings.getInt("signal.digitAlpha", 80) / 100f;
        // 保存字体原始颜色与比例，绘制后恢复（try-finally 保证异常时也恢复）
        Color oldFontColor = Fonts.def.getColor();
        float oldScale = Fonts.def.getData().scaleX;
        // 字号按显示屏大小动态变化（非相机缩放）：动态检测屏幕（物理显示）高度与当前游戏（逻辑）分辨率高度。
        // 全屏 2K（uiScale=1）时二者相等 → 字号 0.2（基准）；高 DPI（uiScale>1）时按 1/uiScale 缩小，物理观感稳定
        float screenH = Core.graphics.getBackBufferHeight();
        float scale = Mathf.clamp(0.2f * Core.graphics.getHeight() / (screenH <= 0f ? 1440f : screenH), 0.1f, 0.5f);
        Fonts.def.getData().setScale(scale);
        Building[] bestSrc = bestSrcTmp;
        String[] bestCode = bestCodeTmp;
        try {
            // 单字符居中偏移：相对原 0.2 字号的 1/1.6，按当前字号比例缩放
            float k = scale / 0.2f;
            for (int gx = x0; gx <= x1; gx++) {
                for (int gy = y0; gy <= y1; gy++) {
                    float wx = gx * 8f, wy = gy * 8f; // 格子中心（像素）
                    float s = bestSignal(team, wx, wy, bestSrc, bestCode);
                    if (s <= 0f) continue;
                    int val = Mathf.round(s);
                    float t = s / SignalSource.MAX_STRENGTH;
                    // 颜色：最强来源的专属色（信号源/中继器不同色）；仅卫星信号时为最强贡献卫星的编码色（未绑定浅蓝→深蓝渐变）
                    if (bestSrc[0] != null) {
                        Tmp.c1.set(buildingColor(bestSrc[0]));
                    } else {
                        satelliteColor(bestCode[0], t, Tmp.c1);
                    }
                    Tmp.c1.a((0.6f + 0.4f * t) * digitAlpha * alpha);
                    // 复用预计算字符串避免分配；居中偏移随字号缩放
                    Fonts.def.setColor(Tmp.c1);
                    Fonts.def.draw(NUMBER_STRINGS[val < 0 ? 0 : (val > 15 ? 15 : val)], wx - 1f * k, wy - 1.6f * k);
                }
            }
        } finally {
            // 恢复默认颜色与字号，避免影响其他字体渲染
            Fonts.def.setColor(oldFontColor);
            Fonts.def.getData().setScale(oldScale);
        }
    }

    /** 范围模式（逐格合成）：每格取各信道最大有效信号，与卫星层（对数叠加扣底噪）取 max，用最强来源的
     *  专属颜色绘制（重叠/干扰区显示最强或空白）。卫星信号与地面信号源共用同一透明度公式
     *  (0.45+0.35t)·rangeAlpha·alpha 与同一强度标度（t = 强度/MAX_STRENGTH），每格只绘制一次——
     *  卫星覆盖透明度与信号源完全一致 */
    static void drawRangeComposite(Team team, float alpha) {
        Rect view = Core.camera.bounds(Tmp.r1);
        float rpx = SignalSource.RADIUS * 8f;
        // 格子范围：视口外扩一个覆盖半径（源在视口外但覆盖进入视口）
        int x0 = (int) ((view.x - rpx) / 8f) - 1, x1 = (int) ((view.x + view.width + rpx) / 8f) + 1;
        int y0 = (int) ((view.y - rpx) / 8f) - 1, y1 = (int) ((view.y + view.height + rpx) / 8f) + 1;
        // 范围模式透明度（0~100，设置项）
        float rangeAlpha = Core.settings.getInt("signal.rangeAlpha", 45) / 100f;
        Building[] bestSrc = bestSrcTmp;
        String[] bestCode = bestCodeTmp;
        for (int gx = x0; gx <= x1; gx++) {
            for (int gy = y0; gy <= y1; gy++) {
                float wx = gx * 8f, wy = gy * 8f; // 格子中心（像素）
                float s = bestSignal(team, wx, wy, bestSrc, bestCode);
                if (s <= 0f) continue;
                float t = s / SignalSource.MAX_STRENGTH;
                // 最强来源的专属颜色（仅卫星时为最强贡献卫星的编码色，未绑定浅蓝），不透明度随强度
                if (bestSrc[0] != null) {
                    Draw.color(buildingColor(bestSrc[0]), (0.45f + 0.35f * t) * rangeAlpha * alpha);
                } else {
                    satelliteColor(bestCode[0], t, Tmp.c2);
                    Draw.color(Tmp.c2, (0.45f + 0.35f * t) * rangeAlpha * alpha);
                }
                Fill.rect(wx, wy, 8f, 8f);
            }
        }
    }
}
