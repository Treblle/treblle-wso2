package com.treblle.wso2publisher.handlers;

/**
 * Holds context of the Publisher client retry mechanism.
 */
public class PublisherClientContextHolder {
    public static final ThreadLocal<Integer> PUBLISH_ATTEMPTS = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return Integer.valueOf(1);
        }

        @Override
        public Integer get() {
            return super.get();
        }

        @Override
        public void set(Integer value) {
            super.set(value);
        }
    };
}
