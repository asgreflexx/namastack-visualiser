package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.record.BlockedKey;
import at.unleash.it.outboxvisualiser.record.FailureGroup;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** FAILED records that hold back their key, grouped per source, with bulk-retry per handler/type. */
@Controller
public class BlockedController {

    private static final int LIMIT = 200;

    private final OutboxSourceRegistry registry;
    private final OutboxRecordRepository repository;

    public BlockedController(OutboxSourceRegistry registry, OutboxRecordRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @GetMapping("/blocked")
    public String blocked(@RequestParam(required = false) String source, Model model) {
        List<SourceBlocked> result = new ArrayList<>();
        for (OutboxSource selected : registry.select(source)) {
            try {
                result.add(new SourceBlocked(selected, repository.blockedKeys(selected, LIMIT),
                        repository.failuresByHandler(selected, 50), null));
            } catch (RuntimeException e) {
                result.add(new SourceBlocked(selected, List.of(), List.of(), e.getMessage()));
            }
        }
        model.addAttribute("selectedSource", source == null ? "" : source);
        model.addAttribute("blocked", result);
        model.addAttribute("limit", LIMIT);
        return "blocked";
    }

    public record SourceBlocked(OutboxSource source, List<BlockedKey> keys, List<FailureGroup> groups, String error) {

        public long maxWaiting() {
            return keys.stream().mapToLong(BlockedKey::waitingRecords).max().orElse(0);
        }
    }
}
