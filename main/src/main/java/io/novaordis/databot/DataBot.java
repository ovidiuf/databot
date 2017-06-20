/*
 * Copyright (c) 2017 Nova Ordis LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.novaordis.databot;

import io.novaordis.databot.configuration.Configuration;
import io.novaordis.databot.failure.DataBotException;
import io.novaordis.events.api.event.Event;
import io.novaordis.events.api.event.ShutdownEvent;
import io.novaordis.events.api.metric.MetricSource;
import io.novaordis.events.api.metric.MetricSourceDefinition;
import io.novaordis.events.api.metric.MetricSourceException;
import io.novaordis.events.api.metric.MetricSourceFactory;
import io.novaordis.events.api.metric.MetricSourceFactoryImpl;
import io.novaordis.events.api.metric.MetricSourceRepository;
import io.novaordis.events.api.metric.MetricSourceRepositoryImpl;
import io.novaordis.utilities.address.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The central instance of a data collector node (DataBot). This instance:
 *
 * 1) Manages the in-memory event queue. The data collection threads will independently read and convert the readings
 *    into events, which will be placed into the queue. The consumers, either local file writers or network forwarders,
 *    will pick up events from the queue and process/forward them.
 *
 * 2) Maintains the source state between readings. Long lived metric sources, for which creating a connection is
 *    expensive, are stored in a "started" state.
 *
 *
 * @author Ovidiu Feodorov <ovidiu@novaordis.com>
 * @since 6/11/17
 */
public class DataBot {

    // Constants -------------------------------------------------------------------------------------------------------

    private static final Logger log = LoggerFactory.getLogger(DataBot.class);

    public static final String TIMER_THREAD_NAME = "DataBot Timer Thread";

    //
    // The source executor cannot be configured with zero initial threads, so we need a non-zero positive minimum value
    // in case there are no sources in the configuration. If there are sources, the executor will be configured with
    // as many threads as there are sources.
    //
    public static final int DEFAULT_SOURCE_EXECUTOR_CORE_POOL_SIZE = 5;

    // Static ----------------------------------------------------------------------------------------------------------

    // Attributes ------------------------------------------------------------------------------------------------------

    private String id;

    private final Configuration configuration;

    private volatile boolean started;

    private final List<DataConsumer> consumers;

    //
    // the in-memory event queue. The data collection threads will independently read and convert the readings into
    // events, which will be placed into the queue. The consumers, either local file writers or network forwarders,
    // will pick up events from the queue and process/forward them.
    //
    private final int eventQueueSize;
    private final BlockingQueue<Event> eventQueue;

    private final MetricSourceFactory sourceFactory;

    private final MetricSourceRepository sources;

    //
    // manages the threads that will be used to query sources
    //
    private final ThreadPoolExecutor sourceExecutor;

    private final ThreadFactory sourceThreadFactory;

    private final Timer timer;

    private final DataBotTimerTask timerTask;

    // Constructors ----------------------------------------------------------------------------------------------------

    public DataBot(Configuration configuration) throws DataBotException {

        if (configuration == null) {

            throw new IllegalArgumentException("null configuration");
        }

        this.id = UUID.randomUUID().toString();

        this.configuration = configuration;

        this.eventQueueSize = configuration.getEventQueueSize();

        this.eventQueue = new ArrayBlockingQueue<>(eventQueueSize);

        this.sourceFactory =
                configuration.getMetricSourceFactory() != null ?
                        configuration.getMetricSourceFactory() :
                        new MetricSourceFactoryImpl();

        this.sources = new MetricSourceRepositoryImpl();

        this.sourceThreadFactory = new MetricSourceThreadFactory("DataBot Metric Source Handler Thread");

        int sourceExecutorCorePoolSize = configuration.getMetricSourceCount();
        sourceExecutorCorePoolSize =
                sourceExecutorCorePoolSize != 0 ? sourceExecutorCorePoolSize : DEFAULT_SOURCE_EXECUTOR_CORE_POOL_SIZE;

        this.sourceExecutor = (ThreadPoolExecutor)Executors.
                newFixedThreadPool(sourceExecutorCorePoolSize, sourceThreadFactory);

        this.consumers = new ArrayList<>();

        this.timer = new Timer(TIMER_THREAD_NAME);

        this.timerTask = new DataBotTimerTask(this);

        this.started = false;

        try {

            //
            // any errors at this stage will stop the boot process
            //

            initialize();
        }
        catch(Exception e) {

            throw new DataBotException(e);
        }
    }

    // Public ----------------------------------------------------------------------------------------------------------

    public String getId() {

        return id;
    }

    /**
     * @return the metric source associated with the given address, if exists, or null if the metric source does
     * not exist.
     */
    public MetricSource getMetricSource(Address a) {

        return sources.getSource(a);
    }

    /**
     * @return the underlying storage, so handle with care.
     */
    public List<DataConsumer> getDataConsumers() {

        return consumers;
    }

    public Configuration getConfiguration() {

        return configuration;
    }

