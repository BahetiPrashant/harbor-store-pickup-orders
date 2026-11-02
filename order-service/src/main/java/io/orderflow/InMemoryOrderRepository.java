package io.orderflow;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Indexed process-memory adapter. A write keeps every list and due index in sync. */
@Repository
@Profile("!prod")
public class InMemoryOrderRepository implements OrderRepository {
    private record DueKey(long dueAtMillis,String orderId) implements Comparable<DueKey> {
        @Override public int compareTo(DueKey other) {
            int byTime=Long.compare(dueAtMillis,other.dueAtMillis);
            return byTime!=0 ? byTime : orderId.compareTo(other.orderId);
        }
    }

    private static final Comparator<Map<String,Object>> NEWEST = Comparator
        .comparingLong((Map<String,Object> order) -> ((Number)order.get("createdAtEpochMillis")).longValue())
        .thenComparing(order -> String.valueOf(order.get("orderId")));

    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
    private final Map<String,Map<String,Object>> records = new HashMap<>();
    private final NavigableSet<Map<String,Object>> all = new TreeSet<>(NEWEST);
    private final Map<String,NavigableSet<Map<String,Object>>> byCustomer = new HashMap<>();
    private final Map<String,NavigableSet<Map<String,Object>>> byStatus = new HashMap<>();
    private final Map<String,NavigableSet<Map<String,Object>>> byCustomerStatus = new HashMap<>();
    private final NavigableMap<DueKey,String> due = new TreeMap<>();
    private final Map<String,String> byIdempotency = new HashMap<>();

    private static String indexKey(String customerId,String status) {
        return customerId + "\u0000" + status;
    }

    private static String idempotencyKey(String customerId,String key) {
        return customerId + "\u0000" + key;
    }

    private static DueKey dueKey(Map<String,Object> order) {
        return new DueKey(Instant.parse(String.valueOf(order.get("processAfter"))).toEpochMilli(),
            String.valueOf(order.get("orderId")));
    }

    private void removeIndexes(Map<String,Object> order) {
        String customerId=String.valueOf(order.get("customerId"));
        String status=String.valueOf(order.get("status"));
        all.remove(order);
        Optional.ofNullable(byCustomer.get(customerId)).ifPresent(index -> index.remove(order));
        Optional.ofNullable(byStatus.get(status)).ifPresent(index -> index.remove(order));
        Optional.ofNullable(byCustomerStatus.get(indexKey(customerId,status))).ifPresent(index -> index.remove(order));
        if ("PENDING".equals(status) && order.containsKey("processAfter")) {
            due.remove(dueKey(order));
        }
        if (order.containsKey("idempotencyKey")) {
            byIdempotency.remove(idempotencyKey(customerId,String.valueOf(order.get("idempotencyKey"))));
        }
    }

    private void addIndexes(Map<String,Object> order) {
        String customerId=String.valueOf(order.get("customerId"));
        String status=String.valueOf(order.get("status"));
        all.add(order);
        byCustomer.computeIfAbsent(customerId, ignored -> new TreeSet<>(NEWEST)).add(order);
        byStatus.computeIfAbsent(status, ignored -> new TreeSet<>(NEWEST)).add(order);
        byCustomerStatus.computeIfAbsent(indexKey(customerId,status), ignored -> new TreeSet<>(NEWEST)).add(order);
        if ("PENDING".equals(status) && order.containsKey("processAfter")) {
            due.put(dueKey(order),String.valueOf(order.get("orderId")));
        }
        if (order.containsKey("idempotencyKey")) {
            byIdempotency.put(idempotencyKey(customerId,String.valueOf(order.get("idempotencyKey"))),String.valueOf(order.get("orderId")));
        }
    }

    @Override public boolean insert(Map<String,Object> order) {
        gate.writeLock().lock();
        try {
            String customerId=String.valueOf(order.get("customerId"));
            String key=idempotencyKey(customerId,String.valueOf(order.get("idempotencyKey")));
            String id=String.valueOf(order.get("orderId"));
            if (byIdempotency.containsKey(key) || records.containsKey(id)) return false;
            Map<String,Object> stored=new HashMap<>(order);
            records.put(id,stored);
            addIndexes(stored);
            return true;
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override public Optional<Map<String,Object>> findById(String orderId) {
        gate.readLock().lock();
        try {
            return Optional.ofNullable(records.get(orderId)).map(HashMap::new);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override public Optional<Map<String,Object>> findByIdempotency(String customerId,String key) {
        gate.readLock().lock();
        try {
            String id=byIdempotency.get(idempotencyKey(customerId,key));
            return id==null ? Optional.empty() : Optional.ofNullable(records.get(id)).map(HashMap::new);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override public Optional<Map<String,Object>> compareAndSet(String orderId,String expectedStatus,int expectedVersion,Map<String,Object> replacement) {
        gate.writeLock().lock();
        try {
            Map<String,Object> current=records.get(orderId);
            if (current==null || !Objects.equals(expectedStatus,current.get("status")) ||
                expectedVersion!=((Number)current.get("version")).intValue()) return Optional.empty();
            removeIndexes(current);
            Map<String,Object> stored=new HashMap<>(replacement);
            records.put(orderId,stored);
            addIndexes(stored);
            return Optional.of(new HashMap<>(stored));
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override public List<Map<String,Object>> findPage(String customerId,String status,Long createdBeforeMillis,String orderIdBefore,int limit) {
        gate.readLock().lock();
        try {
            NavigableSet<Map<String,Object>> index;
            if (customerId!=null && status!=null) index=byCustomerStatus.get(indexKey(customerId,status));
            else if (customerId!=null) index=byCustomer.get(customerId);
            else if (status!=null) index=byStatus.get(status);
            else index=all;
            if (index==null || limit<=0) return List.of();

            Iterator<Map<String,Object>> walk;
            if (createdBeforeMillis==null) {
                walk=index.descendingIterator();
            } else {
                Map<String,Object> anchor=Map.of("createdAtEpochMillis",createdBeforeMillis,"orderId",orderIdBefore);
                walk=index.headSet(anchor,false).descendingIterator();
            }
            List<Map<String,Object>> page=new ArrayList<>(limit);
            while (walk.hasNext() && page.size()<limit) page.add(new HashMap<>(walk.next()));
            return page;
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override public List<Map<String,Object>> findDue(long nowMillis,int limit) {
        if (limit<=0) return List.of();
        gate.readLock().lock();
        try {
            List<Map<String,Object>> dueOrders=new ArrayList<>(Math.max(0,limit));
            for (Map.Entry<DueKey,String> entry:due.entrySet()) {
                if (entry.getKey().dueAtMillis()>nowMillis) break;
                Map<String,Object> order=records.get(entry.getValue());
                if (order!=null) dueOrders.add(new HashMap<>(order));
                if (dueOrders.size()>=limit) break;
            }
            return dueOrders;
        } finally {
            gate.readLock().unlock();
        }
    }
}
