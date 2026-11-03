package com.kakario.tggallery

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 기본 아이콘 묶음에 없는 아이콘 몇 개 (머티리얼 아이콘 모양) */
object Ic {
    private fun v(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val Pause = v("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Play = v("play", "M8,5v14l11,-7z")
    val Download = v("download", "M5,20h14v-2H5v2zM19,9h-4V3H9v6H5l7,7 7,-7z")
    val Grid = v("grid", "M4,4h7v7H4zM13,4h7v7h-7zM4,13h7v7H4zM13,13h7v7h-7z")
    val RotateLock = v("rotlock", "M23.25,12.77l-2.57,-2.57 -1.41,1.41 2.22,2.22 -5.66,5.66L4.51,8.17l5.66,-5.66 2.1,2.1 1.41,-1.41L11.23,0.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L2.75,7.11c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36c0.59,-0.59 0.59,-1.54 0,-2.12zM8.47,20.48C5.2,18.94 2.86,15.76 2.5,12H1c0.51,6.16 5.66,11 11.95,11l0.66,-0.03 -3.81,-3.82 -1.33,1.33zM16,9h5c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1v-0.5C21,1.12 19.88,0 18.5,0S16,1.12 16,2.5V3c-0.55,0 -1,0.45 -1,1v4c0,0.55 0.45,1 1,1zM16.8,2.5c0,-0.94 0.76,-1.7 1.7,-1.7s1.7,0.76 1.7,1.7V3h-3.4v-0.5z")
    val Rotate = v("rot", "M16.48,2.52c3.27,1.55 5.61,4.72 5.97,8.48h1.5C23.44,4.84 18.29,0 12,0l-0.66,0.03 3.81,3.81 1.33,-1.32zM10.23,1.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L1.75,8.11c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36c0.59,-0.59 0.59,-1.54 0,-2.12L10.23,1.75zM14.83,21.19L2.81,9.17l6.36,-6.36 12.02,12.02 -6.36,6.36zM7.52,21.48C4.25,19.94 1.91,16.76 1.55,13H0.05C0.56,19.16 5.71,24 12,24l0.66,-0.03 -3.81,-3.81 -1.33,1.32z")
    val SelectAll = v("selall", "M3,5h2V3c-1.1,0 -2,0.9 -2,2zM3,13h2v-2H3v2zM7,21h2v-2H7v2zM3,9h2V7H3v2zM13,3h-2v2h2V3zM19,3v2h2c0,-1.1 -0.9,-2 -2,-2zM5,21v-2H3c0,1.1 0.9,2 2,2zM3,17h2v-2H3v2zM9,3H7v2h2V3zM11,21h2v-2h-2v2zM19,13h2v-2h-2v2zM19,21c1.1,0 2,-0.9 2,-2h-2v2zM19,9h2V7h-2v2zM19,17h2v-2h-2v2zM15,21h2v-2h-2v2zM15,5h2V3h-2v2zM7,17h10V7H7v10zM9,9h6v6H9V9z")
}
