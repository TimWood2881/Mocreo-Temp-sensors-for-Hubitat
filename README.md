# Mocreo Hub Local Connector for Hubitat

A community-built Hubitat integration that pulls live sensor readings from your Mocreo temperature and humidity sensors into Hubitat Elevation — entirely over your local network, with no cloud dependency.

---

## Background

Mocreo makes a popular line of Bluetooth Low Energy (BLE) temperature and humidity sensors — models like the ST5, ST6, ST9 (temp + humidity) and ST4, ST8, MS1 (temperature only). The sensors connect to a Mocreo Hub, which bridges them to Wi-Fi and uploads readings to the Mocreo cloud and app.

When this integration was first built, the natural approach was to connect to the Mocreo cloud REST API at `api.mocreo.com`. Mocreo does publish a public OpenAPI schema, and they advertise developer access. However, in practice the login endpoint consistently timed out from Hubitat — likely because the documented API paths weren't matching what the server actually expected, or the cloud API is rate-limited in a way that doesn't play nicely with Hubitat's HTTP client.

The solution came from the broader home-automation community. Users discovered that the Mocreo Hub itself runs a small local web server at its LAN IP address — the same portal accessible by typing `http://<hub-ip>` in a browser. This local portal requires only the hub's own password (set during hardware setup), has no rate limits, works when the internet is down, and responds nearly instantly. That is the approach this integration uses.

**Version 1.0** targeted the Mocreo cloud API — abandoned due to timeout errors.  
**Version 2.0** (current) talks directly to the Hub's local web portal.

---

## How It Works

The integration consists of three Groovy files loaded into Hubitat:

**`mocreo-app.groovy`** — the Parent App. On a configurable schedule it:
1. POSTs your hub password to `http://<hub-ip>/login` and receives a session cookie
2. GETs `http://<hub-ip>/sensors` using that cookie
3. Parses the response — first looking for embedded JSON (used by most firmware versions), then falling back to HTML card scraping if JSON isn't found
4. Creates or updates one Hubitat child device per sensor

**`mocreo-driver-temp.groovy`** — the driver for temperature-only sensors (ST4, ST8, MS1 and similar). Exposes Hubitat's standard `TemperatureMeasurement` and `Battery` capabilities.

**`mocreo-driver-temphum.groovy`** — the driver for temperature + humidity sensors (ST5, ST6, ST9 and similar). Exposes `TemperatureMeasurement`, `RelativeHumidityMeasurement`, and `Battery` capabilities, plus an optional calculated dew point.

Child devices are created automatically the first time a sensor is seen. They are identified by the sensor's serial number, so they survive hub reboots and sensor re-ordering.

---

## Supported Hardware

| Sensor model | Type | Driver used |
|---|---|---|
| ST4 | Temperature only | Mocreo Temperature Sensor |
| ST8 | Temperature only | Mocreo Temperature Sensor |
| MS1 | Temperature only | Mocreo Temperature Sensor |
| ST5 | Temperature + Humidity | Mocreo Temp+Humidity Sensor |
| ST6 | Temperature + Humidity | Mocreo Temp+Humidity Sensor |
| ST9 | Temperature + Humidity | Mocreo Temp+Humidity Sensor |
| Other BLE models | May work | Depends on hub firmware |

Any Mocreo Hub model that has a local web portal at its IP address should work: H1B, H2, H3, H5-Lite, H5-Pro, H6-Lite, H6-Pro. If you are not sure which hub you have, check the label on the bottom of the device.

---

## Prerequisites

- Hubitat Elevation hub (any model) on the same local network as your Mocreo Hub
- Mocreo Hub already set up and showing sensors in the Mocreo app
- The Mocreo Hub's **local IP address** (see below)
- The Mocreo Hub's **local password** — this is the password you created during the hub's hardware setup, **not** your Mocreo cloud account password

### Finding the Hub's Local IP

The easiest ways:

- **Mocreo app** → tap your hub → Device Info — the IP is listed there
- **Your router's admin page** → DHCP client list — look for a device named MOCREO, or with a MAC address starting with `00:30:AE`
- **Type `http://mocreo/`** in a browser on the same network — some hubs respond to that hostname

Once you have the IP, it is strongly recommended to **reserve it in your router** (sometimes called a DHCP reservation or static lease) so the hub always gets the same address. Otherwise the IP could change after a router reboot and break the integration.

---

## Installation

Install the files in this order. Drivers must come before the app.

### Step 1 — Install the Temperature Driver

1. In Hubitat, go to **Drivers Code** in the left sidebar
2. Click **+ New Driver** (top right)
3. Paste the entire contents of `mocreo-driver-temp.groovy`
4. Click **Save**

### Step 2 — Install the Temp+Humidity Driver

1. Click **+ New Driver** again
2. Paste the entire contents of `mocreo-driver-temphum.groovy`
3. Click **Save**

### Step 3 — Install the App

1. Go to **Apps Code** in the left sidebar
2. Click **+ New App**
3. Paste the entire contents of `mocreo-app.groovy`
4. Click **Save**

