package com.orbitlink.signup.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orbitlink.signup.IntegrationTest;
import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CoverageGrid;

class OutboxAndAdminIT extends IntegrationTest {

    private static final double LAT = 52.5170, LNG = 13.3889;

    @Autowired OutboxWorker worker;
    @Autowired CellRepository cells;
    @Autowired CoverageGrid grid;

    @Test
    void addingCapacityPromotesAndTheWorkerDeliversEachNotificationOnce() throws Exception {
        String h3 = grid.cellFor(LAT, LNG);
        cells.ensureExists(h3, 0);
        HttpResponse<String> joined = postOrder(newKey(), orderBody(LAT, LNG, "wartend@example.com", "tok_visa"), null);
        assertThat(body(joined).at("/kind").asString()).isEqualTo("WAITLIST");
        assertThat(body(joined).at("/waitlist/position").asLong()).isEqualTo(1);

        assertThat(admin("POST", "/api/admin/cells/" + h3 + "/capacity", "{\"slots\":2}", "dev-admin-token").body())
                .contains("\"promoted\":1");

        assertThat(worker.drainBatch()).isEqualTo(2);    // WAITLIST_JOINED + WAITLIST_PROMOTED
        assertThat(worker.drainBatch()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE delivered_at IS NULL").query(Long.class).single()).isZero();

        HttpResponse<String> stats = admin("GET", "/api/admin/cells", null, "dev-admin-token");
        assertThat(stats.statusCode()).isEqualTo(200);
        assertThat(body(stats).get(0).get("currency").asString()).isEqualTo("EUR");
        assertThat(body(stats).get(0).get("capacity").asInt()).isEqualTo(2);
        assertThat(body(stats).get(0).get("promoted").asInt()).isEqualTo(1);
    }

    @Test
    void adminApiNeedsTheToken() throws Exception {
        assertThat(admin("GET", "/api/admin/cells", null, null).statusCode()).isEqualTo(401);
        assertThat(admin("GET", "/api/admin/cells", null, "wrong").statusCode()).isEqualTo(401);
        assertThat(admin("POST", "/api/admin/cells/not-a-cell/capacity", "{\"slots\":1}", "dev-admin-token").statusCode())
                .isEqualTo(404);
    }

    private HttpResponse<String> admin(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
