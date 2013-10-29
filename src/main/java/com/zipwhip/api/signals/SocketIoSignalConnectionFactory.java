package com.zipwhip.api.signals;

import com.zipwhip.executors.SimpleExecutor;
import com.zipwhip.important.ImportantTaskExecutor;
import com.zipwhip.reliable.retry.ExponentialBackoffRetryStrategy;
import com.zipwhip.reliable.retry.RetryStrategy;
import com.zipwhip.timers.Timer;
import com.zipwhip.util.Factory;

import java.util.concurrent.Executor;

/**
 * Date: 9/25/13
 * Time: 2:07 PM
 *
 * @author Michael
 * @version 1
 */
public class SocketIoSignalConnectionFactory implements Factory<SignalConnection> {

    private String signalsUrl = "http://10.50.245.101:80";
    private Executor executor = SimpleExecutor.getInstance();
    private Timer timer;
    private ImportantTaskExecutor importantTaskExecutor;
    private RetryStrategy retryStrategy = new ExponentialBackoffRetryStrategy(1000, 1.1);

    @Override
    public SignalConnection create() {
        SocketIoSignalConnection connection = new SocketIoSignalConnection();

        connection.setUrl(signalsUrl);
        connection.setImportantTaskExecutor(importantTaskExecutor);
        connection.setTimer(timer);
        connection.setExecutor(executor);
        connection.setRetryStrategy(retryStrategy);

        return connection;
    }

    public String getSignalsUrl() {
        return signalsUrl;
    }

    public void setSignalsUrl(String signalsUrl) {
        this.signalsUrl = signalsUrl;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public Timer getTimer() {
        return timer;
    }

    public void setTimer(Timer timer) {
        this.timer = timer;
    }

    public ImportantTaskExecutor getImportantTaskExecutor() {
        return importantTaskExecutor;
    }

    public void setImportantTaskExecutor(ImportantTaskExecutor importantTaskExecutor) {
        this.importantTaskExecutor = importantTaskExecutor;
    }

    public RetryStrategy getRetryStrategy() {
        return retryStrategy;
    }

    public void setRetryStrategy(RetryStrategy retryStrategy) {
        this.retryStrategy = retryStrategy;
    }

}