### Step 4 — Configure and Run

1. Go to **Apps** in the left sidebar
2. Click **+ Add User App**
3. Select **Mocreo Hub Local Connector** from the list
4. Fill in the settings:
   - **Hub local IP address** — e.g. `192.168.1.42` (no `http://`, no trailing slash)
   - **Hub password** — the password from hub setup
   - **Poll interval** — 10 minutes matches the hub's own update frequency; 5 minutes is fine too
   - **Enable debug logging** — turn this on if something isn't working (see Troubleshooting)
5. Click **Done**

The app polls immediately on first run. Within about 15 seconds, new devices will appear under **Devices** — one per sensor, named as they are in your Mocreo app.

---

## Sensor Device Settings

Each sensor device has its own preferences page (go to **Devices**, click the sensor).

**Temperature sensors (ST4, ST8, MS1):**
- `Temperature unit` — Celsius or Fahrenheit; the displayed value switches immediately when saved

**Temp+Humidity sensors (ST5, ST6, ST9):**
- `Temperature unit` — Celsius or Fahrenheit
- `Calculate and show dew point` — when enabled, computes and exposes a `dewPoint` attribute using the Magnus formula; useful for greenhouse or HVAC automations

Both driver types expose these attributes for use in dashboards and rules:
- `temperature` — current reading in your chosen unit
- `battery` — percent remaining (0–100)
- `online` — `"online"` or `"offline"` based on hub report
- `model` — sensor model string (e.g. `"ST6"`)
- `lastUpdated` — timestamp of the last successful poll

The Refresh command on any sensor device asks the parent app to poll the hub immediately rather than waiting for the next scheduled poll.

---

## Troubleshooting

**The app runs but no devices are created**

Turn on debug logging in the app settings, then click Done to trigger a fresh poll. Look in **Logs** for messages starting with `[MocreoLocal]`. Common things to check:

- The IP address field should be just the numbers, e.g. `192.168.1.42` — no `http://` prefix, no port number, no trailing slash
- Make sure you can reach the hub by typing `http://<that-ip>` in a browser on the same network; you should see a login page
- If the browser shows a login page but the app still fails, the password may be wrong — it is the hardware password set during hub setup, not your Mocreo app account password

**Sensors appear but show no data / attributes are blank**

The hub's response format varies slightly between firmware versions. The app tries JSON extraction first, then HTML scraping. Enable debug logging and look for the line that says how many sensors were parsed. If it says 0, the page format may need a small tweak — post the first 500 characters of the hub's `/sensors` page response (shown in the debug log) and the parser can be adjusted.

**"Session expired" warnings in the log**

The hub session cookie lasts roughly 30 minutes. The app caches it and reuses within 20 minutes, so this should be rare. If it happens frequently, try reducing the poll interval to 5 minutes, or check whether your hub's firmware is unusually aggressive about expiring sessions.

**The hub's IP changed and everything stopped working**

Set up a DHCP reservation in your router for the hub's MAC address. Then update the IP in the app settings (Apps → Mocreo Hub Local Connector → hub IP field).

**Devices were created but I want to start fresh**

Go to **Apps → Mocreo Hub Local Connector**, scroll to the bottom, and click **Uninstall**. This removes the app and all child sensor devices. You can then reinstall and reconfigure from scratch.

---

## Version History

| Version | Date | Notes |
|---|---|---|
| 1.0 | April 2026 | Initial release targeting Mocreo cloud REST API (`api.mocreo.com`). Abandoned — login endpoint timed out consistently from Hubitat. |
| 2.0 | April 2026 | Complete rewrite. Now polls the Mocreo Hub's local web portal directly over LAN. More reliable, no cloud dependency, no authentication timeouts. |

---

## Known Limitations

- **Local network only.** The integration communicates with the Mocreo Hub on your LAN. It will not work if Hubitat and the Mocreo Hub are on different networks or VLANs without routing between them.
- **Hub must be powered and online.** If the hub loses power or Wi-Fi, readings will stop until it recovers. The app logs a warning but continues retrying on schedule.
- **Sensor data is only as fresh as the hub's cache.** The Mocreo Hub receives BLE updates from sensors every 3 seconds but only uploads to its web page when values change by more than 0.5°C or 6% RH, or every 10 minutes otherwise. Setting the poll interval below 5 minutes gains little.
- **HTML parsing is firmware-dependent.** The JSON path works on most current firmware versions. The HTML fallback is best-effort and may not work on very old or very new firmware without adjustment.
- **No push/webhook support yet.** Mocreo has announced webhook delivery as "coming soon." When that arrives, this integration could be updated to receive instant push updates rather than polling.

---

## Files

| File | Purpose |
|---|---|
| `mocreo-app.groovy` | Parent app — handles login, polling, scheduling, device creation |
| `mocreo-driver-temp.groovy` | Driver for temperature-only sensors (ST4, ST8, MS1) |
| `mocreo-driver-temphum.groovy` | Driver for temperature + humidity sensors (ST5, ST6, ST9) |
| `mocreo-hubitat-docs.md` | This document |
