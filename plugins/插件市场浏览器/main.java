import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

// ====================== 常量 ======================

String DEFAULT_API   = "https://raw.githubusercontent.com/HdShare/WAuxiliary_Plugin/main/docs/index.json";
String DEFAULT_CACHE = pluginDir + "/plugin_repo_cache.json";
String CONFIG_FILE   = pluginDir + "/app_config.json";

// ====================== 内存状态 ======================

JSONArray pluginArray;

HashMap globalStore = new HashMap();

AlertDialog getMasterDialog() {
    return (AlertDialog) globalStore.get("masterDialog");
}
void setMasterDialog(AlertDialog d) {
    globalStore.put("masterDialog", d);
}
FrameLayout getMasterContent() {
    return (FrameLayout) globalStore.get("masterContent");
}
void setMasterContent(FrameLayout f) {
    globalStore.put("masterContent", f);
}

String currentPage = "list";

LinearLayout gListView = null;
LinearLayout gListRoot = null;
TextView gHeaderView = null;
LinearLayout gTabRow = null;
HorizontalScrollView gTabScroll = null;
EditText gSearchBox = null;
Activity gListCtx = null;

String currentKeyword = "";

int RENDER_BATCH_SIZE = 25;
long renderSeq = 0;

int STATUS_NOT_INSTALLED = 0;
int STATUS_UP_TO_DATE    = 1;
int STATUS_UPGRADABLE    = 2;
int STATUS_UNKNOWN_VER   = 3;

String activeRepoId = "default";
List proxyList = new ArrayList();
int activeProxyIndex = 0;

List repoNames = new ArrayList();
List repoUrls  = new ArrayList();
List repoLocal = new ArrayList();

HashMap installedExact = new HashMap();
HashMap installedWeak  = new HashMap();

// 本地已安装插件列表，每项为 HashMap（name/author/version/updateTime/dirPath）
List installedList = new ArrayList();

boolean installedCacheDirty = true;

boolean repoFetching    = false;
String  fetchingRepoId  = "";
long    repoFetchSeq    = 0;

Activity pendingRepoUiCtx    = null;
boolean  pendingRepoUiUpdate = false;

// ====================== 生命周期 ======================

void onLoad() {
    loadRepoConfig();
    refreshRepoSilent(null, "default", false, null);
    initMenuHook();
}

void onUnload() {
    try { new File(DEFAULT_CACHE).delete(); } catch (Throwable e) {}
    if (hookD     != null) unhook(hookD);
    if (hookClick != null) unhook(hookClick);
}

void openSettings() {
    try {
        final Activity ctx = getTopActivity();
        if (ctx == null) { toast("无法获取Activity"); return; }
        if (getMasterDialog() == null) createMasterDialog(ctx);
        boolean cacheLoaded = loadLocalCache(activeRepoId);
        if (cacheLoaded) {
            showPluginList(ctx);
            new Thread(new Runnable() {
                @Override public void run() {
                    installedCacheDirty = true;
                    ensureInstalledCache();
                    refreshListUI();
                }
            }).start();
            refreshRepoSilent(ctx, activeRepoId, true, null);
        } else {
            toast("正在后台拉取插件仓库");
            refreshRepoSilent(ctx, activeRepoId, true, null);
        }
    } catch (Throwable e) {
        log("openSettings异常=" + e);
        toast(e.toString());
    }
}

// ================== Hook 扩展变量 ==================
var hookD = null;
var hookClick = null;
var fField_ig_s = null;
var fField_fg_b = null;
var fField_gg_c = null;
var ggCtor5 = null;
var fgCtor  = null;

final int    CUSTOM_TYPE  = 9999;
final String CUSTOM_TITLE = "插件市场";
final int    CUSTOM_ICON  = 2131692088;

void initMenuHook() {
    try {
        var igClass = com.tencent.mm.ui.ig.class;
        var ggClass = com.tencent.mm.ui.gg.class;
        var fgClass = com.tencent.mm.ui.fg.class;

        fField_ig_s = igClass.getDeclaredField("s"); fField_ig_s.setAccessible(true);
        fField_fg_b = fgClass.getDeclaredField("b"); fField_fg_b.setAccessible(true);
        fField_gg_c = ggClass.getDeclaredField("c"); fField_gg_c.setAccessible(true);

        ggCtor5 = ggClass.getDeclaredConstructor(int.class, String.class, String.class, int.class, int.class);
        ggCtor5.setAccessible(true);
        fgCtor = fgClass.getDeclaredConstructor(ggClass);
        fgCtor.setAccessible(true);

        hookD = hookAfter(igClass.getDeclaredMethod("d"), param -> {
            var sparseArray = (android.util.SparseArray) fField_ig_s.get(param.thisObject);
            if (sparseArray == null) return;
            for (int i = 0; i < sparseArray.size(); i++) {
                var fg = sparseArray.valueAt(i);
                if (fg != null && fField_gg_c.get(fField_fg_b.get(fg)) == CUSTOM_TYPE) return;
            }
            var newGG = ggCtor5.newInstance(CUSTOM_TYPE, CUSTOM_TITLE, "", CUSTOM_ICON, 0);
            sparseArray.put(sparseArray.size(), fgCtor.newInstance(newGG));
        });

        hookClick = hookBefore(igClass.getDeclaredMethod("onItemClick",
            android.widget.AdapterView.class, android.view.View.class, int.class, long.class), param -> {
            int position = (int) param.args[2];
            var sparseArr = (android.util.SparseArray) fField_ig_s.get(param.thisObject);
            var fg = sparseArr.get(position);
            if (fg != null && fField_gg_c.get(fField_fg_b.get(fg)) == CUSTOM_TYPE) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        try {
                            final Activity ctx = getTopActivity();
                            if (ctx == null) return;
                            if (getMasterDialog() == null) createMasterDialog(ctx);
                            boolean cacheLoaded = loadLocalCache(activeRepoId);
                            if (cacheLoaded) {
                                showPluginList(ctx);
                                new Thread(new Runnable() {
                                    @Override public void run() {
                                        installedCacheDirty = true;
                                        ensureInstalledCache();
                                        refreshListUI();
                                    }
                                }).start();
                                refreshRepoSilent(ctx, activeRepoId, true, null);
                            } else {
                                toast("正在后台拉取插件仓库");
                                refreshRepoSilent(ctx, activeRepoId, true, null);
                            }
                        } catch (Throwable e) {
                            log("菜单打开异常: " + e.toString());
                            toast(e.toString());
                        }
                    }
                });
            }
        });
    } catch (Throwable e) {
        log("Hook 菜单初始化失败: " + e.getMessage());
    }
}

// ====================== JSON 配置读写 ======================

JSONObject loadAppConfig() {
    try {
        File f = new File(CONFIG_FILE.toString());
        if (!f.exists()) return new JSONObject();
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int len;
        while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
        fis.close();
        return new JSONObject(bos.toString("UTF-8"));
    } catch (Throwable e) { return new JSONObject(); }
}

void saveAppConfig(JSONObject cfg) {
    try {
        FileOutputStream fos = new FileOutputStream(new File(CONFIG_FILE.toString()));
        fos.write(cfg.toString(2).getBytes("UTF-8"));
        fos.flush(); fos.close();
    } catch (Throwable e) { log("saveAppConfig异常=" + e.getMessage()); }
}

JSONObject buildConfigJson() {
    try {
        JSONObject cfg = new JSONObject();
        JSONArray proxiesArr = new JSONArray();
        for (int i = 0; i < proxyList.size(); i++) proxiesArr.put(proxyList.get(i).toString());
        cfg.put("proxies", proxiesArr);
        cfg.put("activeProxy", activeProxyIndex);
        JSONArray reposArr = new JSONArray();
        for (int i = 0; i < repoNames.size(); i++) {
            JSONObject repo = new JSONObject();
            repo.put("name",  repoNames.get(i).toString());
            repo.put("url",   repoUrls.get(i).toString());
            repo.put("local", ((Boolean) repoLocal.get(i)).booleanValue());
            reposArr.put(repo);
        }
        cfg.put("repos", reposArr);
        cfg.put("activeRepo", activeRepoId);
        return cfg;
    } catch (Throwable e) { return new JSONObject(); }
}

void saveConfig() { saveAppConfig(buildConfigJson()); }

void loadRepoConfig() {
    JSONObject cfg = loadAppConfig();
    proxyList.clear();
    try {
        JSONArray arr = cfg.getJSONArray("proxies");
        for (int i = 0; i < arr.length(); i++) proxyList.add(arr.getString(i));
    } catch (Throwable ignore) {}
    if (proxyList.isEmpty()) {
        proxyList.add("https://proxy.vvvv.ee/");
        proxyList.add("https://gh.idayer.com/");
        saveConfig();
    }
    try { activeProxyIndex = cfg.getInt("activeProxy"); } catch (Throwable ignore) {}
    if (activeProxyIndex < 0 || activeProxyIndex >= proxyList.size()) activeProxyIndex = 0;
    repoNames.clear(); repoUrls.clear(); repoLocal.clear();
    try {
        JSONArray arr = cfg.getJSONArray("repos");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.getJSONObject(i);
            String n = r.optString("name", ""), u = r.optString("url", "");
            if (!n.isEmpty() && !u.isEmpty()) {
                repoNames.add(n); repoUrls.add(u);
                repoLocal.add(Boolean.valueOf(r.optBoolean("local", false)));
            }
        }
    } catch (Throwable ignore) {}
    try { activeRepoId = cfg.getString("activeRepo"); } catch (Throwable ignore) {}
    // "local" 是合法保留值，不做 index 校验
    if (!"default".equals(activeRepoId) && !"local".equals(activeRepoId)) {
        try {
            int idx = Integer.parseInt(activeRepoId);
            if (idx < 0 || idx >= repoNames.size()) activeRepoId = "default";
        } catch (Throwable e) { activeRepoId = "default"; }
    }
}

