package io.orderflow;

import java.util.*;

/** Storage seam shared by the local in-memory and production JDBC adapters. */
public interface OrderRepository {
    boolean insert(Map<String,Object> order);
    Optional<Map<String,Object>> findById(String orderId);
    Optional<Map<String,Object>> findByIdempotency(String customerId,String key);
    Optional<Map<String,Object>> compareAndSet(String orderId,String expectedStatus,int expectedVersion,Map<String,Object> replacement);
    List<Map<String,Object>> findPage(String customerId,String status,Long createdBeforeMillis,String orderIdBefore,int limit);
    List<Map<String,Object>> findDue(long nowMillis,int limit);
}
