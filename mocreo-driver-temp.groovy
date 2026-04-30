/**
 *  Mocreo Temperature Sensor – Driver
 *
 *  For temperature-only sensors: ST4, ST8, MS1, and compatible models.
 *
 *  Capabilities exposed to Hubitat rules & dashboards:
 *    • TemperatureMeasurement  → attribute: temperature
 *    • Battery                 → attribute: battery
 *    • Sensor
 *    • Refresh
 *
 *  This driver is a "child" device managed by the Mocreo Cloud Connector
 *  parent app.  You do not need to create it manually.
 *
 *  Author : generated for personal use
 *  License: Apache 2.0
 */

metadata {
    definition(
        name      : "Mocreo Temperature Sensor",
        namespace : "TimW",
        author    : "Claude AI Code",
        importUrl : ""
    ) {
        capability "TemperatureMeasurement"   // attribute: temperature (Number)
        capability "Battery"                  // attribute: battery (Number, 0-100)
        capability "Sensor"
        capability "Refresh"

        // Extra attributes visible on device page and usable in rules
        attribute "online",      "string"    // "online" or "offline"
        attribute "model",       "string"    // e.g. "ST8"
        attribute "lastUpdated", "string"    // ISO timestamp from Mocreo cloud
    }

    preferences {
        input "tempUnit", "enum",
            title: "Temperature unit displayed",
            options: ["C": "Celsius (°C)", "F": "Fahrenheit (°F)"],
            defaultValue: "F",
            required: true

        input "enableDebug", "bool",
            title: "Enable debug logging",
            defaultValue: false
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    logDebug "Mocreo Temperature Sensor installed."
    sendEvent(name: "online", value: "unknown")
}

def updated() {
    logDebug "Mocreo Temperature Sensor preferences updated."
    // Re-apply the last known temperature in the chosen unit
    if (state.lastTempC != null) {
        def t = convertTemp(state.lastTempC)
        sendEvent(name: "temperature", value: t, unit: "°${tempUnit}")
    }
}

// ── Command: Refresh ─────────────────────────────────────────────────────────

def refresh() {
    // Asks the parent app to immediately poll Mocreo for fresh data
    logDebug "refresh() called – asking parent to poll."
    parent?.pollMocreo()
}

// ── Called by the parent app ─────────────────────────────────────────────────

/**
 * The parent app calls this method every poll cycle with a map containing:
 *   temperature  – degrees Celsius (Double or null)
 *   battery      – percent (Integer 0-100, or null)
 *   online       – Boolean (or null)
 *   model        – String e.g. "ST8"
 *   lastUpdated  – String timestamp (or null)
 */
def updateSensorData(Map data) {
    logDebug "updateSensorData() → ${data}"

    // ── Temperature ──
    if (data.temperature != null) {
        def tempC = data.temperature as Double
        state.lastTempC = tempC
        def t = convertTemp(tempC)
        sendEvent(name: "temperature", value: t, unit: "°${tempUnit ?: 'F'}")
        logDebug "Temperature: ${t} °${tempUnit ?: 'F'}"
    }

    // ── Battery ──
    if (data.battery != null) {
        sendEvent(name: "battery", value: data.battery as Integer, unit: "%")
    }

    // ── Online status ──
    if (data.online != null) {
        def onlineStr = data.online ? "online" : "offline"
        sendEvent(name: "online", value: onlineStr)
    }

    // ── Metadata ──
    if (data.model)       sendEvent(name: "model",       value: data.model.toString())
    if (data.lastUpdated) sendEvent(name: "lastUpdated", value: data.lastUpdated.toString())
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Converts a Celsius value to the unit selected in preferences.
 * Returns a Double rounded to one decimal place.
 */
private Double convertTemp(Double celsius) {
    if ((tempUnit ?: "F") == "F") {
        return Math.round((celsius * 9 / 5 + 32) * 10) / 10.0
    }
    return Math.round(celsius * 10) / 10.0
}

private void logDebug(String msg) {
    if (enableDebug) log.debug "[Mocreo Temp] ${msg}"
}
