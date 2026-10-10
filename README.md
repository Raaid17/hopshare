# HopShare

An open-source, no-root alternative to NetShare. It shares your Android phone's mobile data with laptops, tablets, and other phones over Wi-Fi Direct.

## How it works

1. The phone creates a **Wi-Fi Direct group**. Other devices see it as an ordinary Wi-Fi network (`DIRECT-hs-HopShare-xxxx`).
2. The phone runs an **HTTP/HTTPS proxy** on the group address (normally `192.168.49.1:8282`).
3. Client devices join the network and set that proxy. The phone opens every outbound connection itself and resolves DNS itself, so traffic goes out over its mobile data.

Because no root or system tethering is involved, it works on stock phones. The trade-off is that only proxy-aware traffic is shared.

| Works | Doesn't work |
|---|---|
| Browsers, most desktop/mobile apps, package managers, `git`, `curl`, Steam/Store downloads that honour the system proxy | UDP (most games, video calls on some apps, QUIC — browsers fall back to TCP), VPN clients, ping/ICMP, apps that ignore proxy settings |

## Build

Requirements: Android Studio Ladybug or newer (or JDK 17 + Android SDK 35).

```bash
# Generate the Gradle wrapper once (Android Studio does this automatically on import)
gradle wrapper --gradle-version 8.11.1

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The minimum supported version is Android 10 (API 29). That version added `WifiP2pConfig.Builder`, which lets the app set a stable network name and password.

## Using it

**On the phone:** turn mobile data on, and turn Wi-Fi on (it doesn't need to be connected to a network). Turn off the phone's own hotspot. Then open HopShare and tap **Start sharing**. The network name and password stay the same between sessions until you tap *New network password*.

**On the client**, join the network, then set the proxy:

| Device | Where |
|---|---|
| Windows 10/11 | Settings → Network & internet → Proxy → *Manual proxy setup* → `192.168.49.1`, port `8282` |
| macOS | System Settings → Wi-Fi → Details on the network → Proxies → enable **Web proxy (HTTP)** and **Secure web proxy (HTTPS)** → `192.168.49.1:8282` |
| Android | Long-press the network → Modify → Advanced → Proxy: Manual → `192.168.49.1` / `8282` |
| iPhone/iPad | Wi-Fi → ⓘ on the network → Configure Proxy → Manual → `192.168.49.1` / `8282` |
| Linux shell | `export http_proxy=http://192.168.49.1:8282 https_proxy=$http_proxy` |

Any client can also use the auto-config URL `http://192.168.49.1:8282/proxy.pac`. Opening `http://192.168.49.1:8282/` in a browser on a joined device shows a status page.

## Project layout

```
app/src/main/java/dev/raaid/hopshare/
├── MainActivity.kt   UI, permission flow, renders HopState
├── HopService.kt     Foreground service: Wi-Fi Direct group, wake/Wi-Fi locks, notification
├── ProxyServer.kt    Thread-per-connection HTTP proxy (CONNECT tunnel + absolute-form HTTP + PAC)
├── Credentials.kt    Persisted DIRECT-xy network name and passphrase
└── HopState.kt       Idle / Starting / Running / Failed
```

## Known limitations / ideas

- **Band:** the group uses `GROUP_OWNER_BAND_AUTO`. Some older laptops only see 2.4 GHz groups; to force 2.4 GHz, switch to `GROUP_OWNER_BAND_2GHZ` in `HopService.createGroup()`.
- **Concurrent hotspot:** most phones can't run the system hotspot and a Wi-Fi Direct group at the same time.
- **Android 10–12:** Location services must be turned on for the group to be created.
- **Not yet built:** a client-side VPN app (as NetShare offers) that routes *all* of a client Android's traffic through the proxy, and a SOCKS5 listener for apps that only speak SOCKS.
