package uk.ac.cam.everynet.everynet_feed;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// EverynetFeed.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Receives data via POST on 'module_name/module_id/feed_id'
//
//
// Will WRITE the raw binary post data into:
//   TFC_DATA_MONITOR/<filename>
//   TFC_DATA_BIN/YYYY/MM/DD/<filename>
//
// where <filename> = <UTC TIMESTAMP>_YYYY-MM-DD-hh-mm-ss.bin
// and any prior '.bin' files in TFC_DATA_MONITOR will be deleted
//
// Config values are read from provided vertx config() json file, e.g. see README.md
//
// Will publish the feed data as a JSON string on eventbus.
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpMethod;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;

import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.ArrayList;
import java.util.Base64;

// other tfc_server classes
import uk.ac.cam.everynet.util.Log;
import uk.ac.cam.everynet.util.Constants;

public class EverynetFeed extends AbstractVerticle {

    private final String VERSION = "0.03";
    
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedscraper"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    // maker configs:
    private JsonArray START_FEEDS; // config module_name.feeds parameters
    
    public int LOG_LEVEL; // optional in config(), defaults to Constants.LOG_INFO

    private int HTTP_PORT;            // config <module_name>.http.port

    // local constants
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    // global vars
    private HashMap<String,HttpClient> http_clients; // used to store a HttpClient for each feed_id
    private EventBus eb = null;

    private Log logger;
    
