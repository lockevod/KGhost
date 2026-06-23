package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPoint
import com.enderthor.kghost.geo.toDto
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import timber.log.Timber
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming GPX parser built on the JDK SAX API (available on both the Android runtime and the JVM
 * unit-test classpath, unlike [android.util.Xml]/XmlPullParser which throws "Stub!" in unit tests),
 * so this stays fully JVM-testable.
 *
 * Parses `<trkpt lat lon>` points with their `<time>` child. A track is usable for racing only if
 * every point carries a parsable time, so the parse returns null if any point lacks one.
 */
object GpxParser {

    /** Raw collected point: position plus an optional epoch-ms timestamp. */
    private data class RawPoint(val lat: Double, val lng: Double, val timeEpochMs: Long?)

    private val fallbackFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /**
     * One factory, built once and reused for every file (newInstance() does a service-loader lookup,
     * wasteful per-file in a bulk import). XXE hardening is DEFENCE IN DEPTH:
     *  - `disallow-doctype-decl` rejects any DOCTYPE outright — but this feature is Xerces-specific
     *    (the JVM/test backend). On the Karoo's Android runtime the SAX backend is Expat, which does
     *    NOT recognise it: `setFeature` then throws and is swallowed here, so on-device a DOCTYPE may
     *    still be ALLOWED. Hence it cannot be the only line of defence.
     *  - the load-bearing, runtime-independent guarantee is [GpxHandler.resolveEntity], which returns
     *    an empty source so NO external entity (file://, http://) is ever fetched on ANY SAX impl.
     *  - the external-entity features are best-effort belts on top.
     */
    private val factory: SAXParserFactory = SAXParserFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }

    /** Parses [file] into a [RecordedTrack], or null if invalid / missing per-point times. */
    fun parse(file: File): RecordedTrack? = runCatching {
        val handler = GpxHandler()
        val parser = factory.newSAXParser()
        parser.parse(file, handler)
        handler.result
    }.getOrElse { e ->
        Timber.w(e, "GPX parse failed: %s", file.name)
        null
    }

    private fun parseTime(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { fallbackFormat.parse(text)?.time }.getOrNull()
    }

    internal class GpxHandler : DefaultHandler() {
        private val raw = ArrayList<RawPoint>()

        /**
         * XXE defence that holds on EVERY SAX backend (Android Expat included, where the
         * disallow-doctype-decl feature may be silently unavailable): never fetch an external entity.
         * Returning an empty source means a `<!ENTITY x SYSTEM "file:///…">` reference resolves to
         * nothing instead of leaking local files / hitting a URL.
         */
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))

        // State for the trkpt currently being read.
        private var curLat: Double? = null
        private var curLng: Double? = null
        private var curTimeMs: Long? = null
        private var inTrkpt = false

        // <time> character accumulation.
        private var inTime = false
        private val timeBuffer = StringBuilder()

        var result: RecordedTrack? = null
            private set

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
            when (name) {
                "trkpt" -> {
                    inTrkpt = true
                    curLat = attributes?.getValue("lat")?.toDoubleOrNull()
                    curLng = attributes?.getValue("lon")?.toDoubleOrNull()
                    curTimeMs = null
                }
                "time" -> if (inTrkpt) {
                    inTime = true
                    timeBuffer.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inTime && ch != null) timeBuffer.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
            when (name) {
                "time" -> if (inTime) {
                    curTimeMs = parseTime(timeBuffer.toString())
                    inTime = false
                }
                "trkpt" -> {
                    val lat = curLat
                    val lng = curLng
                    // Skip points with missing/NaN coordinates.
                    if (lat != null && lng != null && !lat.isNaN() && !lng.isNaN()) {
                        raw.add(RawPoint(lat, lng, curTimeMs))
                    }
                    inTrkpt = false
                    curLat = null
                    curLng = null
                    curTimeMs = null
                }
            }
        }

        override fun endDocument() {
            // Need at least two points (parity with FitDecoder's >= 2 rule — a single-point track
            // is not a usable ride), and every point must have a parsed time to race against.
            if (raw.size < 2) {
                Timber.w("GPX dropped: %d point(s), need >= 2", raw.size)
                result = null
                return
            }
            val missingTimes = raw.count { it.timeEpochMs == null }
            if (missingTimes > 0) {
                // Common for route exports (Strava/Komoot) that carry no <time>. Log so a silently
                // "failed" import in HistoryImporter has a discoverable reason rather than vanishing.
                Timber.w("GPX dropped: %d/%d point(s) lack a parsable <time> (needed to race)", missingTimes, raw.size)
                result = null
                return
            }

            val firstEpochMs = raw.first().timeEpochMs!!
            val points = ArrayList<TrackPoint>(raw.size)
            var cumulativeM = 0.0
            var prev: RawPoint? = null
            for (rp in raw) {
                val p = prev
                if (p != null) {
                    cumulativeM += Polyline.haversineM(LatLng(p.lat, p.lng), LatLng(rp.lat, rp.lng))
                }
                points.add(
                    TrackPoint(
                        lat = rp.lat,
                        lng = rp.lng,
                        distanceM = cumulativeM,
                        timeS = (rp.timeEpochMs!! - firstEpochMs) / 1000.0,
                    ),
                )
                prev = rp
            }

            val totalDistanceM = points.last().distanceM
            result = RecordedTrack(
                id = "gpx-$firstEpochMs",
                startedAtEpoch = firstEpochMs,
                points = points.map { it.toDto() },
                sourceKey = sourceKeyOf(firstEpochMs, totalDistanceM),
                source = Source.GPX_IMPORT,
            )
        }
    }
}
