package com.fnos.tablet

/** 与 PC 端 getNasUA() 完全一致：NAS 看到桌面 UA 才会下发完整桌面版页面 */
object NasUa {
    const val DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
