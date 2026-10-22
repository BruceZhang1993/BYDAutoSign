package com.brucezhang.bydautosign.ipc;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

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
        boolean ownsPackage = false;
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String name : packages) {
                if (WECHAT_PACKAGE.equals(name)) {
                    ownsPackage = true;
                    break;
                }
            }
        }
        // This is a same-Android-user POC. A cloned user requires a distinct
        // cross-user design; do not silently treat its UID as the owner UID.
        try {
            int installedUid = getPackageManager().getApplicationInfo(WECHAT_PACKAGE, 0).uid;
            if (!ownsPackage || installedUid != uid) {
                throw new SecurityException("Caller is not the installed WeChat UID");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("WeChat package is not visible to Broker", error);
        }
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
            int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            return "已注册 · User " + userId + " · Ping/Pong 正常";
        } catch (RemoteException e) {
            synchronized (service.lock) {
                if (service.endpoint == callback) service.forgetLocked();
            }
            return "Binder 已断开，等待微信重新注册";
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