// ====================== 代理 / 仓库 URL ======================

String getEffectiveProxy() {
    if (proxyList.isEmpty()) return "";
    int idx = activeProxyIndex;
    if (idx < 0 || idx >= proxyList.size()) idx = 0;
    return proxyList.get(idx).toString();
}

boolean needsProxy(String url) {
    return url != null && (url.contains("github.com")
        || url.contains("raw.githubusercontent.com")
        || url.contains("api.github.com"));
}

String getRepoRawUrl(String repoId) {
    if ("default".equals(repoId)) return DEFAULT_API;
    try {
        int idx = Integer.parseInt(repoId);
        if (idx >= 0 && idx < repoUrls.size()) return repoUrls.get(idx).toString();
    } catch (Throwable ignore) {}
    return DEFAULT_API;
}

String getCacheFilePath(String repoId) {
    if ("default".equals(repoId)) return DEFAULT_CACHE;
    try {
        int idx = Integer.parseInt(repoId);
        if (idx >= 0 && idx < repoUrls.size()) {
            int hash = Math.abs(repoUrls.get(idx).toString().hashCode());
            return pluginDir + "/repo_cache_" + hash + ".json";
        }
    } catch (Throwable ignore) {}
    return DEFAULT_CACHE;
}

boolean isLocalRepo(String repoId) {
    if ("default".equals(repoId)) return false;
    try {
        int idx = Integer.parseInt(repoId);
        if (idx >= 0 && idx < repoLocal.size())
            return ((Boolean) repoLocal.get(idx)).booleanValue();
    } catch (Throwable ignore) {}
    return false;
}

// ====================== 仓库管理 ======================

void addRepo(String name, String url) {
    boolean isLocal = url.startsWith("/") || url.startsWith("file://");
    repoNames.add(name); repoUrls.add(url);
    repoLocal.add(Boolean.valueOf(isLocal));
    saveConfig();
}

void deleteRepo(int index) {
    if (index < 0 || index >= repoNames.size()) return;
    try { new File(getCacheFilePath(String.valueOf(index))).delete(); } catch (Throwable ignore) {}
    repoNames.remove(index); repoUrls.remove(index); repoLocal.remove(index);
    if (!"default".equals(activeRepoId) && !"local".equals(activeRepoId)) {
        try {
            int activeIdx = Integer.parseInt(activeRepoId);
            if      (activeIdx == index)              activeRepoId = "default";
            else if (activeIdx > index)               activeRepoId = String.valueOf(activeIdx - 1);
            else if (activeIdx >= repoNames.size())   activeRepoId = "default";
        } catch (Throwable e) { activeRepoId = "default"; }
    }
    saveConfig();
}

// ====================== 扫描已安装 ======================

void ensureInstalledCache() {
    if (!installedCacheDirty) return;
    scanInstalledPlugins();
    installedCacheDirty = false;
}

void scanInstalledPlugins() {
    installedExact.clear();
    installedWeak.clear();
    installedList.clear();
    try {
        String pluginPath = pluginDir.toString();
        String parentPath = pluginPath.substring(0, pluginPath.lastIndexOf("/"));
        File parentDir = new File(parentPath);
        if (!parentDir.exists()) return;
        File[] dirs = parentDir.listFiles();
        if (dirs == null) return;
        int count = 0;
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (!dir.isDirectory()) continue;
            File infoProp = new File(dir.getAbsolutePath() + "/info.prop");
            if (!infoProp.exists()) continue;
            try {
                Properties p = parseInfoProp(dir.getAbsolutePath() + "/info.prop");
                if (p == null) continue;
                String name   = p.getProperty("name",   "").trim();
                String author = p.getProperty("author", "").trim();
                if (name.isEmpty()) continue;
                String nameLow = name.toLowerCase();
                if (!author.isEmpty())
                    installedExact.put(nameLow + "::" + author.toLowerCase(), p);
                installedWeak.put(nameLow + "::", p);
                // 保存完整条目供本地 tab 和移除功能使用
                HashMap entry = new HashMap();
                entry.put("name",       name);
                entry.put("author",     author);
                entry.put("version",    p.getProperty("version",    "").trim());
                entry.put("updateTime", p.getProperty("updateTime", "").trim());
                entry.put("dirPath",    dir.getAbsolutePath());
                installedList.add(entry);
                count++;
            } catch (Throwable ignore) {}
        }
        log("[scan] 已安装 " + count + " 个插件");
    } catch (Throwable e) { log("[scan] 异常=" + e.getMessage()); }
}

Properties parseInfoProp(String filePath) {
    try {
        Properties p = new Properties();
        FileInputStream fis = new FileInputStream(filePath);
        InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
        p.load(reader); reader.close(); fis.close();
        return p;
    } catch (Throwable e) { return null; }
}

// ====================== 匹配逻辑 ======================

int getMatchStatus(String onlineName, String onlineAuthor, String onlineVersion, String onlineUpdateTime) {
    if (TextUtils.isEmpty(onlineName)) return STATUS_NOT_INSTALLED;
    String nameLow   = onlineName.trim().toLowerCase();
    String authorLow = TextUtils.isEmpty(onlineAuthor) ? "" : onlineAuthor.trim().toLowerCase();
    Properties local = null;
    if (!authorLow.isEmpty()) local = (Properties) installedExact.get(nameLow + "::" + authorLow);
    if (local == null)        local = (Properties) installedWeak.get(nameLow + "::");
    if (local == null) return STATUS_NOT_INSTALLED;
    String localUpTime  = local.getProperty("updateTime", "").trim();
    String localVersion = local.getProperty("version",    "").trim();
    String onlineUp  = TextUtils.isEmpty(onlineUpdateTime) ? "" : onlineUpdateTime.trim();
    String onlineVer = TextUtils.isEmpty(onlineVersion)    ? "" : onlineVersion.trim();
    if (!localUpTime.isEmpty() && !onlineUp.isEmpty()) {
        try {
            if (Long.parseLong(onlineUp) > Long.parseLong(localUpTime)) return STATUS_UPGRADABLE;
            return STATUS_UP_TO_DATE;
        } catch (Throwable ignore) {}
    }
    if (!localVersion.isEmpty() && !onlineVer.isEmpty())
        return localVersion.equals(onlineVer) ? STATUS_UP_TO_DATE : STATUS_UPGRADABLE;
    return STATUS_UNKNOWN_VER;
}

String getStatusColor(int s) {
    if (s == STATUS_UPGRADABLE)  return "#FF9800";
    if (s == STATUS_UP_TO_DATE)  return "#E53935";
    if (s == STATUS_UNKNOWN_VER) return "#E53935";
    return "#4A90E2";
}

String getStatusLabel(int s) {
    if (s == STATUS_UPGRADABLE)  return "更新";
    if (s == STATUS_UP_TO_DATE)  return "已安装";
    if (s == STATUS_UNKNOWN_VER) return "已安装";
    return "查看";
}

int countInstalled() {
    int count = 0;
    if (pluginArray == null) return 0;
    try {
        for (int i = 0; i < pluginArray.length(); i++) {
            JSONObject o = pluginArray.getJSONObject(i);
            if (getMatchStatus(o.optString("name"), o.optString("author"),
                    o.optString("version"), String.valueOf(o.opt("updateTime")))
                    != STATUS_NOT_INSTALLED) count++;
        }
    } catch (Throwable ignore) {}
    return count;
}

// 计算 header 文字，统一入口
String getHeaderText() {
    if ("local".equals(activeRepoId)) {
        return "本地已安装: " + installedList.size() + " 个插件";
    }
    if (pluginArray == null) return "插件数量: 0   已安装: 0";
    return "插件数量: " + pluginArray.length() + "   已安装: " + countInstalled();
}

String repoFingerprint(JSONArray arr) {
    if (arr == null) return "0:";
    try {
        StringBuilder sb = new StringBuilder();
        sb.append(arr.length()).append(":");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            sb.append(o.optString("name","")).append("|")
              .append(o.optString("version","")).append("|")
              .append(o.optString("updateTime","")).append(";");
        }
        return String.valueOf(sb.toString().hashCode());
    } catch (Throwable e) { return "0:"; }
}

// 在 installedList 中按名称+作者查找条目，供详情页取 dirPath
HashMap findInstalledEntry(String nameLow, String authorLow) {
    for (int i = 0; i < installedList.size(); i++) {
        HashMap e = (HashMap) installedList.get(i);
        String eName   = e.get("name").toString().toLowerCase();
        String eAuthor = e.get("author").toString().toLowerCase();
        if (eName.equals(nameLow) && (authorLow.isEmpty() || eAuthor.equals(authorLow)))
            return e;
    }
    return null;
}

// ====================== 单 Dialog 架构 ======================

