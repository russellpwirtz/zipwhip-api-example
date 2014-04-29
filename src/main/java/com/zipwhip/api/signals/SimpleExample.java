package com.zipwhip.api.signals;

/**
 * Created by IntelliJ IDEA.
 * User: Russ
 * Date: 4/16/2014
 * Time: 5:10 PM
 */

import com.ning.http.client.AsyncHttpClient;
import com.zipwhip.api.ApiConnection;
import com.zipwhip.api.ApiConnectionConfiguration;
import com.zipwhip.api.NingHttpConnection;
import com.zipwhip.api.signals.dto.BindResult;
import com.zipwhip.api.signals.dto.DeliveredMessage;
import com.zipwhip.api.signals.dto.SubscribeResult;
import com.zipwhip.concurrent.ObservableFuture;
import com.zipwhip.events.Observer;
import com.zipwhip.important.ImportantTaskExecutor;
import com.zipwhip.reliable.retry.ExponentialBackoffRetryStrategy;
import com.zipwhip.reliable.retry.RetryStrategy;
import com.zipwhip.signals2.SignalServerException;
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

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Created by IntelliJ IDEA.
 * User: Russ
 * Date: 9/4/13
 * Time: 5:09 PM
 */
public class SimpleExample {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleExample.class);
    private static final String SUBSCRIBE_URL = "/signal/subscribe";

    private static String apiHost = "http://network.zipwhip.com:80";
    private static String signalsHost;
    private static String sessionKey = null;

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
            }
        }

        Executor executor = Executors.newSingleThreadExecutor();
        Timer timer = new HashedWheelTimer();

        RetryStrategy retryStrategy = new ExponentialBackoffRetryStrategy(1, 1.5d, 18);

        SocketIoSignalConnectionFactory signalConnectionFactory = new SocketIoSignalConnectionFactory();

        signalConnectionFactory.setSignalsUrl(signalsHost);
        signalConnectionFactory.setImportantTaskExecutor(importantTaskExecutor);
        signalConnectionFactory.setExecutor(executor);
        signalConnectionFactory.setRetryStrategy(retryStrategy);
        signalConnectionFactory.setTimer(timer);

        SignalProviderFactory signalProviderFactory = new SignalProviderFactory();
        Factory<BufferedOrderedQueue<DeliveredMessage>> bufferedOrderedQueueFactory = new SilenceOnTheLineBufferedOrderedQueueFactory(timer);

        signalProviderFactory.setSignalConnectionFactory(signalConnectionFactory);
        signalProviderFactory.setImportantTaskExecutor(importantTaskExecutor);
        signalProviderFactory.setBufferedOrderedQueueFactory(bufferedOrderedQueueFactory);
        signalProviderFactory.setSignalsSubscribeActor(new NingSignalsSubscribeActor(asyncHttpClient, apiHost + SUBSCRIBE_URL));

        connect(signalProviderFactory);
    }

    private static void connect(SignalProviderFactory signalProviderFactory) throws Exception {
        UserAgent userAgent = new UserAgent();
        userAgent.setBuild("zipwhip-api example");
        userAgent.setCategory(UserAgentCategory.Desktop);
        userAgent.setVersion("1.0.0");

        final TestClient client = createTestClient(signalProviderFactory);

        final String[] clientId = {null};

        client.connect(userAgent, clientId[0], clientId[0]);
    }

    private static TestClient createTestClient(SignalProviderFactory signalProviderFactory) throws Exception {
        return new TestClient((SignalProviderImpl) signalProviderFactory.create());
    }

    private static class TestClient {
        private SignalProviderImpl signalProvider;

        public TestClient(SignalProviderImpl signalProvider) {
            this.signalProvider = signalProvider;

            ApiConnection apiConnection = new NingHttpConnection(Executors.newSingleThreadExecutor());
            apiConnection.setHost(ApiConnectionConfiguration.API_HOST);

            signalProvider.getSignalReceivedEvent().addObserver(new SignalObserver());
            signalProvider.getExceptionEvent().addObserver(EXCEPTION_EVENT_OBSERVER);
        }

        private Observer<BindResult> BIND_OBSERVER = new Observer<BindResult>() {
            public void notify(Object sender, BindResult item) {
                LOGGER.debug("Connected and bind complete!");

                String sessionKey;
                if (StringUtil.isNullOrEmpty(SimpleExample.sessionKey)) {
                    sessionKey = UUID.randomUUID().toString();
                } else {
                    sessionKey = SimpleExample.sessionKey;
                }

                ObservableFuture<SubscribeResult> future = signalProvider.subscribe(sessionKey, null);

                future.addObserver(new Observer<ObservableFuture<SubscribeResult>>() {
                    public void notify(Object sender, ObservableFuture<SubscribeResult> item) {
                        if (item.isCancelled() || item.isFailed()) {
                            LOGGER.error("Failed to subscribe!", item.getCause());
                            return;
                        }

                        LOGGER.debug("Subscribed!");
                    }
                });
            }
        };

        public void connect(final UserAgent userAgent, String clientId, String token) {
            signalProvider.getBindEvent().addObserver(BIND_OBSERVER);
            signalProvider.connect(userAgent, clientId, token);
        }
    }

    private static final Observer<Throwable> EXCEPTION_EVENT_OBSERVER = new Observer<Throwable>() {
        @Override
        public void notify(Object sender, Throwable item) {
            LOGGER.error("Received exception! " + item.getCause());

            if (item.getCause() instanceof SignalServerException) {
                LOGGER.error("Signal server exception.");
            }
        }
    };
}
