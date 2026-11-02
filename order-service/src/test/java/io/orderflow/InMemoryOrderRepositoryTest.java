package io.orderflow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryOrderRepositoryTest {
    @Test void keysetOrderingUsesEpochMillisEvenWhenIsoFractionsHaveDifferentWidths() {
        InMemoryOrderRepository repository=new InMemoryOrderRepository();
        repository.insert(order("ord_older",100,"2026-11-02T10:00:00.100Z","key-older"));
        repository.insert(order("ord_newer",101,"2026-11-02T10:00:00.101Z","key-newer"));

        List<Map<String,Object>> first=repository.findPage(null,null,null,null,1);
        assertEquals("ord_newer",first.get(0).get("orderId"));
        List<Map<String,Object>> next=repository.findPage(null,null,101L,"ord_newer",1);
        assertEquals("ord_older",next.get(0).get("orderId"));
    }

    @Test void dueIndexComparesNumericInstantsAcrossWholeSecondBoundary() {
        InMemoryOrderRepository repository=new InMemoryOrderRepository();
        repository.insert(order("ord_whole_second",100,"2026-11-02T10:00:00Z","key-whole"));
        repository.insert(order("ord_fractional",101,"2026-11-02T10:00:00.100Z","key-fractional"));

        List<Map<String,Object>> due=repository.findDue(Instant.parse("2026-11-02T10:00:00.050Z").toEpochMilli(),10);
        assertEquals(1,due.size());
        assertEquals("ord_whole_second",due.get(0).get("orderId"));
    }

    private static Map<String,Object> order(String id,long createdAt,String timestamp,String idempotencyKey) {
        Map<String,Object> order=new HashMap<>();
        order.put("orderId",id);
        order.put("customerId","customer-001");
        order.put("status","PENDING");
        order.put("createdAtEpochMillis",createdAt);
        order.put("createdAt",timestamp);
        order.put("idempotencyKey",idempotencyKey);
        order.put("processAfter",timestamp);
        order.put("version",1);
        return order;
    }
}
