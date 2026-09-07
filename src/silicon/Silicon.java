package silicon;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.TextField;
import arc.util.Time;
import mindustry.core.GameState;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.mod.Mod;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;
import silicon.content.SatelliteUnits;
import silicon.content.block.Blocks;
import silicon.content.item.Items;
import silicon.util.SatelliteManager;
import silicon.util.SiliconLog;
import silicon.util.SignalOverlay;
import silicon.util.UpdateChecker;
import silicon.world.blocks.power.PowerProtector;
import silicon.world.blocks.production.MineConverter;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalSource;
import silicon.ui.BlockSearch;

import static mindustry.Vars.*;


public class Silicon extends Mod {
    public static Mods.LoadedMod MOD;

    /**
     * 自定义设置项：在设置表中插入任意内容（分隔线、按钮等）。
     * 通过 SettingsTable.pref() 注册进设置列表，rebuild（恢复默认/切换分类）时自动保留；
     * name 传 null，恢复默认设置时不会被删除。
     */
    public static class CustomSetting extends SettingsMenuDialog.SettingsTable.Setting {
        private final Cons<SettingsMenuDialog.SettingsTable> cons;

        public CustomSetting(Cons<SettingsMenuDialog.SettingsTable> cons) {
            super(null);
            this.cons = cons;
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            cons.get(table);
            table.row();
        }
    }

    /** 卫星状态周期广播计时（约 30 tick / 0.5s） */
    private static int satelliteBroadcastTick = 0;

    public Silicon() {
        Events.on(EventType.ClientLoadEvent.class, e -> {
            MOD = mods.getMod(Silicon.class);
            if (MOD != null) MOD.meta.subtitle = MOD.meta.version;
        });
    }

    @Override
    public void loadContent() {
        Items.load();
        Blocks.load();
        SatelliteUnits.load();
        SiliconLog.info("Loading contents.");
    }

    @Override
    public void init() {
        // 信号源/中继器按队缓存也在世界加载时失效重建（读档后建筑重新加入 Groups.build）。
        // 注:hub network id 计数器不再在此 reset——读档顺序是构造(占号)→read 用存档 id
        // 覆盖→WorldLoadEvent,reset 反而制造撞号;现由 ItemTransferHubBuild.read() 调
        // ItemTransferHubNetwork.updateCounterAfterLoad 按 max 推进。
        // 卫星名册的清空挂在 ResetEvent（存档读入前/返回主菜单/新开局都会触发）——
        // 读档流程是 ResetEvent 清空 → 控制台存档块（map 区域）读入重建名册 → WorldLoadEvent
        // （此时单位实体尚未读入！）→ entities 区域读入单位 → app.post 延迟一拍做真正生效的对账。
        Events.on(EventType.ResetEvent.class, e -> SatelliteManager.reset());
        Events.on(EventType.WorldLoadEvent.class, e -> {
            SignalSource.markDirty();
            SignalRelay.markDirty();
            // 名册↔卫星实体对账：存档读入时 WorldLoadEvent 早于单位读入（readMap→endMapLoad→readEntities），
            // 此刻 Groups.unit 还没有卫星，这里的调用只覆盖"实体先于事件"的路径（如直接进新图）
            SatelliteManager.onWorldLoaded();
            Core.app.post(SatelliteManager::onWorldLoaded); // 存档读档真正生效的对账：entities 区域已读完
            SignalOverlay.reset(); // 清颜色缓存/色相分配/显示状态，防跨世界累积
        });
        // 卫星实体被击落（伤害仅可能来自 scripted unit.damage()）→ 名册除名并广播
        Events.on(EventType.UnitDestroyEvent.class, e -> SatelliteManager.onUnitDestroyed(e.unit));
        // 玩家中途加入时：主机向新玩家补发卫星状态 + 中继器激活状态
        Events.on(EventType.PlayerJoin.class, e -> {
            if (net.server()) {
                SatelliteManager.broadcastState(e.player.team());
                // 补发该队所有中继器当前 active（active 是自定义字段不随实体同步；新玩家加入时已稳定的
                // 中继器不会再有变化事件，必须全量发一次，否则客机 H 键覆盖缺中继器级联段）
                for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(e.player.team())) {
                    Call.tileConfig(e.player, rb, rb.active);
                }
            }
            // PowerProtector 无全局静态状态，数据随存档保存，无需重置
        });