    @Override
    public String toString() {

        return "" + getId();
    }

    // Lifecycle -------------------------------------------------------------------------------------------------------

    /**
     * Starts all its consumers and schedules the periodic read timer.
     *
     * Note that the source start is not attempted here. Source start attempt will be initiated on the first timer
     * read, and then subsequently on all next time reads. This is to deal with sources that "die" between readings
     * but then can be resuscitated.
     *
     * @exception DataBotException if a consumer cannot be successfully started.
     */
    public synchronized void start() throws DataBotException {

        //
        // start the consumers - they must start correctly or this instance will end up in an invalid state
        //

        for(DataConsumer dc: consumers) {

            dc.start();
        }

        //
        // start the timer that performs periodic data collections
        //

        timer.scheduleAtFixedRate(timerTask, 0, configuration.getSamplingIntervalSec() * 1000L);

        started = true;

        log.info(this + " started");
    }

    public synchronized boolean isStarted() {

        return started;
    }

    public synchronized void stop() {

        log.debug("stopping " + this);

        //
        // once we initiate the stop process, the instance will be stopped one way or another (clean or dirty).
        //

        started = false;

        boolean clean = true;

        //
        // stop the timer
        //

        log.debug("stopping the timer " + timer + " ...");

        timer.purge();
        timer.cancel();

        //
        // stop the source executor service
        //

        sourceExecutor.shutdown();

        //
        // stop metric sources
        //

        for(MetricSource s: sources.getSources()) {

            try {

                log.debug("stopping " + s  + " ...");

                s.stop();

                log.debug(s + " stopped");
            }
            catch(Exception e) {

                clean = false;

                log.error("failed to cleanly stop " + s, e);
            }
        }

        //
        // send a shutdown event on the queue
        //

        try {

            int offerTimeoutSecs = 5;

            boolean shutdownSent = eventQueue.offer(new ShutdownEvent(), offerTimeoutSecs, TimeUnit.SECONDS);

            if (!shutdownSent) {

                clean = false;

                log.error("failed to place a shutdown event on the event queue, timed out after " +
                        offerTimeoutSecs + "seconds");
            }
        }
        catch (InterruptedException e) {

            clean = false;

            log.debug("interrupted while attempting to place a shutdown event on the event queue");
        }

        //
        // stop the data consumers
        //

        for(DataConsumer c: consumers) {

            try {

                log.debug("stopping " + c  + " ...");

                c.stop();

                log.debug(c + " stopped");
            }
            catch(Exception e) {

                clean = false;

                log.error("failed to cleanly stop " + c, e);
            }
        }

        log.info(this + " stopped " + (clean ? "successfully" : "with errors"));
    }

    // Package protected -----------------------------------------------------------------------------------------------

    Set<MetricSource> getMetricSources() {

        return sources.getSources();
    }

    /**
     * Report the number of events in the queue at the time of the reading.
     */
    int getEventCount() {

        return eventQueue.size();
    }

    /**
     * Report the maximum number of events that can be accommodated by the event queue, before starting to block.
     */
    int getEventQueueSize() {

        return eventQueueSize;
    }

    /**
     * @return the actual storage, so handle with care.
     */
    BlockingQueue<Event> getEventQueue() {

        return eventQueue;
    }

    long getTimerTaskExecutionCount() {

        return timerTask.getExecutionCount();
    }

    DataBotTimerTask getTimerTask() {

        return timerTask;
    }

    ExecutorService getSourceExecutor() {

        return sourceExecutor;
    }

    ThreadFactory getSourceThreadFactory() {

        return sourceThreadFactory;
    }

    // Protected -------------------------------------------------------------------------------------------------------

    /**
     * Create active instances (sources, consumers) but do not start them. The method will fail with a checked
     * exception, with the intention of starting the boot process, in case a metric source address is incorrectly
     * specified. Note that the metric source does not have to be accessible, as we we don't attempt to connect
     * yet, but all addresses must be syntactically correct at initialization.
     */
    void initialize() throws MetricSourceException, DataConsumerException {

        //
        // get the source addresses from configuration and initialize the corresponding metric sources, but do
        // not start them yet.
        //

        List<MetricSourceDefinition> sourceDefinitions = configuration.getMetricSourceDefinitions();

        for(MetricSourceDefinition d: sourceDefinitions) {

            Address a = d.getAddress();

            //
            // create the metric source; this is where we may enforce a specific order, if we wanted it
            //

            MetricSource s = sourceFactory.buildMetricSource(a);

            log.debug("registering metric source " + s);

            this.sources.add(s);
        }

        //
        // the source executor service is already started, nothing to do there
        //

        //
        // initialize data consumers, but not start them yet
        //

        for(DataConsumer c: configuration.getDataConsumers()) {

            c.setEventQueue(eventQueue);

            consumers.add(c);
            log.debug(this + " installed data consumer " + c);
        }
    }

    // Private ---------------------------------------------------------------------------------------------------------

    // Inner classes ---------------------------------------------------------------------------------------------------

}