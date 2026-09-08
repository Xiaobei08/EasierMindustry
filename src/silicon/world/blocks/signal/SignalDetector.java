package silicon.world.blocks.signal;

import arc.Core;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.meta.BlockGroup;

/**
 * 信号检测器（1×1 纯测量设备）：配置界面内嵌 {@link silicon.ui.SignalSpectrum}——
 * 显示所在点位 5 条信道的占用计数、干扰功率 I 与可用有效强度条（SINR 比值制），
 * 用于在远离信号源/中继器的位置排查干扰、选择信道。不发射任何信号（对敌方零信息）。
 * <p>
 * sprite = assets/sprites/blocks/signal-detector.png（32×32，随 mod sprites 管线打包；
 * 方块的 region 必须是图集 AtlasRegion——程序化 TextureRegion 会在 createIcons 打包期
 * ClassCastException 崩溃，实测过的坑）。
 * <p>
 * 可配置但无 config 类（saveConfig=false）：点击仅打开频谱面板，无状态可存。
 * 注册在 Blocks.load() 末尾，不占旧存档方块 ID。
 */
public class SignalDetector extends Block {

    public SignalDetector(String name) {
        super(name);
        update = false;
        solid = true;
        configurable = true;
        saveConfig = false;
        group = BlockGroup.none;
        enableDrawStatus = false;
        // 占位配置类：BlockConfigFragment.showConfig 经 configTapped()（默认=配置类表非空）放行，
        // 无 config 类的纯面板方块点击不会弹 UI；布尔配置为无操作占位（saveConfig=false 不落存档）
        config(Boolean.class, (building, value) -> {});
    }

    public class SignalDetectorBuild extends Building {

        /** 配置面板：标题 + 本点信号频谱（无"当前信道"高亮——检测器不属于任何信道） */
        @Override
        public void buildConfiguration(arc.scene.ui.layout.Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                t.add(Core.bundle.get("block.silicon-signal-detector.title"))
                        .colspan(4).center().color(Pal.accent).pad(2f);
                t.row();
                // 无当前信道（-1 永不匹配 → 无行高亮）
                silicon.ui.SignalSpectrum.buildSection(t, this, () -> -1);
            }).pad(4f);
        }

        /** 选中显示：弱提示圈（本设备无信号范围，仅标识可点击） */
        @Override
        public void drawSelect() {
            super.drawSelect();
            arc.graphics.g2d.Draw.color(Team.derelict.color, 0.25f);
            arc.graphics.g2d.Lines.stroke(1.5f);
            arc.graphics.g2d.Lines.circle(x, y, 10f);
            arc.graphics.g2d.Draw.reset();
        }
    }
}
