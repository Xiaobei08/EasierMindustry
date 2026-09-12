package silicon.world.blocks.distribution;

import arc.struct.IntSet;
import arc.struct.Seq;
import mindustry.gen.Building;

/**
 * 网络层：负责跨中枢的全局拓扑与寻址。
 * 每帧由首个中枢触发一次，全图 BFS 结果供各建筑复用。
 * 与 Building.updateTile() 的建筑本地逻辑分离。
 */
public class ItemTransferHubNetwork {

    private static int total = 1;

    /** 每个中枢的 network 实例 id（用于存档序列化的 network.id）。 */
    public int id;

    /** 本网络直接包含的中枢（物理相连的 hub 互为邻居）。 */
    public Seq<ItemTransferHub.ItemTransferHubBuild> hubs = new Seq<>();

    /** 拉取 / 推送 总开关（网络级）。 */
    public boolean enableDemandPull = true;

    public boolean enableSurplusPush = true;

    /**
     * 读档后推进计数器:存档中的 network.id 会覆盖构造时占用的自增号,
     * 必须把静态计数器抬到已见最大 id 之上,否则读档后新建中枢会与
     * 已加载中枢撞号(BFS/计费去重按 id 合并,跨枢搬运静默失效)。
     * 各建筑 read() 时逐个调用,取 max 语义,与读档顺序无关。
     */
    public static void updateCounterAfterLoad(int loadedId) {
        if (loadedId >= total) total = loadedId + 1;
    }

    public ItemTransferHubNetwork() {
        id = total++;
    }

    public ItemTransferHubNetwork(Seq<ItemTransferHub.ItemTransferHubBuild> hubs) {
        this();
        this.hubs.addAll(hubs);
    }

    public void clear() {
        hubs.clear();
    }

    // ── 网络级更新（Network Update）─────────────────────────
    // 职责：维护跨中枢的全局可达性、缓存 BFS 距离、决定是否允许拉/推。
    // 调用方：建议由 ItemTransferHubBuild.updateTile() 中按网络节流（timer）统一触发一次。

    /** 网络是否允许执行一次调度（电力门控等可在网络层统一拦截）。 */
    public boolean shouldTick(Building anyHub) {
        return enableDemandPull || enableSurplusPush;
    }

    // ── 网络寻址辅助（Network Routing）──────────────────────
    // 职责：供建筑层的 findNearest* 复用，避免每建筑各自重复 BFS。

    public static class HubData {

        /** 本中枢直连的建筑（工厂/仓储等）。 */
        public final Seq<Building> buildings;

        /** 本中枢直连的中枢邻居。 */
        public final Seq<ItemTransferHub.ItemTransferHubBuild> hubs = new Seq<>();

        public HubData(Seq<Building> buildings) {
            this.buildings = buildings;
        }

        public void add(Building building) {
            if (buildings.contains(building)) return;
            buildings.add(building);
        }

        public void add(ItemTransferHub.ItemTransferHubBuild hubBuild) {
            if (hubs.contains(hubBuild)) return;
            hubs.add(hubBuild);
        }

        public void remove(Building building) {
            buildings.remove(building);
        }

        public void remove(ItemTransferHub.ItemTransferHubBuild hubBuild) {
            hubs.remove(hubBuild);
        }

        public void clear() {
            buildings.clear();
            hubs.clear();
        }
    }
}
