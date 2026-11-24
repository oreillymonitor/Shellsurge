package tsbridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

type nodeStatusSnapshot struct {
	State          string `json:"state"`
	AuthURL        string `json:"authUrl"`
	SelfHostname   string `json:"selfHostname"`
	SelfIP         string `json:"selfIp"`
	TailnetName    string `json:"tailnetName"`
	MagicDNSSuffix string `json:"magicDnsSuffix"`
	ProxyPort      int32  `json:"proxyPort"`
	LastError      string `json:"lastError"`
	Detail         string `json:"detail"`
}

type peerSnapshot struct {
	ID          string   `json:"id"`
	HostName    string   `json:"hostName"`
	DNSName     string   `json:"dnsName"`
	IPs         []string `json:"ips"`
	OS          string   `json:"os"`
	Online      bool     `json:"online"`
	SSHHostKeys bool     `json:"sshHostKeys"`
}

// Node manages a single embedded userspace tsnet node and loopback SOCKS5 dialer.
type Node struct {
	mu sync.Mutex

	srv       *tsnet.Server
	lc        *local.Client
	proxy     *socks5Server
	proxyUser string
	proxyPass string
	cancel    context.CancelFunc

	state          string
	authURL        string
	selfHostname   string
	selfIP         string
	tailnetName    string
	magicDNSSuffix string
	lastError      string
	detail         string
	loginStarted   bool
}

func NewNode() *Node {
	return &Node{
		state: "Stopped",
	}
}

