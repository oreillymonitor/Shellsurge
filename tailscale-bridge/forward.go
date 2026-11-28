package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/tsnet"
)

type tcpForward struct {
	targetHost string
	targetPort int32
	localPort  int32
	listener   net.Listener
	cancel     context.CancelFunc
}

type tcpForwardSnapshot struct {
	LocalPort  int32  `json:"localPort"`
	TargetHost string `json:"targetHost"`
	TargetPort int32  `json:"targetPort"`
}

func filterSubnetRoutes(routes []netip.Prefix) []string {
	if len(routes) == 0 {
		return nil
	}
	out := make([]string, 0, len(routes))
	seen := make(map[string]struct{}, len(routes))
	for _, pfx := range routes {
		if !pfx.IsValid() || pfx.Bits() <= 0 {
			// Skip 0.0.0.0/0 and ::/0 exit-node default routes.
			continue
		}
		s := pfx.Masked().String()
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func (n *Node) StartTCPForward(targetHost string, targetPort int32) (localPort int32, retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = errors.New(n.handleRecovered("Node.StartTCPForward", r))
		}
	}()

	cleanHost := strings.TrimSuffix(strings.TrimSpace(targetHost), ".")
	if cleanHost == "" {
		return 0, errors.New("targetHost is required")
	}
	if targetPort < 1 || targetPort > 65535 {
		return 0, fmt.Errorf("invalid targetPort: %d", targetPort)
	}

	n.mu.Lock()
	srv := n.srv
	lc := n.lc
	if n.forwards != nil {
		for port, fwd := range n.forwards {
			if strings.EqualFold(fwd.targetHost, cleanHost) && fwd.targetPort == targetPort {
				n.mu.Unlock()
				return port, nil
			}
		}
	}
	n.mu.Unlock()

	if srv == nil {
		return 0, errors.New("embedded Tailscale is not running")
	}

	fwd, err := startTCPForwardWithDialer(cleanHost, targetPort, func(ctx context.Context, network, addr string) (net.Conn, error) {
		return dialTarget(ctx, srv, lc, network, addr)
	})
	if err != nil {
		return 0, err
	}

	n.mu.Lock()
	if n.srv != srv {
		n.mu.Unlock()
		fwd.close()
		return 0, errors.New("embedded Tailscale stopped")
	}
	if n.forwards == nil {
		n.forwards = make(map[int32]*tcpForward)
	}
	for port, existing := range n.forwards {
		if strings.EqualFold(existing.targetHost, cleanHost) && existing.targetPort == targetPort {
			n.mu.Unlock()
			fwd.close()
			return port, nil
		}
	}
	n.forwards[fwd.localPort] = fwd
	n.mu.Unlock()

	return fwd.localPort, nil
}

func (n *Node) StopTCPForward(localPort int32) (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = errors.New(n.handleRecovered("Node.StopTCPForward", r))
		}
	}()

	n.mu.Lock()
	var fwd *tcpForward
	if n.forwards != nil {
		fwd = n.forwards[localPort]
		delete(n.forwards, localPort)
	}
	n.mu.Unlock()

	if fwd != nil {
		fwd.close()
	}
	return nil
}

func (n *Node) StopAllTCPForwards() {
	n.mu.Lock()
	forwards := n.forwards
	n.forwards = nil
	n.mu.Unlock()

	for _, fwd := range forwards {
		fwd.close()
	}
}

func (n *Node) ActiveTCPForwardsJSON() (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = "[]"
		}
	}()

	n.mu.Lock()
	items := make([]tcpForwardSnapshot, 0, len(n.forwards))
	for _, fwd := range n.forwards {
		items = append(items, tcpForwardSnapshot{
			LocalPort:  fwd.localPort,
			TargetHost: fwd.targetHost,
			TargetPort: fwd.targetPort,
		})
	}
	n.mu.Unlock()

	sort.Slice(items, func(i, j int) bool {
		return items[i].LocalPort < items[j].LocalPort
	})
	raw, err := json.Marshal(items)
	if err != nil {
		return "[]"
	}
	return string(raw)
}

func startTCPForwardWithDialer(
	targetHost string,
	targetPort int32,
	dialFn func(context.Context, string, string) (net.Conn, error),
) (*tcpForward, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen loopback tcp forward: %w", err)
	}
	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		_ = ln.Close()
		return nil, errors.New("unexpected listener address type")
	}

	ctx, cancel := context.WithCancel(context.Background())
	fwd := &tcpForward{
		targetHost: targetHost,
		targetPort: targetPort,
		localPort:  int32(tcpAddr.Port),
		listener:   ln,
		cancel:     cancel,
	}
	targetAddr := net.JoinHostPort(targetHost, strconv.Itoa(int(targetPort)))
	go fwd.serve(ctx, targetAddr, dialFn)
	return fwd, nil
}

func (f *tcpForward) serve(
	ctx context.Context,
	targetAddr string,
	dialFn func(context.Context, string, string) (net.Conn, error),
) {
	for {
		clientConn, err := f.listener.Accept()
		if err != nil {
			return
		}
		go func(c net.Conn) {
			defer c.Close()
			dialCtx, dialCancel := context.WithTimeout(ctx, 15*time.Second)
			remoteConn, dialErr := dialFn(dialCtx, "tcp", targetAddr)
			dialCancel()
			if dialErr != nil {
				return
			}
			defer remoteConn.Close()

			done := make(chan struct{})
			go func() {
				select {
				case <-ctx.Done():
					_ = c.Close()
					_ = remoteConn.Close()
				case <-done:
				}
			}()

			var wg sync.WaitGroup
			wg.Add(2)
			go func() {
				defer wg.Done()
				_, _ = io.Copy(remoteConn, c)
				if cw, ok := remoteConn.(interface{ CloseWrite() error }); ok {
					_ = cw.CloseWrite()
				}
			}()
			go func() {
				defer wg.Done()
				_, _ = io.Copy(c, remoteConn)
				if cw, ok := c.(interface{ CloseWrite() error }); ok {
					_ = cw.CloseWrite()
				}
			}()
			wg.Wait()
			close(done)
		}(clientConn)
	}
}

func (f *tcpForward) close() {
	if f.cancel != nil {
		f.cancel()
	}
	if f.listener != nil {
		_ = f.listener.Close()
	}
}

// Keep unused imports referenced if build tags shift.
var _ = (*tsnet.Server)(nil)
var _ = (*local.Client)(nil)