        BlockSearch.init();
        MineConverter.initNetworking();
        SignalOverlay.init();

        // 卫星发射请求（客机 → 服务器）：注册在 init 而非 ClientLoadEvent——dedicated 服务器（无客户端，
        // 不触发 ClientLoadEvent）也必须能处理发射请求。主机权威执行，失败原因定向回发，成功走全图播报+状态广播
        if (netServer != null) {
            netServer.addPacketHandler("sat-launch", (p, data) -> {
                try {
                    String[] parts = data.split("\\|", -1);
                    if (parts.length != 3) {
                        // 所有失败路径都必须回包,否则请求方 UI 一直等待
                        Call.clientPacketReliable(p.con, "sat-result", "fail");
                        SiliconLog.info("sat-launch: malformed packet (fields) from " + p.name);
                        return;
                    }
                    String[] xy = parts[0].split(",");
                    if (xy.length != 2) {
                        Call.clientPacketReliable(p.con, "sat-result", "fail");
                        SiliconLog.info("sat-launch: malformed packet (coords) from " + p.name);
                        return;
                    }
                    mindustry.world.Tile tile = world.tile(
                            Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
                    if (tile == null || !(tile.build instanceof silicon.world.blocks.satellite.SatelliteConsole.SatelliteConsoleBuild)) {
                        // 控制台可能已被拆除/替换:给请求者明确反馈,而非无声死点击
                        Call.clientPacketReliable(p.con, "sat-result", "fail");
                        SiliconLog.info("sat-launch: invalid console tile from " + p.name);
                        return;
                    }
                    silicon.world.blocks.satellite.SatelliteConsole.SatelliteConsoleBuild cb =
                            (silicon.world.blocks.satellite.SatelliteConsole.SatelliteConsoleBuild) tile.build;
                    if (cb.team != p.team()) {
                        // 只能操作本队控制台;越权请求回笼统 fail(细节只进日志,不向可疑客户端透露原因)
                        SiliconLog.info("sat-launch: team mismatch from " + p.name);
                        Call.clientPacketReliable(p.con, "sat-result", "fail");
                        return;
                    }
                    if (!cb.enabled) {
                        Call.clientPacketReliable(p.con, "sat-result", "disabled");
                        return;
                    }
                    int orbit;
                    try {
                        orbit = Integer.parseInt(parts[2].trim());
                        if (orbit < silicon.world.blocks.satellite.SatelliteConsole.ORBIT_LEO
                                || orbit > silicon.world.blocks.satellite.SatelliteConsole.ORBIT_SSO) {
                            Call.clientPacketReliable(p.con, "sat-result", "fail");
                            SiliconLog.info("sat-launch: orbit out of range from " + p.name);
                            return;
                        }
                    } catch (NumberFormatException e) {
                        Call.clientPacketReliable(p.con, "sat-result", "fail");
                        SiliconLog.info("sat-launch: malformed packet (orbit) from " + p.name);
                        return;
                    }
                    int result = SatelliteManager.launch(p.team(), parts[1].isEmpty() ? null : parts[1], orbit, cb.x, cb.y);
                    if (result != SatelliteManager.LAUNCH_OK) {
                        Call.clientPacketReliable(p.con, "sat-result", String.valueOf(result));
                    }
                } catch (Exception e) {
                    SiliconLog.info("sat-launch: handler error: " + e);
                    // 异常路径也必须回包;再兜一层防止回包本身抛异常
                    try {
                        Call.clientPacketReliable(p.con, "sat-result", "fail");
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        // 多人暂停的服务端包处理器：必须注册在 init()——dedicated 服务器只触发 ServerLoadEvent、
        // 不触发 ClientLoadEvent，原先注册在 ClientLoadEvent 里时这四个处理器在专属服务器上
        // 永远不会生效（与 sat-launch 同因，故移到同一位置）
        if (netServer != null) {
            netServer.addPacketHandler("pause", (p, time) -> {
                if (p.admin || p.name.equals(state.map.author())) {
                    state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
                    Call.clientPacketReliable(p.con, "paused", time);
                    SiliconLog.info(p.name + " pause");
                    return;
                }

                if (Vars.pauseMode == 0) return;

                if (Vars.pauseMode == 1) {
                    state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
                    Call.clientPacketReliable(p.con, "paused", time);
                    SiliconLog.info(p.name + " pause");
                    return;
                }

                if (Vars.pauseMode == 2 && Vars.pauseWhitelist.contains(p.name)) {
                    state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
                    Call.clientPacketReliable(p.con, "paused", time);
                    SiliconLog.info(p.name + " pause");
                }
            });

            netServer.addPacketHandler("pause-setmode", (p, data) -> {
                if (!p.admin && !p.name.equals(state.map.author())) return;
                try {
                    Vars.pauseMode = Integer.parseInt(data.trim());
                    if (Vars.pauseMode < 0 || Vars.pauseMode > 2) Vars.pauseMode = 0;
                } catch (NumberFormatException ignored) {}
            });

            netServer.addPacketHandler("pause-grant", (p, data) -> {
                if (!p.admin && !p.name.equals(state.map.author())) return;
                String target = data.trim();
                if (target.isEmpty()) return;
                if (!Vars.pauseWhitelist.contains(target)) {
                    Vars.pauseWhitelist.add(target);
                }
            });

            netServer.addPacketHandler("pause-revoke", (p, data) -> {
                if (!p.admin && !p.name.equals(state.map.author())) return;
                String target = data.trim();
                Vars.pauseWhitelist.remove(target);
            });
        }

        // 主界面自动检查 GitHub 更新（可在设置中关闭；有更新才显示横幅，初始隐藏）
        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (Core.settings.getBool("updatecheck.autoCheck", true)) {
                UpdateChecker.check();
            }
            UpdateChecker.setupBanner();
        });

        Events.on(EventType.ClientLoadEvent.class, e -> {
            ui.settings.addCategory("@settings.silicon.meta.category.name",
                    new TextureRegionDrawable(new TextureRegion(Silicon.MOD.iconTexture)), st -> {
                // —— 方块搜索设置 ——
                st.checkPref("blocksearch.showHistory", true);
                st.checkPref("blocksearch.clearOnSelect", true);
                // 灰色细线：搜索设置与暂停设置分隔（注册为设置项，rebuild 时保留）
                st.pref(new CustomSetting(t -> t.image(Tex.whiteui).growX().height(2f).color(Pal.gray).padTop(8f).padBottom(8f)));
                // —— 暂停设置 ——
                st.sliderPref("pauseMode", 0, 0, 2, 1,
                        i -> Core.bundle.get("setting.pauseMode.value." + i, String.valueOf(i)),
                        i -> {
                            Vars.pauseMode = i;
                            if (net.client()) Call.serverPacketReliable("pause-setmode", String.valueOf(i));
                        });
                st.checkPref("pauseRequest", true);
                st.pref(new CustomSetting(t -> t.button(Core.bundle.get("setting.pauseWhitelist.name"), Styles.defaultt, Silicon::showWhitelistDialog).width(200f).padTop(6f)));
                // 灰色细线：更新区与上方设置分隔（注册为设置项，rebuild 时保留）
                st.pref(new CustomSetting(t -> t.image(Tex.whiteui).growX().height(2f).color(Pal.gray).padTop(8f).padBottom(8f)));
                // —— 更新设置 ——
                st.checkPref("updatecheck.autoCheck", true);
                st.pref(new CustomSetting(t -> t.button(Core.bundle.get("setting.checkUpdate.name"), Styles.defaultt, () -> UpdateChecker.check(true)).width(200f).padTop(6f)));
                // 灰色细线：更新区与信号/中枢显示设置分隔（注册为设置项，rebuild 时保留）
                st.pref(new CustomSetting(t -> t.image(Tex.whiteui).growX().height(2f).color(Pal.gray).padTop(8f).padBottom(8f)));
                // —— 信号显示设置 ——
                st.checkPref("signal.hkey.toggle", true);
                // 数字模式 / 范围模式透明度（0~100%）
                st.sliderPref("signal.digitAlpha", 80, 0, 100, 5,
                        i -> Core.bundle.format("setting.signal.digitAlpha.value", i));
                st.sliderPref("signal.rangeAlpha", 45, 0, 100, 5,
                        i -> Core.bundle.format("setting.signal.rangeAlpha.value", i));
                // —— 中枢物流调试与连线 ——
                st.checkPref("hubDebugLog", false, v -> silicon.world.blocks.distribution.ItemTransferHub.debugFlows = v);
                st.sliderPref("hubLinkOpacity", 100, 0, 100, 5, i -> i + "%");
                // 灰色细线：与「恢复默认设置」分隔（注册为设置项，rebuild 时保留）
                st.pref(new CustomSetting(t -> t.image(Tex.whiteui).growX().height(2f).color(Pal.gray).padTop(8f).padBottom(8f)));

                SiliconLog.info("Loading settings.");
            });
        });

        Events.on(EventType.ClientLoadEvent.class, e -> {
            // 启动时从持久化设置恢复调试开关（checkPref 的变更回调只在用户手动切换时触发，
            // 不初始化的话每次启动都要重新关闭再打开才生效）
            silicon.world.blocks.distribution.ItemTransferHub.debugFlows = Core.settings.getBool("hubDebugLog", false);

            // 卫星状态广播（服务器 → 客机）：应用主机权威状态（在轨数/归属信号/待发射数镜像）
            netClient.addPacketHandler("sat-state", SatelliteManager::applyState);
            // 发射失败反馈（服务器 → 请求者）
            netClient.addPacketHandler("sat-result", (s) -> {
                if (s.equals("disabled")) {
                    ui.showInfoToast(Core.bundle.get("block.silicon-satellite-console.disabled"), 3f);
                    return;
                }
                if (s.equals("fail")) {
                    // 服务端通用失败（包格式/越权/控制台失效/处理异常等，细节只留在服务器日志）
                    ui.showInfoToast(Core.bundle.get("block.silicon-satellite-console.fail"), 3f);
                    return;
                }
                try {
                    int result = Integer.parseInt(s.trim());
                    if (result == SatelliteManager.LAUNCH_OK) return;
                    String key;
                    switch (result) {
                        case SatelliteManager.LAUNCH_NO_READY: key = "block.silicon-satellite-console.noready"; break;
                        case SatelliteManager.LAUNCH_NO_FUEL: key = "block.silicon-satellite-console.nofuel"; break;
                        case SatelliteManager.LAUNCH_NO_POWER: key = "block.silicon-satellite-console.nopower"; break;
                        case SatelliteManager.LAUNCH_ORBIT_FORBIDDEN: key = "block.silicon-satellite-console.orbitForbidden"; break;
                        case SatelliteManager.LAUNCH_NO_HUB: key = "block.silicon-satellite-console.nohub"; break;
                        case SatelliteManager.LAUNCH_MULTI_HUB: key = "block.silicon-satellite-console.multihub"; break;
                        case SatelliteManager.LAUNCH_MULTI_CONSOLE: key = "block.silicon-satellite-console.multiconsole"; break;
                        case SatelliteManager.LAUNCH_TEST_SANDBOX: key = "block.silicon-satellite-console.sandboxOnly"; break;
                        default: key = "block.silicon-satellite-console.fail"; break;
                    }
                    ui.showInfoToast(Core.bundle.get(key), 3f);
                } catch (NumberFormatException ignored) {
                }
            });
        });

        Events.run(EventType.Trigger.update, () -> {
            if (!state.isGame()) return;
            // 卫星状态周期广播（控制台卫星名称/制造中字段保鲜，约 0.5s；服务器端）
            if (net.server() && ++satelliteBroadcastTick >= 30) {
                satelliteBroadcastTick = 0;
                SatelliteManager.periodicBroadcastAll();
            }
            if (net.client() && Core.settings.getBool("pauseRequest", true)) {
                if (Core.input.keyTap(Binding.pause)) {
                    String time = String.valueOf((long) Time.time);
                    Call.serverPacketReliable("pause", time);
                    Vars.pause = new Vars.Pause(time);
                } else if (!Vars.pause.complete && Time.time - Float.parseFloat(Vars.pause.time) > 60f) {
                    String time = String.valueOf((long) Time.time);
                    Call.serverPacketReliable("pause", time);
                    Vars.pause = new Vars.Pause(time);
                }
            }
        });

        Events.on(EventType.PlayerChatEvent.class, e -> {
            String msg = e.message;
            if (msg == null || !msg.startsWith("!pause")) return;
            handlePauseCommand(e.player, msg);
        });
    }

    public static void showWhitelistDialog() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("hubWhitelist.title"));
        dialog.cont.top();

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            dialog.cont.clearChildren();
            dialog.cont.top();

            if (Vars.pauseWhitelist.isEmpty()) {
                dialog.cont.add(Core.bundle.get("hubWhitelist.empty")).color(Color.lightGray).pad(16f);
            } else {
                for (int i = 0; i < Vars.pauseWhitelist.size; i++) {
                    String name = Vars.pauseWhitelist.get(i);
                    dialog.cont.row();
                    dialog.cont.table(t -> {
                        t.add(name).growX().left();
                        t.button(Core.bundle.get("hubWhitelist.remove"), Styles.flatBordert, () -> {
                            Vars.pauseWhitelist.remove(name);
                            if (net.client()) Call.serverPacketReliable("pause-revoke", name);
                            rebuild[0].run();
                        }).padLeft(8f);
                    }).fillX().pad(4f).padLeft(8f).padRight(8f);
                }
            }

            dialog.cont.row();
            dialog.cont.table(t -> {
                TextField field = t.field("", text -> {}).growX().pad(8f).get();
                field.setMessageText(Core.bundle.get("hubWhitelist.placeholder"));
                t.button(Core.bundle.get("hubWhitelist.add"), Styles.flatBordert, () -> {
                    String input = field.getText().trim();
                    if (!input.isEmpty() && !Vars.pauseWhitelist.contains(input)) {
                        Vars.pauseWhitelist.add(input);
                        if (net.client()) Call.serverPacketReliable("pause-grant", input);
                        field.clearText();
                        rebuild[0].run();
                    }
                }).padLeft(8f);
            }).fillX().pad(8f);
        };

        rebuild[0].run();
        dialog.closeOnBack();
        dialog.show();
    }

