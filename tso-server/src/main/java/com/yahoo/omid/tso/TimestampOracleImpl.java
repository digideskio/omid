package com.yahoo.omid.tso;

import static com.codahale.metrics.MetricRegistry.name;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yahoo.omid.metrics.Gauge;
import com.yahoo.omid.metrics.MetricsRegistry;
import com.yahoo.omid.timestamp.storage.TimestampStorage;

/**
 * The Timestamp Oracle that gives monotonically increasing timestamps
 */
@Singleton
public class TimestampOracleImpl implements TimestampOracle {

    private static final Logger LOG = LoggerFactory.getLogger(TimestampOracleImpl.class);

    /**
     * Used for testing
     */
    public static class InMemoryTimestampStorage implements TimestampStorage {

        long maxTimestamp = 0;

        @Override
        public void updateMaxTimestamp(long previousMaxTimestamp, long nextMaxTimestamp) {
            maxTimestamp = nextMaxTimestamp;
            LOG.info("Updating max timestamp: (previous:{}, new:{})", previousMaxTimestamp, nextMaxTimestamp);
        }

        @Override
        public long getMaxTimestamp() {
            return maxTimestamp;
        }

    }

    private class AllocateTimestampBatchTask implements Runnable {
        long previousMaxTimestamp;

        public AllocateTimestampBatchTask(long previousMaxTimestamp) {
            this.previousMaxTimestamp = previousMaxTimestamp;
        }

        @Override
        public void run() {
            long newMaxTimestamp = previousMaxTimestamp + TIMESTAMP_BATCH;
            try {
                storage.updateMaxTimestamp(previousMaxTimestamp, newMaxTimestamp);
                maxAllocatedTimestamp = newMaxTimestamp;
                previousMaxTimestamp = newMaxTimestamp;
            } catch(Throwable e) {
                panicker.panic("Can't store the new max timestamp", e);
            }
        }

    }

    static final long TIMESTAMP_BATCH = 10 * 1000 * 1000; // 10 million
    static final long TIMESTAMP_REMAINING_THRESHOLD = 1 * 1000 * 1000; // 1 million

    private long lastTimestamp;

    private long maxTimestamp;

    private MetricsRegistry metrics;
    private TimestampStorage storage;
    private Panicker panicker;

    private long nextAllocationThreshold;
    private volatile long maxAllocatedTimestamp;

    private Executor executor = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("ts-persist-%d").build());

    private Runnable allocateTimestampsBatchTask;

    @Inject
    public TimestampOracleImpl(MetricsRegistry metrics,
                               TimestampStorage tsStorage,
                               Panicker panicker) throws IOException {

        this.metrics = metrics;
        this.storage = tsStorage;
        this.panicker = panicker;

        metrics.gauge(name("tso", "maxTimestamp"), new Gauge<Long>() {
            @Override
            public Long getValue() {
                return maxTimestamp;
            }
        });

    }

    @Override
    public void initialize() throws IOException {

        this.lastTimestamp = this.maxTimestamp = storage.getMaxTimestamp();

        this.allocateTimestampsBatchTask = new AllocateTimestampBatchTask(lastTimestamp);

        // Trigger first allocation of timestamps
        executor.execute(allocateTimestampsBatchTask);

        LOG.info("Initializing timestamp oracle with timestamp {}", this.lastTimestamp);
    }

    /**
     * Returns the next timestamp if available. Otherwise spins till the
     * ts-persist thread performs the new timestamp allocation
     */
    @Override
    public long next() throws IOException {
        lastTimestamp++;

        if (lastTimestamp == nextAllocationThreshold) {
            executor.execute(allocateTimestampsBatchTask);
        }

        if (lastTimestamp >= maxTimestamp) {
            assert(maxTimestamp <= maxAllocatedTimestamp);
            while(maxAllocatedTimestamp == maxTimestamp) {
                // spin
            }
            assert(maxAllocatedTimestamp > maxTimestamp);
            maxTimestamp = maxAllocatedTimestamp;
            nextAllocationThreshold = maxTimestamp - TIMESTAMP_REMAINING_THRESHOLD;
            assert(nextAllocationThreshold > lastTimestamp && nextAllocationThreshold < maxTimestamp);
            assert(lastTimestamp < maxTimestamp);
        }

        return lastTimestamp;
    }

    @Override
    public long getLast() {
        return lastTimestamp;
    }

    @Override
    public String toString() {
        return String.format("TimestampOracle -> LastTimestamp: %d, MaxTimestamp: %d", lastTimestamp, maxTimestamp);
    }

}