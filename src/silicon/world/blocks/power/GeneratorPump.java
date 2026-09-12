package silicon.world.blocks.power;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Scaling;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.core.UI;
import mindustry.entities.Effect;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.world.consumers.*;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawDefault;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

import static mindustry.Vars.*;
import static mindustry.world.meta.StatValues.withTooltip;

public class GeneratorPump extends LiquidBlock {
    /**
     * The amount of power produced per tick in case of an efficiency of 1.0, which represents 100%.
     */
    public float powerProduction, powerConsumption;
    /** Pump amount per tile. */
    public float pumpAmount = 0.2f;
    /**
     * List of liquids that this pump can extract
     */
    public Seq<Liquid> canPumpLiquids = Seq.with();
    /**
     * Drawing component for rendering
     */
    public DrawBlock drawer = new DrawDefault();
    /** Interval in-between item consumptions, if applicable. */
    public float consumeTime = 60f * 5f;

    /**
     * Speed of warmup animation transition
     */
    public float warmupSpeed = 0.05f;
    /** Chance of generating visual effects */
    public float effectChance = 0.01f;
    /** Visual effect to display during generation */
    public Effect generateEffect = Fx.none;
    /** Range for effect generation */
    public float generateEffectRange = 3f;

    /** Optional item consumption filter */
    public @Nullable ConsumeItemFilter filterItem;
    /** Optional liquid consumption filter */
    public @Nullable ConsumeLiquidFilter filterLiquid;
    /** Cached liquid booster consumer */
    public @Nullable ConsumeLiquid boosterLiquid;

    public GeneratorPump(String name) {
        super(name);
        group = BlockGroup.liquids;
        floating = true;
        envEnabled = Env.terrestrial;
        update = true;
        hasLiquids = true;
        hasPower = true;
        outputsPower = true;
        conductivePower = true;
        outputsLiquid = true;
        consPower = new ConsumePower(0, 0, false);
    }

    @Override
    public void init() {
        filterItem = findConsumer(c -> c instanceof ConsumeItemFilter);
        filterLiquid = findConsumer(c -> c instanceof ConsumeLiquidFilter);
        if (findConsumer(f -> f instanceof ConsumeLiquid cl && cl.booster) instanceof ConsumeLiquid cl) {
            boosterLiquid = cl;
        }

        if (canPumpLiquids != null) {
            outputsLiquid = true;
            hasLiquids = true;
        }
        emitLight = true;
        lightRadius = 65f * size;
        super.init();
    }

    @Override
    public void setBars() {
        super.setBars();

        if (hasPower && outputsPower) {
            boolean buffered = consPower.buffered;
            float capacity = consPower.capacity;

            addBar("poweramount", entity -> new Bar(
                    () -> buffered ? Core.bundle.format("bar.poweramount",
                            Float.isNaN(entity.power.status * capacity) ? "<ERROR>" : UI.formatAmount((int)(entity.power.status * capacity))) :
                            Core.bundle.get("bar.power"),
                    () -> Pal.powerBar,
                    () -> Mathf.zero(consPower.requestedPower(entity)) && entity.power.graph.getPowerProduced() + entity.power.graph.getBatteryStored() > 0f ? 1f : entity.power.status)
            );

            addBar("power", (EasierMdyGeneratorPumpBuild entity) -> new Bar(() ->
                    Core.bundle.format("bar.poweroutput",
                            Strings.fixed(entity.getPowerProduction() * 60 * entity.timeScale(), 1)),
                    () -> Pal.powerBar,
                    () -> entity.productionEfficiency)
            );
        }
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.powerUse, powerConsumption * 60f, StatUnit.powerSecond);
        if (hasItems) {
            stats.add(Stat.productionTime, consumeTime / 60f, StatUnit.seconds);
        }

        if (hasLiquids) {
            stats.add(Stat.output, 60f * pumpAmount * size * size, StatUnit.liquidSecond);
        }

