package silicon;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyBind;
import arc.input.KeyCode;
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
import silicon.content.block.Blocks;
import silicon.content.item.Items;
import silicon.util.MessageSync;
import silicon.util.MessageSystem;
import silicon.util.SiliconLog;
import silicon.util.SignalOverlay;
import silicon.util.UpdateChecker;
import silicon.world.blocks.distribution.ItemTransferHubNetwork;
import silicon.world.blocks.distribution.ItemTransferHub;
import silicon.world.blocks.power.PowerProtector;
import silicon.world.blocks.production.MineConverter;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalSource;
import silicon.ui.BlockSearch;
import silicon.ui.MessagePanel;

import static mindustry.Vars.*;


public class Silicon extends Mod {
    public static Mods.LoadedMod MOD;
    /** 常驻屏幕左边缘的消息面板实例（游戏中显示） */
    public static MessagePanel messagePanel;
    /** 消息面板开关按键绑定（默认 I，可在按键设置中重绑定） */
    public static KeyBind keyToggleMessagePanel;

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

    public Silicon() {
        Events.on(EventType.ClientLoadEvent.class, e -> {
            MOD = mods.getMod(Silicon.class);
            if (MOD != null) MOD.meta.subtitle = MOD.meta.version;
        });
    }

    @Override
    public void loadContent() {
        Items.load();
        // 液体必须先于方块加载：方块构造函数中引用了 silicon.world.Liquids.lubricant
        silicon.world.Liquids.load();
        Blocks.load();
        SiliconLog.info("Loading contents.");
    }