func randomHexToken(numBytes int) (string, error) {
	b := make([]byte, numBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func (n *Node) Start(stateDir, hostname, authKey, controlURL string) error {
	if err := n.Stop(); err != nil {
		return err
	}
	cleanDir := strings.TrimSpace(stateDir)
	if cleanDir == "" {
		return errors.New("stateDir is required")
	}
	if err := os.MkdirAll(cleanDir, 0o700); err != nil {
		return fmt.Errorf("create stateDir: %w", err)
	}
	cleanHost := strings.TrimSpace(hostname)
	if cleanHost == "" {
		cleanHost = "shellsurge"
	}
	cleanAuthKey := strings.TrimSpace(authKey)
	cleanControlURL := strings.TrimSpace(controlURL)

	u, err := randomHexToken(16)
	if err != nil {
		return fmt.Errorf("generate proxy user: %w", err)
	}
	p, err := randomHexToken(16)
	if err != nil {
		return fmt.Errorf("generate proxy password: %w", err)
	}

	srv := &tsnet.Server{
		Dir:        cleanDir,
		Hostname:   cleanHost,
		AuthKey:    cleanAuthKey,
		ControlURL: cleanControlURL,
		Logf:       n.handleTsnetLog,
	}
	if err := srv.Start(); err != nil {
		_ = srv.Close()
		n.setError(err.Error())
		return fmt.Errorf("start tsnet server: %w", err)
	}

	lc, err := srv.LocalClient()
	if err != nil {
		_ = srv.Close()
		n.setError(err.Error())
		return fmt.Errorf("get tsnet local client: %w", err)
	}

	proxy, err := startSocks5Server(u, p, srv.Dial)
	if err != nil {
		_ = srv.Close()
		n.setError(err.Error())
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())

	n.mu.Lock()
	n.srv = srv
	n.lc = lc
	n.proxy = proxy
	n.proxyUser = u
	n.proxyPass = p
	n.cancel = cancel
	n.state = "Starting"
	n.authURL = ""
	n.selfHostname = cleanHost
	n.selfIP = ""
	n.tailnetName = ""
	n.magicDNSSuffix = ""
	n.lastError = ""
	n.detail = ""
	n.loginStarted = false
	n.mu.Unlock()

	go n.watchBus(ctx, lc, cleanAuthKey != "")
	return nil
}

func (n *Node) setError(msg string) {
	n.mu.Lock()
	n.lastError = msg
	n.mu.Unlock()
}

func (n *Node) handleTsnetLog(format string, args ...any) {
	msg := strings.TrimSpace(fmt.Sprintf(format, args...))
	if msg == "" {
		return
	}
	lower := strings.ToLower(msg)
	if strings.Contains(lower, "error") ||
		strings.Contains(lower, "failed") ||
		strings.Contains(lower, "denied") ||
		strings.Contains(lower, "warning") ||
		strings.Contains(lower, "timeout") {
		n.mu.Lock()
		if n.authURL == "" && n.state != "Running" {
			if len(msg) > 220 {
				msg = msg[:220] + "…"
			}
			n.detail = msg
		}
		n.mu.Unlock()
	}
}

func (n *Node) watchBus(ctx context.Context, lc *local.Client, hasAuthKey bool) {
	go n.pollAuthStatus(ctx, lc, hasAuthKey)

	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyInitialNetMap)
	if err != nil {
		if ctx.Err() == nil {
			n.setError(err.Error())
		}
		return
	}
	defer watcher.Close()

	go n.syncStatusFromLocalClient(ctx, lc, hasAuthKey)

	for {
		note, err := watcher.Next()
		if err != nil {
			if ctx.Err() == nil {
				n.setError(err.Error())
			}
			return
		}
		var triggerInteractive bool
		var refreshStatus bool

		n.mu.Lock()
		if note.State != nil {
			n.state = note.State.String()
			if *note.State == ipn.Running {
				n.authURL = ""
				n.lastError = ""
				n.detail = ""
				refreshStatus = true
			} else if *note.State == ipn.NeedsLogin && !hasAuthKey && n.authURL == "" && !n.loginStarted {
				n.loginStarted = true
				triggerInteractive = true
			}
		}
		if note.BrowseToURL != nil && *note.BrowseToURL != "" {
			n.authURL = *note.BrowseToURL
			n.detail = ""
		}
		if note.NetMap != nil {
			if note.NetMap.SelfNode.Valid() {
				addrs := note.NetMap.SelfNode.Addresses()
				for i := range addrs.Len() {
					a := addrs.At(i).Addr()
					if a.Is4() {
						n.selfIP = a.String()
						break
					}
					if n.selfIP == "" {
						n.selfIP = a.String()
					}
				}
				if name := note.NetMap.SelfNode.ComputedName(); name != "" {
					n.selfHostname = name
				}
			}
			if note.NetMap.Domain != "" {
				n.tailnetName = note.NetMap.Domain
			}
			if dns := note.NetMap.MagicDNSSuffix(); dns != "" {
				n.magicDNSSuffix = dns
			}
		}
		if note.ErrMessage != nil && *note.ErrMessage != "" {
			n.lastError = *note.ErrMessage
		}
		n.mu.Unlock()

		if triggerInteractive {
			go func() {
				lCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
				defer cancel()
				if err := lc.StartLoginInteractive(lCtx); err != nil && ctx.Err() == nil {
					n.mu.Lock()
					if n.lastError == "" {
						n.detail = "StartLoginInteractive: " + err.Error()
					}
					n.mu.Unlock()
				}
			}()
		}
		if refreshStatus {
			go n.syncStatusFromLocalClient(ctx, lc, hasAuthKey)
		}
	}
}

func (n *Node) pollAuthStatus(ctx context.Context, lc *local.Client, hasAuthKey bool) {
	ticker := time.NewTicker(1200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			n.mu.Lock()
			done := n.state == "Running" || (n.authURL != "" && n.state == "NeedsLogin")
			n.mu.Unlock()
			if done {
				return
			}
			n.syncStatusFromLocalClient(ctx, lc, hasAuthKey)
		}
	}
}

func (n *Node) syncStatusFromLocalClient(ctx context.Context, lc *local.Client, hasAuthKey bool) {
	sCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(sCtx)
	if err != nil || st == nil {
		return
	}

	var triggerInteractive bool

	n.mu.Lock()
	if st.BackendState != "" {
		n.state = st.BackendState
	}
	if st.AuthURL != "" {
		n.authURL = st.AuthURL
		n.detail = ""
	} else if st.BackendState == "Running" {
		n.authURL = ""
		n.lastError = ""
		n.detail = ""
	}
	if st.BackendState == "NeedsLogin" && !hasAuthKey && n.authURL == "" && !n.loginStarted {
		n.loginStarted = true
		triggerInteractive = true
	}
	if len(st.TailscaleIPs) > 0 {
		for _, ip := range st.TailscaleIPs {
			if ip.Is4() {
				n.selfIP = ip.String()
				break
			}
		}
		if n.selfIP == "" {
			n.selfIP = st.TailscaleIPs[0].String()
		}
	}
	if st.CurrentTailnet != nil {
		if st.CurrentTailnet.Name != "" {
			n.tailnetName = st.CurrentTailnet.Name
		}
		if st.CurrentTailnet.MagicDNSSuffix != "" {
			n.magicDNSSuffix = st.CurrentTailnet.MagicDNSSuffix
		}
	}
	if st.Self != nil && st.Self.HostName != "" {
		n.selfHostname = st.Self.HostName
	}
	if n.authURL == "" && st.BackendState != "Running" && len(st.Health) > 0 {
		for _, h := range st.Health {
			if strings.TrimSpace(h) != "" {
				n.detail = strings.TrimSpace(h)
				break
			}
		}
	}
	n.mu.Unlock()

	if triggerInteractive {
		lCtx, lCancel := context.WithTimeout(ctx, 15*time.Second)
		defer lCancel()
		if err := lc.StartLoginInteractive(lCtx); err != nil && ctx.Err() == nil {
			n.mu.Lock()
			if n.lastError == "" {
				n.detail = "StartLoginInteractive: " + err.Error()
			}
			n.mu.Unlock()
		}
	}
}

