package at.unleash.it.outboxvisualiser.cluster;

import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.SqlTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Read access to the Namastack {@code outbox_instance} and {@code outbox_partition} tables. */
@Repository
public class ClusterRepository {

    public List<InstanceView> findInstances(OutboxSource source) {
        return source.jdbc()
                .sql("SELECT instance_id, hostname, port, status, started_at, last_heartbeat FROM "
                        + source.tables().instance() + " ORDER BY started_at, instance_id")
                .query((rs, rowNum) -> new InstanceView(
                        rs.getString("instance_id"),
                        rs.getString("hostname"),
                        rs.getInt("port"),
                        rs.getString("status"),
                        SqlTime.instant(rs, "started_at"),
                        SqlTime.instant(rs, "last_heartbeat")))
                .list();
    }

    public List<PartitionView> findPartitions(OutboxSource source) {
        return source.jdbc()
                .sql("SELECT partition_number, instance_id, version, updated_at FROM "
                        + source.tables().partition() + " ORDER BY partition_number")
                .query((rs, rowNum) -> new PartitionView(
                        rs.getInt("partition_number"),
                        rs.getString("instance_id"),
                        rs.getLong("version"),
                        SqlTime.instant(rs, "updated_at")))
                .list();
    }
}
