import re


class FixtureModel:
    def suggest(self, text: str, candidates: list[dict]) -> list[dict]:
        lowered = text.casefold()
        quantity_match = re.search(r"\b(\d+)\b", lowered)
        if quantity_match:
            quantity = int(quantity_match.group(1))
        else:
            words = {"one": 1, "two": 2, "three": 3}
            quantity = next((n for word, n in words.items() if re.search(rf"\b{word}\b", lowered)), 1)
        lines = []
        for product in candidates:
            phrases = [product["sku"], product["name"], *product.get("tags", [])]
            if any(p.casefold() in lowered for p in phrases):
                lines.append({"sku": product["sku"], "quantity": quantity, "confidence": 0.9})
        if re.search(r"\bfree\b|sku-free", lowered):
            lines.append({"sku": "SKU-FREE", "quantity": 1, "confidence": 0.99})
        return lines
