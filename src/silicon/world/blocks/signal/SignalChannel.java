package silicon.world.blocks.signal;

import arc.math.Mathf;
import arc.struct.ObjectMap;
import mindustry.game.Team;
import mindustry.gen.Building;
import silicon.util.SatelliteManager;

/**
 * 信道信号统一计算（SINR 比值制）：
 * - 环境底噪/热噪声（含噪声系数）：固定底噪 N0，参与信噪比分母
 * - 同信道干扰（CCI）：最强源为目标，其余同信道源强度之和为干扰
 * - 邻信道干扰（ACI）：其他信道源强度 × ACIR 泄漏系数
 * - 同信道/全信道干扰器：干扰强度（与信号同模型衰减）直接叠加；邻信道泄漏（ACIR_jam）
 * - 有效强度 = 信号功率 × 质量因子(SINR)：SINR = best / (N0 + 干扰总和)
 *   SINR ≤ 1（功率压不过噪声+干扰）→ 无信号；SINR ≥ SINR_REF → 满质量；中间平滑。
 *   干扰压的是信噪比（比值），不是从幅度扣功率——强信号天然抗弱干扰。
 */
public class SignalChannel {
    /** 底噪（强度域 0~15，含噪声系数；SINR 分母的固定项） */
    public static final float NOISE_FLOOR = 0.5f;
    /** SINR 参考阈值：SINR ≥ 此值（≈5.4dB）满质量——校准使 GEO 单星 0.53 / LEO 单星 1.2，
     *  与旧线性制的激活阈值（>0.5）与边缘绑定行为对齐 */
    public static final float SINR_REF = 3.5f;

    /** SINR → 质量因子：sinr ≤ 1 → 0（无信号）；sinr ≥ {@link #SINR_REF} → 1（满质量）；中间线性平滑。
     *  有效强度 = 信号功率 × 质量因子；eff > 0 ⟺ 信号功率 > 底噪+干扰（物理可检测的唯一定义） */
    public static float sinrQuality(float signal, float interference) {
        if (signal <= 0f) return 0f;
        float sinr = signal / Math.max(interference, 1e-4f);
        float q = (sinr - 1f) / (SINR_REF - 1f);
        return Math.max(0f, Math.min(1f, q));
    }

    /** 邻信道泄漏系数 ACIR：Δch=0 → 1（本信道），1 → 0.25，2 → 0.08，≥3 → 0（忽略） */
    public static float acir(int dch) {
        int d = Math.abs(dch);
        if (d == 0) return 1f;
        if (d == 1) return 0.25f;
        if (d == 2) return 0.08f;
        return 0f;
    }

    /** 干扰器邻信道泄漏系数（比发射器略大）：Δch=1 → 0.4，2 → 0.12，≥3 → 0 */
    public static float acirJam(int dch) {
        int d = Math.abs(dch);
        if (d == 0) return 1f;
        if (d == 1) return 0.4f;
        if (d == 2) return 0.12f;
        return 0f;
    }

    /**
     * (wx,wy) 处是否处于指定信号 name 的"信号范围"内。
     * 同一编码视为同一信号：在轨卫星的星下点覆盖圆（未被同信道干扰完全压制）、
     * 信号源自身覆盖、同编码激活中继器的级联延伸，三者广播的有效范围取并集。
     * 供卫星控制台 ↔ 卫星发射中枢绑定判定（控制台与中枢必须同处该信号范围内）。
     * 卫星层有上行门控（{@link #hasLiveSource}）：该编码的地面源全部消失后，
     * 在轨卫星停止广播该编码——删源即断链，重放同编码源即恢复。
     */
    public static boolean inSignalRange(Team team, String name, float wx, float wy) {
        if (name == null || name.isEmpty()) return false;
        // 在轨卫星：编码匹配的卫星，其星下点覆盖圆含该点且有效强度 > 0（同信道干扰可打断卫星绑定）。
        // 上行门控：编码无存活地面源时卫星层整体静默（卫星只是地面信号的转发者）
        if (hasLiveSource(team, name)) {
            for (SatelliteManager.SatelliteRecord r : SatelliteManager.satellites(team)) {
                if (r.code != null && name.equals(r.code) && SatelliteManager.satelliteEffAt(r, wx, wy) > 0f) {
                    return true;
                }
            }
        }
        return inGroundSignalRange(team, name, wx, wy);
    }

