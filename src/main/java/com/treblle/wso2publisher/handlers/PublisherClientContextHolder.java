package com.treblle.wso2publisher.handlers;

/**
 * Holds context of the Publisher client retry mechanism.
 */
public class PublisherClientContextHolder {
    public static final ThreadLocal<Integer> PUBLISH_ATTEMPTS = new ThreadLocal<Integer>() {

        // Method to initialize the ThreadLocal variable with a default value of 1
        @Override
        protected Integer initialValue() {
            return Integer.valueOf(1);
        }

        // Method to get the current value of the ThreadLocal variable
        @Override
        public Integer get() {
            return super.get();
        }

        // Method to set the value of the ThreadLocal variable
        @Override
        public void set(Integer value) {
            super.set(value);
        }
    };
}
