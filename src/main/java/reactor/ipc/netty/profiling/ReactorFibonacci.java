package reactor.ipc.netty.profiling;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.*;
import reactor.util.function.Tuples;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

        boolean usePost = true;

        HttpServer httpServer = HttpServer.create(options -> {
                    options.listenAddress(new InetSocketAddress(PORT));
                    sslServerContext.ifPresent(sslContext -> options.sslContext(sslContext));
                }
        );
        boolean useSsl = sslServerContext.isPresent();
        httpServer.startRouterAndAwait(createRoutesBuilder(fibonacciReactiveOverHttp(sslClientContext, usePost), usePost), context ->
                System.out.println("http" + (useSsl ? "s" : "") + " server started on port " + context.getPort()));
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
            Function<Integer, Mono<Long>> fibonacci, boolean usePost) {
        return routes -> routes.route(request -> request.uri().length() > 1 && request.uri().lastIndexOf('/') == 0 &&
                (request.method() == HttpMethod.GET || request.method() == HttpMethod.POST), (request, response) -> {
            int n = Integer.parseInt(request.uri().replaceAll("/", ""));
            Mono<Void> outbound = createOutbound(fibonacci, response, n);
            if (request.method() == HttpMethod.POST) {
                AtomicInteger counter = new AtomicInteger();
                int expectedTotalBytes = calculateTotalBytes(n);
                return request.receive().doOnNext(byteBuf -> {
                            System.out.println("X");
                            AtomicInteger blockCounter = new AtomicInteger();
                            byteBuf.forEachByte(value -> {
                                blockCounter.getAndIncrement();
                                int expected = counter.getAndIncrement() % 2;
                                if (value != expected) {
                                    String message = String.format("Unexpected byte received! index=%d/%d, expected=%d, value=%d, blockcounter=%d", counter.get(), expectedTotalBytes, expected, value, blockCounter.get());
                                    System.err.println(message);
                                    throw new IllegalStateException(message);
                                }
                                return true;
                            });
                        }
                ).then(outbound);
            } else {
                return outbound;
            }
        });
    }

    private static Mono<Void> createOutbound(Function<Integer, Mono<Long>> fibonacci, HttpServerResponse response, int n) {
        if (n <= 2) {
            return response.sendString(Mono.just("1")).then();
        } else {
            return response.sendString(calculate(fibonacci, n).map(String::valueOf)).then();
        }
    }

    private static Mono<Long> calculate(Function<Integer, Mono<Long>> fibonacci, int n) {
        Mono<Long> n_1 = fibonacci.apply(n - 1);
        Mono<Long> n_2 = fibonacci.apply(n - 2);
        return n_1.zipWith(n_2, Long::sum);
    }

    static HttpClient createHttpClient(Optional<SslContext> sslClientContext) {
        return HttpClient.create(opts -> {
            opts.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120000);
            sslClientContext.ifPresent(sslContext -> opts.sslContext(sslContext));
        });
    }

    static Function<Integer, Mono<Long>> fibonacciReactiveOverHttp(Optional<SslContext> sslClientContext, boolean usePost) {
        HttpClient httpClient = createHttpClient(sslClientContext);
        return n -> {
            boolean useSsl = sslClientContext.isPresent();
            String localUrl = createLocalUrlOnNextLoopbackIp(useSsl, n);
            Mono<HttpClientResponse> responseMono = createRequest(httpClient, localUrl, usePost, n.intValue());
            return responseMono
                    .flatMap(response -> response.receive()
                            .aggregate()
                            .asString(StandardCharsets.UTF_8))
                    .map(Long::valueOf);
        };
    }

    private static Mono<HttpClientResponse> createRequest(HttpClient httpClient, String localUrl, boolean usePost, int n) {
        if (usePost) {
            return httpClient.post(localUrl, request -> request.send(createBlockSizes(n)
                    .map(blockSize -> {
                        ByteBuf buffer = request.alloc().buffer(blockSize);
                        for (int i = 0; i < blockSize; i++) {
                            buffer.writeByte(i % 2);
                        }
                        return buffer;
                    })));
        } else {
            return httpClient.get(localUrl);
        }
    }

    static Flux<Integer> createBlockSizes(int n) {
        int numberOfBlocks = 241 + (n * 67);
        int[] multipliers = new int[]{2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97};
        return Flux.range(1, numberOfBlocks)
                .map(blockNumber -> {
                    int multiplier = multipliers[blockNumber % multipliers.length];
                    int blockSize = 967 * multiplier;
                    return blockSize;
                });
    }

    static int calculateTotalBytes(int n) {
        return createBlockSizes(n).toStream().mapToInt(Integer::intValue)
                .sum();
    }

    public static final class CalcIt {
        public static void main(String[] args) {
            System.out.println("Ranges");
            Flux.range(1, 25)
                    .map(n ->
                            Tuples.of(n, calculateTotalBytes(n)))
                    .map(tuple -> String.format("%d\t%d bytes\t(%d kB)", tuple.getT1(), tuple.getT2(), tuple.getT2() / 1024))
                    .doOnNext(System.out::println)
                    .subscribe();
        }
    }

    private static final AtomicLong counter = new AtomicLong();

    private static String createLocalUrlOnNextLoopbackIp(boolean useSsl, int n) {
        long offset = counter.getAndIncrement() % LOCAL_MAX + 1;
        return "http" + (useSsl ? "s" : "") + "://" + LOCAL_PREFIX + offset + ":" + PORT + "/" + n;
    }
}
