import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Editable;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import org.json.JSONObject;

var hookList = new ArrayList()
var hookedKeys = new HashSet()
var historyPath = pluginDir + "/history.jsonl"
var dbPath = pluginDir + "/scan_history_v2.db"
var historyMigratedFlagPath = pluginDir + "/history_sqlite_migrated.flag"
var dbLock = new Object()
var recordQueueLock = new Object()
var recordQueue = new ArrayList()
var recordWriterStarted = false
var maxHistoryCount = 200
var lastRecordKey = ""
var lastRecordAt = 0L
var debugLogEnabled = 0
var debugHitCount = 0
var replayMethod = null
var replayClass = null
var hookStarted = false
var hookFinished = false
var hookCancelled = false
var plusMenuId = 2147483001
var plusMenuInjectLogCount = 0
var cachedPlusMenuData = null
var cachedPlusMenuHost = null
var cachedShareFriendTargets = null
var cachedShareGroupTargets = null
var historyViewMode = 0
var activeHistoryDialog = null

void debugLog(String text) {
    if (debugLogEnabled != 1) return
    log(text)
}

void onLoad() {
    ensureHistoryDb()
    hookCancelled = false
    startRecordWriter()
    startHookWorker()
    debugLog("扫码记录已加载，后台适配中，进程: " + currentProcessName() + "，宿主版本: " + hostVerName + " (" + hostVerCode + ")")
}

void onUnload() {
    hookCancelled = true
    activeHistoryDialog = null
    try {
        synchronized (recordQueueLock) {
            recordQueueLock.notifyAll()
        }
    } catch (Throwable e0) {
    }
    var i = 0
    while (i < hookList.size()) {
        try {
            unhookAny(hookList.get(i))
        } catch (Throwable e) {
        }
        i = i + 1
    }
    hookList.clear()
    hookedKeys.clear()
    hookStarted = false
    hookFinished = false
    recordWriterStarted = false
}

void openSettings() {
    showHistoryDialog()
}

boolean onClickSendBtn(String text) {
    var input = text == null ? "" : text.trim()
    if (!input.equals("扫码记录") && !input.equals("#扫码记录") && !input.equals("扫码记录发送") && !input.equals("扫码记录清空")) return false

    if (input.equals("扫码记录清空")) {
        confirmClearHistory(null)
        return true
    }

    if (input.equals("扫码记录发送")) {
        sendText(getTargetTalker(), buildHistoryText(20))
        return true
    }

    showHistoryDialog()
    return true
}

void resolveAndHookByDexKit() {
    if (hookCancelled) return
    hookHomePlusMenuEntry()
    if (hookCancelled) return
    hookScanBundleMethods()
    if (hookCancelled) return
    hookDecodeRunnableMethods()
    if (hookCancelled) return
    hookGalleryResultRunMethods()
    if (hookCancelled) return
    hookImageResultEventMethods()
    if (hookCancelled) return
    hookMediaSelectMethods()
    if (hookCancelled) return
    resolveReplayMethod()
}

void startHookWorker() {
    try {
        if (hookStarted) {
            debugLog("扫码记录后台 hook 已启动，跳过重复启动")
            return
        }
        hookStarted = true
        hookFinished = false
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(120L)
                    if (hookCancelled) return
                    debugLog("扫码记录后台 hook 开始")
                    resolveAndHookByDexKit()
                    hookFinished = true
                    debugLog("扫码记录后台 hook 完成")
                } catch (Throwable e) {
                    hookFinished = false
                    hookStarted = false
                    debugLog("扫码记录后台 hook 失败: " + e)
                }
            }
        }, "ScanHistoryHookWorker").start()
    } catch (Throwable e) {
        hookStarted = false
        debugLog("启动扫码记录后台 hook 失败: " + e)
    }
}

void hookScanBundleMethods() {
    try {
        var members = findMemberList({"result_content", "result_code_name"})
        var count = 0
        var i = 0
        while (members != null && i < members.size()) {
            var member = members.get(i)
            if (member instanceof Method && canHookBundleMethod((Method) member)) {
                if (hookOnce((Method) member, "bundle")) count = count + 1
            }
            i = i + 1
        }
        debugLog("扫码记录 DexKit: Bundle 结果 hook 数=" + count)
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: 定位 Bundle 结果失败 " + e)
    }
}

void hookGalleryResultRunMethods() {
    var count = 0
    try {
        var members = findMemberList({"result_content", "result_code_name"})
        count = count + hookNoArgVoidCandidates(members, "gallery-result-run")
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: 相册结果 run 定位失败 " + e)
    }
    try {
        var members2 = findMemberList({"resortCodeList no bad code"})
        count = count + hookNoArgVoidCandidates(members2, "gallery-code-run")
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: 相册 code run 定位失败 " + e)
    }
    debugLog("扫码记录 DexKit: 相册结果 run hook 数=" + count)
}

int hookNoArgVoidCandidates(Object members, String kind) {
    var count = 0
    var i = 0
    while (members != null && i < members.size()) {
        var member = members.get(i)
        if (member instanceof Method) {
            var method = (Method) member
            if (canHookNoArgVoidMethod(method)) {
                if (hookOnce(method, kind)) count = count + 1
            }
        }
        i = i + 1
    }
    return count
}

boolean canHookNoArgVoidMethod(Method method) {
    try {
        return method.getReturnType() == Void.TYPE && method.getParameterTypes().length == 0
    } catch (Throwable e) {
        return false
    }
}

void hookDecodeRunnableMethods() {
    var count = 0
    try {
        var members = findMemberList({"result_qbar_result_list"})
        count = count + hookDecodeCandidateMembers(members, "decode-list")
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: result_qbar_result_list 定位失败 " + e)
    }

    try {
        var members2 = findMemberList({"onDecodeSuccess result size"})
        count = count + hookDecodeCandidateMembers(members2, "decode-success")
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: onDecodeSuccess 定位失败 " + e)
    }
    debugLog("扫码记录 DexKit: 解码早期 hook 数=" + count)
}

void hookImageResultEventMethods() {
    var count = 0
    count = count + hookImageResultEventByAnchors({"RecogQBarOfImageFileResultEvent is null."}, "image-event-gallery-null")
    count = count + hookImageResultEventByAnchors({"recog result size:"}, "image-event-gallery-size")
    count = count + hookImageResultEventByAnchors({"not same filepath"}, "image-event-gallery-path")
    count = count + hookImageResultEventByAnchors({"mRecogResultListener callback:"}, "image-event-webview")
    debugLog("扫码记录 DexKit: 图片识别事件 hook 数=" + count)
}

int hookImageResultEventByAnchors(Object anchors, String kind) {
    var count = 0
    try {
        var members = findMemberList(anchors)
        var i = 0
        while (members != null && i < members.size()) {
            var member = members.get(i)
            if (member instanceof Method) {
                var method = (Method) member
                if (canHookImageResultEventMethod(method)) {
                    if (hookOnce(method, kind)) count = count + 1
                }
            }
            i = i + 1
        }
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: 图片识别事件定位失败 " + kind + " " + e)
    }
    return count
}

boolean canHookImageResultEventMethod(Method method) {
    try {
        var pts = method.getParameterTypes()
        if (pts.length != 1) return false
        var name = pts[0].getName()
        if (name.indexOf("RecogQBarOfImageFileResultEvent") >= 0) return true
        if (name.indexOf("IEvent") >= 0) return true
    } catch (Throwable e) {
    }
    return false
}

int hookDecodeCandidateMembers(Object members, String kind) {
    var count = 0
    var i = 0
    while (members != null && i < members.size()) {
        var member = members.get(i)
        if (member instanceof Method) {
            var method = (Method) member
            if (canHookDecodeMethod(method)) {
                if (hookOnce(method, kind)) count = count + 1
            }
        }
        i = i + 1
    }
    return count
}

boolean canHookDecodeMethod(Method method) {
    try {
        if (method.getReturnType() != Void.TYPE) return false
        var pts = method.getParameterTypes()
        if (pts.length == 0) return true
        var i = 0
        while (i < pts.length) {
            if (pts[i] == Bundle.class) return true
            i = i + 1
        }
    } catch (Throwable e) {
    }
    return false
}

boolean canHookBundleMethod(Method method) {
    try {
        var pts = method.getParameterTypes()
        var hasBundle = false
        var i = 0
        while (i < pts.length) {
            if (pts[i] == Bundle.class) hasBundle = true
            i = i + 1
        }
        if (method.getReturnType() == Bundle.class) hasBundle = true
        return hasBundle
    } catch (Throwable e) {
        return false
    }
}

boolean hookOnce(Method method, String kind) {
    try {
        method.setAccessible(true)
        var key = method.toString()
        if (hookedKeys.contains(key)) return false
        hookedKeys.add(key)
        var h = XposedBridge.hookMethod(method, new XC_MethodHook() {
            void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                try {
                    recordFromHookParam(param, kind)
                } catch (Throwable e) {
                    debugLog("记录扫码结果失败: " + e)
                }
            }
        })
        hookList.add(h)
        debugLog("已 hook " + kind + ": " + method)
        return true
    } catch (Throwable e) {
        debugLog("hook 失败 " + method + " : " + e)
        return false
    }
}

void hookHomePlusMenuEntry() {
    var buildCount = 0
    var clickCount = 0
    try {
        var members = findMemberList({"dyna plus config is null, we use default one"})
        var i = 0
        while (members != null && i < members.size()) {
            var member = members.get(i)
            if (member instanceof Method) {
                var method = (Method) member
                if (canHookPlusMenuBuildMethod(method)) {
                    if (hookPlusMenuBuildOnce(method)) buildCount = buildCount + 1
                }
            }
            i = i + 1
        }
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: 主页加号菜单构建定位失败 " + e)
    }

    try {
        var members2 = findMemberList({"processOnItemClick"})
        var j = 0
        while (members2 != null && j < members2.size()) {
            var member2 = members2.get(j)
            if (member2 instanceof Method) {
                var method2 = (Method) member2
                if (canHookPlusMenuClickMethod(method2)) {
                    if (hookPlusMenuClickOnce(method2)) clickCount = clickCount + 1
                }
            }
            j = j + 1
        }
    } catch (Throwable e2) {
        debugLog("扫码记录 DexKit: 主页加号菜单点击定位失败 " + e2)
    }
    debugLog("扫码记录 DexKit: 主页加号入口 build=" + buildCount + ", click=" + clickCount)
}

boolean canHookPlusMenuBuildMethod(Method method) {
    try {
        var pts = method.getParameterTypes()
        if (pts.length != 0) return false
        var rt = method.getReturnType()
        return rt == Boolean.TYPE || rt == Boolean.class || rt == Void.TYPE
    } catch (Throwable e) {
        return false
    }
}

boolean canHookPlusMenuClickMethod(Method method) {
    try {
        if (method.getReturnType() != Void.TYPE) return false
        var pts = method.getParameterTypes()
        if (pts.length != 4) return false
        if (pts[0] != AdapterView.class) return false
        if (pts[1] != View.class) return false
        if (!isIntType(pts[2])) return false
        return pts[3] == Long.TYPE || pts[3] == Long.class
    } catch (Throwable e) {
        return false
    }
}

boolean hookPlusMenuBuildOnce(Method method) {
    try {
        method.setAccessible(true)
        var key = "plus-build:" + method.toString()
        if (hookedKeys.contains(key)) return false
        hookedKeys.add(key)
        var h = XposedBridge.hookMethod(method, new XC_MethodHook() {
            void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                try {
                    appendHomePlusEntry(getAnyField(param, "thisObject"))
                } catch (Throwable e) {
                    debugLog("注入主页加号入口失败: " + e)
                }
            }
        })
        hookList.add(h)
        debugLog("已 hook home-plus-build: " + method)
        return true
    } catch (Throwable e) {
        debugLog("hook 主页加号菜单构建失败 " + method + " : " + e)
        return false
    }
}

boolean hookPlusMenuClickOnce(Method method) {
    try {
        method.setAccessible(true)
        var key = "plus-click:" + method.toString()
        if (hookedKeys.contains(key)) return false
        hookedKeys.add(key)
        var h = XposedBridge.hookMethod(method, new XC_MethodHook() {
            void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                try {
                    var args = (Object[]) getAnyField(param, "args")
                    if (args == null || args.length < 3) return
                    var index = intValue(args[2], -1)
                    var helper = getAnyField(param, "thisObject")
                    var data = getCachedPlusMenuData(helper)
                    if (data == null) data = findPlusMenuData(helper, 0)
                    if (data == null) return
                    cachedPlusMenuData = data
                    var wrapper = getSparseValue(data, index)
                    var item = getPlusItemFromWrapper(wrapper)
                    if (readPlusItemId(item) != plusMenuId) return
                    try {
                        param.setResult(null)
                    } catch (Throwable e) {
                    }
                    tryDismissPlusMenu(helper)
                    showHistoryDialog()
                } catch (Throwable e2) {
                    debugLog("处理主页加号扫码记录点击失败: " + e2)
                }
            }
        })
        hookList.add(h)
        debugLog("已 hook home-plus-click: " + method)
        return true
    } catch (Throwable e) {
        debugLog("hook 主页加号菜单点击失败 " + method + " : " + e)
        return false
    }
}

void unhookAny(Object handle) {
    if (handle == null) return
    try {
        handle.unhook()
        return
    } catch (Throwable e) {
    }
    try {
        unhook(handle)
    } catch (Throwable e2) {
    }
}

void logHookHit(String kind, Method method) {
    try {
        if (debugHitCount >= 20) return
        debugHitCount = debugHitCount + 1
        debugLog("扫码记录命中 " + kind + ": " + method.getDeclaringClass().getName() + "." + method.getName())
    } catch (Throwable e) {
    }
}

void recordFromHookParam(Object param, String kind) {
    try {
        var args = (Object[]) getAnyField(param, "args")
        if (args != null) {
            var i = 0
            while (i < args.length) {
                var arg = args[i]
                recordFromHookObjectLight(arg, kind)
                i = i + 1
            }
        }

        var result = null
        try {
            result = param.getResult()
        } catch (Throwable e) {
        }
        recordFromHookObjectLight(result, kind)
    } catch (Throwable e) {
        debugLog("解析 hook 参数失败: " + e)
    }
}

void recordFromHookObjectLight(Object obj, String kind) {
    if (obj == null) return
    if (obj instanceof Bundle) {
        recordFromBundle((Bundle) obj, kind)
        recordFromBundleQBarList((Bundle) obj, kind)
        return
    }
    if (obj instanceof java.util.List) {
        var list = (java.util.List) obj
        var limit = Math.min(list.size(), 5)
        var i = 0
        while (i < limit) {
            recordFromHookObjectLight(list.get(i), kind)
            i = i + 1
        }
        return
    }
    if (looksLikeQBar(obj)) {
        recordFromQBar(obj, null, kind)
        return
    }
    if (looksLikeImageQBarDataBean(obj)) {
        recordFromImageBean(obj, kind)
        return
    }
    if (looksLikeRecogQBarResultEvent(obj)) {
        recordFromRecogQBarResultEvent(obj, kind)
    }
}

