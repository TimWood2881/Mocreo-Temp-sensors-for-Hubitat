/**
 *  Mocreo Hub Local Connector - Parent App
 *  Version 2.0
 *
 *  Reads sensor data from your Mocreo Hub's LOCAL web portal (on your LAN)
 *  rather than the Mocreo cloud API.  More reliable, works offline, and
 *  avoids cloud authentication timeouts entirely.
 *
 *  HOW IT WORKS
 *  ─────────────
 *  Your Mocreo Hub runs a small web server at its local IP address.
 *  This app:
 *    1. POSTs the hub password to  http://<hub-ip>/login
 *    2. Gets a session cookie back
 *    3. GETs  http://<hub-ip>/sensors  (HTML with sensor data)
 *    4. Parses the data and updates one child device per sensor
 *
 *  FINDING YOUR HUB'S LOCAL IP
 *  ─────────────────────────────
 *  • Mocreo app on your phone → Hub → Device Info
 *  • Or check your router's DHCP client list for a device named "MOCREO"
 *  • TIP: set a static/reserved IP in your router so it never changes
 *
 *  INSTALLATION
 *  ─────────────
 *  1. Apps Code → + New App → paste this file → Save
 *  2. Drivers Code → + New Driver → paste mocreo-driver-temp.groovy → Save
 *  3. Drivers Code → + New Driver → paste mocreo-driver-temphum.groovy → Save
 *  4. Apps → + Add User App → "Mocreo Hub Local Connector"
 *  5. Enter Hub IP, hub password (set during hub setup), poll interval → Done
 *
 *  Author : generated for personal use
 *  License: Apache 2.0
 */

definition(
    name          : "Mocreo Hub Local Connector",
    namespace     : "mocreo",
    author        : "You",
    description   : "Reads Mocreo sensor data from the Hub's local web portal.",
    category      : "Convenience",
    iconUrl       : "",
    iconX2Url     : "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Mocreo Hub Local Connector", install: true, uninstall: true) {

        section("Mocreo Hub") {
            input "hubIp", "text",
                title: "Hub local IP address  (e.g. 192.168.1.42)",
                required: true
            input "hubPassword", "password",
                title: "Hub password  (set during hub hardware setup, NOT your cloud account password)",
                required: true
        }

        section("Poll Interval") {
            input "pollInterval", "enum",
                title: "How often to fetch sensor readings",
                options: ["5":"Every 5 minutes", "10":"Every 10 minutes",
                          "15":"Every 15 minutes", "30":"Every 30 minutes"],
                defaultValue: "10",
                required: true
        }

        section("Logging") {
            input "enableDebug", "bool", title: "Enable debug logging", defaultValue: false
        }

        if (state.lastPoll) {
            section("Status") {
                paragraph "Last successful poll: ${state.lastPoll}"
                paragraph "Sensors found: ${state.sensorCount ?: 0}"
                if (state.lastError) paragraph "⚠️ Last error: ${state.lastError}"
            }
        }
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    logDebug "Mocreo Local app installed."
    initialize()
}

def updated() {
    logDebug "Mocreo Local app updated – rescheduling."
    unschedule()
    state.cookie     = null   // force re-login after settings change
    state.cookieTime = null
    initialize()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    runIn(5, "pollMocreo")
    def mins = (pollInterval ?: "10").toInteger()
    schedule("0 */${mins} * * * ?", "pollMocreo")
    logDebug "Polling every ${mins} min(s)."
}

// ── Main poll ────────────────────────────────────────────────────────────────

def pollMocreo() {
    logDebug "pollMocreo() called"

    def cookie = getHubCookie()
    if (!cookie) {
        def msg = "Could not log in to Mocreo hub at ${hubIp}. Check IP and hub password."
        log.error "Mocreo: ${msg}"
        state.lastError = msg
        return
    }

    def sensors = fetchAndParseSensors(cookie)
    if (!sensors) {
        def msg = "No sensor data returned from hub."
        log.warn "Mocreo: ${msg}"
        state.lastError = msg
        return
    }

    state.sensorCount = sensors.size()
    state.lastPoll    = new Date().toString()
    state.lastError   = null

    sensors.each { processSensor(it) }
    logDebug "Poll complete. ${sensors.size()} sensor(s) updated."
}

// ── Hub login ────────────────────────────────────────────────────────────────

/**
 * POSTs the hub password to http://<hubIp>/login.
 * The hub responds with a Set-Cookie header.
 * Returns the cookie string, or null on failure.
 *
 * Hub sessions expire after ~30 minutes; we cache and reuse within 20 min.
 */
