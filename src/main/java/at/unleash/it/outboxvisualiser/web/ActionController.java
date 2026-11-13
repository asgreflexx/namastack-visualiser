package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.action.ActionResult;
import at.unleash.it.outboxvisualiser.action.AuditLog;
import at.unleash.it.outboxvisualiser.action.RecordActionService;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Write actions, called by htmx. The response is either the refreshed record drawer
 * ({@code view=drawer}) or nothing, which removes the calling table row ({@code view=none}); both
 * carry an out-of-band toast with the outcome.
 */
@Controller
public class ActionController {

    private final OutboxSourceRegistry registry;
    private final RecordActionService actions;
    private final OutboxRecordRepository repository;
    private final AuditLog auditLog;

    public ActionController(OutboxSourceRegistry registry, RecordActionService actions,
                            OutboxRecordRepository repository, AuditLog auditLog) {
        this.registry = registry;
        this.actions = actions;
        this.repository = repository;
        this.auditLog = auditLog;
    }

    @PostMapping("/actions/{sourceId}/records/{id}/retry")
    public String retry(@PathVariable String sourceId, @PathVariable String id,
                        @RequestParam(defaultValue = "drawer") String view, Principal principal, Model model) {
        OutboxSource source = registry.get(sourceId);
        return respond(source, actions.retry(source, id, principal.getName()), view, model);
    }

    @PostMapping("/actions/{sourceId}/records/{id}/run-now")
    public String runNow(@PathVariable String sourceId, @PathVariable String id,
                         @RequestParam(defaultValue = "drawer") String view, Principal principal, Model model) {
        OutboxSource source = registry.get(sourceId);
        return respond(source, actions.runNow(source, id, principal.getName()), view, model);
    }

    @PostMapping("/actions/{sourceId}/records/{id}/replay")
    public String replay(@PathVariable String sourceId, @PathVariable String id,
                         @RequestParam(defaultValue = "drawer") String view, Principal principal, Model model) {
        OutboxSource source = registry.get(sourceId);
        return respond(source, actions.replay(source, id, principal.getName()), view, model);
    }

    @PostMapping("/actions/{sourceId}/bulk-retry")
    public String bulkRetry(@PathVariable String sourceId,
                            @RequestParam(required = false) String handlerId,
                            @RequestParam(required = false) String recordType,
                            Principal principal, Model model) {
        OutboxSource source = registry.get(sourceId);
        return respond(source, actions.bulkRetry(source, handlerId, recordType, principal.getName()), "none", model);
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("entries", auditLog.recent());
        return "audit";
    }

    private String respond(OutboxSource source, ActionResult result, String view, Model model) {
        model.addAttribute("result", result);
        model.addAttribute("source", source);
        if ("drawer".equals(view) && result.recordId() != null) {
            repository.findById(source, result.recordId()).ifPresent(record -> model.addAttribute("record", record));
        }
        return "action-response";
    }
}