boolean shouldScanHookThisObject(String kind) {
    if (kind == null) return false
    if (kind.equals("gallery-result-run")) return true
    if (kind.equals("gallery-code-run")) return true
    if (kind.equals("decode-success")) return true
    return false
}

void recordFromAnyObject(Object obj, String kind, int depth) {
    if (obj == null || depth < 0) return

    if (obj instanceof Bundle) {
        recordFromBundle((Bundle) obj, kind)
        recordFromBundleQBarList((Bundle) obj, kind)
        return
    }

    if (looksLikeQBar(obj)) {
        recordFromQBar(obj, null, kind)
        return
    }

    if (looksLikeImageQBarDataBean(obj)) {
        recordFromImageBean(obj, kind)
        return
    }

    if (looksLikeRecogQBarResultEvent(obj)) {
        recordFromRecogQBarResultEvent(obj, kind)
    }

    if (obj instanceof java.util.List) {
        var list = (java.util.List) obj
        var limit = Math.min(list.size(), 5)
        var i = 0
        while (i < limit) {
            recordFromAnyObject(list.get(i), kind, depth - 1)
            i = i + 1
        }
        return
    }

    if (depth == 0) return
    scanObjectFields(obj, kind, depth - 1)
}

boolean looksLikeRecogQBarResultEvent(Object obj) {
    try {
        if (obj == null) return false
        return obj.getClass().getName().indexOf("RecogQBarOfImageFileResultEvent") >= 0
    } catch (Throwable e) {
        return false
    }
}

void scanObjectFields(Object obj, String kind, int depth) {
    try {
        var cls = obj.getClass()
        var guard = 0
        while (cls != null && guard < 4) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true)
                        var value = f.get(obj)
                        if (value instanceof Bundle || value instanceof java.util.List || looksLikeQBar(value) || looksLikeImageQBarDataBean(value)) {
                            recordFromAnyObject(value, kind, depth)
                        }
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e) {
    }
}

void hookMediaSelectMethods() {
    var count = 0
    try {
        var members = findMemberList({"select codeString"})
        count = count + hookImageBeanCandidateMembers(members, "media-select")
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: select codeString 定位失败 " + e)
    }

    try {
        var members2 = findMemberList({"codeString"})
        count = count + hookImageBeanCandidateMembers(members2, "media-codeString")
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: codeString 定位失败 " + e)
    }
    debugLog("扫码记录 DexKit: 图片选择 hook 数=" + count)
}

int hookImageBeanCandidateMembers(Object members, String kind) {
    var count = 0
    var i = 0
    while (members != null && i < members.size()) {
        var member = members.get(i)
        if (member instanceof Method) {
            var method = (Method) member
            if (hasImageBeanParam(method)) {
                if (hookOnce(method, kind)) count = count + 1
            }
        }
        i = i + 1
    }
    return count
}

boolean hasImageBeanParam(Method method) {
    try {
        var pts = method.getParameterTypes()
        var i = 0
        while (i < pts.length) {
            var n = pts[i].getName()
            if (n.indexOf("ImageQBarDataBean") >= 0) return true
            i = i + 1
        }
    } catch (Throwable e) {
    }
    return false
}

void resolveReplayMethod() {
    replayMethod = null
    replayClass = null

    if (tryResolveReplay({"[handleCode-dealQBarString]", "qbarString is empty"})) return
    if (tryResolveReplay({"weixin://qr/", "weixin://wxpay/bizpayurl"})) return
    if (tryResolveReplay({"weixin://wxpay/bizpayurl", "weixin://wxpay/pcpayurl"})) return

    debugLog("扫码记录 DexKit: 未找到扫码回放处理器")
}

boolean tryResolveReplay(Object anchors) {
    try {
        var members = findMemberList(anchors)
        var i = 0
        while (members != null && i < members.size()) {
            var member = members.get(i)
            if (member instanceof Method) {
                var method = (Method) member
                if (isReplayCoreMethod(method)) {
                    method.setAccessible(true)
                    replayMethod = method
                    replayClass = method.getDeclaringClass()
                    debugLog("扫码记录 DexKit: 回放处理器=" + method)
                    return true
                }
            }
            i = i + 1
        }
    } catch (Throwable e) {
        debugLog("扫码记录 DexKit: 回放处理器定位失败 " + e)
    }
    return false
}

boolean isReplayCoreMethod(Method method) {
    try {
        if (Modifier.isStatic(method.getModifiers())) return false
        if (method.getReturnType() != Void.TYPE) return false
        var pts = method.getParameterTypes()
        if (pts.length < 14 || pts.length > 18) return false
        if (pts[0] != Activity.class) return false
        if (pts[1] != String.class) return false
        var hasBundle = false
        var hasBool = false
        var i = 2
        while (i < pts.length) {
            if (pts[i] == Bundle.class) hasBundle = true
            if (pts[i] == Boolean.TYPE || pts[i] == Boolean.class) hasBool = true
            i = i + 1
        }
        return hasBundle && hasBool
    } catch (Throwable e) {
        return false
    }
}

void recordFromBundle(Bundle bundle, String fromText) {
    if (bundle == null) return
    var content = stringValue(bundle.getString("result_content", ""))
    if (content.length() == 0) return
    var codeName = stringValue(bundle.getString("result_code_name", ""))
    var codeVersion = bundle.getInt("result_code_version", 0)
    var source = bundle.getInt("qbar_string_scan_source", 0)
    var codeType = codeTypeFromName(codeName)
    saveRecord(content, codeName, codeType, codeVersion, source, fromText)
}

void recordFromBundleQBarList(Bundle bundle, String fromText) {
    if (bundle == null) return
    try {
        var list = bundle.getParcelableArrayList("result_qbar_result_list")
        if (list == null || list.size() == 0) return
        var source = bundle.getInt("qbar_string_scan_source", 0)
        var i = 0
        while (i < list.size() && i < 5) {
            var qbar = list.get(i)
            recordFromQBar(qbar, bundle, fromText + "-list")
            i = i + 1
        }
    } catch (Throwable e) {
    }
}

void recordFromQBar(Object qbar, Bundle bundle, String fromText) {
    if (qbar == null) return
    var codeName = stringValue(getAnyField(qbar, "e"))
    var content = stringValue(getAnyField(qbar, "f"))
    var codeVersion = intValue(getAnyField(qbar, "m"), 0)
    var source = 0
    if (bundle != null) {
        if (content.length() == 0) content = stringValue(bundle.getString("result_content", ""))
        if (codeName.length() == 0) codeName = stringValue(bundle.getString("result_code_name", ""))
        codeVersion = bundle.getInt("result_code_version", codeVersion)
        source = bundle.getInt("qbar_string_scan_source", 0)
    }
    if (content.length() == 0) return
    saveRecord(content, codeName, codeTypeFromName(codeName), codeVersion, source, fromText)
}

void recordFromImageBean(Object bean, String fromText) {
    if (bean == null) return
    var content = stringValue(getAnyField(bean, "d"))
    if (content.length() == 0) return
    var codeName = stringValue(getAnyField(bean, "g"))
    var codeType = intValue(getAnyField(bean, "e"), codeTypeFromName(codeName))
    if (codeName.length() == 0) codeName = codeType == 22 ? "WX_CODE" : "QR_CODE"
    saveRecord(content, codeName, codeType, 0, 1, fromText)
}

void recordFromRecogQBarResultEvent(Object event, String fromText) {
    try {
        var data = getAnyField(event, "g")
        if (data == null) return
        var contents = getAnyField(data, "b")
        if (!(contents instanceof java.util.List)) return

        var codeTypes = getAnyField(data, "c")
        var codeNames = getAnyField(data, "d")
        var list = (java.util.List) contents
        var i = 0
        while (i < list.size() && i < 8) {
            var content = stringValue(list.get(i))
            if (content.length() > 0) {
                var codeName = getListString(codeNames, i, "QR_CODE")
                var codeType = getListInt(codeTypes, i, codeTypeFromName(codeName))
                if (codeName.length() == 0) codeName = codeType == 22 ? "WX_CODE" : "QR_CODE"
                saveRecord(content, codeName, codeType, 0, 1, fromText + "-event")
            }
            i = i + 1
        }
    } catch (Throwable e) {
        debugLog("解析图片识别事件失败: " + e)
    }
}

String getListString(Object obj, int index, String defValue) {
    try {
        if (!(obj instanceof java.util.List)) return defValue
        var list = (java.util.List) obj
        if (index < 0 || index >= list.size()) return defValue
        var value = list.get(index)
        if (value == null) return defValue
        return String.valueOf(value)
    } catch (Throwable e) {
        return defValue
    }
}

int getListInt(Object obj, int index, int defValue) {
    try {
        if (!(obj instanceof java.util.List)) return defValue
        var list = (java.util.List) obj
        if (index < 0 || index >= list.size()) return defValue
        return intValue(list.get(index), defValue)
    } catch (Throwable e) {
        return defValue
    }
}

boolean looksLikeQBar(Object obj) {
    try {
        if (obj == null) return false
        var cls = obj.getClass()
        while (cls != null) {
            var name = cls.getName()
            if (name.indexOf("WxQBarResult") >= 0) return true
            cls = cls.getSuperclass()
        }
        return getAnyField(obj, "f") instanceof String && getAnyField(obj, "e") instanceof String
    } catch (Throwable e) {
        return false
    }
}

boolean looksLikeImageQBarDataBean(Object obj) {
    try {
        if (obj == null) return false
        var name = obj.getClass().getName()
        if (name.indexOf("ImageQBarDataBean") >= 0) return true
        return getAnyField(obj, "d") instanceof String && getAnyField(obj, "r") != null
    } catch (Throwable e) {
        return false
    }
}

void saveRecord(String content, String codeName, int codeType, int codeVersion, int source, String fromText) {
    try {
        var normalized = content == null ? "" : content.trim()
        if (normalized.length() == 0) return

        var now = System.currentTimeMillis()
        var finalCodeName = codeName == null || codeName.length() == 0 ? "QR_CODE" : codeName
        var key = normalized + "|" + finalCodeName + "|" + source
        if (key.equals(lastRecordKey) && now - lastRecordAt < 1500L) return
        lastRecordKey = key
        lastRecordAt = now

        var obj = new JSONObject()
        obj.put("time", now)
        obj.put("content", normalized)
        obj.put("codeName", finalCodeName)
        obj.put("codeType", codeType <= 0 ? codeTypeFromName(finalCodeName) : codeType)
        obj.put("codeVersion", codeVersion)
        obj.put("source", normalizeSource(source))
        obj.put("from", fromText == null ? "" : fromText)
        obj.put("host", hostVerName == null ? "" : hostVerName)
        enqueueRecord(obj)
    } catch (Throwable e) {
        debugLog("加入扫码记录队列失败: " + e)
    }
}

void startRecordWriter() {
    try {
        synchronized (recordQueueLock) {
            if (recordWriterStarted) return
            recordWriterStarted = true
        }
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    var batch = takeRecordBatch()
                    if (batch.size() > 0) {
                        writeRecordBatch(batch)
                    } else if (hookCancelled) {
                        break
                    }
                }
                var rest = takeRecordBatch()
                if (rest.size() > 0) writeRecordBatch(rest)
            }
        }, "ScanHistoryRecordWriter").start()
    } catch (Throwable e) {
        recordWriterStarted = false
        debugLog("启动扫码记录写入线程失败: " + e)
    }
}

void enqueueRecord(JSONObject obj) {
    if (obj == null) return
    synchronized (recordQueueLock) {
        recordQueue.add(obj)
        while (recordQueue.size() > 80) {
            recordQueue.remove(0)
        }
        recordQueueLock.notifyAll()
    }
}

ArrayList takeRecordBatch() {
    var batch = new ArrayList()
    synchronized (recordQueueLock) {
        try {
            if (recordQueue.size() == 0 && !hookCancelled) recordQueueLock.wait(1200L)
        } catch (Throwable e) {
        }
        while (recordQueue.size() > 0 && batch.size() < 24) {
            batch.add(recordQueue.remove(0))
        }
    }
    return batch
}

void writeRecordBatch(ArrayList batch) {
    if (batch == null || batch.size() == 0) return
    var start = System.currentTimeMillis()
    try {
        synchronized (dbLock) {
            var db = openHistoryDb()
            try {
                db.beginTransaction()
                var i = 0
                while (i < batch.size()) {
                    try {
                        var obj = (JSONObject) batch.get(i)
                        insertRecordObject(db, obj)
                    } catch (Throwable e) {
                    }
                    i = i + 1
                }
                trimHistoryDb(db)
                db.setTransactionSuccessful()
            } finally {
                try {
                    db.endTransaction()
                } catch (Throwable e2) {
                }
                try {
                    db.close()
                } catch (Throwable e3) {
                }
            }
        }
        var cost = System.currentTimeMillis() - start
        if (cost > 120L || batch.size() > 1) debugLog("已后台写入扫码记录: " + batch.size() + " 条, " + cost + "ms")
    } catch (Throwable e4) {
        debugLog("后台保存扫码记录失败: " + e4)
    }
}

ArrayList loadRecords() {
    return loadRecords(0)
}

ArrayList loadRecords(boolean favoriteOnly) {
    return loadRecords(favoriteOnly ? 1 : 0)
}

ArrayList loadRecords(int mode) {
    var records = new ArrayList()
    synchronized (dbLock) {
        var db = openHistoryDb()
        Cursor cursor = null
        try {
            var fields = "SELECT time,content,codeName,codeType,codeVersion,source,fromText,host,customTitle,pinned,favorite,(SELECT name FROM auto_rules WHERE length(prefix)>0 AND instr(lower(scan_records.content),lower(prefix))=1 ORDER BY length(prefix) DESC,id ASC LIMIT 1) FROM scan_records "
            var where = ""
            if (mode == 1) where = "WHERE favorite=1 "
            else if (mode == 2) where = "WHERE EXISTS(SELECT 1 FROM auto_rules WHERE length(prefix)>0 AND instr(lower(scan_records.content),lower(prefix))=1) "
            else where = "WHERE NOT EXISTS(SELECT 1 FROM auto_rules WHERE length(prefix)>0 AND instr(lower(scan_records.content),lower(prefix))=1) "
            var sql = fields + where + "ORDER BY pinned DESC,time DESC,id DESC LIMIT ?"
            cursor = db.rawQuery(sql, new String[]{String.valueOf(maxHistoryCount)})
            while (cursor != null && cursor.moveToNext()) {
                try {
                    var obj = new JSONObject()
                    obj.put("time", cursor.getLong(0))
                    obj.put("content", cursor.getString(1))
                    obj.put("codeName", cursor.getString(2))
                    obj.put("codeType", cursor.getInt(3))
                    obj.put("codeVersion", cursor.getInt(4))
                    obj.put("source", cursor.getInt(5))
                    obj.put("from", cursor.getString(6))
                    obj.put("host", cursor.getString(7))
                    obj.put("customTitle", cursor.getString(8))
                    obj.put("pinned", cursor.getInt(9))
                    obj.put("favorite", cursor.getInt(10))
                    obj.put("categoryName", cursor.getString(11))
                    records.add(obj)
                } catch (Throwable e) {
                }
            }
        } catch (Throwable e2) {
            debugLog("读取扫码记录数据库失败: " + e2)
        } finally {
            try {
                if (cursor != null) cursor.close()
            } catch (Throwable e3) {
            }
            try {
                db.close()
            } catch (Throwable e4) {
            }
        }
    }
    return records
}

