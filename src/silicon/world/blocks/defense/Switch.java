package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.util.Eachable;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.BlockGroup;
import silicon.util.SiliconTmp;

import static mindustry.Vars.player;

public class Switch extends Block {
    TextureRegion state[];

    public Switch(String name) {
        super(name);
        update = true;
        solid = true;
        configurable = true; // 可配置：支持按钮式切换
        sync = true; // 操纵另一端 enabled 的控制块：writeBase 服务器快照兜底（同原版 SwitchBlock）
        rotate = true;
        group = BlockGroup.logic;
        // 配置同步开关自身状态 fE（联网经 Call.tileConfig 全端一致）；front 目标由 updateTile 同帧应用
        config(Boolean.class, (building, value) -> ((SwitchBuild) building).fE = value);
        state = new TextureRegion[2];
    }

    @Override
    public void load() {
        super.load();
        state[0] = Core.atlas.find(name + "-off");
        state[1] = Core.atlas.find(name + "-on");
//        state[0].flip(true,true);
//        state[1].flip(true,true);
        region = state[0];
    }

    @Override
    public void drawDefaultPlanRegion(BuildPlan plan, Eachable<BuildPlan> list){
        int trns = size / 2 + 1;
        Building front = Vars.world.build(plan.tile().x + Geometry.d4(plan.rotation).x * trns, plan.tile().y + Geometry.d4(plan.rotation).y * trns);
        float a = Draw.getColorAlpha();
        Draw.rect(front != null && front.enabled ? state[1] : state[0], plan.drawx(), plan.drawy(), !rotate || !rotateDraw ? 0 : plan.rotation * 90 + 90);
        if(plan.worldContext && player != null && teamRegion != null && teamRegion.found()){
            if(teamRegions[player.team().id] == teamRegion) Draw.color(player.team().color, a);
            Draw.rect(teamRegions[player.team().id], plan.drawx(), plan.drawy());
            Draw.color(1f, 1f, 1f, a);
        }

        drawPlanConfig(plan, list);
    }

    @Override
    public void placeEnded(Tile tile, @Nullable Unit builder, int rotation, @Nullable Object config) {
        if (tile.build instanceof SwitchBuild build && build.front() != null
            && build.front().team == build.team) { // #28 同队校验
            build.fE = build.front().enabled;
        }
    }

    public class SwitchBuild extends Building {
        boolean fE;
        @Override
        public void drawSelect() {
            super.drawSelect();
            if (front() == null || (front() instanceof SwitchBuild)) return;
            Drawf.selected(front(), SiliconTmp.c1.set(front().enabled ? Color.green : Color.red).a(Mathf.absin(4f, 1f)));

        }

        @Override
        public void draw() {
            super.draw();
            Draw.rect(fE ? state[1] : state[0], x, y, this.drawrot() + 90);
        }



        @Override
        public void updateTile() {
            super.updateTile();
            // #28 同队校验：不控制其它队伍建筑
            if (front() != null && front().team == team && front().enabled != fE) front().enabled = fE;
        }

/**
         * 点按切换：对同队且非开关的目标建筑翻转其启用状态（标准 configure 链路，联网全端一致，
         * 与原版 SwitchBlock 相同）。返回 false 表示不弹配置菜单——点按即切换。
         */
        @Override
        public boolean configTapped() {
            // #28 同队校验
            if (front() != null && front().team == team && !(front() instanceof SwitchBuild)) {
                configure(!fE);
                Sounds.click.at(this);
            }
            return false;
        }

        /**
         * Writes building data to save a file
         *
         * @param write The writer object
         */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(fE);
        }

        /**
         * Reads building data from a save file
         *
         * @param read     The reader object
         * @param revision The save revision
         */
        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            fE = read.bool();
        }

        @Override
        public Boolean config() {
            return fE;
        }
    }
}
