package silicon.world.blocks.power;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.ui.Bar;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.sandbox.PowerVoid;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;

import static silicon.Vars.powerChanged;
import static silicon.Vars.powerStored;

/**
 * RollGenerator - A dynamic power generator that produces power based on
 * current stored power and power change rates in the network
 * Power generation scales with network conditions and has adaptive limits
 */
public class RollGenerator extends PowerGenerator {
    /**
     * Percentage of stored power used for base power generation per second
     */
    public float powerStoredProductionPercentage = 0.01f;
    /**
     * Percentage of power change used for additional power generation
     */
    public float powerChangedProductionPercentage = 0.05f;
    /**
     * Speed of warmup animation transition
     */
    public float warmupSpeed = 0.1f;


    /**
     * Constructor for RollGenerator block
     * @param name The name identifier for this block
     */
    public RollGenerator(String name) {
        super(name);
        // Basic properties setup
        update = true;           // Needs updating
        solid = true;            // Is solid
        hasPower = true;         // Requires power module
        outputsPower = true;     // Outputs power
        consumesPower = true;
        size = 3;                // Size of the block
        health = 800;            // Health points
        envEnabled = Env.any;    // Effective in any environment
        configurable = false;    // Not configurable
        saveConfig = false;      // Don't save configuration
        displayFlow = false;     // Don't display flow
        drawArrow = false;       // Don't draw arrow
        consumePowerDynamic((entity) -> ((RollGeneratorBuild) entity).getPowerConsumptionPerTick()).optional(false, false);

    }

    /**
     * Sets up statistics for the block
     */
    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.productionTime, "1s");
    }

    /**
     * Sets up status bars for the block
     */
    @Override
    public void setBars() {
        super.setBars();

        // Add power status bar
        addBar("power", (RollGeneratorBuild entity) -> new Bar(() ->
                Core.bundle.format("bar.power1", Strings.fixed((entity.currentPowerProduction * 60 * entity.timeScale()), 1)),
                () -> Pal.powerBar,
                () -> entity.roll > 0 ? entity.currentPowerProduction / entity.roll : 0f));
    }

    /**
     * Building class for RollGenerator
     * Manages dynamic power generation based on network conditions
     */
    public class RollGeneratorBuild extends GeneratorBuild {
        /**
         * Interval timer for periodic updates
         */
        private final Interval interval = new Interval();
        /** Current power production rate */
        private float currentPowerProduction = 0f;
        /** Maximum allowed power generation */
        private float maxPowerGeneration = 0;
        /** Previous power production value for smooth transitions */
        private float lastCurrentPowerProduction = 0;
        private float warmupProgress = 0;
        private float roll = 0;

        /**
         * Updates the tile each frame
         * Calculates dynamic power generation based on network conditions
         */
        @Override
        public void updateTile() {
            // 提前 return 前必须归零:currentPowerProduction 是上一帧缓存,
            // getPowerProduction() 无门控直接返回它——禁用/PowerVoid 后若不归零,
            // 幽灵供电会持续进入电网(联机下还随 sync 快照分发给客户端)。
            if (!enabled) {
                currentPowerProduction = 0f;
                return;
            }
            // 单遍 graph.all 扫描:此前两轮"按类型取全队列表 × Seq.contains(逐个线性查图)"
            // 是 O(R²G)/O(V²G)——图越大、同类越多每帧开销越差;图成员直接遍历一次即得,
            // 语义不变(仍只统计本队、本图内的同类建筑)
            int i = 0;
            for (Building b : power.graph.all) {
                if (b.team != team) continue;
                if (b.block instanceof RollGenerator) {
                    i++;
                } else if (b.block instanceof PowerVoid) {
                    currentPowerProduction = 0f;
                    return;
                }
            }
            if (Float.isNaN(currentPowerProduction)) {
                lastCurrentPowerProduction = 0f;
            } else {
                lastCurrentPowerProduction = currentPowerProduction;
            }
            if (Float.isNaN(maxPowerGeneration)) {
                maxPowerGeneration = 0f;
            }
            currentPowerProduction = 0f;

            roll = powerStored.get(self()) * powerStoredProductionPercentage / 60 +
                    powerChanged.get(self()) * 60 * powerChangedProductionPercentage / 60;

            float add = roll * 60;
            if (powerChanged.get(self()) < 0.01 * add && maxPowerGeneration <= roll) {
                maxPowerGeneration += Time.delta / 60f;
                interval.clear();
            } else if (powerChanged.get(self()) > 0.02 * add && powerChanged.get(self()) >= 0) {
                if (interval.get(60f)) {
                    if (maxPowerGeneration > 0 && i > 0) {
                        maxPowerGeneration -= (powerChanged.get(self()) - 0.015f * add) / i * warmup();
                    }
                    if (maxPowerGeneration < 0) {
                        maxPowerGeneration = 0;
                    }
                    interval.clear();

                }
            }

            // Update warmup progress
            float target = Math.min(roll, maxPowerGeneration);
            warmupProgress = Mathf.approachDelta(warmupProgress, target > 0 ? 1f : 0f, warmupSpeed);

            // Calculate new power generation: 1% per second = 1% / 60 per tick
            // Limit minimum power generation to avoid stopping
            currentPowerProduction = Mathf.lerp(lastCurrentPowerProduction, Math.min(roll, maxPowerGeneration), warmupProgress);
        }

        /**
         * Gets the current power production amount
         * @return Power production in power units per tick
         */
        @Override
        public float getPowerProduction() {
            // Return current power generation
            return (currentPowerProduction > 0) ? currentPowerProduction : 0f;
        }

        /**
         * Gets the power consumption per tick
         * @return Negative power consumption when producing power
         */
        public float getPowerConsumptionPerTick() {
            return (currentPowerProduction < 0) ? -currentPowerProduction : 0f;
        }

        /**
         * Gets the save version for this building
         * @return The version number
         */
        @Override
        public byte version() {
            return 7;
        }

        /**
         * Gets the warmup progress for animations
         * @return Warmup progress from 0 to 1
         */
        @Override
        public float warmup() {
            return warmupProgress;
        }

        /**
         * Draws the building and visual effects
         */
        @Override
        public void draw() {
            super.draw();

            // Draw generation effect
            if (enabled && currentPowerProduction > 0) {
                Draw.color(Color.valueOf("f8c266"));
                Lines.stroke(0.8f);
                Lines.circle(x, y, 3f + Mathf.absin(Time.time, 10f, 1f));
                Draw.reset();
            }
        }

        /**
         * Called when proximity changes
         */
        @Override
        public void onProximityUpdate() {
            power.status = 1;
            super.onProximityUpdate();
        }

        /**
         * Provides sensor access to power network data
         * @param sensor The sensor type to query
         * @return The requested sensor value
         */
        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.powerNetStored) return power.graph.getBatteryStored();
            if (sensor == LAccess.powerNetCapacity) return power.graph.getBatteryCapacity();
            return super.sense(sensor);
        }

        /**
         * Writes building data to save a file
         * @param write The writer object
         */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.f(currentPowerProduction);
            write.f(maxPowerGeneration);
            write.f(lastCurrentPowerProduction);
        }

        /**
         * Reads building data from a save file
         * @param read The reader object
         * @param revision The save revision
         */
        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            currentPowerProduction = read.f();
            maxPowerGeneration = read.f();
            lastCurrentPowerProduction = read.f();
        }
    }
}
