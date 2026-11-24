package tsbridge

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

const (
	socks5Version      = 0x05
	authMethodUserPass = 0x02
	authMethodNoAccept = 0xFF

	userPassVersion = 0x01
	userPassSuccess = 0x00
	userPassFailure = 0x01

	cmdConnect = 0x01

	atypIPv4   = 0x01
	atypDomain = 0x03
	atypIPv6   = 0x04

	repSuccess             = 0x00
	repGeneralFailure      = 0x01
	repCommandNotSupported = 0x07
	repAtypNotSupported    = 0x08
)

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

type socks5Server struct {
	dial     dialFunc
	username string
	password string
	listener net.Listener
	ctx      context.Context
	cancel   context.CancelFunc
	wg       sync.WaitGroup
}

func startSocks5Server(username, password string, dial dialFunc) (*socks5Server, error) {
	if dial == nil {
		return nil, errors.New("socks5Server requires a dial function")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen loopback socks5: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	srv := &socks5Server{
		dial:     dial,
		username: username,
		password: password,
		listener: ln,
		ctx:      ctx,
		cancel:   cancel,
	}
	srv.wg.Add(1)
	go srv.acceptLoop()
	return srv, nil
}

func (s *socks5Server) Port() int32 {
	if s == nil || s.listener == nil {
		return 0
	}
	addr, ok := s.listener.Addr().(*net.TCPAddr)
	if !ok {
		return 0
	}
	return int32(addr.Port)
}

func (s *socks5Server) Close() error {
	if s == nil {
		return nil
	}
	s.cancel()
	var err error
	if s.listener != nil {
		err = s.listener.Close()
	}
	s.wg.Wait()
	return err
}

func (s *socks5Server) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			if s.ctx.Err() != nil {
				return
			}
			continue
		}
		s.wg.Add(1)
		go func(c net.Conn) {
			defer s.wg.Done()
			s.handleConn(c)
		}(conn)
	}
}

func (s *socks5Server) handleConn(client net.Conn) {
	defer client.Close()
	stopClientWatch := context.AfterFunc(s.ctx, func() {
		_ = client.Close()
	})
	defer stopClientWatch()
	_ = client.SetDeadline(time.Now().Add(20 * time.Second))

	// 1. Greeting: VER, NMETHODS, METHODS...
	var header [2]byte
	if _, err := io.ReadFull(client, header[:]); err != nil {
		return
	}
	if header[0] != socks5Version || header[1] == 0 {
		return
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	hasUserPass := false
	for _, m := range methods {
		if m == authMethodUserPass {
			hasUserPass = true
			break
		}
	}
	if !hasUserPass {
		_, _ = client.Write([]byte{socks5Version, authMethodNoAccept})
		return
	}
	if _, err := client.Write([]byte{socks5Version, authMethodUserPass}); err != nil {
		return
	}

	// 2. RFC 1929 Username/Password sub-negotiation
	var authVerLen [2]byte
	if _, err := io.ReadFull(client, authVerLen[:]); err != nil {
		return
	}
	if authVerLen[0] != userPassVersion {
		_, _ = client.Write([]byte{userPassVersion, userPassFailure})
		return
	}
	uBytes := make([]byte, int(authVerLen[1]))
	if _, err := io.ReadFull(client, uBytes); err != nil {
		return
	}
	var passLen [1]byte
	if _, err := io.ReadFull(client, passLen[:]); err != nil {
		return
	}
	pBytes := make([]byte, int(passLen[0]))
	if _, err := io.ReadFull(client, pBytes); err != nil {
		return
	}

	uMatch := subtle.ConstantTimeCompare(uBytes, []byte(s.username)) == 1
	pMatch := subtle.ConstantTimeCompare(pBytes, []byte(s.password)) == 1
	if !uMatch || !pMatch {
		_, _ = client.Write([]byte{userPassVersion, userPassFailure})
		return
	}
	if _, err := client.Write([]byte{userPassVersion, userPassSuccess}); err != nil {
		return
	}

	// 3. Request: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT
	var req [4]byte
	if _, err := io.ReadFull(client, req[:]); err != nil {
		return
	}
	if req[0] != socks5Version {
		return
	}
	if req[1] != cmdConnect {
		_, _ = client.Write([]byte{socks5Version, repCommandNotSupported, 0x00, atypIPv4, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch req[3] {
	case atypIPv4:
		var ip [4]byte
		if _, err := io.ReadFull(client, ip[:]); err != nil {
			return
		}
		host = net.IP(ip[:]).String()
	case atypDomain:
		var dLen [1]byte
		if _, err := io.ReadFull(client, dLen[:]); err != nil {
			return
		}
		domain := make([]byte, int(dLen[0]))
		if _, err := io.ReadFull(client, domain); err != nil {
			return
		}
		host = string(domain)
	case atypIPv6:
		var ip [16]byte
		if _, err := io.ReadFull(client, ip[:]); err != nil {
			return
		}
		host = net.IP(ip[:]).String()
	default:
		_, _ = client.Write([]byte{socks5Version, repAtypNotSupported, 0x00, atypIPv4, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(client, portBuf[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf[:])
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))

	dialCtx, dialCancel := context.WithTimeout(s.ctx, 20*time.Second)
	remote, err := s.dial(dialCtx, "tcp", target)
	dialCancel()
	if err != nil {
		_, _ = client.Write([]byte{socks5Version, repGeneralFailure, 0x00, atypIPv4, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	stopRemoteWatch := context.AfterFunc(s.ctx, func() {
		_ = remote.Close()
	})
	defer stopRemoteWatch()

	// Clear handshake deadline once tunnel is established.
	_ = client.SetDeadline(time.Time{})

	if _, err := client.Write([]byte{socks5Version, repSuccess, 0x00, atypIPv4, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	var pumpWG sync.WaitGroup
	pumpWG.Add(2)
	go func() {
		defer pumpWG.Done()
		_, _ = io.Copy(remote, client)
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}()
	go func() {
		defer pumpWG.Done()
		_, _ = io.Copy(client, remote)
		if cw, ok := client.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}()
	pumpWG.Wait()
}
