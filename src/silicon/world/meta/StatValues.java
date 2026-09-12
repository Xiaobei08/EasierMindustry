package silicon.world.meta;

import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;
import arc.util.Nullable;
import arc.util.Scaling;
import arc.util.Strings;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.world.meta.StatValue;

import java.util.TreeMap;

public class StatValues extends mindustry.world.meta.StatValues {
    public static StatValue itemsScaled(boolean displayName, TreeMap<Float, Item> scaled) {
        return table -> {
            // 末位条目只取一次（原先在 forEach 内对每个元素重复 lastKey()+get()）
            Item last = scaled.isEmpty() ? null : scaled.get(scaled.lastKey());
            scaled.forEach((amount, item) -> {
                if (item.equals(last)) {
                    table.add(displayItemsScaled(item, amount, displayName, ""));
                    return;
                }
                table.add(displayItemsScaled(item, amount, displayName, ":")).padRight(5);
            });
        };
    }

    public static Table displayItemsScaled(Item item, float amount, boolean showName, String spacing) {
        Table t = new Table();
        t.add(stack(item.uiIcon, amount, item, !showName, spacing));
        if (showName) t.add(item.localizedName).padLeft(4 + (amount > 99 ? 4 : 0));
        return t;
    }

    /**
     * Displays an item with a specified amount.
     */
    private static Stack stack(TextureRegion region, float amount, @Nullable UnlockableContent content, boolean tooltip, String spacing) {
        Stack stack = new Stack();

        stack.add(new Table(o -> {
            o.left();
            o.add(new Image(region)).size(32f).scaling(Scaling.fit);
        }));

        if (amount != 0) {
            stack.add(new Table(t -> {
                t.left().bottom();
                t.add((amount >= 1000 ? UI.formatAmount((long) amount) : Strings.fixed(amount, 2) + spacing)).name("stack amount").style(Styles.outlineLabel);
                t.pack();
            }));
        }

        withTooltip(stack, content, tooltip);

        return stack;
    }
}
