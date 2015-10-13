package com.github.longkerdandy.mithril.mqtt.api.comm;

/**
 * Broker Listener Factory
 */
public interface BrokerListenerFactory {

    /**
     * Create a new BrokerListener
     *
     * @return BrokerListener
     */
    BrokerListener newListener(BrokerCommunicator communicator);
}
