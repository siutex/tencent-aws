package edgeone.origin.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TokenStoreTest {

    static final String TOKEN = "a".repeat(64);
    static final String OTHER = "b".repeat(64);

    /** Secrets Manager double: counts calls, optional delay and failure. */
    static final class FakeSecrets implements SecretSource {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> payload = new AtomicReference<>();
        volatile RuntimeException failure;
        volatile long delayMillis;

        FakeSecrets(String... tokens) {
            setTokens(tokens);
        }

        void setTokens(String... tokens) {
            payload.set("{\"tokens\":[" + String.join(",", List.of(tokens).stream().map(t -> "\"" + t + "\"").toList()) + "]}");
        }

        @Override
        public String fetch() throws InterruptedException {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            if (failure != null) {
                throw failure;
            }
            return payload.get();
        }
    }

    static final class FakeClock {
        final AtomicLong nanos = new AtomicLong();

        void seconds(double value) {
            nanos.set((long) (value * 1_000_000_000L));
        }

        long now() {
            return nanos.get();
        }
    }

    static TokenStore store(SecretSource source, FakeClock clock) {
        return new TokenStore(source, Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofSeconds(10), clock::now);
    }

    @Test
    void validatesFormatAcceptsBothTokensAndCaches() throws Exception {
        FakeClock clock = new FakeClock();
        FakeSecrets sm = new FakeSecrets(TOKEN, OTHER);
        TokenStore store = store(sm, clock);
        assertFalse(store.allowed(""));
        assertFalse(store.allowed(null));
        assertFalse(store.allowed("a,b"));
        assertFalse(store.allowed("ą".repeat(64)));
        assertFalse(store.allowed(TOKEN + "," + TOKEN)); // duplicated header joined with a comma
        assertTrue(store.allowed(TOKEN));
        assertTrue(store.allowed(OTHER));
        assertFalse(store.allowed("c".repeat(64)));
        assertEquals(1, sm.calls.get());
        clock.seconds(29);
        assertTrue(store.allowed(TOKEN));
        assertEquals(1, sm.calls.get());
    }

    @Test
    void staleWindowIsBoundedAndFailuresBackOff() throws Exception {
        FakeClock clock = new FakeClock();
        FakeSecrets sm = new FakeSecrets(TOKEN);
        TokenStore store = store(sm, clock);
        assertTrue(store.allowed(TOKEN));
        sm.failure = new IllegalStateException("outage");
        clock.seconds(31); // stale: refresh fails, last good tokens still used
        assertTrue(store.allowed(TOKEN));
        assertEquals(2, sm.calls.get());
        clock.seconds(33); // inside retryAfter: no new call
        assertTrue(store.allowed(TOKEN));
        assertEquals(2, sm.calls.get());
        clock.seconds(90); // hard limit (ttl + maxStale): fail closed
        assertThrows(ValueUnavailableException.class, () -> store.allowed(TOKEN));
        assertEquals(3, sm.calls.get());
        clock.seconds(91); // still backing off: fails fast without calling the source
        assertThrows(ValueUnavailableException.class, () -> store.allowed(TOKEN));
        assertEquals(3, sm.calls.get());
        sm.failure = null;
        clock.seconds(96);
        assertTrue(store.allowed(TOKEN));
        assertEquals(4, sm.calls.get());
    }

    @Test
    void revokedTokenRejectedAfterTtl() throws Exception {
        FakeClock clock = new FakeClock();
        FakeSecrets sm = new FakeSecrets(TOKEN, OTHER);
        TokenStore store = store(sm, clock);
        assertTrue(store.allowed(TOKEN));
        sm.setTokens(OTHER);
        clock.seconds(30);
        assertFalse(store.allowed(TOKEN));
        assertTrue(store.allowed(OTHER));
    }

    @Test
    void invalidateForcesReload() throws Exception {
        FakeClock clock = new FakeClock();
        FakeSecrets sm = new FakeSecrets(TOKEN);
        TokenStore store = store(sm, clock);
        assertTrue(store.allowed(TOKEN));
        store.invalidate();
        assertTrue(store.allowed(TOKEN));
        assertEquals(2, sm.calls.get());
    }

    @Test
    void staleRefreshDoesNotBlockOtherRequests() throws Exception {
        FakeClock clock = new FakeClock();
        FakeSecrets sm = new FakeSecrets(TOKEN);
        TokenStore store = store(sm, clock);
        assertTrue(store.allowed(TOKEN));
        clock.seconds(40); // stale but usable
        sm.delayMillis = 1000;
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    long start = System.nanoTime();
                    assertTrue(store.allowed(TOKEN));
                    latencies.add((System.nanoTime() - start) / 1_000_000);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertEquals(2, sm.calls.get());
        assertEquals(1, latencies.stream().filter(ms -> ms > 500).count(), "only the refreshing request waits");
    }

    @Test
    void firstLoadIsSharedAndFailureIsNotSerialized() throws Exception {
        FakeSecrets sm = new FakeSecrets(TOKEN);
        sm.delayMillis = 300;
        TokenStore store = new TokenStore(sm);
        assertEquals(8, runConcurrently(8, () -> store.allowed(TOKEN) ? 1 : 0));
        assertEquals(1, sm.calls.get());

        FakeSecrets failing = new FakeSecrets(TOKEN);
        failing.delayMillis = 300;
        failing.failure = new IllegalStateException("timeout");
        TokenStore failingStore = new TokenStore(failing);
        long start = System.nanoTime();
        int unavailable = runConcurrently(8, () -> {
            try {
                failingStore.allowed(TOKEN);
                return 0;
            } catch (ValueUnavailableException e) {
                return 1;
            }
        });
        assertEquals(8, unavailable);
        assertEquals(1, failing.calls.get());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000, "was ~8 x timeout with I/O under the lock");
    }

    static List<String> malformedSecrets() {
        String a = "\"" + TOKEN + "\"";
        String b = "\"" + OTHER + "\"";
        return List.of("{\"tokens\":[]}", "{\"tokens\":[\"\"]}", "{\"tokens\":[" + a + "," + a + "]}",
                "{\"tokens\":[" + a + "," + b + ",\"" + "c".repeat(64) + "\"]}",
                "{\"tokens\":\"text\"}", "{\"tokens\":[123]}", "{\"tokens\":[\"" + "é".repeat(40) + "\"]}",
                "[" + a + "]", "not json", "{}", "");
    }

    @ParameterizedTest
    @MethodSource("malformedSecrets")
    void malformedSecretFailsClosed(String secret) {
        TokenStore store = new TokenStore(() -> secret);
        assertThrows(ValueUnavailableException.class, () -> store.allowed(TOKEN));
    }

    interface Task {
        int run() throws Exception;
    }

    static int runConcurrently(int n, Task task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.run();
                }));
            }
            start.countDown();
            int sum = 0;
            for (Future<Integer> f : futures) {
                sum += f.get();
            }
            return sum;
        }
    }
}
