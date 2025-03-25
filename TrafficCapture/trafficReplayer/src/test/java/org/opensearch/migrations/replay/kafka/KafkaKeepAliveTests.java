package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("requiresDocker")
@Tag("isolatedTest")
public class KafkaKeepAliveTests extends InstrumentationTest {
    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String HEARTBEAT_INTERVAL_MS_KEY = "heartbeat.interval.ms";
    public static final long MAX_POLL_INTERVAL_MS = 1000;
    public static final long HEARTBEAT_INTERVAL_MS = 300;
    public static final String testTopicName = "TEST_TOPIC";

    private record TestSetup(
        ConfluentKafkaContainer kafkaContainer,
        Producer<String, byte[]> kafkaProducer,
        AtomicInteger sendCompleteCount,
        Properties kafkaProperties,
        KafkaTrafficCaptureSource kafkaSource,
        BlockingTrafficSource trafficSource,
        ArrayList<ITrafficStreamKey> keysReceived
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (trafficSource != null) {
                trafficSource.close();
            }
            if (kafkaProducer != null) {
                kafkaProducer.close();
            }
            if (kafkaContainer != null) {
                kafkaContainer.close();
            }
        }
    }
    
    private TestSetup setupTestCase() throws Exception {
        // Create and start a new Kafka container for this test
        ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);
        kafkaContainer.start();
        
        Producer<String, byte[]> kafkaProducer = KafkaTestUtils.buildKafkaProducer(kafkaContainer.getBootstrapServers());
        AtomicInteger sendCompleteCount = new AtomicInteger(0);
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 0, sendCompleteCount).get();
        Assertions.assertEquals(1, sendCompleteCount.get());

        Properties kafkaProperties = KafkaTrafficCaptureSource.buildKafkaProperties(
            kafkaContainer.getBootstrapServers(),
            TEST_GROUP_CONSUMER_ID,
            false,
            null
        );
        Assertions.assertNull(kafkaProperties.get(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY));

        kafkaProperties.put(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY, MAX_POLL_INTERVAL_MS + "");
        kafkaProperties.put(HEARTBEAT_INTERVAL_MS_KEY, HEARTBEAT_INTERVAL_MS + "");
        kafkaProperties.put("max.poll.records", 1);
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaProperties);
        KafkaTrafficCaptureSource kafkaSource = new KafkaTrafficCaptureSource(
            rootContext,
            kafkaConsumer,
            testTopicName,
            Duration.ofMillis(MAX_POLL_INTERVAL_MS)
        );
        BlockingTrafficSource trafficSource = new BlockingTrafficSource(kafkaSource, Duration.ZERO);
        ArrayList<ITrafficStreamKey> keysReceived = new ArrayList<>();

        readNextNStreams(rootContext, trafficSource, keysReceived, 0, 1);
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 1, sendCompleteCount);

        return new TestSetup(kafkaContainer, kafkaProducer, sendCompleteCount, kafkaProperties, kafkaSource, trafficSource, keysReceived);
    }


    @Test
    @Tag("longTest")
    public void testTimeoutsDontOccurForSlowPolls() throws Exception {
        try (TestSetup setup = setupTestCase()) {
            var pollIntervalMs = Optional.ofNullable(setup.kafkaProperties.get(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY))
                .map(s -> Integer.valueOf((String) s))
                .orElseThrow();
            var executor = Executors.newSingleThreadScheduledExecutor();
            try {
            executor.schedule(() -> {
                try {
                    var k = setup.keysReceived.get(0);
                    log.info("Calling commit traffic stream for " + k);
                    setup.trafficSource.commitTrafficStream(k);
                    log.info("finished committing traffic stream");
                    log.info("Stop reads to infinity");
                    // this is a way to signal back to the main thread that this thread is done
                    KafkaTestUtils.produceKafkaRecord(testTopicName, setup.kafkaProducer, 2, setup.sendCompleteCount);
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            }, pollIntervalMs, TimeUnit.MILLISECONDS);

                readNextNStreams(rootContext, setup.trafficSource, setup.keysReceived, 1, 2);
            Assertions.assertEquals(3, setup.keysReceived.size());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @Tag("longTest")
    public void testBlockedReadsAndBrokenCommitsDontCauseReordering() throws Exception {
        try (TestSetup setup = setupTestCase()) {
            for (int i = 0; i < 2; ++i) {
                KafkaTestUtils.produceKafkaRecord(testTopicName, setup.kafkaProducer, 1 + i, setup.sendCompleteCount).get();
            }
            readNextNStreams(rootContext, setup.trafficSource, setup.keysReceived, 1, 1);

            setup.trafficSource.commitTrafficStream(setup.keysReceived.get(0));
            log.info(
                "Called commitTrafficStream but waiting long enough for the client to leave the group.  "
                    + "That will make the previous commit a 'zombie-commit' that should easily be dropped."
            );

            log.info(
                "1 message was committed, but not synced, 1 message is being processed."
                    + "wait long enough to fall out of the group before we can commit"
            );
            Thread.sleep(2 * MAX_POLL_INTERVAL_MS);

            // Save the first key for later use
            var firstKey = setup.keysReceived.get(0);
            setup.keysReceived.clear();

            log.info("re-establish a client connection so that the following commit will work");
            log.atInfo().setMessage("1 ...{}").addArgument(() -> setup.kafkaSource.trackingKafkaConsumer.nextCommitsToString()).log();
            readNextNStreams(rootContext, setup.trafficSource, setup.keysReceived, 0, 1);
            log.atInfo().setMessage("2 ...{}").addArgument(() -> setup.kafkaSource.trackingKafkaConsumer.nextCommitsToString()).log();

            log.info("wait long enough to fall out of the group again");
            Thread.sleep(2 * MAX_POLL_INTERVAL_MS);

            var keysReceivedUntilDrop2 = new ArrayList<>(setup.keysReceived);
            setup.keysReceived.clear();
            log.atInfo().setMessage("re-establish... 3 ...{}").addArgument(() -> setup.kafkaSource.trackingKafkaConsumer.nextCommitsToString()).log();
            readNextNStreams(rootContext, setup.trafficSource, setup.keysReceived, 0, 1);
            // Use the second key we've read
            var secondKey = setup.keysReceived.get(0);
            setup.trafficSource.commitTrafficStream(secondKey);
            log.atInfo().setMessage("re-establish... 4 ...{}").addArgument(() -> setup.kafkaSource.trackingKafkaConsumer.nextCommitsToString()).log();
            readNextNStreams(rootContext, setup.trafficSource, setup.keysReceived, 1, 1);
            log.atInfo().setMessage("5 ...{}").addArgument(() -> setup.kafkaSource.trackingKafkaConsumer.nextCommitsToString()).log();

            Thread.sleep(2 * MAX_POLL_INTERVAL_MS);
            var keysReceivedUntilDrop3 = new ArrayList<>(setup.keysReceived);
            setup.keysReceived.clear();
            readNextNStreams(rootContext, setup.trafficSource, setup.keysReceived, 0, 3);
            log.atInfo().setMessage("6 ...{}").addArgument(() -> setup.kafkaSource.trackingKafkaConsumer.nextCommitsToString()).log();
        }
    }

    @SneakyThrows
    private static void readNextNStreams(
        TestContext rootContext,
        BlockingTrafficSource kafkaSource,
        List<ITrafficStreamKey> keysReceived,
        int from,
        int count
    ) {
        Assertions.assertEquals(from, keysReceived.size());
        for (int i = 0; i < count;) {
            var trafficStreams = kafkaSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            trafficStreams.forEach(ts -> {
                var tsk = ts.getKey();
                log.atInfo().setMessage("checking for {}").addArgument(tsk).log();
                Assertions.assertFalse(keysReceived.contains(tsk));
                keysReceived.add(tsk);
            });
            log.info("Read " + trafficStreams.size() + " traffic streams");
            i += trafficStreams.size();
        }
    }
}