    private void handlePauseCommand(Player p, String msg) {
        String[] parts = msg.split(" ");
        if (parts.length < 2) return;

        boolean isHost = p.admin || p.name.equals(state.map.author());

        switch (parts[1]) {
            case "on":
                if (!isHost) return;
                Vars.pauseMode = 1;
                Call.infoMessage(p.con, "[accent]Pause mode: Admins only");
                break;
            case "off":
                if (!isHost) return;
                Vars.pauseMode = 0;
                Call.infoMessage(p.con, "[accent]Pause mode: Off");
                break;
            case "custom":
                if (!isHost) return;
                Vars.pauseMode = 2;
                Call.infoMessage(p.con, "[accent]Pause mode: Custom whitelist");
                break;
            case "grant":
                if (!isHost || parts.length < 3) return;
                String grantTarget = parts[2];
                if (!Vars.pauseWhitelist.contains(grantTarget)) {
                    Vars.pauseWhitelist.add(grantTarget);
                }
                Call.infoMessage(p.con, "[accent]Granted pause to: " + grantTarget);
                break;
            case "revoke":
                if (!isHost || parts.length < 3) return;
                String revokeTarget = parts[2];
                Vars.pauseWhitelist.remove(revokeTarget);
                Call.infoMessage(p.con, "[accent]Revoked pause from: " + revokeTarget);
                break;
            case "list":
                if (!isHost) return;
                String list = Vars.pauseWhitelist.isEmpty() ? "(empty)" : Vars.pauseWhitelist.toString(", ");
                Call.infoMessage(p.con, "[accent]Whitelist: " + list);
                break;
        }
    }
}