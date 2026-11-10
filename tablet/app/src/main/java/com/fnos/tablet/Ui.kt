package com.fnos.tablet

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 窗口外观工具。
 *
 * 标题栏改由网页自绘后，系统状态栏会压在自绘标题栏上面（PC 端没有这一层），
 * 所以进入桌面/应用窗时切沉浸式：系统栏隐藏、下滑临时呼出，画面等同于 PC 的无边框窗口。
 */
object Ui {

    fun immersive(act: AppCompatActivity, on: Boolean) {
        try {
            val w = act.window
            WindowCompat.setDecorFitsSystemWindows(w, !on)
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) {
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Throwable) {}
    }
}