    /**
     * 仅地面覆盖（信号源 + 激活中继器），卫星覆盖不参与。
     * 供卫星发射的 1:1 配对计数（hubsInSignal/consolesInSignal）使用：卫星覆盖只解锁
     * "远方指派"该编码，配对仍约束地面基建布局——否则全图覆盖会把所有中枢算进同一
     * "范围"，多中枢队伍永远 MULTI_HUB，发射能力被自己的卫星锁死。
     */
    public static boolean inGroundSignalRange(Team team, String name, float wx, float wy) {
        if (name == null || name.isEmpty()) return false;
        for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
            if (sb.signal != null && name.equals(sb.signal.name)
                    && SignalSource.strengthAt(sb.x, sb.y, wx, wy) > 0f) {
                return true;
            }
        }
        for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(team)) {
            if (rb.active && name.equals(rb.selectedSource) && rb.strengthAt(wx, wy) > 0f) {
                return true;
            }
        }
        return false;
    }

    // —— 卫星上行门控：卫星广播编码 X 的前提是本队存在存活的地面信号源 X ——

    /** (team, code) → 是否存在存活地面源；SignalSource.markDirty 时整体失效（增删/世界加载/编码下发） */
    private static final ObjectMap<Team, ObjectMap<String, Boolean>> liveSrcCache = new ObjectMap<>();

    /** 失效上行门控缓存（信号源增删、世界加载、编码经 tileConfig 下发时调用） */
    public static void invalidateLiveSources() {
        liveSrcCache.clear();
    }

    /**
     * 指定编码当前是否存在本队存活的地面信号源（删除最后一个该编码源 → false）。
     * 卫星广播的上行门控：卫星只是地面信号的转发者，地面源全部消失后该编码卫星停止广播，
     * 绑定该编码的中继器随之去活；重放同编码源后自动恢复（名册不动，语义与"源被拆不影响卫星信道"的编码固化正交）。
     * 结果按 (team, code) 缓存，失效时机由 {@link silicon.world.blocks.signal.SignalSource#markDirty} 驱动。
     */
    public static boolean hasLiveSource(Team team, String code) {
        if (team == null || code == null || code.isEmpty()) return false;
        ObjectMap<String, Boolean> m = liveSrcCache.get(team);
        if (m == null) {
            m = new ObjectMap<>();
            liveSrcCache.put(team, m);
        }
        Boolean v = m.get(code);
        if (v != null) return v;
        boolean found = false;
        for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
            if (sb.signal != null && code.equals(sb.signal.name)) {
                found = true;
                break;
            }
        }
        m.put(code, found);
        return found;
    }

    // —— 每信道批量计算（覆盖绘制用）：静态缓冲，一次遍历全部源按信道分摊 ——
    private static final float[] bestA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final Building[] bestSrcA = new Building[SignalJammer.CHANNEL_MAX + 1];
    private static final String[] bestIdA = new String[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] otherA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] aciA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] jamA = new float[SignalJammer.CHANNEL_MAX + 1];

    /** 将某源信号按信道分摊：本信道按身份计入 best/other，邻信道计入 ACI */
    private static void addSource(int ch, float s, String id, Building src) {
        if (ch < 1 || ch > SignalJammer.CHANNEL_MAX) return;
        if (ch > 1) aciA[ch - 1] += s * acir(1);
        if (ch < SignalJammer.CHANNEL_MAX) aciA[ch + 1] += s * acir(1);
        if (ch > 2) aciA[ch - 2] += s * acir(2);
        if (ch < SignalJammer.CHANNEL_MAX - 1) aciA[ch + 2] += s * acir(2);
        // 本信道：同身份取最强不互扰，不同身份计 CCI
        if (id.equals(bestIdA[ch])) {
            if (s > bestA[ch]) {
                bestA[ch] = s;
                bestSrcA[ch] = src;
            }
        } else if (s > bestA[ch]) {
            otherA[ch] += bestA[ch];
            bestA[ch] = s;
            bestIdA[ch] = id;
            bestSrcA[ch] = src;
        } else {
            otherA[ch] += s;
        }
    }

    /**
     * 批量计算位置 (wx,wy) 所有信道（1~5）的有效信号强度。
     * 一次遍历全部信号源/中继器/干扰器，按信道分摊（含底噪/CCI/ACI/干扰器），
     * 结果写入 effOut[1..5] 与 srcOut[1..5]（最强同信道源，用于颜色）。
     * 比逐信道调用 effective 快约 5 倍（覆盖绘制用）。
     */
    public static void effectiveAll(Team team, float wx, float wy, float[] effOut, Building[] srcOut) {
        effectiveAll(team, wx, wy, effOut, srcOut, null);
    }

    /**
     * 同上，额外把每信道"底噪+干扰总和"（SINR 分母 I）写入 intOut[1..5]——
     * 频谱面板用它区分"本点干扰功率"与"可用有效强度"；传 null 等价于无干扰输出。
     */
    public static void effectiveAll(Team team, float wx, float wy, float[] effOut, Building[] srcOut, float[] intOut) {
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            bestA[ch] = 0f;
            bestSrcA[ch] = null;
            bestIdA[ch] = null;
            otherA[ch] = 0f;
            aciA[ch] = 0f;
            jamA[ch] = 0f;
        }
        // 信号源
        for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
            float s = sb.strengthAt(wx, wy);
            if (s <= 0f) continue;
            addSource(sb.channel, s, "S" + sb.signal.name, sb);
        }
        // 激活中继器（级联源；发射信道与所选信号源一致）
        for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(team)) {
            if (!rb.active) continue;
            float s = rb.strengthAt(wx, wy);
            if (s <= 0f) continue;
            String id = (rb.selectedSource != null && !rb.selectedSource.isEmpty())
                    ? "S" + rb.selectedSource : "R" + ((int) rb.x * 7 + (int) rb.y * 13);
            addSource(rb.signalChannel(), s, id, rb);
        }
        // 干扰器（全局：敌方干扰器同样压制本信道；同信道 + 邻信道泄漏）
        // enabled 守卫与 SignalJammer.strengthAt 口径一致:关闭(enabled=false)不发射干扰——
        // 否则 H 覆盖中同一干扰器在信道层"仍在压制"、卫星层却已消失,自相矛盾
        for (SignalJammer.SignalJammerBuild jb : SignalJammer.allJammers()) {
            if (!jb.enabled) continue;
            float j = SignalSource.strengthAt(jb.x, jb.y, wx, wy);
            if (jb.jamChannel == SignalJammer.ALL) {
                for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) jamA[ch] += j;
            } else {
                int c = jb.jamChannel;
                jamA[c] += j;
                if (c > 1) jamA[c - 1] += j * acirJam(1);
                if (c < SignalJammer.CHANNEL_MAX) jamA[c + 1] += j * acirJam(1);
                if (c > 2) jamA[c - 2] += j * acirJam(2);
                if (c < SignalJammer.CHANNEL_MAX - 1) jamA[c + 2] += j * acirJam(2);
            }
        }
        // 每信道有效信号 = 信号功率 × 质量因子(SINR)：干扰压信噪比，不从幅度扣功率
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            float i = NOISE_FLOOR + otherA[ch] + aciA[ch] + jamA[ch];
            effOut[ch] = bestA[ch] * sinrQuality(bestA[ch], i);
            srcOut[ch] = bestSrcA[ch];
            if (intOut != null) intOut[ch] = i;
        }
    }
}

