package com.github.longkerdandy.mithril.mqtt.storage.redis;

import com.github.longkerdandy.mithril.mqtt.util.Topics;
import com.lambdaworks.redis.*;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.async.RedisAsyncCommands;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.*;
import org.apache.commons.lang3.BooleanUtils;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.longkerdandy.mithril.mqtt.util.Topics.END;
import static io.netty.buffer.Unpooled.wrappedBuffer;

/**
 * Redis Storage
 */
public class RedisStorage {

    // A scalable thread-safe Redis client. Multiple threads may share one connection if they avoid
    // blocking and transactional operations such as BLPOP and MULTI/EXEC.
    protected RedisClient client;
    // A thread-safe connection to a redis server. Multiple threads may share one StatefulRedisConnection
    protected StatefulRedisConnection<String, String> conn;

    public RedisStorage(String host, int port) {
        this.client = new RedisClient(host, port);
    }

    /**
     * Convert Map to MqttMessage
     *
     * @param map Map
     * @return MqttMessage
     */
    public static MqttMessage mapToMqtt(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;

        int type = Integer.parseInt(map.get("type"));
        if (type == MqttMessageType.PUBLISH.value()) {
            byte[] payload = null;
            if (map.get("payload") != null) try {
                payload = map.get("payload").getBytes("ISO-8859-1");
            } catch (UnsupportedEncodingException ignore) {
            }
            return MqttMessageFactory.newMessage(
                    new MqttFixedHeader(
                            MqttMessageType.PUBLISH,
                            BooleanUtils.toBoolean(map.getOrDefault("dup", "0"), "1", "0"),
                            MqttQoS.valueOf(Integer.parseInt(map.getOrDefault("qos", "0"))),
                            BooleanUtils.toBoolean(map.getOrDefault("retain", "0"), "1", "0"),
                            0
                    ),
                    new MqttPublishVariableHeader(map.get("topicName"), Integer.parseInt(map.getOrDefault("packetId", "0"))),
                    payload == null ? null : wrappedBuffer(payload)
            );
        } else if (type == MqttMessageType.PUBREL.value()) {
            return MqttMessageFactory.newMessage(
                    new MqttFixedHeader(
                            MqttMessageType.PUBREL,
                            false,
                            MqttQoS.AT_LEAST_ONCE,
                            false,
                            0
                    ),
                    MqttMessageIdVariableHeader.from(Integer.parseInt(map.getOrDefault("packetId", "0"))),
                    null
            );
        } else {
            throw new IllegalArgumentException("Invalid in-flight MQTT message type: " + MqttMessageType.valueOf(type));
        }
    }

    /**
     * Convert MqttMessage to Map
     *
     * @param msg MqttMessage
     * @return Map
     */
    public static Map<String, String> mqttToMap(MqttMessage msg) {
        Map<String, String> map = new HashMap<>();
        if (msg == null) return map;

        if (msg.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            map.put("type", String.valueOf(MqttMessageType.PUBLISH.value()));
            map.put("retain", BooleanUtils.toString(msg.fixedHeader().isRetain(), "1", "0"));
            map.put("qos", String.valueOf(msg.fixedHeader().qosLevel().value()));
            map.put("dup", BooleanUtils.toString(msg.fixedHeader().isDup(), "1", "0"));
            map.put("topicName", ((MqttPublishVariableHeader) msg.variableHeader()).topicName());
            map.put("packetId", String.valueOf(((MqttPublishVariableHeader) msg.variableHeader()).messageId()));
            if (msg.payload() != null) try {
                map.put("payload", new String(((ByteBuf) msg.payload()).array(), "ISO-8859-1"));
            } catch (UnsupportedEncodingException ignore) {
            }
            return map;
        } else if (msg.fixedHeader().messageType() == MqttMessageType.PUBREL) {
            map.put("type", String.valueOf(MqttMessageType.PUBREL.value()));
            map.put("qos", "1");
            map.put("packetId", String.valueOf(((MqttPublishVariableHeader) msg.variableHeader()).messageId()));
            return map;
        } else {
            throw new IllegalArgumentException("Invalid in-flight MQTT message type: " + msg.fixedHeader().messageType());
        }
    }

