package tsbridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
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
	PeersVersion   int64  `json:"peersVersion"`
	LastError      string `json:"lastError"`
	Detail         string `json:"detail"`
}

type peerSnapshot struct {
	ID            string   `json:"id"`
	HostName      string   `json:"hostName"`
	DNSName       string   `json:"dnsName"`
	IPs           []string `json:"ips"`
	OS            string   `json:"os"`
	Online        bool     `json:"online"`
	SSHHostKeys   bool     `json:"sshHostKeys"`
	PrimaryRoutes []string `json:"primaryRoutes,omitempty"`
	CurAddr       string   `json:"curAddr,omitempty"`
	Relay         string   `json:"relay,omitempty"`
	Active        bool     `json:"active,omitempty"`
}

type pingResultSnapshot struct {
	IP             string  `json:"ip"`
	NodeName       string  `json:"nodeName"`
	LatencyMs      float64 `json:"latencyMs"`
	Direct         bool    `json:"direct"`
	Endpoint       string  `json:"endpoint"`
	DERPRegionCode string  `json:"derpRegionCode"`
	DERPRegionID   int     `json:"derpRegionId"`
	PeerRelay      string  `json:"peerRelay"`
	Error          string  `json:"error"`
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
	forwards  map[int32]*tcpForward

	stateDir       string
	state          string
	authURL        string
	selfHostname   string
	selfIP         string
	tailnetName    string
	magicDNSSuffix string
	peersVersion   int64
	lastError      string
	detail         string
	loginStarted   bool
}

func writeStageFile(stateDir, stage string) {
	if stateDir == "" {
		return
	}
	_ = os.WriteFile(filepath.Join(stateDir, "go_stage.txt"), []byte(stage+"\n"), 0o600)
}

func writePanicFile(stateDir, where string, r any) string {
	msg := fmt.Sprintf("panic in %s: %v", where, r)
	if stateDir != "" {
		body := msg + "\n" + string(debug.Stack())
		_ = os.WriteFile(filepath.Join(stateDir, "go_panic.txt"), []byte(body), 0o600)
	}
	return msg
}

func (n *Node) currentStateDir() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.stateDir
}

