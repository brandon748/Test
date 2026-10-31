# News AdBlock (Android)

Blocks ads and trackers in the in-app browser Google News opens for articles.

## Why it's built this way
Google News opens articles in **Chrome Custom Tabs**. Custom Tabs can't run
extensions, so a browser extension can't block ads there. This app is a
**local DNS filter** instead. It uses Android's VPN API, but only DNS lookups
go through it. When a page asks for an ad or tracker domain, the app answers
"no such domain", so the ad never loads. All other traffic goes straight to the
internet and nothing leaves the phone except normal DNS lookups to 1.1.1.1
(falling back to 8.8.8.8 or 9.9.9.9).

The blocklist is [StevenBlack/hosts](https://github.com/StevenBlack/hosts)
(about 80k ad/tracking domains). It's bundled in the APK, and you can refresh it
in the app with **Update blocklist**.

## Install
1. On your phone, open this repo's **Releases → latest** and tap `news-adblock-claude.apk`.
   (Or download the `news-adblock-claude-apk` artifact from the Actions run.)
2. Allow your browser to "install unknown apps" when Android asks.
3. Open **News AdBlock**, tap **Turn on**, and accept the VPN prompt.
4. Tap **Open VPN settings**, tap the gear next to News AdBlock and turn on
   **Always-on VPN** so it restarts after a reboot.

## Required phone settings
- **Settings → Network & internet → Private DNS:** set to *Off* or *Automatic*.
  If it names a provider hostname, DNS bypasses the blocker.
- **Chrome → Settings → Privacy and security → Use secure DNS:** choose
  *With your current service provider* or turn it off. A custom provider
  bypasses the blocker. Custom Tabs use Chrome's settings.

## Limits
- Ads served from the same domain as the article (first-party) can't be
  blocked by DNS. Most news-site ads come from third-party ad networks, which
  this does block.
- Only one VPN can run at a time on Android. If you use another VPN, this one
  turns off.
- If a site breaks, add its domain to the in-app allowlist.

## Build
CI (`.github/workflows/build-apk-claude.yml`) builds on every push. Locally,
with the Android SDK installed:
```
mkdir -p src/main/assets
curl -fsSL https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts -o src/main/assets/hosts-claude.txt
gradle --settings-file settings-claude.gradle assembleRelease
```
The build uses a custom settings filename (`settings-claude.gradle`), so the
`--settings-file` flag is required.
