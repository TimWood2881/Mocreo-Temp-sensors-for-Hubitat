/**
 *  Mocreo Temp+Humidity Sensor – Driver
 *
 *  For temperature AND humidity sensors: ST5, ST6, ST9, and compatible models.
 *
 *  Capabilities exposed to Hubitat rules & dashboards:
 *    • TemperatureMeasurement  → attribute: temperature
 *    • RelativeHumidityMeasurement → attribute: humidity
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
        name      : "Mocreo Temp+Humidity Sensor",
        namespace : "TimW",
        author    : "Claude AI Code",
        importUrl : ""
    ) {
        capability "TemperatureMeasurement"         // attribute: temperature (Number)
        capability "RelativeHumidityMeasurement"    // attribute: humidity (Number)
        capability "Battery"                        // attribute: battery (Number 0-100)
        capability "Sensor"
        capability "Refresh"

        // Extra attributes visible on device page and usable in rules
        attribute "online",      "string"    // "online" or "offline"
        attribute "model",       "string"    // e.g. "ST6"
        attribute "lastUpdated", "string"    // timestamp from Mocreo cloud
        attribute "dewPoint",    "number"    // calculated dew point in chosen unit
    }

    preferences {
        input "tempUnit", "enum",
            title: "Temperature unit displayed",
            options: ["C": "Celsius (°C)", "F": "Fahrenheit (°F)"],
            defaultValue: "F",
            required: true

        input "calcDewPoint", "bool",
            title: "Calculate and show dew point",
            defaultValue: true

        input "enableDebug", "bool",
            title: "Enable debug logging",
            defaultValue: false
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    logDebug "Mocreo Temp+Humidity Sensor installed."
    sendEvent(name: "online", value: "unknown")
}

def updated() {
    logDebug "Mocreo Temp+Humidity Sensor preferences updated."
    // Re-apply temperature in the newly chosen unit
    if (state.lastTempC != null) {
        def t = convertTemp(state.lastTempC)
        sendEvent(name: "temperature", value: t, unit: "°${tempUnit}")
    }
    // Re-apply dew point if we have the data
    if (calcDewPoint && state.lastTempC != null && state.lastHumidity != null) {
        def dp = dewPoint(state.lastTempC, state.lastHumidity as Double)
        sendEvent(name: "dewPoint", value: convertTemp(dp), unit: "°${tempUnit}")
    }
}

// ── Command: Refresh ─────────────────────────────────────────────────────────

def refresh() {
    logDebug "refresh() called – asking parent to poll."
    parent?.pollMocreo()
}

// ── Called by the parent app ─────────────────────────────────────────────────

/**
 * The parent app calls this method every poll cycle with a map containing:
 *   temperature  – degrees Celsius (Double or null)
 *   humidity     – percent relative humidity (Double or null)
 *   battery      – percent (Integer 0-100, or null)
 *   online       – Boolean (or null)
 *   model        – String e.g. "ST6"
 *   lastUpdated  – String timestamp (or null)
 */
def updateSensorData(Map data) {
    logDebug "updateSensorData() → ${data}"

    def tempC    = (data.temperature != null) ? (data.temperature as Double) : null
    def humidity = (data.humidity    != null) ? (data.humidity    as Double) : null

    // ── Temperature ──
    if (tempC != null) {
        state.lastTempC = tempC
        def t = convertTemp(tempC)
        sendEvent(name: "temperature", value: t, unit: "°${tempUnit ?: 'F'}")
        logDebug "Temperature: ${t} °${tempUnit ?: 'F'}"
    }

    // ── Humidity ──
    if (humidity != null) {
        state.lastHumidity = humidity
        sendEvent(name: "humidity", value: Math.round(humidity * 10) / 10.0, unit: "%")
        logDebug "Humidity: ${humidity}%"
    }

    // ── Dew point ──
    if (calcDewPoint && tempC != null && humidity != null) {
        def dp = dewPoint(tempC, humidity)
        sendEvent(name: "dewPoint", value: convertTemp(dp), unit: "°${tempUnit ?: 'F'}")
        logDebug "Dew point: ${convertTemp(dp)} °${tempUnit ?: 'F'}"
    }

    // ── Battery ──
    if (data.battery != null) {
        sendEvent(name: "battery", value: data.battery as Integer, unit: "%")
    }

    // ── Online status ──
    if (data.online != null) {
        sendEvent(name: "online", value: data.online ? "online" : "offline")
    }

    // ── Metadata ──
    if (data.model)       sendEvent(name: "model",       value: data.model.toString())
    if (data.lastUpdated) sendEvent(name: "lastUpdated", value: data.lastUpdated.toString())
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Magnus formula approximation for dew point.
 * Inputs: temp in Celsius, humidity in % (0-100).
 * Returns: dew point in Celsius.
 */
private Double dewPoint(Double tempC, Double rh) {
    def a = 17.625
    def b = 243.04
    def alpha = Math.log(rh / 100.0) + (a * tempC) / (b + tempC)
    return (b * alpha) / (a - alpha)
}

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
    if (enableDebug) log.debug "[Mocreo TH] ${msg}"
}
