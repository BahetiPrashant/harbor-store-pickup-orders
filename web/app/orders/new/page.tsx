"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { SALE_ENDS, wasPrice } from "@/lib/sale";

type Product = { sku: string; name: string; description: string; unitPriceMinor: number; tags: string[] };
type Line = { sku: string; quantity: number };
type Suggestion = { sku: string; productName: string; quantity: number; unitPriceMinor: number; confidence: number };
type Notice = { message: string; hint: string };

const families = ["All", "Cables", "Desk", "Power", "Storage", "Carry"];
const imageNames: Record<string, string> = {
  "HDMI-1M": "hdmi_1m", "HDMI-2M": "hdmi_2m", "KEY-1": "keyboard", "MOUSE-1": "mouse_1",
  "USB-C": "usb_c", "HUB-4": "hub_4", "SSD-1": "ssd_1", "CHG-65": "charger", "CASE-1": "case_1",
};
const artNames: Record<string, string> = {
  "HDMI-1M": "plug", "HDMI-2M": "plug", "KEY-1": "keys", "MOUSE-1": "mouse", "USB-C": "cord",
  "HUB-4": "hub", "SSD-1": "drive", "CHG-65": "charger", "CASE-1": "sleeve",
};
const money = (minor: number) => `$${(minor / 100).toFixed(2)}`;

function familyOf(product: Product) {
  if (product.tags.some(tag => ["hdmi", "cable", "usb"].includes(tag))) return "Cables";
  if (product.tags.some(tag => ["keyboard", "mouse", "hub"].includes(tag))) return "Desk";
  if (product.tags.includes("charger")) return "Power";
  if (product.tags.some(tag => ["ssd", "drive"].includes(tag))) return "Storage";
  return "Carry";
}

function Photo({ sku, className = "" }: { sku: string; className?: string }) {
  return <div className={`product-photo ${className}`} aria-hidden="true">
    <img src={`/products/${imageNames[sku] ?? "hdmi_1m"}.webp`} alt="" onError={event => { event.currentTarget.style.display = "none"; }} />
    <span className={`product-art ${artNames[sku] ?? "plug"}`} />
  </div>;
}