void createMasterDialog(final Activity ctx) {
    try {
        FrameLayout content = new FrameLayout(ctx);
        content.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        setMasterContent(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setView(content);
        AlertDialog dialog = builder.create();
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int keyCode, android.view.KeyEvent event) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    if ("detail".equals(currentPage)) { navigateBackToList(ctx); return true; }
                }
                return false;
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                currentPage = "list";
                setMasterDialog(null); setMasterContent(null);
                gListView = null; gListRoot = null; gHeaderView = null;
                gTabRow = null; gTabScroll = null; gSearchBox = null; gListCtx = null;
            }
        });
        setMasterDialog(dialog);
    } catch (Throwable e) {
        log("createMasterDialog异常=" + e);
        setMasterDialog(null); setMasterContent(null);
    }
}

void showMasterDialog(Activity ctx) {
    AlertDialog dialog = getMasterDialog();
    if (dialog == null) {
        createMasterDialog(ctx);
        dialog = getMasterDialog();
        if (dialog == null) { toast("对话框创建失败"); return; }
    }
    if (!dialog.isShowing()) {
        try { dialog.show(); }
        catch (Throwable e) { log("showDialog异常=" + e); toast("对话框显示失败"); return; }
    }
    try {
        android.view.Window w = dialog.getWindow();
        if (w == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 20));
        bg.setColor(Color.parseColor(getBgColor(ctx)));
        w.setBackgroundDrawable(bg);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ctx.getWindowManager().getDefaultDisplay().getMetrics(dm);
        w.setLayout((int)(dm.widthPixels * 0.92f), (int)(dm.heightPixels * 0.85f));
        w.setGravity(Gravity.CENTER);
    } catch (Throwable ignore) {}
}

void swapContent(View newView) {
    FrameLayout content = getMasterContent();
    if (content == null || newView == null) return;
    try {
        android.view.ViewParent p = newView.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(newView);
    } catch (Throwable ignore) {}
    content.removeAllViews();
    newView.setAlpha(0f);
    content.addView(newView, new FrameLayout.LayoutParams(-1, -1));
    newView.animate().alpha(1f).setDuration(100).start();
}

void fadeSwapContent(final View newView) {
    FrameLayout content = getMasterContent();
    if (content == null || newView == null) return;
    try {
        android.view.ViewParent p = newView.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(newView);
    } catch (Throwable ignore) {}
    if (content.getChildCount() == 0) { swapContent(newView); return; }
    final View oldView = content.getChildAt(0);
    oldView.animate().alpha(0f).setDuration(80).withEndAction(new Runnable() {
        @Override public void run() {
            try {
                FrameLayout cc = getMasterContent();
                if (cc != null) {
                    cc.removeAllViews();
                    newView.setAlpha(0f);
                    cc.addView(newView, new FrameLayout.LayoutParams(-1, -1));
                    newView.animate().alpha(1f).setDuration(80).start();
                }
            } catch (Throwable e) { log("fadeSwapContent异常=" + e); }
        }
    }).start();
}

void navigateBackToList(Activity ctx) {
    currentPage = "list";
    if (getMasterContent() != null && gListView != null) {
        fadeSwapContent(gListView);
    } else {
        // 列表引用已丢失，重建
        if ("local".equals(activeRepoId)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    ensureInstalledCache();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { showPluginList(ctx); }
                    });
                }
            }).start();
        } else if (pluginArray != null) {
            showPluginList(ctx);
        }
    }
}

// 统一刷新：只更新 header + tab 高亮 + 列表内容，不重建外壳
void refreshListContent(final Activity ctx) {
    if (gListRoot == null || gListView == null) {
        // 外壳丢失，需完整重建
        if ("local".equals(activeRepoId) || pluginArray != null) showPluginList(ctx);
        return;
    }
    new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override public void run() {
            if (gHeaderView != null) gHeaderView.setText(getHeaderText());
            refreshTabHighlights(ctx);
            if (gListRoot == null) return;
            if ("local".equals(activeRepoId)) {
                renderLocalCards(ctx, gListRoot, currentKeyword);
            } else {
                renderPluginCards(ctx, gListRoot, currentKeyword);
            }
        }
    });
}

void refreshTabHighlights(Activity ctx) {
    if (gTabRow == null) return;
    for (int i = 0; i < gTabRow.getChildCount(); i++) {
        View child = gTabRow.getChildAt(i);
        if (!(child instanceof Button)) continue;
        Button btn = (Button) child;
        Object tag = btn.getTag();
        if (tag == null) continue;
        boolean active = activeRepoId.equals(tag.toString());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 999));
        if (active) {
            bg.setColor(Color.parseColor("#4A90E2"));
            btn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.parseColor(getCardColor(ctx)));
            bg.setStroke(dp(ctx, 1), Color.parseColor("#4A90E2"));
            btn.setTextColor(Color.parseColor("#4A90E2"));
        }
        btn.setBackground(bg);
    }
}

void rebuildRepoTabs(final Activity ctx) {
    if (gTabRow == null) return;
    gTabRow.removeAllViews();
    addTabToRow(ctx, "默认官方", "default");
    for (int i = 0; i < repoNames.size(); i++)
        addTabToRow(ctx, repoNames.get(i).toString(), String.valueOf(i));
    addTabToRow(ctx, "📦 本地", "local");
    refreshTabHighlights(ctx);
}

// 内部辅助：向 gTabRow 追加一个 tab 按钮
void addTabToRow(final Activity ctx, String label, final String repoId) {
    Button btn = createTabBtn(ctx, label, repoId.equals(activeRepoId));
    btn.setTag(repoId);
    btn.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
            if (!repoId.equals(activeRepoId)) switchRepo(ctx, repoId);
        }
    });
    gTabRow.addView(btn, makeTabLp(ctx));
}

void refreshListUI() {
    if (gListRoot == null || gListCtx == null) return;
    final Activity ctx = gListCtx;
    new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override public void run() {
            if (gHeaderView != null) gHeaderView.setText(getHeaderText());
            if (gListRoot == null) return;
            if ("local".equals(activeRepoId)) {
                renderLocalCards(ctx, gListRoot, currentKeyword);
            } else if (pluginArray != null) {
                renderPluginCards(ctx, gListRoot, currentKeyword);
            }
        }
    });
}

// ====================== 仓库静默加载 / 切换 ======================

void refreshRepoSilent(final Activity ctx, final String repoId, final boolean updateUi, final Runnable onComplete) {
    try {
        if (repoFetching && repoId.equals(fetchingRepoId)) {
            if (ctx != null && updateUi) { pendingRepoUiCtx = ctx; pendingRepoUiUpdate = true; }
            return;
        }
        if (repoFetching && !updateUi) return;
        repoFetching = true; fetchingRepoId = repoId;
        final long seq = ++repoFetchSeq;
        if (ctx != null && updateUi) { pendingRepoUiCtx = ctx; pendingRepoUiUpdate = true; }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean isLocal = isLocalRepo(repoId);
                    String rawUrl = getRepoRawUrl(repoId);
                    String body;
                    if (isLocal)           body = readLocalFile(rawUrl);
                    else if (needsProxy(rawUrl)) body = requestWithProxyFallback(rawUrl);
                    else                   body = requestWithRetry(rawUrl, 2, true);
                    if (seq != repoFetchSeq) return;
                    if (TextUtils.isEmpty(body)) {
                        if (updateUi || pendingRepoUiUpdate) {
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override public void run() { toast("获取失败，请检查网络或代理设置"); }
                            });
                        }
                        return;
                    }
                    JSONObject root = new JSONObject(body);
                    final JSONArray newArr = root.getJSONArray("plugins");
                    if (!isLocal) saveCache(body, repoId);
                    if (seq != repoFetchSeq) return;
                    final boolean isActiveRepo = repoId.equals(activeRepoId);
                    boolean dataChanged = true;
                    if (isActiveRepo && pluginArray != null)
                        dataChanged = !repoFingerprint(newArr).equals(repoFingerprint(pluginArray));
                    if (isActiveRepo && dataChanged) {
                        pluginArray = newArr;
                        installedCacheDirty = true;
                        ensureInstalledCache();
                    }
                    final boolean shouldUpdateUi = updateUi || pendingRepoUiUpdate;
                    final Activity uiCtx = ctx != null ? ctx : pendingRepoUiCtx;
                    if (shouldUpdateUi && uiCtx != null && isActiveRepo && dataChanged) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (getMasterDialog() == null) createMasterDialog(uiCtx);
                                    if (gListView == null || !getMasterDialog().isShowing()) {
                                        showPluginList(uiCtx);
                                    } else {
                                        refreshListContent(uiCtx);
                                    }
                                    if (onComplete != null) onComplete.run();
                                } catch (Throwable e) { log("refreshRepoSilent更新UI异常=" + e); }
                            }
                        });
                    } else if (onComplete != null) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() { onComplete.run(); }
                        });
                    }
                } catch (final Throwable e) {
                    log("refreshRepoSilent异常=" + e);
                    if (seq == repoFetchSeq && (updateUi || pendingRepoUiUpdate)) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() { toast("加载失败: " + e.getMessage()); }
                        });
                    }
                } finally {
                    if (seq == repoFetchSeq) {
                        repoFetching = false; fetchingRepoId = "";
                        pendingRepoUiCtx = null; pendingRepoUiUpdate = false;
                    }
                }
            }
        }).start();
    } catch (Throwable e) { log("refreshRepoSilent启动异常=" + e); }
}

