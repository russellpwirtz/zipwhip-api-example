package com.zipwhip.api.signals;

import com.ning.http.client.AsyncHttpClient;
import com.zipwhip.api.signals.dto.BindResult;
import com.zipwhip.api.signals.dto.DeliveredMessage;
import com.zipwhip.api.signals.dto.SubscribeResult;
import com.zipwhip.concurrent.ObservableFuture;
import com.zipwhip.events.Observer;
import com.zipwhip.important.ImportantTaskExecutor;
import com.zipwhip.signals2.presence.UserAgent;
import com.zipwhip.signals2.presence.UserAgentCategory;
import com.zipwhip.timers.HashedWheelTimer;
import com.zipwhip.timers.Timer;
import com.zipwhip.util.Factory;
import com.zipwhip.util.StringUtil;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Created by IntelliJ IDEA.
 * User: Russ
 * Date: 9/4/13
 * Time: 5:09 PM
 */
public class Example {

    private static final Logger LOGGER = LoggerFactory.getLogger(Example.class);
    private static final String SUBSCRIBE_URL = "/signal/subscribe";

    private static String apiHost = "http://network.zipwhip.com:80";
    private static String signalsHost = "http://localhost:8000";
    private static String sessionKey = null;
    private static int clients = 1;
    private static Boolean doInject = false;
    private static List<TestClient> connectedClients = new ArrayList<TestClient>();

    private static ImportantTaskExecutor importantTaskExecutor = new ImportantTaskExecutor();
    private static AsyncHttpClient asyncHttpClient = new AsyncHttpClient();