func (n *Node) Stop() error {
	n.mu.Lock()
	cancel := n.cancel
	proxy := n.proxy
	srv := n.srv
	n.cancel = nil
	n.proxy = nil
	n.srv = nil
	n.lc = nil
	n.proxyUser = ""
	n.proxyPass = ""
	n.state = "Stopped"
	n.authURL = ""
	n.selfIP = ""
	n.tailnetName = ""
	n.magicDNSSuffix = ""
	n.detail = ""
	n.loginStarted = false
	n.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	var err error
	if proxy != nil {
		if pErr := proxy.Close(); pErr != nil {
			err = pErr
		}
	}
	if srv != nil {
		if sErr := srv.Close(); sErr != nil && err == nil {
			err = sErr
		}
	}
	return err
}

func (n *Node) Logout() error {
	n.mu.Lock()
	lc := n.lc
	n.mu.Unlock()

	if lc != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_ = lc.Logout(ctx)
		cancel()
	}
	return n.Stop()
}

func (n *Node) UpdateNetwork(defaultIface, gatewayIP, interfacesJSON string) {
	UpdateAndroidNetwork(defaultIface, gatewayIP, interfacesJSON)
	n.NotifyNetworkChanged()
}

func (n *Node) NotifyNetworkChanged() {
	n.mu.Lock()
	lc := n.lc
	n.mu.Unlock()
	if lc == nil {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_, _ = lc.StatusWithoutPeers(ctx)
	}()
}

func (n *Node) ProxyPort() int32 {
	n.mu.Lock()
	proxy := n.proxy
	n.mu.Unlock()
	if proxy == nil {
		return 0
	}
	return proxy.Port()
}

func (n *Node) ProxyUsername() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.proxyUser
}

func (n *Node) ProxyPassword() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.proxyPass
}

func (n *Node) StatusJSON() string {
	n.mu.Lock()
	snap := nodeStatusSnapshot{
		State:          n.state,
		AuthURL:        n.authURL,
		SelfHostname:   n.selfHostname,
		SelfIP:         n.selfIP,
		TailnetName:    n.tailnetName,
		MagicDNSSuffix: n.magicDNSSuffix,
		ProxyPort:      0,
		LastError:      n.lastError,
		Detail:         n.detail,
	}
	if n.proxy != nil {
		snap.ProxyPort = n.proxy.Port()
	}
	n.mu.Unlock()

	raw, err := json.Marshal(snap)
	if err != nil {
		return `{"state":"Error"}`
	}
	return string(raw)
}

func (n *Node) PeersJSON() string {
	n.mu.Lock()
	lc := n.lc
	n.mu.Unlock()
	if lc == nil {
		return "[]"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil || st == nil {
		return "[]"
	}

	peers := make([]peerSnapshot, 0, len(st.Peer))
	for _, p := range st.Peer {
		if p == nil {
			continue
		}
		ips := make([]string, 0, len(p.TailscaleIPs))
		for _, ip := range p.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		peers = append(peers, peerSnapshot{
			ID:          string(p.ID),
			HostName:    p.HostName,
			DNSName:     p.DNSName,
			IPs:         ips,
			OS:          p.OS,
			Online:      p.Online,
			SSHHostKeys: len(p.SSH_HostKeys) > 0,
		})
	}

	sort.Slice(peers, func(i, j int) bool {
		if peers[i].Online != peers[j].Online {
			return peers[i].Online
		}
		return strings.ToLower(peers[i].HostName) < strings.ToLower(peers[j].HostName)
	})

	raw, err := json.Marshal(peers)
	if err != nil {
		return "[]"
	}
	return string(raw)
}
