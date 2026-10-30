package edgeone.origin.common;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * A value loaded from a remote source (Secrets Manager, EC2 prefix list) and cached per process.
 *
 * <ul>
 *   <li><b>fresh</b> ({@code age < ttl}): returned without any remote call;</li>
 *   <li><b>stale</b> ({@code ttl <= age < ttl + maxStale}): still returned; exactly one caller
 *       refreshes it, and a failed refresh keeps the last good value;</li>
 *   <li><b>expired</b> or never loaded: callers wait for the single in-flight load, or fail closed
 *       with {@link ValueUnavailableException}.</li>
 * </ul>
 *
 * <p>The lock only guards in-memory state; the loader never runs while it is held, so a slow or
 * failing source does not serialize unrelated requests. After a failed load the next attempt is
 * made no earlier than {@code retryAfter}, so an outage fails fast instead of queueing.
 * Consequence for revocation: a value removed at the source stops being used after at most
 * {@code ttl + maxStale} (normally about {@code ttl}).
 *
 * @param <T> immutable value type
 */
public final class RefreshingValue<T> {

    /** Loads the current value from the source. Must not return {@code null}. */
    @FunctionalInterface
    public interface Loader<T> {
        T load() throws Exception;
    }

    private static final Logger LOG = System.getLogger(RefreshingValue.class.getName());

    private final String name;
    private final Loader<T> loader;
    private final long ttlNanos;
    private final long hardLimitNanos;
    private final long retryAfterNanos;
    private final long waitTimeoutNanos;
    private final LongSupplier clock;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private T value;
    private boolean loaded;
    private long loadedAt;
    private boolean refreshing;
    private boolean backingOff;
    private long retryAt;

    public RefreshingValue(String name, Loader<T> loader, Duration ttl, Duration maxStale,
                           Duration retryAfter, Duration waitTimeout, LongSupplier nanoClock) {
        this.name = Objects.requireNonNull(name);
        this.loader = Objects.requireNonNull(loader);
        this.ttlNanos = ttl.toNanos();
        this.hardLimitNanos = ttl.plus(maxStale).toNanos();
        this.retryAfterNanos = retryAfter.toNanos();
        this.waitTimeoutNanos = waitTimeout.toNanos();
        this.clock = Objects.requireNonNull(nanoClock);
        if (ttlNanos <= 0 || maxStale.isNegative() || retryAfter.isNegative() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid durations for " + name);
        }
    }

    /** Returns a value that is fresh, or stale within the hard limit; otherwise fails closed. */
    public T get() throws ValueUnavailableException {
        long deadline = clock.getAsLong() + waitTimeoutNanos;
        lock.lock();
        try {
            while (true) {
                long now = clock.getAsLong();
                if (loaded && now - loadedAt < ttlNanos) {
                    return value;
                }
                if (!refreshing && (!backingOff || now - retryAt >= 0)) {
                    refreshing = true;
                    break; // this caller loads, outside the lock
                }
                if (usable(now)) {
                    return value; // stale but within the hard limit
                }
                if (!refreshing || now - deadline >= 0) {
                    throw new ValueUnavailableException(name + " unavailable");
                }
                // First load or past the hard limit: wait for the single in-flight load.
                changed.awaitNanos(deadline - now);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValueUnavailableException(name + " unavailable (interrupted)", e);
        } finally {
            lock.unlock();
        }
        return refresh();
    }

    /** Drops the cached value, e.g. after a Lambda SnapStart restore. */
    public void invalidate() {
        lock.lock();
        try {
            loaded = false;
            value = null;
            backingOff = false;
        } finally {
            lock.unlock();
        }
    }

    private T refresh() throws ValueUnavailableException {
        T fresh;
        try {
            fresh = Objects.requireNonNull(loader.load(), "loader returned null");
        } catch (Throwable failure) {
            T fallback;
            boolean usable;
            lock.lock();
            try {
                refreshing = false;
                backingOff = true;
                long now = clock.getAsLong();
                retryAt = now + retryAfterNanos;
                usable = usable(now);
                fallback = value;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            if (failure instanceof Error error) {
                throw error;
            }
            // Never log the failure message: it may echo request or secret content.
            LOG.log(Level.WARNING, "refresh_failed name={0} error={1}", name, failure.getClass().getSimpleName());
            if (usable) {
                return fallback;
            }
            throw new ValueUnavailableException(name + " unavailable", failure);
        }
        lock.lock();
        try {
            value = fresh;
            loaded = true;
            loadedAt = clock.getAsLong();
            refreshing = false;
            backingOff = false;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        return fresh;
    }

    private boolean usable(long now) {
        return loaded && now - loadedAt < hardLimitNanos;
    }
}