void loadPlugins(final Activity ctx, final String repoId, final Runnable onComplete) {
    refreshRepoSilent(ctx, repoId, true, onComplete);
}

void switchRepo(final Activity ctx, final String repoId) {
    activeRepoId = repoId;
    saveConfig();
    currentKeyword = "";
    try { if (gSearchBox != null) gSearchBox.setText(""); } catch (Throwable ignore) {}

    // 先刷新 tab 高亮（主线程）
    new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override public void run() { refreshTabHighlights(ctx); }
    });

    // 本地 tab：扫描目录后直接渲染，无需网络请求
    if ("local".equals(repoId)) {
        new Thread(new Runnable() {
            @Override public void run() {
                installedCacheDirty = true;
                ensureInstalledCache();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { refreshListContent(ctx); }
                });
            }
        }).start();
        return;
    }

    // 其他仓库：优先加载本地缓存快速渲染，再后台拉取
    if (loadLocalCache(repoId)) {
        installedCacheDirty = true;
        new Thread(new Runnable() {
            @Override public void run() {
                ensureInstalledCache();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { refreshListContent(ctx); }
                });
                refreshRepoSilent(ctx, repoId, true, null);
            }
        }).start();
    } else {
        pluginArray = new JSONArray();
        refreshListContent(ctx);
        toast("正在后台拉取插件仓库");
        refreshRepoSilent(ctx, repoId, true, null);
    }
}

// ====================== 移除插件 ======================

void removePlugin(final Activity ctx, final String dirPath,
                  final TextView tvStatus, final Button btnRemove) {
    new Thread(new Runnable() {
        @Override public void run() {
            deleteDir(dirPath);
            installedCacheDirty = true;
            ensureInstalledCache();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    toast("插件已移除");
                    // 更新 header 计数
                    if (gHeaderView != null) gHeaderView.setText(getHeaderText());
                    if ("local".equals(activeRepoId)) {
                        // 本地 tab：直接重渲染列表
                        if (gListRoot != null && gListCtx != null)
                            renderLocalCards(gListCtx, gListRoot, currentKeyword);
                    } else {
                        // 仓库 tab：刷新卡片安装状态，同时更新详情页
                        if (gListRoot != null && gListCtx != null)
                            renderPluginCards(gListCtx, gListRoot, currentKeyword);
                        if (tvStatus  != null) tvStatus.setText("未安装");
                        if (btnRemove != null) btnRemove.setVisibility(View.GONE);
                    }
                }
            });
        }
    }).start();
}

// ====================== 主界面 ======================

void showPluginList(final Activity ctx) {
    try {
        // 本地 tab 不依赖 pluginArray，其他 tab 要求非空
        if (!"local".equals(activeRepoId) && pluginArray == null) {
            toast("数据为空"); return;
        }

        currentPage = "list";

        LinearLayout outerRoot = new LinearLayout(ctx);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(Color.parseColor(getBgColor(ctx)));
        outerRoot.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // ---- Header（代理 + 管理按钮，始终显示）----
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 6));

        final TextView tvHeader = new TextView(ctx);
        tvHeader.setText(getHeaderText());
        tvHeader.setTextSize(15);
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setTextColor(Color.parseColor(getTextColor(ctx)));
        headerRow.addView(tvHeader, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnProxy = createSmallBtn(ctx, "⚙ 代理", "#607D8B");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.leftMargin = dp(ctx, 8);
        headerRow.addView(btnProxy, btnLp);
        btnProxy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showProxyDialog(ctx); }
        });

        Button btnManage = createSmallBtn(ctx, "管理", "#4A90E2");
        headerRow.addView(btnManage, btnLp);
        btnManage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showManageRepoDialog(ctx); }
        });

        outerRoot.addView(headerRow);

        // ---- 搜索框 ----
        final EditText searchBox = new EditText(ctx);
        searchBox.setHint("搜索插件名称或作者...");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(14);
        searchBox.setTextColor(Color.parseColor(getTextColor(ctx)));
        searchBox.setHintTextColor(Color.parseColor(getSubTextColor(ctx)));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setCornerRadius(dp(ctx, 999));
        searchBg.setColor(Color.parseColor(getCardColor(ctx)));
        searchBg.setStroke(dp(ctx, 1), Color.parseColor(getStrokeColor(ctx)));
        searchBox.setBackground(searchBg);
        searchBox.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        if (!currentKeyword.isEmpty()) searchBox.setText(currentKeyword);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.leftMargin = dp(ctx, 14); searchLp.rightMargin  = dp(ctx, 14);
        searchLp.topMargin  = dp(ctx, 6);  searchLp.bottomMargin = dp(ctx, 4);
        outerRoot.addView(searchBox, searchLp);

        // ---- Tab 栏（始终渲染，本地 tab 无条件追加在末尾）----
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6));

        // 先把 gTabRow 指向新建的 tabRow，再用 addTabToRow 复用逻辑
        gTabRow = tabRow;
        addTabToRow(ctx, "默认官方", "default");
        for (int i = 0; i < repoNames.size(); i++)
            addTabToRow(ctx, repoNames.get(i).toString(), String.valueOf(i));
        addTabToRow(ctx, "📦 本地", "local");
        refreshTabHighlights(ctx);

        tabScroll.addView(tabRow);
        outerRoot.addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));

        // ---- 列表区 ----
        ScrollView scrollView = new ScrollView(ctx);
        final LinearLayout listRoot = new LinearLayout(ctx);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 14));
        scrollView.addView(listRoot);
        outerRoot.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 写入全局引用
        gListCtx    = ctx;
        gHeaderView = tvHeader;
        gListRoot   = listRoot;
        gListView   = outerRoot;
        gTabScroll  = tabScroll;
        gSearchBox  = searchBox;
        // gTabRow 已在上面赋值

        // 首次渲染
        if ("local".equals(activeRepoId)) {
            renderLocalCards(ctx, listRoot, currentKeyword);
        } else {
            renderPluginCards(ctx, listRoot, currentKeyword);
        }

        // 搜索监听
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                currentKeyword = s.toString().trim();
                if (gListRoot == null) return;
                if ("local".equals(activeRepoId)) {
                    renderLocalCards(ctx, gListRoot, currentKeyword);
                } else {
                    renderPluginCards(ctx, gListRoot, currentKeyword);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (getMasterDialog() == null) createMasterDialog(ctx);
        swapContent(outerRoot);
        showMasterDialog(ctx);
    } catch (Throwable e) {
        log("showPluginList异常=" + e);
        toast(e.toString());
    }
}

// ====================== 本地已安装列表渲染 ======================

void renderLocalCards(final Activity ctx, final LinearLayout listRoot, final String keyword) {
    listRoot.removeAllViews();
    String kw = keyword == null ? "" : keyword.toLowerCase().trim();
    int shown = 0;

    for (int i = 0; i < installedList.size(); i++) {
        HashMap entry = (HashMap) installedList.get(i);
        String name    = entry.get("name").toString();
        String author  = entry.get("author").toString();
        String version = entry.get("version").toString();
        String dirPath = entry.get("dirPath").toString();

        if (!kw.isEmpty()
                && !name.toLowerCase().contains(kw)
                && !author.toLowerCase().contains(kw)) continue;

        LinearLayout card = createCard(ctx);
        listRoot.addView(card);

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow);

        LinearLayout textWrap = new LinearLayout(ctx);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        topRow.addView(textWrap);

        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextSize(17);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(Color.parseColor(getTextColor(ctx)));
        textWrap.addView(tvName);

        String subText = "v" + version + (author.isEmpty() ? "" : "  ·  " + author);
        TextView tvSub = new TextView(ctx);
        tvSub.setText(subText);
        tvSub.setTextSize(12);
        tvSub.setTextColor(Color.parseColor(getSubTextColor(ctx)));
        tvSub.setPadding(0, dp(ctx, 5), 0, 0);
        textWrap.addView(tvSub);

        // 目录路径小字，方便区分自编写插件
        String shortPath = dirPath.length() > 42
                ? "…" + dirPath.substring(dirPath.length() - 40) : dirPath;
        TextView tvPath = new TextView(ctx);
        tvPath.setText(shortPath);
        tvPath.setTextSize(10);
        tvPath.setTextColor(Color.parseColor(getSubTextColor(ctx)));
        tvPath.setPadding(0, dp(ctx, 3), 0, 0);
        textWrap.addView(tvPath);

        Button btnRemove = createModernButton(ctx, "移除", "#E53935");
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(-2, -2);
        removeLp.leftMargin = dp(ctx, 10);
        topRow.addView(btnRemove, removeLp);

        // 用 setTag 存索引，彻底避免 BeanShell 匿名类捕获循环变量错误
        btnRemove.setTag(new Integer(i));
        btnRemove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int idx = ((Integer) v.getTag()).intValue();
                // 运行时从 installedList 取最新数据，索引已由 tag 锁定
                HashMap e = (HashMap) installedList.get(idx);
                final String eName    = e.get("name").toString();
                final String eDirPath = e.get("dirPath").toString();

                // 构建与整体风格统一的确认弹窗
                LinearLayout msgLayout = new LinearLayout(ctx);
                msgLayout.setOrientation(LinearLayout.VERTICAL);
                msgLayout.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 8));

                TextView tvMsg = new TextView(ctx);
                tvMsg.setText("确定移除插件「" + eName + "」？");
                tvMsg.setTextSize(14);
                tvMsg.setTextColor(Color.parseColor(getTextColor(ctx)));
                msgLayout.addView(tvMsg);

                // 路径以小字显示，不撑大弹窗
                TextView tvPath2 = new TextView(ctx);
                tvPath2.setText(eDirPath);
                tvPath2.setTextSize(11);
                tvPath2.setTextColor(Color.parseColor(getSubTextColor(ctx)));
                LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
                pathLp.topMargin = dp(ctx, 8);
                tvPath2.setLayoutParams(pathLp);
                msgLayout.addView(tvPath2);

                AlertDialog confirmDialog = new AlertDialog.Builder(ctx)
                        .setTitle("确认移除")
                        .setView(msgLayout)
                        .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                removePlugin(ctx, eDirPath, null, null);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .create();

                confirmDialog.show();
                // 应用与代理/仓库弹窗相同的圆角+深浅色背景
                setupDialogStyle(ctx, confirmDialog);
                styleDialogButtons(ctx, confirmDialog);
            }
        });

        shown++;
    }

    if (shown == 0) {
        TextView empty = new TextView(ctx);
        empty.setText(kw.isEmpty() ? "暂无本地已安装插件" : "无匹配插件「" + kw + "」");
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.parseColor(getSubTextColor(ctx)));
        empty.setPadding(0, dp(ctx, 40), 0, 0);
        listRoot.addView(empty);
    }
}