void writeRecords(ArrayList records) {
    synchronized (dbLock) {
        var db = openHistoryDb()
        try {
            db.beginTransaction()
            db.execSQL("DELETE FROM scan_records")
            var i = records == null ? 0 : records.size() - 1
            while (records != null && i >= 0) {
                try {
                    var obj = (JSONObject) records.get(i)
                    insertRecordObject(db, obj)
                } catch (Throwable e) {
                }
                i = i - 1
            }
            trimHistoryDb(db)
            db.setTransactionSuccessful()
        } finally {
            try {
                db.endTransaction()
            } catch (Throwable e2) {
            }
            try {
                db.close()
            } catch (Throwable e3) {
            }
        }
    }
}

void clearHistory(boolean includeFavorites) {
    try {
        synchronized (dbLock) {
            var db = openHistoryDb()
            try {
                if (includeFavorites) {
                    db.execSQL("DELETE FROM scan_records")
                } else {
                    db.execSQL("DELETE FROM scan_records WHERE favorite=0")
                }
            } finally {
                try {
                    db.close()
                } catch (Throwable e2) {
                }
            }
        }
    } catch (Throwable e) {
        debugLog("清空扫码记录失败: " + e)
    }
}

void confirmClearHistory(final Dialog historyDialog) {
    try {
        var act = getTopActivity()
        if (act == null) return
        var builder = new AlertDialog.Builder(act)
        builder.setTitle("清空扫码记录")
        builder.setMessage("确定要清空扫码记录吗？收藏内容默认保留，这个操作不能撤销。")
        final boolean[] clearFavoritesState = new boolean[]{false}
        final TextView clearFavorites = new TextView(act)
        styleClearFavoritesCheckbox(clearFavorites, false)
        var clearFavoritesText = new TextView(act)
        clearFavoritesText.setText("同时清空收藏内容")
        clearFavoritesText.setTextColor(Color.parseColor("#E53935"))
        clearFavoritesText.setTextSize(15)
        clearFavoritesText.setGravity(Gravity.CENTER_VERTICAL)
        clearFavoritesText.setPadding(dp(4), 0, 0, 0)
        var clearFavoritesRow = new LinearLayout(act)
        clearFavoritesRow.setOrientation(LinearLayout.HORIZONTAL)
        clearFavoritesRow.setGravity(Gravity.CENTER_VERTICAL)
        clearFavoritesRow.setPadding(dp(18), dp(2), dp(18), dp(4))
        var clearFavoritesBoxLp = new LinearLayout.LayoutParams(dp(18), dp(18))
        clearFavoritesBoxLp.rightMargin = dp(7)
        clearFavoritesRow.addView(clearFavorites, clearFavoritesBoxLp)
        clearFavoritesRow.addView(clearFavoritesText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        clearFavorites.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearFavoritesState[0] = !clearFavoritesState[0]
                styleClearFavoritesCheckbox(clearFavorites, clearFavoritesState[0])
            }
        })
        clearFavoritesText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearFavoritesState[0] = !clearFavoritesState[0]
                styleClearFavoritesCheckbox(clearFavorites, clearFavoritesState[0])
            }
        })
        builder.setView(clearFavoritesRow)
        builder.setPositiveButton("清空", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                var includeFavorites = clearFavoritesState[0]
                clearHistory(includeFavorites)
                toast(includeFavorites ? "扫码记录和收藏已清空" : "未收藏的扫码记录已清空")
                refreshHistoryDialog(historyDialog)
            }
        })
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                d.dismiss()
            }
        })
        final AlertDialog dialog = builder.create()
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                styleDangerConfirmDialog(dialog)
            }
        })
        dialog.show()
    } catch (Throwable e) {
        debugLog("打开清空扫码记录确认框失败: " + e)
    }
}

void styleClearFavoritesCheckbox(TextView box, boolean checked) {
    if (box == null) return
    var bg = new GradientDrawable()
    bg.setShape(GradientDrawable.RECTANGLE)
    bg.setCornerRadius(dp(2))
    if (checked) {
        bg.setColor(Color.parseColor("#E53935"))
        bg.setStroke(dp(1), Color.parseColor("#E53935"))
        box.setText("✓")
        box.setTextColor(Color.WHITE)
    } else {
        bg.setColor(Color.TRANSPARENT)
        bg.setStroke(dp(1), Color.parseColor("#9CA3AF"))
        box.setText("")
    }
    box.setTextSize(13)
    box.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    box.setGravity(Gravity.CENTER)
    box.setPadding(0, 0, 0, 0)
    box.setBackground(bg)
}

void deleteRecord(String content) {
    if (content == null || content.length() == 0) return
    try {
        synchronized (dbLock) {
            var db = openHistoryDb()
            try {
                db.execSQL("DELETE FROM scan_records WHERE content=?", new Object[]{content})
            } finally {
                try {
                    db.close()
                } catch (Throwable e2) {
                }
            }
        }
    } catch (Throwable e) {
        debugLog("删除单条扫码记录失败: " + e)
    }
}

void confirmDeleteRecord(final JSONObject item, final Dialog detailDialog, final Dialog historyDialog) {
    try {
        var act = getTopActivity()
        if (act == null || item == null) return
        var builder = new AlertDialog.Builder(act)
        builder.setTitle("删除扫码记录")
        builder.setMessage("确定要删除这条扫码记录吗？这个操作不能撤销。")
        builder.setPositiveButton("删除", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                deleteRecord(item.optString("content", ""))
                toast("扫码记录已删除")
                try {
                    if (detailDialog != null) detailDialog.dismiss()
                } catch (Throwable e) {
                }
                refreshHistoryDialog(historyDialog)
            }
        })
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                d.dismiss()
            }
        })
        final AlertDialog dialog = builder.create()
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                styleDangerConfirmDialog(dialog)
            }
        })
        dialog.show()
    } catch (Throwable e) {
        debugLog("打开删除扫码记录确认框失败: " + e)
    }
}

SQLiteDatabase openHistoryDb() {
    ensureHistoryDbFile()
    var db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
    db.execSQL("CREATE TABLE IF NOT EXISTS scan_records(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,content TEXT NOT NULL UNIQUE,codeName TEXT,codeType INTEGER,codeVersion INTEGER,source INTEGER,fromText TEXT,host TEXT,customTitle TEXT,pinned INTEGER DEFAULT 0,favorite INTEGER DEFAULT 0)")
    db.execSQL("CREATE TABLE IF NOT EXISTS auto_rules(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,prefix TEXT NOT NULL UNIQUE)")
    return db
}

void ensureHistoryDb() {
    synchronized (dbLock) {
        var db = openHistoryDb()
        try {
            trimHistoryDb(db)
        } finally {
            try {
                db.close()
            } catch (Throwable e) {
            }
        }
    }
}

void ensureHistoryDbFile() {
    try {
        var file = new File(dbPath)
        var parent = file.getParentFile()
        if (parent != null && !parent.exists()) parent.mkdirs()
    } catch (Throwable e) {
        debugLog("创建扫码记录数据库目录失败: " + e)
    }
}

void migrateLegacyHistoryIfNeeded(SQLiteDatabase db) {
    try {
        var flag = new File(historyMigratedFlagPath)
        if (flag.exists()) return
        var legacy = loadLegacyRecords()
        var i = legacy.size() - 1
        while (i >= 0) {
            try {
                insertRecordObject(db, (JSONObject) legacy.get(i))
            } catch (Throwable e) {
            }
            i = i - 1
        }
        trimHistoryDb(db)
        try {
            flag.createNewFile()
        } catch (Throwable e2) {
        }
        if (legacy.size() > 0) debugLog("已迁移旧扫码记录到 SQLite: " + legacy.size())
    } catch (Throwable e) {
        debugLog("迁移旧扫码记录失败: " + e)
    }
}

ArrayList loadLegacyRecords() {
    var records = new ArrayList()
    ensureHistoryFile()
    BufferedReader reader = null
    try {
        reader = new BufferedReader(new FileReader(historyPath))
        String line = null
        while ((line = reader.readLine()) != null) {
            line = line.trim()
            if (line.length() == 0) continue
            try {
                records.add(new JSONObject(line))
            } catch (Throwable e) {
            }
        }
    } catch (Throwable e) {
    } finally {
        try {
            if (reader != null) reader.close()
        } catch (Throwable e) {
        }
    }
    return records
}

void insertRecordObject(SQLiteDatabase db, JSONObject obj) {
    if (db == null || obj == null) return
    var content = obj.optString("content", "").trim()
    if (content.length() == 0) return
    var codeName = obj.optString("codeName", "QR_CODE")
    if (codeName == null || codeName.length() == 0) codeName = "QR_CODE"
    var meta = loadRecordMeta(db, content)
    var customTitle = meta.optString("customTitle", obj.optString("customTitle", ""))
    var pinned = meta.optInt("pinned", obj.optInt("pinned", 0))
    var favorite = meta.optInt("favorite", obj.optInt("favorite", 0))
    db.delete("scan_records", "content = ?", new String[]{content})
    db.execSQL(
        "INSERT OR REPLACE INTO scan_records(time,content,codeName,codeType,codeVersion,source,fromText,host,customTitle,pinned,favorite) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        new Object[]{
            new Long(obj.optLong("time", System.currentTimeMillis())),
            content,
            codeName,
            new Integer(obj.optInt("codeType", codeTypeFromName(codeName))),
            new Integer(obj.optInt("codeVersion", 0)),
            new Integer(normalizeSource(obj.optInt("source", 0))),
            obj.optString("from", ""),
            obj.optString("host", hostVerName == null ? "" : hostVerName),
            customTitle,
            new Integer(pinned),
            new Integer(favorite)
        }
    )
}

JSONObject loadRecordMeta(SQLiteDatabase db, String content) {
    var meta = new JSONObject()
    Cursor cursor = null
    try {
        cursor = db.rawQuery("SELECT customTitle,pinned,favorite FROM scan_records WHERE content=? LIMIT 1", new String[]{content})
        if (cursor != null && cursor.moveToNext()) {
            meta.put("customTitle", cursor.getString(0))
            meta.put("pinned", cursor.getInt(1))
            meta.put("favorite", cursor.getInt(2))
        }
    } catch (Throwable e) {
    } finally {
        try {
            if (cursor != null) cursor.close()
        } catch (Throwable e2) {
        }
    }
    return meta
}

void trimHistoryDb(SQLiteDatabase db) {
    try {
        db.execSQL("DELETE FROM scan_records WHERE id NOT IN (SELECT id FROM scan_records ORDER BY pinned DESC,time DESC,id DESC LIMIT " + maxHistoryCount + ")")
    } catch (Throwable e) {
        debugLog("裁剪扫码记录数据库失败: " + e)
    }
}

void ensureHistoryFile() {
    try {
        var file = new File(historyPath)
        var parent = file.getParentFile()
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (!file.exists()) file.createNewFile()
    } catch (Throwable e) {
        debugLog("创建扫码记录文件失败: " + e)
    }
}

ArrayList loadAutoRules() {
    var rules = new ArrayList()
    synchronized (dbLock) {
        var db = openHistoryDb()
        Cursor cursor = null
        try {
            cursor = db.rawQuery("SELECT id,name,prefix FROM auto_rules ORDER BY id ASC", new String[]{})
            while (cursor != null && cursor.moveToNext()) {
                var rule = new JSONObject()
                rule.put("id", cursor.getLong(0))
                rule.put("name", cursor.getString(1))
                rule.put("prefix", cursor.getString(2))
                rules.add(rule)
            }
        } catch (Throwable e) {
            debugLog("读取自动归类规则失败: " + e)
        } finally {
            try { if (cursor != null) cursor.close() } catch (Throwable e2) {}
            try { db.close() } catch (Throwable e3) {}
        }
    }
    return rules
}

void saveAutoRule(long id, String name, String prefix) {
    synchronized (dbLock) {
        var db = openHistoryDb()
        try {
            db.beginTransaction()
            db.execSQL("DELETE FROM auto_rules WHERE lower(prefix)=lower(?) AND id<>?", new Object[]{prefix, new Long(id)})
            if (id > 0L) {
                db.execSQL("UPDATE auto_rules SET name=?,prefix=? WHERE id=?", new Object[]{name, prefix, new Long(id)})
            } else {
                db.execSQL("INSERT INTO auto_rules(name,prefix) VALUES(?,?)", new Object[]{name, prefix})
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (Throwable e) {}
            try { db.close() } catch (Throwable e2) {}
        }
    }
}

void deleteAutoRule(long id) {
    if (id <= 0L) return
    synchronized (dbLock) {
        var db = openHistoryDb()
        try {
            db.execSQL("DELETE FROM auto_rules WHERE id=?", new Object[]{new Long(id)})
        } finally {
            try { db.close() } catch (Throwable e) {}
        }
    }
}

View buildAutoRulesPanel(Activity act, final Dialog historyDialog) {
    final ArrayList rules = loadAutoRules()
    var card = new LinearLayout(act)
    card.setOrientation(LinearLayout.VERTICAL)
    card.setPadding(dp(14), dp(12), dp(14), dp(10))
    var bg = new GradientDrawable()
    bg.setColor(Color.parseColor("#FFFFFF"))
    bg.setStroke(1, Color.parseColor("#D9E5F5"))
    bg.setCornerRadius(dp(8))
    card.setBackground(bg)
    var cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    cardLp.setMargins(0, 0, 0, dp(12))
    card.setLayoutParams(cardLp)

    var header = new LinearLayout(act)
    header.setOrientation(LinearLayout.HORIZONTAL)
    header.setGravity(Gravity.CENTER_VERTICAL)
    card.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))

    var title = new TextView(act)
    title.setText("归类规则  " + rules.size() + " 条")
    title.setTextSize(15)
    title.setTypeface(Typeface.DEFAULT_BOLD)
    title.setTextColor(Color.parseColor("#1F2937"))
    header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1))
    title.setGravity(Gravity.CENTER_VERTICAL)

    var add = new TextView(act)
    add.setText("+ 添加规则")
    add.setTextSize(14)
    add.setTypeface(Typeface.DEFAULT_BOLD)
    add.setTextColor(Color.parseColor("#2F7DF6"))
    add.setGravity(Gravity.CENTER)
    header.addView(add, new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.MATCH_PARENT))
    add.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            showAutoRuleEditDialog(null, historyDialog)
        }
    })

    if (rules.size() == 0) {
        var hint = new TextView(act)
        hint.setText("添加名称和链接前缀后，匹配记录将自动移入此页。")
        hint.setTextSize(13)
        hint.setTextColor(Color.parseColor("#7A8598"))
        hint.setPadding(0, dp(5), 0, dp(3))
        card.addView(hint)
    } else {
        var i = 0
        while (i < rules.size()) {
            card.addView(buildAutoRuleRow(act, (JSONObject) rules.get(i), historyDialog))
            i = i + 1
        }
    }
    return card
}

