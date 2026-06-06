package com.enderthor.kvpartner.engine

/**
 * Curva monótona (distancia, tiempo) del fantasma. Modelo C de la spec: interpolación lineal
 * en ambos sentidos. Inmutable. Las muestras deben ser estrictamente crecientes en distancia
 * y no decrecientes en tiempo.
 */
class GhostCurve(val samples: List<GhostSample>) {
    init {
        require(samples.size >= 2) { "GhostCurve necesita al menos 2 muestras" }
        for (i in 1 until samples.size) {
            require(samples[i].distanceM > samples[i - 1].distanceM) { "distancia no monótona en $i" }
            require(samples[i].timeS >= samples[i - 1].timeS) { "tiempo decreciente en $i" }
        }
    }

    val totalDistanceM: Double get() = samples.last().distanceM
    val totalTimeS: Double get() = samples.last().timeS

    /** Tiempo del fantasma a [distanceM] metros. Clamp a los extremos fuera de rango. */
    fun timeAt(distanceM: Double): Double {
        if (distanceM <= samples.first().distanceM) return samples.first().timeS
        if (distanceM >= samples.last().distanceM) return samples.last().timeS
        val hi = samples.indexOfFirst { it.distanceM >= distanceM }
        val a = samples[hi - 1]; val b = samples[hi]
        val f = (distanceM - a.distanceM) / (b.distanceM - a.distanceM)
        return a.timeS + f * (b.timeS - a.timeS)
    }

    /** Distancia del fantasma en el instante [timeS]. Clamp a los extremos fuera de rango. */
    fun distanceAt(timeS: Double): Double {
        if (timeS <= samples.first().timeS) return samples.first().distanceM
        if (timeS >= samples.last().timeS) return samples.last().distanceM
        val hi = samples.indexOfFirst { it.timeS >= timeS }
        val a = samples[hi - 1]; val b = samples[hi]
        if (b.timeS == a.timeS) return b.distanceM   // tramo de tiempo plano (parada): salta al final
        val f = (timeS - a.timeS) / (b.timeS - a.timeS)
        return a.distanceM + f * (b.distanceM - a.distanceM)
    }
}
