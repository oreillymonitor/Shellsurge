//go:build android

package tsbridge

import "tailscale.com/net/netmon"

func updateAndroidDefaultRoute(ifName, gatewayIP string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	netmon.UpdateLastKnownDefaultGateway(gatewayIP)
}
