package xiaojw.hook;

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

import com.brucezhang.bydautosign.ipc.IHookBroker;
import com.brucezhang.bydautosign.ipc.IHookEndpoint;
import com.brucezhang.bydautosign.ipc.IpcBrokerService;

/** POC A: an injected WeChat process binds the actual service in the module APK. */
public final class HookIpcClient {
    private static final String TAG = "BYDAutoSign-IPC";
    private static final String APP_PACKAGE = "com.brucezhang.bydautosign";
    private static final String SERVICE = APP_PACKAGE + ".ipc.IpcBrokerService";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean bound = false;
    private boolean retryScheduled = false;
    private volatile IHookBroker broker;

    private final IHookEndpoint.Stub endpoint = new IHookEndpoint.Stub() {
        @Override
        public String ping() {
            int uid = Binder.getCallingUid();
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) throw new SecurityException("Unknown IPC caller");
            for (String name : packages) {
                if (APP_PACKAGE.equals(name)) return "pong";
            }
            throw new SecurityException("Ping denied to non-module caller");
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

    public HookIpcClient(Context context) {
        // Application.attach may run before getApplicationContext() is ready.
        this.context = context;
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