func (n *Node) handleRecovered(where string, r any) string {
	dir := n.currentStateDir()
	msg := writePanicFile(dir, where, r)
	n.setError(msg)
	return msg
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

func configureSandboxEnv(stateDir string) error {
	dirs := map[string]string{
		"TS_LOGS_DIR":     filepath.Join(stateDir, "logs"),
		"TMPDIR":          filepath.Join(stateDir, "tmp"),
		"XDG_CACHE_HOME":  filepath.Join(stateDir, "cache"),
		"XDG_CONFIG_HOME": filepath.Join(stateDir, "config"),
	}
	for envKey, dirPath := range dirs {
		if err := os.MkdirAll(dirPath, 0o700); err != nil {
			return fmt.Errorf("create %s dir: %w", envKey, err)
		}
		if err := os.Setenv(envKey, dirPath); err != nil {
			return fmt.Errorf("set %s: %w", envKey, err)
		}
	}
	if os.Getenv("HOME") == "" {
		if err := os.Setenv("HOME", stateDir); err != nil {
			return fmt.Errorf("set HOME: %w", err)
		}
	}
	return nil
}

func (n *Node) Start(stateDir, hostname, authKey, controlURL string) (retErr error) {
	cleanDir := strings.TrimSpace(stateDir)
	defer func() {
		if r := recover(); r != nil {
			msg := writePanicFile(cleanDir, "Node.Start", r)
			n.setError(msg)
			retErr = errors.New(msg)
		}
	}()
	if err := n.Stop(); err != nil {
		return err
	}
	if cleanDir == "" {
		return errors.New("stateDir is required")
	}
	writeStageFile(cleanDir, "go:mkdir")
	if err := os.MkdirAll(cleanDir, 0o700); err != nil {
		return fmt.Errorf("create stateDir: %w", err)
	}
	_ = os.Remove(filepath.Join(cleanDir, "go_panic.txt"))
	if err := configureSandboxEnv(cleanDir); err != nil {
		n.setError(err.Error())
		return err
	}
	n.mu.Lock()
	n.stateDir = cleanDir
	n.mu.Unlock()
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

	writeStageFile(cleanDir, "go:tsnet_start")
	srv := &tsnet.Server{
		Dir:        cleanDir,
		Hostname:   cleanHost,
		AuthKey:    cleanAuthKey,
		ControlURL: cleanControlURL,
		Logf:       n.handleTsnetLog,
		UserLogf:   n.handleTsnetLog,
	}
	if err := srv.Start(); err != nil {
		_ = srv.Close()
		n.setError(err.Error())
		return fmt.Errorf("start tsnet server: %w", err)
	}

	writeStageFile(cleanDir, "go:local_client")
	lc, err := srv.LocalClient()
	if err != nil {
		_ = srv.Close()
		n.setError(err.Error())
		return fmt.Errorf("get tsnet local client: %w", err)
	}

	writeStageFile(cleanDir, "go:socks5")
	proxy, err := startSocks5Server(u, p, func(dialCtx context.Context, network, addr string) (net.Conn, error) {
		return dialTarget(dialCtx, srv, lc, network, addr)
	})
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
	n.peersVersion = 0
	n.lastError = ""
	n.detail = ""
	n.loginStarted = false
	n.mu.Unlock()

	writeStageFile(cleanDir, "go:watch_bus")
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
	defer func() {
		if r := recover(); r != nil {
			n.handleRecovered("Node.watchBus", r)
		}
	}()
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
			n.peersVersion++
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
	defer func() {
		if r := recover(); r != nil {
			n.handleRecovered("Node.pollAuthStatus", r)
		}
	}()
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
	defer func() {
		if r := recover(); r != nil {
			n.handleRecovered("Node.syncStatusFromLocalClient", r)
		}
	}()
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

func (n *Node) Stop() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = errors.New(n.handleRecovered("Node.Stop", r))
		}
	}()
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
	n.peersVersion = 0
	n.detail = ""
	n.loginStarted = false
	n.mu.Unlock()

	n.StopAllTCPForwards()
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

func (n *Node) Logout() (retErr error) {
	defer func() {
		if r := recover(); r != nil {
			retErr = errors.New(n.handleRecovered("Node.Logout", r))
		}
	}()
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
	defer func() {
		if r := recover(); r != nil {
			n.handleRecovered("Node.UpdateNetwork", r)
		}
	}()
	UpdateAndroidNetwork(defaultIface, gatewayIP, interfacesJSON)
	n.NotifyNetworkChanged()
}

