package com.jollydoddger.waymark

import android.content.Context
import android.util.Log
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.WebSearchTool20250305
import com.jollydoddger.waymark.shared.Prefs.anthropicKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the assistant actually did, so the UI shows receipts, not claims. */
data class Action(val summary: String)

data class Reply(val text: String, val actions: List<Action>)

/**
 * The assistant: a Claude tool-use loop over [GeoTools], modelled directly on
 * the one in loose-ends (same pinned SDK; its API quirks are already paid
 * for). The standing rule is in the system prompt and enforced by the shape
 * of the tools: Claude never states a distance, direction, or existence it
 * did not get from a tool — the tools are arithmetic and databases, and
 * Claude is the interpreter between them and a person on a hillside.
 */
class Assistant(private val ctx: Context, private val tools: GeoTools) {

    private sealed interface Wire {
        data class Said(val text: String) : Wire
        data class Answered(val blocks: List<ContentBlockParam>) : Wire
        data class Returned(val blocks: List<ContentBlockParam>) : Wire
    }

    fun ask(question: String): Reply {
        val key = ctx.anthropicKey
        if (key.isEmpty()) {
            return Reply("No Anthropic key yet — add one in ⚙ and the assistant wakes up.", emptyList())
        }
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()

        val wire = mutableListOf<Wire>()
        for ((who, text) in ChatStore.recent(ctx)) {
            when (who) {
                "you" -> wire += Wire.Said(text)
                else -> wire += Wire.Answered(
                    listOf(
                        ContentBlockParam.ofText(
                            TextBlockParam.builder().text(text.ifBlank { "(no reply)" }).build(),
                        ),
                    ),
                )
            }
        }
        wire += Wire.Said(question)

        val actions = mutableListOf<Action>()
        repeat(MAX_STEPS) {
            val response = runCatching { client.messages().create(params(wire)) }
                .onFailure { Log.e(TAG, "assistant call failed", it) }
                .getOrElse { failure ->
                    return Reply(explain(failure.message ?: failure.javaClass.simpleName), actions)
                }

            val text = response.content()
                .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString("\n").trim()
            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }
            val stop = response.stopReason().orElse(null)

            for (block in response.content()) {
                block.serverToolUse().ifPresent { use ->
                    val query = runCatching {
                        (use._input().convert(Map::class.java) as Map<*, *>)["query"] as? String
                    }.getOrNull()
                    actions += Action("Searched the web" + (query?.let { ": \"$it\"" }.orEmpty()))
                }
            }

            // Every block back verbatim — hand-listing block kinds silently
            // drops the server's own search blocks (loose-ends learnt this).
            val echo = { wire += Wire.Answered(response.content().map { it.toParam() }) }

            if (stop == StopReason.PAUSE_TURN) {
                echo()
                return@repeat
            }
            if (stop != StopReason.TOOL_USE || calls.isEmpty()) {
                val reply = text.ifBlank {
                    "Claude declined to answer that one." // refusal or empty turn, said plainly
                }
                ChatStore.append(ctx, question, reply)
                return Reply(reply, actions)
            }

            echo()
            wire += Wire.Returned(
                calls.map { call ->
                    val outcome = runCatching { run(call, actions) }
                        .getOrElse { "Failed: ${it.message ?: "unknown error"}" }
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(call.id()).content(outcome).build(),
                    )
                },
            )
        }
        return Reply("That turned into more steps than expected — try one thing at a time.", actions)
    }

    // --- tool dispatch -------------------------------------------------------

    private fun run(call: ToolUseBlock, actions: MutableList<Action>): String {
        val input = runCatching { call._input().convert(Map::class.java) as Map<*, *> }
            .getOrDefault(emptyMap<String, Any>())
        fun str(key: String): String = (input[key] as? String).orEmpty().trim()
        fun num(key: String): Double = (input[key] as? Number)?.toDouble() ?: 0.0
        fun flag(key: String): Boolean = (input[key] as? Boolean) ?: false

        return when (call.name()) {
            "route_info" -> tools.routeInfo()
            "route_profile" -> tools.routeProfile()
            "find_places" -> {
                val result = tools.findPlaces(str("kind"), flag("along_route"))
                if (result.startsWith("Marked")) {
                    actions += Action(result.lineSequence().first())
                }
                result
            }
            "plan_route" -> {
                val places = (input["via"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                // Roads are avoided unless he says otherwise: the safer
                // default for a walker, and the one he asked for.
                val avoid = (input["avoid_roads"] as? Boolean) ?: true
                val result = tools.planRoute(places, num("circular_km"), avoid, str("start_place"))
                if (result.startsWith("Route set")) {
                    actions += Action(result.substringBefore('.') + " — previous route banked")
                }
                result
            }
            "restore_previous_route" -> {
                val result = tools.restorePreviousRoute()
                if (result.startsWith("Restored")) actions += Action(result.substringBefore('.'))
                result
            }
            "find_walks" -> {
                val result = tools.findWalks(
                    num("radius_km"), str("bearing"), num("min_km"), num("max_km"),
                )
                if ("queued on the map" in result) {
                    actions += Action("Queued walks on the map's picker")
                }
                result
            }
            "download_gpx" -> {
                val result = tools.downloadGpx(str("url"))
                if (!result.startsWith("Failed")) {
                    actions += Action(result.substringBefore(':') + " — on the map's picker")
                }
                result
            }
            "walk_brief" -> tools.walkBrief(num("depart_in_minutes"))
            "measure_to" -> tools.measureTo(str("place"))
            "weather" -> tools.weather()
            "where_am_i" -> tools.whereAmI()
            "clear_markers" -> {
                actions += Action("Cleared the markers")
                tools.clearMarkers()
            }
            else -> "Failed: unknown tool ${call.name()}."
        }
    }

    // --- the request ---------------------------------------------------------

    private fun params(wire: List<Wire>): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(Model.of(MODEL))
            .maxTokens(4_000L)
            // Cache breakpoint after the frozen prompt: one question is up to
            // eight requests within seconds, replaying the same prompt and
            // tool list — rounds two onward read it at a tenth of the price.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(SYSTEM)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
        TOOLS.forEach { builder.addTool(it) }
        builder.addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).build())
        for (turn in wire) {
            when (turn) {
                is Wire.Said -> builder.addUserMessage(turn.text)
                is Wire.Answered -> builder.addAssistantMessageOfBlockParams(turn.blocks)
                is Wire.Returned -> builder.addUserMessageOfBlockParams(turn.blocks)
            }
        }
        return builder.build()
    }

    private fun explain(message: String): String = when {
        "401" in message || "authentication" in message.lowercase() ->
            "The Anthropic key was rejected — check it in ⚙."
        "credit" in message.lowercase() || "billing" in message.lowercase() ->
            "The Anthropic account is out of credit."
        "overloaded" in message.lowercase() || "529" in message ->
            "Anthropic's servers are busy — try again in a moment."
        "Unable to resolve host" in message || "timeout" in message.lowercase() ->
            "No connection to Anthropic — signal?"
        else -> "The assistant call failed: $message"
    }

    companion object {
        private const val TAG = "Assistant"
        const val MODEL = "claude-opus-5"
        const val MAX_STEPS = 8
        const val MAX_SEARCHES = 3L

        private val SYSTEM = """
            You are the assistant inside Waymark, a deliberately simple app that
            shows one walker (in the UK, on a Galaxy phone and watch) as an
            arrow on an Ordnance Survey map, usually following an imported GPX
            route on a walk.

            The one rule: never state a distance, direction, place, or route
            that did not come from a tool result in this conversation. Your
            tools are the app's own grid arithmetic and real databases —
            OpenStreetMap, the Toilet Map, the FOSSGIS foot router, Open-Meteo.
            If a tool fails or finds nothing, say so plainly; never fill the
            gap from general knowledge. Finding nothing in OSM is a fact about
            the database, not the ground — say that too, especially for bins.

            plan_route replaces the current route on the map (the old one is
            banked and restore_previous_route brings it back — mention that
            when you replace a route). It routes on the app's own network, so
            you CAN hold a length and CAN refuse A and B roads; say what it
            actually achieved rather than what was asked for, and if a plan
            comes back too long, too short, or blocked, try again with
            different parameters before settling. The one thing not to trade
            away is the shape: a circular walk means a circuit, so if the
            reply says a large share of it retraces itself, try another
            distance rather than presenting it as a loop. Planned routes
            follow paths mapped in OpenStreetMap: usually right, not gospel;
            advise a glance against the OS map. If OpenStreetMap's query
            servers refuse, that is the database being busy — say so and
            offer to try again, never report it as "no paths here".

            When he wants an established, walked-and-written-up route rather
            than a planned one, chain web_search with download_gpx: search the
            free walking sites (gps-routes.co.uk, walkingclub.org.uk,
            walkingbritain.co.uk, and council or national-park walk pages all
            offer free GPX downloads), then hand download_gpx the walk's own
            page — it digs the GPX link out itself. Several candidates beat
            one: download the plausible ones and they all land on the map's
            picker for him to flick through. Never AllTrails, komoot or OS
            Maps. find_walks (OpenStreetMap + his own library, up to 25 km)
            is the offline-data half of the same question — often worth
            running as well.

            You are expected to plan properly rather than one-shot it: chain
            the tools. A good answer to "plan me a walk for this afternoon"
            is plan_route, then walk_brief on the result, then say in one
            short paragraph what he is getting and whether the weather and
            the light suit it. Keep replies short — they are read on a phone,
            mid-walk, possibly in rain. Use km and metres.

            For "should I set off now?", "will it rain on me?", or "back
            before dark?" call walk_brief — it does the route, weather and
            daylight arithmetic in one go, including where the sun sets.

            Web search is for things the tools cannot know (opening hours, a
            bus time); name the source when you use it.
        """.trimIndent()

        private fun property(type: String, description: String): JsonValue =
            JsonValue.from(mapOf("type" to type, "description" to description))

        private fun schema(
            properties: Map<String, JsonValue>,
            required: List<String>,
        ): Tool.InputSchema {
            val props = Tool.InputSchema.Properties.builder()
            properties.forEach { (name, spec) -> props.putAdditionalProperty(name, spec) }
            return Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(props.build())
                .required(required)
                .build()
        }

        private fun tool(name: String, description: String, schema: Tool.InputSchema): Tool =
            Tool.builder().name(name).description(description).inputSchema(schema).build()

        val TOOLS: List<Tool> = listOf(
            tool(
                "route_info",
                "The loaded route's length, and — given a GPS fix — how far along it he is " +
                    "and how much remains. Measured by the app; always current.",
                schema(emptyMap(), emptyList()),
            ),
            tool(
                "route_profile",
                "Total ascent/descent and high/low points of the loaded route " +
                    "(Open-Meteo terrain model).",
                schema(emptyMap(), emptyList()),
            ),
            tool(
                "find_places",
                "Find real mapped places near him or along the route and mark them on the map. " +
                    "Sources: OpenStreetMap, plus the Toilet Map for toilets.",
                schema(
                    mapOf(
                        "kind" to property(
                            "string",
                            "One of: toilets, cafe, pub, bin, water, parking, defibrillator, bus_stop, bench.",
                        ),
                        "along_route" to property(
                            "boolean",
                            "true = search a corridor along the whole route; false = around his position.",
                        ),
                    ),
                    listOf("kind"),
                ),
            ),
            tool(
                "plan_route",
                "Plan a walking route and set it as the app's route (the old one is banked). " +
                    "Routes on Waymark's OWN network built from OpenStreetMap, not a public " +
                    "router, which means two things you can genuinely promise: it holds the " +
                    "length you ask for (it re-runs on a tighter loop until the distance " +
                    "lands) and it can keep off A and B roads entirely (they are left out of " +
                    "the network, so it cannot stray onto one). It reports the length it " +
                    "actually achieved and the percentage on paths, tracks and bridleways, " +
                    "counted off the route rather than estimated — quote those figures back " +
                    "to him. Takes up to a minute. If it cannot close a loop under the rules " +
                    "it says so; retrying with avoid_roads false, or a different distance, is " +
                    "a sensible next move. Distances are KILOMETRES: convert miles yourself " +
                    "(1 mile = 1.61 km).",
                schema(
                    mapOf(
                        "via" to JsonValue.from(
                            mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string"),
                                "description" to "Place names to route via, in order, nearby (geocoded locally).",
                            ),
                        ),
                        "circular_km" to property(
                            "number",
                            "Rough length in KILOMETRES for a circular walk back to the start " +
                                "(convert from miles first). 0 or absent for point-to-point. " +
                                "Treat it as elastic: the router prefers a genuine circuit at " +
                                "the wrong length over the right length with the walk doubling " +
                                "back on itself, and the reply says which it got.",
                        ),
                        "avoid_roads" to property(
                            "boolean",
                            "Default true: keep off A, B and C roads entirely, walking only " +
                                "paths, tracks, bridleways and quiet lanes. Set false only if " +
                                "he asks, or if a plan failed for want of a link road.",
                        ),
                        "start_place" to property(
                            "string",
                            "Start somewhere other than his current position — a place name, " +
                                "for planning a walk before setting out. Absent = start here.",
                        ),
                    ),
                    emptyList(),
                ),
            ),
            tool(
                "restore_previous_route",
                "Put back the route that the last plan_route or GPX import replaced.",
                schema(emptyMap(), emptyList()),
            ),
            tool(
                "walk_brief",
                "The before-a-walk briefing for the loaded route: length, climb, a " +
                    "Naismith time estimate, rain across the walk's own time window, " +
                    "temperature and wind, and whether he finishes before sunset — with " +
                    "the sunset time and which way the sun goes down. Use it whenever he " +
                    "asks anything shaped like \"should I set off now\", \"will I get " +
                    "rained on\", or \"will I be back before dark\".",
                schema(
                    mapOf(
                        "depart_in_minutes" to property(
                            "number",
                            "Minutes from now until setting off. 0 or absent = leaving now; " +
                                "\"at 3pm\" = minutes between now and 3pm (work it out).",
                        ),
                    ),
                    emptyList(),
                ),
            ),
            tool(
                "find_walks",
                "Find existing walking routes whose LINE passes within a radius of his " +
                    "position — OpenStreetMap's named walking/hiking routes plus his own " +
                    "indexed GPX library, ranked by closest approach. Matches are queued " +
                    "on the map's picker, where he previews each and takes one; nothing " +
                    "is loaded onto the route by this tool. Use the filters when he " +
                    "gives a direction or a length — \"south east, 4-6 miles\" is " +
                    "bearing SE with min/max converted to km.",
                schema(
                    mapOf(
                        "radius_km" to property(
                            "number",
                            "Search radius in KILOMETRES (0.5–25; convert miles first; " +
                                "25 is roughly a 20-minute drive).",
                        ),
                        "bearing" to property(
                            "string",
                            "Optional compass direction from him (N, NE, \"south east\"…): " +
                                "only walks roughly that way. Omit for any direction.",
                        ),
                        "min_km" to property(
                            "number",
                            "Optional minimum route length in km; 0 for no minimum.",
                        ),
                        "max_km" to property(
                            "number",
                            "Optional maximum route length in km; 0 for no maximum.",
                        ),
                    ),
                    listOf("radius_km"),
                ),
            ),
            tool(
                "download_gpx",
                "Download a GPX route and queue it on the map's picker for him to " +
                    "flick through, preview and take — never loaded straight onto the " +
                    "route. Give it either the .gpx file's own URL or the WALK'S PAGE " +
                    "from a walking website: a page is read for its .gpx links, one is " +
                    "followed automatically, several are returned for you to pick from. " +
                    "Free-download sites are fine; NEVER AllTrails, komoot or OS Maps " +
                    "links — their terms forbid it and the tool refuses them.",
                schema(
                    mapOf(
                        "url" to property(
                            "string",
                            "Direct http(s) URL of the .gpx file.",
                        ),
                    ),
                    listOf("url"),
                ),
            ),
            tool(
                "measure_to",
                "Straight-line distance and compass direction from his position to a named place.",
                schema(mapOf("place" to property("string", "The place name.")), listOf("place")),
            ),
            tool(
                "weather",
                "Hourly forecast for the next 8 hours where he is: temperature, rain chance and " +
                    "amount, wind (Open-Meteo).",
                schema(emptyMap(), emptyList()),
            ),
            tool(
                "where_am_i",
                "His position as an OS grid reference (the form mountain rescue uses), nearest " +
                    "named place, and today's sunset time.",
                schema(emptyMap(), emptyList()),
            ),
            tool(
                "clear_markers",
                "Remove the found-place markers from the map on both devices.",
                schema(emptyMap(), emptyList()),
            ),
        )
    }
}

/**
 * A short conversational memory, so "and cafés?" works after "toilets on the
 * route?". A working surface, not an archive: capped, plain JSON.
 */
object ChatStore {
    private const val KEEP = 12
    private fun file(ctx: Context) = File(ctx.filesDir, "chat.json")

    fun recent(ctx: Context): List<Pair<String, String>> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("who") to o.getString("text")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun append(ctx: Context, question: String, reply: String) {
        val all = recent(ctx).toMutableList()
        all.add("you" to question)
        all.add("claude" to reply)
        val arr = JSONArray()
        all.takeLast(KEEP * 2).forEach { (who, text) ->
            arr.put(JSONObject().put("who", who).put("text", text))
        }
        val tmp = File(ctx.filesDir, "chat.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).delete()
            tmp.renameTo(file(ctx))
        }
    }
}
