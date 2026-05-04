package com.treblle.wso2publisher.commons;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.Properties;

/**
 * Converts a PropertyUtils file data into JSONObject and back.
 * @author JSON.org
 */
public class PropertyUtils {
    /**
     * Converts a property file object into a JSONObject. The property file object is a table of name value pairs.
     * @param properties java.util.Properties
     * @return JSONObject
     * @throws JSONException
     */
    public static JSONObject toJSONObject(Properties properties) throws JSONException {
        JSONObject jo = new JSONObject();
        if (properties != null && !properties.isEmpty()) {
            Enumeration<?> enumProperties = properties.propertyNames();
            while(enumProperties.hasMoreElements()) {
                String name = (String)enumProperties.nextElement();
                jo.put(name, properties.getProperty(name));
            }
        }
        return jo;

    }

    /**
     * Converts the JSONObject into a property file object.
     * @param jo JSONObject
     * @return java.util.Properties
     * @throws JSONException
     */
    public static Properties toProperties(JSONObject jo)  throws JSONException {
        Properties  properties = new Properties();
        if (jo != null) {
            Iterator<?> keys = jo.keys();

            while (keys.hasNext()) {
                String name = keys.next().toString();
                
                Object object = jo.get(name);
                //Test is object is a string other just add it. Fixes embedded JSON objects
                if (object instanceof String) {
                    properties.put(name, (String)object);
                } else {
                    properties.put(name, object);
                }
            }
        }
        return properties;
    }
}