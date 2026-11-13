package at.unleash.it.outboxvisualiser.cluster;

import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.VisualiserProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Combines instances, partition assignments and open records per partition into one picture. */
@Service
public class ClusterService {

    /** Namastack's fixed partition count ({@code PartitionHasher.TOTAL_PARTITIONS}). */
    public static final int TOTAL_PARTITIONS = 256;

    private final ClusterRepository clusterRepository;
    private final OutboxRecordRepository recordRepository;
    private final Duration staleAfter;
    private final Clock clock;

    public ClusterService(ClusterRepository clusterRepository, OutboxRecordRepository recordRepository,
                          VisualiserProperties properties, Clock clock) {
        this.clusterRepository = clusterRepository;
        this.recordRepository = recordRepository;
        this.staleAfter = properties.instanceStaleAfter();
        this.clock = clock;
    }

    public ClusterOverview overview(OutboxSource source) {
        Instant now = clock.instant();
        List<InstanceView> instances = clusterRepository.findInstances(source);
        Map<Integer, PartitionView> assignments = clusterRepository.findPartitions(source).stream()
                .collect(Collectors.toMap(PartitionView::partitionNumber, Function.identity()));
        Map<Integer, Long> openRecords = recordRepository.openRecordsPerPartition(source);

        // Colour slot per instance in the order Namastack registered them.
        Map<String, Integer> colourSlots = new LinkedHashMap<>();
        for (InstanceView instance : instances) {
            colourSlots.put(instance.instanceId(), colourSlots.size());
        }

        List<InstanceSummary> summaries = new ArrayList<>();
        for (InstanceView instance : instances) {
            long owned = assignments.values().stream().filter(p -> instance.instanceId().equals(p.instanceId())).count();
            summaries.add(new InstanceSummary(instance, instance.health(now, staleAfter), instance.heartbeatAge(now),
                    owned, colourSlots.get(instance.instanceId())));
        }

        List<PartitionCell> cells = new ArrayList<>(TOTAL_PARTITIONS);
        for (int number = 0; number < TOTAL_PARTITIONS; number++) {
            PartitionView assignment = assignments.get(number);
            String owner = assignment == null ? null : assignment.instanceId();
            Integer slot = owner == null ? null : colourSlots.getOrDefault(owner, -1);
            cells.add(new PartitionCell(number, owner, slot, openRecords.getOrDefault(number, 0L)));
        }
        long unassigned = cells.stream().filter(c -> c.instanceId() == null).count();
        return new ClusterOverview(summaries, cells, unassigned);
    }

    public record ClusterOverview(List<InstanceSummary> instances, List<PartitionCell> partitions, long unassignedPartitions) {
    }

    public record InstanceSummary(InstanceView instance, InstanceView.Health health, Duration heartbeatAge,
                                  long ownedPartitions, int colourSlot) {
    }

    /**
     * @param colourSlot index of the owning instance, -1 for an owner that is not registered, null when unassigned
     */
    public record PartitionCell(int number, String instanceId, Integer colourSlot, long openRecords) {
    }
}
