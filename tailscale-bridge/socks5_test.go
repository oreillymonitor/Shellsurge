package tsbridge

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"

	"tailscale.com/logpolicy"
	"tailscale.com/net/netmon"
)

func TestSocks5IPv4ConnectAndEcho(t *testing.T) {
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

	srv, err := startSocks5Server("alice", "s3cr3t", func(ctx context.Context, network, addr string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	})
	if err != nil {
		t.Fatalf("startSocks5Server: %v", err)
	}
	defer srv.Close()

	conn, err := net.Dial("tcp", echoTargetAddr(srv.Port()))
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer conn.Close()

	// Greeting
	if _, err := conn.Write([]byte{socks5Version, 1, authMethodUserPass}); err != nil {
		t.Fatal(err)
	}
	var greetResp [2]byte
	if _, err := io.ReadFull(conn, greetResp[:]); err != nil {
		t.Fatal(err)
	}
	if greetResp != [2]byte{socks5Version, authMethodUserPass} {
		t.Fatalf("unexpected greet response: %v", greetResp)
	}

	// Auth
	var authBuf bytes.Buffer
	authBuf.WriteByte(userPassVersion)
	authBuf.WriteByte(byte(len("alice")))
	authBuf.WriteString("alice")
	authBuf.WriteByte(byte(len("s3cr3t")))
	authBuf.WriteString("s3cr3t")
	if _, err := conn.Write(authBuf.Bytes()); err != nil {
		t.Fatal(err)
	}
	var authResp [2]byte
	if _, err := io.ReadFull(conn, authResp[:]); err != nil {
		t.Fatal(err)
	}
	if authResp != [2]byte{userPassVersion, userPassSuccess} {
		t.Fatalf("unexpected auth response: %v", authResp)
	}

	// Connect to echoLn (127.0.0.1:<port>)
	echoAddr := echoLn.Addr().(*net.TCPAddr)
	req := make([]byte, 10)
	req[0] = socks5Version
	req[1] = cmdConnect
	req[2] = 0x00
	req[3] = atypIPv4
	copy(req[4:8], echoAddr.IP.To4())
	binary.BigEndian.PutUint16(req[8:10], uint16(echoAddr.Port))
	if _, err := conn.Write(req); err != nil {
		t.Fatal(err)
	}

	var rep [10]byte
	if _, err := io.ReadFull(conn, rep[:]); err != nil {
		t.Fatal(err)
	}
	if rep[1] != repSuccess {
		t.Fatalf("unexpected connect status: %d", rep[1])
	}

	payload := []byte("hello tailscale bridge")
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, out); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, out) {
		t.Fatalf("echo mismatch: got %q want %q", out, payload)
	}
}

