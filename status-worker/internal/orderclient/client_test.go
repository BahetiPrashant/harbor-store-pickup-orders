package orderclient

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDueAndPromote(t *testing.T) {
	s:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){ if r.Header.Get("X-Internal-Token")!="secret" { t.Errorf("missing token") }; if r.URL.Path=="/internal/orders/due" { fmt.Fprint(w,`{"orderIds":["ord_abc1","ord_def2"]}`); return }; if r.URL.Path=="/internal/orders/ord_abc1/promote" { w.WriteHeader(409); return }; w.WriteHeader(200) })); defer s.Close()
	c:=Client{BaseURL:s.URL,Token:"secret",HTTP:s.Client()}; ids,err:=c.Due(context.Background());if err!=nil||len(ids)!=2{t.Fatalf("Due: %v %v",ids,err)}; a,_:=c.Promote(context.Background(),ids[0]);b,_:=c.Promote(context.Background(),ids[1]);if a!=409||b!=200{t.Fatalf("statuses %d %d",a,b)}
}
