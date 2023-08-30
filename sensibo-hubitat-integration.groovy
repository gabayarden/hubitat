definition(
    name: "Sensibo Integration",
    namespace: "yarden.cloud",
    author: "Yarden Gabay",
    description: "Integrate Sensibo devices with Hubitat",
    category: "Cloud Integration",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage", title: "Sensibo Integration", install: true)
}

def mainPage() {
    dynamicPage(name: "mainPage") {
        section("Sensibo Account") {
            input("apiKey", "text", title: "Sensibo API Key", required: true)
        }
        section("Connected Devices") {
            paragraph "${connectedDevicesToString(state.connectedDevices)}"
        }
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    unschedule()
    initialize()
}

def initialize() {
    if (!settings.apiKey) {
        return
    }
    if(!state.connectedDevices) {
        state.connectedDevices = []
    }
    fetchAndSetupSensiboDevices()
    scheduleStatusRefresh()
}

def scheduleStatusRefresh() {
    runStatusRefresh()
}

def runStatusRefresh() {
    getChildDevices().each { device ->
        def podId = device.deviceNetworkId.split('-').last()
        fetchSensiboStatus(podId)
    }
    runIn(60, runStatusRefresh) // Refresh every 20 seconds
}

def switchEventHandler(evt) {
    if(manualOverride) {
        return
    }
    def podId = evt.device.deviceNetworkId.split('-').last()
    if (evt.value == "on") {
        sendTurnOnRequest(podId)
    } else if (evt.value == "off") {
        sendTurnOffRequest(podId)
    }
}

def sendTurnOffRequest(podId) {
    log.debug "To turn off ${podId}"
    def uri = "https://home.sensibo.com/api/v2/pods/${podId}/acStates?apiKey=${settings.apiKey}"
    def data = [acState: [on: false]]

    httpPostJson(uri: uri, body: data) { response ->
        if (response.status != 200) {
            log.error "Failed to turn off Sensibo device with pod ID ${podId}. Status: ${response.status}"
        } else {
            log.debug "Sent request to turn off Sensibo device with pod ID ${podId}"
        }
    }
}

def sendTurnOnRequest(podId) {
    def uri = "https://home.sensibo.com/api/v2/pods/${podId}/acStates?apiKey=${settings.apiKey}"
    def data = [acState: [on: true]]

    httpPostJson(uri: uri, body: data) { response ->
        if (response.status != 200) {
            log.error "Failed to turn on Sensibo device with pod ID ${podId}. Status: ${response.status}"
        } else {
            log.debug "Sent request to turn on Sensibo device with pod ID ${podId}"
        }
    }
}

def fetchSensiboStatus(podId) {
    def uri = "https://home.sensibo.com/api/v2/pods/${podId}/acStates?limit=1&apiKey=${settings.apiKey}"

    httpGet(uri: uri) { response ->
        def acState = response.data.result[0]?.acState?.on
        updateDeviceStatus(acState, podId)
    }
}

def manualOverride = false

def updateDeviceStatus(status, podId) {
    def deviceNetworkId = "${app.id}-${podId}".replaceAll("\\s+", "_")
    def device = getChildDevice(deviceNetworkId)
    manualOverride = true
    if (status) {
       device.on()
    } else {
       device.off()
    }
    manualOverride = false
}

def fetchAndSetupSensiboDevices() {
    def uri = "https://home.sensibo.com/api/v2/users/me/pods?apiKey=${settings.apiKey}"

    httpGet(uri: uri) { response ->
        response.data.result.each { podData ->
            fetchPodDetailsAndSetupSwitch(podData.id)
        }
    }
}

def fetchPodDetailsAndSetupSwitch(podId) {
    def uri = "https://home.sensibo.com/api/v2/pods/${podId}?apiKey=${settings.apiKey}&fields=room"

    httpGet(uri: uri) { response ->
        if(response.status == 200) {
            def podName = response.data.result?.room?.name ?: podId
            state.connectedDevices << [id: podId, name: podName]
            createOrUpdateVirtualSwitch(podId, podName)
        } else {
            log.error "Failed to retrieve details for Sensibo device with pod ID ${podId}"
        }
    }
}

def createOrUpdateVirtualSwitch(podId, deviceName) {
    def deviceNetworkId = "${app.id}-${podId}".replaceAll("\\s+", "_")
    def virtualSwitch = getChildDevice(deviceNetworkId)
    if (!virtualSwitch) {
        virtualSwitch = addChildDevice("hubitat", "Virtual Switch", deviceNetworkId, null, [label: "${deviceName} Switch", isComponent: false])
        subscribe(virtualSwitch, "switch", "switchEventHandler")
        log.debug "Virtual switch device created and subscribed for pod ID ${podId}"
    }
}

def connectedDevicesToString(list) {
    log.debug "Connected devices: ${list}"
    def strList = []
    list.each { device ->
        strList << "${device.id}: ${device.name}"
    }
    return strList.join("<br>")
}
