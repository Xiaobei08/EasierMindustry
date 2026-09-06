package silicon.world.blocks.distribution;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.FloatSeq;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.core.Renderer;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import silicon.util.SiliconLog;
import silicon.world.blocks.production.MineConverter;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.meta.BlockGroup;

import mindustry.world.Tile;
import arc.math.geom.Intersector;
import arc.util.Tmp;

import static mindustry.Vars.content;
import static mindustry.Vars.player;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

public class ItemTransferHub extends Block {
    public float connectionRange = 20f;
    public int maxConnections = 20;
    /** 矿机/工厂产出达到该容量比例即视为“快满”，触发向核心/仓库推送。 */
    public float surplusPushAt = 0.75f;
    /** 满电判定阈值：电网供给达到该比例才允许中枢工作/中转（电力不足完全停止工作）。 */
    static final float POWER_OK = 0.999f;
    /** 欠压冷却时长（tick）：停止后须等待电网回血再重试，避免逐帧“要电/不要电”抖动。 */
    static final int STARVE_COOLDOWN_TICKS = 60;
    /** 瞬时请求平滑窗口（tick）：批量计费摊入 30 帧衰减，避免突兀的耗电锋；调度先于求值保证守恒。 */
    static final int SMOOTH_TICKS = 30;
    /** 探测请求下限（电力）：至少相当于一件物品经手的电费，用于实证电网供电能力。 */
    static final float PROBE_DRAW = 10f;
    /** 拓扑校正：建筑出现在本端后的延迟校正倒计时（tick；给原一次性广播落地时间再比对）。 */
    static final int HUB_TOPO_PREPARE_TICKS = 40;
    /** 拓扑校正常驻周期（tick）：一次性 config 广播被端端构造竞态丢弃时，靠此自愈。 */
    static final int HUB_TOPO_REPAIR_TICKS = 900;
    /** 调试日志开关（Silicon 设置页控制）。 */
    public static boolean debugFlows = false;

    /** 全部存活的中枢实例：用于「建筑建成自动接入范围内中枢」的事件分发。 */
    public static final Seq<ItemTransferHubBuild> allHubs = new Seq<>();

    /**
     * 本帧输入钩子解析出的复制预览线段，延迟到 Trigger.postDraw 统一渲染后清空。
     * 元素 = {x1, y1, x2, y2, colorFlag}（colorFlag &gt; 0 为中枢间粉色）。
     */
    static final Seq<float[]> pendingCopyLinks = new Seq<>();

