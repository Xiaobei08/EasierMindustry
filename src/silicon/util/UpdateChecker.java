package silicon.util;

import arc.Core;
import arc.Events;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Http;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.Vars;

import java.io.File;

import static mindustry.Vars.ui;

/**
 * 自动检查 GitHub 更新并支持游戏内下载安装。
 * <p>
 * 启动后异步请求 GitHub releases 列表，解析最新 tag 与 jar 资产下载地址；
 * 对比当前模组版本（mod.hjson version），有更新时主界面显示更新弹窗。
 * 下载后写入游戏 mods 目录（替换旧 Silicon jar），提示玩家重启游戏。
 */
public class UpdateChecker {
    /** 检查的 GitHub 仓库 */
    public static final String REPO = "Xiaobei08/Silicon";
    // 用 /releases 列表而非 /releases/latest：latest 端点不返回 prerelease（本仓库 release 均为 prerelease 时返回 404）
    public static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases";
    public static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";

    /** 是否已检查（启动后只检查一次）。HTTP 线程写/UI 线程读，需 volatile 保证可见性 */
    public static volatile boolean checked = false;
    /** 是否存在更新 */
    public static volatile boolean hasUpdate = false;
    /** 最新版本号 tag（如 va0.10.0.0，下载 URL 用） */
    public static volatile String latestVersion = "";
    /** 最新 jar 下载地址 */
    public static volatile String downloadUrl = "";
    /** 下载状态（均在 HTTP 线程写、UI 线程读） */
    public static volatile boolean downloading = false;
    public static volatile boolean downloadDone = false;
    public static volatile boolean downloadFailed = false;
    /** 上次检查时间（毫秒）：限制手动检查频率，避免触发 GitHub API 限流 */
    private static long lastCheckTime = 0;

    /** 启动后检查一次（异步；失败静默，保持隐藏） */
    public static void check() {
        check(false);
    }

    /** 检查更新；force=true 时强制重新检查（用于设置页手动检查按钮） */
    public static void check(boolean force) {
        if (checked && !force) return;
        if (force && System.currentTimeMillis() - lastCheckTime < 30_000) {
            // 节流：30 秒内不重复手动检查（启动自动检查不占用手动额度）
            showInfoPopup(Core.bundle.get("updatecheck.tooFrequent"));
            return;
        }
        if (force) lastCheckTime = System.currentTimeMillis();
        checked = true;
        hasUpdate = false;
        latestVersion = "";
        downloadUrl = "";
        if (force) {
            // 手动检查：重置下载与弹窗状态，允许再次弹出提示
            downloading = false;
            downloadDone = false;
            downloadFailed = false;
            dialogShown = false;
        }
        SiliconLog.info("Checking for updates...");
        Http.get(API_URL, res -> {
            String body = res.getResultAsString();
            String tag = extractTag(body);
            String current = currentVersion();
            if (tag == null) {
                // 响应异常（限流/格式变化）：手动检查时提示失败
                if (force) showInfoPopup(Core.bundle.get("updatecheck.failed"));
                return;
            }
            if (isNewer(tag, current)) {
                latestVersion = tag;
                downloadUrl = findAssetUrl(body, tag);
                hasUpdate = true;
                if (force) {
                    // 手动检查：立即显示弹窗（不限于主界面）
                    Core.app.post(UpdateChecker::showUpdateDialog);
                } else {
                    // 启动检查：若当前已在主界面，显示弹窗
                    Core.app.post(UpdateChecker::refreshBanner);
                }
            } else if (force) {
                // 手动检查且已是最新：居中弹窗提示
                showInfoPopup(Core.bundle.get("updatecheck.none"));
            }
        }, err -> {
            SiliconLog.info("Update check failed: " + err);
            if (force) {
                // GitHub API 限流（403）与普通网络失败区分提示
                if (err instanceof Http.HttpStatusException
                        && ((Http.HttpStatusException)err).status != null
                        && ((Http.HttpStatusException)err).status.code == 403) {
                    showInfoPopup(Core.bundle.get("updatecheck.rateLimited"));
                } else {
                    showInfoPopup(Core.bundle.get("updatecheck.failed"));
                }
            }
        });
    }

