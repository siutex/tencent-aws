package edgeone.origin.proxy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/** EMF sampled every 30s, with peak active requests and interval rejection count.
 * No URLs, headers, tokens, or per-request dimensions.
 */
final class ProxyMetrics extends AbstractLifeCycle {
    private final AdmissionHandler admission;
    private ScheduledExecutorService timer;
    ProxyMetrics(AdmissionHandler admission) { this.admission = admission; }
    @Override
    protected void doStart() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "origin-metrics");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleAtFixedRate(this::publish, 30, 30, TimeUnit.SECONDS);
    }
    @Override
    protected void doStop() { timer.shutdownNow(); }
    private void publish() {
        publishPool();
        System.out.printf("{\"_aws\":{\"Timestamp\":%d,\"CloudWatchMetrics\":[{\"Namespace\":\"EdgeOne/Proxy\","
                + "\"Dimensions\":[[\"Service\"]],\"Metrics\":[{\"Name\":\"Active\",\"Unit\":\"Count\"},"
                + "{\"Name\":\"PeakActive\",\"Unit\":\"Count\"},{\"Name\":\"Rejected\",\"Unit\":\"Count\"}]}]},"
                + "\"Service\":\"%s\",\"Active\":%d,\"PeakActive\":%d,\"Rejected\":%d}%n",
                System.currentTimeMillis(), service(), admission.active(), admission.takePeak(), admission.takeRejected());
    }
    private void publishPool() {
        long micros = S3PoolMetrics.INSTANCE.takeMaxMicros();
        long samples = S3PoolMetrics.INSTANCE.takeSamples();
        System.out.printf("{\"_aws\":{\"Timestamp\":%d,\"CloudWatchMetrics\":[{\"Namespace\":\"EdgeOne/Proxy\","
                + "\"Dimensions\":[[\"Service\"]],\"Metrics\":[{\"Name\":\"S3AcquireMaxMicros\",\"Unit\":\"Microseconds\"},"
                + "{\"Name\":\"S3AcquireSamples\",\"Unit\":\"Count\"}]}]},\"Service\":\"%s\","
                + "\"S3AcquireMaxMicros\":%d,\"S3AcquireSamples\":%d}%n",
                System.currentTimeMillis(), service(), micros, samples);
    }
    private static String service() {
        String value = System.getenv().getOrDefault("METRICS_SERVICE", "origin-proxy");
        return value.matches("[a-zA-Z0-9_-]{1,64}") ? value : "origin-proxy";
    }
}
