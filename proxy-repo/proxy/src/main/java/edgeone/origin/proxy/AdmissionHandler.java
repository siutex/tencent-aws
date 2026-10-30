package edgeone.origin.proxy;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/** Bounds the entire lifetime of a request, including auth, S3 and slow downstream writes.
 * Queue length is intentionally zero. Health probes never consume transfer slots.
 */
final class AdmissionHandler extends Handler.Wrapper {
    private final Semaphore slots;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();

    AdmissionHandler(Handler next, int limit) {
        super(next);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        slots = new Semaphore(limit);
    }

    int active() { return active.get(); }
    long rejected() { return rejected.get(); }
    int peak() { return peak.get(); }
    int takePeak() { return peak.getAndSet(active.get()); }
    long takeRejected() { return rejected.getAndSet(0); }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (OriginHandler.HEALTH_PATH.equals(request.getHttpURI().getPath())
                && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            return super.handle(request, response, callback);
        }
        if (!slots.tryAcquire()) {
            rejected.incrementAndGet();
            OriginHandler.text(response, callback, "HEAD".equals(request.getMethod()), 503,
                    "Origin busy", List.of(new S3Origin.Header("Retry-After", "1")));
            return true;
        }
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                active.decrementAndGet();
                slots.release();
            }
        };
        // Callback.Nested keeps the wrapped callback's InvocationType (an anonymous Callback
        // would report BLOCKING); completed() runs on both success and failure.
        Callback guarded = new Callback.Nested(callback) {
            @Override
            public void completed() {
                release.run();
            }
        };
        try {
            boolean handled = super.handle(request, response, guarded);
            if (!handled) {
                release.run();
            }
            return handled;
        } catch (Exception | Error failure) {
            release.run();
            throw failure;
        }
    }
}
