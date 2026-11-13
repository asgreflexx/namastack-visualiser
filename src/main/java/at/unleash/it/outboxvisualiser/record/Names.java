package at.unleash.it.outboxvisualiser.record;

/** Shortens fully qualified type names and Namastack handler ids for display. */
public final class Names {

    private Names() {
    }

    /** {@code com.example.OrderEvents$Created} becomes {@code OrderEvents$Created}. */
    public static String simpleTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }

    /**
     * Generated Namastack handler ids look like {@code com.example.Handler#handle(com.example.Event)}.
     * They become {@code Handler#handle}. Explicitly configured ids are returned unchanged.
     */
    public static String shortHandler(String handlerId) {
        if (handlerId == null) {
            return "";
        }
        int hash = handlerId.indexOf('#');
        if (hash < 0) {
            return handlerId;
        }
        String className = simpleTypeName(handlerId.substring(0, hash));
        String method = handlerId.substring(hash + 1);
        int paren = method.indexOf('(');
        return className + "#" + (paren < 0 ? method : method.substring(0, paren));
    }
}
