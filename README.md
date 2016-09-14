# Hive Sensors in Samsung SmartThings
## Installation
SmartThings Device Handlers for the British Gas Hive Sensors

1. Add the Device Handlers to your My Device Handlers.
2. Reset the device by removing the battery, waiting 10 seconds, reconnecting, and pressing the tamper button eight times. (If it's discoverable LED will be flashing in bursts of two. If not, start again.)
3. Search for new devices through the SmartThings app and the sensor will be discovered.

Apologies about the rubbish formatting. These were developed live in the online SmartThings IDE and it's a little...special sometimes.

After much experimentation and hair pulling, I have come to some conclusions regarding the Hive sensors that I thought would be useful to anyone else thinking of using them. Here're my findings:

## Background
The Hive sensors are addons that go with the Hive Active Heating range from British Gas. This is an incredibly popular IoT thermostat and hot water control in the UK with more dpeloyments afaik than Nest, as it's actively pushed by one of the main utility providers. Their ecosystem revolves around their hub and their app, and they have been extremely closed minded in the past to external integration. It has taken many years for them to release an official external route in, which is an IFTTT channel, and even that is very limited. The equipment however is Zigbee, so I'd often wondered if I could make use of the sensors myself.

## Motion Sensors

**Accessibility**
They are reachable through the standard Zigbee HA clusters, which surprised me. I'm not sure why but I was expecting them to be more proprietary. They provide the following server clusters:

* 0x0000 - Basic
* 0x0003 - Identity
* 0x0500 - IAS Zone (Specifically attribute 0x0002 which is the IAS 16 bit bitmap)
* 0x0001 - Power (Specifically useful is the attribute 0x0020 which is battery level)
* 0x0020 - This is something manufacturer specific. I've ordered a USB Zigbee modem to try and MITM this and figure it out. Could be for firmware, or perhaps tuning
* 0x0402 - Temperature measurement (Specifically attribute 0x0000)
* 0x0406 - Occupancy sensing. (Attribute 0x0000 should give occupancy, but although this claims to support it never reports)

They will show up as endpoint 02 which is expected.

They ignore *most* attempts to send a `rattr` but reporting does have an effect. It seems to disregard minimum reporting times on most attributes, though does appear to honour maximum times.

It's possible that it's intentionally designed to have a serious amount of rate limiting to save battery. Thinking about the use case in the Hive app this would make sense as nothing in their needs true real-time.

**Temperature Reporting**
They will continually report their temperature, seemingly as fast as every 10 seconds if you want them to, however that temperature they report is not read at the time of the message, it's just the last reading which is cached. The actual time between readings seems to be about 10-15 minutes, sometimes longer. This is bizarre because if you are powering up to send a message then the mW required to also read the sensor is a tiny fraction of the total mW in use. Also the temperature it does report seems to be off by several degrees when it's warm and up to 10 degrees when it's cold.

**Motion Reporting**
If the sensor believes and reports that it is in a state of "inactive" then it will send you a report saying "active" when it detects motion in a reasonably timely fashion, usually less than a second. However going the other way back to "inactive" can sometimes take up to 5 minutes. No good for things like light control or monitoring.

**Tamper Switch**
This bit at least it seems to report insanely quickly, for both tamper true and false.

**Battery Reporting**
The battery reading is as regular as it should be and seems accurate, at least compared to my multimeter.

## Contact Sensor

In complete opposite to the motion sensor, the contact sensor is great.

**Accessibility**
Standard Zigbee HA clusters, supporting the same as the motion sensors above except for 0x0406 (occupancy).

All the same findings as the motion sensors, except for importantly the 0x0500 cluster (IAS) reports in close to real-time.

**Temperature Reporting**
According to the Hive REST API, *and* the discover simple announcemrnt, these support temperature, however I can't get it to report over Zigbee at all.

**Battery Reporting**
The battery reading is again as regular as it should be and seems accurate, at least compared to my multimeter.

**Tamper Switch**
Like the motion sensors, this reports immediately on the 0x0500 cluster.

**Contact Sensor**
On the contact sensors 0x0500 0x0002 reports almost immediately (lass than half a second). It has some rate limiting, I assume to prevent a partially open door from draining the battery, but it resets soon enough and is exactly what you need really.


## Conclusion
The motion sensors are rubbish. :smiley:  They basically lie about their state making them little use for any real-time applications. The contact sensors on the other hand work great, are much smaller and more asthetically pleasing than the Samsung ones, and I'd recommend their use.