// ====================== 仓库插件列表渲染 ======================

void renderPluginCards(final Activity ctx, final LinearLayout listRoot, final String keyword) {
    final long seq = ++renderSeq;
    listRoot.removeAllViews();
    if (pluginArray == null) return;
    final String kw = keyword == null ? "" : keyword.toLowerCase().trim();
    renderPluginBatch(ctx, listRoot, kw, 0, seq);
}

void renderPluginBatch(final Activity ctx, final LinearLayout listRoot,
                       final String kw, final int startIndex, final long seq) {
    if (seq != renderSeq || pluginArray == null || listRoot == null) return;
    int added = 0, nextIndex = startIndex;
    try {
        for (int i = startIndex; i < pluginArray.length(); i++) {
            if (seq != renderSeq) return;
            JSONObject obj = pluginArray.getJSONObject(i);
            String name   = obj.optString("name");
            String author = obj.optString("author");
            if (!kw.isEmpty()
                    && !name.toLowerCase().contains(kw)
                    && !author.toLowerCase().contains(kw)) {
                nextIndex = i + 1; continue;
            }
            addPluginCard(ctx, listRoot, obj, i);
            added++; nextIndex = i + 1;
            if (added >= RENDER_BATCH_SIZE) break;
        }
        if (listRoot.getChildCount() == 0 && nextIndex >= pluginArray.length()) {
            TextView empty = new TextView(ctx);
            empty.setText(kw.isEmpty() ? "暂无插件" : "无匹配插件「" + kw + "」");
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.parseColor(getSubTextColor(ctx)));
            empty.setPadding(0, dp(ctx, 40), 0, 0);
            listRoot.addView(empty);
            return;
        }
        if (nextIndex < pluginArray.length()) {
            final int ni = nextIndex;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { renderPluginBatch(ctx, listRoot, kw, ni, seq); }
            }, 16);
        }
    } catch (Throwable e) { log("renderPluginBatch异常=" + e); }
}

void addPluginCard(final Activity ctx, LinearLayout listRoot, final JSONObject obj, final int index) {
    try {
        final String name    = obj.optString("name");
        final String version = obj.optString("version");
        final String author  = obj.optString("author");
        final String upTime  = String.valueOf(obj.opt("updateTime"));
        final int status     = getMatchStatus(name, author, version, upTime);

        LinearLayout card = createCard(ctx);
        listRoot.addView(card);

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow);

        LinearLayout textWrap = new LinearLayout(ctx);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        topRow.addView(textWrap);

        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextSize(17);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(Color.parseColor(getTextColor(ctx)));
        textWrap.addView(tvName);

        TextView tvSub = new TextView(ctx);
        tvSub.setText("v" + version + "  ·  " + author);
        tvSub.setTextSize(12);
        tvSub.setTextColor(Color.parseColor(getSubTextColor(ctx)));
        tvSub.setPadding(0, dp(ctx, 6), 0, 0);
        textWrap.addView(tvSub);

        Button btn = createModernButton(ctx, getStatusLabel(status), getStatusColor(status));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.leftMargin = dp(ctx, 10);
        topRow.addView(btn, btnLp);
        btn.setTag(new Integer(index));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    showPluginInfo(ctx, pluginArray.getJSONObject(((Integer) v.getTag()).intValue()));
                } catch (Throwable e) { toast(e.toString()); }
            }
        });
    } catch (Throwable e) { log("addPluginCard异常=" + e); }
}

// ====================== 插件详情 ======================

void showPluginInfo(final Activity ctx, final JSONObject obj) {
    try {
        final String name        = obj.optString("name");
        final String version     = obj.optString("version");
        final String author      = obj.optString("author");
        final String homeLink    = decodeUrl(obj.optString("homeLink"));
        final String downloadUrl = decodeUrl(obj.optString("downloadUrl"));
        final String updateTime  = String.valueOf(obj.opt("updateTime"));
        final int status         = getMatchStatus(name, author, version, updateTime);

        currentPage = "detail";

        LinearLayout pageRoot = new LinearLayout(ctx);
        pageRoot.setOrientation(LinearLayout.VERTICAL);
        pageRoot.setBackgroundColor(Color.parseColor(getBgColor(ctx)));
        pageRoot.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        LinearLayout detailHeader = new LinearLayout(ctx);
        detailHeader.setOrientation(LinearLayout.HORIZONTAL);
        detailHeader.setGravity(Gravity.CENTER_VERTICAL);
        detailHeader.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 6));

        Button btnBack = createSmallBtn(ctx, "← 返回", "#607D8B");
        detailHeader.addView(btnBack, new LinearLayout.LayoutParams(-2, -2));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { navigateBackToList(ctx); }
        });

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(name);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(Color.parseColor(getTextColor(ctx)));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(ctx, 12);
        detailHeader.addView(tvTitle, titleLp);
        pageRoot.addView(detailHeader);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 16));
        sv.addView(root);
        pageRoot.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(createInfoItem(ctx, "名称",   name));
        root.addView(createInfoItem(ctx, "作者",   author));
        root.addView(createInfoItem(ctx, "版本",   version));
        root.addView(createInfoItem(ctx, "更新时间", updateTime));

        final String statusHint;
        if      (status == STATUS_UP_TO_DATE)  statusHint = "✅ 已安装（最新）";
        else if (status == STATUS_UPGRADABLE)  statusHint = "🔄 已安装（有更新）";
        else if (status == STATUS_UNKNOWN_VER) statusHint = "✅ 已安装（版本未知）";
        else                                   statusHint = "未安装";

        LinearLayout statusCard = makeCardLayout(ctx);

        TextView tvScTitle = new TextView(ctx);
        tvScTitle.setText("安装状态");
        tvScTitle.setTextSize(12);
        tvScTitle.setTypeface(null, Typeface.BOLD);
        tvScTitle.setTextColor(Color.parseColor("#4A90E2"));
        statusCard.addView(tvScTitle);

        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(-1, -2);
        srLp.topMargin = dp(ctx, 6);
        statusCard.addView(statusRow, srLp);

        final TextView tvStatusValue = new TextView(ctx);
        tvStatusValue.setText(statusHint);
        tvStatusValue.setTextSize(14);
        tvStatusValue.setTextColor(Color.parseColor(getTextColor(ctx)));
        tvStatusValue.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        statusRow.addView(tvStatusValue);

        boolean canInstall = !TextUtils.isEmpty(homeLink) && homeLink.contains("github.com");
        if (canInstall) {
            String btnLabel = (status == STATUS_UPGRADABLE)    ? "更新"
                            : (status == STATUS_NOT_INSTALLED) ? "安装" : "重装";
            String btnColor = (status == STATUS_UPGRADABLE)    ? "#FF9800"
                            : (status == STATUS_NOT_INSTALLED) ? "#4A90E2" : "#E53935";
            final Button btnInstall = createSmallBtn(ctx, btnLabel, btnColor);
            LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(-2, -2);
            ibLp.leftMargin = dp(ctx, 10);
            statusRow.addView(btnInstall, ibLp);
            btnInstall.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    btnInstall.setEnabled(false);
                    installPlugin(ctx, name, homeLink, tvStatusValue, btnInstall);
                }
            });
        }

        // 已安装时追加移除按钮（灰色，次要操作）
        if (status != STATUS_NOT_INSTALLED) {
            final HashMap localEntry = findInstalledEntry(
                    name.trim().toLowerCase(), author.trim().toLowerCase());
            if (localEntry != null) {
                final String dirPath = localEntry.get("dirPath").toString();
                final Button btnRemove = createSmallBtn(ctx, "移除", "#757575");
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = dp(ctx, 8);
                statusRow.addView(btnRemove, rlp);
                btnRemove.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        new AlertDialog.Builder(ctx)
                            .setTitle("确认移除")
                            .setMessage("确定移除插件「" + name + "」？")
                            .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    removePlugin(ctx, dirPath, tvStatusValue, btnRemove);
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    }
                });
            }
        }

        root.addView(statusCard);
        root.addView(createCopyInfoItem(ctx, "主页",   homeLink));
        root.addView(createCopyInfoItem(ctx, "下载地址", downloadUrl));

        if (getMasterDialog() == null) createMasterDialog(ctx);
        swapContent(pageRoot);
        showMasterDialog(ctx);
    } catch (Throwable e) {
        log("showPluginInfo异常=" + e);
        toast(e.toString());
    }
}

