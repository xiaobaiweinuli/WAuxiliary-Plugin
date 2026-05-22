import android.widget.Toast
import android.app.AlertDialog
import android.content.Context

var hookD = null
var hookClick = null

// ─── 字段/构造缓存 ───────────────────────────────
var fField_ig_s   = null
var fField_fg_b   = null
var fField_gg_c   = null
var ggCtor5       = null
var fgCtor        = null

val CUSTOM_TYPE   = 9999
val CUSTOM_TITLE  = "测试"
val CUSTOM_ICON   = 2131692088 

void onLoad() {
    log("开始 Hook：真实插入菜单")

    var igClass = com.tencent.mm.ui.ig.class
    var ggClass = com.tencent.mm.ui.gg.class
    var fgClass = com.tencent.mm.ui.fg.class

    fField_ig_s = igClass.getDeclaredField("s")
    fField_ig_s.setAccessible(true)
    fField_fg_b = fgClass.getDeclaredField("b")
    fField_fg_b.setAccessible(true)
    fField_gg_c = ggClass.getDeclaredField("c")
    fField_gg_c.setAccessible(true)

    ggCtor5 = ggClass.getDeclaredConstructor(
        java.lang.Integer.TYPE, java.lang.String.class, java.lang.String.class, 
        java.lang.Integer.TYPE, java.lang.Integer.TYPE
    )
    ggCtor5.setAccessible(true)
    fgCtor = fgClass.getDeclaredConstructor(ggClass)
    fgCtor.setAccessible(true)

    // ── Hook 1：插入菜单 ──────────────────────────
    var methodD = igClass.getDeclaredMethod("d")
    hookD = hookAfter(methodD, param -> {
        try {
            var sparseArray = fField_ig_s.get(param.thisObject)
            if (sparseArray == null) return

            var size = sparseArray.size()
            for (var i = 0; i < size; i++) {
                var fg = sparseArray.valueAt(i)
                if (fg != null && fField_gg_c.get(fField_fg_b.get(fg)) == CUSTOM_TYPE) return
            }

            var newGG = ggCtor5.newInstance(CUSTOM_TYPE, CUSTOM_TITLE, "", CUSTOM_ICON, 0)
            var newFG = fgCtor.newInstance(newGG)
            sparseArray.put(size, newFG)
            log("注入成功")
        } catch (e) { log("hookD 异常=" + e) }
    })

    // ── Hook 2：点击处理（显示测试页面） ───────────────
    var methodClick = igClass.getDeclaredMethod(
        "onItemClick",
        android.widget.AdapterView.class,
        android.view.View.class,
        java.lang.Integer.TYPE,
        java.lang.Long.TYPE
    )

    hookClick = hookBefore(methodClick, param -> {
        try {
            var position = param.args[2]
            var sparseArr = fField_ig_s.get(param.thisObject)
            var fg = sparseArr.get(position)
            if (fg == null) return
            
            var typeId = fField_gg_c.get(fField_fg_b.get(fg))
            if (typeId != CUSTOM_TYPE) return

            // 拦截点击：显示测试弹窗
            var ctx = param.args[1].getContext()
            
            // 确保在主线程执行 UI 操作
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    var builder = new AlertDialog.Builder(ctx)
                    builder.setTitle("测试点击")
                    builder.setMessage("测试界面显示成功！\n这里可以放置自定义布局或设置项。")
                    builder.setPositiveButton("确定", null)
                    builder.show()
                } catch (e) {
                    log("弹窗异常=" + e)
                }
            })

            // 阻止原始点击事件继续（可选）
            // param.setResult(null) 
        } catch (e) { log("hookClick 异常=" + e) }
    })
}

void onUnload() {
    if (hookD != null) { unhook(hookD); hookD = null }
    if (hookClick != null) { unhook(hookClick); hookClick = null }
}
