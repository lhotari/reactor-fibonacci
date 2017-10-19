package reactor.ipc.netty.profiling;

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerRoutes;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("-p")) {
            printNumberOfCalls();
        }

        HttpServer httpServer = HttpServer.create(options ->
                options.listenAddress(new InetSocketAddress(PORT))
        );
        httpServer.startRouterAndAwait(createRoutesBuilder(fibonacciReactiveOverHttp()), context ->
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

    static HttpClient createHttpClient() {
        return HttpClient.create(opts -> opts.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120000));
    }

    static Function<Long, Mono<Long>> fibonacciReactiveOverHttp() {
        HttpClient httpClient = createHttpClient();
        return n -> {
            String localUrl = createLocalUrlOnNextLoopbackIp(n);
            return httpClient.get(localUrl)
                    .flatMap(response -> response.receive()
                            .aggregate()
                            .asString(StandardCharsets.UTF_8))
                    .map(Long::valueOf);
        };
    }

    private static final AtomicLong counter = new AtomicLong();

    private static String createLocalUrlOnNextLoopbackIp(Long n) {
        long offset = counter.getAndIncrement() % LOCAL_MAX + 1;
        return "http://" + LOCAL_PREFIX + offset + ":" + PORT + "/" + n;
    }
}
