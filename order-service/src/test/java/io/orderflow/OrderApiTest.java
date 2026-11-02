package io.orderflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class OrderApiTest {
    private static final AtomicInteger LOGIN_IP = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private String login(String email, String password) throws Exception {
        String body=mvc.perform(post("/api/auth/login").with(request->{request.setRemoteAddr(uniqueLoginIp());return request;}).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("email",email,"password",password))))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("accessToken").asText();
    }
    @Test void credentialsAndPlaceUseCatalogPriceAndTokenOwner() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ada@harbor.example\",\"password\":\"wrong\"}"))
          .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Email or password is incorrect"));
        String token=login("ada@harbor.example","test-password");
        mvc.perform(post("/api/orders").header("Authorization","Bearer "+token).header("Idempotency-Key","test-key-0001").contentType(MediaType.APPLICATION_JSON)
          .content("{\"customerId\":\"foreign\",\"items\":[{\"sku\":\"HDMI-1M\",\"quantity\":2,\"unitPriceMinor\":1}]}"))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.totalMinor").value(1998)).andExpect(jsonPath("$.customerId").value("cust-ada"));
    }
    @Test void ownerCanReadListAndCancelButCannotSkipStatus() throws Exception {
        String customer=login("ada@harbor.example","test-password"),operator=login("ops@harbor.example","test-password");
        String response=mvc.perform(post("/api/orders").header("Authorization","Bearer "+customer).header("Idempotency-Key","test-key-0002").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"KEY-1\",\"quantity\":1}]}"))
          .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();String id=mapper.readTree(response).get("orderId").asText();
        mvc.perform(get("/api/orders/"+id).header("Authorization","Bearer "+customer)).andExpect(status().isOk());
        mvc.perform(get("/api/orders").header("Authorization","Bearer "+customer)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(patch("/api/orders/"+id+"/status").header("Authorization","Bearer "+operator).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
          .andExpect(status().isConflict());
        mvc.perform(post("/api/orders/"+id+"/cancel").header("Authorization","Bearer "+customer).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Changed my mind\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }
    @Test void rejectsMissingSessionUnknownSkuAndBadQuantity() throws Exception {
        mvc.perform(get("/api/catalog")).andExpect(status().isUnauthorized());String token=login("ada@harbor.example","test-password");
        mvc.perform(post("/api/orders").header("Authorization","Bearer "+token).header("Idempotency-Key","test-key-0003").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"NOPE\",\"quantity\":1}]}"))
          .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Authorization","Bearer "+token).header("Idempotency-Key","test-key-0004").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"KEY-1\",\"quantity\":100}]}"))
          .andExpect(status().isBadRequest());
    }

    @Test void createsMultiItemOrderAndFiltersListByStatus() throws Exception {
        String customer=login("ada@harbor.example","test-password"),operator=login("ops@harbor.example","test-password");
        String pending=mvc.perform(post("/api/orders").header("Authorization","Bearer "+customer).header("Idempotency-Key","multi-item-order-0001").contentType(MediaType.APPLICATION_JSON)
          .content("{\"items\":[{\"sku\":\"HDMI-1M\",\"quantity\":2},{\"sku\":\"KEY-1\",\"quantity\":1}]}"))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalMinor").value(6997)).andReturn().getResponse().getContentAsString();
        String pendingId=mapper.readTree(pending).get("orderId").asText();
        mvc.perform(post("/api/orders").header("Authorization","Bearer "+customer).header("Idempotency-Key","multi-item-order-0002").contentType(MediaType.APPLICATION_JSON)
          .content("{\"items\":[{\"sku\":\"USB-C\",\"quantity\":1}]}"))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(patch("/api/orders/"+pendingId+"/status").header("Authorization","Bearer "+operator).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PROCESSING\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        mvc.perform(get("/api/orders?status=PROCESSING").header("Authorization","Bearer "+customer))
          .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].orderId").value(pendingId));
    }

    @Test void onlyPendingOrdersCanBeCancelledAndOperatorMovesOneStepAtATime() throws Exception {
        String customer=login("ada@harbor.example","test-password"),operator=login("ops@harbor.example","test-password");
        String response=mvc.perform(post("/api/orders").header("Authorization","Bearer "+customer).header("Idempotency-Key","status-flow-0001").contentType(MediaType.APPLICATION_JSON)
          .content("{\"items\":[{\"sku\":\"USB-C\",\"quantity\":1}]}"))
          .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(response).get("orderId").asText();
        mvc.perform(patch("/api/orders/"+id+"/status").header("Authorization","Bearer "+operator).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PROCESSING\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        mvc.perform(post("/api/orders/"+id+"/cancel").header("Authorization","Bearer "+customer).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isConflict());
        mvc.perform(patch("/api/orders/"+id+"/status").header("Authorization","Bearer "+operator).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SHIPPED\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(patch("/api/orders/"+id+"/status").header("Authorization","Bearer "+operator).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test void loginRateLimitDoesNotBlockOtherAccountsBehindTheSameProxy() throws Exception {
        for(int i=0;i<5;i++){
            mvc.perform(post("/api/auth/login").with(request->{request.setRemoteAddr("192.0.2.99");return request;})
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ada@harbor.example\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/login").with(request->{request.setRemoteAddr("192.0.2.99");return request;})
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ops@harbor.example\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk());
    }

    @Test void cursorPaginationReturnsDistinctPagesAndRejectsInvalidCursor() throws Exception {
        String customer=login("ada@harbor.example","test-password");
        for(String key:List.of("cursor-page-order-0001","cursor-page-order-0002")){
            mvc.perform(post("/api/orders").header("Authorization","Bearer "+customer).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"USB-C\",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
        }
        String firstBody=mvc.perform(get("/api/orders").param("limit","1").header("Authorization","Bearer "+customer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").exists()).andReturn().getResponse().getContentAsString();
        var first=mapper.readTree(firstBody);
        String cursor=first.get("nextCursor").asText();
        String secondBody=mvc.perform(get("/api/orders").param("limit","1").param("cursor",cursor).header("Authorization","Bearer "+customer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNotEquals(first.get("items").get(0).get("orderId").asText(),mapper.readTree(secondBody).get("items").get(0).get("orderId").asText());
        mvc.perform(get("/api/orders").param("cursor","not-a-cursor").header("Authorization","Bearer "+customer))
            .andExpect(status().isBadRequest());
    }

    private String uniqueLoginIp() { return "192.0.2." + LOGIN_IP.incrementAndGet(); }
}