// ====================== HTTP 公共底层 ======================

byte[] httpRawGet(String url, int connectMs, int readMs) {
    HttpURLConnection conn = null;
    try {
        conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.connect();
        if (conn.getResponseCode() != 200) return null;
        BufferedInputStream bis = new BufferedInputStream(conn.getInputStream());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int len;
        while ((len = bis.read(buf)) != -1) bos.write(buf, 0, len);
        bis.close();
        return bos.toByteArray();
    } catch (Throwable e) { return null; }
    finally { try { if (conn != null) conn.disconnect(); } catch (Throwable ignore) {} }
}

String httpGetText(String url) {
    byte[] bytes = httpRawGet(url, 8000, 10000);
    if (bytes == null) return null;
    try { return new String(bytes, "UTF-8"); } catch (Throwable e) { return null; }
}

byte[] httpGetBytes(String url) { return httpRawGet(url, 15000, 30000); }

String requestWithRetry(String api, int maxRetry, boolean silent) {
    for (int i = 1; i <= maxRetry; i++) {
        String result = httpGetText(api);
        if (result != null) return result;
        if (!silent) log("请求失败(第" + i + "次): " + api);
        try { Thread.sleep(800); } catch (Throwable ignore) {}
    }
    return null;
}

String requestWithProxyFallback(String rawUrl) {
    if (proxyList.isEmpty()) return requestWithRetry(rawUrl, 2, true);
    if (activeProxyIndex < 0 || activeProxyIndex >= proxyList.size()) activeProxyIndex = 0;
    String result = httpGetText(proxyList.get(activeProxyIndex).toString() + rawUrl);
    if (result != null) return result;
    for (int i = 0; i < proxyList.size(); i++) {
        if (i == activeProxyIndex) continue;
        result = httpGetText(proxyList.get(i).toString() + rawUrl);
        if (result != null) {
            activeProxyIndex = i; saveConfig();
            final int si = i;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { toast("已自动切换代理: " + proxyList.get(si)); }
            });
            return result;
        }
    }
    return null;
}

byte[] downloadFileBytes(String rawUrl) {
    if (proxyList.isEmpty()) return httpGetBytes(rawUrl);
    if (activeProxyIndex < 0 || activeProxyIndex >= proxyList.size()) activeProxyIndex = 0;
    byte[] result = httpGetBytes(proxyList.get(activeProxyIndex).toString() + rawUrl);
    if (result != null) return result;
    for (int i = 0; i < proxyList.size(); i++) {
        if (i == activeProxyIndex) continue;
        result = httpGetBytes(proxyList.get(i).toString() + rawUrl);
        if (result != null) { activeProxyIndex = i; saveConfig(); return result; }
    }
    return null;
}

String readLocalFile(String path) {
    try {
        String p = path.startsWith("file://") ? path.substring(7) : path;
        FileInputStream fis = new FileInputStream(p);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int len;
        while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
        fis.close();
        return bos.toString("UTF-8");
    } catch (Throwable e) { log("readLocalFile异常=" + e.getMessage()); return null; }
}

// ====================== 缓存 ======================

boolean loadLocalCache(String repoId) {
    if ("local".equals(repoId)) return false; // 本地 tab 不走文件缓存
    if (isLocalRepo(repoId))   return false;
    try {
        File file = new File(getCacheFilePath(repoId));
        if (!file.exists()) return false;
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int len;
        while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
        fis.close();
        pluginArray = new JSONObject(bos.toString("UTF-8")).getJSONArray("plugins");
        return true;
    } catch (Throwable e) { return false; }
}

void saveCache(String body, String repoId) {
    try {
        FileOutputStream fos = new FileOutputStream(new File(getCacheFilePath(repoId)));
        fos.write(body.getBytes("UTF-8")); fos.flush(); fos.close();
    } catch (Throwable e) { log("saveCache异常=" + e); }
}

// ====================== 安装 / 更新 ======================

String[] parseGithubUrl(String url) {
    try {
        if (url == null || url.isEmpty()) return null;
        int ghIdx = url.indexOf("github.com/");
        if (ghIdx < 0) return null;
        String rest  = url.substring(ghIdx + "github.com/".length()).replaceAll("/$", "");
        String[] parts = rest.split("/");
        if (parts.length < 2) return null;
        String owner = parts[0], repo = parts[1], branch = "main", path = "";
        if (parts.length >= 4 && ("tree".equals(parts[2]) || "blob".equals(parts[2]))) {
            branch = parts[3];
            if (parts.length >= 5) {
                StringBuilder sb = new StringBuilder();
                for (int i = 4; i < parts.length; i++) {
                    if (sb.length() > 0) sb.append("/");
                    sb.append(parts[i]);
                }
                path = sb.toString();
            }
        }
        return new String[]{owner, repo, branch, path};
    } catch (Throwable e) { return null; }
}

void collectFiles(String owner, String repo, String branch, String apiPath, String relBase, List results) {
    try {
        String enc    = apiPath.replace(" ", "%20");
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + enc + "?ref=" + branch;
        String body   = needsProxy(apiUrl) ? requestWithProxyFallback(apiUrl) : requestWithRetry(apiUrl, 2, false);
        if (TextUtils.isEmpty(body)) return;
        JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String type = item.optString("type"), itemName = item.optString("name"), itemPath = item.optString("path");
            String relPath = relBase.isEmpty() ? itemName : relBase + "/" + itemName;
            if ("file".equals(type))
                results.add(new String[]{relPath, "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/" + itemPath});
            else if ("dir".equals(type))
                collectFiles(owner, repo, branch, itemPath, relPath, results);
        }
    } catch (Throwable e) { log("[collect] " + e.getMessage()); }
}

boolean writeFile(String absPath, byte[] bytes) {
    try {
        new File(absPath.substring(0, absPath.lastIndexOf("/"))).mkdirs();
        FileOutputStream fos = new FileOutputStream(new File(absPath));
        fos.write(bytes); fos.flush(); fos.close();
        return true;
    } catch (Throwable e) { log("writeFile失败: " + absPath + "→" + e.getMessage()); return false; }
}

boolean copyDir(String srcPath, String dstPath) {
    try {
        new File(dstPath).mkdirs();
        File[] files = new File(srcPath).listFiles();
        if (files == null) return true;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String dst = dstPath + "/" + f.getName();
            if (f.isDirectory()) { if (!copyDir(f.getAbsolutePath(), dst)) return false; }
            else {
                FileInputStream fis = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int len;
                while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
                fis.close();
                if (!writeFile(dst, bos.toByteArray())) return false;
            }
        }
        return true;
    } catch (Throwable e) { log("copyDir失败=" + e.getMessage()); return false; }
}

void deleteDir(String path) {
    try {
        File dir = new File(path);
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null)
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f.isDirectory()) deleteDir(f.getAbsolutePath()); else f.delete();
            }
        dir.delete();
    } catch (Throwable ignore) {}
}

void updateInstallText(final TextView tv, final String text) {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override public void run() { tv.setText(text); }
    });
}

void finishInstall(final TextView tvStatus, final Button btnInstall, final String msg) {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override public void run() {
            tvStatus.setText(msg);
            if (btnInstall != null) btnInstall.setEnabled(true);
        }
    });
}

void installPlugin(final Activity ctx, final String pluginName,
                   final String homeLink, final TextView tvStatus, final Button btnInstall) {
    final String[] parsed = parseGithubUrl(homeLink);
    if (parsed == null) {
        toast("无法解析安装链接");
        if (btnInstall != null) btnInstall.setEnabled(true);
        return;
    }
    updateInstallText(tvStatus, "⏳ 准备中...");
    new Thread(new Runnable() {
        @Override public void run() {
            List files = new ArrayList();
            collectFiles(parsed[0], parsed[1], parsed[2], parsed[3], "", files);
            if (files.isEmpty()) { finishInstall(tvStatus, btnInstall, "❌ 未找到可安装的文件"); return; }
            String safeName = pluginName.replace("/", "_").replace("\\", "_");
            String tempDir  = cacheDir.toString() + "/install_temp/" + safeName;
            deleteDir(tempDir); new File(tempDir).mkdirs();
            int total = files.size(), downloadFail = 0;
            for (int i = 0; i < total; i++) {
                String[] entry = (String[]) files.get(i);
                updateInstallText(tvStatus, "⏳ 下载 " + (i+1) + "/" + total + "  " + entry[0]);
                byte[] bytes = downloadFileBytes(entry[1]);
                if (bytes == null || bytes.length == 0) { downloadFail++; continue; }
                writeFile(tempDir + "/" + entry[0], bytes);
            }
            boolean allOk = (downloadFail == 0);
            if (allOk)
                for (int i = 0; i < files.size(); i++) {
                    File f = new File(tempDir + "/" + ((String[]) files.get(i))[0]);
                    if (!f.exists() || f.length() == 0) { allOk = false; break; }
                }
            if (!allOk) {
                deleteDir(tempDir);
                finishInstall(tvStatus, btnInstall, "❌ 下载不完整（" + downloadFail + " 个失败），原插件未变动");
                return;
            }
            String parentPath = pluginDir.toString().substring(0, pluginDir.toString().lastIndexOf("/"));
            String installDir = parentPath + "/" + safeName;
            boolean copyOk = copyDir(tempDir, installDir);
            deleteDir(tempDir);
            if (!copyOk) { finishInstall(tvStatus, btnInstall, "❌ 文件替换失败，请检查存储权限"); return; }
            installedCacheDirty = true; ensureInstalledCache();
            refreshListUI();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    if (btnInstall != null) {
                        btnInstall.setText("重装"); btnInstall.setEnabled(true);
                        GradientDrawable bg = new GradientDrawable();
                        bg.setCornerRadius(dp(ctx, 999));
                        bg.setColor(Color.parseColor("#E53935"));
                        btnInstall.setBackground(bg);
                    }
                    tvStatus.setText("✅ 已安装（最新）");
                    toast("「" + pluginName + "」安装完成（共 " + total + " 个文件）");
                }
            });
        }
    }).start();
}