func TestSocks5DomainPassesMagicDNSTargetUnresolved(t *testing.T) {
	var mu sync.Mutex
	var dialedTarget string

	srv, err := startSocks5Server("u", "p", func(ctx context.Context, network, addr string) (net.Conn, error) {
		mu.Lock()
		dialedTarget = addr
		mu.Unlock()
		c1, c2 := net.Pipe()
		go func() {
			defer c2.Close()
			_, _ = io.Copy(c2, c2)
		}()
		return c1, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	conn, err := net.Dial("tcp", echoTargetAddr(srv.Port()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	_, _ = conn.Write([]byte{socks5Version, 1, authMethodUserPass})
	var greetResp [2]byte
	_, _ = io.ReadFull(conn, greetResp[:])

	_, _ = conn.Write([]byte{userPassVersion, 1, 'u', 1, 'p'})
	var authResp [2]byte
	_, _ = io.ReadFull(conn, authResp[:])

	domain := "devbox.tail1234.ts.net"
	var req bytes.Buffer
	req.Write([]byte{socks5Version, cmdConnect, 0x00, atypDomain, byte(len(domain))})
	req.WriteString(domain)
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], 22)
	req.Write(portBuf[:])
	_, _ = conn.Write(req.Bytes())

	var rep [10]byte
	if _, err := io.ReadFull(conn, rep[:]); err != nil {
		t.Fatal(err)
	}
	if rep[1] != repSuccess {
		t.Fatalf("expected repSuccess, got %d", rep[1])
	}

	mu.Lock()
	got := dialedTarget
	mu.Unlock()
	if got != "devbox.tail1234.ts.net:22" {
		t.Fatalf("expected unresolved domain dial target, got %q", got)
	}
}

func TestSocks5RejectsBadAuthAndNoAuth(t *testing.T) {
	srv, err := startSocks5Server("user", "pass", func(ctx context.Context, network, addr string) (net.Conn, error) {
		return nil, io.EOF
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	// Reject no-auth
	c1, err := net.Dial("tcp", echoTargetAddr(srv.Port()))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = c1.Write([]byte{socks5Version, 1, 0x00})
	var g1 [2]byte
	if _, err := io.ReadFull(c1, g1[:]); err != nil {
		t.Fatal(err)
	}
	c1.Close()
	if g1 != [2]byte{socks5Version, authMethodNoAccept} {
		t.Fatalf("expected no acceptable method, got %v", g1)
	}

	// Reject wrong password
	c2, err := net.Dial("tcp", echoTargetAddr(srv.Port()))
	if err != nil {
		t.Fatal(err)
	}
	defer c2.Close()
	_, _ = c2.Write([]byte{socks5Version, 1, authMethodUserPass})
	var g2 [2]byte
	_, _ = io.ReadFull(c2, g2[:])
	_, _ = c2.Write([]byte{userPassVersion, 4, 'u', 's', 'e', 'r', 5, 'w', 'r', 'o', 'n', 'g'})
	var a2 [2]byte
	if _, err := io.ReadFull(c2, a2[:]); err != nil {
		t.Fatal(err)
	}
	if a2 != [2]byte{userPassVersion, userPassFailure} {
		t.Fatalf("expected auth failure, got %v", a2)
	}
}

func TestNodeStoppedStatusAndPeers(t *testing.T) {
	node := NewNode()
	var status nodeStatusSnapshot
	if err := json.Unmarshal([]byte(node.StatusJSON()), &status); err != nil {
		t.Fatal(err)
	}
	if status.State != "Stopped" || status.ProxyPort != 0 {
		t.Fatalf("unexpected stopped snapshot: %+v", status)
	}
	if peers := node.PeersJSON(); strings.TrimSpace(peers) != "[]" {
		t.Fatalf("expected empty peers array, got %q", peers)
	}
}

func echoTargetAddr(port int32) string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(int(port)))
}

func TestGetIfAddrsReturnsLoopback(t *testing.T) {
	ifs, err := listInterfacesViaGetIfAddrs()
	if err != nil {
		t.Fatalf("listInterfacesViaGetIfAddrs failed: %v", err)
	}
	if len(ifs) == 0 {
		t.Fatal("expected at least one network interface")
	}
	foundLoopback := false
	for _, iface := range ifs {
		if !iface.IsLoopback() {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if strings.HasPrefix(a.String(), "127.0.0.1/") {
				foundLoopback = true
				break
			}
		}
	}
	if !foundLoopback {
		t.Fatal("expected loopback interface with 127.0.0.1 address")
	}
}

func TestParseAndroidInterfacesJSONAndOverride(t *testing.T) {
	defer UpdateAndroidNetwork("", "", "[]")

	payload := `[
	  {"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":["127.0.0.1/8","::1/128"]},
	  {"name":"wlan0","index":24,"mtu":1500,"up":true,"broadcast":true,"multicast":true,"addrs":["192.168.1.42/24"]}
	]`
	UpdateAndroidNetwork("", "192.168.1.1", payload)

	ifs, err := listInterfaces()
	if err != nil {
		t.Fatalf("listInterfaces failed: %v", err)
	}
	if len(ifs) != 2 {
		t.Fatalf("expected 2 overridden interfaces, got %d", len(ifs))
	}
	var wlan *netmon.Interface
	for i := range ifs {
		if ifs[i].Name == "wlan0" {
			wlan = &ifs[i]
			break
		}
	}
	if wlan == nil || !wlan.IsUp() {
		t.Fatalf("expected wlan0 to be present and Up, got %+v", wlan)
	}
	addrs, err := wlan.Addrs()
	if err != nil || len(addrs) != 1 || addrs[0].String() != "192.168.1.42/24" {
		t.Fatalf("unexpected wlan0 addrs: %v err=%v", addrs, err)
	}

	androidNetMu.RLock()
	defIf := androidDefaultIface
	androidNetMu.RUnlock()
	if defIf != "wlan0" {
		t.Fatalf("expected inferred default interface wlan0, got %q", defIf)
	}
}

func TestWriteStageAndPanicFiles(t *testing.T) {
	dir := t.TempDir()
	writeStageFile(dir, "go:tsnet_start")
	stageBytes, err := os.ReadFile(filepath.Join(dir, "go_stage.txt"))
	if err != nil {
		t.Fatalf("read go_stage.txt: %v", err)
	}
	if strings.TrimSpace(string(stageBytes)) != "go:tsnet_start" {
		t.Fatalf("unexpected go_stage.txt: %q", string(stageBytes))
	}

	msg := writePanicFile(dir, "TestCase", "boom")
	if !strings.Contains(msg, "panic in TestCase: boom") {
		t.Fatalf("unexpected panic msg: %q", msg)
	}
	panicBytes, err := os.ReadFile(filepath.Join(dir, "go_panic.txt"))
	if err != nil {
		t.Fatalf("read go_panic.txt: %v", err)
	}
	if !strings.Contains(string(panicBytes), "panic in TestCase: boom") {
		t.Fatalf("unexpected go_panic.txt: %q", string(panicBytes))
	}
}

func TestConfigureSandboxEnvPreventsLogsDirPanic(t *testing.T) {
	t.Setenv("TS_LOGS_DIR", "")
	t.Setenv("TMPDIR", "")
	t.Setenv("XDG_CACHE_HOME", "")
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("HOME", "")
	t.Setenv("STATE_DIRECTORY", "")

	dir := t.TempDir()
	if err := configureSandboxEnv(dir); err != nil {
		t.Fatalf("configureSandboxEnv failed: %v", err)
	}

	got := logpolicy.LogsDir(func(string, ...any) {})
	want := filepath.Join(dir, "logs")
	if got != want {
		t.Fatalf("logpolicy.LogsDir = %q, want %q", got, want)
	}
}