        if (findConsumer(f -> f instanceof ConsumeLiquidBase && f.booster) instanceof ConsumeLiquidBase consumeLiquidBase) {
            stats.remove(Stat.booster);
            stats.add(Stat.booster, table -> {
                table.row();
                table.table(c -> {
                    for(Liquid liquid : content.liquids()){
                        if(!consumeLiquidBase.consumes(liquid)) continue;

                        c.table(Styles.grayPanel, b -> {
                            b.image(liquid.uiIcon).size(40).pad(10f).left().scaling(Scaling.fit).with(i -> withTooltip(i, liquid, false));
                            b.table(info -> {
                                info.add(liquid.localizedName).left().row();
                                info.add(Strings.autoFixed(consumeLiquidBase.amount * 60f, 2) + StatUnit.perSecond.localized()).left().color(Color.lightGray);
                            });

                            b.table(bt -> {
                                bt.right().defaults().padRight(3).left();
                                if(powerProduction * 60 != Float.MAX_VALUE)
                                    bt.add("[stat]"
                                            + Strings.autoFixed(powerProduction * 60 * 1f
                                            + 0f, 2) + "[lightgray]" + StatUnit.powerUnits.localized()).pad(5);
                            }).right().grow().pad(10f).padRight(15f);
                        }).growX().pad(5).row();
                    }
                }).growX().colspan(table.getColumns());
                table.row();
            });
        }
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        Tile tile = world.tile(x, y);
        if(tile == null) return;

        float amount = 0f;
        Liquid liquidDrop = null;

        for(Tile other : tile.getLinkedTilesAs(this, tempTiles)){
            if(canPump(other)){
                if(liquidDrop != null && other.floor().liquidDrop != liquidDrop){
                    liquidDrop = null;
                    break;
                }
                liquidDrop = other.floor().liquidDrop;
                amount += other.floor().liquidMultiplier;
            }
        }

