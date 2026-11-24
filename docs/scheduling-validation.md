# P0/P1 scheduled sign validation

The exact alarm starts a short-lived dataSync foreground service directly. If the system refuses the launch or promotion, a uniquely ticketed WorkManager request is used instead. WorkManager remains the 30-minute best-effort watchdog. Android can delay both inexact alarms and watchdog work; force-stop and vendor background policies are not bypassed.

## Device acceptance

1. Enable exact alarms and automatic signing; use Settings > Background execution to check battery optimization and vendor auto-start/background settings.
2. Start the two-minute lock-screen test, lock the device, and inspect schedule history: TEST_REGISTERED → TEST_ALARM_FIRED → TEST_SERVICE_STARTED → TEST_NETWORK_READY or TEST_NETWORK_UNAVAILABLE. No sign or authorization request is made by the probe.
3. With a valid session, select a daily time a few minutes ahead and lock the phone. Verify ALARM_REGISTERED, ALARM_FIRED, SERVICE_REQUESTED/STARTED, EXECUTION_STARTED/FINISHED, and result history.
4. Deny exact alarm permission and repeat. The alarm may be delayed; service launch rejection must fall back to WorkManager.
5. Disable network during an attempt, then restore it. Check same-day hourly retry and no sign before the next day's configured slot.
6. Reboot after the daily anchor without a successful sign; check missed-first-slot recovery. Repeat with a completed sign; no duplicate request or result notification.
7. Compare normal lock-screen behavior with forced Doze. Force-stop is a known limitation, not a supported wake mechanism.
8. Export the diagnostic JSON; it must contain typed events and permission state but no raw credentials or server responses.

The probe checks Android's validated network capability, not BYD endpoint reachability. Business requests retain the existing bounded HTTP call timeouts. The foreground notification and timed partial wake lock are released after execution.
