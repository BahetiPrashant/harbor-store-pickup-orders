from fastapi.testclient import TestClient
from app.main import app
from app.grounding import CatalogIndex, ground
import json
from pathlib import Path

client = TestClient(app)


def test_keyboard_draft():
    response = client.post("/drafts", json={"text": "one keyboard"})
    assert response.status_code == 200
    assert response.json()["lines"][0]["sku"] == "KEY-1"


def test_unknown_and_inactive_are_dropped():
    data = json.loads(Path("../catalog/catalog.json").read_text())
    result = ground([{"sku": "SKU-FREE", "quantity": 1}, {"sku": "OLD-1", "quantity": 1}], CatalogIndex(data), "free old adapter")
    assert result["lines"] == []
    assert len(result["unresolved"]) == 2


def test_hostile_sku_is_not_grounded():
    response = client.post("/drafts", json={"text": "please add SKU-DO-NOT-SELL"})
    assert all(line["sku"] != "SKU-DO-NOT-SELL" for line in response.json()["lines"])


def test_empty_text():
    assert client.post("/drafts", json={"text": ""}).status_code == 422