View buildAutoRuleRow(Activity act, final JSONObject rule, final Dialog historyDialog) {
    var row = new LinearLayout(act)
    row.setOrientation(LinearLayout.HORIZONTAL)
    row.setGravity(Gravity.CENTER_VERTICAL)
    row.setPadding(dp(10), dp(8), dp(6), dp(8))
    var bg = new GradientDrawable()
    bg.setColor(Color.parseColor("#F7FAFF"))
    bg.setCornerRadius(dp(6))
    row.setBackground(bg)
    var lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.topMargin = dp(7)
    row.setLayoutParams(lp)

    var texts = new LinearLayout(act)
    texts.setOrientation(LinearLayout.VERTICAL)
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1))
    var name = new TextView(act)
    name.setText(rule.optString("name", "未命名规则"))
    name.setTextSize(14)
    name.setTypeface(Typeface.DEFAULT_BOLD)
    name.setTextColor(Color.parseColor("#1F2937"))
    texts.addView(name)
    var prefix = new TextView(act)
    prefix.setText(rule.optString("prefix", ""))
    prefix.setTextSize(12)
    prefix.setTextColor(Color.parseColor("#667085"))
    prefix.setSingleLine(true)
    prefix.setEllipsize(TextUtils.TruncateAt.END)
    prefix.setPadding(0, dp(3), 0, 0)
    texts.addView(prefix)

    var delete = new TextView(act)
    delete.setText("删除")
    delete.setTextSize(13)
    delete.setTextColor(Color.parseColor("#E53935"))
    delete.setGravity(Gravity.CENTER)
    row.addView(delete, new LinearLayout.LayoutParams(dp(54), dp(42)))
    texts.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            showAutoRuleEditDialog(rule, historyDialog)
        }
    })
    delete.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            confirmDeleteAutoRule(rule, historyDialog)
        }
    })
    return row
}

void showAutoRuleEditDialog(final JSONObject rule, final Dialog historyDialog) {
    try {
        final Activity act = getTopActivity()
        if (act == null) return
        var root = new LinearLayout(act)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setPadding(dp(8), dp(8), dp(8), 0)
        var nameLabel = new TextView(act)
        nameLabel.setText("归类名称")
        nameLabel.setTextColor(Color.parseColor("#374151"))
        nameLabel.setTextSize(14)
        root.addView(nameLabel)
        final EditText nameEdit = createShareSearchEdit(act, "例如：极兔")
        nameEdit.setText(rule == null ? "" : rule.optString("name", ""))
        nameEdit.setSingleLine(true)
        var nameLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        nameLp.topMargin = dp(7)
        root.addView(nameEdit, nameLp)

        var prefixLabel = new TextView(act)
        prefixLabel.setText("链接前缀")
        prefixLabel.setTextColor(Color.parseColor("#374151"))
        prefixLabel.setTextSize(14)
        prefixLabel.setPadding(0, dp(14), 0, 0)
        root.addView(prefixLabel)
        final EditText prefixEdit = createShareSearchEdit(act, "例如：https://mp.jtexpress.com/")
        prefixEdit.setText(rule == null ? "" : rule.optString("prefix", ""))
        prefixEdit.setSingleLine(true)
        var prefixLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        prefixLp.topMargin = dp(7)
        root.addView(prefixEdit, prefixLp)

        var builder = new AlertDialog.Builder(act)
        builder.setTitle(rule == null ? "添加归类规则" : "编辑归类规则")
        builder.setView(root)
        builder.setPositiveButton("保存", null)
        builder.setNegativeButton("取消", null)
        final AlertDialog dialog = builder.create()
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                styleShareAlertDialog(dialog)
                var save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                if (save != null) save.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        var name = nameEdit.getText() == null ? "" : nameEdit.getText().toString().trim()
                        var prefix = prefixEdit.getText() == null ? "" : prefixEdit.getText().toString().trim()
                        if (name.length() == 0) {
                            Toast.makeText(act.getApplicationContext(), "请输入归类名称", Toast.LENGTH_SHORT).show()
                            return
                        }
                        if (prefix.length() == 0) {
                            Toast.makeText(act.getApplicationContext(), "请输入链接前缀", Toast.LENGTH_SHORT).show()
                            return
                        }
                        if (name.length() > 30) name = name.substring(0, 30)
                        if (prefix.length() > 500) prefix = prefix.substring(0, 500)
                        try {
                            saveAutoRule(rule == null ? 0L : rule.optLong("id", 0L), name, prefix)
                            dialog.dismiss()
                            refreshHistoryDialog(historyDialog)
                        } catch (Throwable e2) {
                            debugLog("保存自动归类规则失败: " + e2)
                            Toast.makeText(act.getApplicationContext(), "保存规则失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        })
        dialog.show()
    } catch (Throwable e) {
        debugLog("打开自动归类规则编辑框失败: " + e)
    }
}

void confirmDeleteAutoRule(final JSONObject rule, final Dialog historyDialog) {
    var act = getTopActivity()
    if (act == null || rule == null) return
    new AlertDialog.Builder(act)
        .setTitle("删除归类规则")
        .setMessage("删除“" + rule.optString("name", "") + "”规则？记录不会被删除，将重新出现在全部记录中。")
        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                deleteAutoRule(rule.optLong("id", 0L))
                refreshHistoryDialog(historyDialog)
            }
        })
        .setNegativeButton("取消", null)
        .show()
}

void showHistoryDialog() {
    var act = getTopActivity()
    if (act == null) {
        toast("当前没有可用界面")
        return
    }
    try {
        if (activeHistoryDialog != null && activeHistoryDialog.isShowing()) {
            renderHistoryDialog(activeHistoryDialog, act)
            return
        }
    } catch (Throwable e0) {
        activeHistoryDialog = null
    }

    final Dialog dialog = new Dialog(act)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    activeHistoryDialog = dialog
    dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
        public void onDismiss(DialogInterface d) {
            if (activeHistoryDialog == dialog) activeHistoryDialog = null
        }
    })
    renderHistoryDialog(dialog, act)
    dialog.show()
    styleHistoryWindow(dialog)
}

void refreshHistoryDialog(Dialog dialog) {
    try {
        if (dialog == null || !dialog.isShowing()) {
            showHistoryDialog()
            return
        }
        var act = getTopActivity()
        if (act == null) return
        renderHistoryDialog(dialog, act)
    } catch (Throwable e) {
        debugLog("原地刷新扫码记录界面失败: " + e)
    }
}

void renderHistoryDialog(final Dialog dialog, Activity act) {
    if (dialog == null || act == null) return

    var records = loadRecords(historyViewMode)

    var page = new LinearLayout(act)
    page.setOrientation(LinearLayout.VERTICAL)
    page.setBackgroundColor(Color.WHITE)

    var top = new LinearLayout(act)
    top.setOrientation(LinearLayout.HORIZONTAL)
    top.setGravity(Gravity.CENTER_VERTICAL)
    top.setPadding(dp(10), dp(18), dp(14), 0)
    page.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))

    var back = new TextView(act)
    back.setText("<")
    back.setTextSize(30)
    back.setTextColor(Color.parseColor("#111827"))
    back.setGravity(Gravity.CENTER)
    top.addView(back, new LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.MATCH_PARENT))
    back.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            dialog.dismiss()
        }
    })

    top.addView(buildTopFilterTabs(act, dialog), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1))

    var clear = new TextView(act)
    clear.setText("清空记录")
    clear.setTextSize(15)
    clear.setTextColor(Color.parseColor("#30343B"))
    clear.setGravity(Gravity.CENTER)
    top.addView(clear, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.MATCH_PARENT))
    clear.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            confirmClearHistory(dialog)
        }
    })

    var scroll = new ScrollView(act)
    var root = new LinearLayout(act)
    root.setOrientation(LinearLayout.VERTICAL)
    root.setPadding(dp(18), 0, dp(18), dp(28))
    scroll.addView(root)
    page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1))

    if (historyViewMode == 2) root.addView(buildAutoRulesPanel(act, dialog))
    root.addView(buildSummaryCard(act, records.size()))

    if (records.size() == 0) {
        root.addView(buildEmptyCard(act))
    } else {
        var limit = Math.min(records.size(), 80)
        var i = 0
        while (i < limit) {
            final JSONObject item = (JSONObject) records.get(i)
            root.addView(buildRecordRow(act, item, i + 1, dialog))
            i = i + 1
        }
        var more = new TextView(act)
        more.setText("- 没有更多记录了 -")
        more.setTextSize(13)
        more.setGravity(Gravity.CENTER)
        more.setTextColor(Color.parseColor("#9AA3AF"))
        more.setPadding(0, dp(12), 0, dp(4))
        root.addView(more, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
    }

    dialog.setContentView(page)
}

void styleHistoryWindow(Dialog dialog) {
    try {
        var window = dialog.getWindow()
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE))
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            try {
                window.setStatusBarColor(Color.WHITE)
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            } catch (Throwable e2) {
            }
        }
    } catch (Throwable e) {
    }
}

View buildTopFilterTabs(Activity act, final Dialog dialog) {
    var wrap = new LinearLayout(act)
    wrap.setOrientation(LinearLayout.HORIZONTAL)
    wrap.setGravity(Gravity.CENTER)
    wrap.setPadding(dp(4), dp(15), dp(4), dp(13))

    var all = topFilterTab(act, "全部记录", historyViewMode == 0)
    var auto = topFilterTab(act, "自动归类", historyViewMode == 2)
    var fav = topFilterTab(act, "收藏", historyViewMode == 1)
    wrap.addView(all, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1))
    var gap = new TextView(act)
    wrap.addView(gap, new LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT))
    wrap.addView(auto, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1))
    var gap2 = new TextView(act)
    wrap.addView(gap2, new LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT))
    wrap.addView(fav, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1))

    all.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (historyViewMode == 0) return
            historyViewMode = 0
            refreshHistoryDialog(dialog)
        }
    })
    auto.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (historyViewMode == 2) return
            historyViewMode = 2
            refreshHistoryDialog(dialog)
        }
    })
    fav.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (historyViewMode == 1) return
            historyViewMode = 1
            refreshHistoryDialog(dialog)
        }
    })
    return wrap
}

TextView topFilterTab(Activity act, String text, boolean selected) {
    var tv = new TextView(act)
    tv.setText(text)
    tv.setTextSize(15)
    tv.setTypeface(Typeface.DEFAULT_BOLD)
    tv.setGravity(Gravity.CENTER)
    tv.setTextColor(Color.parseColor(selected ? "#FFFFFF" : "#2F7DF6"))
    var bg = new GradientDrawable()
    bg.setCornerRadius(dp(8))
    bg.setColor(Color.parseColor(selected ? "#2F7DF6" : "#F3F7FF"))
    bg.setStroke(1, Color.parseColor(selected ? "#2F7DF6" : "#C9DAF2"))
    tv.setBackground(bg)
    return tv
}

View buildSummaryCard(Activity act, int count) {
    var card = new LinearLayout(act)
    card.setOrientation(LinearLayout.HORIZONTAL)
    card.setGravity(Gravity.CENTER_VERTICAL)
    card.setPadding(dp(16), 0, dp(14), 0)

    var bg = new GradientDrawable()
    bg.setColor(Color.parseColor("#F2F7FF"))
    bg.setCornerRadius(dp(8))
    card.setBackground(bg)

    var lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
    lp.setMargins(0, 0, 0, dp(14))
    card.setLayoutParams(lp)

    card.addView(roundText(act, "◔", "#E8F1FF", "#2F7DF6", 30, 14))

    var texts = new LinearLayout(act)
    texts.setOrientation(LinearLayout.VERTICAL)
    texts.setPadding(dp(14), 0, 0, 0)
    card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1))

    var line1 = new TextView(act)
    if (historyViewMode == 1) line1.setText("共 " + count + " 条收藏记录")
    else if (historyViewMode == 2) line1.setText("共 " + count + " 条自动归类记录")
    else line1.setText("共 " + count + " 条扫码记录")
    line1.setTextSize(16)
    line1.setTypeface(Typeface.DEFAULT_BOLD)
    line1.setTextColor(Color.parseColor("#1F2937"))
    texts.addView(line1)

    return card
}

View buildEmptyCard(Activity act) {
    var empty = new TextView(act)
    empty.setText(historyViewMode == 2 ? "暂无自动归类记录" : (historyViewMode == 1 ? "暂无收藏记录" : "暂无扫码记录"))
    empty.setTextSize(16)
    empty.setGravity(Gravity.CENTER)
    empty.setTextColor(Color.parseColor("#6B7280"))
    var bg = new GradientDrawable()
    bg.setColor(Color.WHITE)
    bg.setStroke(1, Color.parseColor("#EDF0F4"))
    bg.setCornerRadius(dp(8))
    empty.setBackground(bg)
    var lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(128))
    lp.setMargins(0, 0, 0, dp(12))
    empty.setLayoutParams(lp)
    return empty
}

