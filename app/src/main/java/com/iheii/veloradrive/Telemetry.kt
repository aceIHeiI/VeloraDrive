package com.iheii.veloradrive

/** Punto cardinal en español (N, NE, E, SE, S, SO, O, NO) para un rumbo en grados. */
fun cardinal(bearing: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return points[(((bearing % 360f) + 360f) % 360f / 45f + 0.5f).toInt() % 8]
}
