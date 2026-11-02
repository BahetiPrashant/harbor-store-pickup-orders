"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { wasPrice } from "@/lib/sale";

type Order = {
  orderId: string;
  status: string;
  customerName: string;
  createdAt: string;
  totalMinor: number;
  items: { sku: string; productName: string; quantity: number; unitPriceMinor: number; lineTotalMinor: number }[];
  history: { from: string; to: string; at: string; actorRole: string }[];
  cancelReason?: string;
};

const labels: Record<string, string> = { PENDING: "Pending", PROCESSING: "Preparing", SHIPPED: "Ready for pickup", DELIVERED: "Collected", CANCELLED: "Cancelled" };
const imageNames: Record<string, string> = {
  "HDMI-1M": "hdmi_1m", "HDMI-2M": "hdmi_2m", "KEY-1": "keyboard", "MOUSE-1": "mouse_1",
  "USB-C": "usb_c", "HUB-4": "hub_4", "SSD-1": "ssd_1", "CHG-65": "charger", "CASE-1": "case_1",
};
const money = (n: number) => `$${(n / 100).toFixed(2)}`;
const dateTime = (value: string) => new Date(value).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });

export default function OrderPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [role, setRole] = useState("CUSTOMER");
  const [notice, setNotice] = useState("");
  const router = useRouter();

  async function load() {
    const [response, sessionResponse] = await Promise.all([fetch(`/api/orders/${orderId}`), fetch("/api/session")]);
    const data = await response.json();
    if (!response.ok) { setNotice(data.message); return; }
    setOrder(data);
    const session = await sessionResponse.json();
    setRole(session.role);
  }

  useEffect(() => { load(); }, [orderId]);

  async function action(path: string, method: string, body?: unknown) {
    const response = await fetch(path, { method, headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    const data = await response.json();
    if (!response.ok) { setNotice(`${data.message} ${data.hint ?? ""}`); return; }
    setNotice("");
    load();
  }

  const steps = ["PENDING", "PROCESSING", "SHIPPED", "DELIVERED"];
  if (!order) return <main className="shell"><header className="topbar"><Link className="brand" href="/orders">Harbor <span>orders</span></Link><nav><Link href="/orders">Orders</Link><Link href="/orders/new">Catalog</Link></nav></header>{notice ? <div className="notice panel" role="alert">{notice}<p><Link href="/orders">Back to orders</Link></p></div> : <div className="skeletons"><i /></div>}</main>;

  const statusIndex = steps.indexOf(order.status);
  const savingsMinor = order.items.reduce((sum, item) => {
    const was = wasPrice(item.sku);
    return sum + (was !== null ? Math.max(0, was - item.unitPriceMinor) * item.quantity : 0);
  }, 0);
  const orderMessage = order.status === "PENDING" ? "Your order is waiting to be prepared. You can still cancel it." :
    order.status === "PROCESSING" ? "The Harbor desk is setting these items aside for you." :
    order.status === "SHIPPED" ? "Your order is ready. Show the receipt at the desk, check your items, then pay cash." :
    order.status === "DELIVERED" ? "Your order has been collected and paid for. Returns go back to the same desk." :
    "This order was cancelled. Nothing is due.";

  return <main className="shell order-page">
    <header className="topbar"><Link className="brand" href="/orders">Harbor <span>orders</span></Link><nav><Link href="/orders">Orders</Link><Link href="/orders/new">Catalog</Link><button className="btn" onClick={async () => { await fetch("/api/auth/logout", { method: "POST" }); router.push("/login"); }}>Sign out</button></nav></header>

    <div className="order-page-heading"><div><div className="eyebrow">Store pickup order</div><h1>Order details</h1><p className="muted">Placed {dateTime(order.createdAt)}</p></div><span className={`badge ${order.status.toLowerCase()}`}><i />{labels[order.status] ?? "Order"}</span></div>

    <section className="order-detail panel" aria-label="Order status and details">
      {order.status !== "CANCELLED" && <div className="track">{steps.map((step, index) => <div className={index <= statusIndex ? "track-step reached" : "track-step"} key={step}><i />{labels[step]}</div>)}</div>}
      <div className="pickup-card"><div className="eyebrow">{order.status === "SHIPPED" ? "Ready at the desk" : order.status === "DELIVERED" ? "Pickup complete" : order.status === "CANCELLED" ? "Order update" : "What happens next"}</div><h2>{labels[order.status]}</h2><p>{orderMessage}</p></div>
      <section className="order-updates"><h2>Order updates</h2><ul className="history">{order.history.map((entry, index) => <li key={`${entry.at}-${index}`}><b>{entry.to === "CANCELLED" ? "Order cancelled" : entry.to === "PROCESSING" ? "Preparation started" : entry.to === "SHIPPED" ? "Ready for pickup" : entry.to === "DELIVERED" ? "Collected" : "Order placed"}</b><span>{dateTime(entry.at)} · {entry.to === "CANCELLED" ? "Cancelled before preparation began." : entry.to === "PROCESSING" ? "The desk started setting aside your items." : entry.to === "SHIPPED" ? "Bring this receipt to the desk and pay cash." : entry.to === "DELIVERED" ? "Pickup completed at the desk." : "Your order is in the queue."}</span></li>)}</ul></section>
      <details className="order-terms"><summary>Pickup, cash, and returns</summary><p>Collect your items at the Harbor desk. Pay cash when you collect. Check every item before paying. If something is wrong, or you change your mind, bring unused items and this receipt back to the desk for a cash refund.</p></details>
      {notice && <p className="inline-error" role="alert">{notice}</p>}
      <div className="order-actions">
        {role === "OPERATOR" && statusIndex >= 0 && statusIndex < 3 && <button className="btn primary" onClick={() => action(`/api/orders/${orderId}/status`, "PATCH", { status: steps[statusIndex + 1] })}>Mark {labels[steps[statusIndex + 1]].toLowerCase()}</button>}
        {role === "CUSTOMER" && order.status === "PENDING" && <button className="btn danger" onClick={() => action(`/api/orders/${orderId}/cancel`, "POST", { reason: "Changed my mind" })}>Cancel order</button>}
      </div>
    </section>

    <section className="receipt-section" aria-labelledby="receipt-section-title">
      <div className="receipt-section-heading"><div className="eyebrow">Keep this for pickup</div><h2 id="receipt-section-title">Your receipt</h2><p className="muted">Show this at the counter. You can print or save a copy.</p></div>
      <div className="receipt-wrap">
        <article id="harbor-receipt" className="receipt-slip">
          <header className="receipt-brand"><div className="receipt-wordmark">Harbor</div><p>Store pickup receipt</p><b>{order.status === "CANCELLED" ? "Order cancelled" : "Pay cash at the desk"}</b></header>
          <div className="receipt-meta"><time>{dateTime(order.createdAt)}</time><strong>{order.customerName}</strong><span>{order.orderId}</span></div>
          <div className="receipt-items">{order.items.map(item => {
            const was = wasPrice(item.sku);
            return <div className="receipt-item" key={item.sku}>
              <img className="receipt-product-photo" src={`/products/${imageNames[item.sku] ?? "hdmi_1m"}.webp`} alt="" />
              <div className="receipt-product-copy"><b>{item.quantity} × {item.productName}</b><span>{was !== null && <del>{money(was)}</del>} {money(item.unitPriceMinor)} each</span></div>
              <strong>{money(item.lineTotalMinor)}</strong>
            </div>;
          })}</div>
          {savingsMinor > 0 && <div className="receipt-savings"><span>Harbor Days savings</span><strong>{money(savingsMinor)}</strong></div>}
          <div className="receipt-total"><span>{order.status === "CANCELLED" || order.status === "DELIVERED" ? "Cash paid" : "Cash due"}</span><strong>{money(order.totalMinor)}</strong></div>
          <p className="receipt-footer">{order.status === "CANCELLED" ? "This order has been cancelled. Nothing is due." : order.status === "DELIVERED" ? "Thank you for shopping at Harbor. Bring unused items and this receipt to the counter for a cash refund." : "Show this receipt at the counter. Check the items, then pay cash. Unused items can be returned here for a cash refund."}</p>
        </article>
        <div className="receipt-actions"><button className="btn primary" onClick={() => window.print()}>Print receipt</button></div>
      </div>
    </section>
  </main>;
}
