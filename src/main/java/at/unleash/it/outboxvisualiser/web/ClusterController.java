package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.cluster.ClusterService;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ClusterController {

    private final OutboxSourceRegistry registry;
    private final ClusterService clusterService;

    public ClusterController(OutboxSourceRegistry registry, ClusterService clusterService) {
        this.registry = registry;
        this.clusterService = clusterService;
    }

    @GetMapping("/cluster")
    public String cluster(@RequestParam(required = false) String source,
                          @RequestHeader(name = "HX-Request", required = false) String htmx,
                          Model model) {
        if (registry.all().isEmpty()) {
            return "cluster";
        }
        OutboxSource selected = source == null || source.isBlank() ? registry.all().getFirst() : registry.get(source);
        model.addAttribute("source", selected);
        try {
            model.addAttribute("overview", clusterService.overview(selected));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return htmx != null ? "cluster :: content" : "cluster";
    }
}