    public static void main(String... args) throws Exception {
        BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("%d{dd MMM HH:mm:ss,SSS}\t%5p\t[%F:%L]\t- %m%n")));

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-apiHost")) {
                apiHost = args[++i];
            } else if (args[i].equals("-signalsHost")) {
                signalsHost = args[++i];
            } else if (args[i].equals("-sessionKey")) {
                sessionKey = args[++i];
            } else if (args[i].equals("-clients")) {
                clients = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-doInject")) {
                doInject = Boolean.parseBoolean(args[++i]);
            }
        }

        Executor executor = Executors.newSingleThreadExecutor();
        Timer timer = new HashedWheelTimer();

        SocketIoSignalConnectionFactory signalConnectionFactory = new SocketIoSignalConnectionFactory();

        signalConnectionFactory.setSignalsUrl(signalsHost);
        signalConnectionFactory.setImportantTaskExecutor(importantTaskExecutor);
        signalConnectionFactory.setExecutor(executor);

        SignalProviderFactory signalProviderFactory = new SignalProviderFactory();
        Factory<BufferedOrderedQueue<DeliveredMessage>> bufferedOrderedQueueFactory = new SilenceOnTheLineBufferedOrderedQueueFactory(timer);

        signalProviderFactory.setSignalConnectionFactory(signalConnectionFactory);
        signalProviderFactory.setImportantTaskExecutor(importantTaskExecutor);
        signalProviderFactory.setBufferedOrderedQueueFactory(bufferedOrderedQueueFactory);
        signalProviderFactory.setSignalsSubscribeActor(new NingSignalsSubscribeActor(asyncHttpClient, apiHost + SUBSCRIBE_URL));

        for (int i = 0; i < clients; i++) {
            Thread.sleep(50);

            LOGGER.debug("Connecting " + i);
            connect(signalProviderFactory).await();
            LOGGER.debug("Connected " + i);
        }

        if (doInject) {
            new Thread(){
                @Override
                public void run() {
                    while(true) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {}

                        if (connectedClients.isEmpty()) {
                            LOGGER.warn("No connected clients!");
                            break;
                        }

                        Random random = new Random();
                        TestClient client = connectedClients.get(random.nextInt(connectedClients.size()));

                        if (!client.signalProvider.isConnected()) {
                            LOGGER.error("Client wasn't connected, skipping!");
                            continue;
                        }

                        Iterator<String> i = client.channels.iterator();
                        for (int j = 0; j < random.nextInt(client.channels.size()); j++){
                            i.next();
                        }

                        try {
                            inject(i.next(), client.sessionKey);
                        } catch (IOException e) {
                            LOGGER.error("IOError: ", e);
                        }
                    }
                }
            }.start();
        }
    }

    private static CountDownLatch connect(SignalProviderFactory signalProviderFactory) {
        UserAgent userAgent = new UserAgent();
        userAgent.setBuild("zipwhip-api example");
        userAgent.setCategory(UserAgentCategory.Desktop);
        userAgent.setVersion("1.0.0");

        final TestClient client = createTestClient(signalProviderFactory);

        final String[] clientId = {null};
        return client.connect(userAgent, clientId[0], clientId[0]);

    }

    private static TestClient createTestClient(SignalProviderFactory signalProviderFactory) {
        return new TestClient((SignalProviderImpl) signalProviderFactory.create());
    }

    public static final Observer<ObservableFuture<SubscribeResult>> SUBSCRIBE_OBSERVER = new Observer<ObservableFuture<SubscribeResult>>() {
        public void notify(Object sender, ObservableFuture<SubscribeResult> item) {
            if (item.isFailed()) {
                LOGGER.error("Couldn't subscribe! " + item.getCause());
                return;
            }

            LOGGER.debug("Subscribed!");
        }
    };

    public static final Observer<BindResult> BIND_RESULT_OBSERVER = new Observer<BindResult>() {
        public void notify(Object sender, BindResult item) {
            LOGGER.debug("Received bind event for clientId: " + item.getClientId() + ". Anything before " + item.getTimestamp() + " is considered backfill.");
        }
    };

    private static class TestClient {

        private SignalProviderImpl signalProvider;
        private Set<String> channels;
        private String clientId;
        private String subscriptionId;
        private String sessionKey;

        public TestClient(SignalProviderImpl signalProvider) {
            this.signalProvider = signalProvider;

            signalProvider.getSignalReceivedEvent().addObserver(new SignalObserver());
            signalProvider.getBindEvent().addObserver(BIND_RESULT_OBSERVER);
        }

        public CountDownLatch connect(UserAgent userAgent, String clientId, String token) {
            final CountDownLatch latch = new CountDownLatch(1);

            ObservableFuture<Void> connectFuture = signalProvider.connect(userAgent, clientId, token);

            connectFuture.addObserver(new Observer<ObservableFuture<Void>>() {
                public void notify(Object sender, ObservableFuture <Void> item) {
                    if (item.isFailed()) {
                        LOGGER.error("Couldn't connect! " + item.getCause());
                        latch.countDown();
                        return;
                    }

                    LOGGER.debug("Connected!");
                    TestClient.this.clientId = signalProvider.getClientId();

                    if (StringUtil.isNullOrEmpty(Example.sessionKey)) {
                        TestClient.this.sessionKey = UUID.randomUUID().toString();
                    } else {
                        TestClient.this.sessionKey = Example.sessionKey;
                    }
                    ObservableFuture<SubscribeResult> future = signalProvider.subscribe(TestClient.this.sessionKey, null);

                    future.addObserver(SUBSCRIBE_OBSERVER);
                    future.addObserver(new Observer<ObservableFuture<SubscribeResult>>() {
                        @Override
                        public void notify(Object sender, ObservableFuture<SubscribeResult> item) {
                            if (item.isCancelled() || item.isFailed()) {
                                LOGGER.error("Failed to subscribe!", item.getCause());
                                latch.countDown();
                                return;
                            }

                            latch.countDown();

                            channels = item.getResult().getChannels();
                            subscriptionId = item.getResult().getSubscriptionId();

                            connectedClients.add(TestClient.this);
                        }
                    });
                }
            });

            return latch;
        }
    }

    public static void inject(String channel, String session) throws IOException {
        AsyncHttpClient.BoundRequestBuilder builder = asyncHttpClient.preparePost(apiHost + "/signal/inject");

        builder.addParameter("channel", channel);
        builder.addParameter("session", session);
        builder.addParameter("content", UUID.randomUUID().toString());

        builder.execute();
    }

}