View buildRecordRow(Activity act, final JSONObject item, int index, final Dialog dialog) {
    var row = new LinearLayout(act)
    row.setOrientation(LinearLayout.HORIZONTAL)
    row.setGravity(Gravity.CENTER_VERTICAL)
    row.setPadding(dp(18), dp(14), dp(14), dp(14))

    var bg = new GradientDrawable()
    var pinned = item.optInt("pinned", 0) == 1
    bg.setColor(Color.parseColor(pinned ? "#FFFBEB" : "#FFFFFF"))
    bg.setStroke(1, Color.parseColor(pinned ? "#F7DFAE" : "#EDF0F4"))
    bg.setCornerRadius(dp(8))
    row.setBackground(bg)

    var lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.setMargins(0, 0, 0, dp(12))
    row.setLayoutParams(lp)

    var tag = recordTag(item)
    var iconView = roundText(act, recordIconText(tag), recordIconBg(tag), "#FFFFFF", 54, 20)
    iconView.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            replayRecord(item)
        }
    })
    row.addView(iconView)

    var middle = new LinearLayout(act)
    middle.setOrientation(LinearLayout.VERTICAL)
    middle.setPadding(dp(14), 0, dp(8), 0)
    row.addView(middle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1))

    var titleLine = new LinearLayout(act)
    titleLine.setOrientation(LinearLayout.HORIZONTAL)
    titleLine.setGravity(Gravity.CENTER_VERTICAL)
    middle.addView(titleLine)

    if (item.optInt("favorite", 0) == 1) {
        var favoriteIcon = new TextView(act)
        favoriteIcon.setText("⭐")
        favoriteIcon.setTextSize(17)
        favoriteIcon.setGravity(Gravity.CENTER)
        var favoriteLp = new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT)
        favoriteLp.setMargins(0, 0, dp(3), 0)
        titleLine.addView(favoriteIcon, favoriteLp)
    }

    var title = new TextView(act)
    title.setText(recordTitle(item, index))
    title.setTextSize(17)
    title.setTypeface(Typeface.DEFAULT_BOLD)
    title.setTextColor(Color.parseColor("#111827"))
    title.setSingleLine(true)
    title.setEllipsize(TextUtils.TruncateAt.END)
    titleLine.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1))
    title.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            showRecordEditDialog(item, dialog)
        }
    })

    var content = new TextView(act)
    content.setText(shortText(item.optString("content", ""), 34))
    content.setTextSize(14)
    content.setTextColor(Color.parseColor("#667085"))
    content.setSingleLine(true)
    content.setEllipsize(TextUtils.TruncateAt.END)
    content.setPadding(0, dp(5), 0, 0)
    middle.addView(content)

    var meta = new LinearLayout(act)
    meta.setOrientation(LinearLayout.HORIZONTAL)
    meta.setGravity(Gravity.CENTER_VERTICAL)
    meta.setPadding(0, dp(8), 0, 0)
    middle.addView(meta)

    var categoryName = item.optString("categoryName", "")
    if (categoryName.length() > 0) {
        meta.addView(tagView(act, categoryName, "#E8F1FF", "#2563EB"))
        var categoryGap = new TextView(act)
        meta.addView(categoryGap, new LinearLayout.LayoutParams(dp(6), dp(1)))
    }
    meta.addView(tagView(act, tag, recordTagBg(tag), recordTagColor(tag)))

    var time = new TextView(act)
    time.setText(formatFullTime(item.optLong("time", 0L)))
    time.setTextSize(13)
    time.setTextColor(Color.parseColor("#778195"))
    time.setPadding(dp(12), 0, 0, 0)
    meta.addView(time)

    var right = new LinearLayout(act)
    right.setOrientation(LinearLayout.VERTICAL)
    right.setGravity(Gravity.CENTER)
    row.addView(right, new LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT))

    var viewBtn = new TextView(act)
    viewBtn.setText("查看")
    viewBtn.setTextSize(15)
    viewBtn.setTypeface(Typeface.DEFAULT_BOLD)
    viewBtn.setTextColor(Color.parseColor("#2F7DF6"))
    viewBtn.setGravity(Gravity.CENTER)
    var btnBg = new GradientDrawable()
    btnBg.setColor(Color.WHITE)
    btnBg.setStroke(1, Color.parseColor("#B9CCE4"))
    btnBg.setCornerRadius(dp(6))
    viewBtn.setBackground(btnBg)
    var viewLp = new LinearLayout.LayoutParams(dp(72), dp(38))
    right.addView(viewBtn, viewLp)

    viewBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            showQrDialog(item, dialog)
        }
    })
    row.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            showQrDialog(item, dialog)
        }
    })
    final String longPressContent = item.optString("content", "")
    final Handler recordPressHandler = new Handler(Looper.getMainLooper())
    final boolean[] recordPressCopied = new boolean[]{false}
    final boolean[] recordPressMoved = new boolean[]{false}
    final float[] recordPressStart = new float[]{0f, 0f}
    final View recordPressView = middle
    final Runnable recordPressCopyTask = new Runnable() {
        public void run() {
            recordPressCopied[0] = true
            copyTextFromView(recordPressView, longPressContent)
        }
    }
    middle.setOnTouchListener(new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            if (event == null) return true
            var action = event.getAction()
            if (action == MotionEvent.ACTION_DOWN) {
                recordPressCopied[0] = false
                recordPressMoved[0] = false
                recordPressStart[0] = event.getX()
                recordPressStart[1] = event.getY()
                recordPressHandler.removeCallbacks(recordPressCopyTask)
                recordPressHandler.postDelayed(recordPressCopyTask, 600L)
                return true
            }
            if (action == MotionEvent.ACTION_MOVE) {
                var dx = Math.abs(event.getX() - recordPressStart[0])
                var dy = Math.abs(event.getY() - recordPressStart[1])
                if (dx > dp(12) || dy > dp(12)) {
                    recordPressMoved[0] = true
                    recordPressHandler.removeCallbacks(recordPressCopyTask)
                }
                return true
            }
            if (action == MotionEvent.ACTION_UP) {
                recordPressHandler.removeCallbacks(recordPressCopyTask)
                if (!recordPressCopied[0] && !recordPressMoved[0]) showQrDialog(item, dialog)
                return true
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                recordPressHandler.removeCallbacks(recordPressCopyTask)
                recordPressCopied[0] = false
                recordPressMoved[0] = false
                return true
            }
            return true
        }
    })

    return row
}

void showRecordEditDialog(final JSONObject item, final Dialog historyDialog) {
    try {
        var act = getTopActivity()
        if (act == null || item == null) return
        var root = new LinearLayout(act)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setPadding(dp(8), dp(8), dp(8), 0)

        var label = new TextView(act)
        label.setText("自定义名称")
        label.setTextSize(14)
        label.setTextColor(Color.parseColor("#374151"))
        label.setPadding(0, 0, 0, dp(8))
        root.addView(label)

        final EditText nameEdit = createShareSearchEdit(act, recordTitle(item, 1))
        nameEdit.setText(item.optString("customTitle", ""))
        nameEdit.setSingleLine(true)
        root.addView(nameEdit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        var toggles = new LinearLayout(act)
        toggles.setOrientation(LinearLayout.HORIZONTAL)
        toggles.setPadding(0, dp(14), 0, dp(2))
        root.addView(toggles, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)))

        final boolean[] pinnedState = new boolean[]{item.optInt("pinned", 0) == 1}
        final boolean[] favoriteState = new boolean[]{item.optInt("favorite", 0) == 1}
        final TextView pinnedBtn = recordMetaToggleButton(act, "置顶", pinnedState[0], "#F59E0B")
        final TextView favoriteBtn = recordMetaToggleButton(act, "收藏", favoriteState[0], "#E11D48")
        toggles.addView(pinnedBtn, new LinearLayout.LayoutParams(0, dp(42), 1))
        var gap = new TextView(act)
        toggles.addView(gap, new LinearLayout.LayoutParams(dp(10), dp(42)))
        toggles.addView(favoriteBtn, new LinearLayout.LayoutParams(0, dp(42), 1))

        pinnedBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pinnedState[0] = !pinnedState[0]
                styleRecordMetaToggle(pinnedBtn, "置顶", pinnedState[0], "#F59E0B")
            }
        })
        favoriteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                favoriteState[0] = !favoriteState[0]
                styleRecordMetaToggle(favoriteBtn, "收藏", favoriteState[0], "#E11D48")
            }
        })

        var builder = new AlertDialog.Builder(act)
        builder.setTitle("修改二维码记录")
        builder.setView(root)
        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                var title = nameEdit.getText() == null ? "" : nameEdit.getText().toString().trim()
                if (title.length() > 60) title = title.substring(0, 60)
                updateRecordMeta(item.optString("content", ""), title, pinnedState[0] ? 1 : 0, favoriteState[0] ? 1 : 0)
                refreshHistoryDialog(historyDialog)
            }
        })
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                d.dismiss()
            }
        })
        final AlertDialog dialog = builder.create()
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                styleShareAlertDialog(dialog)
            }
        })
        dialog.show()
    } catch (Throwable e) {
        debugLog("打开二维码记录编辑失败: " + e)
        toast("打开编辑失败")
    }
}

TextView recordMetaToggleButton(Activity act, String text, boolean selected, String color) {
    var tv = new TextView(act)
    tv.setTextSize(14)
    tv.setTypeface(Typeface.DEFAULT_BOLD)
    tv.setGravity(Gravity.CENTER)
    styleRecordMetaToggle(tv, text, selected, color)
    return tv
}

void styleRecordMetaToggle(TextView tv, String text, boolean selected, String color) {
    if (tv == null) return
    tv.setText((selected ? "● " : "○ ") + text)
    tv.setTextColor(Color.parseColor(selected ? "#FFFFFF" : color))
    var bg = new GradientDrawable()
    bg.setCornerRadius(dp(8))
    bg.setColor(Color.parseColor(selected ? color : "#FFFFFF"))
    bg.setStroke(1, Color.parseColor(color))
    tv.setBackground(bg)
}

void updateRecordMeta(String content, String customTitle, int pinned, int favorite) {
    if (content == null || content.length() == 0) return
    try {
        synchronized (dbLock) {
            var db = openHistoryDb()
            try {
                db.execSQL(
                    "UPDATE scan_records SET customTitle=?,pinned=?,favorite=? WHERE content=?",
                    new Object[]{customTitle == null ? "" : customTitle, new Integer(pinned), new Integer(favorite), content}
                )
            } finally {
                try {
                    db.close()
                } catch (Throwable e2) {
                }
            }
        }
    } catch (Throwable e) {
        debugLog("更新扫码记录备注失败: " + e)
    }
}

