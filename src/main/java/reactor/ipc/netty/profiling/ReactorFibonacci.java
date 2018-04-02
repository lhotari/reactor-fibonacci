package reactor.ipc.netty.profiling;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerRoutes;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Simple load test for Reactor Netty.
 * Based on code from [this gist](https://gist.github.com/ris58h/9a3322c7e2989015e3dc09370b42ff7b) by [ris58h](https://github.com/ris58h). *
 */
public class ReactorFibonacci {
    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    // Loopback address space is 127.0.0.0/8, use multiple addresses to overcome the limitation of ports (2^16) between two endpoints addresses
    private static final String LOCAL_PREFIX = "127.0.0.";
    private static final int LOCAL_MAX = 250;
    private static final int PORT = 8888;

    public static void main(String[] args) throws CertificateException, SSLException {
        if (args.length > 0 && args[0].equals("-p")) {
            printNumberOfCalls();
            System.exit(0);
        }

        Optional<SslContext> sslServerContext;
        Optional<SslContext> sslClientContext;
        if (args.length > 0 && args[0].equals("-s")) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslServerContext = Optional.of(SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build());
            sslClientContext = Optional.of(SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build());
        } else {
            sslServerContext = Optional.empty();
            sslClientContext = Optional.empty();
        }

        HttpServer httpServer = HttpServer.create(options -> {
                    options.listenAddress(new InetSocketAddress(PORT));
                    sslServerContext.ifPresent(sslContext -> options.sslContext(sslContext));
                }
        );
        httpServer.startRouterAndAwait(createRoutesBuilder(fibonacciReactiveOverHttp(sslClientContext)), context ->
                System.out.println("HTTP server started on port " + context.getPort()));
    }

    private static void printNumberOfCalls() {
        IntStream.rangeClosed(3, 26).forEach(i -> {
            int left = calculateNumberOfCalls(i - 1);
            int right = calculateNumberOfCalls(i - 2);
            System.out.println(i + " requires 1+" + left + "+" + right + "="
                    + (1 + left + right) + " calls.");
        });
    }

    private static int calculateNumberOfCalls(int n) {
        if (n <= 2) {
            return 1;
        } else {
            return 1 + calculateNumberOfCalls(n - 1) + calculateNumberOfCalls(n - 2);
        }
    }


    private static Consumer<HttpServerRoutes> createRoutesBuilder(
            Function<Long, Mono<Long>> fibonacci) {
        return routes -> routes.get("/{n}", (request, response) -> {
            Long n = Long.valueOf(request.param("n"));
            if (n <= 2) {
                return response.sendString(Mono.just("1"));
            } else {
                Mono<Long> n_1 = fibonacci.apply(n - 1);
                Mono<Long> n_2 = fibonacci.apply(n - 2);
                Mono<Long> sum = n_1.zipWith(n_2, Long::sum);
                return response.sendString(sum.map(String::valueOf));
            }
        });
    }

    static HttpClient createHttpClient(Optional<SslContext> sslClientContext) {
        return HttpClient.create(opts -> {
            opts.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120000);
            sslClientContext.ifPresent(sslContext -> opts.sslContext(sslContext));
        });
    }

    static Function<Long, Mono<Long>> fibonacciReactiveOverHttp(Optional<SslContext> sslClientContext) {
        HttpClient httpClient = createHttpClient(sslClientContext);
        return n -> {
            boolean useSsl = sslClientContext.isPresent();
            String localUrl = createLocalUrlOnNextLoopbackIp(useSsl, n);
            return httpClient.get(localUrl)
                    .flatMap(response -> response.receive()
                            .aggregate()
                            .asString(StandardCharsets.UTF_8))
                    .map(Long::valueOf);
        };
    }

    private static final AtomicLong counter = new AtomicLong();

    private static String createLocalUrlOnNextLoopbackIp(boolean useSsl, Long n) {
        long offset = counter.getAndIncrement() % LOCAL_MAX + 1;
        return "http" + (useSsl ? "s" : "") + "://" + LOCAL_PREFIX + offset + ":" + PORT + "/" + n;
    }
}