        if(liquidDrop != null){
            float width = drawPlaceText(Core.bundle.formatFloat("bar.pumpspeed", amount * pumpAmount * 60f, 0), x, y, valid);
            float dx = x * tilesize + offset - width/2f - 4f, dy = y * tilesize + offset + size * tilesize / 2f + 5, s = iconSmall / 4f;
            float ratio = (float)liquidDrop.fullIcon.width / liquidDrop.fullIcon.height;
            Draw.mixcol(Color.darkGray, 1f);
            Draw.rect(liquidDrop.fullIcon, dx, dy - 1, s * ratio, s);
            Draw.reset();
            Draw.rect(liquidDrop.fullIcon, dx, dy, s * ratio, s);
        }
    }

    /** 检查图块能否生产某种液体 */
    protected boolean canPump(Tile tile){
        return tile != null && tile.floor().liquidDrop != null && canPumpLiquids.contains(tile.floor().liquidDrop);
    }

    @Override
    public TextureRegion[] icons(){
        return drawer.finalIcons(this);
    }

    public class EasierMdyGeneratorPumpBuild extends LiquidBuild {
        /** Current warmup progress for animations */
        public float warmup, efficiencyMultiplier = 1f;
        /**
          The efficiency of the producer. An efficiency of 1.0 means 100%
         */
        public float productionEfficiency = 0.0f;

        //Pump
        /** Consumption timer for periodic operations */
        public float consTimer,totalProgress;
        /** The liquid type being pumped */
        public @Nullable Liquid liquidDrop = null;
        /** Amount of liquid available for pumping */
        public float amount = 0f;

        @Override
        public void updateTile() {
            boolean valid = efficiency > 0;

            productionEfficiency = efficiency * efficiencyMultiplier;

            //randomly produce the effect
            if (valid && Mathf.chanceDelta(effectChance)) {
                generateEffect.at(x + Mathf.range(generateEffectRange), y + Mathf.range(generateEffectRange));
            }

            if (valid && liquidDrop != null) {
                float maxPump = Math.min(liquidCapacity - liquids.get(liquidDrop), amount * pumpAmount * edelta());
                liquids.add(liquidDrop, maxPump);

                //does nothing for most pumps, as those do not require items.
                if((consTimer += delta()) >= consumeTime){
                    consume();
                    consTimer %= consumeTime;
                }

                warmup = Mathf.approachDelta(warmup, maxPump > 0.001f ? 1f : 0f, warmupSpeed);
            }else{
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
            }

            totalProgress += warmup * Time.delta;

            if (boosterLiquid != null
                    && liquids.get(boosterLiquid.liquid) > boosterLiquid.amount) {
                dumpLiquid(liquids.current());
            }
        }

        @Override
        public float getPowerProduction() {
            return shouldConsume() ?
                    (Mathf.lerp(0, powerProduction, optionalEfficiency)
                            - (amount != 0 ? powerConsumption : 0)) * productionEfficiency : 0;
        }

        @Override
        public void updateEfficiencyMultiplier(){
            if(filterItem != null){
                float m = filterItem.efficiencyMultiplier(this);
                if(m > 0) efficiencyMultiplier = m;
            }else if(filterLiquid != null){
                float m = filterLiquid.efficiencyMultiplier(this);
                if(m > 0) efficiencyMultiplier = m;
            }
        }

        //Pump
        @Override
        public void onProximityUpdate(){
            super.onProximityUpdate();

            amount = 0f;
            liquidDrop = null;

            for(Tile other : tile.getLinkedTiles(tempTiles)){
                if(canPump(other)){
                    liquidDrop = other.floor().liquidDrop;
                    amount += other.floor().liquidMultiplier;
                }
            }
        }

        @Override
        public boolean shouldConsume(){
            // 自举守卫:本方块 outputsPower 且带非缓冲 consPower → 同时进 producers+consumers。
            // 冷启动电网(本泵是第一台/唯一发电机)上 produced=0 → coverage=0 → status=0 →
            // 永不泵水 → 永不发电,自举死锁,空电池也无济于事(存量 0 不放电)。
            // 电网尚无任何产出时放行消费让泵转起来,此后正常按 status 门控。
            boolean graphCold = power != null && power.graph != null
                    && power.graph.getLastPowerProduced() <= 0.0001f;
            return enabled && (amount != 0 || liquids.currentAmount() != 0)
                    && (power.status != 0 || graphCold);
        }

        @Override
        public void draw(){
            Liquid liq = liquids.current();
            if(liq != null && liquids.currentAmount() > 0.001f){
                LiquidBlock.drawTiledFrames(size, x, y, 0f, liq, liquids.currentAmount() / liquidCapacity);
            }
            drawer.draw(this);
        }

        @Override
        public void drawLight(){
            super.drawLight();
            drawer.drawLight(this);
        }

        @Override
        public void pickedUp(){
            amount = 0f;
        }

        @Override
        public double sense(LAccess sensor){
            if(sensor == LAccess.efficiency) return shouldConsume() ? efficiency : 0f;
            if(sensor == LAccess.totalLiquids) return liquidDrop == null ? 0f : liquids.get(liquidDrop);
            return super.sense(sensor);
        }

        @Override
        public float warmup(){
            return warmup;
        }

        @Override
        public float progress(){
            return Mathf.clamp(consTimer / consumeTime);
        }

        @Override
        public float totalProgress(){
            return totalProgress;
        }

        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.f(warmup);
            write.f(efficiencyMultiplier);
            write.f(productionEfficiency);
            write.f(consTimer);
            write.f(totalProgress);
            write.s(liquidDrop == null ? -1 : liquidDrop.id);
            write.f(amount);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            warmup = read.f();
            efficiencyMultiplier = read.f();
            productionEfficiency = read.f();
            consTimer = read.f();
            totalProgress = read.f();
            short liquidId = read.s();
            liquidDrop = liquidId >= 0 ? content.liquids().get(liquidId) : null;
            amount = read.f();
        }
    }
}
