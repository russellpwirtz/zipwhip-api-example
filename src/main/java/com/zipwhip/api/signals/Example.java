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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
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

    private static String apiHost;
    private static String signalsHost = null;
    private static String sessionKey = null;
    private static int clients = 1;
//    private static String clientId;

    private static ImportantTaskExecutor importantTaskExecutor = new ImportantTaskExecutor();
    private static AsyncHttpClient asyncHttpClient = new AsyncHttpClient();

    public static void main(String... args) throws Exception {
        BasicConfigurator.configure();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-apiHost")) {
                apiHost = args[++i];
            } else if (args[i].equals("-signalsHost")) {
                signalsHost = args[++i];
            } else if (args[i].equals("-sessionKey")) {
                sessionKey = args[++i];
            } else if (args[i].equals("-clients")) {
                clients = Integer.parseInt(args[++i]);
            }
        }

//        if (StringUtil.isNullOrEmpty(sessionKey)) {
//            System.err.println("The required -sessionKey parameter is missing");
//            return;
//        }

        if (StringUtil.isNullOrEmpty(apiHost)) {
            apiHost = "http://network.zipwhip.com:80";
        }

        if (StringUtil.isNullOrEmpty(signalsHost)) {
            signalsHost = "http://localhost:8000";
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
            Thread.sleep(500);

            LOGGER.debug("Connecting " + i);

            connect(signalProviderFactory).await();

            LOGGER.debug("Connected " + i);
        }
    }

    private static CountDownLatch connect(Factory<SignalProvider> signalProviderFactory) {
        final SignalProvider signalProvider = signalProviderFactory.create();

        signalProvider.getSignalReceivedEvent().addObserver(new SignalObserver());
        signalProvider.getBindEvent().addObserver(BIND_RESULT_OBSERVER);

        final String[] clientId = {null};
        final CountDownLatch latch = new CountDownLatch(1);
//        clientId = "d21952f2-d7a6-4d7c-9452-e6d517548e93";

        UserAgent userAgent = new UserAgent();
        userAgent.setBuild("zipwhip-api example");
        userAgent.setCategory(UserAgentCategory.Desktop);
        userAgent.setVersion("1.0.0");

        ObservableFuture<Void> connectFuture = signalProvider.connect(userAgent, clientId[0], clientId[0]);

        connectFuture.addObserver(new Observer<ObservableFuture<Void>>() {
            public void notify(Object sender, ObservableFuture <Void> item) {
                if (item.isFailed()) {
                    LOGGER.error("Couldn't connect! " + item.getCause());
                    return;
                }

                LOGGER.debug("Connected!");
                clientId[0] = signalProvider.getClientId();

                ObservableFuture<SubscribeResult> future = signalProvider.subscribe(StringUtil.exists(sessionKey) ? sessionKey : UUID.randomUUID().toString(), null);

                future.addObserver(SUBSCRIBE_OBSERVER);
                future.addObserver(new Observer<ObservableFuture<SubscribeResult>>() {
                    @Override
                    public void notify(Object sender, ObservableFuture<SubscribeResult> item) {
                        latch.countDown();
                    }
                });
            }
        });

        return latch;
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
}