    @Override
    public void init() {
        // 注册消息面板开关按键（默认 I，可在按键设置中重绑定）
        keyToggleMessagePanel = KeyBind.add("silicon_toggle_panel", KeyCode.i, "silicon");

        // Reset hub network ID counter on world load to avoid ID collisions with saved hubs.
        // 信号源/中继器按队缓存也在世界加载时失效重建（读档后建筑重新加入 Groups.build）。
        // 电力保护器全局状态也需重置。
        Events.on(EventType.WorldLoadEvent.class, e -> {
            ItemTransferHubNetwork.resetIdCounter();
            SignalSource.markDirty();
            SignalRelay.markDirty();
            // PowerProtector 无全局静态状态，数据随存档保存，无需重置
        });

        BlockSearch.init();
        MineConverter.initNetworking();
        ItemTransferHub.initNetworking();
        SignalOverlay.init();
        // 消息系统多人联网同步（nop 当不在服务器上时，仅注册事件处理器）
        MessageSync.init();

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

                // —— 方块搜索 ——
                addSection(st, "setting.silicon.group.blocksearch");
                st.checkPref("blocksearch.showHistory", true);
                st.checkPref("blocksearch.clearOnSelect", true);

                // —— 消息面板 ——
                addSection(st, "setting.silicon.group.messagepanel");
                // 宽度（屏幕宽百分比，20%~50%）与最高位置（屏幕高百分比，20%~80%）
                st.sliderPref(MessagePanel.SET_WIDTH, (int) MessagePanel.DEFAULT_WIDTH_PERCENT,
                        (int) MessagePanel.MIN_WIDTH_PERCENT, (int) MessagePanel.MAX_WIDTH_PERCENT, 5,
                        i -> i + "%", i -> MessagePanel.applySettings());
                st.sliderPref(MessagePanel.SET_TOP, (int) MessagePanel.DEFAULT_TOP_PERCENT,
                        (int) MessagePanel.MIN_TOP_PERCENT, (int) MessagePanel.MAX_TOP_PERCENT, 5,
                        i -> i + "%", i -> MessagePanel.applySettings());
                st.sliderPref(MessageSystem.SET_MAX_MESSAGES, MessageSystem.DEFAULT_MAX_MESSAGES,
                        MessageSystem.MIN_MAX_MESSAGES, MessageSystem.MAX_MAX_MESSAGES, 5,
                        i -> i + "", i -> MessagePanel.applySettings());

                // —— 多人暂停 ——
                addSection(st, "setting.silicon.group.pause");
                st.sliderPref("pauseMode", 0, 0, 2, 1,
                        i -> Core.bundle.get("setting.pauseMode.value." + i, String.valueOf(i)),
                        i -> {
                            Vars.pauseMode = i;
                            if (net.client()) Call.serverPacketReliable("pause-setmode", String.valueOf(i));
                        });
                st.checkPref("pauseRequest", true);
                st.pref(new CustomSetting(t -> t.button(Core.bundle.get("setting.pauseWhitelist.name"), Styles.defaultt, Silicon::showWhitelistDialog).width(200f).padTop(6f)));

                // —— 信号显示 ——
                addSection(st, "setting.silicon.group.signal");
                st.checkPref("signal.hkey.toggle", true);
                // 数字模式 / 范围模式透明度（0~100%）
                st.sliderPref("signal.digitAlpha", 80, 0, 100, 5,
                        i -> Core.bundle.format("setting.signal.digitAlpha.value", i));
                st.sliderPref("signal.rangeAlpha", 45, 0, 100, 5,
                        i -> Core.bundle.format("setting.signal.rangeAlpha.value", i));

                // —— 物流中枢 ——
                addSection(st, "setting.silicon.group.hub");
                st.checkPref("hubDebugLog", false, v -> silicon.world.blocks.distribution.ItemTransferHub.debugFlows = v);
                st.sliderPref("hubLinkOpacity", 100, 0, 100, 5, i -> i + "%");

                // —— 界面 ——
                addSection(st, "setting.silicon.group.ui");
                st.checkPref("universal-junction.newUI", false);

                // —— 更新 ——
                addSection(st, "setting.silicon.group.update");
                st.checkPref("updatecheck.autoCheck", true);
                st.pref(new CustomSetting(t -> t.button(Core.bundle.get("setting.checkUpdate.name"), Styles.defaultt, () -> UpdateChecker.check(true)).width(200f).padTop(6f)));

                // 灰色细线：与「恢复默认设置」分隔（注册为设置项，rebuild 时保留）
                st.pref(new CustomSetting(t -> t.image(Tex.whiteui).growX().height(2f).color(Pal.gray).padTop(8f).padBottom(8f)));

                SiliconLog.info("Loading settings.");
            });
        });

        Events.on(EventType.ClientLoadEvent.class, e -> {
            // 初始化常驻左边缘的消息面板，加入 HUD 组
            messagePanel = new MessagePanel();
            MessagePanel.setInstance(messagePanel);
            ui.hudGroup.addChild(messagePanel);
        });

        Events.on(EventType.ClientLoadEvent.class, e -> {
            // 启动时从持久化设置恢复调试开关（checkPref 的变更回调只在用户手动切换时触发，
            // 不初始化的话每次启动都要重新关闭再打开才生效）
            silicon.world.blocks.distribution.ItemTransferHub.debugFlows = Core.settings.getBool("hubDebugLog", false);
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

            netClient.addPacketHandler("paused", (s) -> {
                Vars.pause.complete = true;
            });
        });

        Events.run(EventType.Trigger.update, () -> {
            if (!state.isGame()) return;
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

        // —— 消息面板开关按键 ——
        // 默认 I 键切换面板展开/收起（可在按键设置中重绑定，仅游戏内生效）
        Events.run(EventType.Trigger.update, () -> {
            if (!state.isGame()) return;
            if (messagePanel != null && keyToggleMessagePanel != null
                && Core.input.keyTap(keyToggleMessagePanel)) {
                messagePanel.toggle();
            }
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

    /**
     * 在设置表中插入一个「分类标题」：上方灰色分隔横线 + 强调色分类名（左对齐）。
     * 注册为设置项，rebuild（恢复默认/切换分类）时自动保留。
     */
    private static void addSection(SettingsMenuDialog.SettingsTable st, String labelKey) {
        st.pref(new CustomSetting(t -> {
            t.image(Tex.whiteui).growX().height(2f).color(Pal.gray).padTop(8f).padBottom(2f);
            t.row();
            t.add(Core.bundle.get(labelKey)).color(Pal.accent).fontScale(1.1f).padTop(2f).padBottom(4f).left();
        }));
    }
}