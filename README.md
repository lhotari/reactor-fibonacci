# Reactor Fibonacci

Simple load test for Reactor Netty. Based on code from [this gist](https://gist.github.com/ris58h/9a3322c7e2989015e3dc09370b42ff7b) by [ris58h](https://github.com/ris58h).

This uses a http client to call itself to calculate Fibonacci without memoization. This leads to a high number of active connections which is useful for load testing Reactor Netty.

| F<sub>x</sub> | number of calls |
|----:|-------:|
| F<sub>10</sub> | 109 |
| F<sub>15</sub> | 1219 |
| F<sub>20</sub> | 13529 |
| F<sub>25</sub> | 150049 |

Since the number of active connections will quickly go over 2<sup>16</sup>, the client will make connections to local loopback addresses 127.0.0.1-250 in a round robin fashion so that TCP port limitations won't be met.

### Running

```
./gradlew run
```
This uses the Reactor Netty version specified in [`build.gradle`](build.gradle) . 

Passing `-PuseSnapshot` will use the defined snapshot version of Reactor Netty.
Use `./gradlew install` in `reactor-netty` to update the maven local snapshot version of it and make it available for this project.

example of running with snapshot version of Reactor Netty:
```
./gradlew -PuseSnapshot run
```

### Running with SSL and uploading request bodies

```
./gradlew -PrunArgs=--post,--ssl run
```

in another terminal:
```
curl -k https://127.0.0.1:8888/15
```

### Profiling for 60 seconds with Java Flight Recorder

Requires Oracle JDK
```
# assuming the use of sdkman
sdk u java 8u161-oracle
```

Start the application with JFR enabled
```
./gradlew -PrunJfr run
```

In another terminal, execute the `profileJfr` task which profiles the application for 60 seconds with JFR:
```
./gradlew profileJfr
```
You can then open the resulting file with JMC
```
jmc -open [filename.jfr]
```

### Example of load generation

```
while [ true ]; do time curl 127.0.0.1:8888/20; done
```

### Combining all

This script uses tmux to start the relevant commands to do a simple load test and profile it

```
./profile_with_jfr.sh
```

Profiling SSL with HTTP POST bodies:

```
./profile_with_jfr.sh -s -PrunArgs=--post
```
