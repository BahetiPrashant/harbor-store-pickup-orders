package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"orderflow/status-worker/internal/orderclient"
)

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func weakProductionToken(token string) bool {
	if len([]byte(token)) < 32 || strings.TrimSpace(token) == "" ||
		token == "local-dev-internal-token-32bytes" || token == "replace-this-with-a-long-production-internal-token" {
		return true
	}
	characters := make(map[rune]struct{})
	for _, character := range token {
		characters[character] = struct{}{}
	}
	return len(characters) < 8
}

func runTick(ctx context.Context, client *orderclient.Client) error {
	for {
		ids, err := client.Due(ctx)
		if err != nil {
			slog.Warn("order.promote.skipped", "outcome", "error", "error", err)
			return err
		}
		if len(ids) == 0 {
			return nil
		}

		workerCount := len(ids)
		if workerCount > 4 {
			workerCount = 4
		}
		jobs := make(chan string)
		var group sync.WaitGroup
		var promoted atomic.Int32
		var failed atomic.Int32
		for range workerCount {
			group.Add(1)
			go func() {
				defer group.Done()
				for id := range jobs {
					code, promoteErr := client.Promote(ctx, id)
					if promoteErr != nil || code >= http.StatusInternalServerError {
						failed.Add(1)
						slog.Warn("order.promote.skipped", "orderId", id, "outcome", "error", "error", promoteErr)
						continue
					}
					if code == http.StatusOK {
						promoted.Add(1)
						slog.Info("order.promote.succeeded", "orderId", id)
					} else {
						slog.Info("order.promote.skipped", "orderId", id, "status", code)
					}
				}
			}()
		}
		for _, id := range ids {
			select {
			case jobs <- id:
			case <-ctx.Done():
				close(jobs)
				group.Wait()
				return ctx.Err()
			}
		}
		close(jobs)
		group.Wait()
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if promoted.Load() == 0 {
			// Avoid hot-looping if all returned orders lost a race or failed.
			if failed.Load() > 0 {
				return errors.New("one or more order promotions failed")
			}
			return nil
		}
	}
}

func main() {
	token := os.Getenv("INTERNAL_TOKEN")
	if strings.EqualFold(os.Getenv("ORDERFLOW_ENV"), "prod") && weakProductionToken(token) {
		slog.Error("INTERNAL_TOKEN must be configured in production")
		os.Exit(1)
	}

	interval, err := time.ParseDuration(env("INTERVAL", "5m"))
	if err != nil || interval <= 0 {
		slog.Error("invalid INTERVAL")
		os.Exit(1)
	}
	retryDelay := 5 * time.Second
	if interval < retryDelay {
		retryDelay = interval
	}
	client := &orderclient.Client{
		BaseURL: env("ORDER_API_URL", "http://localhost:8080"),
		Token:   token,
		HTTP:    &http.Client{Timeout: 10 * time.Second},
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	for {
		nextDelay := interval
		if err := runTick(ctx, client); err != nil && ctx.Err() == nil {
			nextDelay = retryDelay
		}
		timer := time.NewTimer(nextDelay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}
