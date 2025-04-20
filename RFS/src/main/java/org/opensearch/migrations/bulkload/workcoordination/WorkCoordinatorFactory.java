package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Clock;
import java.util.function.Consumer;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.version_es_6_8.OpenSearchWorkCoordinator_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchWorkCoordinator_OS_2_11;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@AllArgsConstructor
@Slf4j
public class WorkCoordinatorFactory {
    private final Version version;
    private String indexNameAppendage = "";

    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId
        ) {
        if (VersionMatchers.isOS_1_X.or(VersionMatchers.isOS_2_X).or(VersionMatchers.isES_7_X).test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient,
                indexNameAppendage,
                tolerableClientServerClockDifferenceSeconds,
                workerId);
        } else if (VersionMatchers.isES_6_X.or(VersionMatchers.isES_5_X).test(version)) {
            return new OpenSearchWorkCoordinator_ES_6_8(httpClient,
                indexNameAppendage,
                tolerableClientServerClockDifferenceSeconds,
                workerId);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }
    
    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId,
            Clock clock,
            Consumer<WorkItemAndDuration> workItemConsumer
        ) {
        if (VersionMatchers.isOS_1_X.or(VersionMatchers.isOS_2_X).or(VersionMatchers.isES_7_X).test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient,
                indexNameAppendage,
                tolerableClientServerClockDifferenceSeconds,
                workerId,
                clock,
                workItemConsumer);
        } else if (VersionMatchers.isES_6_X.or(VersionMatchers.isES_5_X).test(version)) {
            return new OpenSearchWorkCoordinator_ES_6_8(httpClient,
                indexNameAppendage,
                tolerableClientServerClockDifferenceSeconds,
                workerId,
                clock,
                workItemConsumer);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }


    public String getFinalIndexName() {
        return OpenSearchWorkCoordinator.getFinalIndexName(indexNameAppendage);
    }
}
