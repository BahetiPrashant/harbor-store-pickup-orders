package orderclient

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

var validID = regexp.MustCompile(`^ord_[a-z0-9]{1,44}$`)

type Client struct { BaseURL, Token string; HTTP *http.Client }
func (c *Client) request(ctx context.Context, method, path string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.BaseURL, "/")+path, nil); if err != nil { return nil, err }
	req.Header.Set("X-Internal-Token", c.Token)
	return c.HTTP.Do(req)
}
func (c *Client) Due(ctx context.Context) ([]string, error) {
	r, err := c.request(ctx, http.MethodGet, "/internal/orders/due?limit=50"); if err != nil { return nil, err }; defer r.Body.Close()
	if r.StatusCode != http.StatusOK { return nil, fmt.Errorf("due list returned %d", r.StatusCode) }
	var body struct { OrderIDs []string `json:"orderIds"` }; if err := json.NewDecoder(r.Body).Decode(&body); err != nil { return nil, err }; return body.OrderIDs, nil
}
func (c *Client) Promote(ctx context.Context, id string) (int, error) {
	if !validID.MatchString(id) { return 0, fmt.Errorf("invalid order id") }
	r, err := c.request(ctx, http.MethodPost, "/internal/orders/"+url.PathEscape(id)+"/promote"); if err != nil { return 0, err }; defer r.Body.Close(); return r.StatusCode, nil
}