  @Override
  public void start(Future<Void> fut) throws Exception {

    Router router = null;

    String BASE_URI = null;

    // create holder for HttpClients
    http_clients = new HashMap<String,HttpClient>();

    // load initialization values from config()
    if (!get_config())
          {
              Log.log_err("JsonrpcFeed: "+ MODULE_ID + " failed to load initial config()");
              vertx.close();
              return;
          }

    logger = new Log(LOG_LEVEL);
    
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started");

    // create link to EventBus
    eb = vertx.eventBus();

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    // create webserver
    HttpServer http_server = vertx.createHttpServer();

    // create request router for webserver
    router = Router.router(vertx);
    BASE_URI = MODULE_NAME+"/"+MODULE_ID;

    // iterate through all the feed handlers to be started
    // This will add the required
    // BASE_URI/FEED_ID http POST handlers to the router
    for (int i=0; i<START_FEEDS.size(); i++)
        {
          start_maker(START_FEEDS.getJsonObject(i), router, BASE_URI);
        }

    // *********************************************************************
    // connect router to http_server, including the feed POST handlers added
    http_server.requestHandler(router::accept).listen(HTTP_PORT);
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": http server started on port "+ HTTP_PORT);
    
  } // end start()

    // *************************************
    // start a feed maker with a given config
    private void start_maker(JsonObject config, Router router, String BASE_URI)
    {
          // create monitor directory if necessary
          FileSystem fs = vertx.fileSystem();          
          String monitor_path = config.getString("data_monitor");
          if (!fs.existsBlocking(monitor_path))
          {
            try {
                fs.mkdirsBlocking(monitor_path);
                logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                                        ": start_maker created monitor path "+monitor_path);
            } catch (Exception e) {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                        ": start_maker FAIL: error creating monitor path "+monitor_path);
                return;
            }
          }
          // monitor_path now exists

          logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                     ": starting "+MODULE_NAME+" for "+
                     config.getString("feed_id"));

          add_feed_handler(router, BASE_URI, config);

    }

    // ******************************
    // send UP status to the EventBus
    private void send_status()
    {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_msg\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    }

    // ************************************************
    // ************************************************
    // Here is where the essential feed POST is handled
    // create handler for POST from BASE_URI/FEED_ID
    // ************************************************
    // ************************************************
    private void add_feed_handler(Router router, 
                                  String BASE_URI,
                                  JsonObject config)
    {
        final String HTTP_TOKEN = config.getString("http.token");
        final String FEED_ID = config.getString("feed_id");

        final String FEED_URI = "/"+BASE_URI+"/"+FEED_ID;

        router.route(HttpMethod.POST,FEED_URI).handler( ctx -> {
                ctx.request().bodyHandler( buffer -> {
                        try {
                            // read the head value "X-Auth-Token" from the POST
                            String post_token = ctx.request().getHeader("X-Auth-Token");
                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                       ": X-Auth-Token="+post_token);
                            // if the token matches the config(), or config() http.token is null
                            // then parse this assumed gtfs-realtime POST data
                            if (HTTP_TOKEN==null || HTTP_TOKEN.equals(post_token))
                                {
                                    process_feed(buffer, config);
                                }
                        } catch (Exception e) {
                            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+
                                       ": proceed_feed error");
                            logger.log(Constants.LOG_WARN, e.getMessage());
                        }

                        ctx.request().response().end("");
                    });

            });
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+"."+FEED_ID+": POST handler started on "+FEED_URI);
    }

    // ***********************************************    
    // get current local time as "YYYY-MM-DD-hh-mm-ss"
    private String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

  // *****************************************************************
  // process the received raw data
  private void process_feed(Buffer buf, JsonObject config) throws Exception 
  {

    JsonArray request_data = new JsonArray();
    JsonObject params; 
    String dev_eui; // unique identifier from CSN device, contained in json "params>dev_eui"

    try {
           JsonObject jo = new JsonObject(buf.toString());
           request_data.add(jo);
           params = request_data.getJsonObject(0).getJsonObject("params");
           dev_eui = params.getString("dev_eui");
    }
    catch (Exception e) {
        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                   ": exception raised during parsing of feed "+config.getString("feed_id")+":");
        logger.log(Constants.LOG_WARN, e.getMessage());
        return;
    }

    LocalDateTime local_time = LocalDateTime.now();
    
    String day = local_time.format(DateTimeFormatter.ofPattern("dd"));
    String month = local_time.format(DateTimeFormatter.ofPattern("MM"));
    String year = local_time.format(DateTimeFormatter.ofPattern("yyyy"));
    String utc_ts = String.valueOf(System.currentTimeMillis() / 1000);

    // filename without the suffix
    String filename = utc_ts+"_"+local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    // sub-dir structure to store the file
    String filepath = year+"/"+month+"/"+day;
    
    // Write file to DATA_BIN
    //
    final String bin_path = config.getString("data_bin")+"/"+dev_eui+"/"+filepath;
    final String file_suffix = config.getString("file_suffix");
    write_bin_file(buf, bin_path, filename, file_suffix);

    // Write file to DATA_MONITOR
    //
    final String monitor_path = config.getString("data_monitor")+"/"+dev_eui;
    write_monitor_file(buf, monitor_path, filename, file_suffix);

    // Place the received data into a suitable EventBus JsonObject message
    JsonObject msg = new JsonObject();

    msg.put("module_name", MODULE_NAME);
    msg.put("module_id", MODULE_ID);
    msg.put("msg_type", config.getString("msg_type"));
    msg.put("feed_id", config.getString("feed_id"));
    msg.put("filename", filename);
    msg.put("filepath", filepath);
    msg.put("ts", Integer.parseInt(utc_ts));
    msg.put("dev_eui", dev_eui);


    try {            

        // *****************************************************************************
        // **** Only send 'uplink' messages to the eventbus
        // *****************************************************************************
        if (!request_data.getJsonObject(0).getString("method").equals("uplink"))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": skipping non-uplink msg "+utc_ts);
            return;
        }

        if (config.getString("msg_type").equals(Constants.EVERYNET_ASCII_DECIMAL))
        {
            msg.put("decoded_payload", new String(Base64.getDecoder().decode(params.getString("payload"))));
        } else if (config.getString("msg_type").equals(Constants.EVERYNET_ASCII_HEX))
        {
            msg.put("decoded_payload", to_hex(Base64.getDecoder().decode(params.getString("payload"))));
        } else
        {
            msg.put("decoded_payload", params.getString("payload"));
        }

        msg.put("request_data", request_data);
    
        // debug print out the JsonObject message
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": prepared EventBus msg:");
        logger.log(Constants.LOG_DEBUG, msg.toString());

        String feed_address = config.getString("address");

        eb.publish(feed_address, msg);
    
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": published latest GET data to "+feed_address);
    }
    catch (Exception e) {
        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                   ": exception raised during parsing of feed "+config.getString("feed_id")+":");
        logger.log(Constants.LOG_WARN, e.getMessage());
    }
  } // end process_feed()

    // simple function to convert an array of bytes to a HEX ascii string
    private String to_hex(byte[] buf)
    {
        final char[] DIGITS = "0123456789ABCDEF".toCharArray();
//            = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

        final StringBuffer sb = new StringBuffer(buf.length * 2);
        for (int i = 0; i < buf.length; i++) {
            sb.append(DIGITS[(buf[i] >>> 4) & 0x0F]);
            sb.append(DIGITS[buf[i] & 0x0F]);
        }
        return sb.toString();
    }

    // ******************************************************************
    // write_bin_file()
    //
    // Write the 'buf' (i.e. the binary data as received) into a file at
    // 'bin_path/filename/file_suffix'
    //
    private void write_bin_file(Buffer buf, String bin_path, String filename, String file_suffix)
    {
        FileSystem fs = vertx.fileSystem();
        // if full directory path exists, then write file
        // otherwise create full path first
    
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": Writing "+bin_path+"/"+filename + file_suffix);
        fs.exists(bin_path, result -> {
                if (result.succeeded() && result.result())
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": process_feed: path "+bin_path+" exists");
                        write_file(fs, buf, bin_path+"/"+filename+ file_suffix);
                    }
                else
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": Creating directory "+bin_path);
                        fs.mkdirs(bin_path, mkdirs_result -> {
                                if (mkdirs_result.succeeded())
                                    {
                                        write_file(fs, buf, bin_path+"/"+filename+ file_suffix);
                                    }
                                else
                                    {
                                        Log.log_err(MODULE_NAME+"."+MODULE_ID+
                                                    ": error creating tfc_data_bin path "+bin_path);
                                    }
                            });
                    }
        });
    }        

    // ************************************************************************************
    // write_monitor_file()
    //
    // Write 'buf' to file in the filesystem, deleting previous files in the same directory
    // This is convenient for a separate 'inotifywait' process to listen for file-close-write
    // events on that directory and trigger separate processes, e.g. to POST the file onward.
    //
    private void write_monitor_file(Buffer buf, String monitor_path, String filename, String file_suffix)
    {
        FileSystem fs = vertx.fileSystem();

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
               ": Writing "+monitor_path+"/"+filename+ file_suffix);
        fs.readDir(monitor_path, ".*\\"+file_suffix, monitor_result -> {
            if (monitor_result.succeeded())
                {
                    // directory exists, delete previous files of same suffix
                    for (String f: monitor_result.result())
                        {
                            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                       ": Deleting "+f);
                            fs.delete(f, delete_result -> {
                                    if (!delete_result.succeeded())
                                        {
                                          logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                                      ": write_monitor_file error deleting file: "+f);
                                        }
                                });
                        }
                    write_file(fs, buf, monitor_path+"/"+filename+ file_suffix);
                }
            else
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                ": error reading data_monitor path: "+
                                monitor_path);
                    logger.log(Constants.LOG_WARN, monitor_result.cause().getMessage());
                    try {
                        fs.mkdirsBlocking(monitor_path);
                        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                                   ": write_monitor_file created monitor path "+monitor_path);
                        write_file(fs, buf, monitor_path+"/"+filename+ file_suffix);
                    } catch (Exception e) {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": write_monitor_file FAIL: error creating monitor path "+monitor_path);
                    }
                }
        });
    }

  // ***************************************************
  // Write the 'buf' as a file 'filepath' (non-blocking)
  private void write_file(FileSystem fs, Buffer buf, String file_path)
  {
    fs.writeFile(file_path, 
                 buf, 
                 result -> {
      if (result.succeeded()) {
          logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": File "+file_path+" written");
      } else {
        Log.log_err(MODULE_NAME+"."+MODULE_ID+": write_file error ..." + result.cause());
      }
    });
  } // end write_file

    // validate_feeds() will validate a feeds config, and insert default values
    // The config is kept as a JsonArray
    private boolean validate_feeds()
    {
        if (START_FEEDS.size() < 1)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": no "+MODULE_NAME+".feeds in config");
                return false;
            }
        for (int i=0; i<START_FEEDS.size(); i++)
            {
                JsonObject config = START_FEEDS.getJsonObject(i);

                // feed_id is unique for this feed
                if (config.getString("feed_id")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+": feed_id missing in config");
                        return false;
                    }

                String FEED_ID = config.getString("feed_id");

                if (config.getString("data_bin")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": data_bin missing in config");
                        return false;
                    }

                // filesystem path to store the latest 'post_data.bin' file so 
                // it can be monitored for inotifywait processing
                if (config.getString("data_monitor")==null)
                    {
                        Log.log_err(MODULE_NAME+"."+MODULE_ID+".feeds."+FEED_ID+
                                    ": data_monitor missing in config");
                        return false;
                    }

                if (config.getString("file_suffix")==null)
                    {
                        config.put("file_suffix",".bin");
                    }

            }

        return true; // if we got to here then we can return ok, error would have exitted earlier
    }        

    // Load initialization global constants from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "zone"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
            {
                Log.log_err("JsonrpcFeed: config() not set");
                return false;
            }

        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
            {
                Log.log_err(MODULE_NAME+": module.id config() not set");
                return false;
            }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": eb.system_status config() not set");
                return false;
            }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": eb.manager config() not set");
                return false;
            }

        // web address for this FeedHandler to receive POST data messages from original source
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port",0);

        START_FEEDS = config().getJsonArray(MODULE_NAME+".feeds");
        
        if (!validate_feeds())
            {
                Log.log_err(MODULE_NAME+"."+MODULE_ID+": feeds config() not valid");
                return false;
            }
                
        return true;
    }

} // end verticle class
