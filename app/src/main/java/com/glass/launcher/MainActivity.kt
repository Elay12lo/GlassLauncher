package com.glass.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*

class MainActivity : Activity() {

    class App(val label: String, val icon: Drawable, val intent: Intent, val pkg: String)

    private lateinit var grid: GridView
    private lateinit var dock: LinearLayout
    private var apps: List<App> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0x26000000))
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(60)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            textSize = 68f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 2f, 0x55000000)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"
            textSize = 16f; setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 1f, 0x55000000)
        }
        col.addView(time, LinearLayout.LayoutParams(-1, -2))
        col.addView(date, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(dp(12), dp(8), dp(12), dp(130))
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x66FFFFFF, 0x26FFFFFF)
            ).apply {
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), 0x80FFFFFF.toInt())
            }
            elevation = dp(8).toFloat()
        }
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })

        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            col.setPadding(0, ins.systemWindowInsetTop + dp(24), 0, 0)
            @Suppress("DEPRECATION")
            (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(16) + ins.systemWindowInsetBottom
            dock.requestLayout()
            ins
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onBackPressed() { /* home: ничего не делаем */ }

    private fun loadApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val ai = it.activityInfo
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                App(it.loadLabel(pm).toString(), it.loadIcon(pm), i, ai.packageName)
            }
            .sortedBy { it.label.lowercase() }

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View = makeItem(apps[p], 60, 11f)
        }
        grid.setOnItemClickListener { _, _, p, _ -> open(apps[p]) }
        grid.setOnItemLongClickListener { _, _, p, _ ->
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + apps[p].pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }

        dock.removeAllViews()
        val dockIntents = listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        for (di in dockIntents) {
            val ri = pm.resolveActivity(di, PackageManager.MATCH_DEFAULT_ONLY) ?: continue
            val ai = ri.activityInfo
            if (ai.packageName == "android") continue
            di.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val app = App("", ri.loadIcon(pm), di, ai.packageName)
            val item = makeItem(app, 58, 0f)
            item.setOnClickListener { open(app) }
            dock.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun open(app: App) {
        try { startActivity(app.intent) } catch (_: Exception) {}
    }

    private fun makeItem(app: App, sizeDp: Int, textSp: Float): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val iv = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ColorDrawable(0x33FFFFFF)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) {
                    o.setRoundRect(0, 0, view.width, view.height, view.width * 0.225f)
                }
            }
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        box.addView(iv, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        if (textSp > 0f) {
            box.addView(TextView(this).apply {
                text = app.label
                textSize = textSp
                setTextColor(Color.WHITE)
                setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
        return box
    }
}
