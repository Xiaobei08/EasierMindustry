package silicon.content.block;

import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;
import silicon.world.blocks.container.DualPurposeStorager;
import silicon.world.blocks.defense.Switch;
import silicon.world.blocks.distribution.ItemTransferHub;
import silicon.world.blocks.distribution.Junction;
import silicon.world.blocks.distribution.UniversalJunction;
import silicon.world.blocks.power.GeneratorPump;
import silicon.world.blocks.power.PowerProtector;
import silicon.world.blocks.power.RollGenerator;
import silicon.world.blocks.production.MineConverter;
import silicon.world.blocks.production.PetroleumRefinery;
import silicon.world.blocks.sandbox.PowerSource;
import silicon.world.blocks.sandbox.MessageTest;
import silicon.world.blocks.signal.DimensionAnchor;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalSource;

import static mindustry.type.ItemStack.with;

public class Blocks {
    public static Block powerGeneratorPump, dualPurposeJunction, dualPurposeStorager,
            rollGenerator, powerProtector, powerSource, mineConverter, theSwitch, itemTransferHub,
            dimensionAnchor, signalSource, universalJunction, signalRelay, messageTest, petroleumRefinery;

    public static void load() {
        powerGeneratorPump = new GeneratorPump("power-generator-pump") {{
            hasItems = false;
            liquidPressure = 1f;
            pumpAmount = 0.22f;
            liquidCapacity = 90f;
            canPumpLiquids.add(Liquids.water);
            powerConsumption = 43f / 60;
            consumeLiquid(Liquids.water, 12.5f / 60).boost();
            powerProduction = 345f / 60;
            size = 3;
            destructible = true;
            requirements(Category.power, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 60, Items.lead, 30, Items.metaglass, 15, Items.graphite, 40,
                            Items.titanium, 45, Items.thorium, 6, Items.silicon, 40));
            alwaysUnlocked = true;
        }};
        dualPurposeJunction = new Junction("dual-purpose-junction") {{
            requirements(Category.liquid, BuildVisibility.shown,
                    ItemStack.with(Items.graphite, 2, Items.metaglass, 4, Items.copper, 1));
            alwaysUnlocked = true;
        }};
        dualPurposeStorager = new DualPurposeStorager("dual-purpose-storager") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.thorium, 100, Items.metaglass, 30, Items.titanium, 45, Items.plastanium, 10));
            alwaysUnlocked = true;
            size = 3;
            health = 600;
        }};

        // Compound interest generator - generates power based on 1% of existing stored power
        rollGenerator = new RollGenerator("roll-generator") {{
            requirements(Category.power, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 40, Items.lead, 24, Items.graphite, 20,
                            Items.silicon, 16, Items.thorium, 16, Items.plastanium, 10));
            alwaysUnlocked = true;
            size = 1;
            powerStoredProductionPercentage = 0.001f;
            powerChangedProductionPercentage = 0.005f;
        }};

        // Power protector - protects power network when below 0 and recovers spent power
        powerProtector = new PowerProtector("power-protector") {{
            requirements(Category.power, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 150, Items.lead, 100, Items.graphite, 80,
                            Items.silicon, 70, Items.thorium, 50, Items.plastanium, 40, Items.phaseFabric, 20));
            alwaysUnlocked = true;
            size = 2;
            health = 600;
        }};
        powerSource = new PowerSource("power-source") {{
            requirements(Category.power, BuildVisibility.sandboxOnly, with());
            alwaysUnlocked = true;
            size = 1;
            health = 600;
            powerProduction = Float.MAX_VALUE / 2;
        }};
        mineConverter = new MineConverter("mine-converter") {{
            requirements(Category.crafting, BuildVisibility.shown,
                    ItemStack.with(Items.graphite, 200, Items.silicon, 250, Items.thorium, 250, Items.plastanium, 100));
            consumePower(200f / 60);
            size = 3;
            frame = 18;
            frameTime = 8;
        }};
        theSwitch = new Switch("switch") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.graphite, 100, Items.silicon, 100, Items.thorium, 100, Items.plastanium, 100));
            alwaysUnlocked = true;
            update = true;
            solid = true;
        }};
        itemTransferHub = new ItemTransferHub("item-transfer-hub") {{
            requirements(Category.distribution, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 80, Items.lead, 40, Items.metaglass, 20,
                            Items.graphite, 30, Items.silicon, 25, Items.titanium, 15));
            alwaysUnlocked = true;
            size = 3;
        }};
        // 已废弃的维度锚点存根：保持原注册位置以保留旧存档方块 ID（隐藏于建造菜单，无功能）
        dimensionAnchor = new DimensionAnchor("dimension-anchor") {{
            health = 600;
        }};
        signalSource = new SignalSource("signal-source") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 20, Items.lead, 10, Items.silicon, 15));
            alwaysUnlocked = true;
            size = 1;
            health = 120;
        }};
        universalJunction = new UniversalJunction("universal-junction") {{
            requirements(Category.distribution, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 15, Items.lead, 10, Items.graphite, 8, Items.silicon, 5));
            alwaysUnlocked = true;
            size = 1;
        }};
        // 信号中继器注册在最后：保证旧存档（含维度锚点/旧信号源/万能枢纽）的方块 ID 不被后续新增方块打乱
        signalRelay = new SignalRelay("signal-relay") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 15, Items.lead, 10, Items.silicon, 12));
            alwaysUnlocked = true;
            size = 1;
            health = 100;
        }};
        // “消息测试”调试方块：置于建造菜单最后，不占旧存档 ID；功能上用于手动投递消息面板测试消息
        messageTest = new MessageTest("message-test") {{
            requirements(Category.effect, BuildVisibility.sandboxOnly, with());
            alwaysUnlocked = true;
            size = 1;
            health = 60;
        }};
        // 石油炼化厂：2x2 工厂方块。配方：25 石油 + 50 氢气 -> 25 润滑油 + 1 火石（5s/次，240 功率）
        petroleumRefinery = new PetroleumRefinery("petroleum-refinery") {{
            requirements(Category.crafting, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 100, Items.lead, 80, Items.graphite, 60, Items.silicon, 60));
            alwaysUnlocked = true;
            size = 2;
            health = 320;
        }};
    }
}