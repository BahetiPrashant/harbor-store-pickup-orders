"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

type Order = { orderId: string; status: string; items: { productName: string; quantity: number }[]; totalMinor: number; createdAt: string };
const labels: Record<string, string> = { PENDING: "Pending", PROCESSING: "Preparing", SHIPPED: "Ready", DELIVERED: "Collected", CANCELLED: "Cancelled" };
const money = (n: number) => `$${(n / 100).toFixed(2)}`;
function spokenTime(value: string) {
  const date = new Date(value), now = new Date(), minutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} ${minutes === 1 ? "minute" : "minutes"} ago`;
  const time = date.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  if (date.toDateString() === now.toDateString()) return `Today at ${time}`;
  const yesterday = new Date(now); yesterday.setDate(now.getDate() - 1);
  if (date.toDateString() === yesterday.toDateString()) return `Yesterday at ${time}`;
  return `${date.toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" })} at ${time}`;
}

export default function Orders() {
  const [items, setItems] = useState<Order[] | null>(null), [filter, setFilter] = useState(""), [err, setErr] = useState(""), [next, setNext] = useState("");
  const router = useRouter();
  useEffect(() => {
    setItems(null); setErr(""); setNext("");
    fetch(`/api/orders${filter ? `?status=${filter}` : ""}`).then(async r => { const d = await r.json(); if (!r.ok) throw Error(d.message); setItems(d.items ?? []); setNext(d.nextCursor ?? ""); }).catch(e => setErr(e.message));
  }, [filter]);
  async function more() {
    if (!next) return;
    try { const query = new URLSearchParams({ cursor: next }); if (filter) query.set("status", filter); const r = await fetch(`/api/orders?${query}`), d = await r.json(); if (!r.ok) throw Error(d.message); setItems(old => [...(old ?? []), ...(d.items ?? [])]); setNext(d.nextCursor ?? ""); }
    catch (e) { setErr(e instanceof Error ? e.message : "Could not reach Harbor"); }
  }
  async function logout() { await fetch("/api/auth/logout", { method: "POST" }); router.push("/login"); }
  return <main className="shell">
    <header className="topbar"><Link className="brand" href="/orders">Harbor <span>orders</span></Link><nav><Link href="/orders">Orders</Link><Link href="/orders/new">Catalog</Link><button className="btn" onClick={logout}>Sign out</button></nav></header>
    <div className="heading-row"><div><div className="eyebrow">Store pickup</div><h1>Orders</h1></div><Link className="btn primary" href="/orders/new">Browse catalog</Link></div>
    <div className="pills">{[["", "All"], ["PENDING", "Pending"], ["PROCESSING", "Preparing"], ["SHIPPED", "Ready"], ["DELIVERED", "Collected"], ["CANCELLED", "Cancelled"]].map(([value, label]) => <button className={filter === value ? "pill selected" : "pill"} key={value} onClick={() => setFilter(value)}>{label}</button>)}</div>
    {err && <div className="notice" role="alert">{err}</div>}
    {items === null ? <div className="skeletons"><i/><i/><i/></div> : items.length === 0 ? <section className="empty panel"><span className="eyebrow">A fresh start</span><h2>No orders yet</h2><p>Your next store pickup order will show up here.</p><Link className="btn primary" href="/orders/new">Browse catalog</Link></section> : <><div className="order-list">{items.map((o, i) => <Link className="order-card panel" href={`/orders/${o.orderId}`} key={o.orderId} style={{ animationDelay: `${Math.min(i, 8) * 30}ms` }}><div className="row"><span className={`badge ${o.status.toLowerCase()}`}><i/>{labels[o.status] || "Order"}</span><time>{spokenTime(o.createdAt)}</time></div><div className="item-summary">{o.items.map(x => `${x.quantity} × ${x.productName}`).join(", ")}</div><strong>{money(o.totalMinor)}</strong></Link>)}</div>{next && <div className="load-more"><button className="btn" onClick={more}>Load more orders</button></div>}</>}
  </main>;
}