    public void init() {
        // open a new connection to a Redis server that treats keys and values as UTF-8 strings
        this.conn = this.client.connect();
    }

    public void destroy() {
        // shutdown this client and close all open connections
        this.client.shutdown();
    }

    /**
     * Iteration connected clients for the mqtt server node
     *
     * @param node   MQTT Server Node
     * @param cursor Scan Cursor
     * @param count  Limit
     * @return Clients and Cursor
     */
    public RedisFuture<ValueScanCursor<String>> getConnectedClients(String node, String cursor, long count) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.sscan(RedisKey.connectedClients(node), ScanCursor.of(cursor), ScanArgs.Builder.limit(count));
    }

    /**
     * Get connected mqtt broker node for the client
     *
     * @param clientId Client Id
     * @return MQTT Broker Node
     */
    public RedisFuture<String> getConnectedNode(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.get(RedisKey.connectedNode(clientId));
    }

    /**
     * Update connected mqtt broker node for the client
     *
     * @param clientId Client Id
     * @param node     MQTT Broker Node
     * @return RedisFutures (add to server's clients, add to client's servers)
     */
    public List<RedisFuture> updateConnectedNode(String clientId, String node) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.sadd(RedisKey.connectedClients(node), clientId));
        list.add(commands.set(RedisKey.connectedNode(clientId), node));
        return list;
    }

    /**
     * Remove connected mqtt server node for the client
     *
     * @param clientId Client Id
     * @param node     MQTT Server Node
     * @return RedisFutures (remove from server's clients, remove from client's servers)
     */
    public List<RedisFuture> removeConnectedNodes(String clientId, String node) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.srem(RedisKey.connectedClients(node), clientId));
        String[] keys = new String[]{RedisKey.connectedNode(clientId)};
        String[] values = new String[]{node};
        list.add(commands.eval(RedisLua.CHECKDEL, ScriptOutputType.INTEGER, keys, values));
        return list;
    }

    /**
     * Get next packet id for the client
     *
     * @param clientId Client Id
     * @return Next Packet Id
     */
    public RedisFuture<Long> getNextPacketId(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        String[] keys = new String[]{RedisKey.nextPacketId(clientId)};
        String[] values = new String[]{"65535"};
        return commands.eval(RedisLua.INCRLIMIT, ScriptOutputType.INTEGER, keys, values);
    }

    /**
     * Get session existence for the client
     *
     * @param clientId Client Id
     * @return Session Existence (1 clean session, 0 normal session, null not exist)
     */
    public RedisFuture<String> getSessionExist(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.get(RedisKey.session(clientId));
    }

    /**
     * Update session existence for the client
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @return OK if was executed correctly
     */
    public RedisFuture<String> updateSessionExist(String clientId, boolean cleanSession) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.set(RedisKey.session(clientId), BooleanUtils.toString(cleanSession, "1", "0"));
    }

    /**
     * Remove session existence for the client
     *
     * @param clientId Client Id
     * @return 1 removed, 0 not exist
     */
    public RedisFuture<Long> removeSessionExist(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.del(RedisKey.session(clientId));
    }

    /**
     * Remove all session state
     *
     * @param clientId Client Id
     */
    public void removeAllSessionState(String clientId) {
        removeSessionExist(clientId);
        removeAllSubscriptions(clientId);
        removeAllQoS2MessageId(clientId);
        removeAllInFlightMessage(clientId);
    }

    /**
     * Add unacknowledged qos 2 PUBLISH message's packet id from the client
     *
     * @param clientId Client Id
     * @param packetId Packet Id
     * @return 1 packet id added, 0 packet id exist
     */
    public RedisFuture<Long> addQoS2MessageId(String clientId, int packetId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.sadd(RedisKey.qos2Set(clientId), String.valueOf(packetId));
    }

    /**
     * Remove unacknowledged qos 2 PUBLISH message's packet id from the client
     *
     * @param clientId Client Id
     * @param packetId Packet Id
     * @return 1 packet id removed, 0 packet id not exist
     */
    public RedisFuture<Long> removeQoS2MessageId(String clientId, int packetId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.srem(RedisKey.qos2Set(clientId), String.valueOf(packetId));
    }

    /**
     * Remove all unacknowledged qos 2 PUBLISH message's packet id from the client
     *
     * @param clientId Client Id
     * @return 1 packet id removed, 0 packet id not exist
     */
    public RedisFuture<Long> removeAllQoS2MessageId(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.del(RedisKey.qos2Set(clientId));
    }

    /**
     * Get all in-flight message's packet ids for the client
     * Including:
     * QoS 1 and QoS 2 PUBLISH messages which have been sent to the Client, but have not been acknowledged.
     * QoS 0, QoS 1 and QoS 2 PUBLISH messages pending transmission to the Client.
     * QoS 2 PUBREL messages which have been sent from the Client, but have not been acknowledged.
     *
     * @param clientId Client Id
     * @return In-flight message's Packet Ids
     */
    public RedisFuture<List<String>> getAllInFlightMessageIds(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.lrange(RedisKey.inFlightList(clientId), 0, -1);
    }

    /**
     * Get and handle all in-flight message for the client
     * Including:
     * QoS 1 and QoS 2 PUBLISH messages which have been sent to the Client, but have not been acknowledged.
     * QoS 0, QoS 1 and QoS 2 PUBLISH messages pending transmission to the Client.
     * QoS 2 PUBREL messages which have been sent from the Client, but have not been acknowledged.
     *
     * @param clientId Client Id
     * @param handler  In-flight message handler
     */
    public void handleAllInFlightMessage(String clientId, Consumer<Map<String, String>> handler) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        commands.lrange(RedisKey.inFlightList(clientId), 0, -1).thenAccept(ids -> {
            for (String packetId : ids) {
                commands.hgetall(RedisKey.inFlightMessage(clientId, Integer.parseInt(packetId))).thenAccept(handler);
            }
        });
    }

    /**
     * Get specific in-flight message for the client
     *
     * @param clientId Client Id
     * @param packetId Packet Id
     * @return In-flight message in Map format
     */
    public RedisFuture<Map<String, String>> getInFlightMessage(String clientId, int packetId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.hgetall(RedisKey.inFlightMessage(clientId, packetId));
    }

    /**
     * Add in-flight message for the client
     *
     * @param clientId Client Id
     * @param packetId Packet Id
     * @param map      Message as Map
     * @return RedisFutures (add to client's in-flight list, save the message)
     */
    public List<RedisFuture> addInFlightMessage(String clientId, int packetId, Map<String, String> map) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.rpush(RedisKey.inFlightList(clientId), String.valueOf(packetId)));
        list.add(commands.hmset(RedisKey.inFlightMessage(clientId, packetId), map));
        return list;
    }

    /**
     * Remove specific in-flight message for the client
     *
     * @param clientId Client Id
     * @param packetId Packet Id
     * @return RedisFutures (remove from client's in-flight list, remove the message)
     */
    public List<RedisFuture> removeInFlightMessage(String clientId, int packetId) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.lrem(RedisKey.inFlightList(clientId), 0, String.valueOf(packetId)));    // remove all elements
        list.add(commands.del(RedisKey.inFlightMessage(clientId, packetId)));
        return list;
    }

    /**
     * Remove all in-flight message for the client
     *
     * @param clientId Client Id
     */
    public void removeAllInFlightMessage(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        commands.lpop(RedisKey.inFlightList(clientId)).thenAccept(packetId -> {
            if (packetId != null) {
                commands.del(RedisKey.inFlightMessage(clientId, Integer.parseInt(packetId)));
                removeAllInFlightMessage(clientId);
            }
        });
    }

    /**
     * Get the topic's subscriptions
     * Include both clean session's subscriptions
     * Topic Levels must be sanitized using Topics
     *
     * @param topicLevels List of topic levels
     * @return Subscriptions
     */
    public RedisFuture<Map<String, String>> getTopicSubscriptions(List<String> topicLevels) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        if (Topics.isTopicFilter(topicLevels)) {
            return commands.hgetall(RedisKey.topicFilter(topicLevels));
        } else {
            return commands.hgetall(RedisKey.topicName(topicLevels));
        }
    }

    /**
     * Get the client's subscriptions
     *
     * @param clientId Client Id
     * @return Subscriptions
     */
    public RedisFuture<Map<String, String>> getClientSubscriptions(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.hgetall(RedisKey.subscription(clientId));
    }

    /**
     * Update topic name subscription for the client
     * Topic Levels must be sanitized using Topics
     *
     * @param clientId    Client Id
     * @param topicLevels List of topic levels
     * @param qos         Subscription QoS
     * @return RedisFutures (add to client's subscriptions, add to topic's subscriptions, add to topic filter tree)
     */
    public List<RedisFuture> updateSubscription(String clientId, List<String> topicLevels, String qos) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.hset(RedisKey.subscription(clientId), String.join("/", topicLevels), qos));
        if (Topics.isTopicFilter(topicLevels)) {
            list.add(commands.hset(RedisKey.topicFilter(topicLevels), clientId, qos));
            for (int i = 0; i < topicLevels.size(); i++) {
                list.add(commands.hincrby(RedisKey.topicFilterChild(topicLevels.subList(0, i)), topicLevels.get(i), 1));
            }
        } else {
            list.add(commands.hset(RedisKey.topicName(topicLevels), clientId, qos));
        }
        return list;
    }

    /***
     * Remove topic name subscription for the client
     * Topic Levels must be sanitized using Topics
     *
     * @param clientId    Client Id
     * @param topicLevels List of topic levels
     * @return RedisFutures (remove from client's subscriptions, remove from topic's subscriptions, remove from topic filter tree)
     */
    public List<RedisFuture> removeSubscription(String clientId, List<String> topicLevels) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.hdel(RedisKey.subscription(clientId), String.join("/", topicLevels)));
        if (Topics.isTopicFilter(topicLevels)) {
            list.add(commands.hdel(RedisKey.topicFilter(topicLevels), clientId));
            for (int i = 0; i < topicLevels.size(); i++) {
                list.add(commands.hincrby(RedisKey.topicFilterChild(topicLevels.subList(0, i)), topicLevels.get(i), -1));
            }
        } else {
            list.add(commands.hdel(RedisKey.topicName(topicLevels), clientId));
        }
        return list;
    }

    /**
     * Remove all subscriptions for the client
     *
     * @param clientId Client Id
     * @return RedisFutures (remove from topic's subscriptions, remove from topic filter tree, remove from client's subscriptions)
     */
    public List<RedisFuture> removeAllSubscriptions(String clientId) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        commands.hgetall(RedisKey.subscription(clientId)).thenAccept(map -> {
            map.forEach((k, v) -> {
                if (Topics.isTopicFilter(k)) {
                    list.add(commands.hdel(RedisKey.topicFilter(k), clientId));
                    List<String> levels = Topics.sanitizeTopicFilter(k);
                    for (int i = 0; i < levels.size(); i++) {
                        list.add(commands.hincrby(RedisKey.topicFilterChild(levels.subList(0, i)), levels.get(i), -1));
                    }
                } else {
                    list.add(commands.hdel(RedisKey.topicName(k), clientId));
                }
            });
            list.add(commands.del(RedisKey.subscription(clientId)));
        });
        return list;
    }

    /**
     * Get possible topic filter tree nodes matching the topic
     * Topic Levels must be sanitized using Topics
     *
     * @param topicLevels List of topic levels
     * @param index       Current match level
     * @return Possible matching children
     */
    protected RedisFuture<List<String>> getMatchTopicFilter(List<String> topicLevels, int index) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        if (index == topicLevels.size() - 1) {
            return commands.hmget(RedisKey.topicFilterChild(topicLevels.subList(0, index)), END, "#");
        } else {
            return commands.hmget(RedisKey.topicFilterChild(topicLevels.subList(0, index)), topicLevels.get(index), "#", "+");
        }
    }

    /**
     * Get and handle all topic subscriptions matching the topic
     * This is a recursion method
     * Topic Levels must be sanitized using Topics
     *
     * @param topicLevels List of topic levels
     * @param index       Current match level (use 0 if you have doubt)
     * @param handler     Subscriptions Handler (Key - Client Id, Value - QoS Level)
     */
    public void handleMatchSubscriptions(List<String> topicLevels, int index, Consumer<Map<String, String>> handler) {
        // topic name
        if (index == 0) getTopicSubscriptions(topicLevels).thenAccept(handler);

        // topic filter
        getMatchTopicFilter(topicLevels, index).thenAccept(children -> {
            // last one
            if (children.size() == 2) {
                int c = children.get(0) == null ? 0 : Integer.parseInt(children.get(0)); // char
                int s = children.get(1) == null ? 0 : Integer.parseInt(children.get(1)); // #
                if (c > 0) {
                    getTopicSubscriptions(topicLevels).thenAccept(handler);
                }
                if (s > 0) {
                    List<String> newTopicLevels = new ArrayList<>(topicLevels.subList(0, index));
                    newTopicLevels.add("#");
                    newTopicLevels.add(END);
                    getTopicSubscriptions(newTopicLevels).thenAccept(handler);
                }
            }
            // not last one
            else if (children.size() == 3) {
                int c = children.get(0) == null ? 0 : Integer.parseInt(children.get(0)); // char
                int s = children.get(1) == null ? 0 : Integer.parseInt(children.get(1)); // #
                int p = children.get(2) == null ? 0 : Integer.parseInt(children.get(2)); // +
                if (c > 0) {
                    handleMatchSubscriptions(topicLevels, index + 1, handler);
                }
                if (s > 0) {
                    List<String> newTopicLevels = new ArrayList<>(topicLevels.subList(0, index));
                    newTopicLevels.add("#");
                    newTopicLevels.add(END);
                    getTopicSubscriptions(newTopicLevels).thenAccept(handler);
                }
                if (p > 0) {
                    List<String> newTopicLevels = new ArrayList<>(topicLevels);
                    newTopicLevels.set(index, "+");
                    handleMatchSubscriptions(newTopicLevels, index + 1, handler);
                }
            }
        });
    }

    /**
     * Get all retain message's packet ids for the topic name
     *
     * @param topicLevels Topic Levels
     * @return Retain message's Packet Ids
     */
    public RedisFuture<List<String>> getAllRetainMessageIds(List<String> topicLevels) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.lrange(RedisKey.topicRetainList(topicLevels), 0, -1);
    }

    /**
     * Get and handle all retain message for the client
     *
     * @param topicLevels Topic Levels
     * @param handler     Retain message handler
     */
    public void handleAllRetainMessage(List<String> topicLevels, Consumer<Map<String, String>> handler) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        commands.lrange(RedisKey.topicRetainList(topicLevels), 0, -1).thenAccept(ids -> {
            for (String packetId : ids) {
                commands.hgetall(RedisKey.topicRemainMessage(topicLevels, Integer.parseInt(packetId))).thenAccept(handler);
            }
        });
    }

    /**
     * Add retain message for the topic name
     *
     * @param topicLevels Topic Levels
     * @param packetId    Packet Id
     * @param map         Message as Map
     * @return RedisFutures (add to topic name's retain list, save the message)
     */
    public List<RedisFuture> addRetainMessage(List<String> topicLevels, int packetId, Map<String, String> map) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.rpush(RedisKey.topicRetainList(topicLevels), String.valueOf(packetId)));
        list.add(commands.hmset(RedisKey.topicRemainMessage(topicLevels, packetId), map));
        return list;
    }

    /**
     * Get specific retain message for the topic name
     *
     * @param topicLevels Topic Levels
     * @param packetId    Packet Id
     * @return Retain message in Map format
     */
    public RedisFuture<Map<String, String>> getRetainMessage(List<String> topicLevels, int packetId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.hgetall(RedisKey.topicRemainMessage(topicLevels, packetId));
    }

    /**
     * Remove all retain message for the topic name
     *
     * @param topicLevels Topic Levels
     */
    public void removeAllRetainMessage(List<String> topicLevels) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        commands.lpop(RedisKey.topicRetainList(topicLevels)).thenAccept(packetId -> {
            if (packetId != null) {
                commands.del(RedisKey.topicRemainMessage(topicLevels, Integer.parseInt(packetId)));
                removeAllRetainMessage(topicLevels);
            }
        });
    }
}