// ====================== 管理仓库弹窗 ======================

void showManageRepoDialog(final Activity ctx) {
    try {
        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(Color.parseColor(getBgColor(ctx)));
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));
        sv.addView(root);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("自定义仓库管理").setView(sv).setNegativeButton("关闭", null).create();
        Runnable build = new Runnable() {
            @Override public void run() {
                root.removeAllViews();
                if (repoNames.isEmpty()) {
                    TextView empty = new TextView(ctx);
                    empty.setText("暂无自定义仓库"); empty.setTextSize(13);
                    empty.setTextColor(Color.parseColor(getSubTextColor(ctx)));
                    empty.setPadding(dp(ctx,4), dp(ctx,8), dp(ctx,4), dp(ctx,16));
                    root.addView(empty);
                } else {
                    final Runnable self = this;
                    for (int i = 0; i < repoNames.size(); i++) {
                        LinearLayout card = createCard(ctx);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                        clp.bottomMargin = dp(ctx, 10); card.setLayoutParams(clp);
                        root.addView(card);
                        LinearLayout row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                        card.addView(row);
                        LinearLayout tw = new LinearLayout(ctx);
                        tw.setOrientation(LinearLayout.VERTICAL);
                        tw.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                        row.addView(tw);
                        TextView tn = new TextView(ctx);
                        tn.setText(repoNames.get(i).toString()); tn.setTextSize(15);
                        tn.setTypeface(null, Typeface.BOLD);
                        tn.setTextColor(Color.parseColor(getTextColor(ctx)));
                        tw.addView(tn);
                        TextView tu = new TextView(ctx);
                        tu.setText(repoUrls.get(i).toString()); tu.setTextSize(11);
                        tu.setTextColor(Color.parseColor(getSubTextColor(ctx)));
                        tu.setPadding(0, dp(ctx,4), 0, 0); tw.addView(tu);
                        Button btnDel = createSmallBtn(ctx, "删除", "#E53935");
                        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                        dlp.leftMargin = dp(ctx, 8); row.addView(btnDel, dlp);
                        btnDel.setTag(new Integer(i));
                        btnDel.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                int idx = ((Integer) v.getTag()).intValue();
                                boolean deletingActive = String.valueOf(idx).equals(activeRepoId);
                                deleteRepo(idx);
                                rebuildRepoTabs(ctx);
                                if (!deletingActive) { self.run(); return; }
                                if (loadLocalCache(activeRepoId)) {
                                    new Thread(new Runnable() {
                                        @Override public void run() {
                                            installedCacheDirty = true; ensureInstalledCache();
                                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                                @Override public void run() { refreshListContent(ctx); }
                                            });
                                        }
                                    }).start();
                                } else {
                                    pluginArray = new JSONArray();
                                    refreshListContent(ctx);
                                    toast("正在后台拉取插件仓库");
                                    refreshRepoSilent(ctx, activeRepoId, true, null);
                                }
                                self.run();
                            }
                        });
                    }
                }
                View div = new View(ctx);
                div.setBackgroundColor(Color.parseColor(getStrokeColor(ctx)));
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, 1);
                divLp.topMargin = dp(ctx,8); divLp.bottomMargin = dp(ctx,14);
                root.addView(div, divLp);
                TextView addTitle = new TextView(ctx);
                addTitle.setText("添加新仓库"); addTitle.setTextSize(14);
                addTitle.setTypeface(null, Typeface.BOLD);
                addTitle.setTextColor(Color.parseColor(getTextColor(ctx)));
                addTitle.setPadding(0, 0, 0, dp(ctx,10)); root.addView(addTitle);
                final EditText etName = new EditText(ctx);
                etName.setHint("仓库名称"); etName.setSingleLine(true); etName.setTextSize(14);
                etName.setTextColor(Color.parseColor(getTextColor(ctx)));
                etName.setHintTextColor(Color.parseColor(getSubTextColor(ctx)));
                etName.setBackground(makeInputBg(ctx));
                etName.setPadding(dp(ctx,12), dp(ctx,10), dp(ctx,12), dp(ctx,10));
                root.addView(etName, new LinearLayout.LayoutParams(-1, -2));
                final EditText etUrl = new EditText(ctx);
                etUrl.setHint("JSON 链接或本地路径"); etUrl.setSingleLine(true); etUrl.setTextSize(14);
                etUrl.setTextColor(Color.parseColor(getTextColor(ctx)));
                etUrl.setHintTextColor(Color.parseColor(getSubTextColor(ctx)));
                etUrl.setBackground(makeInputBg(ctx));
                etUrl.setPadding(dp(ctx,12), dp(ctx,10), dp(ctx,12), dp(ctx,10));
                LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-1, -2);
                etLp.topMargin = dp(ctx, 8); root.addView(etUrl, etLp);
                LinearLayout saveBtnRow = new LinearLayout(ctx);
                saveBtnRow.setGravity(Gravity.END);
                LinearLayout.LayoutParams sbrLp = new LinearLayout.LayoutParams(-1, -2);
                sbrLp.topMargin = dp(ctx, 12); root.addView(saveBtnRow, sbrLp);
                Button btnSave = createModernButton(ctx, "保存", "#4A90E2");
                saveBtnRow.addView(btnSave);
                final Runnable selfRef = this;
                btnSave.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String n = etName.getText().toString().trim();
                        String u = etUrl.getText().toString().trim();
                        if (n.isEmpty() || u.isEmpty()) { toast("名称和链接不能为空"); return; }
                        addRepo(n, u);
                        String newRepoId = String.valueOf(repoNames.size() - 1);
                        etName.setText(""); etUrl.setText("");
                        toast("已添加: " + n + "，正在后台预拉取");
                        rebuildRepoTabs(ctx);
                        refreshRepoSilent(ctx, newRepoId, false, null);
                        selfRef.run();
                    }
                });
            }
        };
        build.run();
        dialog.show();
        setupDialogStyle(ctx, dialog);
        styleDialogButtons(ctx, dialog);
    } catch (Throwable e) { log("showManageRepoDialog异常=" + e); toast(e.toString()); }
}

// ====================== 代理管理弹窗 ======================

