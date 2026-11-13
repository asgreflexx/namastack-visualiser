package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.record.FailureGroup;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import at.unleash.it.outboxvisualiser.stats.StatsService;
import at.unleash.it.outboxvisualiser.stats.StatsService.SourceSummary;
import at.unleash.it.outboxvisualiser.stats.StatsService.Throughput;
import at.unleash.it.outboxvisualiser.stats.StatsService.Window;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class DashboardController {

    private final OutboxSourceRegistry registry;
    private final StatsService stats;

    public DashboardController(OutboxSourceRegistry registry, StatsService stats) {
        this.registry = registry;
        this.stats = stats;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) String source,
                            @RequestParam(defaultValue = "H1") Window window,
                            Model model) {
        registry.select(source);
        model.addAttribute("selectedSource", source == null ? "" : source);
        model.addAttribute("window", window);
        model.addAttribute("windows", Window.values());
        addSummaries(source, model);
        return "dashboard";
    }

    @GetMapping("/fragments/summary")
    public String summary(@RequestParam(required = false) String source, Model model) {
        model.addAttribute("selectedSource", source == null ? "" : source);
        addSummaries(source, model);
        return "dashboard :: summary";
    }

    @GetMapping("/api/throughput")
    @ResponseBody
    public ThroughputResponse throughput(@RequestParam(required = false) String source,
                                         @RequestParam(defaultValue = "H1") Window window) {
        Throughput throughput = stats.throughput(registry.select(source), window);
        return new ThroughputResponse(throughput.bucketStarts(), throughput.created(), throughput.completed(),
                window.bucket().toSeconds());
    }

    @GetMapping("/api/failures")
    @ResponseBody
    public List<FailureResponse> failures(@RequestParam(required = false) String source) {
        return stats.failures(registry.select(source), 12).stream()
                .map(FailureResponse::of)
                .toList();
    }

    private void addSummaries(String source, Model model) {
        List<SourceSummary> summaries = registry.select(source).stream().map(stats::summary).toList();
        model.addAttribute("summaries", summaries);
        model.addAttribute("totals", Totals.of(summaries));
    }

    public record Totals(long newCount, long failedCount, long completedCount, long blockedKeys, Duration maxLag) {

        static Totals of(List<SourceSummary> summaries) {
            Duration maxLag = summaries.stream()
                    .map(SourceSummary::lag)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            return new Totals(
                    summaries.stream().mapToLong(SourceSummary::newCount).sum(),
                    summaries.stream().mapToLong(SourceSummary::failedCount).sum(),
                    summaries.stream().mapToLong(SourceSummary::completedCount).sum(),
                    summaries.stream().mapToLong(SourceSummary::blockedKeys).sum(),
                    maxLag);
        }
    }

    public record ThroughputResponse(List<Instant> buckets, long[] created, long[] completed, long bucketSeconds) {
    }

    public record FailureResponse(String handler, String handlerId, String type, String recordType, long count) {

        static FailureResponse of(FailureGroup group) {
            return new FailureResponse(group.shortHandler(), group.handlerId(), group.shortType(), group.recordType(), group.count());
        }
    }
}
