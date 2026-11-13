package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.record.OutboxRecordView;
import at.unleash.it.outboxvisualiser.record.PageResult;
import at.unleash.it.outboxvisualiser.record.RecordFilter;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RecordController {

    private static final int PAGE_SIZE = 50;

    private final OutboxSourceRegistry registry;
    private final OutboxRecordRepository repository;

    public RecordController(OutboxSourceRegistry registry, OutboxRecordRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @GetMapping("/records")
    public String records(RecordFilter filter,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestHeader(name = "HX-Target", required = false) String hxTarget,
                          Model model) {
        List<OutboxSource> all = registry.all();
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", RecordStatus.values());
        if (all.isEmpty()) {
            model.addAttribute("result", new PageResult<OutboxRecordView>(List.of(), 0, PAGE_SIZE, 0));
            return "records";
        }
        OutboxSource source = filter.source() == null || filter.source().isBlank() ? all.getFirst() : registry.get(filter.source());
        model.addAttribute("source", source);
        try {
            model.addAttribute("result", repository.find(source, filter, Math.max(page, 0), PAGE_SIZE));
            if (!"record-table".equals(hxTarget)) {
                model.addAttribute("recordTypes", repository.distinctRecordTypes(source));
                model.addAttribute("handlerIds", repository.distinctHandlerIds(source));
            }
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("result", new PageResult<OutboxRecordView>(List.of(), 0, PAGE_SIZE, 0));
        }
        return "record-table".equals(hxTarget) ? "records :: table" : "records";
    }

    @GetMapping("/records/{sourceId}/{id}")
    public String record(@PathVariable String sourceId, @PathVariable String id,
                         @RequestHeader(name = "HX-Request", required = false) String htmx,
                         Model model) {
        OutboxSource source = registry.get(sourceId);
        OutboxRecordView record = repository.findById(source, id)
                .orElseThrow(() -> new NoSuchElementException("Record " + id + " not found in source '" + sourceId + "'"));
        model.addAttribute("source", source);
        model.addAttribute("record", record);
        return htmx != null ? "record-detail :: drawer" : "record-detail";
    }
}
