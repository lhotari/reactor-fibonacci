package reactor.ipc.netty.profiling;

import io.netty.channel.Channel;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.LongUnaryOperator;
import java.util.stream.IntStream;

/**
 * Methods to print out information to System.out about the test scenario
 */
class ScenarioInfoReports {
    static final Logger log = Loggers.getLogger(ScenarioInfoReports.class);

    public static void printInfo() {
        printNumberOfCalls();
        printUploadBytes();
        printTotalUploadSize();
    }

    public static void logLibraryVersionInfo() {
        Arrays.asList(Flux.class, HttpServer.class, Channel.class)
                .stream().forEach(ScenarioInfoReports::logLibraryVersionInfo);
    }

    private static void logLibraryVersionInfo(Class<?> clazz) {
        Package aPackage = clazz.getPackage();
        if (aPackage.getImplementationVersion() != null) {
            log.info(aPackage.getImplementationTitle() + " version: " + aPackage.getImplementationVersion());
        }
    }

    private static void printNumberOfCalls() {
        System.out.println("Number of calls");
        printFibonacciSums(ScenarioInfoReports::calculateNumberOfCalls, (n, tuple) -> System.out
                .println(n + " requires 1+" + tuple.getT1() + "+" + tuple.getT2() + "="
                        + (1 + tuple.getT1() + tuple.getT2()) + " calls."));
    }

    private static void printTotalUploadSize() {
        System.out.println("Total upload sizes");
        printFibonacciSums(n -> ReactorFibonacci.calculateBlockBytesSum(n).block(), (n, tuple) -> System.out
                .println(n + " total upload size " + (tuple.getT1() + tuple.getT2()) / 1024 / 1024 + " MB"));
    }

    private static void printFibonacciSums(LongUnaryOperator calculateFunction,
                                           BiConsumer<Integer, Tuple2<Long, Long>> logFunction) {
        IntStream.rangeClosed(3, 26).forEach(i -> {
            Long left = calculateFunction.applyAsLong(i - 1);
            Long right = calculateFunction.applyAsLong(i - 2);
            logFunction.accept(i, Tuples.of(left, right));
        });
    }

    private static long calculateNumberOfCalls(long n) {
        return calculateFibonacciSum(n, x -> 1);
    }

    private static long calculateFibonacciSum(long n, LongUnaryOperator entryFunction) {
        if (n <= 2) {
            return entryFunction.applyAsLong(n);
        } else {
            return entryFunction.applyAsLong(n) + calculateFibonacciSum(n - 1, entryFunction) +
                    calculateFibonacciSum(n - 2, entryFunction);
        }
    }

    private static void printUploadBytes() {
        System.out.println("Upload total size\nn\tsize");
        Flux.range(1, 25)
                .map(n ->
                        Tuples.of(n, ReactorFibonacci.calculateBlockBytesSum(n).block()))
                .map(tuple -> String.format("%d\t%d bytes\t(%d kB)", tuple.getT1(), tuple.getT2(), tuple.getT2() / 1024))
                .doOnNext(System.out::println)
                .subscribe();
    }

}
