CREATE TABLE IF NOT EXISTS orders (
    order_id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    process_after_epoch_ms BIGINT,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    order_json CLOB NOT NULL,
    CONSTRAINT uq_orders_customer_idempotency UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_orders_due ON orders(status, process_after_epoch_ms, order_id);
CREATE INDEX IF NOT EXISTS idx_orders_customer_status_created ON orders(customer_id, status, created_at_epoch_ms DESC, order_id DESC);
CREATE INDEX IF NOT EXISTS idx_orders_status_created ON orders(status, created_at_epoch_ms DESC, order_id DESC);
CREATE INDEX IF NOT EXISTS idx_orders_created ON orders(created_at_epoch_ms DESC, order_id DESC);
