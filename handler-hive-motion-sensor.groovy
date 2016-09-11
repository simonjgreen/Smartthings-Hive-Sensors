/*
 *  Copyright 2016 Simon Green
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus


metadata {
	definition (name: "Hive Motion Sensor", namespace: "simonjgreen", author: "Simon Green") {
		capability "Motion Sensor"
		capability "Battery"
		capability "Temperature Measurement"
		capability "Refresh"
		capability "Sensor"

		fingerprint profileId: "0104", outClusters: "0000,0003,0500,0001,0020,0402,0406", manufacturer: "AlertMe.com", model: "PIR00140005", deviceJoinName: "Motion Sensor"
	}

	simulator {
		status "active": "zone report :: type: 19 value: 0031"
		status "inactive": "zone report :: type: 19 value: 0030"
	}

	preferences {
		section {
			input title: "Temperature Offset", description: "This feature allows you to correct any temperature variations by selecting an offset. Ex: If your sensor consistently reports a temp that's 5 degrees too warm, you'd enter '-5'. If 3 degrees too cold, enter '+3'.", displayDuringSetup: false, type: "paragraph", element: "paragraph"
			input "tempOffset", "number", title: "Degrees", description: "Adjust temperature by this many degrees", range: "*..*", displayDuringSetup: false
		}
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"motion", type: "generic", width: 6, height: 4){
			tileAttribute ("device.motion", key: "PRIMARY_CONTROL") {
				attributeState "active", label:'motion', icon:"st.motion.motion.active", backgroundColor:"#53a7c0"
				attributeState "inactive", label:'no motion', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff"
			}
		}
		valueTile("temperature", "device.temperature", width: 2, height: 2) {
			state("temperature", label:'${currentValue}°', unit:"C",
				backgroundColors:[
					[value: 0, color: "#153591"],
					[value: 7, color: "#1e9cbb"],
					[value: 15, color: "#90d2a7"],
					[value: 20, color: "#44b621"],
					[value: 25, color: "#f1d801"],
					[value: 29, color: "#d04e00"],
					[value: 32, color: "#bc2323"]
				]
			)
		}
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}

		main(["motion", "temperature"])
		details(["motion", "temperature", "battery", "refresh"])
	}
}

def parse(String description) {
	log.debug "description: $description"

	Map map = [:]
	if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		map = parseReportAttributeMessage(description)
	}
	else if (description?.startsWith('temperature: ')) {
		map = parseCustomMessage(description)
	}
	else if (description?.startsWith('zone status')) {
		map = parseIasMessage(description)
	}

	// Temporary fix for the case when Device is OFFLINE and is connected again
	if (state.lastActivity == null){
		state.lastActivity = now()
		sendEvent(name: "deviceWatch-lastActivity", value: state.lastActivity, description: "Last Activity is on ${new Date((long)state.lastActivity)}", displayed: false, isStateChange: true)
	}
	state.lastActivity = now()

	log.debug "Parse returned $map"
	def result = map ? createEvent(map) : null

	return result
}

private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def cluster = zigbee.parse(description)
	if (shouldProcessMessage(cluster)) {
		switch(cluster.clusterId) {
			case 0x0001:
				resultMap = getBatteryResult(cluster.data.last())
				break

			case 0x0402:
				// temp is last 2 data values. reverse to swap endian
				String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
				def value = getTemperature(temp)
				resultMap = getTemperatureResult(value)
				break

			case 0x0406:
				log.debug 'motion'
				resultMap.name = 'motion'
				break
		}
	}

	return resultMap
}

private boolean shouldProcessMessage(cluster) {
	// 0x0B is default response indicating message got through
	// 0x07 is bind message
	boolean ignoredMessage = cluster.profileId != 0x0104 ||
		cluster.command == 0x0B ||
		cluster.command == 0x07 ||
		(cluster.data.size() > 0 && cluster.data.first() == 0x3e)
	return !ignoredMessage
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	log.debug "Desc Map: $descMap"

	Map resultMap = [:]
	if (descMap.cluster == "0402" && descMap.attrId == "0000") {
		def value = getTemperature(descMap.value)
		resultMap = getTemperatureResult(value)
	}
	else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
		resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
	}
	else if (descMap.cluster == "0406" && descMap.attrId == "0000") {
		def value = descMap.value.endsWith("01") ? "active" : "inactive"
		resultMap = getMotionResult(value)
	}

	return resultMap
}

private Map parseCustomMessage(String description) {
	Map resultMap = [:]
	if (description?.startsWith('temperature: ')) {
		def value = zigbee.parseHATemperatureValue(description, "temperature: ", getTemperatureScale())
		resultMap = getTemperatureResult(value)
	}
	return resultMap
}

private Map parseIasMessage(String description) {
	ZoneStatus zs = zigbee.parseZoneStatus(description)

	// Some sensor models that use this DTH use alarm1 and some use alarm2 to signify motion
	return (zs.isAlarm1Set() || zs.isAlarm2Set()) ? getMotionResult('active') : getMotionResult('inactive')
}

def getTemperature(value) {
	def celsius = Integer.parseInt(value, 16).shortValue() / 100
	if(getTemperatureScale() == "C"){
		return celsius
	} else {
		return celsiusToFahrenheit(celsius) as Integer
	}
}

private Map getBatteryResult(rawValue) {
	log.debug "Battery rawValue = ${rawValue}"
	def linkText = getLinkText(device)

	def result = [
		name: 'battery',
		value: '--',
		translatable: true
	]

	def volts = rawValue / 10

	if (rawValue == 0 || rawValue == 255) {}
	else {
		if (volts > 3.5) {
			result.descriptionText = "{{ device.displayName }} battery has too much power: (> 3.5) volts."
		}
		else {
			if (device.getDataValue("manufacturer") == "SmartThings") {
				volts = rawValue // For the batteryMap to work the key needs to be an int
				def batteryMap = [28:100, 27:100, 26:100, 25:90, 24:90, 23:70,
								  22:70, 21:50, 20:50, 19:30, 18:30, 17:15, 16:1, 15:0]
				def minVolts = 15
				def maxVolts = 28

				if (volts < minVolts)
					volts = minVolts
				else if (volts > maxVolts)
					volts = maxVolts
				def pct = batteryMap[volts]
				if (pct != null) {
					result.value = pct
                    def value = pct
					result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"
				}
			}
			else {
				def minVolts = 2.1
				def maxVolts = 3.0
				def pct = (volts - minVolts) / (maxVolts - minVolts)
				def roundedPct = Math.round(pct * 100)
				if (roundedPct <= 0)
					roundedPct = 1
				result.value = Math.min(100, roundedPct)
				result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"
			}
		}
	}

	return result
}

private Map getTemperatureResult(value) {
	log.debug 'TEMP'
	if (tempOffset) {
		def offset = tempOffset as int
		def v = value as int
		value = v + offset
	}
    def descriptionText
    if ( temperatureScale == 'C' )
    	descriptionText = '{{ device.displayName }} was {{ value }}°C'
    else
    	descriptionText = '{{ device.displayName }} was {{ value }}°F'

	return [
		name: 'temperature',
		value: value,
		descriptionText: descriptionText,
		translatable: true,
		unit: temperatureScale
	]
}

private Map getMotionResult(value) {
	log.debug 'motion'
	String descriptionText = value == 'active' ? "{{ device.displayName }} detected motion" : "{{ device.displayName }} motion has stopped"
	return [
		name: 'motion',
		value: value,
		descriptionText: descriptionText,
        translatable: true
	]
}

def ping() {
	if (state.lastActivity < (now() - (1000 * device.currentValue("checkInterval"))) ){
		log.info "ping, alive=no, lastActivity=${state.lastActivity}"
		state.lastActivity = null
		return zigbee.readAttribute(0x001, 0x0020) // Read the Battery Level
	} else {
		log.info "ping, alive=yes, lastActivity=${state.lastActivity}"
		sendEvent(name: "deviceWatch-lastActivity", value: state.lastActivity, description: "Last Activity is on ${new Date((long)state.lastActivity)}", displayed: false, isStateChange: true)
	}
}

def refresh() {
	log.debug "refresh called"

return  zigbee.readAttribute(0x0001, 0x0000) +
    zigbee.readAttribute(0x0402, 0x0000) +
    zigbee.readAttribute(0x0500, 0x0002) +
    zigbee.configureReporting(0x0402, 0x0000, 0x29, 120, 600, 0x3dcccccd) +
    zigbee.configureReporting(0x0001, 0x0020, 0x20, 600, 6000, null) +
    zigbee.configureReporting(0x0500, 0x0002, 0x20, 1, 600, null)

    //zigbee.readAttribute(0x0406, 0x0000) Occupancy Sensing
    //zigbee.configureReporting(0x0406, 0x0000, 0x10, 1, 60, null) +
}

def configure() {
	sendEvent(name: "checkInterval", value: 60, displayed: false, data: [protocol: "zigbee"])
	return refresh() // send refresh cmds as part of config
}

private getEndpointId() {
	new BigInteger(device.endpointId, 16).toString()
}

private hex(value) {
	new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
	int i = 0;
	int j = array.length - 1;
	byte tmp;
	while (j > i) {
		tmp = array[j];
		array[j] = array[i];
		array[i] = tmp;
		j--;
		i++;
	}
	return array
}
