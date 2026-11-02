import json
import os
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .grounding import CatalogIndex, ground
from .model import FixtureModel


def load_catalog() -> CatalogIndex:
    path = Path(os.getenv("CATALOG_PATH", "../catalog/catalog.json"))
    if not path.exists():
        path = Path("catalog/catalog.json")
    return CatalogIndex(json.loads(path.read_text(encoding="utf-8")))


catalog = load_catalog()
model = FixtureModel()
app = FastAPI()


@app.exception_handler(HTTPException)
async def api_error(_request: Request, exc: HTTPException):
    if isinstance(exc.detail, dict):
        return JSONResponse(status_code=exc.status_code, content=exc.detail)
    return JSONResponse(status_code=exc.status_code, content={"code": "DRAFT_UNAVAILABLE", "message": "Suggestions are unavailable right now.", "hint": "Add products from the catalog instead.", "details": {}})


class DraftRequest(BaseModel):
    text: str


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/drafts")
def create_draft(body: DraftRequest):
    text = body.text.strip()
    if not text or len(text) > 500:
        raise HTTPException(422, detail={"code": "VALIDATION_ERROR", "message": "Describe the order in 1 to 500 characters.", "hint": 'Write a short sentence, such as "one keyboard", or add products with the Add button.'})
    if not catalog.products:
        raise HTTPException(503, detail={"code": "CATALOG_UNAVAILABLE", "message": "The catalog is not available.", "hint": "Try again in a moment."})
    try:
        result = ground(model.suggest(text, catalog.candidates(text)), catalog, text)
    except (LookupError, ValueError, TypeError, OSError, ArithmeticError) as exc:
        raise HTTPException(503, detail={"code": "DRAFT_UNAVAILABLE", "message": "Suggestions are unavailable right now.", "hint": "Add products from the catalog instead."}) from exc
    return {"draftId": "drf_" + uuid.uuid4().hex[:16], **result}
