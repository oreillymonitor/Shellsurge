//go:build android || linux

package tsbridge

/*
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

static int sw_ifa_family(struct ifaddrs *ifa) {
	if (ifa == NULL || ifa->ifa_addr == NULL) return AF_UNSPEC;
	return ifa->ifa_addr->sa_family;
}

static int sw_ifa_ipv4(struct ifaddrs *ifa, unsigned char *ip, unsigned char *mask) {
	if (ifa == NULL || ifa->ifa_addr == NULL || ifa->ifa_addr->sa_family != AF_INET) return 0;
	struct sockaddr_in *sin = (struct sockaddr_in *)ifa->ifa_addr;
	memcpy(ip, &sin->sin_addr, 4);
	if (ifa->ifa_netmask != NULL && ifa->ifa_netmask->sa_family == AF_INET) {
		struct sockaddr_in *nmask = (struct sockaddr_in *)ifa->ifa_netmask;
		memcpy(mask, &nmask->sin_addr, 4);
		return 2;
	}
	return 1;
}

static int sw_ifa_ipv6(struct ifaddrs *ifa, unsigned char *ip, unsigned char *mask) {
	if (ifa == NULL || ifa->ifa_addr == NULL || ifa->ifa_addr->sa_family != AF_INET6) return 0;
	struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)ifa->ifa_addr;
	memcpy(ip, &sin6->sin6_addr, 16);
	if (ifa->ifa_netmask != NULL && ifa->ifa_netmask->sa_family == AF_INET6) {
		struct sockaddr_in6 *nmask6 = (struct sockaddr_in6 *)ifa->ifa_netmask;
		memcpy(mask, &nmask6->sin6_addr, 16);
		return 2;
	}
	return 1;
}
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"net"
	"slices"
	"strings"
	"sync"
	"unsafe"

	"tailscale.com/net/netmon"
)

type androidInterfaceSpec struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Broadcast    bool     `json:"broadcast"`
	Multicast    bool     `json:"multicast"`
	Addrs        []string `json:"addrs"`
}

var (
	androidNetMu         sync.RWMutex
	androidInterfaces    []netmon.Interface
	androidDefaultIface  string
	androidDefaultGwIP   string
)

func init() {
	// Android 11+ blocks raw netlink RTM_GETLINK/RTM_GETADDR syscalls from unprivileged apps.
	// Prefer a ConnectivityManager/Java NetworkInterface snapshot pushed from Kotlin when available,
	// and fall back to Bionic getifaddrs via Cgo without stdlib linkname hacks.
	netmon.RegisterInterfaceGetter(listInterfaces)
}

// UpdateAndroidNetwork supplies interface and default-route metadata gathered via Android's
// ConnectivityManager / NetworkInterface APIs so netmon can track interface viability and
// default routes inside Android's sandbox.
func UpdateAndroidNetwork(defaultIface, gatewayIP, interfacesJSON string) {
	cleanIface := strings.TrimSpace(defaultIface)
	cleanGw := strings.TrimSpace(gatewayIP)
	parsed, inferredDefault := parseAndroidInterfacesJSON(interfacesJSON)
	if cleanIface == "" {
		cleanIface = inferredDefault
	}

	androidNetMu.Lock()
	if len(parsed) > 0 {
		androidInterfaces = parsed
	} else if strings.TrimSpace(interfacesJSON) == "[]" {
		androidInterfaces = nil
	}
	androidDefaultIface = cleanIface
	androidDefaultGwIP = cleanGw
	androidNetMu.Unlock()

	updateAndroidDefaultRoute(cleanIface, cleanGw)
}

func parseAndroidInterfacesJSON(raw string) ([]netmon.Interface, string) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, ""
	}
	var specs []androidInterfaceSpec
	if err := json.Unmarshal([]byte(trimmed), &specs); err != nil {
		return nil, ""
	}

	out := make([]netmon.Interface, 0, len(specs))
	var inferredDefault string
	for i, spec := range specs {
		name := strings.TrimSpace(spec.Name)
		if name == "" {
			continue
		}
		var flags net.Flags
		if spec.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if spec.Loopback || name == "lo" {
			flags |= net.FlagLoopback
		}
		if spec.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if spec.Broadcast {
			flags |= net.FlagBroadcast
		}
		if spec.Multicast {
			flags |= net.FlagMulticast
		}

		altAddrs := make([]net.Addr, 0, len(spec.Addrs))
		for _, cidr := range spec.Addrs {
			ip, ipNet, err := net.ParseCIDR(strings.TrimSpace(cidr))
			if err != nil || ipNet == nil {
				continue
			}
			altAddrs = append(altAddrs, &net.IPNet{
				IP:   ip,
				Mask: ipNet.Mask,
			})
		}

		mtu := spec.MTU
		if mtu <= 0 {
			mtu = 1500
		}
		idx := spec.Index
		if idx <= 0 {
			idx = i + 1
		}

		if inferredDefault == "" && spec.Up && !spec.Loopback && name != "lo" && len(altAddrs) > 0 {
			inferredDefault = name
		}

		out = append(out, netmon.Interface{
			Interface: &net.Interface{
				Index: idx,
				MTU:   mtu,
				Name:  name,
				Flags: flags,
			},
			AltAddrs: altAddrs,
		})
	}
	return out, inferredDefault
}

func listInterfaces() ([]netmon.Interface, error) {
	androidNetMu.RLock()
	cached := slices.Clone(androidInterfaces)
	androidNetMu.RUnlock()
	if len(cached) > 0 {
		return cached, nil
	}
	return listInterfacesViaGetIfAddrs()
}

func listInterfacesViaGetIfAddrs() ([]netmon.Interface, error) {
	var ifap *C.struct_ifaddrs
	rc, err := C.getifaddrs(&ifap)
	if rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(ifap)

	byName := make(map[string]*netmon.Interface)
	var order []string
	nextFallbackIdx := 1

	for ifa := ifap; ifa != nil; ifa = ifa.ifa_next {
		if ifa.ifa_name == nil {
			continue
		}
		name := C.GoString(ifa.ifa_name)
		entry, exists := byName[name]
		if !exists {
			idx := int(C.if_nametoindex(ifa.ifa_name))
			if idx == 0 {
				idx = nextFallbackIdx
				nextFallbackIdx++
			}
			entry = &netmon.Interface{
				Interface: &net.Interface{
					Index: idx,
					MTU:   1500,
					Name:  name,
					Flags: mapInterfaceFlags(uint(ifa.ifa_flags)),
				},
			}
			byName[name] = entry
			order = append(order, name)
		}

		switch C.sw_ifa_family(ifa) {
		case C.AF_INET:
			var ipBuf [4]byte
			var maskBuf [4]byte
			res := C.sw_ifa_ipv4(
				ifa,
				(*C.uchar)(unsafe.Pointer(&ipBuf[0])),
				(*C.uchar)(unsafe.Pointer(&maskBuf[0])),
			)
			if res > 0 {
				ip := net.IP(append([]byte(nil), ipBuf[:]...))
				mask := net.CIDRMask(32, 32)
				if res == 2 {
					mask = net.IPMask(append([]byte(nil), maskBuf[:]...))
				}
				entry.AltAddrs = append(entry.AltAddrs, &net.IPNet{IP: ip, Mask: mask})
			}
		case C.AF_INET6:
			var ipBuf [16]byte
			var maskBuf [16]byte
			res := C.sw_ifa_ipv6(
				ifa,
				(*C.uchar)(unsafe.Pointer(&ipBuf[0])),
				(*C.uchar)(unsafe.Pointer(&maskBuf[0])),
			)
			if res > 0 {
				ip := net.IP(append([]byte(nil), ipBuf[:]...))
				mask := net.CIDRMask(128, 128)
				if res == 2 {
					mask = net.IPMask(append([]byte(nil), maskBuf[:]...))
				}
				entry.AltAddrs = append(entry.AltAddrs, &net.IPNet{IP: ip, Mask: mask})
			}
		}
	}

	ret := make([]netmon.Interface, 0, len(order))
	for _, name := range order {
		entry := byName[name]
		// On Android 11+, Bionic getifaddrs() skips RTM_GETLINK for app UIDs, which can leave
		// ifa_flags without IFF_UP even when valid interface addresses were returned.
		if len(entry.AltAddrs) > 0 && entry.Flags&net.FlagUp == 0 {
			entry.Flags |= net.FlagUp | net.FlagRunning
		}
		if name == "lo" && entry.Flags&net.FlagLoopback == 0 {
			entry.Flags |= net.FlagLoopback
		}
		ret = append(ret, *entry)
	}
	return ret, nil
}

func mapInterfaceFlags(raw uint) net.Flags {
	var flags net.Flags
	if raw&C.IFF_UP != 0 {
		flags |= net.FlagUp
	}
	if raw&C.IFF_BROADCAST != 0 {
		flags |= net.FlagBroadcast
	}
	if raw&C.IFF_LOOPBACK != 0 {
		flags |= net.FlagLoopback
	}
	if raw&C.IFF_POINTOPOINT != 0 {
		flags |= net.FlagPointToPoint
	}
	if raw&C.IFF_MULTICAST != 0 {
		flags |= net.FlagMulticast
	}
	if raw&C.IFF_RUNNING != 0 {
		flags |= net.FlagRunning
	}
	return flags
}
