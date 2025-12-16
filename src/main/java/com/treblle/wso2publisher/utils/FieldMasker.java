package com.treblle.wso2publisher.utils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FieldMasker is responsible for masking sensitive fields in nested JSON data.
 * It recursively masks fields based on provided field names and always masks
 * sensitive headers like 'authorization' and 'x-api-key'.
 */
public class FieldMasker {

    private static final Log log = LogFactory.getLog(FieldMasker.class);

    // Sensitive headers that should always be masked
    private static final List<String> SENSITIVE_HEADERS = Arrays.asList(
        "authorization",
        "x-api-key"
    );

    // Fields to mask
    private final List<String> maskedFields;

    // Pattern to detect base64 encoded data
    private static final Pattern BASE64_PATTERN = Pattern.compile(
        "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$"
    );

    // Threshold for base64 detection (minimum length)
    private static final int BASE64_MIN_LENGTH = 100;

    /**
     * Constructor to initialize FieldMasker with fields to mask.
     *
     * @param maskedFields List of field names to mask
     */
    public FieldMasker(List<String> maskedFields) {
        this.maskedFields = new ArrayList<>();
        // Convert all field names to lowercase for case-insensitive comparison
        for (String field : maskedFields) {
            this.maskedFields.add(field.toLowerCase());
        }
        // Add sensitive headers to masked fields
        for (String header : SENSITIVE_HEADERS) {
            if (!this.maskedFields.contains(header)) {
                this.maskedFields.add(header);
            }
        }
    }

    /**
     * Mask sensitive data in the provided JSONObject.
     *
     * @param data JSONObject containing data to mask
     * @return Masked JSONObject
     */
    public JSONObject mask(JSONObject data) {
        if (data == null) {
            return null;
        }
        return maskObject(data);
    }

    /**
     * Recursively mask fields in a JSONObject.
     *
     * @param obj JSONObject to mask
     * @return Masked JSONObject
     */
    private JSONObject maskObject(JSONObject obj) {
        if (obj == null) {
            return null;
        }

        JSONObject result = new JSONObject();
        for (String key : obj.keySet()) {
            Object value = obj.get(key);
            String lowerKey = key.toLowerCase();

            // Check if this key should be masked
            if (shouldMaskField(lowerKey)) {
                result.put(key, maskValue(value));
            } else if (value instanceof JSONObject) {
                // Recursively mask nested objects
                result.put(key, maskObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                // Recursively mask arrays
                result.put(key, maskArray((JSONArray) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Recursively mask fields in a JSONArray.
     *
     * @param arr JSONArray to mask
     * @return Masked JSONArray
     */
    private JSONArray maskArray(JSONArray arr) {
        if (arr == null) {
            return null;
        }

        JSONArray result = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            Object value = arr.get(i);

            if (value instanceof JSONObject) {
                result.put(maskObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                result.put(maskArray((JSONArray) value));
            } else {
                result.put(value);
            }
        }
        return result;
    }

    /**
     * Check if a field should be masked based on its key.
     *
     * @param lowerKey Field key in lowercase
     * @return true if field should be masked, false otherwise
     */
    private boolean shouldMaskField(String lowerKey) {
        return maskedFields.contains(lowerKey);
    }

    /**
     * Mask a value by replacing it with stars or special message.
     *
     * @param value Value to mask
     * @return Masked value
     */
    private Object maskValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return value;
        }

        String stringValue = value.toString();

        // Check if it's a base64 encoded image
        if (isBase64Image(stringValue)) {
            return "base64 encoded images are too big to process";
        }

        // Replace with stars equal to the length of the value
        return "*".repeat(stringValue.length());
    }

    /**
     * Check if a string is a base64 encoded image.
     *
     * @param value String to check
     * @return true if it's a base64 image, false otherwise
     */
    private boolean isBase64Image(String value) {
        if (value == null || value.length() < BASE64_MIN_LENGTH) {
            return false;
        }

        // Check if it starts with common base64 image prefixes
        if (value.startsWith("data:image/")) {
            return true;
        }

        // Check if it matches base64 pattern and is long enough
        if (value.length() > BASE64_MIN_LENGTH && BASE64_PATTERN.matcher(value).matches()) {
            try {
                // Try to decode as base64
                Base64.getDecoder().decode(value);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        return false;
    }

    /**
     * Get the list of sensitive headers that are always masked.
     *
     * @return List of sensitive header names
     */
    public static List<String> getSensitiveHeaders() {
        return new ArrayList<>(SENSITIVE_HEADERS);
    }

    /**
     * Add a new sensitive header to the list.
     * This is a static method that affects all instances.
     *
     * @param header Header name to add
     */
    public static void addSensitiveHeader(String header) {
        if (!SENSITIVE_HEADERS.contains(header.toLowerCase())) {
            SENSITIVE_HEADERS.add(header.toLowerCase());
        }
    }
}