    /** 当前模组版本（mod.hjson） */
    public static String currentVersion() {
        if (Vars.mods == null) return "";
        var mod = Vars.mods.locateMod("silicon");
        return mod != null && mod.meta != null && mod.meta.version != null ? mod.meta.version : "";
    }

    /** 显示用最新版本号：去掉 tag 的 v 前缀（如 va0.10.0.0 → a0.10.0.0） */
    public static String latestDisplayVersion() {
        return latestVersion != null && latestVersion.startsWith("v") ? latestVersion.substring(1) : latestVersion;
    }

    /** 简易解析 JSON 中的 tag_name */
    static String extractTag(String body) {
        int i = body.indexOf("\"tag_name\"");
        if (i < 0) return null;
        int s = body.indexOf('"', i + 10);
        int e = body.indexOf('"', s + 1);
        return s >= 0 && e > s ? body.substring(s + 1, e) : null;
    }

    /** 在 release JSON 中找第一个 .jar 资产地址 */
    static String findAssetUrl(String body, String tag) {
        int i = body.indexOf("browser_download_url");
        while (i >= 0) {
            int s = body.indexOf('"', i + 22);
            int e = body.indexOf('"', s + 1);
            if (s >= 0 && e > s) {
                String url = body.substring(s + 1, e);
                if (url.endsWith(".jar")) return url;
            }
            i = body.indexOf("browser_download_url", i + 1);
        }
        return DOWNLOAD_PREFIX + tag + "/Silicon.jar";
    }

