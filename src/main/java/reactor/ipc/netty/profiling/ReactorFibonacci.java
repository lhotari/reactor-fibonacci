package reactor.ipc.netty.profiling;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.*;
import reactor.ipc.netty.http.server.*;
import reactor.ipc.netty.tcp.BlockingNettyContext;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Simple load test for Reactor Netty.
 * Based on code from [this gist](https://gist.github.com/ris58h/9a3322c7e2989015e3dc09370b42ff7b) by [ris58h](https://github.com/ris58h). *
 */
public class ReactorFibonacci {
    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("io.netty.leakDetection.level", "simple");
    }

    // Loopback address space is 127.0.0.0/8, use multiple addresses to overcome the limitation of ports (2^16) between two endpoints addresses
    private static final String LOCAL_PREFIX = "127.0.0.";
    private static final int LOCAL_MAX = 250;
    private static final int PORT = 8888;

    public static void main(String[] args) throws CertificateException, SSLException {
        Set<String> arguments = new HashSet<>(Arrays.asList(args));

        if (arguments.contains("--help")) {
            System.out.println(
                    "usage:\n-p/--print-calls\tprint number of calls required for calculating fibonacci\n-s/--ssl\tuse https\n--post\tuse post with request body");
            System.exit(0);
        }

        if (arguments.contains("-p") || arguments.contains("--print-info")) {
            ScenarioInfoReports.printInfo();
        }


        boolean usePost = arguments.contains("--post");
        if (usePost) {
            System.out.println("Using POST calls with request body.");
        }

        boolean useSsl = arguments.contains("-s") || arguments.contains("--ssl");

        ScenarioInfoReports.printLibraryVersionInfo();

        startServer(useSsl, usePost, context ->
                System.out.println(
                        "http" + (useSsl ? "s" : "") + " server started on port " +
                                context.getPort()));
    }

    private static void startServer(boolean useSsl, boolean usePost, Consumer<BlockingNettyContext> onStart) throws CertificateException, SSLException {
        Optional<SslContext> sslServerContext;
        Optional<SslContext> sslClientContext;
        if (useSsl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslServerContext = Optional.of(SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build());
            sslClientContext = Optional.of(SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build());
        } else {
            sslServerContext = Optional.empty();
            sslClientContext = Optional.empty();
        }

        HttpServer httpServer = HttpServer.create(options -> {
                    options.listenAddress(new InetSocketAddress(PORT));
                    options.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(32 * 1024));
                    sslServerContext.ifPresent(sslContext -> options.sslContext(sslContext));
                }
        );
        httpServer
                .startRouterAndAwait(createRoutesBuilder(fibonacciReactiveOverHttp(sslClientContext, usePost), usePost),
                        onStart);
    }

    private static Consumer<HttpServerRoutes> createRoutesBuilder(
            Function<Integer, Mono<Long>> fibonacci, boolean usePost) {
        return routes -> routes.route(request -> request.uri().length() > 1 &&
                (request.method() == HttpMethod.GET ||
                        request.method() == HttpMethod.POST), (request, response) -> {
            int n = Integer.parseInt(request.uri().replaceAll("/", ""));
            Mono<Void> outbound = createOutbound(fibonacci, response, n);
            if (request.method() == HttpMethod.POST) {
                AtomicInteger counter = new AtomicInteger();
                return calculateBlockBytesSum(n)
                        .map(expectedTotalBytes ->
                                request.receive()
                                        .doOnNext(createByteBufConsumer(counter, expectedTotalBytes))
                        )
                        .then(outbound);
            } else {
                return outbound;
            }
        });
    }

    private static Consumer<ByteBuf> createByteBufConsumer(AtomicInteger counter, Long expectedTotalBytes) {
        return byteBuf -> {
            AtomicInteger blockCounter = new AtomicInteger();
            byteBuf.forEachByte(value -> {
                blockCounter.getAndIncrement();
                int expected = counter.getAndIncrement() % 2;
                if (value != expected) {
                    String message = String.format(
                            "Unexpected byte received! index=%d/%d, expected=%d, value=%d, blockcounter=%d",
                            counter.get(), expectedTotalBytes, expected, value,
                            blockCounter.get());
                    System.err.println(message);
                    throw new IllegalStateException(message);
                }
                return true;
            });
        };
    }

    private static Mono<Void> createOutbound(Function<Integer, Mono<Long>> fibonacci, HttpServerResponse response,
                                             int n) {
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
            opts.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(32 * 1024));
            sslClientContext.ifPresent(sslContext -> opts.sslContext(sslContext));
        });
    }

    static Function<Integer, Mono<Long>> fibonacciReactiveOverHttp(Optional<SslContext> sslClientContext,
                                                                   boolean usePost) {
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

    private static Mono<HttpClientResponse> createRequest(HttpClient httpClient, String localUrl, boolean usePost,
                                                          int n) {
        if (usePost) {
            return httpClient.post(localUrl, httpPostBodyPublisher(n));
        } else {
            return httpClient.get(localUrl);
        }
    }

    private static Function<HttpClientRequest, Publisher<Void>> httpPostBodyPublisher(int n) {
        return request -> request.send(createOffsetAndBlockSizeTuples(n)
                .map(offSetAndBlockSize -> {
                    int offSet =
                            offSetAndBlockSize.getT1();
                    int blockSize =
                            offSetAndBlockSize.getT2();
                    ByteBuf buffer = request.alloc()
                            .buffer(Math.max(
                                    blockSize,
                                    4080));
                    for (int i = 0; i < blockSize; i++) {
                        // write a stream of 0 & 1s to easily verify the bytestream on the other end
                        buffer.writeByte((offSet + i) % 2);
                    }
                    return buffer;
                }));
    }

    // create body size as the function of n
    // this function is more or less random, the idea was to have a function that uses prime numbers so that
    // block sizes are divisible only by the prime number and produce a lot of different remainder bytes when
    // bytes are consumed with a fixed block size. This might not be helpful, but it was an assumption that it could
    // possibly reveal some corner cases. It might be well so that a simple function would be as good in practise.
    static Flux<Tuple2<Integer, Integer>> createOffsetAndBlockSizeTuples(int n) {
        int numberOfBlocks = 241 + (n * 67);
        int[] multipliers = new int[]{
                2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97
        };
        // Flux of tuples of rolling sum and block sum in the particular entry.
        return Flux.range(1, numberOfBlocks)
                .map(blockNumber -> {
                    int multiplier = multipliers[blockNumber % multipliers.length];
                    int blockSize = 967 * multiplier;
                    return blockSize;
                })
                .scan(Tuples.of(0, 0), (acc, entry) -> Tuples.of(acc.getT1() + acc.getT2(), entry));
    }

    static Mono<Long> calculateBlockBytesSum(long n) {
        return createOffsetAndBlockSizeTuples((int) n).last().map(t -> t.getT1().longValue());
    }

    private static final AtomicLong httpRequestCounter = new AtomicLong();

    private static String createLocalUrlOnNextLoopbackIp(boolean useSsl, int n) {
        long offset = httpRequestCounter.getAndIncrement() % LOCAL_MAX + 1;
        return "http" + (useSsl ? "s" : "") + "://" + LOCAL_PREFIX + offset + ":" + PORT + "/" + n;
    }
}
