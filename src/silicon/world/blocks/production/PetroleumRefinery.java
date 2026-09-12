package silicon.world.blocks.production;

import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;
import mindustry.world.blocks.production.GenericCrafter;

import static mindustry.content.Items.pyratite;
import static mindustry.content.Liquids.hydrogen;
import static mindustry.content.Liquids.oil;
import static silicon.world.Liquids.lubricant;

/**
 * 石油炼化厂：2x2 工厂方块。
 * 配方：25 石油 + 50 氢气（每秒）-> 润滑油 10/s + 火石 0.2/s，周期 5s（300 ticks），功耗 240/s。
 * 原料液体只被消耗、不会主动排出（GenericCrafter 仅 dump outputLiquids 中的液体）。
 * 产物进入内部库存并只向本方建筑排出，防止敌方管道/传送带旁路取走。
 */
public class PetroleumRefinery extends GenericCrafter {

    public PetroleumRefinery(String name) {
        super(name);

        // 配方：25 石油/s + 50 氢气/s -> 10 润滑油/s + 0.2 火石/s，周期 5s（300 ticks），功耗 240/s
        craftTime = 300f;
        liquidCapacity = 60f;

        outputItem = new ItemStack(pyratite, 1);
        // 注意：GenericCrafter 液体产出按每 tick amount×edelta 直接结算，与 craftTime 无关；10/s => 10/60 每 tick
        outputLiquid = new LiquidStack(lubricant, 10f / 60f);

        consumeLiquid(oil, 25f / 60f);
        consumeLiquid(hydrogen, 50f / 60f);

        consumePower(240f / 60f);
    }

    public class PetroleumRefineryBuild extends GenericCrafterBuild {
        // 产物改为存入内部库存由 dumpOutputs 逐步推出，未接输出产线时也不会凭空消失
        @Override
        public void craft() {
            consume();
            if (outputItems != null) {
                for (ItemStack stack : outputItems) {
                    items.add(stack.item, stack.amount);
                }
            }
            if (wasVisible) {
                craftEffect.at(x, y);
            }
            progress %= 1f;
        }
        // 多人/跨队保护：物品与液体只允许排向本方建筑，防止敌方管道/传送带旁路取走产物
        @Override
        public boolean canDump(Building to, Item item) {
            return to.team == team && super.canDump(to, item);
        }

        @Override
        public boolean canDumpLiquid(Building to, Liquid liquid) {
            return to.team == team && super.canDumpLiquid(to, liquid);
        }

        @Override
        public void offload(Item item) {
            int dump = cdump;
            for (int i = 0; i < proximity.size; i++) {
                Building other = proximity.get((i + dump) % proximity.size);
                if (other.team == team && other.acceptItem(this, item)) {
                    other.handleItem(this, item);
                    items.remove(item, 1);
                    incrementDump(proximity.size);
                    return;
                }
            }
            incrementDump(proximity.size);
        }

        // 输入液体同样只接受本方供给，避免敌方向炼化厂灌入原料干扰生产
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return source.team == team && super.acceptLiquid(source, liquid);
        }
    }
}