package io.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class JdbcOrderRepositoryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;

    @AfterEach void cleanRows() {
        jdbc.update("DELETE FROM orders WHERE order_id LIKE 'ord_persist_test_%'");
    }

    @Test void orderAndIdempotencyRecordSurviveRepositoryRecreationAndCas() {
        JdbcOrderRepository repository=new JdbcOrderRepository(jdbc,mapper);
        long now=System.currentTimeMillis();
        String id="ord_persist_test_0001";
        Map<String,Object> order=new HashMap<>();
        order.put("orderId",id);
        order.put("customerId","customer-persist-test");
        order.put("customerName","Persistent Customer");
        order.put("status","PENDING");
        order.put("createdAtEpochMillis",now);
        order.put("createdAt",Instant.ofEpochMilli(now).toString());
        order.put("processAfter",Instant.ofEpochMilli(now-1000).toString());
        order.put("idempotencyKey","persistent-test-key");
        order.put("requestHash","a".repeat(64));
        order.put("version",1);
        order.put("items",List.of(Map.of("sku","USB-C","quantity",1)));

        assertTrue(repository.insert(order));
        assertEquals(id,repository.findByIdempotency("customer-persist-test","persistent-test-key").orElseThrow().get("orderId"));
        assertEquals(id,repository.findPage("customer-persist-test","PENDING",null,null,2).get(0).get("orderId"));
        assertEquals(1,repository.findDue(now,10).size());

        JdbcOrderRepository afterRestart=new JdbcOrderRepository(new JdbcTemplate(dataSource),mapper);
        assertEquals("PENDING",afterRestart.findById(id).orElseThrow().get("status"));
        Map<String,Object> replacement=new HashMap<>(order);
        replacement.put("status","PROCESSING");
        replacement.put("version",2);
        replacement.remove("processAfter");
        assertTrue(afterRestart.compareAndSet(id,"PENDING",1,replacement).isPresent());
        assertTrue(afterRestart.compareAndSet(id,"PENDING",1,replacement).isEmpty());
        assertTrue(repository.findDue(now,10).isEmpty());
        assertEquals("PROCESSING",repository.findById(id).orElseThrow().get("status"));
    }
}