View buildBottomTabs(Activity act) {
    var tabs = new LinearLayout(act)
    tabs.setOrientation(LinearLayout.HORIZONTAL)
    tabs.setGravity(Gravity.CENTER_VERTICAL)
    tabs.setPadding(0, dp(4), 0, dp(10))
    tabs.setBackgroundColor(Color.WHITE)
    var half = act.getResources().getDisplayMetrics().widthPixels / 2

    var left = new LinearLayout(act)
    left.setOrientation(LinearLayout.VERTICAL)
    left.setGravity(Gravity.CENTER)
    left.addView(roundText(act, "◷", "#E8F1FF", "#2F7DF6", 36, 18))
    var leftText = new TextView(act)
    leftText.setText("扫码记录")
    leftText.setTextSize(13)
    leftText.setTextColor(Color.parseColor("#2F7DF6"))
    leftText.setPadding(0, dp(4), 0, 0)
    leftText.setGravity(Gravity.CENTER)
    left.addView(leftText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    var right = new LinearLayout(act)
    right.setOrientation(LinearLayout.VERTICAL)
    right.setGravity(Gravity.CENTER)
    right.addView(roundText(act, "●", "#F2F4F7", "#A8B0BD", 36, 15))
    var rightText = new TextView(act)
    rightText.setText("我的")
    rightText.setTextSize(13)
    rightText.setTextColor(Color.parseColor("#8A94A6"))
    rightText.setPadding(0, dp(4), 0, 0)
    rightText.setGravity(Gravity.CENTER)
    right.addView(rightText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    tabs.addView(left, new LinearLayout.LayoutParams(half, LinearLayout.LayoutParams.MATCH_PARENT))
    tabs.addView(right, new LinearLayout.LayoutParams(half, LinearLayout.LayoutParams.MATCH_PARENT))
    return tabs
}

TextView roundText(Activity act, String text, String bgColor, String textColor, int sizeDp, int textSp) {
    var tv = new TextView(act)
    tv.setText(text)
    tv.setTextSize(textSp)
    tv.setTypeface(Typeface.DEFAULT_BOLD)
    tv.setTextColor(Color.parseColor(textColor))
    tv.setGravity(Gravity.CENTER)
    var bg = new GradientDrawable()
    bg.setShape(GradientDrawable.OVAL)
    bg.setColor(Color.parseColor(bgColor))
    tv.setBackground(bg)
    tv.setSingleLine(true)
    tv.setIncludeFontPadding(false)
    tv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
    return tv
}

TextView tagView(Activity act, String text, String bgColor, String textColor) {
    var tv = new TextView(act)
    tv.setText(text)
    tv.setTextSize(12)
    tv.setTextColor(Color.parseColor(textColor))
    tv.setGravity(Gravity.CENTER)
    tv.setPadding(dp(7), dp(2), dp(7), dp(2))
    var bg = new GradientDrawable()
    bg.setColor(Color.parseColor(bgColor))
    bg.setCornerRadius(dp(4))
    tv.setBackground(bg)
    return tv
}

String recordTitle(JSONObject item, int index) {
    try {
        var customTitle = item.optString("customTitle", "").trim()
        if (customTitle.length() > 0) return customTitle
        var content = item.optString("content", "")
        var type = recordType(item, content)
        if (type.length() > 0) return type
        var codeName = item.optString("codeName", "QR_CODE")
        if (codeName.indexOf(".") >= 0 || codeName.indexOf("@") >= 0) return "二维码内容"
        if (codeName.indexOf("WX") >= 0) return "微信码"
        if (codeName.indexOf("QR") >= 0) return "二维码"
        if (!(content.startsWith("http://") || content.startsWith("https://"))) return "二维码内容"
        return codeName
    } catch (Throwable e) {
        return "扫码记录 " + index
    }
}

String recordTag(JSONObject item) {
    try {
        var content = item.optString("content", "")
        var type = recordType(item, content)
        if (type.indexOf("支付") >= 0 || type.indexOf("收款") >= 0) return "支付码"
        if (type.indexOf("个人") >= 0 || type.indexOf("联系人") >= 0) return "名片码"
        if (type.indexOf("群") >= 0) return "群邀请"
        if (type.indexOf("公众号") >= 0) return "公众号"
        if (type.indexOf("登录") >= 0) return "登录码"
        if (type.indexOf("企业微信") >= 0) return "企业微信"
        if (type.indexOf("视频号") >= 0) return "视频号"
        if (type.indexOf("安全") >= 0 || type.indexOf("支持") >= 0) return "安全支持"
        if (type.indexOf("小程序") >= 0) return "小程序码"
        if (type.indexOf("网页") >= 0) return "网页链接"
        if (type.indexOf("Scheme") >= 0) return "内部协议"
    } catch (Throwable e) {
    }
    return "二维码"
}

String recordType(JSONObject item, String content) {
    try {
        if (content == null) content = ""
        var s = content.trim().toLowerCase()
        if (s.indexOf("weixin.qq.com/g/") >= 0) return "微信群邀请"
        if (s.indexOf("u.wechat.com/") >= 0) return "个人微信名片"
        if (s.indexOf("weixin.qq.com/r/") >= 0) return "微信联系人/公众号场景"
        if (s.indexOf("open.weixin.qq.com/qr/code") >= 0) return "公众号二维码"
        if (s.indexOf("mp.weixin.qq.com/cgi-bin/showqrcode") >= 0 || s.indexOf("showqrcode?ticket=") >= 0) return "公众号场景二维码"
        if (s.indexOf("api.weixin.qq.com/cgi-bin/qrcode/create") >= 0) return "公众号二维码接口"
        if (s.indexOf("mp.weixin.qq.com/s") >= 0) return "公众号文章"
        if (s.indexOf("mp.weixin.qq.com/mp/") >= 0) return "公众号页面"
        if (s.indexOf("open.weixin.qq.com/connect/qrconnect") >= 0) return "微信扫码登录"
        if (s.indexOf("login.weixin.qq.com/l/") >= 0) return "微信登录二维码"
        if (s.startsWith("wxp://") || s.indexOf("wxpay") >= 0 || s.indexOf("payapp.weixin.qq.com/qr") >= 0 || s.indexOf("wx.tenpay.com") >= 0) return "微信支付二维码"
        if (s.indexOf("work.weixin.qq.com") >= 0 || s.startsWith("wework://")) return "企业微信二维码"
        if (s.startsWith("weixin://contacts/profile/")) return "微信联系人资料页"
        if (s.startsWith("weixin://")) return "微信内部 Scheme"
        if (s.indexOf("channels.weixin.qq.com") >= 0 || s.indexOf("finder.video.qq.com") >= 0) return "视频号/直播内容"
        if (s.indexOf("support.weixin.qq.com") >= 0) return "微信支持/申诉"
        if (s.indexOf("weixin110.qq.com") >= 0) return "微信安全中心"
        if (s.indexOf("game.weixin.qq.com") >= 0) return "微信游戏"
        if (s.indexOf("store.weixin.qq.com") >= 0) return "微信小店"
        if (s.indexOf("servicewechat.com") >= 0) return "小程序相关链接"
        if (isMiniProgramCode(item, content)) return "微信小程序"
        if (s.startsWith("http://") || s.startsWith("https://")) return "网页链接"
    } catch (Throwable e) {
    }
    return ""
}

boolean isMiniProgramCode(JSONObject item, String content) {
    try {
        if (content == null) content = ""
        if (content.indexOf("mp.weixin.qq.com") >= 0) return true
        var codeName = item.optString("codeName", "")
        if (codeName.indexOf("WX_CODE") >= 0) return true
        if (content.length() >= 24 && content.indexOf("~") >= 0 && content.indexOf(";") >= 0) return true
    } catch (Throwable e) {
    }
    return false
}

String recordIconText(String tag) {
    if (tag.indexOf("名片") >= 0) return "人"
    if (tag.indexOf("群") >= 0) return "群"
    if (tag.indexOf("公众号") >= 0) return "文"
    if (tag.indexOf("登录") >= 0) return "登"
    if (tag.indexOf("企业") >= 0) return "企"
    if (tag.indexOf("视频") >= 0) return "播"
    if (tag.indexOf("安全") >= 0) return "!"
    if (tag.indexOf("内部") >= 0) return "链"
    if (tag.indexOf("网页") >= 0) return "网"
    if (tag.indexOf("支付") >= 0) return "¥"
    if (tag.indexOf("小程序") >= 0) return "小"
    return "⌗"
}

String recordIconBg(String tag) {
    if (tag.indexOf("名片") >= 0) return "#2F7DF6"
    if (tag.indexOf("群") >= 0) return "#15B86A"
    if (tag.indexOf("公众号") >= 0) return "#2563EB"
    if (tag.indexOf("登录") >= 0) return "#0EA5E9"
    if (tag.indexOf("企业") >= 0) return "#0F766E"
    if (tag.indexOf("视频") >= 0) return "#E11D48"
    if (tag.indexOf("安全") >= 0) return "#EF4444"
    if (tag.indexOf("内部") >= 0) return "#64748B"
    if (tag.indexOf("网页") >= 0) return "#7C3AED"
    if (tag.indexOf("支付") >= 0) return "#FF8A1F"
    if (tag.indexOf("小程序") >= 0) return "#25C26E"
    return "#2F7DF6"
}

String recordTagBg(String tag) {
    if (tag.indexOf("名片") >= 0) return "#EEF5FF"
    if (tag.indexOf("群") >= 0) return "#EAF8F0"
    if (tag.indexOf("公众号") >= 0) return "#EEF5FF"
    if (tag.indexOf("登录") >= 0) return "#EAF7FF"
    if (tag.indexOf("企业") >= 0) return "#E7F7F4"
    if (tag.indexOf("视频") >= 0) return "#FFF0F3"
    if (tag.indexOf("安全") >= 0) return "#FEECEC"
    if (tag.indexOf("内部") >= 0) return "#F1F5F9"
    if (tag.indexOf("网页") >= 0) return "#F4ECFF"
    if (tag.indexOf("支付") >= 0) return "#FFF2E3"
    if (tag.indexOf("小程序") >= 0) return "#EAF8F0"
    return "#EEF5FF"
}

String recordTagColor(String tag) {
    if (tag.indexOf("名片") >= 0) return "#2F67C8"
    if (tag.indexOf("群") >= 0) return "#159A55"
    if (tag.indexOf("公众号") >= 0) return "#2563EB"
    if (tag.indexOf("登录") >= 0) return "#0284C7"
    if (tag.indexOf("企业") >= 0) return "#0F766E"
    if (tag.indexOf("视频") >= 0) return "#BE123C"
    if (tag.indexOf("安全") >= 0) return "#DC2626"
    if (tag.indexOf("内部") >= 0) return "#475569"
    if (tag.indexOf("网页") >= 0) return "#7C3AED"
    if (tag.indexOf("支付") >= 0) return "#C56A12"
    if (tag.indexOf("小程序") >= 0) return "#159A55"
    return "#2F67C8"
}

void showQrDialog(final JSONObject item) {
    showQrDialog(item, null)
}

void showQrDialog(final JSONObject item, final Dialog historyDialog) {
    try {
        debugLog("准备显示二维码弹窗")
        var act = getTopActivity()
        if (act == null) {
            toast("当前没有可用界面")
            return
        }
        final String content = item.optString("content", "")
        if (content.length() == 0) {
            toast("这条记录内容为空")
            return
        }

        final Dialog dialog = new Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        var overlay = new LinearLayout(act)
        overlay.setOrientation(LinearLayout.VERTICAL)
        overlay.setGravity(Gravity.CENTER)
        overlay.setPadding(dp(18), 0, dp(18), 0)
        overlay.setBackgroundColor(Color.TRANSPARENT)
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss()
            }
        })

        var card = new LinearLayout(act)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setGravity(Gravity.CENTER_HORIZONTAL)
        card.setPadding(dp(22), dp(20), dp(22), 0)
        var bg = new GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.setCornerRadius(dp(12))
        card.setBackground(bg)
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            }
        })
        var cardLp = new LinearLayout.LayoutParams(act.getResources().getDisplayMetrics().widthPixels * 86 / 100, LinearLayout.LayoutParams.WRAP_CONTENT)
        overlay.addView(card, cardLp)

        var closeLine = new LinearLayout(act)
        closeLine.setOrientation(LinearLayout.HORIZONTAL)
        closeLine.setGravity(Gravity.CENTER_VERTICAL)
        var delete = new TextView(act)
        delete.setText("删除")
        delete.setTextSize(15)
        delete.setTypeface(Typeface.DEFAULT_BOLD)
        delete.setTextColor(Color.parseColor("#EF4444"))
        delete.setGravity(Gravity.CENTER)
        closeLine.addView(delete, new LinearLayout.LayoutParams(dp(58), dp(34)))
        var topSpace = new TextView(act)
        closeLine.addView(topSpace, new LinearLayout.LayoutParams(0, dp(34), 1))
        var close = new TextView(act)
        close.setText("×")
        close.setTextSize(26)
        close.setTextColor(Color.parseColor("#111827"))
        close.setGravity(Gravity.CENTER)
        closeLine.addView(close, new LinearLayout.LayoutParams(dp(38), dp(34)))
        card.addView(closeLine, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        delete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDeleteRecord(item, dialog, historyDialog)
            }
        })
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss()
            }
        })

        var tag = recordTag(item)
        card.addView(roundText(act, recordIconText(tag), recordIconBg(tag), "#FFFFFF", 48, 18))

        var title = new TextView(act)
        title.setText(recordTitle(item, 1))
        title.setTextSize(18)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(Color.parseColor("#111827"))
        title.setGravity(Gravity.CENTER)
        title.setSingleLine(true)
        title.setEllipsize(TextUtils.TruncateAt.END)
        title.setPadding(dp(8), dp(12), dp(8), 0)
        card.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        var tagText = tagView(act, tag, recordTagBg(tag), recordTagColor(tag))
        var tagLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tagLp.setMargins(0, dp(8), 0, dp(14))
        card.addView(tagText, tagLp)

        var contentView = new TextView(act)
        contentView.setText(content)
        contentView.setTextSize(14)
        contentView.setTextColor(Color.parseColor("#667085"))
        contentView.setGravity(Gravity.CENTER_VERTICAL)
        contentView.setMaxLines(3)
        contentView.setEllipsize(TextUtils.TruncateAt.END)
        contentView.setPadding(dp(14), 0, dp(14), 0)
        var contentBg = new GradientDrawable()
        contentBg.setColor(Color.parseColor("#F6F8FB"))
        contentBg.setCornerRadius(dp(10))
        contentBg.setStroke(1, Color.parseColor("#E4E8EF"))
        contentView.setBackground(contentBg)
        var contentLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(82))
        contentLp.setMargins(0, dp(10), 0, dp(14))
        card.addView(contentView, contentLp)

        var actions = new LinearLayout(act)
        actions.setOrientation(LinearLayout.HORIZONTAL)
        actions.setGravity(Gravity.CENTER)
        var lineBg = new GradientDrawable()
        lineBg.setColor(Color.WHITE)
        actions.setBackground(lineBg)

        var copy = dialogActionText(act, "复制链接")
        var open = dialogActionText(act, "重新打开")
        var share = dialogActionText(act, "分享给好友")
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(54), 1))
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(54), 1))
        actions.addView(share, new LinearLayout.LayoutParams(0, dp(54), 1))
        card.addView(actions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))

        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyText(content)
            }
        })
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss()
                replayRecord(item)
            }
        })
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showShareTargetDialog(content)
            }
        })

        debugLog("准备展示二维码弹窗")
        dialog.setContentView(overlay)
        dialog.show()
        debugLog("二维码弹窗已展示")
        var window = dialog.getWindow()
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT))
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
    } catch (Throwable e) {
        debugLog("显示二维码失败: " + e)
        toast("生成二维码失败")
    }
}

TextView dialogActionText(Activity act, String text) {
    var tv = new TextView(act)
    tv.setText(text)
    tv.setTextSize(15)
    tv.setTypeface(Typeface.DEFAULT_BOLD)
    tv.setTextColor(Color.parseColor("#2F7DF6"))
    tv.setGravity(Gravity.CENTER)
    return tv
}

void showShareTargetDialog(final String text) {
    try {
        var act = getTopActivity()
        if (act == null) {
            copyText(text)
            return
        }
        var items = new String[]{"👤 选择好友", "🏠 选择群聊"}
        var builder = new AlertDialog.Builder(act)
        builder.setTitle("请选择目标类型")
        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) showShareSelectionDialog(false, text)
                else showShareSelectionDialog(true, text)
            }
        })
        final AlertDialog dialog = builder.create()
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                styleShareAlertDialog(dialog)
            }
        })
        dialog.show()
    } catch (Throwable e) {
        debugLog("打开分享对象类型选择失败: " + e)
        copyText(text)
        toast("未能打开选择列表，已复制链接")
    }
}

void showShareSelectionDialog(final boolean group, final String text) {
    try {
        var act = getTopActivity()
        if (act == null) {
            copyText(text)
            return
        }
        final ProgressDialog loading = new ProgressDialog(act)
        loading.setTitle(group ? "加载群聊列表" : "加载好友列表")
        loading.setMessage(group ? "正在获取群聊..." : "正在获取好友...")
        loading.setIndeterminate(true)
        loading.setCancelable(false)
        loading.show()
        new Thread(new Runnable() {
            public void run() {
                final ArrayList list = loadShareTargets(group)
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try {
                            loading.dismiss()
                        } catch (Throwable e) {
                        }
                        showShareMultiSelectDialog(group ? "选择群聊" : "选择好友", list, text, group ? "搜索群名..." : "搜索昵称/备注...")
                    }
                })
            }
        }, group ? "ScanHistoryLoadGroups" : "ScanHistoryLoadFriends").start()
    } catch (Throwable e) {
        debugLog("打开分享对象选择失败: " + e)
        copyText(text)
        toast("未能打开选择列表，已复制链接")
    }
}

void showShareMultiSelectDialog(final String title, final ArrayList targetList, final String text, String searchHint) {
    try {
        var act = getTopActivity()
        if (act == null) {
            copyText(text)
            return
        }
        final HashSet tempSelected = new HashSet()
        var root = new LinearLayout(act)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setPadding(dp(12), dp(12), dp(12), dp(4))

        final EditText searchEdit = createShareSearchEdit(act, searchHint)
        root.addView(searchEdit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        final ListView listView = new ListView(act)
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE)
        listView.setDividerHeight(0)
        setupShareListTouch(listView)
        var listParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
        listParams.setMargins(0, dp(10), 0, 0)
        root.addView(listView, listParams)

        final ArrayList filteredNames = new ArrayList()
        final ArrayList filteredIds = new ArrayList()
        final Runnable refresh = new Runnable() {
            public void run() {
                var q = searchEdit.getText() == null ? "" : searchEdit.getText().toString().toLowerCase().trim()
                filteredNames.clear()
                filteredIds.clear()
                var i = 0
                while (i < targetList.size()) {
                    try {
                        var obj = (JSONObject) targetList.get(i)
                        var name = obj.optString("name", "")
                        var wxid = obj.optString("wxid", "")
                        if (q.length() == 0 || name.toLowerCase().indexOf(q) >= 0 || wxid.toLowerCase().indexOf(q) >= 0) {
                            filteredNames.add(name)
                            filteredIds.add(wxid)
                        }
                    } catch (Throwable e) {
                    }
                    i = i + 1
                }
                var adapter = new ArrayAdapter(act, android.R.layout.simple_list_item_multiple_choice, filteredNames)
                listView.setAdapter(adapter)
                listView.clearChoices()
                var j = 0
                while (j < filteredIds.size()) {
                    listView.setItemChecked(j, tempSelected.contains(filteredIds.get(j)))
                    j = j + 1
                }
            }
        }

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View view, int pos, long id) {
                if (pos < 0 || pos >= filteredIds.size()) return
                var selected = String.valueOf(filteredIds.get(pos))
                if (listView.isItemChecked(pos)) tempSelected.add(selected)
                else tempSelected.remove(selected)
            }
        })

        searchEdit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            public void afterTextChanged(Editable s) {
                refresh.run()
            }
        })

        var builder = new AlertDialog.Builder(act)
        builder.setTitle(title)
        builder.setView(root)
        builder.setPositiveButton("✅ 确定", null)
        builder.setNegativeButton("❌ 取消", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss()
            }
        })
        builder.setNeutralButton("全选/反选", null)
        final AlertDialog dialog = builder.create()
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                styleShareAlertDialog(dialog)
                var positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                if (positive != null) {
                    positive.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            shareToSelectedTargets(tempSelected, text, dialog)
                        }
                    })
                }
                var neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                if (neutral != null) {
                    neutral.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            var allSelected = filteredIds.size() > 0
                            var i = 0
                            while (i < filteredIds.size()) {
                                if (!tempSelected.contains(filteredIds.get(i))) {
                                    allSelected = false
                                    break
                                }
                                i = i + 1
                            }
                            i = 0
                            if (allSelected) {
                                while (i < filteredIds.size()) {
                                    tempSelected.remove(filteredIds.get(i))
                                    i = i + 1
                                }
                            } else {
                                while (i < filteredIds.size()) {
                                    tempSelected.add(filteredIds.get(i))
                                    i = i + 1
                                }
                            }
                            refresh.run()
                        }
                    })
                }
            }
        })
        dialog.show()
        refresh.run()
    } catch (Throwable e) {
        debugLog("打开分享多选列表失败: " + e)
        copyText(text)
        toast("未能打开选择列表，已复制链接")
    }
}

