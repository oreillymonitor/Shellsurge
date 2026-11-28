package tsbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"reflect"
	"testing"
)

func TestFilterSubnetRoutesExcludesDefaultRoutes(t *testing.T) {
	in := []netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/0"),
		netip.MustParsePrefix("::/0"),
		netip.MustParsePrefix("192.168.1.42/24"),
		netip.MustParsePrefix("192.168.1.0/24"),
		netip.MustParsePrefix("fd00:1234::1/64"),
	}
	got := filterSubnetRoutes(in)
	want := []string{"192.168.1.0/24", "fd00:1234::/64"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("filterSubnetRoutes() = %v, want %v", got, want)
	}
	if len(filterSubnetRoutes([]netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")})) != 0 {
		t.Fatalf("expected default-only routes to return empty slice")
	}
}

func TestStartTCPForwardWithDialerRelaysBidirectionally(t *testing.T) {
	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen echo: %v", err)
	}
	defer echoLn.Close()

	go func() {
		for {
			c, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()

	echoPort := int32(echoLn.Addr().(*net.TCPAddr).Port)
	fwd, err := startTCPForwardWithDialer("devbox.tailnet.ts.net", echoPort, func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, echoLn.Addr().String())
	})
	if err != nil {
		t.Fatalf("startTCPForwardWithDialer: %v", err)
	}
	defer fwd.close()

	client, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", fwd.localPort))
	if err != nil {
		t.Fatalf("dial forward: %v", err)
	}
	defer client.Close()

	msg := "hello-tsnet-preview"
	if _, err := io.WriteString(client, msg); err != nil {
		t.Fatalf("write: %v", err)
	}
	if tc, ok := client.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}
	buf, err := io.ReadAll(client)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(buf) != msg {
		t.Fatalf("echoed = %q, want %q", string(buf), msg)
	}
}

func TestActiveTCPForwardsJSONAndStop(t *testing.T) {
	fwd, err := startTCPForwardWithDialer("app.ts.net", 3000, func(ctx context.Context, network, addr string) (net.Conn, error) {
		return nil, io.EOF
	})
	if err != nil {
		t.Fatalf("startTCPForwardWithDialer: %v", err)
	}
	n := NewNode()
	n.forwards = map[int32]*tcpForward{
		fwd.localPort: fwd,
	}

	raw := n.ActiveTCPForwardsJSON()
	var snaps []tcpForwardSnapshot
	if err := json.Unmarshal([]byte(raw), &snaps); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if len(snaps) != 1 || snaps[0].LocalPort != fwd.localPort || snaps[0].TargetHost != "app.ts.net" || snaps[0].TargetPort != 3000 {
		t.Fatalf("unexpected snapshots: %+v", snaps)
	}

	if err := n.StopTCPForward(fwd.localPort); err != nil {
		t.Fatalf("StopTCPForward: %v", err)
	}
	if got := n.ActiveTCPForwardsJSON(); got != "[]" {
		t.Fatalf("after stop ActiveTCPForwardsJSON() = %s, want []", got)
	}
}
