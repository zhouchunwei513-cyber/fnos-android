package com.fnos.tablet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 设置页（对齐 PC 端设置）：皮肤安装/选择、服务器、关于 */
class SettingsActivity : AppCompatActivity() {

    private val PICK_ZIP = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0D12.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        val st = Settings.load(this)

        root.addView(TextView(this).apply {
            text = "设置"
            setTextColor(0xFFE6EDF3.toInt()); textSize = 22f
            setPadding(0, 0, 0, dp(16))
        })

        root.addView(row("服务器", st.server))
        root.addView(row("线路", st.line))
        root.addView(row("当前皮肤", st.activeSkin.ifBlank { "无皮肤" }))

        // v5.1.30：真正的偏好设置已经换成 PC 同款 settings.html（SettingsWebActivity）。
        // 本页保留为「原生兜底 + 诊断导出」，这里给一个入口指过去。
        root.addView(Button(this).apply {
            text = "打开偏好设置（与 PC 一致）"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, SettingsWebActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(16) })

        // ---- 创建桌面快捷方式（等价 PC 右键「发送到桌面」/ .lnk）----
        root.addView(TextView(this).apply {
            text = "创建桌面快捷方式"
            setTextColor(0xFF8B98A5.toInt()); textSize = 13f
            setPadding(0, dp(20), 0, dp(8))
        })
        val base = if (st.server.isNotBlank()) {
            try { Lines.normalize(st.server, st.line) } catch (_: Exception) { st.server }
        } else ""
        for (e in Apps.all(this)) {
            root.addView(Button(this).apply {
                text = "📌 ${e.name}"
                setOnClickListener {
                    // v5.1.30：成败以 Shortcuts.PinResult 为准（不支持/被拒绝都会给出可执行做法）
                    val r = Shortcuts.requestPin(this@SettingsActivity, e, base)
                    Toast.makeText(this@SettingsActivity, r.msg, Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        }
        root.addView(Button(this).apply {
            text = "📌 飞牛平板（主程序）"
            setOnClickListener {
                val r = Shortcuts.requestPinMain(this@SettingsActivity, base)
                Toast.makeText(this@SettingsActivity, r.msg, Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        root.addView(TextView(this).apply {
            text = "长按应用图标也能看到常用应用的快捷方式（动态快捷方式）。"
            setTextColor(0xFF8B98A5.toInt()); textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })

        root.addView(Button(this).apply {
            text = "安装皮肤包（.fskin.zip）"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, PICK_ZIP)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "已安装皮肤"
            setTextColor(0xFF8B98A5.toInt()); textSize = 13f
            setPadding(0, dp(20), 0, dp(8))
        })

        val skins = Skins.list(this)
        if (skins.isEmpty()) {
            root.addView(row("（无）", "请在 PC 端导出 .fskin.zip 后在此安装"))
        } else {
            for (s in skins) {
                root.addView(Button(this).apply {
                    text = "${s.name}  ·  ${s.heightDp}dp"
                    setOnClickListener {
                        Skins.select(this@SettingsActivity, s.name)
                        Toast.makeText(this@SettingsActivity, "已切换到 ${s.name}", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
            }
            root.addView(Button(this).apply {
                text = "清除皮肤（用默认）"
                setOnClickListener {
                    Skins.select(this@SettingsActivity, "")
                    Toast.makeText(this@SettingsActivity, "已清除", Toast.LENGTH_SHORT).show()
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        }

        // ---- 诊断（原菜单「帮助 → 导出诊断日志」挪到这里，PC 菜单里没有这一项）----
        root.addView(TextView(this).apply {
            text = "诊断"
            setTextColor(0xFF8B98A5.toInt()); textSize = 13f
            setPadding(0, dp(20), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "导出诊断日志"
            setOnClickListener { exportDiagLog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        root.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(24) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 启动日志 + 版本/机型/设置，复制到剪贴板（等价 PC「导出诊断日志」的落盘版） */
    private fun exportDiagLog() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val txt = buildString {
            appendLine("---- FNOS 平板 诊断 ----")
            appendLine("版本: $ver")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("设置: ${Settings.raw(this@SettingsActivity).toString(2)}")
            appendLine("---- startup.log ----")
            append(Log2.tail(this@SettingsActivity, 200))
        }
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("fnos-diag", txt))
            Toast.makeText(this, "诊断日志已复制到剪贴板", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun row(k: String, v: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(this@SettingsActivity).apply {
            text = k; setTextColor(0xFF8B98A5.toInt()); textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f))
        addView(TextView(this@SettingsActivity).apply {
            text = v; setTextColor(0xFFD6DEE8.toInt()); textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
    }

    @Deprecated("兼容旧 API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ZIP && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val name = Skins.installFromZip(this, uri)
            Toast.makeText(this, if (name != null) "已安装：$name" else "安装失败（manifest.json 缺失？）", Toast.LENGTH_LONG).show()
            render()
        }
    }
}
