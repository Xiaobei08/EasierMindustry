package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalJammer;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalSource;

/**
 * 信号频谱面板（信号源 / 信号中继器配置界面共享组件）：
 * 在方块所在点位对 5 条信道各渲染一行——信道色块 | 占用计数（全队源/中继 + 干扰器） |
 * 本点干扰功率 I（SINR 分母去掉底噪） | 该点可用有效强度条（0~15，SINR 折算后）。
 * <p>
 * SINR 比值制的对策信息入口：玩家据此判断"哪条信道干净"，切换信道规避 CCI/干扰器。
 * 当前所在信道行高亮（源=自身信道；中继=绑定源的实际转发信道）。
 * <p>
 * 性能：15 tick 节流刷新（配置面板常开时每秒 4 次全量重算 effectiveAll，与 H 覆盖同一次遍历成本）；
 * 标签 setText / Bar 值每节流周期更新，无逐帧字符串分配（Bar 的值标签由引擎逐帧渲染）。
 * 静态缓冲复用（同一时刻只有一个配置面板打开；面板关闭后 update 链随场景移除自动停止）。
 */
public class SignalSpectrum {
    /** 信道标识色（1~5）：面板视觉分组用，与覆盖绘制无关 */
    private static final Color[] CH_COLORS = {
            Color.valueOf("e05555"), // 1 红
            Color.valueOf("e0c43a"), // 2 黄
            Color.valueOf("5fb04c"), // 3 绿
            Color.valueOf("3aa8e0"), // 4 蓝
            Color.valueOf("8a4ae0"), // 5 紫
    };

    /** 当前信道行的高亮底（白 ui 低透明度染色） */
    private static final arc.scene.style.Drawable selBg =
            ((arc.scene.style.TextureRegionDrawable) Tex.whiteui).tint(new Color(1f, 1f, 1f, 0.08f));

    private static final float[] effBuf = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] intBuf = new float[SignalJammer.CHANNEL_MAX + 1];
    @SuppressWarnings("unchecked")
    private static final Building[] srcBuf = new Building[SignalJammer.CHANNEL_MAX + 1];
    private static final LabelRef[] occLabels = new LabelRef[SignalJammer.CHANNEL_MAX + 1];
    private static final LabelRef[] itfLabels = new LabelRef[SignalJammer.CHANNEL_MAX + 1];
    private static final Table[] rowTables = new Table[SignalJammer.CHANNEL_MAX + 1];

    /** 节流相位（面板打开期间递增；15 tick 一轮） */
    private static int tick;

    private SignalSpectrum() {
    }

    /**
     * 在配置面板表尾追加频谱区（自身占 4 列布局，调用方负责行距）。
     *
     * @param parent         配置面板根表（grayPanel 内容表）
     * @param at             频谱取样点所在建筑（取其坐标与队伍）
     * @param currentChannel 当前信道提供器（源=自身信道；中继=绑定源转发信道），行高亮用
     */
    public static void buildSection(Table parent, Building at, arc.func.Intp currentChannel) {
        // 标题 + 列头
        parent.add(Core.bundle.get("block.silicon-signal.spectrum.title"))
                .colspan(4).center().color(Pal.accent).padTop(6f).padBottom(1f);
        parent.row();
        parent.add(Core.bundle.get("block.silicon-signal.spectrum.ch")).center().color(Color.gray).pad(1f);
        parent.add(Core.bundle.get("block.silicon-signal.spectrum.occ")).center().color(Color.gray).pad(1f);
        parent.add(Core.bundle.get("block.silicon-signal.spectrum.itf")).center().color(Color.gray).pad(1f);
        parent.add(Core.bundle.get("block.silicon-signal.spectrum.str")).left().color(Color.gray).pad(1f);
        parent.row();

        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            // 色板按 0 基索引（CH_COLORS 长 5），信道号 1 基——处处减一，勿直接用信道号索引
            // lambda 捕获要求实际最终变量：c（0基色板）与 ci（信道副本）均为每轮新建
            final int c = ch - 1;
            final int ci = ch;
            Table row = new Table();
            // 信道色块 + 号
            row.add(new Image(Tex.whiteui)).color(CH_COLORS[c]).size(10f, 10f).padRight(4f);
            row.add(String.valueOf(ch)).width(12f).center();
            // 占用计数（节流刷新）
            LabelRef occ = new LabelRef();
            occ.label = row.add("").center().color(Color.lightGray).minWidth(58f).get();
            occLabels[ch] = occ;
            // 本点干扰功率 I
            LabelRef itf = new LabelRef();
            itf.label = row.add("").center().color(Color.lightGray).minWidth(46f).get();
            itfLabels[ch] = itf;
            // 有效强度条（Prov<CharSequence> 构造器：值标签逐帧渲染，无逐帧分配）
            row.add(new Bar(
                    () -> fmtEff(effBuf[ci]),
                    () -> CH_COLORS[c],
                    () -> effBuf[ci] / 15f
            )).growX().minWidth(78f).height(18f);

            parent.add(row).growX().colspan(4).pad(1f);
            parent.row();
            rowTables[ch] = row;
        }

        parent.update(() -> {
            tick = (tick + 1) % 15;
            if (tick != 0) return;
            SignalChannel.effectiveAll(at.team, at.x, at.y, effBuf, srcBuf, intBuf);
            int cur = currentChannel.get();
            for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
                int src = 0, jam = 0;
                for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(at.team)) {
                    if (sb.channel == ch) src++;
                }
                for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(at.team)) {
                    if (rb.active && rb.signalChannel() == ch) src++;
                }
                for (SignalJammer.SignalJammerBuild jb : SignalJammer.allJammers()) {
                    if (!jb.enabled) continue;
                    if (jb.jamChannel == SignalJammer.ALL || jb.jamChannel == ch) jam++;
                }
                occLabels[ch].label.setText(Core.bundle.format("block.silicon-signal.spectrum.src", src, jam));
                // I 标签必须预格式化：bundle.format 吃原始 float 会渲染全精度小数，溢出单元格重叠
                itfLabels[ch].label.setText(Core.bundle.format("block.silicon-signal.spectrum.i",
                        fmtEff(intBuf[ch] - SignalChannel.NOISE_FLOOR)));
                rowTables[ch].background(ch == cur ? selBg : null);
            }
        });
    }

    /** 有效强度格式化（1 位小数；0 显示为 "--" 更直观） */
    private static String fmtEff(float v) {
        return v <= 0.01f ? "--" : arc.util.Strings.fixed(v, 1);
    }

    /** 标签句柄（行构建时捕获，节流刷新时 setText） */
    private static class LabelRef {
        arc.scene.ui.Label label;
    }
}
