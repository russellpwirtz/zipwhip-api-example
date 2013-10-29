package com.zipwhip.api.signals;

import com.google.gson.Gson;
import com.ning.http.client.AsyncHttpClient;
import com.zipwhip.api.signals.dto.BindResult;
import com.zipwhip.api.signals.dto.DeliveredMessage;
import com.zipwhip.api.signals.dto.SubscribeResult;
import com.zipwhip.api.signals.dto.json.SignalProviderGsonBuilder;
import com.zipwhip.concurrent.ObservableFuture;
import com.zipwhip.events.Observer;
import com.zipwhip.important.ImportantTaskExecutor;
import com.zipwhip.important.ZipwhipSchedulerTimer;
import com.zipwhip.reliable.retry.ConstantIntervalRetryStrategy;
import com.zipwhip.signals2.presence.UserAgent;
import com.zipwhip.signals2.presence.UserAgentCategory;
import com.zipwhip.util.StringUtil;
import org.apache.log4j.BasicConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by IntelliJ IDEA.
 * User: Russ
 * Date: 9/4/13
 * Time: 5:09 PM
 */
public class Example {

    private static final Logger LOGGER = LoggerFactory.getLogger(Example.class);
    private static final String SUBSCRIBE_URL = "/signal/subscribe";

    private static SignalProviderImpl signalProvider;
    private static NingSignalsSubscribeActor actor = new NingSignalsSubscribeActor();
    private static String apiHost;
    private static String signalsHost = null;
    private static String sessionKey = null;
    private static String clientId;
    private static Gson gson = SignalProviderGsonBuilder.getInstance();


    public static void main(String... args) throws Exception {
        BasicConfigurator.configure();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-apiHost")) {
                apiHost = args[++i];
            } else if (args[i].equals("-signalsHost")) {
                signalsHost = args[++i];
            } else if (args[i].equals("-sessionKey")) {
                sessionKey = args[++i];
            }
        }

        if (StringUtil.isNullOrEmpty(sessionKey)) {
            System.err.println("The required -sessionKey parameter is missing");
            return;
        }

        if (StringUtil.isNullOrEmpty(apiHost)) {
            apiHost = "http://network.zipwhip.com:80";
        }

        if (StringUtil.isNullOrEmpty(signalsHost)) {
            signalsHost = "http://10.50.245.101:80";
        }

        ImportantTaskExecutor importantTaskExecutor = new ImportantTaskExecutor();

        SocketIoSignalConnection signalConnection = new SocketIoSignalConnection();
        signalConnection.setImportantTaskExecutor(importantTaskExecutor);
        signalConnection.setGson(gson);
        signalConnection.setUrl(signalsHost);
        signalConnection.setRetryStrategy(new ConstantIntervalRetryStrategy(0));
        signalConnection.setTimer(new ZipwhipSchedulerTimer(null));

        signalProvider = new SignalProviderImpl();
        signalProvider.setSignalsSubscribeActor(actor);
        signalProvider.setBufferedOrderedQueue(new SilenceOnTheLineBufferedOrderedQueue<DeliveredMessage>());
        signalProvider.setImportantTaskExecutor(importantTaskExecutor);
        signalProvider.setSignalConnection(signalConnection);
        signalProvider.setGson(gson);

        actor.setUrl(apiHost + SUBSCRIBE_URL);
        actor.setClient(new AsyncHttpClient());

        final UserAgent userAgent = new UserAgent();
        userAgent.setBuild("zipwhip-api example");
        userAgent.setCategory(UserAgentCategory.Desktop);
        userAgent.setVersion("1.0.0");

        signalProvider.getSignalReceivedEvent().addObserver(SIGNAL_RECEIVED_OBSERVER);
        signalProvider.getBindEvent().addObserver(BIND_RESULT_OBSERVER);

        ObservableFuture<Void> connectFuture = signalProvider.connect(userAgent);

        connectFuture.addObserver(new Observer<ObservableFuture<Void>>() {
            public void notify(Object sender, ObservableFuture<Void> item) {
                if (item.isFailed()) {
                    throw new RuntimeException("Couldn't connect!", item.getCause());
                }

                LOGGER.debug("Connected!");
                clientId = signalProvider.getClientId();

                signalProvider.subscribe(sessionKey, null).addObserver(SUBSCRIBE_OBSERVER);
            }
        });
    }

    public static final Observer<ObservableFuture<SubscribeResult>> SUBSCRIBE_OBSERVER = new Observer<ObservableFuture<SubscribeResult>>() {
        public void notify(Object sender, ObservableFuture<SubscribeResult> item) {
            if (item.isFailed()) {
                throw new RuntimeException("Couldn't subscribe!", item.getCause());
            }

            LOGGER.debug("Subscribed!");
        }
    };

    public static final Observer<BindResult> BIND_RESULT_OBSERVER = new Observer<BindResult>() {
        public void notify(Object sender, BindResult item) {
            LOGGER.debug("Received bind event for clientId: " + item.getClientId());
        }
    };

    public static final Observer<DeliveredMessage> SIGNAL_RECEIVED_OBSERVER = new Observer<DeliveredMessage>() {
        public void notify(Object sender, DeliveredMessage item) {
            LOGGER.debug("Received message: " + item.getContent());
        }
    };
}
