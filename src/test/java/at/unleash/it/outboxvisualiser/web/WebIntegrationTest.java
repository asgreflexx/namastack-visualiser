package at.unleash.it.outboxvisualiser.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.unleash.it.outboxvisualiser.OutboxTestData;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Renders every page against an H2 source containing the Namastack schema and calls the actions. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "visualiser.sources[0].id=orders",
        "visualiser.sources[0].name=Orders",
        "visualiser.sources[0].url=jdbc:h2:mem:web-test;DB_CLOSE_DELAY=-1",
        "visualiser.sources[0].username=sa"
})
class WebIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxSourceRegistry registry;

    private OutboxSource source;
    private String failedId;

    @BeforeEach
    void setUp() {
        source = registry.get("orders");
        source.jdbc().sql("DROP ALL OBJECTS").update();
        new ResourceDatabasePopulator(new ClassPathResource("demo/schema-h2.sql")).execute(source.dataSource());
        Instant t0 = Instant.now().minusSeconds(120);
        OutboxTestData.insert(source, "order-1", RecordStatus.COMPLETED, t0);
        failedId = OutboxTestData.insert(source, "order-1", RecordStatus.FAILED, t0.plusSeconds(1));
        OutboxTestData.insert(source, "order-1", RecordStatus.NEW, t0.plusSeconds(2));
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void rendersAllPages() throws Exception {
        mvc.perform(get("/").with(user("v").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Orders")));
        mvc.perform(get("/fragments/summary").with(user("v").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/api/throughput").param("window", "M15").with(user("v").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"created\"")));
        mvc.perform(get("/api/failures").with(user("v").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Handler#on")));
        mvc.perform(get("/records").with(user("v").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("order-1")));
        mvc.perform(get("/records/orders/" + failedId).header("HX-Request", "true").with(user("v").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("boom")));
        mvc.perform(get("/keys").param("key", "order-1").with(user("v").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("1 Record wartet")));
        mvc.perform(get("/blocked").with(user("v").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("order-1")));
        mvc.perform(get("/cluster").with(user("v").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/audit").with(user("v").roles("VIEWER"))).andExpect(status().isOk());
    }

    @Test
    void viewersCannotRetry() throws Exception {
        mvc.perform(post("/actions/orders/records/" + failedId + "/retry").with(csrf()).with(user("v").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorsCanRetry() throws Exception {
        mvc.perform(post("/actions/orders/records/" + failedId + "/retry").with(csrf()).with(user("o").roles("VIEWER", "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Record wird erneut verarbeitet.")));
    }

    @Test
    void unknownSourceIsNotFound() throws Exception {
        mvc.perform(get("/records/nope/x").with(user("v").roles("VIEWER"))).andExpect(status().isNotFound());
    }
}
