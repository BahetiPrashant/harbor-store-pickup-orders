package io.orderflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

/** Durable single-node adapter used by the production profile. */
@Repository
@Profile("prod")
public class JdbcOrderRepository implements OrderRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOrderRepository(JdbcTemplate jdbc,ObjectMapper json) {
        this.jdbc=jdbc;
        this.json=json;
    }

    @Override public boolean insert(Map<String,Object> order) {
        try {
            jdbc.update("""
                INSERT INTO orders(order_id,customer_id,status,created_at_epoch_ms,process_after_epoch_ms,
                                  idempotency_key,request_hash,version,order_json)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,
                order.get("orderId"),order.get("customerId"),order.get("status"),
                order.get("createdAtEpochMillis"),processAfter(order),order.get("idempotencyKey"),
                order.get("requestHash"),order.get("version"),encode(order));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override public Optional<Map<String,Object>> findById(String orderId) {
        return findOne("SELECT order_json FROM orders WHERE order_id=?",orderId);
    }

    @Override public Optional<Map<String,Object>> findByIdempotency(String customerId,String key) {
        return findOne("SELECT order_json FROM orders WHERE customer_id=? AND idempotency_key=?",customerId,key);
    }

    @Override public Optional<Map<String,Object>> compareAndSet(String orderId,String expectedStatus,int expectedVersion,Map<String,Object> replacement) {
        int updated=jdbc.update("""
            UPDATE orders
               SET status=?, version=?, process_after_epoch_ms=?, order_json=?
             WHERE order_id=? AND status=? AND version=?
            """,
            replacement.get("status"),replacement.get("version"),processAfter(replacement),encode(replacement),
            orderId,expectedStatus,expectedVersion);
        return updated==1 ? Optional.of(new HashMap<>(replacement)) : Optional.empty();
    }

    @Override public List<Map<String,Object>> findPage(String customerId,String status,Long createdBeforeMillis,String orderIdBefore,int limit) {
        if(limit<=0)return List.of();
        StringBuilder sql=new StringBuilder("SELECT order_json FROM orders WHERE 1=1");
        List<Object> parameters=new ArrayList<>();
        if(customerId!=null){sql.append(" AND customer_id=?");parameters.add(customerId);}
        if(status!=null){sql.append(" AND status=?");parameters.add(status);}
        if(createdBeforeMillis!=null){
            sql.append(" AND (created_at_epoch_ms < ? OR (created_at_epoch_ms = ? AND order_id < ?))");
            parameters.add(createdBeforeMillis);parameters.add(createdBeforeMillis);parameters.add(orderIdBefore);
        }
        sql.append(" ORDER BY created_at_epoch_ms DESC,order_id DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(sql.toString(),(row,index)->decode(row.getString("order_json")),parameters.toArray());
    }

    @Override public List<Map<String,Object>> findDue(long nowMillis,int limit) {
        if(limit<=0)return List.of();
        return jdbc.query("""
            SELECT order_json FROM orders
             WHERE status='PENDING' AND process_after_epoch_ms<=?
             ORDER BY process_after_epoch_ms,order_id LIMIT ?
            """,(row,index)->decode(row.getString("order_json")),nowMillis,limit);
    }

    private Optional<Map<String,Object>> findOne(String sql,Object... parameters) {
        List<Map<String,Object>> found=jdbc.query(sql,(row,index)->decode(row.getString("order_json")),parameters);
        return found.stream().findFirst();
    }

    private Long processAfter(Map<String,Object> order) {
        Object value=order.get("processAfter");
        return value==null ? null : Instant.parse(String.valueOf(value)).toEpochMilli();
    }

    private String encode(Map<String,Object> order) {
        try{return json.writeValueAsString(order);}
        catch(Exception ex){throw new IllegalStateException("Could not serialize order",ex);}
    }

    private Map<String,Object> decode(String value) {
        try{return json.readValue(value,new TypeReference<>(){});}
        catch(Exception ex){throw new IllegalStateException("Stored order JSON is invalid",ex);}
    }
}