void styleShareAlertDialog(AlertDialog dialog) {
    try {
        var window = dialog.getWindow()
        if (window != null) {
            var bg = new GradientDrawable()
            bg.setColor(Color.WHITE)
            bg.setCornerRadius(dp(14))
            window.setBackgroundDrawable(bg)
        }
        var positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        if (positive != null) positive.setTextColor(Color.parseColor("#2F7DF6"))
        var negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        if (negative != null) negative.setTextColor(Color.parseColor("#374151"))
        var neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        if (neutral != null) neutral.setTextColor(Color.parseColor("#111827"))
    } catch (Throwable e) {
    }
}

void styleDangerConfirmDialog(AlertDialog dialog) {
    try {
        styleShareAlertDialog(dialog)
        var positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        if (positive != null) positive.setTextColor(Color.parseColor("#EF4444"))
        var negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        if (negative != null) negative.setTextColor(Color.parseColor("#374151"))
    } catch (Throwable e) {
    }
}

EditText createShareSearchEdit(Activity act, String hint) {
    var edit = new EditText(act)
    edit.setHint(hint)
    edit.setSingleLine(true)
    edit.setTextSize(14)
    edit.setTextColor(Color.parseColor("#111827"))
    edit.setHintTextColor(Color.parseColor("#9AA3AF"))
    edit.setPadding(dp(14), 0, dp(14), 0)
    var bg = new GradientDrawable()
    bg.setColor(Color.parseColor("#F7F9FC"))
    bg.setCornerRadius(dp(8))
    bg.setStroke(1, Color.parseColor("#E4E8EF"))
    edit.setBackground(bg)
    return edit
}

ArrayList loadShareTargets(boolean group) {
    if (group && cachedShareGroupTargets instanceof ArrayList) return (ArrayList) cachedShareGroupTargets
    if (!group && cachedShareFriendTargets instanceof ArrayList) return (ArrayList) cachedShareFriendTargets
    var targets = new ArrayList()
    try {
        appendShareTargets(targets, group ? getGroupList() : getFriendList(), group)
    } catch (Throwable e) {
        debugLog(group ? "读取群聊列表失败: " + e : "读取好友列表失败: " + e)
    }
    if (group) cachedShareGroupTargets = targets
    else cachedShareFriendTargets = targets
    return targets
}

void appendShareTargets(ArrayList targets, Object listObj, boolean group) {
    if (!(listObj instanceof java.util.List)) return
    var list = (java.util.List) listObj
    var i = 0
    while (i < list.size()) {
        try {
            var raw = list.get(i)
            var wxid = shareTargetWxid(raw, group)
            if (wxid.length() > 0 && !isInvalidShareTarget(wxid)) {
                var obj = new JSONObject()
                obj.put("wxid", wxid)
                obj.put("name", shareTargetDisplayName(wxid, raw, group))
                obj.put("group", group)
                targets.add(obj)
            }
        } catch (Throwable e) {
        }
        i = i + 1
    }
}

String shareTargetWxid(Object raw, boolean group) {
    try {
        if (raw == null) return ""
        if (raw instanceof String) return String.valueOf(raw)
        var methodNames = group ? new String[]{"getRoomId", "getWxid", "getUsername"} : new String[]{"getWxid", "getUsername", "getUserName"}
        var i = 0
        while (i < methodNames.length) {
            var value = callNoArg(raw, methodNames[i])
            if (value != null && String.valueOf(value).length() > 0) return String.valueOf(value)
            i = i + 1
        }
        var keys = new String[]{"wxid", "roomId", "userName", "username", "talker", "field_username", "contactUsername"}
        i = 0
        while (i < keys.length) {
            var value2 = getAnyField(raw, keys[i])
            if (value2 != null && String.valueOf(value2).length() > 0) return String.valueOf(value2)
            i = i + 1
        }
        var s = String.valueOf(raw)
        if (s.indexOf("@") >= 0 || s.startsWith("wxid_")) return s
    } catch (Throwable e) {
    }
    return ""
}

boolean isInvalidShareTarget(String wxid) {
    if (wxid == null || wxid.length() == 0) return true
    if (wxid.equals("filehelper")) return true
    if (wxid.equals("weixin") || wxid.equals("newsapp") || wxid.equals("fmessage")) return true
    if (wxid.indexOf("gh_") == 0) return true
    return false
}

String shareTargetDisplayName(String wxid, Object raw, boolean group) {
    try {
        var name = ""
        if (group) {
            name = stringValue(callNoArg(raw, "getName"))
            if (name.length() == 0) name = stringValue(getAnyField(raw, "name"))
            if (name.length() == 0) name = "未命名群聊"
            return "🏠 " + name
        }
        var nickname = stringValue(callNoArg(raw, "getNickname"))
        var remark = stringValue(callNoArg(raw, "getRemark"))
        if (nickname.length() == 0) nickname = stringValue(getAnyField(raw, "nickname"))
        if (remark.length() == 0) remark = stringValue(getAnyField(raw, "remark"))
        if (nickname.length() == 0) nickname = wxid
        if (remark.length() > 0) return "👤 " + nickname + " (" + remark + ")"
        return "👤 " + nickname
    } catch (Throwable e) {
        return (group ? "🏠 " : "👤 ") + wxid
    }
}

Object callNoArg(Object obj, String name) {
    if (obj == null || name == null) return null
    try {
        var method = obj.getClass().getMethod(name, new Class[]{})
        method.setAccessible(true)
        return method.invoke(obj, new Object[]{})
    } catch (Throwable e) {
    }
    try {
        var method2 = obj.getClass().getDeclaredMethod(name, new Class[]{})
        method2.setAccessible(true)
        return method2.invoke(obj, new Object[]{})
    } catch (Throwable e2) {
    }
    return null
}

void setupShareListTouch(ListView listView) {
    if (listView == null) return
    listView.setOnTouchListener(new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            try {
                if (event.getAction() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true)
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) v.getParent().requestDisallowInterceptTouchEvent(false)
            } catch (Throwable e) {
            }
            return false
        }
    })
}

void shareToSelectedTargets(HashSet selectedIds, String text, Dialog dialog) {
    try {
        if (selectedIds == null || selectedIds.size() == 0) {
            toast("请先勾选好友或群聊")
            return
        }
        var ids = new ArrayList(selectedIds)
        var success = 0
        var failed = 0
        var i = 0
        while (i < ids.size()) {
            var talker = String.valueOf(ids.get(i))
            if (shareToOneTarget(talker, text)) success = success + 1
            else failed = failed + 1
            i = i + 1
        }
        if (success > 0) {
            try {
                dialog.dismiss()
            } catch (Throwable e) {
            }
            if (failed > 0) toast("已分享 " + success + " 个，失败 " + failed + " 个")
            else toast("已分享")
        } else {
            copyText(text)
            toast("分享失败，已复制链接")
        }
    } catch (Throwable e) {
        debugLog("批量分享给好友失败: " + e)
        copyText(text)
        toast("分享失败，已复制链接")
    }
}

boolean shareToOneTarget(String talker, String text) {
    try {
        if (talker == null || talker.length() == 0) return false
        shareText(talker, text, "")
        return true
    } catch (Throwable e) {
        debugLog("shareText 分享失败，尝试 sendText: " + e)
    }
    try {
        sendText(talker, text)
        return true
    } catch (Throwable e2) {
        debugLog("sendText 分享失败: " + e2)
    }
    return false
}

void shareToTarget(String talker, String text, Dialog dialog) {
    try {
        if (talker == null || talker.length() == 0) {
            toast("分享对象无效")
            return
        }
        if (shareToOneTarget(talker, text)) {
            try {
                dialog.dismiss()
            } catch (Throwable e) {
            }
            toast("已分享")
        } else {
            copyText(text)
            toast("分享失败，已复制链接")
        }
    } catch (Throwable e2) {
        debugLog("分享给好友失败: " + e2)
        try {
            dialog.dismiss()
        } catch (Throwable e3) {
        }
        copyText(text)
        toast("分享失败，已复制链接")
    }
}

void replayRecord(JSONObject item) {
    try {
        var act = getTopActivity()
        if (act == null) {
            toast("当前没有可用界面")
            return
        }
        if (replayMethod == null || replayClass == null) {
            resolveReplayMethod()
        }
        if (replayMethod == null || replayClass == null) {
            toast("未适配当前微信的扫码回放入口")
            return
        }

        var content = item.optString("content", "")
        if (content.length() == 0) {
            toast("这条记录内容为空")
            return
        }

        var codeName = item.optString("codeName", "QR_CODE")
        var source = normalizeSource(item.optInt("source", 0))
        var codeType = item.optInt("codeType", codeTypeFromName(codeName))
        if (codeType <= 0) codeType = codeTypeFromName(codeName)
        var codeVersion = item.optInt("codeVersion", 0)

        var handler = createReplayHandler()
        var args = buildReplayArgs(replayMethod, act, content, source, codeName, codeType, codeVersion)
        replayMethod.invoke(handler, args)
        toast("正在重新打开扫码详情")
    } catch (Throwable e) {
        debugLog("重新打开扫码详情失败: " + e)
        toast("重新打开失败: " + e.getMessage())
    }
}

Object createReplayHandler() {
    try {
        var cons = replayClass.getDeclaredConstructor(new Class[]{String.class})
        cons.setAccessible(true)
        return cons.newInstance(new Object[]{String.valueOf(System.currentTimeMillis())})
    } catch (Throwable e) {
    }
    var cons2 = replayClass.getDeclaredConstructor(new Class[]{})
    cons2.setAccessible(true)
    return cons2.newInstance(new Object[]{})
}

Object[] buildReplayArgs(Method method, Activity act, String content, int source, String codeName, int codeType, int codeVersion) {
    var pts = method.getParameterTypes()
    var args = new Object[pts.length]
    var replayScene = replaySceneFromSource(source)

    args[0] = act
    args[1] = content

    if (pts.length >= 8 && isIntType(pts[2]) && isIntType(pts[3]) && pts[4] == String.class && isIntType(pts[5]) && isIntType(pts[6])) {
        args[2] = new Integer(source)
        args[3] = new Integer(replayScene)
        args[4] = codeName
        args[5] = new Integer(codeType)
        args[6] = new Integer(codeVersion)
        fillReplayTail(args, pts, 7)
        return args
    }

    if (pts.length >= 9 && isIntType(pts[2]) && isIntType(pts[3]) && isIntType(pts[4]) && pts[5] == String.class && isIntType(pts[6]) && isIntType(pts[7])) {
        args[2] = new Integer(0)
        args[3] = new Integer(source)
        args[4] = new Integer(replayScene)
        args[5] = codeName
        args[6] = new Integer(codeType)
        args[7] = new Integer(codeVersion)
        fillReplayTail(args, pts, 8)
        return args
    }

    var stringFilled = 0
    var intFilled = 0
    var i = 2
    while (i < pts.length) {
        if (pts[i] == String.class) {
            args[i] = codeName
            stringFilled = stringFilled + 1
        } else if (isIntType(pts[i])) {
            if (intFilled == 0) args[i] = new Integer(source)
            else if (intFilled == 1) args[i] = new Integer(replayScene)
            else if (intFilled == 2) args[i] = new Integer(codeType)
            else if (intFilled == 3) args[i] = new Integer(codeVersion)
            else args[i] = new Integer(-1)
            intFilled = intFilled + 1
        } else {
            args[i] = defaultReplayArg(pts[i])
        }
        i = i + 1
    }
    return args
}

void fillReplayTail(Object[] args, Class[] pts, int start) {
    var i = start
    var boolIndex = 0
    while (i < pts.length) {
        if (pts[i] == Boolean.TYPE || pts[i] == Boolean.class) {
            args[i] = boolIndex == 0 ? Boolean.FALSE : Boolean.TRUE
            boolIndex = boolIndex + 1
        } else {
            args[i] = defaultReplayArg(pts[i])
        }
        i = i + 1
    }
}

Object defaultReplayArg(Class type) {
    if (type == Bundle.class) {
        var ext = new Bundle()
        ext.putString("stat_url", "scan_history")
        return ext
    }
    if (type == Boolean.TYPE || type == Boolean.class) {
        return Boolean.FALSE
    }
    if (type == Integer.TYPE || type == Integer.class) {
        return new Integer(-1)
    }
    if (type == Long.TYPE || type == Long.class) {
        return new Long(0L)
    }
    return null
}

boolean isIntType(Class type) {
    return type == Integer.TYPE || type == Integer.class
}

int replaySceneFromSource(int source) {
    if (source == 1) return 34
    if (source == 0) return 4
    if (source == 3) return 42
    return 30
}

int normalizeSource(int source) {
    if (source == 0 || source == 1 || source == 3) return source
    return 0
}

String buildHistoryText(int count) {
    var records = loadRecords()
    if (records.size() == 0) return "暂无扫码记录"
    var sb = new StringBuilder()
    sb.append("最近扫码记录\n")
    var limit = Math.min(records.size(), count)
    var i = 0
    while (i < limit) {
        var item = (JSONObject) records.get(i)
        sb.append(i + 1).append(". ")
        sb.append(formatTime(item.optLong("time", 0L))).append(" ")
        sb.append(item.optString("codeName", "QR_CODE")).append("\n")
        sb.append(item.optString("content", "")).append("\n")
        i = i + 1
    }
    return sb.toString()
}

void copyText(String text) {
    try {
        var act = getTopActivity()
        if (act == null) return
        var app = act.getApplicationContext()
        var cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE)
        if (cm == null) return
        cm.setPrimaryClip(ClipData.newPlainText("扫码内容", text == null ? "" : text))
        Toast.makeText(app, "已复制", Toast.LENGTH_SHORT).show()
    } catch (Throwable e) {
        debugLog("复制扫码内容失败: " + e)
    }
}

void copyTextFromView(View view, String text) {
    try {
        if (view == null) return
        var context = view.getContext()
        if (context == null) return
        var app = context.getApplicationContext()
        if (app == null) app = context
        var cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE)
        if (cm == null) return
        cm.setPrimaryClip(ClipData.newPlainText("扫码内容", text == null ? "" : text))
        Toast.makeText(app, "已复制", Toast.LENGTH_SHORT).show()
    } catch (Throwable e) {
        debugLog("长按复制扫码内容失败: " + e)
    }
}

