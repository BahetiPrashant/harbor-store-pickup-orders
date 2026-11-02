package io.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "orderflow.embedded-promote=true",
    "orderflow.promote-interval-ms=50"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderPromotionScheduleTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void scheduledJobPromotesDuePendingOrder() throws Exception {
        String login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("email","ada@harbor.example","password","test-password"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=mapper.readTree(login).get("accessToken").asText();
        String placed=mvc.perform(post("/api/orders").header("Authorization","Bearer "+token)
            .header("Idempotency-Key","scheduled-promotion-test-0001").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"sku\":\"USB-C\",\"quantity\":1}]}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(placed).get("orderId").asText();

        long deadline=System.nanoTime()+3_000_000_000L;
        String observed="PENDING";
        String actorRole="";
        while (System.nanoTime()<deadline && "PENDING".equals(observed)) {
            Thread.sleep(25);
            String body=mvc.perform(get("/api/orders/"+id).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var order=mapper.readTree(body);
            observed=order.get("status").asText();
            if ("PROCESSING".equals(observed)) actorRole=order.get("history").get(order.get("history").size()-1).get("actorRole").asText();
        }
        assertEquals("PROCESSING",observed,"the scheduled promoter should move a due order to PROCESSING");
        assertEquals("WORKER",actorRole,"the scheduled promotion should be recorded as a worker transition");
    }
}
