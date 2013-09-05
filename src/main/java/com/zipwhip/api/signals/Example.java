package com.zipwhip.api.signals;

import com.ning.http.client.AsyncHttpClient;
import com.zipwhip.api.signals.dto.DeliveredMessage;
import com.zipwhip.concurrent.ObservableFuture;
import com.zipwhip.events.Observer;
import com.zipwhip.signals.presence.UserAgent;
import com.zipwhip.signals.presence.UserAgentCategory;
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
    private static String server;
    private static String sessionKey = null;
    private static String clientId;

    public static void main(String... args) throws Exception {
        BasicConfigurator.configure();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-server")) {
                server = args[++i];
            } else if (args[i].equals("-sessionKey")) {
                sessionKey = args[++i];
            }
        }

        if (StringUtil.isNullOrEmpty(sessionKey)) {
            System.err.println("The required -sessionKey parameter is missing");
            return;
        }

        if (StringUtil.isNullOrEmpty(server)) {
            server = "http://localhost:8080";
        }

        signalProvider = new SignalProviderImpl();
        signalProvider.setSignalsSubscribeActor(actor);

        actor.setUrl(server + SUBSCRIBE_URL);
        actor.setClient(new AsyncHttpClient());

        UserAgent userAgent = new UserAgent();
        userAgent.setBuild("zipwhip-api example");
        userAgent.setCategory(UserAgentCategory.Desktop);
        userAgent.setVersion("1.0.0");

        LOGGER.debug("Connecting to " + server);
        ObservableFuture<Void> connectFuture = signalProvider.connect(userAgent);

        connectFuture.addObserver(new Observer<ObservableFuture<Void>>() {
            public void notify(Object sender, ObservableFuture<Void> item) {
                if (item.isFailed()) {
                    LOGGER.error("Couldn't connect! " + item.getCause());
                    return;
                }

                LOGGER.debug("Connected!");
                clientId = signalProvider.getClientId();

                signalProvider.subscribe(sessionKey, null).addObserver(SUBSCRIBE_OBSERVER);
            }
        });

        signalProvider.getMessageReceivedEvent().addObserver(DELIVERED_MESSAGE_OBSERVER);
        signalProvider.getBindEvent().addObserver(BIND_RESULT_OBSERVER);
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
            LOGGER.debug("Received bind event for clientId: " + item.getClientId());
        }
    };

    public static final Observer<DeliveredMessage> DELIVERED_MESSAGE_OBSERVER = new Observer<DeliveredMessage>() {
        public void notify(Object sender, DeliveredMessage item) {
            LOGGER.debug("Received message: " + item.getMessage());
        }
    };
}
