package at.unleash.it.outboxvisualiser.record;

/** Number of FAILED records per handler and record type. */
public record FailureGroup(String handlerId, String recordType, long count) {

    public String shortType() {
        return Names.simpleTypeName(recordType);
    }

    public String shortHandler() {
        return Names.shortHandler(handlerId);
    }
}