export default function Catalog() {
  const [products, setProducts] = useState<Product[]>([]);
  const [cart, setCart] = useState<Line[] | null>(null);
  const [family, setFamily] = useState("All");
  const [search, setSearch] = useState("");
  const [agreed, setAgreed] = useState(false);
  const [description, setDescription] = useState("");
  const [suggestions, setSuggestions] = useState<Suggestion[] | null>(null);
  const [unresolved, setUnresolved] = useState<string[]>([]);
  const [suggesting, setSuggesting] = useState(false);
  const [suggestionError, setSuggestionError] = useState<Notice | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [selected, setSelected] = useState<Product | null>(null);
  const [success, setSuccess] = useState("");
  const [now, setNow] = useState(Date.now());
  const placeKey = useRef("");
  const router = useRouter();

  useEffect(() => {
    fetch("/api/catalog").then(async response => {
      const data = await response.json();
      if (!response.ok) throw new Error(data.message ?? "The catalog could not be loaded.");
      setProducts(data);
    }).catch(() => setNotice({ message: "Could not load the catalog", hint: "Refresh this page in a moment. Your cart stays in this browser." }));
    const saved = sessionStorage.getItem("harbor-cart");
    setCart(saved ? JSON.parse(saved) : []);
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (cart !== null) sessionStorage.setItem("harbor-cart", JSON.stringify(cart));
  }, [cart]);

  const filtered = useMemo(() => products.filter(product =>
    (family === "All" || familyOf(product) === family) &&
    `${product.name} ${product.description}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())
  ), [products, family, search]);
  const quantity = (sku: string) => cart?.find(line => line.sku === sku)?.quantity ?? 0;
  const itemCount = (cart ?? []).reduce((sum, line) => sum + line.quantity, 0);
  const total = (cart ?? []).reduce((sum, line) => sum + (products.find(product => product.sku === line.sku)?.unitPriceMinor ?? 0) * line.quantity, 0);
  const activeSale = now <= SALE_ENDS.getTime();
  const remaining = Math.max(0, SALE_ENDS.getTime() - now);

  function setQuantity(sku: string, next: number) {
    setCart(current => {
      const lines = (current ?? []).filter(line => line.sku !== sku);
      if (next > 0) lines.push({ sku, quantity: Math.max(1, Math.min(99, next)) });
      return lines;
    });
  }

  async function getSuggestions() {
    if (!description.trim() || suggesting) return;
    setSuggesting(true);
    setSuggestions(null);
    setUnresolved([]);
    setSuggestionError(null);
    try {
      const response = await fetch("/api/drafts", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: description.trim() }),
      });
      const data = await response.json();
      if (!response.ok) {
        const error = { message: data.message ?? "Suggestions are unavailable.", hint: data.hint ?? "You can still add items from the catalog." };
        setSuggestionError(error);
        setNotice(error);
        return;
      }
      setSuggestions(data.lines ?? []);
      setUnresolved(data.unresolved ?? []);
    } catch {
      const error = { message: "Could not reach Harbor", hint: "Try again in a moment. Your cart has not changed." };
      setSuggestionError(error);
      setNotice(error);
    } finally {
      setSuggesting(false);
    }
  }

  function addSuggestion(line: Suggestion) {
    const current = quantity(line.sku);
    if (current >= 99) {
      setNotice({ message: "You already have the maximum quantity", hint: "Each product can be added up to 99 times. Adjust the quantity in your cart if needed." });
      return;
    }
    setQuantity(line.sku, Math.min(99, current + line.quantity));
  }

  async function placeOrder() {
    if (!agreed || !cart?.length) return;
    const key = placeKey.current || crypto.randomUUID().replaceAll("-", "");
    placeKey.current = key;
    try {
      const response = await fetch("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body: JSON.stringify({ items: cart }),
      });
      const data = await response.json();
      if (!response.ok) {
        if (response.status === 409 && data.code === "IDEMPOTENCY_PAYLOAD_MISMATCH") {
          placeKey.current = crypto.randomUUID().replaceAll("-", "");
          return placeOrder();
        }
        throw new Error(data.message ?? "Your order could not be placed.");
      }
      setSuccess(data.orderId);
      placeKey.current = "";
      setCart([]);
    } catch (error) {
      setNotice({ message: error instanceof Error ? error.message : "Could not reach Harbor", hint: "Your cart is still here. Try again in a moment." });
    }
  }

  async function signOut() {
    await fetch("/api/auth/logout", { method: "POST" });
    router.push("/login");
  }

  return <main className="shell">
    <header className="topbar">
      <Link className="brand" href="/orders">Harbor <span>orders</span></Link>
      <nav><Link href="/orders">Orders</Link><Link href="/orders/new">Catalog</Link><button className="btn" onClick={signOut}>Sign out</button></nav>
    </header>

    {success ? <section className="success panel">
      <span className="check" aria-hidden="true">✓</span><h1>Your order is in.</h1>
      <p>We’ll set it aside for store pickup. Pay cash at the desk.</p>
      <Link className="btn primary" href={`/orders/${success}`}>View your order</Link>
    </section> : <>
      <div className="catalog-heading"><div><div className="eyebrow">The Harbor shop</div><h1>Make room for <span>good work.</span></h1><p>Choose what you need. Pay cash when you collect it.</p></div></div>
      {activeSale && <section className="sale-banner"><div><small>HARBOR DAYS</small><h2>A little less for your desk.</h2><p>Offer ends Friday, November 27.</p></div><div className="timer" aria-label="Time left in sale">
        <span><b>{Math.floor(remaining / 86400000)}</b><small>days</small></span><span><b>{Math.floor(remaining / 3600000) % 24}</b><small>hours</small></span><span><b>{Math.floor(remaining / 60000) % 60}</b><small>minutes</small></span><span><b>{Math.floor(remaining / 1000) % 60}</b><small>seconds</small></span>
      </div></section>}

      <div className="shop-layout">
        <section className="shop-main" aria-label="Product catalog">
          <label className="search-field"><span className="sr-only">Search products</span><span className="search-icon" aria-hidden="true">⌕</span><input placeholder="Search the catalog" value={search} onChange={event => setSearch(event.target.value)} />{search && <button className="search-clear" onClick={() => setSearch("")} aria-label="Clear search">×</button>}</label>
          <div className="pills" aria-label="Product families">{families.map(value => <button key={value} className={family === value ? "pill selected" : "pill"} aria-pressed={family === value} onClick={() => setFamily(value)}>{value}</button>)}</div>
          <p className="result-count" aria-live="polite">{search || family !== "All" ? `${filtered.length} ${filtered.length === 1 ? "item" : "items"} found` : "Choose from the catalog"}</p>
          {filtered.length ? <div className="product-grid">{filtered.map((product, index) => {
            const count = quantity(product.sku);
            const sale = wasPrice(product.sku, now);
            return <article className="product-card panel" key={product.sku} style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}>
              <button className="photo-button" onClick={() => setSelected(product)} aria-label={`View ${product.name}`}><Photo sku={product.sku} /></button>
              <div className="product-copy">
                <button className="text-link" onClick={() => setSelected(product)}>{product.name}</button>
                <p>{product.description.split(".")[0]}.</p>
                <div className="price-row">{sale !== null && <del>{money(sale)}</del>}<strong>{money(product.unitPriceMinor)}</strong>{sale !== null && <small>Sale</small>}</div>
                <div className="stepper product-stepper" aria-label={`${product.name} quantity`}>
                  {count === 0 ? <button className="add-to-cart" onClick={() => setQuantity(product.sku, 1)}><span aria-hidden="true">+</span> Add to cart</button> : <>
                    <button aria-label={`Remove one ${product.name}`} onClick={() => setQuantity(product.sku, count - 1)}>−</button><output aria-live="polite">{count}</output><button aria-label={`Add one ${product.name}`} onClick={() => setQuantity(product.sku, count + 1)}>+</button>
                  </>}
                </div>
              </div>
            </article>;
          })}</div> : <section className="empty-results panel"><span className="eyebrow">No matches yet</span><h2>Try another search</h2><p>Check the spelling or clear your filters to see the full catalog.</p><button className="btn" onClick={() => { setSearch(""); setFamily("All"); }}>Clear search and filters</button></section>}
        </section>

        <aside className="cart panel" aria-label="Your cart">
          <div className="cart-header"><div><div className="eyebrow">Store pickup · cash at desk</div><h2>Your cart</h2></div><span className="cart-count">{itemCount} {itemCount === 1 ? "item" : "items"}</span></div>
          {!cart?.length ? <div className="cart-empty"><div className="cart-empty-mark" aria-hidden="true">＋</div><b>Your cart is empty</b><p>Add items here when you find what you need.</p></div> : <div className="cart-lines">{cart.map(line => {
            const product = products.find(item => item.sku === line.sku);
            if (!product) return null;
            return <div className="cart-line" key={line.sku}>
              <Photo sku={line.sku} className="thumb" />
              <div className="cart-line-copy"><b>{product.name}</b><small>{money(product.unitPriceMinor)} each</small></div>
              <div className="stepper compact" aria-label={`${product.name} in cart`}><button aria-label={`Remove one ${product.name}`} onClick={() => setQuantity(line.sku, line.quantity - 1)}>−</button><output>{line.quantity}</output><button aria-label={`Add one ${product.name}`} onClick={() => setQuantity(line.sku, line.quantity + 1)}>+</button></div>
            </div>;
          })}</div>}

          <div className="total-row"><span>Cash total</span><strong>{money(total)}</strong></div>
          <div className="pickup-note"><b>Store pickup</b><span>No address</span><b>Cash</b><span>Pay at the desk</span></div>
          <label className="agree"><input type="checkbox" checked={agreed} onChange={event => setAgreed(event.target.checked)} /><span>I will collect this in store and pay cash.</span></label>
          <button className="btn primary full place-order" disabled={!agreed || !cart?.length} onClick={placeOrder}>Place order</button>

          <details className="terms"><summary>Pickup, cash, and returns</summary><ol>
            <li>Collect it at the Harbor desk. No delivery, no address.</li><li>Pay when you collect. Nothing is charged before you arrive.</li><li>Cancel in the app only while the order is still pending.</li><li>Look at the items before you pay. Do not pay for anything that is wrong.</li><li>Bring unused items and this order back to the desk for a cash refund.</li>
          </ol></details>

          <details className="describe" open>
            <summary>Describe what you need</summary>
            <p className="suggestion-help">We’ll suggest catalog items. Nothing is added until you choose.</p>
            <label className="sr-only" htmlFor="order-description">Describe the items you need</label>
            <textarea id="order-description" value={description} maxLength={500} onChange={event => { setDescription(event.target.value); setSuggestions(null); setSuggestionError(null); }} placeholder="For example, 2 HDMI cables and one keyboard" />
            <div className="describe-actions"><span>{description.length}/500</span><button className="btn suggest-btn" disabled={!description.trim() || suggesting} onClick={getSuggestions}>{suggesting ? "Finding items…" : suggestions ? "Refresh suggestions" : "Suggest items"}</button></div>
            {suggestionError && <p className="inline-error" role="alert">{suggestionError.message}</p>}
            {suggestions !== null && <div className="suggestion-results" aria-live="polite">
              <div className="suggestion-results-heading"><b>{suggestions.length ? "Suggested for you" : "No close matches"}</b><span>{suggestions.length ? `${suggestions.length} ${suggestions.length === 1 ? "item" : "items"}` : "Try another description"}</span></div>
              {suggestions.map(line => {
                const inCart = quantity(line.sku) > 0;
                return <div className="suggestion-line" key={line.sku}>
                  <Photo sku={line.sku} className="suggestion-thumb" />
                  <div className="suggestion-copy"><b>{line.productName}</b><span>{line.quantity} × {money(line.unitPriceMinor)}</span></div>
                  <button className={inCart ? "btn added-btn" : "btn add-suggestion-btn"} disabled={inCart} onClick={() => addSuggestion(line)}>{inCart ? "In cart ✓" : "Add"}</button>
                </div>;
              })}
              {unresolved.length > 0 && <p className="unresolved-note">Couldn’t match: {unresolved.join(", ")}. You can search the catalog for these items.</p>}
              {suggestions.length === 0 && <button className="btn" onClick={() => { setSearch(""); setFamily("All"); }}>Browse the full catalog</button>}
            </div>}
          </details>
        </aside>
      </div>
    </>}

    {selected && <div className="overlay" onClick={() => setSelected(null)}><section className="sheet panel" role="dialog" aria-modal="true" aria-labelledby="product-sheet-title" onClick={event => event.stopPropagation()}>
      <button className="close" onClick={() => setSelected(null)}>Close</button><Photo sku={selected.sku} className="large-photo" /><h2 id="product-sheet-title">{selected.name}</h2><p>{selected.description}</p><strong>{money(selected.unitPriceMinor)}</strong><button className="btn primary" onClick={() => { setQuantity(selected.sku, quantity(selected.sku) + 1); setSelected(null); }}>Add to cart</button>
    </section></div>}
    {notice && <div className="overlay"><section className="notice panel" role="alertdialog" aria-modal="true" aria-labelledby="notice-title"><h2 id="notice-title">Harbor needs a moment</h2><p>{notice.message}</p><aside>{notice.hint}</aside><button className="btn primary" onClick={() => setNotice(null)}>Close</button></section></div>}
  </main>;
}