private String getHubCookie() {
    if (state.cookie && state.cookieTime) {
        def ageMins = (now() - (state.cookieTime as Long)) / 60000
        if (ageMins < 20) {
            logDebug "Re-using cached cookie (${Math.round(ageMins)} min old)"
            return state.cookie
        }
    }

    def loginUrl = "http://${hubIp}/login"
    def postBody = "passwd=${URLEncoder.encode(hubPassword as String, 'UTF-8')}"

    logDebug "Logging in at ${loginUrl}"

    String cookie = null
    try {
        def params = [
            uri        : loginUrl,
            contentType: "application/x-www-form-urlencoded",
            body       : postBody,
            timeout    : 15
        ]
        httpPost(params) { resp ->
            logDebug "Login status: ${resp.status}"
            // Hub sets a cookie on success (status 200 or 302)
            def setCookie = resp.headers?.find { it.name?.toLowerCase() == "set-cookie" }?.value
            if (setCookie) {
                cookie           = setCookie.toString().split(";")[0].trim()
                state.cookie     = cookie
                state.cookieTime = now()
                logDebug "Login OK. Cookie: ${cookie}"
            } else {
                // Some firmware returns 200 with no cookie on bad password
                log.warn "Mocreo login: no Set-Cookie header in response (wrong password?). Status: ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "Mocreo login exception: ${e.message}"
    }
    return cookie
}

// ── Fetch & parse /sensors page ───────────────────────────────────────────────

/**
 * GETs http://<hubIp>/sensors with the session cookie.
 * Tries to extract sensor data first from embedded JSON, then from HTML.
 * Returns a List of normalized sensor Maps, or null.
 */
private List fetchAndParseSensors(String cookie) {
    def params = [
        uri       : "http://${hubIp}/sensors",
        headers   : ["Cookie": cookie],
        textParser: true,
        timeout   : 15
    ]

    String html = null
    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                html = resp.data?.text
                logDebug "Got /sensors HTML (${html?.size()} chars)"
            } else if (resp.status in [301, 302]) {
                log.warn "Mocreo /sensors redirected – session likely expired, will re-login next poll."
                state.cookie     = null
                state.cookieTime = null
            } else {
                log.warn "Mocreo /sensors returned HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "Mocreo fetchSensors exception: ${e.message}"
        return null
    }

    if (!html) return null

    // Try JSON block first, then HTML card scraping
    def sensors = tryParseJson(html) ?: tryParseHtml(html)
    if (!sensors) {
        log.warn "Mocreo: Could not parse any sensor data from hub response."
        logDebug "First 500 chars of response: ${html.take(500)}"
    }
    return sensors
}

// ── JSON extraction ───────────────────────────────────────────────────────────

/**
 * Many Mocreo hub firmware versions embed sensor data as a JavaScript
 * variable in the /sensors page, e.g.:
 *   var nodeData = [{...}, {...}];
 * We find the JSON array and parse it.
 */
private List tryParseJson(String html) {
    try {
        // Match any JS variable assigned a JSON array
        def m = html =~ /(?s)var\s+\w+\s*=\s*(\[[\s\S]*?\])\s*[;,]/
        if (!m.find()) {
            logDebug "No embedded JSON array found in /sensors HTML."
            return null
        }
        def jsonStr = m.group(1)
        logDebug "JSON block found (${jsonStr.size()} chars)"

        def raw = new groovy.json.JsonSlurper().parseText(jsonStr)
        if (!(raw instanceof List) || raw.isEmpty()) return null

        def result = raw.collect { normalizeJsonNode(it) }.findAll { it }
        logDebug "JSON gave ${result.size()} sensor(s)"
        return result ?: null
    } catch (Exception e) {
        logDebug "JSON extraction failed: ${e.message}"
        return null
    }
}

/**
 * Normalizes a raw hub JSON node object into our standard sensor Map.
 * The hub uses short field names; we handle known variants.
 *
 * Standard output map keys:
 *   serialNumber  String
 *   model         String  (e.g. "ST6")
 *   name          String
 *   temperature   Double  (Celsius)
 *   humidity      Double? (percent, or null)
 *   battery       Integer? (0-100, or null)
 *   online        Boolean
 */
private Map normalizeJsonNode(def node) {
    if (!(node instanceof Map)) return null
    try {
        String sn = (node.sn ?: node.serial ?: node.id ?: node.nodeId ?: "node-${node.hashCode().abs()}").toString()

        // Temperature – some firmwares send raw integer × 100
        Double tempC = null
        def rt = node.temp ?: node.temperature ?: node.t
        if (rt != null) {
            tempC = rt as Double
            if (tempC > 200) tempC = tempC / 100.0   // was stored as 2250 meaning 22.5°C
        }

        // Humidity
        Double humidity = null
        def rh = node.humi ?: node.humidity ?: node.rh
        if (rh != null) {
            humidity = rh as Double
            if (humidity > 100) humidity = humidity / 100.0
        }

        // Battery
        Integer battery = null
        def rb = node.bat ?: node.battery ?: node.batteryLevel
        if (rb != null) battery = (rb as Double).toInteger()

        // Online
        Boolean online = true
        if (node.online != null)      online = node.online as Boolean
        else if (node.found != null)  online = node.found as Boolean
        else if (node.status != null) online = (node.status.toString() == "1" || node.status.toString().toLowerCase() == "online")

        return [
            serialNumber: sn,
            model       : (node.model ?: node.type ?: "Unknown").toString().toUpperCase(),
            name        : (node.name ?: node.label ?: sn).toString(),
            temperature : tempC,
            humidity    : humidity,
            battery     : battery,
            online      : online
        ]
    } catch (Exception e) {
        log.warn "normalizeJsonNode error: ${e.message} for node: ${node}"
        return null
    }
}

// ── HTML card scraping fallback ───────────────────────────────────────────────

/**
 * If no JSON block is found, we scrape the sensor cards directly.
 * Each sensor is a Bootstrap card containing digit spans.
 * This is best-effort and may need tuning for newer hub firmware.
 */
private List tryParseHtml(String html) {
    List sensors = []
    try {
        // The /sensors page on older Mocreo firmware has cards like:
        //   <div class="card"> ... <span class="digits">22.5</span>°C ...
        //   possibly also a serial number in the card header.
        //
        // We split on card boundaries and parse each block.
        def blocks = html.split(/(?=<div[^>]*class="[^"]*\bcard\b)/).toList()
        logDebug "HTML split into ${blocks.size()} potential blocks"

        blocks.each { block ->
            if (!block.contains("digits") && !block.contains("°")) return
            def s = parseHtmlCard(block)
            if (s) sensors << s
        }
    } catch (Exception e) {
        log.warn "HTML parsing exception: ${e.message}"
    }
    return sensors ?: null
}

