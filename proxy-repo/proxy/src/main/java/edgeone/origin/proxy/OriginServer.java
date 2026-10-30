package edgeone.origin.proxy;

import java.util.concurrent.Executors;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/** Jetty wiring: virtual threads for blocking S3 streaming, idle timeout above the ALB's. */
final class OriginServer {

    /** Status, bytes and duration only: no URL, query string or headers. */
    static final String ACCESS_LOG_FORMAT = "%s %O %{ms}T";

    private OriginServer() {
    }

    static Server create(ProxyConfig config, Handler handler) {
        QueuedThreadPool threads = new QueuedThreadPool();
        threads.setName("origin");
        threads.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());

        Server server = new Server(threads);
        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        http.setSendXPoweredBy(false);

        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setPort(config.port());
        connector.setIdleTimeout(config.idleTimeout().toMillis());
        server.addConnector(connector);

        ErrorHandler errors = new ErrorHandler();
        errors.setShowStacks(false);
        errors.setShowMessageInTitle(false);
        server.setErrorHandler(errors);

        AdmissionHandler admission = new AdmissionHandler(handler, config.maxConcurrency());
        server.setHandler(admission);
        server.addBean(new ProxyMetrics(admission));
        server.setRequestLog(new CustomRequestLog(new RequestLogWriter(), ACCESS_LOG_FORMAT));
        server.setStopTimeout(30_000);
        server.setStopAtShutdown(true);
        return server;
    }
}
