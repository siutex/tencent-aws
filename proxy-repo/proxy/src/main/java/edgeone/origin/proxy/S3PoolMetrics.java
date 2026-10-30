package edgeone.origin.proxy;

import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

/** Collect only numeric pool wait metrics; never emit the complete SDK collection. */
final class S3PoolMetrics implements MetricPublisher {
    static final S3PoolMetrics INSTANCE = new S3PoolMetrics();
    private final AtomicLong maxMicros = new AtomicLong();
    private final AtomicLong samples = new AtomicLong();
    @Override
    public void publish(MetricCollection metrics) {
        for (var duration : metrics.metricValues(HttpMetric.CONCURRENCY_ACQUIRE_DURATION)) {
            maxMicros.accumulateAndGet(duration.toNanos() / 1000, Math::max);
            samples.incrementAndGet();
        }
        for (MetricCollection child : metrics.children()) {
            publish(child);
        }
    }
    long takeMaxMicros() { return maxMicros.getAndSet(0); }
    long takeSamples() { return samples.getAndSet(0); }
    @Override
    public void close() { }
}