    static {
        // ── 电力节点同款机制（反编译依据）─────────────────────
        // 原版 Building.placed() 会调用 PowerNode.getNodeLinks(tile, block, team, cons)：
        // 每个新建成的建筑主动请求范围内的电力节点接入自己——因此无论节点还是
        // 目标谁先放置，边总有一端在放置时机触发，永不漏连。
        // 模组无法改写 Building 基类，改用建造完成事件实现等价行为：
        // 每个建筑建成时，范围内可连的中枢立即将其接入（增量式「每放一个就连一个」）
        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if (e.breaking || e.tile == null || e.tile.build == null) return;
            Building nb = e.tile.build;
            if (nb.team == Team.derelict) return;

            // 找最近的范围内可连中枢（只连一个，避免多中枢竞争同一目标）。
            // 【满员回退】最近的普通目标配额已满时，继续向更远的范围内枢寻找空位，
            // 而不是直接放弃连接；中枢间目标无上限不受此限。
            ItemTransferHubBuild best = null;
            float bestDist = Float.MAX_VALUE;
            boolean targetIsHub = nb instanceof ItemTransferHubBuild;
            for (int i = 0; i < allHubs.size; i++) {
                ItemTransferHubBuild hub = allHubs.get(i);
                if (!hub.isValid() || hub.team != nb.team || hub == nb) continue;
                if (hub.hasAnyLink(nb.pos())) return; // 已有中枢接入：不重复
                // 目标在某中枢的挂起队列中：该中枢会自行处理，事件不再干预
                if (hub.pendingLinks.contains(new arc.math.geom.Point2(
                        nb.tile.x - hub.tile.x, nb.tile.y - hub.tile.y))) return;
                if (!linkValid(hub, nb)) continue;
                // 容量闸门：满员枢不参与候选（挂起补连会兜底等空位，此处直接换下一枢）
                if (!targetIsHub && hub.links.size >= ((ItemTransferHub) hub.block).maxConnections) continue;
                float d = Mathf.dst2(hub.x, hub.y, nb.x, nb.y);
                if (d < bestDist) { bestDist = d; best = hub; }
            }
            if (best != null) {
                best.configure(nb.pos());
            }
        });
        // 世界重载：从 Groups.build 重建注册表 + 清空未渲染的预览线段。
        // 不能直接 clear()——WorldLoadEvent 在建筑构造（constructor→allHubs.add）之后触发，
        // 直接 clear 会把刚加载的中枢全部清掉导致连线永不渲染。
        Events.on(EventType.WorldLoadEvent.class, e -> {
            allHubs.clear();
            for (Building b : Groups.build) {
                if (b instanceof ItemTransferHubBuild hub) {
                    allHubs.add(hub);
                }
            }
            pendingCopyLinks.clear();
        });

        // ── 全局连线覆盖层（Trigger.postDraw：最终 flush 之后统一绘制）──────────
        // 电力节点家族的连线始终完全可见，同款「上层绘画」思路：不在方块自身
        // draw() 里穿插绘制，而是等一帧内所有内容画完后一次性补画所有中枢连线。
        // 【为什么必须用 postDraw 而非 drawOver】（v159 反编译依据）：
        //   v8 的 Renderer.draw 顺序 = … → fire(drawOver) → blocks.drawBlocks()
        //   → … → Draw.flush/sort(false) → fire(postDraw)。v8 把方块改为延迟
        //   渲染，drawOver 时方块尚未上屏，其缓存绘制会先 flush 队列再覆盖，
        //   导致 drawOver 里画的连线被方块盖住（实测「常驻连线全体不可见」）。
        //   postDraw 在最终 flush 与 sort(false) 之后触发，此刻绘制的任何内容
        //   必然位于一帧中一切世界几何之上（画布最后一笔），且投影仍为世界坐标。
        // 同一挂点顺带绘制放置侧预览：放置可连建筑时提前显示将被哪个中枢自动接入。
        Events.run(EventType.Trigger.postDraw, () -> {
            if (mindustry.Vars.state.isMenu() || allHubs.isEmpty()) return;
            boolean placingConnectable = isPlacingConnectable();
            boolean drawLinks = !Mathf.zero(Renderer.laserOpacity);
            if (!placingConnectable && !drawLinks && pendingCopyLinks.isEmpty()) return;

            float prevZ = Draw.z();
            Lines.stroke(2f);
            // ① 常驻连线：固定层级 Layer.plans+3
            if (drawLinks) {
                Draw.z(Layer.plans + 3f);
                for (int i = 0; i < allHubs.size; i++) {
                    ItemTransferHubBuild hub = allHubs.get(i);
                    if (!hub.isValid() || hub.team == Team.derelict || hub.isPayload()) continue;
                    hub.drawLinksGlobal();
                }
            }
            // ② 复制/原理图预览线段（本帧输入钩子解析所得）：最高层
            ItemTransferHub hubBlock = (ItemTransferHub) allHubs.first().block;
            Draw.z(Layer.overlayUI);
            if (!pendingCopyLinks.isEmpty()) {
                Lines.stroke(2f);
                for (int i = 0; i < pendingCopyLinks.size; i++) {
                    float[] s = pendingCopyLinks.get(i);
                    Draw.color(s[4] > 0 ? ItemTransferHub.hubLinkColor : ItemTransferHub.linkColor, linkOpacity());
                    Drawf.laser(hubBlock.laserRegion, hubBlock.laserEndRegion, hubBlock.laserEndRegion,
                        s[0], s[1], s[2], s[3], 0.25f);
                }
            }
            // ③ 放置侧连接预览：同样最高层
            if (placingConnectable) {
                drawPlaceClaimPreview();
            }
            // 立即落屏：防止后续阶段切换投影导致本批精灵错位/丢失
            Draw.flush();
            pendingCopyLinks.clear();
            Draw.z(prevZ);
            Draw.reset();
        });
    }

    /**
     * 多人拓扑校正：把所有 hub 连接的一次性广播纳入可自愈协议。
     * 原一次性 config 广播（建完事件 / placeEnded 自动连 / 拖线 / 粘贴）若在
     * 成员端构造出目标建筑前到达，会被 InputHandler 客户端分支【静默丢弃且永无重试】，
     * 造成“主机有连线、成员无连线”的持久不同步（仅重连才会恢复）。
     * 本机制：成员端延迟+周期携带本地拓扑快照请求，服务器比对不一致才应答——
     * 应答走标准 config 通道广播全量权威 Point2[]（既有处理器会清残留+重建反向），
     * 一次性收敛全部客户端。静默稳态下成员仅每周期贡献一个 8 字节级小包。
     */
    public static final String topoRequestPacket = "silicon-hub-topo-request";
    private static boolean topoNetworkingInitialized;

    /** 注册服务器端拓扑请求应答，须在 mod init 中调用（与 MineConverter 同款）。 */
    public static void initNetworking() {
        if (topoNetworkingInitialized) return;
        topoNetworkingInitialized = true;
        if (mindustry.Vars.netServer != null) {
            mindustry.Vars.netServer.addBinaryPacketHandler(topoRequestPacket, ItemTransferHub::onTopoRequest);
        }
    }

    private static void onTopoRequest(mindustry.gen.Player player, byte[] data) {
        if (data == null || data.length < 8) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))) {
            int tx = in.readInt();
            int ty = in.readInt();
            Building b = world.build(tx, ty);
            if (!(b instanceof ItemTransferHubBuild hub) || !hub.isValid()) return;
            // 仅服务于同队玩家（防跨队窃取他队拓扑）
            if (player == null || player.team() != hub.team) return;
            int n1 = in.readInt();
            if (n1 < 0 || n1 > 4096) return;
            IntSeq cLinks = new IntSeq(n1);
            for (int i = 0; i < n1; i++) cLinks.add(in.readInt());
            int n2 = in.readInt();
            if (n2 < 0 || n2 > 4096) return;
            IntSeq cHubLinks = new IntSeq(n2);
            for (int i = 0; i < n2; i++) cHubLinks.add(in.readInt());
            // 拓扑一致：静默（不发空广播）
            if (seqEquals(cLinks, hub.links) && seqEquals(cHubLinks, hub.hubLinks)) return;
            sendTopo(hub);
        } catch (java.io.IOException ignored) {
        }
    }

    private static boolean seqEquals(IntSeq a, IntSeq b) {
        if (a.size != b.size) return false;
        for (int i = 0; i < a.size; i++) {
            if (!b.contains(a.get(i))) return false;
        }
        return true;
    }

    /** 广播一枢全量权威拓扑（Point2[]，走标准 config 通道全端复制）。 */
    private static void sendTopo(ItemTransferHubBuild hub) {
        int n = hub.links.size + hub.hubLinks.size;
        arc.math.geom.Point2[] snaps = new arc.math.geom.Point2[n];
        int k = 0;
        for (int i = 0; i < hub.links.size; i++) {
            int pos = hub.links.get(i);
            snaps[k++] = new arc.math.geom.Point2(arc.math.geom.Point2.x(pos) - hub.tile.x, arc.math.geom.Point2.y(pos) - hub.tile.y);
        }
        for (int i = 0; i < hub.hubLinks.size; i++) {
            int pos = hub.hubLinks.get(i);
            snaps[k++] = new arc.math.geom.Point2(arc.math.geom.Point2.x(pos) - hub.tile.x, arc.math.geom.Point2.y(pos) - hub.tile.y);
        }
        mindustry.gen.Call.tileConfig(null, hub, snaps);
    }

    /** 当前是否正在放置「会被中枢自动接入」的非中枢方块（放置侧预览触发条件）。 */
    private static boolean isPlacingConnectable() {
        mindustry.input.InputHandler input = mindustry.Vars.control.input;
        Block b = input == null ? null : input.block;
        return b != null && input.isPlacing() && !input.isBreaking()
            && !(b instanceof ItemTransferHub) && HubRouting.shouldConnectBlock(b);
    }

    /**
     * 放置侧连接预览：光标处的可连建筑尚未放下，提前显示自动接入结果。
     * 与建造完成事件【完全同口径】，否则预览≠实际：
     * ①该位置已被任何中枢连接 → 事件直接放弃 → 无标记；
     * ②候选 = 范围内可连的同队中枢（含 payload 过滤），胜者 = 最近者——
     *   事件不会因最近枢满员而跳到次近枢；
     * ③胜者普通连接已满（≥maxConnections）→ configure 无空位入列失败 → 实际不会连 → 无标记。
     * 有标记时：实线激光连向幽灵（物流色）+ 幽灵蓝框 + 该枢范围虚线圈；
     * 无标记即「放下后不会自动接入」。
     */
    private static void drawPlaceClaimPreview() {
        mindustry.input.InputHandler input = mindustry.Vars.control.input;
        Block block = input.block;
        // 偶数方块锚点修正（原版 InputHandler.tileX/tileY 同款）：
        // 鼠标世界坐标先减去 block.offset 再取整——否则偶数方块（offset=4）的
        // 预览位置会偏离可见幽灵整整一格
        Tile t = world.tileWorld(
            Core.input.mouseWorldX() - block.offset,
            Core.input.mouseWorldY() - block.offset);
        if (t == null) return;
        float gx = t.x * tilesize + block.offset, gy = t.y * tilesize + block.offset;

        // ①位置已有归属：事件处理器遇到 hasAnyLink 直接放弃
        for (int i = 0; i < allHubs.size; i++) {
            if (allHubs.get(i).hasAnyLink(t.pos())) return;
        }

        // ②候选 = 范围内可连的同队非满员中枢；胜者 = 最近者。
        //   【满员回退】与建造完成事件同口径：最近枢满员时继续找更远的有空位枢
        ItemTransferHubBuild best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < allHubs.size; i++) {
            ItemTransferHubBuild hub = allHubs.get(i);
            if (!hub.isValid() || hub.isPayload() || hub.team != player.team()) continue;
            float range = ((ItemTransferHub) hub.block).connectionRange * tilesize;
            if (!Intersector.overlaps(Tmp.cr1.set(hub.x, hub.y, range),
                Tmp.r1.setCentered(gx, gy, block.size * tilesize, block.size * tilesize))) continue;
            if (hub.links.size >= ((ItemTransferHub) hub.block).maxConnections) continue;
            float d = Mathf.dst2(hub.x, hub.y, gx, gy);
            if (d < bestDist) { bestDist = d; best = hub; }
        }
        // 全部满员/无覆盖：实际不会建立连接，不标记
        if (best == null) return;

        ItemTransferHub hubBlock = (ItemTransferHub) best.block;
        Lines.stroke(1f);
        Draw.color(Pal.accent, 0.35f * linkOpacity());
        Drawf.dashCircle(best.x, best.y, hubBlock.connectionRange * tilesize, Pal.accent);

        float angle = Angles.angle(best.x, best.y, gx, gy);
        float ca = Mathf.cosDeg(angle), sa = Mathf.sinDeg(angle);
        float len1 = best.block.size * tilesize / 2f - 1.5f;
        float len2 = block.size * tilesize / 2f - 1.5f;
        Lines.stroke(2f);
        Draw.color(linkColor, linkOpacity());
        Drawf.laser(hubBlock.laserRegion, hubBlock.laserEndRegion, hubBlock.laserEndRegion,
            best.x + ca * len1, best.y + sa * len1,
            gx - ca * len2, gy - sa * len2, 0.25f);
        Drawf.square(gx, gy, block.size * tilesize / 2f + 2f, Pal.place);
        Draw.reset();
    }

    /** 物流连线颜色：与「连接数」状态栏一致（Pal.items）。 */
    public static final Color linkColor = Pal.items;
    /** 中枢间连线颜色：粉色——区别于中枢→建筑的物流色，一眼可辨网络骨架。 */
    public static final Color hubLinkColor = Color.valueOf("ff88dd");

    /**
     * 连线颜色按目标类型区分：中枢→中枢为粉色，中枢→建筑为物流色。
     * 常驻连线 / 放置预览 / 规划虚线三处共用，保证所见即所得。
     */
    private static Color lineColorFor(Building other) {
        return other instanceof ItemTransferHubBuild ? hubLinkColor : linkColor;
    }
    /** 电力节点风格激光贴图。 */
    public TextureRegion laserRegion, laserEndRegion;

    /** 连线透明度：读取 Silicon 设置「中枢连线透明度」（0-100，默认 100）。 */
    public static float linkOpacity() {
        return Core.settings.getInt("hubLinkOpacity", 100) / 100f;
    }

    @Override
    public void load() {
        super.load();
        laserRegion = Core.atlas.find("laser");
        laserEndRegion = Core.atlas.find("laser-end");
    }

    public ItemTransferHub(String name) {
        super(name);
        hasItems = false;
        hasPower = true;
        consumesPower = true;
        outputsPower = false;
        conductivePower = true;
        consumePowerDynamic(entity -> ((ItemTransferHubBuild) entity).powerConsumed);
        solid = true;
        update = true;
        size = 3;
        timers = 4;
        configurable = true;
        // saveConfig 保持默认 false（电力节点同款）：放置计划不携带历史配置——
        // 若为 true，Block.nextConfig() 会把旧 Point2[] 粘到之后每次拖线/单击
        // 放置的中枢上，产生「莫名虚线」与预期外链接；复制一律走
        // 原理图（Schematics 序列化配置）或 F 键拾取的悬停预览
        group = BlockGroup.transportation;

        config(Integer.class, (ItemTransferHubBuild entity, Integer pos) -> {
            Building other = world.build(pos);
            // 存在性 + 自身 + 范围 二次校验
            if (other == null || !other.isValid() || other == entity) return;
            if (!linkValid(entity, other)) return;

            // 中枢间连接独立存储（hubLinks，无上限、粉色条）；普通连接走 links（≤maxConnections）
            boolean hubTarget = other instanceof ItemTransferHubBuild;
            IntSeq mine = hubTarget ? entity.hubLinks : entity.links;

            if (mine.contains(pos)) {
                mine.removeValue(pos);
                if (hubTarget) {
                    ItemTransferHubBuild oh = (ItemTransferHubBuild) other;
                    oh.hubLinks.removeValue(entity.pos());
                    rebuildData(oh);
                }
                rebuildData(entity);
            } else {
                // 容量闸门先于单一归属裁决：满连接时点击同网/他网建筑必须原样不动——
                // 否则会先把目标从原中枢抢回、再因无空位入列失败返回，
                // 造成「点击一下反而断开其与原中枢的连接」的孤儿链接
                if (!hubTarget && entity.links.size >= maxConnections) return;
                // 单一归属裁决：普通目标若已被其它中枢占有/挂起，先整体抢回再入列——
                // 否则复制粘贴/挂起补连/双击自动连会产生两枢同拉一目标的重复竞争连接
                if (!hubTarget) stealFromOtherHubs(entity, other);
                mine.addUnique(pos);
                if (hubTarget) {
                    ItemTransferHubBuild oh = (ItemTransferHubBuild) other;
                    // 中枢间连接双向对称、无上限
                    if (!oh.hubLinks.contains(entity.pos())) {
                        oh.hubLinks.addUnique(entity.pos());
                    }
                    rebuildData(oh);
                }
                rebuildData(entity);
            }
        });

        // 长按拖线放置 / 复制配置：InputHandler 将相对坐标以 Point2[] 传入。
        // 目标尚未建成的偏移进入 pendingLinks 挂起队列，由 updateTile 周期重试——
        // 粘贴蓝图时中枢可能先于矿机/仓库建成，电力节点的边存在两端所以无此问题，
        // 中枢的边只存于本端，必须自行补连才能保证「后建的建筑也能接上」
        config(arc.math.geom.Point2[].class, (ItemTransferHubBuild entity, arc.math.geom.Point2[] dragLinks) -> {
            // 先清理被替换的旧连接在其它中枢上的反向引用（双向对称维护）：
            // 旧 hubLinks 对应反向 hubLinks；links 中历史遗留的中枢条目同样反查清除。
            // 否则重配（复制粘贴 / 双击清空）后其它中枢仍保留本枢的反向粉色边，跨端拓扑漂移。
            for (int i = entity.hubLinks.size - 1; i >= 0; i--) {
                Building ob = world.build(entity.hubLinks.get(i));
                if (ob instanceof ItemTransferHubBuild oh) {
                    oh.hubLinks.removeValue(entity.pos());
                    rebuildData(oh);
                }
            }
            for (int i = entity.links.size - 1; i >= 0; i--) {
                Building ob = world.build(entity.links.get(i));
                if (ob instanceof ItemTransferHubBuild oh) {
                    oh.hubLinks.removeValue(entity.pos());
                    rebuildData(oh);
                }
            }
            entity.links.clear();
            entity.hubLinks.clear();
            entity.pendingLinks.clear();
            entity.pendingAt.clear();
            for (arc.math.geom.Point2 link : dragLinks) {
                Building other = world.build(entity.tile.x + link.x, entity.tile.y + link.y);
                // 未放置 / 是自身 / 仍在建造中（ConstructBuild 脚手架）→ 挂起等待，
                // 建成后由 updateTile 周期任务自动补连——漏连根因：建造中的目标
                // 此前被静默跳过且永不重试
                if (other == null || other == entity
                    || other instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) {
                    entity.addPending(link);
                    continue;
                }
                if (!other.isValid() || !linkValid(entity, other)) continue;
                if (other instanceof ItemTransferHubBuild otherHub) {
                    // 中枢间连接：独立计数、无上限、双向对称（粉色条）
                    entity.hubLinks.addUnique(other.pos());
                    if (!otherHub.hubLinks.contains(entity.pos())) {
                        otherHub.hubLinks.addUnique(entity.pos());
                    }
                } else {
                    // 从其它中枢抢回属于自己模式的连接（复制粘贴时原网络可能抢先接入）
                    stealFromOtherHubs(entity, other);
                    // 普通连接受上限约束；满员转入挂起队列等空位，不静默丢弃
                    if (entity.links.size >= maxConnections) {
                        entity.addPending(link);
                        continue;
                    }
                    entity.links.addUnique(other.pos());
                }
            }
            rebuildData(entity);
        });
    }

    private static boolean shouldConnect(Building other) {
        return HubRouting.shouldConnect(other);
    }

    public static boolean linkValid(Building tile, Building link) {
        return HubRouting.linkValid(tile, link);
    }

    /**
     * 连接单一归属裁决：把 target 从其它全部中枢的 links/hubLinks/pendingLinks 中整体移除。
     * 背景：建造完成事件按「最近中枢」分配新建筑，复制粘贴时原网络可能抢先接入；
     * 若认领方不清除旧归属，就会形成两枢同时拉取同一目标的重复/竞争连接。
     * 认领路径（Point2[] 复制配置 / Integer 单击·自动连·挂起补连）统一走本裁决，
     * 保证任意时刻一个普通建筑只被一个中枢服务；中枢间粉色连接不受此约束（有意多对多）。
     */
    private static void stealFromOtherHubs(ItemTransferHubBuild self, Building target) {
        int pos = target.pos();
        for (int hi = 0; hi < allHubs.size; hi++) {
            ItemTransferHubBuild oh = allHubs.get(hi);
            if (oh == self || !oh.isValid()) continue;
            if (oh.links.removeValue(pos)) rebuildData(oh);
            if (oh.hubLinks.removeValue(pos)) rebuildData(oh);
            // 清除其它中枢挂起队列中的同位条目
            for (int pi = oh.pendingLinks.size - 1; pi >= 0; pi--) {
                arc.math.geom.Point2 pp = oh.pendingLinks.get(pi);
                if (pp.x == target.tile.x - oh.tile.x && pp.y == target.tile.y - oh.tile.y) {
                    oh.pendingLinks.remove(pi);
                    oh.pendingAt.removeIndex(pi);
                }
            }
        }
    }

    private static void rebuildData(ItemTransferHubBuild hub) {
        hub.data.clear();
        // 中枢间连接（独立列表）→ 网络邻居
        hub.hubLinks.each(pos -> {
            Building b = world.build(pos);
            if (b instanceof ItemTransferHubBuild otherHub) {
                if (!hub.data.hubs.contains(otherHub)) hub.data.add(otherHub);
            }
        });
        // 普通连接（兼容旧存档里可能存在的中枢项）→ 网络/直连建筑
        hub.links.each(pos -> {
            Building b = world.build(pos);
            if (b == null || !b.isValid() || b == hub) return;
            if (b instanceof ItemTransferHubBuild otherHub) {
                if (!hub.data.hubs.contains(otherHub)) hub.data.add(otherHub);
            } else if (shouldConnect(b)) {
                if (!hub.data.buildings.contains(b)) hub.data.add(b);
            }
        });
    }

    @Override
    public void setBars() {
        addBar("health", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("stat.health"),
                () -> Pal.health,
                () -> b.healthf()
        ).blink(Color.white));
        addBar("silicon-hub-power", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-power"),
                () -> Pal.powerBar,
                () -> b.power != null ? b.power.status : 0f
        ));
        addBar("silicon-hub-power-cost", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-power-cost", Strings.fixed(b.powerPerSecond, 1)),
                () -> Pal.accent,
                () -> Math.min(b.powerPerSecond / 100f, 1f)
        ));
        addBar("silicon-hub-connections", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-connections", b.links.size, maxConnections),
                () -> Pal.items,
                () -> (float) b.links.size / maxConnections
        ));
        // 中枢间连接：独立计数、无上限——粉色条（hubLinkColor 同色系）
        addBar("silicon-hub-hublinks", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-links", b.hubLinks.size),
                () -> hubLinkColor,
                () -> b.hubLinks.size == 0 ? 0f : 1f
        ));
        addBar("silicon-hub-transfer-rate", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-transfer-rate", Strings.fixed(b.transferRate, 1)),
                () -> Pal.accent,
                () -> Math.min(b.transferRate / 50f, 1f)
        ));
    }

    /**
     * 自动连接目标统一判定（预览 / 放置 / 双击 三处共用）。
     * - 范围内的所有中枢：一律可连
     * - 非中枢：不在【即将连接的中枢自身】网络系统内即可连
     *   （self 为空 = 放置预览/新中枢，网络为空，全部可连）
     */
    private static boolean autoConnectTargetValid(Building self, Building target){
        if (target == null || !target.isValid()) return false;
        if (target instanceof ItemTransferHubBuild) return true;

        if (self instanceof ItemTransferHubBuild h) {
            return !h.inSameNetwork(target);
        }
        return true;
    }

    /** 收集 root 枢纽所在网络的全部直连建筑（含自身，跨枢 BFS）。 */
    private static void collectNetworkBuildings(ItemTransferHubBuild root, arc.struct.ObjectSet<Building> out) {
        arc.struct.ObjectSet<ItemTransferHubBuild> seen = new arc.struct.ObjectSet<>();
        java.util.ArrayDeque<ItemTransferHubBuild> queue = new java.util.ArrayDeque<>();
        seen.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            ItemTransferHubBuild cur = queue.poll();
            out.addAll(cur.data.buildings);
            for (ItemTransferHubBuild nb : cur.data.hubs) {
                if (seen.add(nb)) queue.add(nb);
            }
        }
    }

    @Override
    public void placeEnded(Tile tile, mindustry.gen.Unit builder, int rotation, Object config) {
        // 链接应用与未建成目标的挂起均由 Point2[] 配置处理器完成（configured 先于本钩子触发）；
        // 此处：①一次性消费 lastConfig，防止旧连接模式继续喂给悬停预览；
        // ②无复制配置的新枢用剩余容量像电力节点一样补连周围建筑
        lastConfig = null;
        if (!(tile.build instanceof ItemTransferHubBuild hub) || mindustry.Vars.net.client()) return;

        // 复制放置（原理图粘贴 / F 键拾取）不再自由补连：拓扑完全由复制的配置 + 挂起队列决定。
        // 判据 = 显式 Point2[] 配置，或 configured 已写入任意连接/挂起（copyConfig 链路下
        // configured 同样先于本钩子执行）。此前补连会接入周边原网络的中枢与建筑：
        // ①偏离复制预览（所见非所得、出现预期外连接）；②占用 maxConnections 配额，
        // 饿死排在后面建成的挂起目标 → 「偏后放置的建筑永远连不上」
        boolean copiedPlacement = config instanceof arc.math.geom.Point2[]
            || hub.links.size > 0 || hub.hubLinks.size > 0 || !hub.pendingLinks.isEmpty();
        if (copiedPlacement) {
            // 复制放置仍自动连接范围内的全部中枢（粉色骨架，无上限、双向对称、不占普通配额）
            // ——普通建筑拓扑保持复制的原始模式不变（不补连、不占位）
            hub.autoConnectNearbyHubs(this);
            return;
        }
        hub.autoConnectNearby(this);
    }

    /**
     * 查找范围内可连接的建筑（电力节点式）。
     */
    protected void getPotentialLinks(Tile tile, Team team, arc.func.Cons<Building> cons) {

        float range = connectionRange * tilesize;
        float wx = tile.worldx() + offset;
        float wy = tile.worldy() + offset;

        var tree = team.data().buildingTree;
        if (tree == null) {
            return;
        }

        tree.intersect(wx - range, wy - range, range * 2, range * 2, b -> {

            if (b == null || b.tile == tile) {
                return;
            }

            if (b.team != team) {
                return;
            }

            if (!overlaps(wx, wy, b, range)) {
                return;
            }

            if (!shouldConnect(b)) {
                return;
            }

            // 中枢间连接不检查被动方的连接数上限（只占主动方配额）

            cons.get(b);
        });
    }

    /** Building 版：建筑中心已含 offset，直接以占位矩形判定。 */
    protected boolean overlaps(float srcx, float srcy, Building other, float range) {
        return Intersector.overlaps(Tmp.cr1.set(srcx, srcy, range),
            Tmp.r1.setCentered(other.x, other.y, other.block.size * tilesize, other.block.size * tilesize));
    }

    @Override
    public void drawPlace(int tx, int ty, int rotation, boolean valid) {
        Tile tile = world.tile(tx, ty);
        if (tile == null) return;
        super.drawPlace(tx, ty, rotation, valid);

        float range = connectionRange * tilesize;
        float cx = tx * tilesize + offset;
        float cy = ty * tilesize + offset;

        Lines.stroke(1f);
        Draw.color(Pal.placing);
        Drawf.circles(cx, cy, range);

        Seq<Building> cands = new Seq<>();
        getPotentialLinks(tile, player.team(), cands::add);

        // 模拟自动连接（与 autoConnectNearby 同口径），两阶段均按【距离就近】：
        // ① 优先连接范围内的全部中枢——蓝色方框 + 连线（中枢间粉线）；
        // ② 其次连接「已选中枢所在网络之外」的非中枢建筑——蓝色方框 + 物流色连线；
        // 三色标记与单击配置显示同口径：
        // 蓝 = 将连接；紫 = 即将连入的网络内（coveredByHubs）；绿 = 可新建（网络外、未入选）。
        cands.sort((a, b) -> Float.compare(
            Mathf.dst2(a.x - cx, a.y - cy),
            Mathf.dst2(b.x - cx, b.y - cy)));
        arc.struct.ObjectSet<Building> coveredByHubs = new arc.struct.ObjectSet<>();
        arc.struct.ObjectSet<Building> actual = new arc.struct.ObjectSet<>();
        int simulated = 0;
        // ① 中枢：范围内全部连接，并记录其整网覆盖
        for (Building cand : cands) {
            if (!cand.isValid() || simulated >= maxConnections) continue;
            if (cand instanceof ItemTransferHubBuild h) {
                actual.add(cand);
                simulated++;
                collectNetworkBuildings(h, coveredByHubs);
            }
        }
        // ② 非中枢：只连「已选中枢网络之外」的
        for (Building cand : cands) {
            if (!cand.isValid() || simulated >= maxConnections) continue;
            if (!(cand instanceof ItemTransferHubBuild) && !coveredByHubs.contains(cand)) {
                actual.add(cand);
                simulated++;
            }
        }

        for (Building other : cands) {
            float angle = Angles.angle(cx, cy, other.x, other.y);
            float ca = Mathf.cosDeg(angle), sa = Mathf.sinDeg(angle);
            // 端点对齐原版 PowerNode.drawLaser：半边长【先内缩 1.5px】再投影——
            // 端点落在方块边缘内侧（先到边界再外推会让端点悬在方块外）
            float len1 = size * tilesize / 2f - 1.5f;
            float len2 = other.block.size * tilesize / 2f - 1.5f;
            if (actual.contains(other)) {
                // 将连接：蓝框 + 连线（中枢间粉色，其余物流色）
                Draw.color(lineColorFor(other), linkOpacity());
                Drawf.laser(laserRegion, laserEndRegion, laserEndRegion,
                    cx + ca * len1, cy + sa * len1,
                    other.x - ca * len2, other.y - sa * len2, 0.25f);
                Drawf.square(other.x, other.y, other.block.size * tilesize / 2f + 2f, Pal.place);
            } else if (coveredByHubs.contains(other)) {
                // 即将连入的网络内成员：紫色方框提示（不画连线——不会直连）
                Drawf.square(other.x, other.y, other.block.size * tilesize / 2f + 2f, Pal.reactorPurple);
            } else {
                // 可新建直连（不在任何即将连入的网络内）：绿色方框
                Drawf.square(other.x, other.y, other.block.size * tilesize / 2f + 2f, Pal.heal);
            }
        }

        Draw.reset();
    }

    /** 队列/原理图计划的配置预览相机裁剪半径：覆盖整网连线范围，防止预览被裁剪消失。 */
    @Override
    public float planConfigClipSize(){
        return connectionRange * tilesize * 2f;
    }

    @Override
    public void drawPlanConfig(mindustry.entities.units.BuildPlan plan, arc.util.Eachable<mindustry.entities.units.BuildPlan> list){
        // 悬停幽灵专用路径：DesktopInput 每帧把 lastConfig 附到 bplan 后调用本钩子并立即清空
        // （不经过 drawPlan，也与 saveConfig 无关）——F 键拾取后的「放置前预览」挂在这里
        drawCopyLinksIfCopied(plan, list, "hover");
    }

    @Override
    public void drawPlanConfigTop(mindustry.entities.units.BuildPlan plan, arc.util.Eachable<mindustry.entities.units.BuildPlan> list) {
        // 入队计划 / 原理图粘贴路径（drawBuildPlans）：与电力节点同款钩子——
        // 成批粘贴时画出计划间激光网；拖线计划经 drawOverPlan 也走这里
        drawCopyLinksIfCopied(plan, list, "top");
    }

    /** 复制预览触发频率诊断计数（仅 debugFlows 开启时输出）。 */
    static int copyPreviewTick;

    /** 携带 Point2[] 连接配置的计划才产生预览（Integer 等其它配置类型忽略）。 */
    private void drawCopyLinksIfCopied(mindustry.entities.units.BuildPlan plan, arc.util.Eachable<mindustry.entities.units.BuildPlan> list, String via){
        if (!(plan.config instanceof arc.math.geom.Point2[] ps)) return;
        if (debugFlows && ++copyPreviewTick % 30 == 1) {
            SiliconLog.info("[中枢复制预览:" + via + "] points=" + ps.length
                + " @" + plan.x + "," + plan.y + " -> 延迟至 postDraw(z=120) op=" + linkOpacity());
        }
        // 只在钩子期【解析】线段——只有此处能看到同批「计划」的上下文；
        // 渲染统一延迟到 Trigger.postDraw。原因：输入阶段提交的精灵会被其后的
        // 方块缓存绘制覆盖（v8 延迟渲染，反编译顺序 …→drawOver→drawBlocks→…），
        // 而 postDraw 位于最终 flush 之后，是一帧画布最后一笔，任何内容都无法再遮挡。
        for (arc.math.geom.Point2 p : ps) {
            final int fx = plan.x + p.x, fy = plan.y + p.y;
            mindustry.entities.units.BuildPlan otherReq = findPlan(list, fx, fy, other ->
                other != plan && other.block != null && other.block.size > 0);
            if (otherReq == null || otherReq.block == null) continue;

            // 距离校验：新位置范围内真正可连才画（与放置后 configured 的 linkValid 同口径）
            boolean inRange = Intersector.overlaps(Tmp.cr1.set(plan.drawx(), plan.drawy(), connectionRange * tilesize),
                Tmp.r1.setCentered(otherReq.drawx(), otherReq.drawy(),
                    otherReq.block.size * tilesize, otherReq.block.size * tilesize));
            if (!inRange) continue;

            float angle = Angles.angle(plan.drawx(), plan.drawy(), otherReq.drawx(), otherReq.drawy());
            float ca = Mathf.cosDeg(angle), sa = Mathf.sinDeg(angle);
            float len1 = size * tilesize / 2f - 1.5f;
            float len2 = otherReq.block.size * tilesize / 2f - 1.5f;
            pendingCopyLinks.add(new float[]{
                plan.drawx() + ca * len1, plan.drawy() + sa * len1,
                otherReq.drawx() - ca * len2, otherReq.drawy() - sa * len2,
                otherReq.block == this ? 1f : 0f});
        }
    }

    @Override
    public void changePlacementPath(arc.struct.Seq<arc.math.geom.Point2> points, int rotation) {
        // chain planning like PowerNode: auto-connect via Placement.calculateNodes
        mindustry.input.Placement.calculateNodes(points, this, rotation,
            (point, other) -> {
                Tile a = world.tile(point.x, point.y);
                Tile b = world.tile(other.x, other.y);
                if (a == null || b == null) return false;
                float range = connectionRange * tilesize;
                return Intersector.overlaps(Tmp.cr1.set(a.worldx() + offset, a.worldy() + offset, range),
                    Tmp.r1.setCentered(b.worldx() + offset, b.worldy() + offset, size * tilesize, size * tilesize));
            });
    }

    public class ItemTransferHubBuild extends Building {
        public ItemTransferHubNetwork network = new ItemTransferHubNetwork();
        public ItemTransferHubNetwork.HubData data;
        /** 普通连接（矿机/仓库/炮台等），受 maxConnections 上限约束。 */
        public IntSeq links = new IntSeq();
        /** 中枢间连接：独立计数、无上限、状态栏粉色条显示。 */
        public IntSeq hubLinks = new IntSeq();
        /** 复制/粘贴时目标尚未建成的挂起偏移：建造完成后由 updateTile 周期补连（会话内有效，不入存档）。 */
        public Seq<arc.math.geom.Point2> pendingLinks = new Seq<>();
        /** 挂起时间戳（Time.time）：用于超时清理无法放置的目标（默认 600 秒过期）。 */
        public FloatSeq pendingAt = new FloatSeq();

        /** 记录一条挂起链接（带时间戳，去重）。 */
        private void addPending(arc.math.geom.Point2 link) {
            if (!pendingLinks.contains(link)) {
                pendingLinks.add(link);
                pendingAt.add(Time.time);
            }
        }
        public float powerConsumed = 0f;
        // 延迟计费：跨枢 charge 分摊到下一帧，避免同帧执行顺序导致的清零覆盖
        public float powerConsumedNext = 0f;
        public float powerPerSecond = 0f;
        /** 计费平滑环形缓冲：瞬时请求 = 最近 SMOOTH_TICKS 帧计费均值，杜绝电网侧消耗跳变。 */
        private final float[] smoothBuf = new float[SMOOTH_TICKS];
        private int smoothIdx = 0;
        /** 欠压冷却剩余 tick：>0 时完全停止工作（不调度、不计费、不可中转）。 */
        int starveCooldown = 0;
        /** 探测态：冷却结束后先发真实电力请求验证供电，交付满格才恢复搬运。 */
        boolean probing = false;
        /** 处于欠压/禁用停止态：路径选择据此跳过本枢（见 relayable）。 */
        boolean powerStarved = false;
        /** 本帧经手件数（含上一帧跨枢延迟并入的 transferCountNext）。 */
        private int transferCount = 0;
        /** 跨枢延迟计数：物品途经本枢（由其它枢纽发起调度）时写入，下一帧并入吞吐统计。 */
        public int transferCountNext = 0;
        /** 传输速率：10 秒滑动窗口平均（件/秒），含路过本枢的所有件数 */
        public float transferRate = 0f;
        private static final int RATE_WINDOW_TICKS = 600; // 运输速率：10s * 60fps 真实滑动窗口
        private static final int POWER_WINDOW_TICKS = 60; // 电力消耗：每秒（60 tick）滑动窗口
        private final IntSeq rateWindowCounts = new IntSeq();
        private long rateWindowSum = 0;
        /** 最近 1 秒逐帧实际取电桶——电力消耗按秒计算。 */
        private final FloatSeq powerSecondWindow = new FloatSeq();
        private float powerSecondSum = 0f;
        private int rateTickCounter = 0;

        // ── 多人拓扑校正（会话内状态，不入存档）──────────────────
        /** 出现于本端后的延迟校正倒计时（0=空闲；递减到 0 触发一次快照比对）。 */
        int topoCheckIn = 0;
        /** 常驻校正周期计数（每 HUB_TOPO_REPAIR_TICKS 与服务器比对一次）。 */
        int topoRepairPeriod = 0;

        private final Seq<ItemTransferHubBuild> bfsQueue = new Seq<>();
        private final IntSeq bfsDists = new IntSeq();
        private final IntSet bfsVisited = new IntSet();
        /**
         * 每非炮台消费者【上次供料后】的物品存量快照：
         * 本轮可补量 = 快照 − 当前库存（即真实消耗量）+ 少量缓冲，
         * 从数学上保证搬运量 ≈ 消耗量，杜绝批量补满带来的吞吐放大。
         */
        private final arc.struct.ObjectMap<Building, int[]> feedSnapshot = new arc.struct.ObjectMap<>();
        /** 调试：流量聚合计数（"标签" -> 件数），由设置开关控制输出。 */
        private final arc.struct.ObjectMap<String, Integer> debugFlow = new arc.struct.ObjectMap<>();
        private int debugTicks = 0;

        public ItemTransferHubBuild() {
            super();
            data = new ItemTransferHubNetwork.HubData(new Seq<>());
            allHubs.add(this);
        }

        @Override
        public void remove() {
            super.remove();
            allHubs.remove(this);
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            return false;
        }

        /**
         * 供原版「拾取复制」（F 键）使用：返回以自身为原点的相对连接坐标。
         * 异地放置时 placeEnded 按相对偏移 + linkValid 现场校验——
         * 只连得上新位置合法范围内的目标，不会复制出无效链路。
         */
        @Override
        public Object config() {
            // 复制时同时携带普通连接与中枢间连接（相对坐标合并导出）
            arc.math.geom.Point2[] arr = new arc.math.geom.Point2[links.size + hubLinks.size];
            int n = 0;
            for (int i = 0; i < links.size; i++) {
                int pos = links.get(i);
                arr[n++] = new arc.math.geom.Point2(arc.math.geom.Point2.x(pos) - tile.x, arc.math.geom.Point2.y(pos) - tile.y);
            }
            for (int i = 0; i < hubLinks.size; i++) {
                int pos = hubLinks.get(i);
                arr[n++] = new arc.math.geom.Point2(arc.math.geom.Point2.x(pos) - tile.x, arc.math.geom.Point2.y(pos) - tile.y);
            }
            return arr;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            updateTopology();
        }

        @Override
        public void created() {
            super.created();
            updateTopology();
            // 世界流/重连加载：原一次性 config 广播存在与本端构造的竞态，排一次校正比对
            if (mindustry.Vars.net.client()) topoCheckIn = HUB_TOPO_PREPARE_TICKS;
        }

        @Override
        public void placed() {
            // 自动补连移至 placeEnded（configured 之后执行）：先应用复制的原始
            // 连接模式，再用剩余容量像电力节点一样补连周围建筑
            super.placed();
            // 放置路径同款竞态：建完广播可能先于本端落盘，延迟比对一次
            if (mindustry.Vars.net.client()) topoCheckIn = HUB_TOPO_PREPARE_TICKS;
        }

        /** 发送本端拓扑快照请求：服务器比对不一致才广播全量权威拓扑，一致则静默。 */
        private void requestTopologyRepair() {
            if (mindustry.Vars.world.isGenerating()) return;
            if (!mindustry.Vars.net.client() || mindustry.Vars.net.server()) return;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(64);
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(tile.x);
                out.writeInt(tile.y);
                out.writeInt(links.size);
                for (int i = 0; i < links.size; i++) out.writeInt(links.get(i));
                out.writeInt(hubLinks.size);
                for (int i = 0; i < hubLinks.size; i++) out.writeInt(hubLinks.get(i));
            } catch (java.io.IOException ignored) {
            }
            mindustry.gen.Call.serverBinaryPacketReliable(topoRequestPacket, bytes.toByteArray());
        }

        /** 仅连接范围内的全部中枢（粉色骨架，无上限、双向对称、不占普通连接配额）。距离就近依次接入。 */
        private void autoConnectNearbyHubs(ItemTransferHub hubBlock) {
            Seq<Building> cands = new Seq<>();
            hubBlock.getPotentialLinks(tile, team, cands::add);
            cands.sort((a, b) -> Float.compare(Mathf.dst2(a.x - x, a.y - y), Mathf.dst2(b.x - x, b.y - y)));
            for (Building other : cands) {
                if (!(other instanceof ItemTransferHubBuild) || hasAnyLink(other.pos())) continue;
                configure(other.pos());
            }
        }

        /**
         * 自动连接（放置 / 双击 / 复制后补连共用），两阶段均按【距离就近】排序：
         * ① 范围内的全部中枢（hubLinks 独立计数、无上限）；
         * ② 其次连接「已选中枢所在网络之外」的非中枢建筑——占用 links 上限，
         *    由 Integer 处理器统一裁决（已连/满员自动跳过）。
         * 预览 drawPlace 以同一口径模拟，所见即所得。
         */
        private void autoConnectNearby(ItemTransferHub hubBlock) {
            Seq<Building> cands = new Seq<>();
            hubBlock.getPotentialLinks(tile, team, cands::add);
            cands.sort((a, b) -> Float.compare(Mathf.dst2(a.x - x, a.y - y), Mathf.dst2(b.x - x, b.y - y)));

            // ① 范围内的中枢按距离依次全部连接，并记录其整网覆盖
            arc.struct.ObjectSet<Building> coveredByHubs = new arc.struct.ObjectSet<>();
            for (Building other : cands) {
                if (!(other instanceof ItemTransferHubBuild) || hasAnyLink(other.pos())) continue;
                configure(other.pos());
                collectNetworkBuildings((ItemTransferHubBuild) other, coveredByHubs);
            }

            // ② 非中枢建筑：只连「已选中枢网络之外」的，同样就近；
            //    容量裁决在 Integer 处理器内（满员静默跳过）
            for (Building other : cands) {
                if (other instanceof ItemTransferHubBuild || hasAnyLink(other.pos())) continue;
                if (coveredByHubs.contains(other)) continue;
                if (!autoConnectTargetValid(this, other)) continue;
                configure(other.pos());
            }
        }

        /** 任一连接表（普通/中枢间）中存在该目标。 */
        private boolean hasAnyLink(int pos) {
            return links.contains(pos) || hubLinks.contains(pos);
        }

        // ── 建筑拓扑（Building Topology）──────────────────────
        // 职责：本中枢的 links → data.hubs/buildings 本地视图重建与陈旧链剔除。
        private void updateTopology() {
            // 存档/地图加载期间邻居建筑可能尚未创建（Tile.changed() 会逐个触发
            // onProximityUpdate），此时不能因“目标不存在”误删已保存的链接；
            // 加载完成后由周期刷新兜底清理真正失效的链接。
            boolean loading = world.isGenerating();

            // 普通连接：失效剔除（目标为普通建筑，无反向表）
            IntSeq stale = new IntSeq();
            links.each(pos -> {
                Building b = world.build(pos);
                if (b == null) {
                    if (!loading) stale.add(pos);
                    return;
                }
                if (!b.isValid() || b == this || !linkValid(this, b)) {
                    stale.add(pos);
                }
            });
            stale.each(pos -> links.removeValue(pos));

            // 中枢间连接：失效剔除 + 双向清理（对端 hubLinks 同步移除）
            IntSeq staleHub = new IntSeq();
            hubLinks.each(pos -> {
                Building b = world.build(pos);
                if (b == null) {
                    if (!loading) staleHub.add(pos);
                    return;
                }
                if (!b.isValid() || b == this || !linkValid(this, b)) {
                    staleHub.add(pos);
                }
            });
            staleHub.each(pos -> {
                hubLinks.removeValue(pos);
                Building other = world.build(pos);
                if (other instanceof ItemTransferHubBuild hub) {
                    hub.hubLinks.removeValue(this.pos());
                    rebuildData(hub);
                }
            });

            data.clear();
            links.each(pos -> {
                Building b = world.build(pos);
                if (b == null || !b.isValid() || b == this) return;
                if (b instanceof ItemTransferHubBuild hub) {
                    if (!data.hubs.contains(hub)) data.add(hub);
                } else if (shouldConnect(b)) {
                    if (!data.buildings.contains(b)) data.add(b);
                }
            });
            hubLinks.each(pos -> {
                Building b = world.build(pos);
                if (b instanceof ItemTransferHubBuild hub && !data.hubs.contains(hub)) {
                    data.add(hub);
                }
            });
            // 清理不再直连的消费者存量快照（防 Building 引用滞留）
            Seq<Building> staleKeys = new Seq<>();
            for (Building k : feedSnapshot.keys()) {
                if (!data.buildings.contains(k)) staleKeys.add(k);
            }
            for (Building k : staleKeys) feedSnapshot.remove(k);
        }

        // ── 建筑级更新（Building Update）──────────────────────
        // 职责：本中枢直连的工厂拉取 / 仓储溢出推送 + 本枢 power/transfer 统计。
        // 网络级（ItemTransferHubNetwork）只提供 enableDemandPull/SurplusPush 总开关与寻址辅助。
        // 电力统计：瞬时请求取「最近 SMOOTH_TICKS 帧计费均值」（平稳不跳变）；
        //           电力消耗按秒计算（60 tick 窗口），运输速率为 10 秒滑动窗口。
        @Override
        public void updateTile() {

            super.updateTile();

            // 多人拓扑校正：一次性 config 广播被成员端构造竞态静默丢弃时，
            // 靠延迟校正 + 常驻周期比对服务器权威拓扑自愈（防“主机有线成员无线”）。
            if (mindustry.Vars.net.client() && !mindustry.Vars.net.server()) {
                if (topoCheckIn > 0 && --topoCheckIn == 0) requestTopologyRepair();
                if (++topoRepairPeriod >= HUB_TOPO_REPAIR_TICKS) {
                    topoRepairPeriod = 0;
                    requestTopologyRepair();
                }
            }

            // 周期性拓扑刷新：链路目标被拆除时不会触发本枢邻近事件，
            // 定时剔除失效路径并回收连接数（timers=4 中使用 id=2）。
            if (timer(2, 120)) {
                updateTopology();
            }

            // 挂起链接补连：复制/粘贴时目标未建成（含建造中脚手架）的偏移，
            // 每 10 tick 重试一次（timers=4 中 id=3）——「每建完一个就连一个」；
            // 中枢目标无上限直接接上；普通目标满员保留挂起等空位；
            // 超过 600 秒仍未出现的目标（无法放置/已取消）过期清理
            // （大蓝图/慢速建造可能远超 3 分钟，过期太短会静默漏连）
            if (!pendingLinks.isEmpty() && timer(3, 10)) {
                float now = Time.time;
                for (int i = pendingLinks.size - 1; i >= 0; i--) {
                    // 过期清理：蓝图无法放置/被取消的目标不再无限等待
                    if (now - pendingAt.get(i) > 600f) {
                        pendingLinks.remove(i);
                        pendingAt.removeIndex(i);
                        continue;
                    }
                    arc.math.geom.Point2 p = pendingLinks.get(i);
                    Building other = world.build(tile.x + p.x, tile.y + p.y);
                    if (other == null
                        || other instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) {
                        continue; // 仍在建造中：继续等待
                    }
                    boolean hubTarget = other instanceof ItemTransferHubBuild;
                    if (!hubTarget && links.size >= maxConnections) continue; // 满员：保留挂起等空位
                    // 已连接（含事件处理器先行接入）→ 直接消费挂起项，不重复 configure
                    if (hasAnyLink(other.pos())) {
                        pendingLinks.remove(i);
                        pendingAt.removeIndex(i);
                        continue;
                    }
                    pendingLinks.remove(i);
                    pendingAt.removeIndex(i);
                    if (other == this || !other.isValid() || !linkValid(this, other)) continue;
                    configure(other.pos());
                }
            }

            // 帧首推进计费平滑窗口：清出最旧槽位供本帧写入
            smoothIdx = (smoothIdx + 1) % SMOOTH_TICKS;
            smoothBuf[smoothIdx] = 0f;

            // 并入跨枢延迟计费/计数——两者语义【刻意不对称】：
            // 计费写入平滑槽随窗口摊平（远端一跳费用同样平稳生效）；
            // 计数用【+=】：入口恒为上帧清零后的 0，若用赋值会把
            // 【本帧刚调度产生的自有件数】连同延迟量一起覆盖丢失 → 速率恒 0 而耗电正常。
            smoothBuf[smoothIdx] += powerConsumedNext;
            powerConsumedNext = 0f;
            transferCount += transferCountNext;
            transferCountNext = 0;

            rateTickCounter++;

            // 门控判定（「电力不足时完全停止工作」）：
            // 只有满电（status ≥ POWER_OK）才允许调度与中转；一旦欠压立即停转并进入冷却，
            // 冷却期请求清零（无幻影需求挤占电网）。冷却结束后先「探测」：发出与近期水平
            // 相当的真实请求但绝不搬运，下一帧该请求被电网全额交付（status 仍满格）才恢复
            // 工作——杜绝无源电网上因“零请求→status 恒为 1”而产生的白嫖式突发搬运，
            // 也避免逐帧在“要电/不要电”之间抖动造成消耗跳变与半功率偷跑。
            if (!enabled || power == null || power.status < POWER_OK) {
                powerStarved = true;
                probing = false;
                starveCooldown = STARVE_COOLDOWN_TICKS;
                java.util.Arrays.fill(smoothBuf, 0f);
                powerConsumed = 0f;
            } else if (starveCooldown > 0) {
                powerStarved = true;
                if (--starveCooldown == 0) {
                    // 冷却结束：本帧发出探测请求（至少一件的经手电费），下一帧验证是否足额供给
                    probing = true;
                    powerConsumed = Math.max(smoothSum() / SMOOTH_TICKS, PROBE_DRAW);
                } else {
                    probing = false;
                    java.util.Arrays.fill(smoothBuf, 0f);
                    powerConsumed = 0f;
                }
            } else {
                // 运行态（含探测验证帧——能走到这里说明上一帧请求已被电网足额交付）
                powerStarved = false;
                probing = false;
                // 调度节流 6Hz：计费写入当前平滑槽后再求均值，保证每笔费用恰好摊入 10 帧
                if (timer(0, 10)) {
                    if (network.enableDemandPull) {
                        pullOnDemand();
                    }
                    if (network.enableSurplusPush) {
                        pushSurplusToCore();
                    }
                }
                // 平稳瞬时请求：最近 SMOOTH_TICKS 帧计费均值（摊平 6Hz 批量突发）
                powerConsumed = smoothSum() / SMOOTH_TICKS;
            }

            // 本帧实际取电：运行/探测帧按电网满足率折算（门控确保满电，即等于全额请求）
            float actualPower = powerConsumed * (power == null ? 0f : Math.min(power.status, 1f));

            // 统计口径：吞吐入 10s 窗口（速率），取电入 1s 窗口（耗电按秒计算）
            rateWindowCounts.add(transferCount);
            rateWindowSum += transferCount;
            transferCount = 0;
            powerSecondWindow.add(actualPower);
            powerSecondSum += actualPower;
            if (rateWindowCounts.size > RATE_WINDOW_TICKS) {
                rateWindowSum -= rateWindowCounts.removeIndex(0);
            }
            if (powerSecondWindow.size > POWER_WINDOW_TICKS) {
                powerSecondSum -= powerSecondWindow.removeIndex(0);
            }

            // 停止态不调度，但统计照常刷新——窗口内推入零桶自然衰减：
            // 运输速率在断电后 10 秒内平滑降零（而非瞬间清零）；耗电约 1 秒内归零

            // 刷新：运输速率 = 10 秒窗口均值；电力消耗 = 最近 1 秒实际取电
            if (rateTickCounter % 10 == 0) {
                float rateSeconds = Math.max(rateWindowCounts.size, 1) / 60f;
                transferRate = rateWindowSum / rateSeconds;
                float powerSeconds = Math.max(powerSecondWindow.size, 1) / 60f;
                powerPerSecond = powerSecondSum / powerSeconds;
            }

            // 调试流量聚合输出（设置页开关控制，每 2 秒一次）
            if (debugFlows && ++debugTicks >= 120 && !debugFlow.isEmpty()) {
                StringBuilder sb = new StringBuilder("[中枢流量 @").append(tile.x).append(",").append(tile.y).append("]");
                for (arc.struct.ObjectMap.Entry<String, Integer> e : debugFlow) {
                    sb.append(' ').append(e.key).append('=').append(e.value);
                }
                sb.append(" | 速率=").append(transferRate).append(" 耗电=").append(powerPerSecond);
                SiliconLog.info(sb.toString());
                debugFlow.clear();
                debugTicks = 0;
            }
        }

        /** 平滑缓冲当前总和。 */
        private float smoothSum() {
            float s = 0f;
            for (float v : smoothBuf) s += v;
            return s;
        }

        private boolean isFactory(Building b) {
            return HubRouting.isFactory(b);
        }

        /** 消费者优先级：炮台(0) > 工厂(1) > 仓储(2)。数值越小越优先。 */
        private int consumerPriority(Building b) {
            return HubRouting.consumerPriority(b);
        }

        private boolean isProducer(Building b) {
            return HubRouting.isProducer(b);
        }

        /** 推送源判定：矿机/工厂溢出优先推核心（其次仓储由拉取补货，不主动推）。 */
        private boolean isPushProducer(Building b) {
            return isProducer(b);
        }

        /**
         * 拉取调度：
         * 消费者两级优先：炮台(0) > 工厂(1)；仓储不拉取。
         * 同级按缺口比例降序；工厂内多输入物品同帧连补。
         * 供源四级：仓库 → 核心 → 矿机/工厂产出 → 兜底同类输入料。
         * 路径约束：BFS 不经过欠压/禁用中枢（relayable 过滤）。
         */
        private boolean pullOnDemand() {

            boolean any = false;

            // 收集待补消费者
            arc.struct.Seq<Building> consumers = new arc.struct.Seq<>();
            for (Building b : data.buildings) {
                if (b.items == null || !b.isValid()) continue;
                if (!isFactory(b)) continue;
                for (int i = 0; i < content.items().size; i++) {
                    Item it = content.item(i);
                    if (it == null || it.id >= b.items.length()) continue;
                    if (b.items.get(it) < b.getMaximumAccepted(it)) {
                        consumers.add(b);
                        break;
                    }
                }
            }

            // 排序：炮台 > 工厂；同级缺口比降序
            consumers.sort((a, b) -> {
                int ta = consumerPriority(a);
                int tb = consumerPriority(b);
                if (ta != tb) return Integer.compare(ta, tb);
                return Float.compare(deficitRatio(b), deficitRatio(a));
            });

            for (Building consumer : consumers) {

                if (consumer.items == null || !consumer.isValid()) continue;

                boolean turret = consumer instanceof ItemTurret.ItemTurretBuild;
                // 消耗匹配补货：非炮台消费者按「快照以来真实消耗量 + 缓冲」供给；
                // 炮台作战耗弹快，保持按缺口即时足量供弹
                int[] snap = turret ? null : feedSnapshot.get(consumer);

                // 候选物品：炮台按伤害降序；其余按缺口比降序
                Seq<Item> ordered = new Seq<>();
                if (consumer instanceof ItemTurret.ItemTurretBuild) {
                    ItemTurret tur = (ItemTurret) consumer.block;
                    tur.ammoTypes.each((it, bt) -> {
                        if (it != null && bt != null && it.id < consumer.items.length()
                            && consumer.items.get(it) < consumer.getMaximumAccepted(it)) {
                            ordered.add(it);
                        }
                    });
                    ordered.sort((a, b) -> Float.compare(
                        ((ItemTurret) consumer.block).ammoTypes.get(b).damage,
                        ((ItemTurret) consumer.block).ammoTypes.get(a).damage));
                } else {
                    for (int i = 0; i < content.items().size; i++) {
                        Item it = content.item(i);
                        if (it == null || it.id >= consumer.items.length()) continue;
                        int cap = consumer.getMaximumAccepted(it);
                        if (cap <= 0) continue;
                        if (consumer.items.get(it) < cap) ordered.add(it);
                    }
                    final Building fc = consumer;
                    ordered.sort((a, b) -> Float.compare(
                        itemDeficitRatio(fc, b, false),
                        itemDeficitRatio(fc, a, false)));
                }

                // 多源料工厂：同一帧连续补多种输入，不提前 break
                for (Item item : ordered) {
                    if (item.id >= consumer.items.length()) continue;
                    if (consumer.items.get(item) >= consumer.getMaximumAccepted(item)) continue;

                    Building supplier = findNearestSupplier(consumer, item);
                    if (supplier == null || !consumer.acceptItem(supplier, item)) continue;

                    // 电力硬门控：本枢欠压即停止一切搬运（与 updateTile 门控同口径）
                    if (!relayable(this)) return any;

                    // 预算：非炮台 = 真实消耗量 + 缓冲；首轮回看快照缺省 2
                    int budget = 10;
                    if (!turret) {
                        int consumedSince = snap == null || item.id >= snap.length
                            ? 0 : Math.max(0, snap[item.id] - consumer.items.get(item));
                        budget = Math.min(10, consumedSince + 2);
                        if (budget <= 0) continue;
                    }

                    if (directTransfer(supplier, consumer, item, budget)) {
                        any = true;
                        addFlow("拉:" + consumer.block.name, budget);
                    }
                }
                // 刷新快照为当前存量（含本轮供料），供下轮计算真实消耗
                if (!turret && consumer.items != null) {
                    int[] ns = new int[consumer.items.length()];
                    for (int i = 0; i < ns.length; i++) ns[i] = consumer.items.get(i);
                    feedSnapshot.put(consumer, ns);
                }
            }

            return any;
        }

    /** 单物品缺口比例：1 - 当前/上限，越大越缺。 */
    private float itemDeficitRatio(Building b, Item it, boolean storage) {
        if (it.id >= b.items.length()) {
            return 0f;
        }
        int cap = storage
            ? (int) (b.block.itemCapacity * 0.9f)
            : b.getMaximumAccepted(it);
        if (cap <= 0 || it.id >= b.items.length()) {
            return 0f;
        }
        return 1f - (float) b.items.get(it) / cap;
    }

        private float deficitRatio(Building b) {
            float maxDef = 0f;
            for (int i = 0; i < content.items().size; i++) {
                Item it = content.item(i);
                if (it == null || it.id >= b.items.length()) {
                    continue;
                }
                int cap;
                if (b instanceof StorageBlock.StorageBuild && !(b instanceof CoreBlock.CoreBuild)) {
                    cap = (int) (b.block.itemCapacity * 0.9f);
                } else {
                    cap = b.getMaximumAccepted(it);
                }
                if (cap <= 0) {
                    continue;
                }
                float ratio = 1f - (float) b.items.get(it) / cap;
                if (ratio > maxDef) {
                    maxDef = ratio;
                }
            }
            return maxDef;
        }

        /**
         * 推送调度（产出上行，只面向存储）：
         * - 矿机/工厂：任一物品达 75% 容量即排空——核心未满即推（acceptItem/getMaximumAccepted
         *   均为动态实际容量，随核心联动扩容实时更新）；核心满/拒收/无核才落仓库。
         * - 仓库：单物品 ≥90% 溢出强制回运；或核心该物品低于 75% 时回收存量（不受 90% 限制）。
         * - 配方输入料用 consumesItem 静态判定保护，绝不外运——
         *   旧版按 acceptItem 判定在「满仓」时失真（满仓 → acceptItem=false → 输入料被当产物
         *   推走 → 又被拉取补回），是仓库↔工厂乒乓空转、速率/耗电虚高的根因。
         * - 工厂补料一律由拉取侧按消耗预算执行；推送不再直接分发给消费者，
         *   避免绕过预算造成双重供给与乒乓倒手。
         */
        private void pushSurplusToCore() {

            for (Building producer : data.buildings) {

                if (producer.items == null || producer.items.empty() || !producer.isValid()) {
                    continue;
                }

                boolean isStorage = producer instanceof StorageBlock.StorageBuild
                    && !(producer instanceof CoreBlock.CoreBuild);
                boolean isProducerB = isPushProducer(producer);

                if (!isStorage && !isProducerB) {
                    continue;
                }

                // 矿机/工厂：任一输出达到快满阈值才排空；仓储用 90% 按物判断
                if (isProducerB) {
                    boolean blocked = false;
                    for (int k = 0; k < producer.items.length(); k++) {
                        Item ck = content.item(k);
                        if (ck == null) continue;
                        if (producer.items.get(ck) >= producer.block.itemCapacity * surplusPushAt) {
                            blocked = true;
                            break;
                        }
                    }
                    if (!blocked) continue;
                }

                for (int i = 0; i < producer.items.length(); i++) {

                    Item item = content.item(i);
                    if (item == null || producer.items.get(item) == 0) continue;

                    // 输入料保护（静态配方判定）：该建筑配方愿意消耗的物品绝不外运，
                    // 不受“当前是否已满”影响（满仓时 acceptItem 变假是乒乓根源）
                    if (!isStorage && producer.block.consumesItem(item)) continue;

                    if (isStorage) {
                        float stock = producer.items.get(item);
                        boolean surplus = stock >= producer.block.itemCapacity * 0.9f;
                        if (!surplus) {
                            // 核心该物品低于 75%（按核心真实容量 storageCapacity 计算，
                            // 不受 coreIncinerates 规则下 getMaximumAccepted 膨胀的影响）时，
                            // 仓库存量即可回收，不受仓库自身 90% 盈余阈值限制（与产出推送阈值对齐）
                            CoreBlock.CoreBuild probe = findNearestCore(producer, item);
                            if (probe == null
                                || probe.items.get(item) >= probe.storageCapacity * surplusPushAt) continue;
                        }
                    }

                    // 电力硬门控：本枢欠压即停止一切搬运
                    if (!relayable(this)) return;

                    // ① 核心未满即推（findNearestCore 内含 acceptItem 动态校验）
                    CoreBlock.CoreBuild core = findNearestCore(producer, item);
                    if (core != null) {
                        directTransfer(producer, core, item, 10);
                        addFlow("推:核心", 10);
                        continue;
                    }

                    // ② 核心满 / 拒收 / 无核：产物回流仓库
                    //    （findNearestStorage 排除推送源自身，防自投自收虚增吞吐）
                    StorageBlock.StorageBuild storage = findNearestStorage(producer, item);
                    if (storage != null) {
                        forceTransferToStorage(producer, storage, item, 10);
                        addFlow("推:仓库", 10);
                    }
                }
            }
        }
        /**
         * 强制入库：供源 → 仓库。跳过收方 acceptItem（规避原版仓库-核心容量联动），
         * 仅以仓库自身剩余容量为约束。
         */
        private boolean forceTransferToStorage(Building supplier, StorageBlock.StorageBuild storage, Item item, int maxAmount){
            if (!relayable(this)) return false;
            if (supplier.items == null || !supplier.isValid() || item.id >= supplier.items.length()) return false;
            // 端点归属枢不可中转（欠压/禁用）→ 整条路径不可用：不搬运、不计费
            ItemTransferHubBuild srcHub = findOwnerHub(supplier);
            if (srcHub != null && !relayable(srcHub)) return false;
            ItemTransferHubBuild dstHub = findOwnerHub(storage);
            if (dstHub != null && !relayable(dstHub)) return false;
            int stock = supplier.items.get(item);
            if (stock <= 0) return false;
            if (item.id >= storage.items.length()) return false;
            int space = storage.block.itemCapacity - storage.items.get(item);
            int moved = Math.min(Math.min(maxAmount, stock), Math.max(space, 0));
            if (moved <= 0) return false;

            // 与 directTransfer 同契约：逐件经 handleItem 交付（仓库默认实现即 items.add）
            for (int i = 0; i < moved; i++) {
                storage.handleItem(supplier, item);
            }
            supplier.items.remove(item, moved);
            addFlow("推:仓库", moved);

            // 计费与统计口径与 directTransfer 一致（统一在 chargeBatch 内完成）
            addFlow("推:->仓库", moved);
            chargeBatch(supplier, storage, moved);
            return true;
        }

        private void bfsInit() {

            bfsQueue.clear();
            bfsDists.clear();
            bfsVisited.clear();
            bfsVisited.add(id);
        }

        /**
         * 判断建筑是否已在本中枢网络内（经由任意中枢链路可达的直连建筑）。
         * 用于放置/双击自动连接时排除同网建筑——它们已被现有中枢服务。
         * 注意：BFS 队列中的【中枢本身】也是网络成员——判定必须含 cur == b，
         * 否则隔一个中枢单元的中枢会被误判为「不在网络内」（只考虑了直接连接的旧 bug）。
         */
        private boolean inSameNetwork(Building b) {
            if (b == null) return false;
            if (b == this || data.buildings.contains(b)) return true;
            bfsInit();
            for (ItemTransferHubBuild h : data.hubs) {
                if (h == b) return true;
                if (bfsVisited.add(h.id)) {
                    bfsQueue.add(h);
                    bfsDists.add(1);
                }
            }
            for (int i = 0; i < bfsQueue.size; i++) {
                ItemTransferHubBuild cur = bfsQueue.get(i);
                if (cur == b || cur.data.buildings.contains(b)) return true;
                for (ItemTransferHubBuild nb : cur.data.hubs) {
                    if (nb == b) return true;
                    if (bfsVisited.add(nb.id)) {
                        bfsQueue.add(nb);
                        bfsDists.add(bfsDists.get(i) + 1);
                    }
                }
            }
            return false;
        }

        /**
         * 供源三级优先级（满足工厂/炮台拉取时的取货顺序）：
         * ① 仓库（StorageBlock，非核心）—— 首选
         * ② 核心
         * ③ 矿机/工厂的【产出物】—— 静态配方判定：该建筑自己配方消耗的原料绝不外供，
         *    不受“该槽位是否已满”影响（旧版按 acceptItem 判定，满仓时变假导致输入料被抽走）
         * 【不兜底动用任何工厂/消费者的输入库存】——拿工厂原料做供给会把被抽工厂断粮停摆。
         * 每级内部取 BFS 最近；路径不经过欠压/禁用中枢（relayable）——无电中枢的辖内建筑视为不可达。
         */
        private Building findNearestSupplier(Building consumer, Item item) {

            for (int pass = 0; pass < 3; pass++) {

                Building best = null;
                int bestDist = Integer.MAX_VALUE;

                // 直连建筑：距离 1
                for (Building b : data.buildings) {
                    if (item.id >= b.items.length()) continue;
                    if (!supplierMatchesPass(b, item, pass)) continue;
                    if (b == consumer || !b.isValid() || b.items == null || b.items.get(item) <= 0) continue;
                    if (!consumer.acceptItem(b, item)) continue;
                    if (bestDist > 1) {
                        best = b;
                        bestDist = 1;
                    }
                }
                if (bestDist == 1) return best;

                // BFS 全网层序
                bfsInit();
                for (ItemTransferHubBuild hub : data.hubs) {
                    if (!relayable(hub)) continue; // 路径不经过欠压/禁用中枢
                    if (bfsVisited.add(hub.id)) {
                        bfsQueue.add(hub);
                        bfsDists.add(1);
                    }
                }

                for (int idx = 0; idx < bfsQueue.size; idx++) {
                    ItemTransferHubBuild hub = bfsQueue.get(idx);
                    int d = bfsDists.get(idx) + 1;
                    for (Building b : hub.data.buildings) {
                        if (item.id >= b.items.length()) continue;
                        if (!supplierMatchesPass(b, item, pass)) continue;
                        if (b == consumer || !b.isValid()) continue;
                        if (b.items == null || b.items.get(item) <= 0) continue;
                        if (!consumer.acceptItem(b, item)) continue;
                        if (d < bestDist) {
                            best = b;
                            bestDist = d;
                        }
                    }
                    for (ItemTransferHubBuild neighbor : hub.data.hubs) {
                        if (!relayable(neighbor)) continue; // 路径不经过欠压/禁用中枢
                        if (bfsVisited.add(neighbor.id)) {
                            bfsQueue.add(neighbor);
                            bfsDists.add(bfsDists.get(idx) + 1);
                        }
                    }
                }

                if (best != null) return best;
            }

            return null;
        }

        private boolean supplierMatchesPass(Building b, Item item, int pass) {
            switch (pass) {
                case 0: return b instanceof StorageBlock.StorageBuild && !(b instanceof CoreBlock.CoreBuild);
                case 1: return b instanceof CoreBlock.CoreBuild;
                // 供源仅产出物：静态配方判定，绝不抽走任何工厂/消费者的输入原料
                default: return isProducer(b) && !b.block.consumesItem(item);
            }
        }

        /**
         * 最近可收货仓储（非核心）。用于核心满或无核时的次级落点。
         * 直连与跨中枢 BFS 双层查找：仓库常连在其它中枢上，
         * 仅扫直连会导致“核心满却推不进仓库”。
         * 排除推送源自身（防自投自收虚增吞吐）；BFS 不经过欠压/禁用中枢。
         */
        private StorageBlock.StorageBuild findNearestStorage(Building producer, Item item) {
            StorageBlock.StorageBuild best = null;
            int bestDist = Integer.MAX_VALUE;

            // 第一层：直连建筑
            for (Building b : data.buildings) {
                if (!(b instanceof StorageBlock.StorageBuild st)) continue;
                if (b instanceof CoreBlock.CoreBuild) continue;
                if (b == producer) continue; // 防自投自收：推送源不能作为自己的落点
                if (!b.isValid() || b.items == null || item.id >= b.items.length()) continue;
                // 不检查 acceptItem：原版仓库与核心容量联动，核心满会连带拒收；
                // 以仓库自身容量为准即可
                if (b.items.get(item) >= b.block.itemCapacity) continue;
                int d = Math.abs(b.tile.x - producer.tile.x) + Math.abs(b.tile.y - producer.tile.y);
                if (d < bestDist) {
                    best = st;
                    bestDist = d;
                }
            }
            if (best != null) return best;

            // 第二层：BFS 全网层序，寻找其它中枢直连的仓库
            bfsInit();
            for (ItemTransferHubBuild hub : data.hubs) {
                if (!relayable(hub)) continue; // 路径不经过欠压/禁用中枢
                if (bfsVisited.add(hub.id)) {
                    bfsQueue.add(hub);
                    bfsDists.add(1);
                }
            }

            for (int idx = 0; idx < bfsQueue.size; idx++) {
                ItemTransferHubBuild hub = bfsQueue.get(idx);
                int d = bfsDists.get(idx) + 1;
                for (Building b : hub.data.buildings) {
                    if (!(b instanceof StorageBlock.StorageBuild st)) continue;
                    if (b instanceof CoreBlock.CoreBuild) continue;
                    if (b == producer) continue; // 防自投自收
                    if (!b.isValid() || b.items == null || item.id >= b.items.length()) continue;
                    if (b.items.get(item) >= b.block.itemCapacity) continue;
                    if (d < bestDist) {
                        best = st;
                        bestDist = d;
                    }
                }
                for (ItemTransferHubBuild neighbor : hub.data.hubs) {
                    if (!relayable(neighbor)) continue; // 路径不经过欠压/禁用中枢
                    if (bfsVisited.add(neighbor.id)) {
                        bfsQueue.add(neighbor);
                        bfsDists.add(bfsDists.get(idx) + 1);
                    }
                }
            }
            return best;
        }

        /**
         * 核心该物品是否有真实剩余空间。
         * 地图规则 coreIncinerates 开启时，原版 getMaximumAccepted 返回 10.7 亿、acceptItem 恒真——
         * 若据此判定“核心有空间”，会把物品送进已满核心被焚烧丢弃。必须以 storageCapacity 为准。
         */
        private static boolean coreHasRoomFor(CoreBlock.CoreBuild core, Item item) {
            return item.id < core.items.length() && core.items.get(item) < core.storageCapacity;
        }

        private CoreBlock.CoreBuild findNearestCore(Building producer, Item item) {
            // Route-variable: same BFS nearest logic for cores
            CoreBlock.CoreBuild best = null;
            int bestDist = Integer.MAX_VALUE;

            for (Building b : data.buildings) {
                if (b instanceof CoreBlock.CoreBuild core && b.isValid()
                    && core.acceptItem(producer, item) && coreHasRoomFor(core, item)) {
                    best = core;
                    bestDist = 1;
                    break;
                }
            }
            if (bestDist == 1) return best;

            bfsInit();
            for (ItemTransferHubBuild hub : data.hubs) {
                if (!relayable(hub)) continue; // 路径不经过欠压/禁用中枢
                if (bfsVisited.add(hub.id)) {
                    bfsQueue.add(hub);
                    bfsDists.add(1);
                }
            }

            for (int idx = 0; idx < bfsQueue.size; idx++) {
                ItemTransferHubBuild hub = bfsQueue.get(idx);
                int d = bfsDists.get(idx) + 1;
                for (Building b : hub.data.buildings) {
                    if (b instanceof CoreBlock.CoreBuild core && b.isValid()
                        && core.acceptItem(producer, item) && coreHasRoomFor(core, item)) {
                        if (d < bestDist) {
                            best = core;
                            bestDist = d;
                        }
                    }
                }
                for (ItemTransferHubBuild neighbor : hub.data.hubs) {
                    if (!relayable(neighbor)) continue; // 路径不经过欠压/禁用中枢
                    if (bfsVisited.add(neighbor.id)) {
                        bfsQueue.add(neighbor);
                        bfsDists.add(bfsDists.get(idx) + 1);
                    }
                }
            }
            return best;
        }

        /**
         * 批量直转：单次最多搬 maxAmount 件（受供源存量 / 收方余位约束），
         * 大幅提升矿机/工厂产物的吞吐速率。计费仍为每件经一枢 +10。
         * 路径约束：本枢与两端点归属枢必须可中转（relayable——满电且未停转）。
         */
        private boolean directTransfer(Building supplier, Building consumer, Item item, int maxAmount) {

            if (!relayable(this)) {
                return false;
            }

            if (supplier.items == null || supplier.isValid() == false || supplier.items.get(item) <= 0 || item.id >= supplier.items.length()) {
                return false;
            }

            if (!consumer.acceptItem(supplier, item)) {
                return false;
            }

            // 端点归属枢不可中转（欠压/禁用）→ 整条路径不可用：不搬运、不计费
            ItemTransferHubBuild srcHub = findOwnerHub(supplier);
            if (srcHub != null && !relayable(srcHub)) {
                return false;
            }
            ItemTransferHubBuild dstHub = findOwnerHub(consumer);
            if (dstHub != null && !relayable(dstHub)) {
                return false;
            }

            int supplierStock = supplier.items.get(item);
            int consumerSpace = consumer.getMaximumAccepted(item) - consumer.items.get(item);
            // 核心收方：以真实容量复核余量（coreIncinerates 规则下 getMaximumAccepted 膨胀为 10.7 亿）
            if (consumer instanceof CoreBlock.CoreBuild coreConsumer) {
                consumerSpace = Math.min(consumerSpace, coreConsumer.storageCapacity - coreConsumer.items.get(item));
            }

            // 距离过近保护：仅保留给「原版自身就在吞吐」的建筑对——
            // 生产建筑与消费者贴面时，钻头等会直接向邻接建筑倾倒产出，
            // 中枢再抽会造成同帧供需倒手。存储（仓库/核心）原版不会自动向邻居送料，
            // 作为供源或收方均豁免——否则贴面仓库永远喂不到旁边的工厂。
            boolean vanillaAutoMoves =
                supplier instanceof StorageBlock.StorageBuild
                || consumer instanceof CoreBlock.CoreBuild
                || consumer instanceof StorageBlock.StorageBuild;
            if (!vanillaAutoMoves) {
                int half = (supplier.block.size + consumer.block.size) / 2 + 1;
                if (Math.abs(supplier.tile.x - consumer.tile.x) <= half
                    && Math.abs(supplier.tile.y - consumer.tile.y) <= half) {
                    return false;
                }
            }

            // 供源保留配额：最多抽走存量的一半（向下取整），防止源头被瞬间抽干后
            // 看起来“拉不到原料”。矿机产量低时尤其明显。
            int reserve = supplierStock / 2;
            int available = supplierStock - reserve;

            int moved = Math.min(Math.min(maxAmount, available), Math.max(consumerSpace, 0));

            if (moved <= 0) {
                return false;
            }

            // 零缓冲代理：供方扣减 + 收方经 handleItem 逐件交付。
            // 默认 handleItem 为 items.add(1)；炮台等重写实现会把物品转换为弹药，
            // 直接 items.add 会绕过转换造成"死库存"（既非弹药也无法取出）。
            for (int i = 0; i < moved; i++) {
                consumer.handleItem(supplier, item);
            }
            supplier.items.remove(item, moved);

            // 经由计费：路径与费用整批只计算一次，避免逐件重跑 BFS；
            // 计费与吞吐计数统一在 chargeBatch 内完成
            chargeBatch(supplier, consumer, moved);

            return true;
        }

        /**
         * 计费与吞吐统计口径（统一入口）：
         * 物品每经过一个中枢：该中枢自身消耗 10 电力、经手件数 +moved。
         * 每个枢纽只统计"通过自己"的那一跳——发起枢不为其它枢的经手买单，
         * 路径不可达时也只按端点归属各记一跳，绝不把全程记到单个枢头上。
         */
        private void chargeBatch(Building supplier, Building consumer, int moved) {
            ItemTransferHubBuild srcHub = findOwnerHub(supplier);
            ItemTransferHubBuild dstHub = findOwnerHub(consumer);

            // 端点无法归属：仅当该端点直连本枢时才计入本枢
            if (srcHub == null || dstHub == null) {
                if ((srcHub == null && data.buildings.contains(supplier))
                    || (dstHub == null && data.buildings.contains(consumer))) {
                    smoothBuf[smoothIdx] += 10f * moved;
                    transferCount += moved;
                }
                return;
            }

            // 同枢直转：费用与吞吐归该枢本身（本枢直接入账，远端枢延迟一帧）
            if (srcHub == dstHub) {
                chargeOne(srcHub, moved);
                return;
            }

            Seq<ItemTransferHubBuild> path = bfsPath(srcHub, dstHub);
            if (path == null || path.size == 0) {
                // 路径不可达（拓扑竞态兜底）：两端点归属枢各记自己的一跳
                chargeOne(srcHub, moved);
                chargeOne(dstHub, moved);
                return;
            }

            // 路径上每个经手中枢各计自己的一跳；本枢直接入账，远端枢下一帧生效
            IntSet charged = new IntSet();
            for (ItemTransferHubBuild h : path) {
                if (!charged.add(h.id)) continue;
                chargeOne(h, moved);
            }
        }

        /** 调试计数（仅 debugFlows 开启时累计）。 */
        void addFlow(String tag, int moved) {
            if (debugFlows) debugFlow.put(tag, debugFlow.get(tag, 0) + moved);
        }

        /**
         * 该中枢是否可参与传输（作为路径节点或端点）：
         * 已启用、有电网、满电（status ≥ POWER_OK）且未处于欠压冷却——
         * 「电力不足时完全停止工作」，路径选择不得经过不满足条件的中枢。
         */
        boolean relayable(ItemTransferHubBuild h) {
            return h.enabled && h.power != null && h.power.status >= POWER_OK && !h.powerStarved;
        }

        /** 单跳计费/计数：本枢写入计费平滑缓冲，远端枢写入延迟队列（下一帧并入）。 */
        private void chargeOne(ItemTransferHubBuild h, int moved) {
            float share = 10f * moved;
            if (h == this) {
                smoothBuf[smoothIdx] += share;
                transferCount += moved;
            } else {
                h.powerConsumedNext += share;
                h.transferCountNext += moved;
            }
        }


        private ItemTransferHubBuild findOwnerHub(Building b) {
            if (b instanceof ItemTransferHubBuild) return (ItemTransferHubBuild) b;
            if (data.buildings.contains(b)) return this;
            for (ItemTransferHubBuild hub : data.hubs) {
                if (hub.data.buildings.contains(b)) return hub;
            }
            // variable route: building may be on a hub beyond direct neighbors — BFS search whole network
            bfsInit();
            for (ItemTransferHubBuild h : data.hubs) {
                if (bfsVisited.add(h.id)) {
                    bfsQueue.add(h);
                    bfsDists.add(1);
                }
            }
            for (int i = 0; i < bfsQueue.size; i++) {
                ItemTransferHubBuild cur = bfsQueue.get(i);
                if (cur.data.buildings.contains(b)) return cur;
                for (ItemTransferHubBuild nb : cur.data.hubs) {
                    if (bfsVisited.add(nb.id)) {
                        bfsQueue.add(nb);
                        bfsDists.add(bfsDists.get(i) + 1);
                    }
                }
            }
            return null;
        }

        private Seq<ItemTransferHubBuild> bfsPath(ItemTransferHubBuild src, ItemTransferHubBuild dst) {
            if (src == dst) {
                Seq<ItemTransferHubBuild> s = new Seq<>();
                s.add(src);
                return s;
            }
            bfsInit();
            Seq<ItemTransferHubBuild> parentHub = new Seq<>();
            IntSeq parentIdx = new IntSeq();
            // reuse bfsQueue/bfsVisited for hubs, track parent index
            bfsQueue.clear();
            bfsVisited.clear();
            bfsVisited.add(src.id);
            bfsQueue.add(src);
            parentHub.add((ItemTransferHubBuild) null);
            parentIdx.add(-1);
            for (int i = 0; i < bfsQueue.size; i++) {
                ItemTransferHubBuild cur = bfsQueue.get(i);
                if (cur == dst) {
                    Seq<ItemTransferHubBuild> path = new Seq<>();
                    int at = i;
                    while (at >= 0) {
                        path.add(bfsQueue.get(at));
                        at = parentIdx.get(at);
                    }
                    path.reverse();
                    return path;
                }
                for (ItemTransferHubBuild nb : cur.data.hubs) {
                    if (!relayable(nb)) continue; // 路径不经过欠压/禁用中枢
                    if (bfsVisited.add(nb.id)) {
                        bfsQueue.add(nb);
                        parentHub.add(cur);
                        parentIdx.add(i);
                    }
                }
            }
            return null;
        }

        @Override
        public void draw() {
            super.draw();
            // 连线不再随方块自身绘制：改由 Trigger.drawOver 全局覆盖层统一驱动
            // （见类顶部静态注册处）——电力节点家族式「上层绘画」，全部更新后一次画完，
            // 严格位于一切方块几何之上且不受方块绘制批次影响。
        }

        /** 全局覆盖层调用：绘制本枢全部连线（普通物流色 + 中枢间粉色）。层级由调用方设定。 */
        void drawLinksGlobal() {
            // 视口裁剪：范围圈外且在屏外的枢不提交精灵
            float range = block.size * tilesize + connectionRange * tilesize;
            if (Math.abs(x - Core.camera.position.x) > Core.camera.width / 2f + range
                || Math.abs(y - Core.camera.position.y) > Core.camera.height / 2f + range) {
                return;
            }

            Lines.stroke(2f);
            // 普通连接（物流色）
            links.each(pos -> {
                Building other = world.build(pos);
                if (other == null || !other.isValid()) return;
                if (!linkValid(this, other)) return;

                if (other instanceof ItemTransferHubBuild && other.id >= id) return;

                float angle = Angles.angle(x, y, other.x, other.y);
                float cos = Mathf.cosDeg(angle);
                float sin = Mathf.sinDeg(angle);

                // 端点对齐原版 PowerNode.drawLaser：半边长【先内缩 1.5px】再投影——
                // 端点落在方块边缘内侧（旧写法先到边界再外推 1.5px，端点会悬在方块外）
                float len1 = block.size * tilesize / 2f - 1.5f;
                float len2 = other.block.size * tilesize / 2f - 1.5f;

                // 连线：中枢间粉色、中枢→建筑物流色；电力节点激光样式，稳定色不闪烁
                Draw.color(lineColorFor(other), linkOpacity());
                // 原版大小：PowerNode 默认 laserScale=0.25
                Drawf.laser(laserRegion, laserEndRegion, laserEndRegion,
                    x + cos * len1, y + sin * len1,
                    other.x - cos * len2, other.y - sin * len2, 0.25f);
            });
            // 中枢间连接（粉色，独立列表）
            hubLinks.each(pos -> {
                Building other = world.build(pos);
                if (other == null || !other.isValid()) return;
                if (!linkValid(this, other)) return;
                if (other instanceof ItemTransferHubBuild && other.id >= id) return;

                float angle = Angles.angle(x, y, other.x, other.y);
                float cos = Mathf.cosDeg(angle);
                float sin = Mathf.sinDeg(angle);
                float len1 = block.size * tilesize / 2f - 1.5f;
                float len2 = other.block.size * tilesize / 2f - 1.5f;

                Draw.color(hubLinkColor, linkOpacity());
                Drawf.laser(laserRegion, laserEndRegion, laserEndRegion,
                    x + cos * len1, y + sin * len1,
                    other.x - cos * len2, other.y - sin * len2, 0.25f);
            });
        }

        @Override
        public void drawSelect() {
            super.drawSelect();

            Drawf.dashCircle(x, y, connectionRange * tilesize, Pal.accent);

            Draw.reset();
        }

        @Override
        public void drawConfigure() {
            super.drawConfigure();

            Drawf.circles(x, y, block.size * tilesize / 2f + 1f + Mathf.absin(Time.time, 4f, 1f));

            Drawf.circles(x, y, connectionRange * tilesize);

            int rangeTiles = (int) connectionRange;
            for (int ix = tile.x - rangeTiles - 2; ix <= tile.x + rangeTiles + 2; ix++) {
                for (int iy = tile.y - rangeTiles - 2; iy <= tile.y + rangeTiles + 2; iy++) {
                    Building link = world.build(ix, iy);
                    if (link == this || link == null) continue;
                    boolean linked = hasAnyLink(link.pos());
                    // 三色标记与放置预览同口径：蓝=已直连、紫=同网络未直连、绿=可新建
                    if (linked && linkValid(this, link)) {
                        // 已直连：蓝色
                        Drawf.square(link.x, link.y, link.block.size * tilesize / 2f + 1f, Pal.place);
                    } else if (linkValid(this, link)) {
                        if (inSameNetwork(link)) {
                            // 同网络但未直连（跨枢链路可达，含中枢本身）：紫色
                            Drawf.square(link.x, link.y, link.block.size * tilesize / 2f + 1f, Pal.reactorPurple);
                        } else {
                            // 可新建直连（不在网络内）：绿色提示
                            Drawf.square(link.x, link.y, link.block.size * tilesize / 2f + 1f, Pal.heal);
                        }
                    }
                }
            }

            Draw.reset();
        }

        @Override
        public boolean onConfigureBuildTapped(Building other) {
            // PowerNode-style: single tap on valid target toggles link
            if (linkValid(this, other)) {
                configure(other.pos());
                return false;
            }
            // double-tap self (PowerNode: linkValid branch above already handles single tap, this == other is double)
            if (this == other) {
                ItemTransferHub hubBlock = (ItemTransferHub) block;
                if (links.size > 0 || hubLinks.size > 0) {
                    // 双击清空全部链接：必须走 configure()（Point2[] 处理器在服务器统一清理
                    // 本端与各对端反向引用后再广播）——直接改写本地列表只会让本客户端清净，
                    // 服务器与其客户端毫不知情，跨端拓扑立即漂移。
                    configure(new arc.math.geom.Point2[0]);
                } else {
                    // 双击自动连接：与放置同一套【距离就近】逻辑（逐条 configure 已走复制的配置通道）
                    autoConnectNearby(hubBlock);
                }
                deselect();
                return false;
            }
            return true;
        }

        // ── 存档序列化（Save / Load）──────────────────────
        // v2 格式：network.id(int) + links(short+int...) + hubLinks(short+int...)。
        // version() 必须与格式配套：未序列化链接的旧构建写入 revision=0 且无自定义数据，
        // 读取时按 revision<1 直接跳过；v1 存档无 hubLinks 段，按空处理。

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(network.id);
            write.s(links.size);
            for (int i = 0; i < links.size; i++) {
                write.i(links.get(i));
            }
            write.s(hubLinks.size);
            for (int i = 0; i < hubLinks.size; i++) {
                write.i(hubLinks.get(i));
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision < 1) {
                // 未写过自定义数据的存档：保持空链接，加载后由放置预览/周期刷新重建
                return;
            }
            network.id = read.i();
            short linkCount = read.s();
            links.clear();
            for (int i = 0; i < linkCount; i++) {
                links.add(read.i());
            }
            hubLinks.clear();
            if (revision >= 2) {
                short hubCount = read.s();
                for (int i = 0; i < hubCount; i++) {
                    hubLinks.add(read.i());
                }
            }
            // 仅按已存在的建筑重建本地视图；加载中缺失的邻居由
            // onProximityUpdate / 周期 updateTopology 补齐，不在此剔除链接
            rebuildData(this);
        }

        @Override
        public byte version() {
            return 2;
        }
    }
}
