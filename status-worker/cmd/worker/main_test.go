package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"orderflow/status-worker/internal/orderclient"
)

func TestRunTickDrainsAllDueBatches(t *testing.T) {
	var dueCalls atomic.Int32
	var promotions atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/orders/due" {
			call := dueCalls.Add(1)
			count := 0
			if call == 1 {
				count = 50
			} else if call == 2 {
				count = 5
			}
			ids := make([]string, count)
			for i := range ids {
				ids[i] = fmt.Sprintf("ord_%04d", int(call)*100+i)
			}
			_ = json.NewEncoder(w).Encode(map[string][]string{"orderIds": ids})
			return
		}
		if r.Method != http.MethodPost {
			t.Errorf("unexpected method %s", r.Method)
		}
		promotions.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := &orderclient.Client{BaseURL: server.URL, Token: "test-token", HTTP: server.Client()}
	if err := runTick(context.Background(), client); err != nil {
		t.Fatalf("runTick returned an error: %v", err)
	}
	if got := dueCalls.Load(); got != 3 {
		t.Fatalf("Due was called %d times, want 3", got)
	}
	if got := promotions.Load(); got != 55 {
		t.Fatalf("promoted %d orders, want 55", got)
	}
}

func TestWeakProductionTokenRejectsDefaultsAndShortValues(t *testing.T) {
	for _, token := range []string{"", "short", "local-dev-internal-token-32bytes", "replace-this-with-a-long-production-internal-token", strings.Repeat("x", 48)} {
		if !weakProductionToken(token) {
			t.Errorf("weakProductionToken(%q) = false, want true", token)
		}
	}
	if weakProductionToken("Truly-private-token-4d7c9a2f-8b1e") {
		t.Fatal("a long high-variation token was rejected")
	}
}

func TestRunTickStopsWhenContextIsCancelled(t *testing.T) {
	entered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done()
	}))
	defer server.Close()

	client := &orderclient.Client{BaseURL: server.URL, Token: "test-token", HTTP: server.Client()}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() { result <- runTick(ctx, client) }()
	<-entered
	cancel()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("runTick should return the cancellation error")
		}
	case <-time.After(time.Second):
		t.Fatal("runTick did not stop after cancellation")
	}
}
