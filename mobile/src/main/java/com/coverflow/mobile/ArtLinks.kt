package com.coverflow.mobile

/**
 * Music apps often link their list thumbnails to an image server that can serve the same
 * picture bigger. For the servers we know, this rewrites the link to ask for a bigger size.
 * The links are only tried; anything that fails falls back to the original.
 */
object ArtLinks {
    private val googleWh = Regex("=w(\\d+)-h(\\d+)")
    private val googleS = Regex("=s(\\d+)(?=-|$)")
    private val youtubeThumb = Regex("^(https?://(?:i\\d?\\.ytimg\\.com|img\\.youtube\\.com)/vi(?:_webp)?/[^/]+/)(default|mqdefault|hqdefault|sddefault)\\.(?:jpg|webp)(\\?.*)?$")
    private val spotifySmall = Regex("ab67616d0000(4851|1e02)")
    private val appleSize = Regex("/(\\d+)x(\\d+)(bb|cc|sr|bf)?\\.(jpg|jpeg|png|webp)")
    private val deezerSize = Regex("/(\\d+)x(\\d+)-")

    /** Bigger versions of [url] to try first, best first. Empty if we don't know the server. */
    fun sharper(url: String, size: Int): List<String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyList()
        val host = hostOf(url)
        val out = ArrayList<String>(2)

        when {
            // YouTube Music, Google Play: ".../photo=w120-h120-l90-rj" or "...=s120"
            host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com") -> {
                var u = url
                googleWh.find(u)?.let { m ->
                    if (m.groupValues[1].toInt() < size) u = u.replaceRange(m.range, "=w$size-h$size")
                }
                googleS.find(u)?.let { m ->
                    if (m.groupValues[1].toInt() < size) u = u.replaceRange(m.range, "=s$size")
                }
                out += u
            }

            // YouTube video thumbnails: the HD frames, when the video has them.
            youtubeThumb.matches(url) -> {
                val m = youtubeThumb.find(url)!!
                val base = m.groupValues[1].replace("/vi_webp/", "/vi/")
                out += base + "maxresdefault.jpg"
                out += base + "hq720.jpg"
            }

            // Spotify: 64 px and 300 px covers have a 640 px sibling.
            host == "i.scdn.co" -> out += spotifySmall.replace(url, "ab67616d0000b273")

            // Apple Music / iTunes: ".../100x100bb.jpg", or an unfilled "{w}x{h}" template.
            host.endsWith("mzstatic.com") -> {
                var u = url.replace("{w}", "$size").replace("{h}", "$size").replace("{f}", "jpg")
                    .replace("%7Bw%7D", "$size").replace("%7Bh%7D", "$size").replace("%7Bf%7D", "jpg")
                appleSize.findAll(u).lastOrNull()?.let { m ->
                    if (m.groupValues[1].toInt() < size) u = u.replaceRange(m.range, "/${size}x${size}bb.jpg")
                }
                out += u
            }

            // Deezer: ".../cover/<id>/56x56-000000-80-0-0.jpg"
            host.endsWith("dzcdn.net") -> deezerSize.findAll(url).lastOrNull()?.let { m ->
                if (m.groupValues[1].toInt() < size) out += url.replaceRange(m.range, "/${size}x${size}-")
            }
        }
        return out.filter { it != url }.distinct()
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
}
