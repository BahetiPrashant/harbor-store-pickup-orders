FROM python:3.12-slim
WORKDIR /app
COPY intake-service/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY intake-service/app ./app
COPY catalog/catalog.json /catalog/catalog.json
USER 65532:65532
ENV CATALOG_PATH=/catalog/catalog.json
EXPOSE 8081
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","8081"]
