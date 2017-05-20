# Everynet feedhandler / application servers

This repo has the source of development 'application servers' for the Everynet LoraWAN network.

## Example Json data packet from the CSN

The main function of this feed handler is to receive the sensor data from the Cambridge Sensor Network, as example below:
```
{
    "params": {
        "payload": "nhtSAwkwAAAWUeEmEHM=",
        "port": 1,
        "dev_addr": "175b5b50",
        "radio": {
            "stat": 1,
            "gw_band": "EU863-870",
            "server_time": 1495228962.576152,
            "modu": "LORA",
            "gw_gps": {
                "lat": 52.20502,
                "alt": 76,
                "lon": 0.10843},
            "gw_addr": "70b3d54b13c80000",
            "chan": 4,
            "gateway_time": 1495228962.523077,
            "datr": "SF12BW125",
            "tmst": 2295430404,
            "codr": "4/5",
            "rfch": 1,
            "lsnr": -16.0,
            "rssi": -119,
            "freq": 868.1,
            "size": 27},
        "counter_up": 127,
        "dev_eui": "0018b2000000113e",
        "rx_time": 1495228962.523077,
        "encrypted_payload": "dKf1XcEJUJNJFnerXEY="},
    "jsonrpc": "2.0",
    "id": "00421fc2adf8",
    "method": "uplink"}
```

## Typical configuration file (src/main/resources/uk.ac.cam.everynet.everynet_feed.A.json)
```
{
    "main":    "uk.ac.cam.everynet.everynet_feed.EverynetFeed",
    "options":
        { "config":
          {

            "module.name":           "everynet_feed",
            "module.id":             "A",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",
              
            "everynet_feed.log_level":   1,

            "everynet_feed.http.port":   8087,

            "everynet_feed.feeds":     [
                                       { 
                                         "feed_id" :   "ascii_decimal",
                                         "area_id" :   "cam",

                                         "file_suffix":   ".json",
                                         "data_bin" :     "/home/ijl20/everynet_data/data_bin",
                                         "data_monitor" : "/home/ijl20/everynet_data/data_monitor",

                                         "msg_type" :  "everynet_ascii_decimal",
                                         "address" :   "tfc.everynet_feed.A"
                                       },
                                       { 
                                         "feed_id" :   "ascii_hex",
                                         "area_id" :   "cam",

                                         "file_suffix":   ".json",
                                         "data_bin" :     "/home/ijl20/everynet_data/data_bin",
                                         "data_monitor" : "/home/ijl20/everynet_data/data_monitor",

                                         "msg_type" :  "everynet_ascii_hex",
                                         "address" :   "tfc.everynet_feed.A"
                                       }
                                     ]
          }
        }
}
```

Each feed handler will http listen on the url <module.name>/<module.id>/<feed_id>

I.e. the above configuration will listen on TWO urls:

/everynet_feed/A/ascii_decimal

and

/everynet_feed/A/ascii_hex

In this case the feedhandler will listen on port 8087 (and it is assumed Nginx will route the POST requests appropriately)

The feed handler will store each received data packed TWICE, as:

<data_bin>/<dev_eui>/YY/MM/DD/<UTC_timestamp>_YY-MM-DD-hh-mm-ss.json

and

<data_monitor>/<dev_eui>/<UTC_timestamp>_YY-MM-DD-hh-mm-ss.json

The latter will OVERWRITE any file previously stored in that directory. The idea is that a separate Unix process could inotifywait on that directory and wake
up when new files arrive.