void showProxyDialog(final Activity ctx) {
    try {
        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(Color.parseColor(getBgColor(ctx)));
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));
        sv.addView(root);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("代理管理").setView(sv).setNegativeButton("关闭", null).create();
        Runnable build = new Runnable() {
            @Override public void run() {
                root.removeAllViews();
                TextView hint = new TextView(ctx);
                hint.setText("点击某条立即切换为当前使用代理"); hint.setTextSize(12);
                hint.setTextColor(Color.parseColor(getSubTextColor(ctx)));
                hint.setPadding(0, 0, 0, dp(ctx, 10)); root.addView(hint);
                final Runnable self = this;
                for (int i = 0; i < proxyList.size(); i++) {
                    boolean isActive = (i == activeProxyIndex);
                    LinearLayout card = createCard(ctx);
                    if (isActive) {
                        GradientDrawable abg = new GradientDrawable();
                        abg.setCornerRadius(dp(ctx, 14));
                        abg.setColor(Color.parseColor(isDarkMode(ctx) ? "#1A2744" : "#EBF2FF"));
                        abg.setStroke(dp(ctx, 2), Color.parseColor("#4A90E2"));
                        card.setBackground(abg);
                    }
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                    clp.bottomMargin = dp(ctx, 8); card.setLayoutParams(clp);
                    root.addView(card);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                    card.addView(row);
                    TextView dot = new TextView(ctx);
                    dot.setText(isActive ? "●" : "○"); dot.setTextSize(16);
                    dot.setTextColor(Color.parseColor(isActive ? "#4A90E2" : getSubTextColor(ctx)));
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                    dlp.rightMargin = dp(ctx, 10); row.addView(dot, dlp);
                    TextView tvUrl = new TextView(ctx);
                    tvUrl.setText(proxyList.get(i).toString()); tvUrl.setTextSize(13);
                    tvUrl.setTextColor(Color.parseColor(getTextColor(ctx)));
                    tvUrl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f)); row.addView(tvUrl);
                    Button btnDel = createSmallBtn(ctx, "×", "#E53935");
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                    blp.leftMargin = dp(ctx, 8); row.addView(btnDel, blp);
                    card.setTag(new Integer(i));
                    card.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            activeProxyIndex = ((Integer) v.getTag()).intValue(); saveConfig();
                            toast("已切换: " + proxyList.get(activeProxyIndex)); self.run();
                        }
                    });
                    btnDel.setTag(new Integer(i));
                    btnDel.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (proxyList.size() <= 1) { toast("至少保留一个代理"); return; }
                            int idx = ((Integer) v.getTag()).intValue();
                            proxyList.remove(idx);
                            if (activeProxyIndex >= proxyList.size()) activeProxyIndex = 0;
                            if (activeProxyIndex < 0) activeProxyIndex = 0;
                            saveConfig(); self.run();
                        }
                    });
                }
                View div = new View(ctx);
                div.setBackgroundColor(Color.parseColor(getStrokeColor(ctx)));
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, 1);
                divLp.topMargin = dp(ctx,8); divLp.bottomMargin = dp(ctx,12);
                root.addView(div, divLp);
                final EditText etProxy = new EditText(ctx);
                etProxy.setHint("添加代理地址（以 / 结尾）"); etProxy.setSingleLine(true); etProxy.setTextSize(13);
                etProxy.setTextColor(Color.parseColor(getTextColor(ctx)));
                etProxy.setHintTextColor(Color.parseColor(getSubTextColor(ctx)));
                etProxy.setBackground(makeInputBg(ctx));
                etProxy.setPadding(dp(ctx,12), dp(ctx,10), dp(ctx,12), dp(ctx,10));
                root.addView(etProxy, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout addRow = new LinearLayout(ctx);
                addRow.setGravity(Gravity.END);
                LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(-1, -2);
                arlp.topMargin = dp(ctx, 10); root.addView(addRow, arlp);
                Button btnAdd = createModernButton(ctx, "添加", "#4A90E2");
                addRow.addView(btnAdd);
                btnAdd.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String url = etProxy.getText().toString().trim();
                        if (url.isEmpty()) { toast("代理地址不能为空"); return; }
                        if (!url.endsWith("/")) url = url + "/";
                        proxyList.add(url); saveConfig(); etProxy.setText(""); toast("已添加"); self.run();
                    }
                });
            }
        };
        build.run();
        dialog.show();
        setupDialogStyle(ctx, dialog);
        styleDialogButtons(ctx, dialog);
    } catch (Throwable e) { log("showProxyDialog异常=" + e); toast(e.toString()); }
}

// ====================== 主题 ======================

boolean isDarkMode(Activity ctx) {
    try {
        int f = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return f == Configuration.UI_MODE_NIGHT_YES;
    } catch (Throwable e) { return false; }
}

String getBgColor(Activity ctx)      { return isDarkMode(ctx) ? "#121212" : "#F5F6FA"; }
String getCardColor(Activity ctx)    { return isDarkMode(ctx) ? "#1E1E1E" : "#FFFFFF"; }
String getTextColor(Activity ctx)    { return isDarkMode(ctx) ? "#FFFFFF" : "#222222"; }
String getSubTextColor(Activity ctx) { return isDarkMode(ctx) ? "#BBBBBB" : "#666666"; }
String getStrokeColor(Activity ctx)  { return isDarkMode(ctx) ? "#333333" : "#EAEAEA"; }

// ====================== UI 组件 ======================

int dp(Activity ctx, int v) {
    return (int)(v * ctx.getResources().getDisplayMetrics().density + 0.5f);
}

LinearLayout createCard(Activity ctx) {
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(ctx,14), dp(ctx,14), dp(ctx,14), dp(ctx,14));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.parseColor(getCardColor(ctx)));
    bg.setCornerRadius(dp(ctx, 14));
    bg.setStroke(dp(ctx,1), Color.parseColor(getStrokeColor(ctx)));
    card.setBackground(bg);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, 0, 0, dp(ctx,10)); card.setLayoutParams(lp);
    return card;
}

LinearLayout makeCardLayout(Activity ctx) {
    LinearLayout layout = new LinearLayout(ctx);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dp(ctx,14), dp(ctx,14), dp(ctx,14), dp(ctx,14));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.parseColor(getCardColor(ctx)));
    bg.setCornerRadius(dp(ctx, 12));
    bg.setStroke(dp(ctx,1), Color.parseColor(getStrokeColor(ctx)));
    layout.setBackground(bg);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, 0, 0, dp(ctx,10)); layout.setLayoutParams(lp);
    return layout;
}

Button createModernButton(Activity ctx, String text, String color) {
    Button btn = new Button(ctx);
    btn.setText(text); btn.setTextColor(Color.WHITE); btn.setAllCaps(false); btn.setTextSize(13);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(ctx, 999)); bg.setColor(Color.parseColor(color));
    btn.setBackground(bg);
    btn.setPadding(dp(ctx,18), dp(ctx,8), dp(ctx,18), dp(ctx,8));
    return btn;
}

Button createSmallBtn(Activity ctx, String text, String color) {
    Button btn = new Button(ctx);
    btn.setText(text); btn.setTextColor(Color.WHITE); btn.setAllCaps(false); btn.setTextSize(12);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(ctx, 999)); bg.setColor(Color.parseColor(color));
    btn.setBackground(bg);
    btn.setPadding(dp(ctx,12), dp(ctx,6), dp(ctx,12), dp(ctx,6));
    return btn;
}

Button createTabBtn(Activity ctx, String text, boolean active) {
    Button btn = new Button(ctx);
    btn.setText(text); btn.setAllCaps(false); btn.setTextSize(13);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(ctx, 999));
    if (active) {
        bg.setColor(Color.parseColor("#4A90E2")); btn.setTextColor(Color.WHITE);
    } else {
        bg.setColor(Color.parseColor(getCardColor(ctx)));
        bg.setStroke(dp(ctx,1), Color.parseColor("#4A90E2"));
        btn.setTextColor(Color.parseColor("#4A90E2"));
    }
    btn.setBackground(bg);
    btn.setPadding(dp(ctx,18), dp(ctx,8), dp(ctx,18), dp(ctx,8));
    return btn;
}

LinearLayout.LayoutParams makeTabLp(Activity ctx) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.rightMargin = dp(ctx, 8); return lp;
}

GradientDrawable makeInputBg(Activity ctx) {
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(ctx, 10)); bg.setColor(Color.parseColor(getCardColor(ctx)));
    bg.setStroke(dp(ctx,1), Color.parseColor(getStrokeColor(ctx)));
    return bg;
}

LinearLayout createInfoItem(Activity ctx, String title, String value) {
    LinearLayout layout = new LinearLayout(ctx);
    layout.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(ctx, 12)); bg.setColor(Color.parseColor(getCardColor(ctx)));
    bg.setStroke(dp(ctx,1), Color.parseColor(getStrokeColor(ctx)));
    layout.setBackground(bg);
    layout.setPadding(dp(ctx,14), dp(ctx,14), dp(ctx,14), dp(ctx,14));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, 0, 0, dp(ctx,10)); layout.setLayoutParams(lp);
    TextView tvT = new TextView(ctx);
    tvT.setText(title); tvT.setTextSize(12); tvT.setTypeface(null, Typeface.BOLD);
    tvT.setTextColor(Color.parseColor("#4A90E2")); layout.addView(tvT);
    TextView tvV = new TextView(ctx);
    tvV.setText(value); tvV.setTextSize(14);
    tvV.setTextColor(Color.parseColor(getTextColor(ctx)));
    tvV.setPadding(0, dp(ctx,6), 0, 0); layout.addView(tvV);
    return layout;
}

LinearLayout createCopyInfoItem(final Activity ctx, final String title, final String value) {
    LinearLayout layout = createInfoItem(ctx, title, value);
    layout.setClickable(true); layout.setFocusable(true);
    layout.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
            try {
                ((ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("text", value));
                toast(title + " 已复制");
            } catch (Throwable e) { log("复制异常=" + e); }
        }
    });
    return layout;
}

void setupDialogStyle(Activity ctx, AlertDialog dialog) {
    try {
        android.view.Window w = dialog.getWindow();
        if (w == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 20)); bg.setColor(Color.parseColor(getBgColor(ctx)));
        w.setBackgroundDrawable(bg);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ctx.getWindowManager().getDefaultDisplay().getMetrics(dm);
        w.setLayout((int)(dm.widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
    } catch (Throwable e) {}
}

void styleDialogButtons(Activity ctx, AlertDialog dialog) {
    try {
        Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (pos != null) pos.setTextColor(Color.parseColor("#4A90E2"));
        if (neg != null) neg.setTextColor(Color.parseColor(getSubTextColor(ctx)));
        if (neu != null) neu.setTextColor(Color.parseColor("#FF9800"));
    } catch (Throwable e) {}
}

void dismissDialog(final AlertDialog dialog) {
    try {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { try { dialog.dismiss(); } catch (Throwable e) {} }
        });
    } catch (Throwable e) {}
}

String decodeUrl(String url) {
    try {
        if (TextUtils.isEmpty(url)) return "";
        return URLDecoder.decode(url, "UTF-8");
    } catch (Throwable e) { return url; }
}