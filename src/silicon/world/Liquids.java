package silicon.world;

import arc.graphics.Color;
import mindustry.type.Liquid;

public class Liquids {
    public static Liquid lubricant;

    public static void load() {
        // 润滑油：黏稠的深琥珀色油液，可用于润滑/特殊合成
        lubricant = new Liquid("lubricant-oil", Color.valueOf("8a5a2b")) {{
            temperature = 0.6f;
            heatCapacity = 0.5f;
            viscosity = 0.8f;
            flammability = 0.7f;
            explosiveness = 0.1f;
            coolant = false;
        }};
    }
}