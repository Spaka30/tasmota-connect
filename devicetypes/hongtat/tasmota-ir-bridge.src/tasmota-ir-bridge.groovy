/**
 *  Tasmota - IR Bridge
 *
 *  Copyright 2020 AwfullySmart.com - HongTat Tan
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

metadata {
    definition (name: "Tasmota IR Bridge", namespace: "hongtat", author: "HongTat Tan") {
        capability "Notification"
        capability "Refresh"
        capability "Health Check"
        capability "Signal Strength"

        attribute "irData", "string"
        attribute "lastEvent", "string"
        attribute "lastSeen", "string"
        attribute "version", "string"

        command "refresh"
        command "irSend"
    }

    // simulator metadata
    simulator {
    }

    preferences {
        section {
            input(title: "Device Settings",
                    description: "To view/update this settings, go to the Tasmota (Connect) SmartApp and select this device.",
                    displayDuringSetup: false,
                    type: "paragraph",
                    element: "paragraph")
        }
    }

    // tile definitions
    tiles(scale: 2) {
        standardTile("icon", "icon", width: 1, height: 1, canChangeIcon: false, inactiveLabel: true, canChangeBackground: false) {
            state "default", label: "IR Bridge", action: "", icon: "st.harmony.harmony-hub-icon", backgroundColor: "#FFFFFF"
        }
        valueTile("lastEvent", "device.lastEvent", decoration:"flat", inactiveLabel: false, width: 6, height: 2) {
            state "lastEvent", label:'Last Event:\n${currentValue}'
        }
        valueTile("irData", "device.irData", inactiveLabel: false, decoration: "flat", width: 3, height: 2) {
            state "irData", label: 'IR Data:\n${currentValue}'
        }
        standardTile("refresh", "device.power", inactiveLabel: false, decoration: "flat", width: 6, height: 2) {
            state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        standardTile("lqi", "device.lqi", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
            state "default", label: 'LQI: ${currentValue}'
        }
        standardTile("rssi", "device.rssi", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
            state "default", label: 'RSSI: ${currentValue}dBm'
        }

        main(["icon"])
        details(["lastEvent","irData","refresh"])
    }
}

def installed() {
    sendEvent(name: "checkInterval", value: 30 * 60 + 2 * 60, displayed: false, data: [protocol: "lan", hubHardwareId: device.hub.hardwareID])
    response(refresh())
}

def updated() {
    initialize()
}

def initialize() {
    if (device.hub == null) {
        log.error "Hub is null, must set the hub in the device settings so we can get local hub IP and port"
        return
    }

    def syncFrequency = (parent.generalSetting("frequency") ?: 'Every 1 minute').replace('Every ', 'Every').replace(' minute', 'Minute').replace(' hour', 'Hour')
    try {
        "run$syncFrequency"(refresh)
    } catch (all) { }

    parent.callTasmota(this, "Status 5")
    parent.callTasmota(this, "Backlog Rule1 ON IrReceived#Data DO WebSend ["+device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")+"] /?json={\"IrReceived\":{\"Data\":\"%value%\"}} ENDON;Rule1 1")
    refresh()
}

def parse(String description) {
    def events = null
    def message = parseLanMessage(description)
    def json = parent.getJson(message.header)
    if (json != null) {
        events = parseEvents(200, json)
    }
    return events
}

def calledBackHandler(physicalgraph.device.HubResponse hubResponse) {
    def events = null
    def status = hubResponse.status
    def json = hubResponse.json
    events = parseEvents(status, json)
    return events
}

def parseEvents(status, json) {
    def events = []
    if (status as Integer == 200) {
        def channel = getDataValue("endpoints")?.toInteger()
        def eventdateformat = parent.generalSetting("dateformat")
        def now = new Date().format("${eventdateformat}a", location.timeZone)
        def irData = null

        // Message
        def message = [:]
        message.dst = "*"
        message.lastSeen = now

        // IrReceived
        if (json?.IrReceived != null) {
            if (json?.IrReceived?.Data != null && json?.IrReceived?.Data.toUpperCase() != 'NONE') {
                irData = json.IrReceived.Data.toUpperCase()
                message.irData = irData
                events << sendEvent(name: "irData", value: irData, isStateChange:true, displayed:true)
                events << sendEvent(name: "lastEvent", value: now, isStateChange:true, displayed:false)
                log.debug "IrReceived#Data: '${irData}'"
            }
        }

        // MAC
        if (json?.StatusNET?.Mac != null) {
            def dni = parent.setNetworkAddress(json.StatusNET.Mac)
            def actualDeviceNetworkId = device.deviceNetworkId
            if (actualDeviceNetworkId != state.dni) {
                runIn(10, refresh)
            }
            log.debug "MAC: '${json.StatusNET.Mac}', DNI: '${state.dni}'"
            state.dni = dni
        }

        // Signal Strength
        if (json?.StatusSTS?.Wifi != null) {
            message.Wifi = [RSSI : json?.StatusSTS?.Wifi.RSSI, Signal: json?.StatusSTS?.Wifi.Signal]
            events << sendEvent(name: "lqi", value: json?.StatusSTS?.Wifi.RSSI, displayed: false)
            events << sendEvent(name: "rssi", value: json?.StatusSTS?.Wifi.Signal, displayed: false)
        }

        // Version
        if (json?.StatusFWR?.Version != null) {
            state.lastCheckedVersion = new Date().getTime()
            events << sendEvent(name: "version", value: json.StatusFWR.Version, displayed: false)
        }

        // Cross-device messaging
        events << sendEvent(name: "messenger", value: message.encodeAsJson(), isStateChange: true, displayed:false)

        // Call back
        if (json?.cb != null) {
            parent.callTasmota(this, json.cb)
        }

        // Last seen
        events << sendEvent(name: "lastSeen", value: now, displayed: false)
    }
    return events
}

def irSend(String ir) {
    parent.callTasmota(this, ir)
}

def refresh(dni=null) {
    def lastRefreshed = state.lastRefreshed
    if (lastRefreshed && (now() - lastRefreshed < 5000)) return
    state.lastRefreshed = now()

    // Check version every 30m
    def lastCheckedVersion = state.lastCheckedVersion
    if (!lastCheckedVersion || (lastCheckedVersion && (now() - lastCheckedVersion > (30 * 60 * 1000)))) {
        parent.callTasmota(this, "Status 2")
    }

    def actualDeviceNetworkId = device.deviceNetworkId
    if (state.dni == null || state.dni == "" || actualDeviceNetworkId != state.dni) {
        parent.callTasmota(this, "Status 5")
    }
    parent.callTasmota(this, "Status 11")
}

def ping() {
    refresh()
}

def poll() {
    refresh()
}

