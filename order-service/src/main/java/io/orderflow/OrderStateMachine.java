package io.orderflow;

import java.util.Map;

/** The single authority for legal order state changes. */
public final class OrderStateMachine {
    private static final Map<String,String> NEXT = Map.of("PENDING","PROCESSING","PROCESSING","SHIPPED","SHIPPED","DELIVERED");
    private OrderStateMachine() {}
    public static String forward(String current) {
        String next=NEXT.get(current);
        if(next==null) throw new IllegalArgumentException("INVALID_STATUS_TRANSITION");
        return next;
    }
    public static void assertCancellable(String current) {
        if(!"PENDING".equals(current)) throw new IllegalArgumentException("CANCEL_NOT_ALLOWED");
    }
}
