package silicon.world.blocks.signal;

import arc.Core;
import arc.math.Mathf;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.game.Team;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.meta.Stat;

/**
 * 信号干扰器（1×1）：在指定信道（1~5，或全信道 ALL）发射压制噪声。
 * 干扰强度与信号强度同模型（中心 15，随距离高斯衰减）；某处信号强度减去干扰强度，
 * 差值 ≤ 0 时该处信号被完全压制（H 覆盖中无信号）。
 */
public class SignalJammer extends Block {
    /** 全信道模式值 */
    public static final int ALL = -1;
    /** 信道范围（1~5，共 5 个信道） */
    public static final int CHANNEL_MAX = 5;

    public SignalJammer(String name) {
        super(name);
        buildType = SignalJammerBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        update = true;
        configurable = true;
        config(Integer.class, (SignalJammerBuild b, Integer v) -> b.jamChannel = Math.max(-1, Math.min(CHANNEL_MAX, v)));
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.powerRange, SignalSource.RADIUS + " tiles");
    }

    /** 干扰器缓存（全局：干扰信道是跨队共享频段——任何队伍的干扰器都压制范围内的同信道信号，不分敌我） */
    private static final Seq<SignalJammerBuild> jammerList = new Seq<>();
    private static boolean dirty = true;

    public static void markDirty() {
        dirty = true;
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        jammerList.clear();
        for (Building b : Groups.build) {
            if (b instanceof SignalJammerBuild jb) {
                jammerList.add(jb);
            }
        }
    }

    /** 全部干扰器（走缓存，跨队伍） */
    public static Seq<SignalJammerBuild> allJammers() {
        rebuildCache();
        return jammerList;
    }

    /** 位置 (wx,wy) 处的同信道（或全信道）干扰强度（0~15，与信号强度同模型衰减；关闭的干扰器不干扰）。
     *  不分队伍：敌方干扰器同样压制我方该信道信号（H 覆盖中敌方干扰区不再显示我方信号）。 */
    public static float strengthAt(int channel, float wx, float wy) {
        float best = 0f;
        for (SignalJammerBuild jb : allJammers()) {
            if (!jb.enabled) continue; // 关闭（enabled=false）不发射干扰
            if (jb.jamChannel != ALL && jb.jamChannel != channel) continue;
            float s = SignalSource.strengthAt(jb.x, jb.y, wx, wy);
            if (s > best) best = s;
        }
        return best;
    }

    public class SignalJammerBuild extends Building {
        /** 干扰信道（1~5，-1=全信道） */
        public int jamChannel = ALL;

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            SignalJammer.markDirty();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            SignalJammer.markDirty();
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            // 夺取/换队：干扰信道归属变化，立即失效缓存（与增删同理）
            SignalJammer.markDirty();
        }

        /** 配置面板：信道选择（1~5 + 全信道，灰底面板） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                // 标题跨满整行（含全信道共 6 个按钮）、居中、原版黄色（避免挤占首列导致按钮间距不均）
                t.add(Core.bundle.get("block.silicon-signal-jammer.channel")).colspan(CHANNEL_MAX + 1).center()
                        .color(mindustry.graphics.Pal.accent).pad(2f);
                t.row();
                ButtonGroup<TextButton> group = new ButtonGroup<>();
                TextButton allBtn = new TextButton(Core.bundle.get("block.silicon-signal-jammer.all"), Styles.flatTogglet);
                allBtn.setChecked(jamChannel == ALL);
                allBtn.clicked(() -> configure(ALL));
                group.add(allBtn);
                t.add(allBtn).size(68f, 40f).pad(1f);
                for (int i = 1; i <= CHANNEL_MAX; i++) {
                    TextButton btn = new TextButton(String.valueOf(i), Styles.flatTogglet);
                    btn.setChecked(jamChannel == i);
                    int ch = i;
                    btn.clicked(() -> configure(ch));
                    group.add(btn);
                    t.add(btn).size(44f, 40f).pad(1f);
                }
            }).pad(4f);
        }

        /** 选中显示：干扰信道 */
        @Override
        public void display(Table table) {
            super.display(table);
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-signal-jammer.channel.current",
                    jamChannel == ALL ? Core.bundle.get("block.silicon-signal-jammer.all") : String.valueOf(jamChannel))).pad(2f);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(jamChannel);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            jamChannel = read.i();
        }
    }
}
