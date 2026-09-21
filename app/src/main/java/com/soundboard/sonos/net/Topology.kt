package com.soundboard.sonos.net

/**
 * Reads the Sonos zone-group topology so a clip can be played on a single speaker
 * even when it is part of a multi-room group.
 */
object Topology {

    /** One Sonos group: its coordinator and every member (both are RINCON_… ids). */
    data class Group(val coordinatorUdn: String, val memberUdns: List<String>)

    private val coordinatorRegex = Regex("Coordinator=\"(RINCON_[^\"]+)\"")
    private val memberUuidRegex = Regex("<ZoneGroupMember[^>]*\\bUUID=\"(RINCON_[^\"]+)\"")

    /**
     * Queries [soapBaseUrl] for the household topology. Returns the list of groups,
     * or an empty list if the topology could not be read.
     */
    fun groups(soapBaseUrl: String): List<Group> {
        val soap = SoapClient(soapBaseUrl)
        val response = soap.invoke(SoapClient.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", emptyList())
        val raw = soap.extract(response, "ZoneGroupState") ?: return emptyList()
        // The value is (possibly double-)escaped XML; unescape until stable, then parse.
        val xml = fullyUnescape(raw)
        return parse(xml)
    }

    /** The group that contains [playerUdn], or null if not found. */
    fun groupOf(groups: List<Group>, playerUdn: String): Group? =
        groups.firstOrNull { it.memberUdns.contains(playerUdn) || it.coordinatorUdn == playerUdn }

    private fun parse(xml: String): List<Group> {
        val result = mutableListOf<Group>()
        // Split into <ZoneGroup …> … </ZoneGroup> blocks.
        val blocks = xml.split("<ZoneGroup ").drop(1)
        for (block in blocks) {
            val body = block.substringBefore("</ZoneGroup>", block)
            val coordinator = coordinatorRegex.find(block)?.groupValues?.get(1) ?: continue
            val members = memberUuidRegex.findAll(body).map { it.groupValues[1] }.toList()
            val memberList = if (members.isEmpty()) listOf(coordinator) else members
            result.add(Group(coordinator, memberList))
        }
        return result
    }

    private fun fullyUnescape(s: String): String {
        var current = s
        repeat(3) {
            val next = current
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
            if (next == current) return next
            current = next
        }
        return current
    }
}
