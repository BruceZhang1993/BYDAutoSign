package com.brucezhang.bydautosign.ipc;

import android.app.Service;
import android.content.Intent;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Looper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * POC A: WeChat's injected LSPosed hook binds this module-owned service and
 * registers a callback Binder. Never rely on an unauthenticated caller-supplied
 * package name or user ID. The service itself does not initiate authentication.
 */
public final class IpcBrokerService extends Service {
    public static final int PROTOCOL_VERSION = 1;
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static volatile IpcBrokerService running;

    private final Object lock = new Object();
    private IHookEndpoint endpoint;
    private IBinder.DeathRecipient deathRecipient;
    private int registeredUid = -1;

    private final IHookBroker.Stub binder = new IHookBroker.Stub() {
        @Override
        public void registerHook(IHookEndpoint callback, int protocolVersion) throws RemoteException {
            final int uid = Binder.getCallingUid();
            assertWeChatCaller(uid);
            if (protocolVersion != PROTOCOL_VERSION || callback == null) {
                throw new IllegalArgumentException("Unsupported IPC protocol or null endpoint");
            }
            final IBinder remoteBinder = callback.asBinder();
            synchronized (lock) {
                forgetLocked();
                final IBinder.DeathRecipient recipient = () -> {
                    synchronized (lock) {
                        if (endpoint != null && endpoint.asBinder() == remoteBinder) {
                            forgetLocked();
                        }
                    }
                };
                remoteBinder.linkToDeath(recipient, 0);
                endpoint = callback;
                registeredUid = uid;
                deathRecipient = recipient;
            }
        }

        @Override
        public void unregisterHook(IHookEndpoint callback) {
            int uid = Binder.getCallingUid();
            assertWeChatCaller(uid);
            synchronized (lock) {
                if (endpoint != null && callback != null &&
                        registeredUid == uid && endpoint.asBinder() == callback.asBinder()) {
                    forgetLocked();
                }
            }
        }
    };

    private void assertWeChatCaller(int uid) {
        // AppOps validates UID->package ownership in system_server even when
        // Android package-visibility filters would hide getPackagesForUid().
        if (!UserHandle.getUserHandleForUid(uid)
                .equals(UserHandle.getUserHandleForUid(android.os.Process.myUid()))) {
            throw new SecurityException("Cross-user Binder registration not yet supported");
        }
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) throw new SecurityException("AppOps unavailable");
        ops.checkPackage(uid, WECHAT_PACKAGE);
    }

    private void forgetLocked() {
        if (endpoint != null && deathRecipient != null) {
            endpoint.asBinder().unlinkToDeath(deathRecipient, 0);
        }
        endpoint = null;
        deathRecipient = null;
        registeredUid = -1;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Caller authentication happens on each AIDL transaction, not onBind:
        // onBind may be called only once for multiple different clients.
        return binder;
    }

    @Override
    public void onDestroy() {
        synchronized (lock) { forgetLocked(); }
        running = null;
        super.onDestroy();
    }

    /** Local application UI diagnostic; never passes the remote endpoint to callers. */
    public static String diagnostic() {
        IpcBrokerService service = running;
        if (service == null) return "未注册：Broker 未启动";
        IHookEndpoint callback;
        int uid;
        synchronized (service.lock) {
            callback = service.endpoint;
            uid = service.registeredUid;
        }
        if (callback == null) return "未注册：等待微信 Hook 连接";
        try {
            String reply = callback.ping();
            if (!"pong".equals(reply)) return "已注册，但 Ping 响应异常";
            UserHandle user = UserHandle.getUserHandleForUid(uid);
            return "已注册 · " + user + " · Ping/Pong 正常";
        } catch (RemoteException | SecurityException e) {
            synchronized (service.lock) {
                if (service.endpoint == callback) service.forgetLocked();
            }
            return "Binder 已断开，等待微信重新注册";
        }
    }

    /** Status is obtained over Binder, not over a localhost HTTP endpoint. */
    public static String readHookStatus() {
        IpcBrokerService service = running;
        if (service == null) return null;
        final IHookEndpoint hook;
        synchronized (service.lock) { hook = service.endpoint; }
        if (hook == null) return null;
        try {
            // Only allow a small status payload; the result must contain no secrets.
            String status = hook.getHookStatus();
            return status != null && status.length() <= 8192 ? status : null;
        } catch (RemoteException | SecurityException error) {
            return null;
        }
    }

    /** Non-blocking forced DEX probe, invoked only through the module UI. */
    public static boolean requestProbe() {
        IpcBrokerService service = running;
        if (service == null) return false;
        final IHookEndpoint hook;
        synchronized (service.lock) { hook = service.endpoint; }
        if (hook == null) return false;
        try {
            hook.triggerProbe();
            return true;
        } catch (RemoteException | SecurityException error) {
            return false;
        }
    }

    public static final class CodeResult {
        public final int status;
        public final String code;
        public CodeResult(int status, String code) {
            this.status = status;
            this.code = code;
        }
        public boolean successful() { return status == 0 && code != null && !code.isEmpty(); }
    }

    /**
     * Local-only API, invoked from BYDAutoSign's explicit authorization UI
     * on a worker thread. No public Binder method allows arbitrary clients
     * to trigger a login.
     */
    public static CodeResult obtainCode(String appId) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return new CodeResult(-600, "");
        }
        IpcBrokerService service = running;
        if (service == null) return new CodeResult(-601, "");
        final IHookEndpoint hook;
        final int trustedUid;
        synchronized (service.lock) {
            hook = service.endpoint;
            trustedUid = service.registeredUid;
        }
        if (hook == null) return new CodeResult(-601, "");

        final String requestId = UUID.randomUUID().toString();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<CodeResult> result = new AtomicReference<>(new CodeResult(-602, ""));
        ICodeResult.Stub response = new ICodeResult.Stub() {
            @Override
            public void onResult(String receivedId, int status, String code) {
                if (Binder.getCallingUid() != trustedUid || !requestId.equals(receivedId)) {
                    return;
                }
                // Never log one-time codes or returned secrets.
                result.set(new CodeResult(status,
                        status == 0 && code != null && code.length() <= 4096 ? code : ""));
                done.countDown();
            }
        };
        try {
            hook.requestLoginCode(requestId, appId, response);
            if (!done.await(75, TimeUnit.SECONDS)) return new CodeResult(-603, "");
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CodeResult(-604, "");
        } catch (RemoteException | SecurityException remoteError) {
            return new CodeResult(-605, "");
        }
    }

    public static boolean connected() {
        IpcBrokerService service = running;
        if (service == null) return false;
        synchronized (service.lock) {
            return service.endpoint != null && service.endpoint.asBinder().isBinderAlive();
        }
    }
}
