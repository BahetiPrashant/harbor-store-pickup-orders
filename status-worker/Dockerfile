FROM golang:1.22-alpine AS build
WORKDIR /src
COPY status-worker/go.mod ./
COPY status-worker/cmd ./cmd
COPY status-worker/internal ./internal
RUN CGO_ENABLED=0 GOOS=linux go build -o /worker ./cmd/worker
FROM scratch
COPY --from=build /worker /worker
USER 65532:65532
ENTRYPOINT ["/worker"]
