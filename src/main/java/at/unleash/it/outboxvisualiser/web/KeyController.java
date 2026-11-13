package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import java.time.Clock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class KeyController {

    private static final int MAX_RECORDS = 500;

    private final OutboxSourceRegistry registry;
    private final OutboxRecordRepository repository;
    private final Clock clock;

    public KeyController(OutboxSourceRegistry registry, OutboxRecordRepository repository, Clock clock) {
        this.registry = registry;
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/keys")
    public String timeline(@RequestParam(required = false) String source,
                           @RequestParam(required = false) String key,
                           Model model) {
        model.addAttribute("key", key == null ? "" : key);
        if (registry.all().isEmpty()) {
            return "key";
        }
        OutboxSource selected = source == null || source.isBlank() ? registry.all().getFirst() : registry.get(source);
        model.addAttribute("source", selected);
        if (key != null && !key.isBlank()) {
            try {
                model.addAttribute("timeline",
                        KeyTimeline.of(key, repository.findByKey(selected, key, MAX_RECORDS), clock.instant()));
                model.addAttribute("maxRecords", MAX_RECORDS);
            } catch (RuntimeException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "key";
    }
}
