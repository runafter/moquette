package org.eclipse.moquette.server;

import static org.eclipse.moquette.commons.Constants.PERSISTENT_STORE_PROPERTY_NAME;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.moquette.server.config.MemoryConfig;
import org.eclipse.moquette.spi.impl.subscriptions.Subscription;
import org.eclipse.moquette.spi.persistence.MapDBPersistentStore;
import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.Future;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.junit.Before;
import org.junit.Test;

public class ServerIntegrationUnexpectedTerminationTest {
    private static final long CALLBACK_TIMEOUT = 5000L;

    private static final Topic TOPIC = new Topic("/topic", QoS.EXACTLY_ONCE);

//    private static final Logger LOG = LoggerFactory.getLogger(ServerIntegrationUnexpectedTerminationTest.class);

    private static final String MESSAGE = "message";

    private Server m_server;
    private MemoryConfig m_config;

    @Test
    public void shouldPublishAfterTerminateUnexpectly() throws Exception {
        final byte[] payload = MESSAGE.getBytes();

        startServer();
        FutureConnection connectionForSubscribe1 = connectionOf("clientForSubscribe1");
        connect(connectionForSubscribe1);
        subscribe(connectionForSubscribe1);
        stopServer();

        // Manipulate connectionForSubscribe1 Subscription to active
        setSubscriptionToActive("clientForSubscribe1");

        startServer();
        final FutureConnection connectionForSubscribe2 = connectionOf("clientForSubscribe2");
        connect(connectionForSubscribe2);
        subscribe(connectionForSubscribe2);

        FutureConnection connectionForPublish = connectionOf("clientForPublish");
        connect(connectionForPublish);
        Future<Void> futurePublish = connectionForPublish.publish("/topic", payload, QoS.EXACTLY_ONCE, true);
        waitForPublish(futurePublish);

        Future<Message> receiveFuture = connectionForSubscribe2.receive();
        waitForReceiveMessage(payload, receiveFuture);
        stopServer();
    }

    private void waitForReceiveMessage(final byte[] payload, Future<Message> receiveFuture) throws Exception {
        receiveFuture.then(new Callback<Message>() {
            @Override
            public void onSuccess(Message value) {
                value.ack();
                assertThat(value.getPayload(), is(payload));
            }
            @Override
            public void onFailure(Throwable value) {
                fail("receiving was failed.");
            }
        });
        
        try {
            receiveFuture.await(CALLBACK_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("Cannot receive message for " + CALLBACK_TIMEOUT + " ms.");
        }
    }

    private void waitForPublish(Future<Void> futurePublish) throws Exception {
        try {
            futurePublish.await(CALLBACK_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("publishing is not finished for " + CALLBACK_TIMEOUT + " ms.");
        }
    }

    private void connect(FutureConnection connectionForSubscribe1) throws Exception {
        connectionForSubscribe1.connect().await(CALLBACK_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    private void subscribe(FutureConnection connection) throws Exception {
        connection.subscribe(new Topic[] { TOPIC }).await();
    }

    private void setSubscriptionToActive(String clientId) {
        MapDBPersistentStore mapStorage = new MapDBPersistentStore(
                m_config.getProperty(PERSISTENT_STORE_PROPERTY_NAME, ""));
        mapStorage.initStore();
        
        Set<Subscription> clientSubscriptions = new HashSet<Subscription>();
        for (Subscription subscription : mapStorage.listAllSubscriptions()) {
            if (clientId.equals(subscription.getClientId())) {
                clientSubscriptions.add(subscription);
            }
        }

        for (Subscription subscription : clientSubscriptions) {
            subscription.setActive(true);
        }

        mapStorage.updateSubscriptions(clientId, clientSubscriptions);
        mapStorage.close();
    }

    private FutureConnection connectionOf(String clientId) throws URISyntaxException, Exception {
        MQTT mqtt = new MQTT();
        mqtt.setCleanSession(false);
        mqtt.setHost("localhost", 1883);
        mqtt.setKeepAlive((short) 60);
        mqtt.setClientId(clientId);
        mqtt.setWillQos(QoS.EXACTLY_ONCE);
        return mqtt.futureConnection();
    }
    

    private void stopServer() {
        m_server.stopServer();
    }

    private void startServer() throws IOException {
        this.m_server = new Server();
        final Properties configProps = IntegrationUtils.prepareTestPropeties();
        m_config = new MemoryConfig(configProps);
        m_server.startServer(m_config);
    }

    @Before
    public void setUp() {
        File mapDBFile = new File(IntegrationUtils.localMapDBPath());
        if (mapDBFile.exists()) {
            mapDBFile.delete();
        }
    }
}