func (n *Node) NotifyNetworkChanged() {
	defer func() {
		if r := recover(); r != nil {
			n.handleRecovered("Node.NotifyNetworkChanged", r)
		}
	}()
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

func (n *Node) StatusJSON() (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = `{"state":"Error"}`
		}
	}()
	n.mu.Lock()
	snap := nodeStatusSnapshot{
		State:          n.state,
		AuthURL:        n.authURL,
		SelfHostname:   n.selfHostname,
		SelfIP:         n.selfIP,
		TailnetName:    n.tailnetName,
		MagicDNSSuffix: n.magicDNSSuffix,
		ProxyPort:      0,
		PeersVersion:   n.peersVersion,
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

func (n *Node) PeersJSON() (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = "[]"
		}
	}()
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
		var rawRoutes []netip.Prefix
		if p.PrimaryRoutes != nil {
			rawRoutes = make([]netip.Prefix, 0, p.PrimaryRoutes.Len())
			for i := range p.PrimaryRoutes.Len() {
				rawRoutes = append(rawRoutes, p.PrimaryRoutes.At(i))
			}
		}
		peers = append(peers, peerSnapshot{
			ID:            string(p.ID),
			HostName:      p.HostName,
			DNSName:       p.DNSName,
			IPs:           ips,
			OS:            p.OS,
			Online:        p.Online,
			SSHHostKeys:   len(p.SSH_HostKeys) > 0,
			PrimaryRoutes: filterSubnetRoutes(rawRoutes),
			CurAddr:       p.CurAddr,
			Relay:         p.Relay,
			Active:        p.Active,
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

func (n *Node) PingPeerJSON(target string) (out string) {
	defer func() {
		if r := recover(); r != nil {
			msg := n.handleRecovered("Node.PingPeerJSON", r)
			raw, _ := json.Marshal(pingResultSnapshot{Error: msg})
			out = string(raw)
		}
	}()
	encodeErr := func(msg string) string {
		raw, _ := json.Marshal(pingResultSnapshot{Error: msg})
		return string(raw)
	}

	cleanTarget := strings.TrimSuffix(strings.TrimSpace(target), ".")
	if cleanTarget == "" {
		return encodeErr("Target host or IP is required")
	}

	n.mu.Lock()
	lc := n.lc
	n.mu.Unlock()
	if lc == nil {
		return encodeErr("Embedded Tailscale is not running")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	ip, err := netip.ParseAddr(cleanTarget)
	if err != nil {
		st, stErr := lc.Status(ctx)
		if stErr != nil || st == nil {
			return encodeErr("Could not query tailnet status for peer IP")
		}
		peerIP := resolvePeerIPFromStatus(st, cleanTarget)
		if peerIP == "" {
			return encodeErr("Peer not found on tailnet: " + cleanTarget)
		}
		ip, err = netip.ParseAddr(peerIP)
		if err != nil {
			return encodeErr("Invalid peer IP: " + peerIP)
		}
	}

	res, err := lc.Ping(ctx, ip, tailcfg.PingDisco)
	if err != nil {
		return encodeErr(err.Error())
	}
	if res == nil {
		return encodeErr("Empty ping response")
	}
	snap := pingResultSnapshot{
		IP:             res.IP,
		NodeName:       res.NodeName,
		LatencyMs:      res.LatencySeconds * 1000.0,
		Direct:         res.Endpoint != "" && res.DERPRegionID == 0 && res.PeerRelay == "",
		Endpoint:       res.Endpoint,
		DERPRegionCode: res.DERPRegionCode,
		DERPRegionID:   res.DERPRegionID,
		PeerRelay:      res.PeerRelay,
		Error:          res.Err,
	}
	raw, marshalErr := json.Marshal(snap)
	if marshalErr != nil {
		return encodeErr(marshalErr.Error())
	}
	return string(raw)
}

func dialTarget(ctx context.Context, srv *tsnet.Server, lc *local.Client, network, addr string) (net.Conn, error) {
	conn, err := srv.Dial(ctx, network, addr)
	if err == nil || lc == nil {
		return conn, err
	}

	host, port, splitErr := net.SplitHostPort(addr)
	if splitErr != nil {
		return nil, err
	}
	cleanHost := strings.TrimSuffix(strings.TrimSpace(host), ".")
	if cleanHost == "" || net.ParseIP(cleanHost) != nil {
		return nil, err
	}

	stCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	st, stErr := lc.Status(stCtx)
	cancel()
	if stErr != nil || st == nil {
		return nil, err
	}

	peerIP := resolvePeerIPFromStatus(st, cleanHost)
	if peerIP == "" {
		return nil, err
	}
	return srv.Dial(ctx, network, net.JoinHostPort(peerIP, port))
}

func resolvePeerIPFromStatus(st *ipnstate.Status, host string) string {
	if st == nil {
		return ""
	}
	target := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(host), "."))
	if target == "" {
		return ""
	}
	for _, p := range st.Peer {
		if p == nil {
			continue
		}
		if peerMatchesHost(p.HostName, p.DNSName, target) {
			for _, ip := range p.TailscaleIPs {
				if ip.Is4() {
					return ip.String()
				}
			}
			if len(p.TailscaleIPs) > 0 {
				return p.TailscaleIPs[0].String()
			}
		}
	}
	return ""
}

func peerMatchesHost(hostName, dnsName, target string) bool {
	if strings.ToLower(strings.TrimSpace(hostName)) == target {
		return true
	}
	cleanDNS := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(dnsName), "."))
	if cleanDNS == "" {
		return false
	}
	if cleanDNS == target {
		return true
	}
	shortDNS, _, _ := strings.Cut(cleanDNS, ".")
	return shortDNS != "" && shortDNS == target
}