void appendHomePlusEntry(Object host) {
    var start = System.currentTimeMillis()
    try {
        if (host == null) return
        var data = findDirectPlusMenuData(host)
        if (data == null) data = getCachedPlusMenuData(host)
        if (data == null) data = findPlusMenuData(host, 0)
        if (data == null) return
        cachedPlusMenuHost = host
        cachedPlusMenuData = data
        if (hasPlusHistoryEntry(data)) return

        var sampleWrapper = findWrapperByItemId(data, 10)
        if (sampleWrapper == null) sampleWrapper = findAnyPlusWrapper(data)
        var sampleItem = getPlusItemFromWrapper(sampleWrapper)
        if (sampleItem == null) return

        var icon = readPlusIcon(sampleItem)
        var item = newPlusMenuItem(sampleItem, icon)
        if (item == null) return
        var wrapper = newPlusMenuWrapper(sampleWrapper, item)
        if (wrapper == null) return

        data.put(data.size(), wrapper)
        notifyPlusMenuAdapter(host, 0)
        if (plusMenuInjectLogCount < 5) {
            plusMenuInjectLogCount = plusMenuInjectLogCount + 1
            debugLog("已注入主页加号扫码记录入口")
        }
    } catch (Throwable e) {
        debugLog("追加主页加号扫码记录入口失败: " + e)
    } finally {
        var cost = System.currentTimeMillis() - start
        if (cost > 180L) debugLog("主页加号扫码记录入口处理耗时: " + cost + "ms")
    }
}

SparseArray getCachedPlusMenuData(Object host) {
    try {
        if (cachedPlusMenuData instanceof SparseArray) {
            var arr = (SparseArray) cachedPlusMenuData
            if (hasPlusHistoryEntry(arr) || isPlusMenuSparseArray(arr)) return arr
        }
    } catch (Throwable e) {
    }
    return null
}

SparseArray findPlusMenuData(Object obj, int depth) {
    if (obj == null || depth > 3) return null
    try {
        var direct = findDirectPlusMenuData(obj)
        if (direct != null) return direct
        var cls = obj.getClass()
        if (shouldSkipDeepScan(cls)) return null
        var guard = 0
        while (cls != null && guard < 5) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true)
                        var value = f.get(obj)
                        if (value != null && !isSimpleObject(value)) {
                            var found = findPlusMenuData(value, depth + 1)
                            if (found != null) return found
                        }
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
    return null
}

SparseArray findDirectPlusMenuData(Object obj) {
    try {
        var cls = obj.getClass()
        var guard = 0
        while (cls != null && guard < 5) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true)
                        var value = f.get(obj)
                        if (value instanceof SparseArray) {
                            var arr = (SparseArray) value
                            if (isPlusMenuSparseArray(arr)) return arr
                        }
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
    return null
}

boolean isPlusMenuSparseArray(SparseArray arr) {
    try {
        if (arr == null || arr.size() == 0 || arr.size() > 40) return false
        var hit = 0
        var i = 0
        while (i < arr.size()) {
            var wrapper = getSparseValue(arr, i)
            var item = getPlusItemFromWrapper(wrapper)
            var id = readPlusItemId(item)
            var title = readPlusTitle(item)
            if ((id == plusMenuId || (id >= 1 && id <= 24) || id == 2147483645 || id == 2147483646) && title.length() > 0) {
                hit = hit + 1
            }
            i = i + 1
        }
        return hit >= 2 || hasPlusHistoryEntry(arr)
    } catch (Throwable e) {
        return false
    }
}

boolean hasPlusHistoryEntry(SparseArray arr) {
    try {
        var i = 0
        while (arr != null && i < arr.size()) {
            var item = getPlusItemFromWrapper(getSparseValue(arr, i))
            if (readPlusItemId(item) == plusMenuId) return true
            i = i + 1
        }
    } catch (Throwable e) {
    }
    return false
}

Object findWrapperByItemId(SparseArray arr, int id) {
    try {
        var i = 0
        while (arr != null && i < arr.size()) {
            var wrapper = getSparseValue(arr, i)
            var item = getPlusItemFromWrapper(wrapper)
            if (readPlusItemId(item) == id) return wrapper
            i = i + 1
        }
    } catch (Throwable e) {
    }
    return null
}

Object findAnyPlusWrapper(SparseArray arr) {
    try {
        var i = 0
        while (arr != null && i < arr.size()) {
            var wrapper = getSparseValue(arr, i)
            if (getPlusItemFromWrapper(wrapper) != null) return wrapper
            i = i + 1
        }
    } catch (Throwable e) {
    }
    return null
}

Object getSparseValue(SparseArray arr, int index) {
    if (arr == null || index < 0 || index >= arr.size()) return null
    try {
        var value = arr.get(index)
        if (value != null) return value
    } catch (Throwable e) {
    }
    try {
        return arr.valueAt(index)
    } catch (Throwable e2) {
    }
    return null
}

Object getPlusItemFromWrapper(Object wrapper) {
    if (wrapper == null) return null
    try {
        if (looksLikePlusItem(wrapper)) return wrapper
        var cls = wrapper.getClass()
        var guard = 0
        while (cls != null && guard < 3) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true)
                        var value = f.get(wrapper)
                        if (looksLikePlusItem(value)) return value
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
    return null
}

boolean looksLikePlusItem(Object item) {
    try {
        if (item == null || isSimpleObject(item)) return false
        var title = readPlusTitle(item)
        if (title.length() == 0) return false
        var id = readPlusItemId(item)
        return id == plusMenuId || (id >= 1 && id <= 24) || id == 2147483645 || id == 2147483646
    } catch (Throwable e) {
        return false
    }
}

String readPlusTitle(Object item) {
    try {
        if (item == null) return ""
        var cls = item.getClass()
        var guard = 0
        while (cls != null && guard < 3) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                        f.setAccessible(true)
                        var value = f.get(item)
                        if (value != null && String.valueOf(value).length() > 0) return String.valueOf(value)
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
    return ""
}

int readPlusItemId(Object item) {
    try {
        if (item == null) return -1
        var fallback = -1
        var cls = item.getClass()
        var guard = 0
        while (cls != null && guard < 3) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers()) && isIntType(f.getType())) {
                        f.setAccessible(true)
                        var value = intValue(f.get(item), -1)
                        if (value == plusMenuId) return value
                        if ((value >= 1 && value <= 24) || value == 2147483645 || value == 2147483646) return value
                        if (fallback < 0 && value > 0 && value < 100) fallback = value
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
        return fallback
    } catch (Throwable e2) {
        return -1
    }
}

int readPlusIcon(Object item) {
    try {
        if (item == null) return 0
        var cls = item.getClass()
        var guard = 0
        while (cls != null && guard < 3) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers()) && isIntType(f.getType())) {
                        f.setAccessible(true)
                        var value = intValue(f.get(item), 0)
                        if (value > 1000000 && value < plusMenuId) return value
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
    return 0
}

Object newPlusMenuItem(Object sampleItem, int icon) {
    try {
        var cons = sampleItem.getClass().getDeclaredConstructors()
        var i = 0
        while (i < cons.length) {
            var c = (Constructor) cons[i]
            var pts = c.getParameterTypes()
            if (pts.length == 5 && isIntType(pts[0]) && pts[1] == String.class && pts[2] == String.class && isIntType(pts[3]) && isIntType(pts[4])) {
                c.setAccessible(true)
                return c.newInstance(new Object[]{new Integer(plusMenuId), "扫码记录", "", new Integer(icon), new Integer(0)})
            }
            if (pts.length == 6 && isIntType(pts[0]) && pts[1] == String.class && pts[2] == String.class && isIntType(pts[3]) && isIntType(pts[4]) && pts[5] == String.class) {
                c.setAccessible(true)
                return c.newInstance(new Object[]{new Integer(plusMenuId), "扫码记录", "", new Integer(icon), new Integer(0), ""})
            }
            i = i + 1
        }
    } catch (Throwable e) {
        debugLog("创建主页加号菜单 item 失败: " + e)
    }
    return null
}

Object newPlusMenuWrapper(Object sampleWrapper, Object item) {
    try {
        var cons = sampleWrapper.getClass().getDeclaredConstructors()
        var i = 0
        while (i < cons.length) {
            var c = (Constructor) cons[i]
            var pts = c.getParameterTypes()
            if (pts.length == 1 && pts[0].isAssignableFrom(item.getClass())) {
                c.setAccessible(true)
                return c.newInstance(new Object[]{item})
            }
            i = i + 1
        }
    } catch (Throwable e) {
        debugLog("创建主页加号菜单 wrapper 失败: " + e)
    }
    return null
}

void notifyPlusMenuAdapter(Object obj, int depth) {
    if (obj == null || depth > 3) return
    try {
        if (obj instanceof BaseAdapter) {
            ((BaseAdapter) obj).notifyDataSetChanged()
            return
        }
        var cls = obj.getClass()
        if (shouldSkipDeepScan(cls)) return
        var guard = 0
        while (cls != null && guard < 5) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true)
                        var value = f.get(obj)
                        if (value instanceof BaseAdapter) {
                            ((BaseAdapter) value).notifyDataSetChanged()
                            return
                        }
                        if (value != null && !isSimpleObject(value)) {
                            notifyPlusMenuAdapter(value, depth + 1)
                        }
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
}

void tryDismissPlusMenu(Object helper) {
    try {
        dismissPopupWindowDeep(helper, 0)
    } catch (Throwable e) {
    }
}

boolean dismissPopupWindowDeep(Object obj, int depth) {
    if (obj == null || depth > 3) return false
    try {
        if (obj instanceof PopupWindow) {
            ((PopupWindow) obj).dismiss()
            return true
        }
        var cls = obj.getClass()
        if (shouldSkipDeepScan(cls)) return false
        var guard = 0
        while (cls != null && guard < 5) {
            var fields = cls.getDeclaredFields()
            var i = 0
            while (i < fields.length) {
                try {
                    var f = fields[i]
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true)
                        var value = f.get(obj)
                        if (value instanceof PopupWindow) {
                            ((PopupWindow) value).dismiss()
                            return true
                        }
                        if (value != null && !isSimpleObject(value) && dismissPopupWindowDeep(value, depth + 1)) return true
                    }
                } catch (Throwable e) {
                }
                i = i + 1
            }
            cls = cls.getSuperclass()
            guard = guard + 1
        }
    } catch (Throwable e2) {
    }
    return false
}

boolean isSimpleObject(Object value) {
    if (value == null) return true
    var cls = value.getClass()
    if (cls.isPrimitive()) return true
    if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) return true
    if (value instanceof Context || value instanceof View) return true
    return shouldSkipDeepScan(cls)
}

boolean shouldSkipDeepScan(Class cls) {
    try {
        if (cls == null) return true
        var name = cls.getName()
        if (name.startsWith("java.")) return true
        if (name.startsWith("android.") && name.indexOf("SparseArray") < 0) return true
        if (name.startsWith("androidx.")) return true
        if (name.startsWith("dalvik.")) return true
    } catch (Throwable e) {
    }
    return false
}

Object getAnyField(Object obj, String name) {
    if (obj == null) return null
    var cls = obj.getClass()
    while (cls != null) {
        try {
            var f = cls.getDeclaredField(name)
            f.setAccessible(true)
            return f.get(obj)
        } catch (Throwable e) {
            cls = cls.getSuperclass()
        }
    }
    return null
}

String stringValue(Object value) {
    if (value == null) return ""
    return String.valueOf(value)
}

int intValue(Object value, int defValue) {
    try {
        if (value == null) return defValue
        if (value instanceof Integer) return ((Integer) value).intValue()
        if (value instanceof Number) return ((Number) value).intValue()
        return Integer.parseInt(String.valueOf(value))
    } catch (Throwable e) {
        return defValue
    }
}

int codeTypeFromName(String name) {
    if (name == null) return 19
    if (name.equals("EAN-13") || name.equals("EAN_13")) return 4
    if (name.equals("EAN-8") || name.equals("EAN_8")) return 3
    if (name.equals("EAN-2")) return 1
    if (name.equals("EAN-5")) return 2
    if (name.equals("UPC-A") || name.equals("UPC_A")) return 5
    if (name.equals("UPC-E") || name.equals("UPC_E")) return 6
    if (name.equals("CODE-39") || name.equals("CODE_39")) return 9
    if (name.equals("CODE-93") || name.equals("CODE_93")) return 10
    if (name.equals("CODE-128") || name.equals("CODE_128")) return 11
    if (name.equals("COMPOSITE")) return 12
    if (name.equals("I/25") || name.equals("ITF")) return 13
    if (name.equals("DATABAR")) return 7
    if (name.equals("DATABAR-EXP")) return 8
    if (name.equals("RSS_14")) return 15
    if (name.equals("RSS_EXPANDED")) return 16
    if (name.equals("MAXICODE")) return 18
    if (name.equals("PDF_417")) return 20
    if (name.equals("QR_CODE")) return 19
    if (name.equals("CODABAR")) return 17
    if (name.equals("ISBN10")) return 14
    if (name.equals("DATA_MATRIX")) return 21
    if (name.equals("WX_CODE")) return 22
    if (name.equals("VQR_CODE")) return 23
    if (name.equals("SHOP_CODE")) return 24
    return 19
}

String shortText(String text, int maxLen) {
    if (text == null) return ""
    var s = text.replace("\r", " ").replace("\n", " ")
    if (s.length() <= maxLen) return s
    return s.substring(0, maxLen) + "..."
}

String formatTime(long time) {
    try {
        if (time <= 0L) return ""
        return new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(time))
    } catch (Throwable e) {
        return ""
    }
}

String formatFullTime(long time) {
    try {
        if (time <= 0L) return ""
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(time))
    } catch (Throwable e) {
        return ""
    }
}

int dp(int value) {
    try {
        var act = getTopActivity()
        if (act != null) return (int) (act.getResources().getDisplayMetrics().density * value + 0.5f)
    } catch (Throwable e) {
    }
    return value
}

String currentProcessName() {
    try {
        var cls = Class.forName("android.app.ActivityThread")
        var method = cls.getDeclaredMethod("currentProcessName", new Class[]{})
        method.setAccessible(true)
        var name = method.invoke(null, new Object[]{})
        if (name != null) return String.valueOf(name)
    } catch (Throwable e) {
    }
    try {
        var pid = android.os.Process.myPid()
        var am = (android.app.ActivityManager) hostContext.getSystemService(Context.ACTIVITY_SERVICE)
        var list = am.getRunningAppProcesses()
        var i = 0
        while (list != null && i < list.size()) {
            var info = list.get(i)
            if (info.pid == pid) return info.processName
            i = i + 1
        }
    } catch (Throwable e2) {
    }
    return "unknown"
}