private Map parseHtmlCard(String block) {
    try {
        // Serial number in header text (e.g. "SN: 0030AEA400000100")
        def snM = block =~ /(?i)(?:SN|Serial|Node)[:\s]+([A-Z0-9]{8,20})/
        String sn = snM ? snM[0][1] : null

        // All digit spans in this card
        def digitSpans = []
        block.eachMatch(/class="digits"[^>]*>\s*([-\d.]+)\s*</) { m ->
            digitSpans << (m[1] as Double)
        }

        if (digitSpans.isEmpty()) return null

        boolean inFahrenheit = block.contains("°F") || block.contains("&deg;F")
        Double tempC = digitSpans[0]
        if (inFahrenheit && tempC != null) tempC = (tempC - 32) * 5 / 9

        Double humidity = (digitSpans.size() > 1) ? digitSpans[1] : null

        def modelM = block =~ /\b(ST\d+|MS\d+|SW\d+|NS\d+)\b/
        String model = modelM ? modelM[0][1].toUpperCase() : "Unknown"

        Boolean online = !block.toLowerCase().contains("offline")

        if (!sn && tempC == null) return null

        return [
            serialNumber: sn ?: "html-${block.hashCode().abs()}",
            model       : model,
            name        : sn ?: model,
            temperature : tempC,
            humidity    : humidity,
            battery     : null,
            online      : online
        ]
    } catch (Exception e) {
        logDebug "parseHtmlCard error: ${e.message}"
        return null
    }
}

// ── Child device dispatch ────────────────────────────────────────────────────

private void processSensor(Map sensor) {
    String dni        = "mocreo-${sensor.serialNumber}"
    String model      = sensor.model ?: "Unknown"
    String label      = sensor.name  ?: "Mocreo ${model}"
    boolean hasHumid  = sensor.humidity != null
    String driverName = hasHumid ? "Mocreo Temp+Humidity Sensor" : "Mocreo Temperature Sensor"

    def child = getChildDevice(dni)
    if (!child) {
        logDebug "Creating child device: ${label} [${dni}]"
        try {
            child = addChildDevice("mocreo", driverName, dni, [label: label, name: driverName])
        } catch (Exception e) {
            log.error "Could not create child '${label}': ${e.message}"
            return
        }
    }

    def data = [
        temperature : sensor.temperature,
        online      : sensor.online,
        battery     : sensor.battery,
        model       : model,
        lastUpdated : new Date().toString()
    ]
    if (hasHumid) data.humidity = sensor.humidity

    logDebug "Pushing to ${label}: temp=${data.temperature}C, humi=${data.humidity}, bat=${data.battery}"
    child.updateSensorData(data)
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private void logDebug(String msg) {
    if (enableDebug) log.debug "[MocreoLocal] ${msg}"
}
