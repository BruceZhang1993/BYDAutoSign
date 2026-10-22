package xiaojw.hook;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;
import com.brucezhang.bydautosign.ipc.ICodeResult;

import com.brucezhang.bydautosign.ipc.IHookBroker;
import com.brucezhang.bydautosign.ipc.IHookEndpoint;
import com.brucezhang.bydautosign.ipc.IpcBrokerService;

/** POC A: an injected WeChat process binds the actual service in the module APK. */
public final class HookIpcClient {
    private static final String TAG = "BYDAutoSign-IPC";
    private static final String APP_PACKAGE = "com.brucezhang.bydautosign";
    private static final String SERVICE = APP_PACKAGE + ".ipc.IpcBrokerService";
    // The BYD mini-program identifier currently used by byd_sign.sh.
    private static final String AUTH_APP_ID = "wxf62054ec313d6f53";
    public interface LoginAction { String run(String appId); }
    public interface StatusAction { String getStatus(); void reprobe(); }
    private final LoginAction loginAction;
    private final StatusAction statusAction;
    private final AtomicBoolean loginInFlight = new AtomicBoolean(false);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean bound = false;
    private boolean retryScheduled = false;
    private volatile IHookBroker broker;

    private void requireModuleCaller() {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) throw new SecurityException("AppOps unavailable");
        ops.checkPackage(Binder.getCallingUid(), APP_PACKAGE);
    }

    private final IHookEndpoint.Stub endpoint = new IHookEndpoint.Stub() {
        @Override
        public String ping() {
            requireModuleCaller();
            return "pong";
        }

        @Override
        public String getHookStatus() {
            requireModuleCaller();
            return statusAction.getStatus();
        }

        @Override
        public void triggerProbe() {
            requireModuleCaller();
            statusAction.reprobe();
        }

        @Override
        public void requestLoginCode(String requestId, String appId, ICodeResult callback) {
            requireModuleCaller();
            if (callback == null || requestId == null || requestId.length() > 80
                    || !AUTH_APP_ID.equals(appId)) {
                throw new IllegalArgumentException("Invalid authorization request");
            }
            // There is no unsolicited login. Broker calls this only for an
            // explicit user action. Never block an incoming Binder thread.
            if (!loginInFlight.compareAndSet(false, true)) {
                try { callback.onResult(requestId, -100, ""); }
                catch (RemoteException ignored) { }
                return;
            }
            new Thread(() -> {
                int status = -500;
                String code = "";
                try {
                    JSONObject result = new JSONObject(loginAction.run(appId));
                    status = result.optInt("err", -500);
                    if (status == 0) {
                        code = result.optString("code", "");
                        if (code.isEmpty() || code.length() > 4096) {
                            status = -501;
                            code = "";
                        }
                    }
                    // Only the requested callback sees the one-time code.
                    callback.onResult(requestId, status, code);
                } catch (Exception error) {
                    try { callback.onResult(requestId, -500, ""); }
                    catch (RemoteException ignored) { }
                } finally {
                    code = "";
                    loginInFlight.set(false);
                }
            }, "byd-ipc-authorize").start();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            broker = IHookBroker.Stub.asInterface(service);
            new Thread(() -> {
                try {
                    broker.registerHook(endpoint, IpcBrokerService.PROTOCOL_VERSION);
                    Log.i(TAG, "Hook registered with Binder Broker");
                } catch (Exception error) {
                    Log.w(TAG, "Binder registration failed: " + error.getClass().getSimpleName());
                    scheduleRetry();
                }
            }, "byd-ipc-register").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            broker = null;
            scheduleRetry();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            broker = null;
            scheduleRetry();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            broker = null;
            scheduleRetry();
        }
    };

    public HookIpcClient(Context context, LoginAction loginAction, StatusAction statusAction) {
        // Application.attach may run before getApplicationContext() is ready.
        this.context = context;
        this.loginAction = loginAction;
        this.statusAction = statusAction;
    }

    public void start() {
        main.post(this::bindNow);
    }

    private void bindNow() {
        if (bound) return;
        Intent intent = new Intent();
        intent.setClassName(APP_PACKAGE, SERVICE);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) scheduleRetry();
        } catch (Exception error) {
            Log.w(TAG, "Cannot bind module IPC Broker: " + error.getClass().getSimpleName());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        main.post(() -> {
            if (retryScheduled) return;
            retryScheduled = true;
            main.postDelayed(() -> {
                retryScheduled = false;
                if (bound) {
                    try { context.unbindService(connection); }
                    catch (Exception ignored) { }
                    bound = false;
                }
                bindNow();
            }, 5000);
        });
    }
}
