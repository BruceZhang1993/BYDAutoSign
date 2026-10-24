# BYD Auto Sign

**Your daily BYD check-in, taken care of.**

BYD Auto Sign is an unofficial Android app that automatically checks in to the BYD WeChat mini program for you. Authorize it once, choose your preferred times, and check your results without opening WeChat every day.

**English** | [简体中文](README.zh-CN.md)

## Features

- **Automatic daily check-ins:** Choose when to check in, with up to eight times per day.
- **One-tap check-in:** Check in manually from the home screen.
- **Check-in history:** See recent results and understandable failure messages.
- **Result notifications:** Get notified after a first successful check-in or a final failure, without alerts for repeated completed check-ins.
- **Troubleshooting tools:** View scheduling information and export a privacy-conscious diagnostic report.
- **A clean interface:** Supports your phone's light and dark appearance.

After you authorize the app, it can check in using the authorization saved on your phone. WeChat does not need to remain open.

## Requirements

- An Android phone running **Android 8.0 or later**.
- **WeChat** with access to the BYD mini program.
- A working **LSPosed** environment.

**Does it need Root?** The app does not run Root commands for daily check-ins, but setting up LSPosed may require Root or another compatible system-level solution. Installing the APK alone is not enough.

Currently only the **main WeChat installation** is supported. Cloned WeChat apps and multiple BYD accounts are not supported yet.

## Installation

1. Open the [latest GitHub Release](https://github.com/BruceZhang1993/BYDAutoSign/releases/latest) and download the signed `BYDAutoSign-v*.apk` under **Assets**.
2. Install the APK on your Android phone. Allow installation from the selected app if Android asks.
3. In **LSPosed**, enable the **BYD Auto Sign** module and select **WeChat** in the module's scope.
4. Fully close and restart WeChat to apply the module.

Upgrading from an older Root-based check-in tool? Please read [Upgrading](#upgrading) first.

## How to use

### 1. Authorize once

Open BYD Auto Sign and tap **Initialize & Authorize** (shown as **初始化并授权** in the app). WeChat may be opened during this step. After authorization succeeds, you do not need to repeat it every day.

### 2. Check in now

On the home screen, tap **Check in now** (**立即签到**) to check in manually. The home screen shows whether today's check-in is complete. If it is already complete, the app will not submit another check-in.

### 3. Schedule daily check-ins

Open **Settings** (**设置**), enable daily automatic check-in and tap **Add time** to choose a time using the 24-hour system time picker. You can edit or remove individual times (up to **8**), then tap **Save changes** (**保存更改**). The home screen shows the next planned time.

If your phone offers **exact alarm permission**, enabling it can reduce delays. Some phones may also need fewer battery or background restrictions for reliable scheduling.

### 4. View results

The home screen shows recent check-ins and schedule events separately. Open **Check-in history** (**签到记录**) and switch to **Schedule triggers** (**调度触发**) to confirm an alarm actually fired, work was queued, or an already-completed check-in was safely skipped. A skipped trigger does not submit a second check-in. Use **Diagnostics** (**运行诊断**) for detailed status and redacted exports.

Enable notification permission if you want result alerts. Disabling notifications does not disable check-ins.

> Android battery-saving rules, network conditions and manufacturer restrictions may delay automatic check-ins. The app attempts to recover missed runs, but cannot promise execution at an exact minute.

## Frequently asked questions

**Do I need Root?**  
Daily check-ins themselves do not use Root. A functioning LSPosed environment is still required for WeChat authorization and may require Root to set up.

**Must WeChat stay running in the background?**  
No. WeChat is needed for initial authorization or reauthorization, not for everyday scheduled check-ins.

**Authorization says WeChat is not connected. What should I do?**  
Make sure WeChat is installed, the module is enabled in LSPosed, WeChat is selected in its scope and WeChat has been fully restarted. Open WeChat once, then try authorizing again. Some WeChat versions may not yet be compatible.

**Why didn't a check-in happen at exactly the scheduled time?**  
Check that automatic check-in is enabled, a next run is shown, the network works and your phone is not blocking background activity. Grant exact alarm permission if available. Use **Diagnostics** to see the latest recorded events. Android cannot guarantee exact execution times.

**Why do I need to authorize again?**  
Saved authorization can expire or be lost after clearing app data or reinstalling. Open Settings and tap **Reauthorize** (**重新授权**). The app does not require you to type in your BYD account password.

**Will I keep getting notifications if today is already complete?**  
No. The app skips completed check-ins and does not notify you just because it retries a task. With notifications enabled, it alerts you to a first success or final failure.

**Can I use cloned WeChat or multiple BYD accounts?**  
Not at present. Support for these scenarios is planned for future versions.

**Where is my authorization stored?**  
It is encrypted on this phone. There is no cloud account sync. Do not share authentication information or unredacted logs when reporting an issue.

## Upgrading

If you used an earlier **Root-based** check-in setup:

1. **Disable the old automatic check-in task** using your Root management tool. The new app cannot remove it automatically, and leaving both running may cause duplicate tasks.
2. Install the new APK and **authorize again**. Old authorization data cannot be imported automatically.
3. Confirm your preferred schedule in Settings.

## Help and feedback

If something goes wrong, check **Check-in history** and **Diagnostics** first. In an issue report, include your phone model, Android version, steps to reproduce and, if helpful, an **exported redacted diagnostic report**. Never post authorization tokens or raw server responses.

[Report an issue](https://github.com/BruceZhang1993/BYDAutoSign/issues) · [Project roadmap](docs/ROADMAP.md)

---

BYD Auto Sign is an **unofficial community project** and is not affiliated with BYD or WeChat. Features rely on third-party services and may change when those services are updated.

Thanks to [wxcode](https://github.com/xiaojinwen/wxcode) and [byd_sign](https://github.com/xiaojinwen/byd_sign). Licensing details: [LICENSE](LICENSE) and [third-party notices](third_party/).
