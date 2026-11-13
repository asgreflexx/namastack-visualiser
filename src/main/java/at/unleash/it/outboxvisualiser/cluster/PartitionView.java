package at.unleash.it.outboxvisualiser.cluster;

import java.time.Instant;

/** One row of the Namastack {@code outbox_partition} table. {@code instanceId} is null when unassigned. */
public record PartitionView(int partitionNumber, String instanceId, long version, Instant updatedAt) {
}
