import re


class CatalogIndex:
    def __init__(self, products: list[dict]):
        self.products = tuple(p for p in products if p.get("active") is True)
        self.by_sku = {p["sku"]: p for p in self.products}

    @staticmethod
    def tokens(product: dict) -> set[str]:
        words = " ".join([product.get("sku", ""), product.get("name", ""), product.get("description", ""), *product.get("tags", [])]).casefold()
        return set(re.findall(r"[a-z0-9]+", words))

    def candidates(self, text: str) -> list[dict]:
        query = re.findall(r"[a-z0-9]+", text.casefold())
        scored = [(sum(any(token.startswith(word) or word in token for token in self.tokens(p)) for word in query), p) for p in self.products]
        return [p for score, p in sorted(scored, key=lambda row: -row[0]) if score][:8]


def ground(lines: list[dict], catalog: CatalogIndex, text: str) -> dict:
    merged: dict[str, dict] = {}
    unresolved = []
    for line in lines:
        sku = str(line.get("sku", ""))
        product = catalog.by_sku.get(sku)
        quantity = line.get("quantity", 1)
        if not product or not isinstance(quantity, int) or isinstance(quantity, bool) or quantity < 1 or quantity > 99:
            unresolved.append(sku or "That item")
            continue
        existing = merged.get(sku)
        total = min(99, quantity + (existing["quantity"] if existing else 0))
        merged[sku] = {"sku": sku, "productName": product["name"], "quantity": total, "unitPriceMinor": product["unitPriceMinor"], "confidence": float(line.get("confidence", .5))}
    if not merged and not unresolved:
        unresolved.append(text)
    return {"lines": list(merged.values()), "unresolved": unresolved, "warnings": []}
