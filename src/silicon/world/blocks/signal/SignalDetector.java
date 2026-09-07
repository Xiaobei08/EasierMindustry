package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
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
 * 无 sprite：{@link #load()} 程序化生成 32×32 造型（深色底座 + 双接收环 + 中心探头），
 * 与卫星机型 uiIcon 的生成方式一致；作者后续补 sprite（atlas 键 = 方块名）后可删该覆写。
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
    }

    @Override
    public void load() {
        super.load();
        // 原版 load 会把无 sprite 方块的 region 落到 error 白块——统一替换为程序化造型
        region = generatedIcon();
        uiIcon = fullIcon = region;
    }

    /** 程序化 32×32 图标：深色底座 + 双接收环 + 中心探头（雷达观感） */
    private static TextureRegion generatedIcon() {
        Pixmap px = new Pixmap(32, 32);
        px.fillRect(2, 2, 28, 28, Color.valueOf("2f2f38").rgba());
        // 底座描边
        px.drawCircle(16, 16, 14, Color.valueOf("55555f").rgba());
        // 双接收环（外环亮、内环淡）
        px.drawCircle(16, 16, 10, Color.valueOf("c0c8d8").rgba());
        px.drawCircle(16, 16, 6, Color.valueOf("6a7284").rgba());
        // 中心探头
        px.fillCircle(16, 16, 2, Color.white.rgba());
        // 天线短划（指向右上）
        px.drawLine(18, 18, 24, 24, Color.valueOf("c0c8d8").rgba());
        Texture tex = new Texture(px);
        px.dispose();
        return new TextureRegion(tex);
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
