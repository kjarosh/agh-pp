package com.github.kjarosh.agh.pp.cli;

import com.github.kjarosh.agh.pp.cli.utils.LogbackUtils;
import com.github.kjarosh.agh.pp.graph.GraphLoader;
import com.github.kjarosh.agh.pp.graph.model.Graph;
import com.github.kjarosh.agh.pp.graph.modification.BulkOperationIssuer;
import com.github.kjarosh.agh.pp.graph.modification.ConcurrentOperationIssuer;
import com.github.kjarosh.agh.pp.graph.modification.OperationIssuer;
import com.github.kjarosh.agh.pp.graph.modification.RandomOperationIssuer;
import com.github.kjarosh.agh.pp.index.events.EventStats;
import com.github.kjarosh.agh.pp.rest.client.ZoneClient;
import com.github.kjarosh.agh.pp.test.EventStatsGatherer;
import com.github.kjarosh.agh.pp.test.RemoteGraphBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Kamil Jarosz
 */
@Slf4j
public class ConstantLoadClientMain {
    private static final ThreadFactory treadFactory = new ThreadFactoryBuilder()
            .setNameFormat("generator-%d")
            .build();

    private static final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor(treadFactory);
    private static boolean loadGraph;
    private static boolean exitOnFail;
    private static int bulkSize;
    private static int operationsPerSecond;
    private static Graph graph;
    private static double permsProbability;
    private static int maxPoolSize;

    private static RandomOperationIssuer randomOperationIssuer;
    private static ConcurrentOperationIssuer baseOperationIssuer;
    private static OperationIssuer operationIssuer;

    static {
        LogbackUtils.loadLogbackCli();
    }

    public static void main(String[] args) throws ParseException {
        Options options = new Options();
        options.addRequiredOption("n", "operations", true, "number of operations per second");
        options.addRequiredOption("g", "graph", true, "path to graph");
        options.addOption("l", "load", false, "decide whether to load graph before running tests");
        options.addOption("x", "exit-on-fail", false, "exit on first fail");
        options.addOption("b", "bulk", true, "enable bulk requests and set bulk size");
        options.addOption("t", "concurrent-pool", true, "enable concurrency and set pool size");
        options.addOption(null, "prob.perms", true,
                "probability that a random operation changes permissions");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        loadGraph = cmd.hasOption("l");
        exitOnFail = cmd.hasOption("x");
        bulkSize = Integer.parseInt(cmd.getOptionValue("b"));
        maxPoolSize = Integer.parseInt(cmd.getOptionValue("t", "0"));
        operationsPerSecond = Integer.parseInt(cmd.getOptionValue("n"));
        graph = GraphLoader.loadGraph(cmd.getOptionValue("g"));
        permsProbability = Double.parseDouble(cmd.getOptionValue("prob.perms", "0.8"));

        if (loadGraph) {
            loadGraph();
        }


        baseOperationIssuer = new ConcurrentOperationIssuer(maxPoolSize, new ZoneClient());
        if (bulkSize >= 1) {
            operationIssuer = new BulkOperationIssuer(baseOperationIssuer, bulkSize, Duration.ZERO);
        } else {
            operationIssuer = baseOperationIssuer;
        }
        randomOperationIssuer =
                new RandomOperationIssuer(graph)
                        .withPermissionsProbability(permsProbability)
                        .withOperationIssuer(operationIssuer);


        log.info("Running constant load: {} requests per second", operationsPerSecond);
        runRandomOperations();
    }

    private static void loadGraph() {
        ZoneClient client = new ZoneClient();
        new RemoteGraphBuilder(graph, client).build(client);
    }

    private static void runRandomOperations() {
        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger errored = new AtomicInteger(0);
        scheduleRequestExecutor(count, errored);

        EventStatsGatherer eventStatsGatherer = new EventStatsGatherer(graph.allZones());

        Instant last = Instant.now();
        long total = 0;
        while (!Thread.interrupted()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }

            Instant now = Instant.now();

            EventStats stats = eventStatsGatherer.get();
            int currentCount = count.getAndSet(0);
            total += currentCount;
            double gps = (double) currentCount / Duration.between(last, now).toMillis() * Duration.ofSeconds(1).toMillis();
            last = now;
            log.info("{}  (gps={}, err={}, tot={}, sat={}, rt={})",
                    stats.toString(),
                    fd(gps),
                    errored.get() + baseOperationIssuer.getFailed(),
                    total,
                    fd(baseOperationIssuer.getSaturation()),
                    fd(baseOperationIssuer.getRequestTime()));
        }

        log.info("Interrupted. Shutting down gracefully...");
        scheduledExecutor.shutdown();
        try {
            scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.info("Interrupted. Exiting...");
        }
    }

    private static void scheduleRequestExecutor(AtomicInteger count, AtomicInteger errored) {
        if (operationsPerSecond == 0) return;

        long period = (long) (1e9 / operationsPerSecond);
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                randomOperationIssuer.perform();
            } catch (Throwable t) {
                log.error("Error while performing operation", t);
                errored.incrementAndGet();
                if (exitOnFail) System.exit(1);
            }
            count.incrementAndGet();
        }, 0, period, TimeUnit.NANOSECONDS);
    }

    private static String fd(double d) {
        return String.format("%.2f", d);
    }
}
