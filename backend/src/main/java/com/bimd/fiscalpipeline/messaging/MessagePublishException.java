package com.bimd.fiscalpipeline.messaging;

/** The broker did not confirm a message. The caller must treat it as not sent. */
public class MessagePublishException extends RuntimeException {

    public MessagePublishException(String destination, Throwable cause) {
        super("Broker did not confirm the message to " + destination, cause);
    }
}