    /** 版本比较（数字段逐段比）：latest 是否比 current 新 */
    public static boolean isNewer(String latest, String current) {
        if (current == null || current.isEmpty()) return true;
        int[] a = parse(latest), b = parse(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av > bv) return true;
            if (av < bv) return false;
        }
        return false;
    }

    /** 提取数字段（"va0.10.0.0" → [0,10,0,0]） */
    static int[] parse(String v) {
        String digits = v.replaceAll("[^0-9.]", "");
        String[] parts = digits.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** CDN 加速前缀（验证可用；下载按 CDN 优先、直连兜底：gh-proxy.com → ghfast.top → 直连） */
    public static final String[] CDN_PREFIXES = {
        "https://gh-proxy.com/",
        "https://ghfast.top/",
    };

    /** 下载新 jar 并安装到 mods 目录（替换旧 Silicon jar）；CDN 优先，直连兜底 */
    public static void downloadAndInstall(Runnable onDone, Runnable onError) {
        if (downloading || downloadUrl.isEmpty()) return;
        downloading = true;
        downloadFrom(0, onDone, onError);
    }

    /** 依次尝试下载源：0..CDN_PREFIXES.length-1 为 CDN，最后一项为直连 */
    static void downloadFrom(int index, Runnable onDone, Runnable onError) {
        if (index > CDN_PREFIXES.length) {
            // 全部 CDN + 直连均失败
            downloading = false;
            downloadFailed = true;
            Core.app.post(onError);
            return;
        }
        String url = index < CDN_PREFIXES.length ? CDN_PREFIXES[index] + downloadUrl : downloadUrl;
        // 下载大 jar 需要长超时(默认仅 8 秒);失败回调挂 .error,成功回调走 .submit
        Http.get(url).timeout(60000).error(err -> {
            // 网络失败：继续尝试下一个源（CDN 加速）
            SiliconLog.info("Download failed, trying next source: " + url + " (" + err + ")");
            downloadFrom(index + 1, onDone, onError);
        }).submit(res -> {
            byte[] data = res.getResult();
            // Arc Http.getResult() 在下载体中途 IOException(超时/断连)时会吞异常并返回空数组:
            // HTTP 状态错误走 error 回调,但"截断的 200 响应"会以成功身份进来。
            // 必须先完整校验内容,再动 mods 目录——旧实现先删旧 jar、0 字节也判"成功",
            // 结果是留下一个 0 字节 Silicon.jar 还提示"更新已安装"。
            boolean valid = data != null && data.length > 4
                    // jar 即 zip:魔数 "PK"
                    && data[0] == 'P' && data[1] == 'K'
                    // 与 Content-Length 比对(未知时为负数,跳过该项)
                    && (res.getContentLength() <= 0 || res.getContentLength() == data.length);
            if (!valid) {
                SiliconLog.info("Update download invalid (truncated/empty response): " + url);
                downloading = false;
                downloadFailed = true;
                Core.app.post(onError);
                return;
            }
            boolean ok = false;
            try {
                var dir = Vars.modDirectory;
                // ① 先落临时文件并校验字节数—— mods 目录此刻仍未被破坏
                var tmp = dir.child("Silicon.jar.part");
                tmp.writeBytes(data, false);
                if (tmp.length() != data.length) throw new Exception("temp size mismatch");
                // ② 只删除本模组的 jar：精确名 Silicon.jar，或 release 资产命名（Silicon-<版本>.jar），
                // 避免误删其他文件名含 silicon 的模组
                for (var f : dir.list()) {
                    String n = f.name().toLowerCase();
                    if (f.extEquals("jar") && (n.equals("silicon.jar") || n.startsWith("silicon-"))) {
                        f.delete();
                    }
                }
                // ③ 同目录 rename 落位（近乎原子;失败则回写直写并再校验）
                var target = dir.child("Silicon.jar");
                tmp.moveTo(target);
                ok = target.length() == data.length;
            } catch (Exception e) {
                SiliconLog.info("Update install failed: " + e);
            }
            if (ok) {
                downloading = false;
                downloadDone = true;
                // 回调最终会碰 scene2d(ui.showInfoToast),必须在渲染线程执行
                Core.app.post(onDone);
            } else {
                // 落盘失败（磁盘问题）：重试其他源无意义，直接失败
                downloading = false;
                downloadFailed = true;
                Core.app.post(onError);
            }
        });
    }

    /** 更新弹窗是否已显示（本次会话只弹一次） */
    public static boolean dialogShown = false;
    /** 弹窗是否被玩家手动关闭（手动关闭后不再自动重挂） */
    public static boolean dialogDismissed = false;
    /** 自定义浮动窗口（更新弹窗，只维持一个） */
    public static Table popupTable;
    /** 信息弹窗（如"已是最新版本"，只维持一个，保持在最上层） */
    public static Table infoPopup;

    public static void setupBanner() {
        // 状态变化（含进入主界面/进入游戏）时检查弹窗
        Events.on(EventType.StateChangeEvent.class, e -> refreshBanner());
    }

    /** 有更新且在主界面时弹出更新提示（自定义浮动窗口，只弹一次）；进入游戏场景切换清除弹窗时自动重新挂载（未手动关闭） */
    public static void refreshBanner() {
        // 弹窗被场景切换清除（parent 为空）但玩家未手动关闭 → 重新挂载到当前场景
        if (hasUpdate && popupTable != null && popupTable.parent == null && !dialogDismissed) {
            Core.scene.root.addChild(popupTable);
            return;
        }
        if (hasUpdate && Vars.state.isMenu() && !dialogShown) {
            showUpdateDialog();
        }
    }

    public static void showUpdateDialog() {
        if (dialogShown) return;
        dialogShown = true;

        // 只维持一个：先关闭旧弹窗（force 重复检查时避免堆叠）
        if (popupTable != null) {
            popupTable.remove();
            popupTable = null;
        }

        Table popup = new Table();
        // 用 pane 而非 pane2：pane2 九宫格贴图的上边框为近黑色，在深色背景上不可见
        popup.setBackground(Tex.pane);
        popup.margin(12f);

        // 标题行（可拖动区域）
        popup.table(tt -> {
            tt.add(Core.bundle.get("updatecheck.title")).color(Pal.accent).padRight(24f);
            addDrag(tt, popup);
        }).padBottom(8f).row();

        // 内容（版本号去掉 v 前缀显示）
        popup.add(Core.bundle.format("updatecheck.found", latestDisplayVersion(), currentVersion())).padBottom(12f).row();

        // 按钮行：[下载并安装] [关闭]
        TextButton dl = new TextButton(Core.bundle.get("updatecheck.download"), Styles.defaultt);
        dl.update(() -> {
            if (downloading) dl.setText(Core.bundle.get("updatecheck.downloading"));
            else if (downloadDone) dl.setText(Core.bundle.get("updatecheck.done"));
            else if (downloadFailed) dl.setText(Core.bundle.get("updatecheck.failed"));
            else dl.setText(Core.bundle.get("updatecheck.download"));
        });
        dl.clicked(() -> {
            if (downloading) return;
            if (downloadDone) {
                // 下载完成：点击自动重启游戏
                restartGame();
            } else {
                downloadAndInstall(
                    () -> ui.showInfoToast(Core.bundle.get("updatecheck.doneToast"), 5f),
                    () -> ui.showInfoToast(Core.bundle.get("updatecheck.failed"), 5f));
            }
        });
        popup.table(bt -> {
            bt.add(dl).size(170f, 44f).pad(5f);
            bt.button(Core.bundle.get("updatecheck.close"), UpdateChecker::hidePopup).size(170f, 44f).pad(5f);
        });

        // 保持在最上层：每次渲染前若不在 root 最上层则移到末尾
        popup.update(() -> {
            if (Core.scene.root.getChildren().peek() != popup) {
                Core.scene.root.addChild(popup);
            }
        });
        popup.pack();
        // 底部中央定位
        popup.setPosition((Core.graphics.getWidth() - popup.getWidth()) / 2f, 80f);
        popupTable = popup;
        Core.scene.root.addChild(popup);
    }

    /** 给元素挂拖动监听（拖动时移动窗口） */
    static void addDrag(Element target, Table popup) {
        target.addListener(new InputListener() {
            float lastX, lastY;

            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                lastX = e.stageX;
                lastY = e.stageY;
                return true;
            }

            @Override
            public void touchDragged(InputEvent e, float x, float y, int pointer) {
                popup.moveBy(e.stageX - lastX, e.stageY - lastY);
                lastX = e.stageX;
                lastY = e.stageY;
            }
        });
    }

    /** 关闭弹窗（手动关闭标记：不再自动重挂） */
    public static void hidePopup() {
        dialogDismissed = true;
        if (popupTable != null) {
            popupTable.remove();
            popupTable = null;
        }
    }

    /** 居中显示一个信息弹窗（如"已是最新版本"/"检查失败"），带关闭按钮；只维持一个并保持在最上层 */
    public static void showInfoPopup(String text) {
        Core.app.post(() -> {
            // 只维持一个：先关闭旧弹窗
            if (infoPopup != null) {
                infoPopup.remove();
                infoPopup = null;
            }

            Table popup = new Table();
            popup.setBackground(Tex.pane);
            popup.margin(14f);
            popup.add(text).pad(12f).row();
            popup.table(bt -> bt.button(Core.bundle.get("updatecheck.close"), () -> {
                popup.remove();
                if (infoPopup == popup) infoPopup = null;
            }).size(120f, 40f));
            // 保持在最上层：每次渲染前若不在 root 最上层则移到末尾
            popup.update(() -> {
                if (Core.scene.root.getChildren().peek() != popup) {
                    Core.scene.root.addChild(popup);
                }
            });
            popup.pack();
            popup.setPosition((Core.graphics.getWidth() - popup.getWidth()) / 2f, (Core.graphics.getHeight() - popup.getHeight()) / 2f);
            infoPopup = popup;
            Core.scene.root.addChild(popup);
        });
    }

    static void restartGame() {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            File jar = new File(mindustry.Vars.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            new Thread(() -> {
                try {
                    Thread.sleep(2000L); // 等当前进程退出后再启动
                    Runtime.getRuntime().exec(new String[]{javaBin, "-jar", jar.getAbsolutePath()});
                } catch (Exception ignored) {
                }
            }).start();
        } catch (Exception ignored) {
        }
        Core.app.exit();
    }
}