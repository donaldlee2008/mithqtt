package com.github.longkerdandy.mithril.mqtt.broker;

import com.github.longkerdandy.mithril.mqtt.api.auth.Authenticator;
import com.github.longkerdandy.mithril.mqtt.api.comm.BrokerCommunicator;
import com.github.longkerdandy.mithril.mqtt.api.comm.BrokerListenerFactory;
import com.github.longkerdandy.mithril.mqtt.broker.comm.BrokerListenerFactoryImpl;
import com.github.longkerdandy.mithril.mqtt.broker.handler.AsyncRedisHandler;
import com.github.longkerdandy.mithril.mqtt.broker.session.SessionRegistry;
import com.github.longkerdandy.mithril.mqtt.broker.util.Validator;
import com.github.longkerdandy.mithril.mqtt.storage.redis.async.RedisAsyncStorage;
import com.lambdaworks.redis.RedisURI;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.apache.commons.configuration.PropertiesConfiguration;

/**
 * MQTT Bridge
 */
public class MqttBroker {

    public static void main(String[] args) throws Exception {
        // load config
        PropertiesConfiguration brokerConfig;
        PropertiesConfiguration redisConfig;
        PropertiesConfiguration communicatorConfig;
        if (args.length >= 3) {
            brokerConfig = new PropertiesConfiguration(args[0]);
            redisConfig = new PropertiesConfiguration(args[1]);
            communicatorConfig = new PropertiesConfiguration(args[2]);
        } else {
            brokerConfig = new PropertiesConfiguration("config/broker.properties");
            redisConfig = new PropertiesConfiguration("config/redis.properties");
            communicatorConfig = new PropertiesConfiguration("config/communicator.properties");
        }

        // validator
        Validator validator = new Validator(brokerConfig);

        // session registry
        SessionRegistry registry = new SessionRegistry();

        // storage
        RedisAsyncStorage redis = (RedisAsyncStorage) Class.forName(redisConfig.getString("storage.async.class")).newInstance();
        redis.init(RedisURI.create(redisConfig.getString("redis.uri")));

        // authenticator
        Authenticator authenticator = null;

        // communicator
        BrokerCommunicator communicator = (BrokerCommunicator) Class.forName(communicatorConfig.getString("communicator.class")).newInstance();
        BrokerListenerFactory listenerFactory = new BrokerListenerFactoryImpl(registry);
        communicator.init(communicatorConfig, brokerConfig.getString("broker.id"), listenerFactory);

        // tcp server
        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            // mqtt encoder & decoder
                            p.addLast(new MqttEncoder());
                            p.addLast(new MqttDecoder());
                            // handler
                            p.addLast(new AsyncRedisHandler(authenticator, communicator, redis, registry, brokerConfig, validator));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, brokerConfig.getInt("netty.soBacklog"))
                    .childOption(ChannelOption.SO_KEEPALIVE, brokerConfig.getBoolean("netty.soKeepAlive"));

            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(brokerConfig.getString("mqtt.host"), brokerConfig.getInt("mqtt.port")).sync();

            // Wait until the server socket is closed.
            // Do this to gracefully shut down the server.
            f.channel().closeFuture().sync();
        } finally {
            communicator.destroy();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
