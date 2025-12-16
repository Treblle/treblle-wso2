package com.treblle.wso2publisher.exceptions;

public class TreblleException extends Exception {

    public TreblleException(String message) {
        super(message);
    }

    public TreblleException(String message, Throwable cause) {
        super(message, cause);
    }

    public static TreblleException missingApiKey() {
        return new TreblleException(
            "No API KEY configured for Treblle. Ensure this is set in your .env before trying again."
        );
    }

    public static TreblleException missingSdkToken() {
        return new TreblleException(
            "No SDK TOKEN configured for Treblle. Ensure this is set in your .env before trying again."
        );
    }
}
