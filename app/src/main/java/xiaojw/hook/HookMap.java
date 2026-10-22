package xiaojw.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local, fingerprint-keyed map of verified structural discoveries.
 * No network requests; all APK/DEX access is made from WeChat's own process.
 */
final class HookMap {
    private static final String PREF = "byd_auto_sign_dynamic_hook";
    private static final String FINGERPRINT = "fingerprint";
    private static final String CONFIG = "mapping";
    private static final String VERIFIED = "code_verified";
    private HookMap() {}

    static String fingerprint(Context context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            add(digest, context.getPackageName() + "/" + info.lastUpdateTime);
            ApplicationInfo ai = context.getApplicationInfo();
            Set<String> paths = new LinkedHashSet<>();
            if (ai.sourceDir != null) paths.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) {
                for (String path : ai.splitSourceDirs) paths.add(path);
            }
            paths.addAll(DexProbe.runtimeDexPaths(context.getClassLoader()));
            for (String path : paths) {
                File file = new File(path);
                add(digest, path + ":" + file.length() + ":" + file.lastModified());
                if (file.isFile() && (path.endsWith(".apk") || path.endsWith(".zip") || path.endsWith(".jar"))) {
                    try (ZipFile zip = new ZipFile(file)) {
                        java.util.Enumeration<? extends ZipEntry> it = zip.entries();
                        while (it.hasMoreElements()) {
                            ZipEntry entry = it.nextElement();
                            if (entry.getName().endsWith(".dex")) {
                                add(digest, entry.getName() + ":" + entry.getCrc() + ":" + entry.getSize());
                            }
                        }
                    } catch (Exception ignored) {
                        // File metadata remains an invalidation signal when ZIP cannot be read.
                    }
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception error) {
            return ""; // Never cache results without a reliable fingerprint.
        }
    }

    private static void add(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static JSONObject load(Context context, String fingerprint) {
        if (fingerprint.isEmpty()) return null;
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!fingerprint.equals(pref.getString(FINGERPRINT, ""))) return null;
        String json = pref.getString(CONFIG, "");
        try { return json == null || json.isEmpty() ? null : new JSONObject(json); }
        catch (Exception ignored) { return null; }
    }

    static boolean codeVerified(Context context, String fingerprint) {
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return fingerprint.equals(pref.getString(FINGERPRINT, "")) && pref.getBoolean(VERIFIED, false);
    }

    static void save(Context context, String fingerprint, JSONObject config) {
        if (fingerprint.isEmpty()) return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(FINGERPRINT, fingerprint).putString(CONFIG, config.toString())
                .putBoolean(VERIFIED, false).apply();
    }

    static void markCodeVerified(Context context, String fingerprint) {
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!fingerprint.isEmpty() && fingerprint.equals(pref.getString(FINGERPRINT, ""))) {
            pref.edit().putBoolean(VERIFIED, true).apply();
        }
    }

    /**
     * Structural safety gate before applying an untrusted/heuristically discovered mapping.
     * It never executes the login chain or uses real account credentials.
     */
    static boolean validate(JSONObject config, ClassLoader cl, StringBuilder why) {
        if (config == null) return failure(why, "映射为空");
        try {
            String taskName = "com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask";
            if (cl.loadClass(taskName) == null) throw new ClassNotFoundException(taskName);
            Class<?> loginTask = cl.loadClass(taskName);
            Class<?> a1 = cl.loadClass(qualify(config.optString("a1")));
            Class<?> a7 = cl.loadClass(qualify(config.optString("a7")));
            Class<?> c = cl.loadClass(config.optString("c"));
            Class<?> submit = cl.loadClass(config.optString("j1"));
            if (!hasCtor(a1, loginTask, null)) return failure(why, "a1 缺少 LoginTask 单参构造");
            if (!hasCtor(a7, loginTask, a1)) return failure(why, "a7 缺少 (LoginTask,a1) 构造");
            if (!hasRequestCtor(c, a7)) return failure(why, "c 构造签名与 a7 不匹配");
            String staticName = config.optString("j1_static_method", "d");
            String instanceName = config.optString("j1_instance_method", "g");
            if (!hasSubmitChain(submit, c, staticName, instanceName)) {
                return failure(why, "任务提交链 " + staticName + "()." + instanceName + "(c) 不匹配");
            }
            // The existing LoginTask field map is still version-sensitive. Detect changes,
            // rather than treating a class-only match as a successful login.
            for (String name : new String[]{"o","p","s","v","t","u","A","r","q"}) {
                Field field = loginTask.getDeclaredField(name);
                if (field == null) return failure(why, "LoginTask 缺少字段 " + name);
            }
            return true;
        } catch (Throwable error) {
            return failure(why, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static boolean failure(StringBuilder why, String message) {
        if (why != null) why.append(message);
        return false;
    }
    private static String qualify(String name) {
        if (name.startsWith("com.tencent.mm.")) return name;
        return "com.tencent.mm." + name;
    }

    private static boolean hasCtor(Class<?> cls, Class<?> loginTask, Class<?> callback) {
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (callback == null && p.length == 1 && p[0].isAssignableFrom(loginTask)) return true;
            if (callback != null && p.length == 2 &&
                    p[0].isAssignableFrom(loginTask) && p[1].isAssignableFrom(callback)) return true;
        }
        return false;
    }

    private static boolean hasRequestCtor(Class<?> cls, Class<?> callback) {
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 8 && p[0].isAssignableFrom(String.class)
                    && p[1].isAssignableFrom(java.util.LinkedList.class)
                    && p[7].isAssignableFrom(callback)) return true;
        }
        return false;
    }

    private static boolean hasSubmitChain(Class<?> submit, Class<?> request,
                                         String staticMethod, String instanceMethod) {
        for (Method get : allMethods(submit)) {
            if (!get.getName().equals(staticMethod) || !Modifier.isStatic(get.getModifiers())
                    || get.getParameterTypes().length != 0) continue;
            Class<?> result = get.getReturnType();
            for (Method send : allMethods(result)) {
                Class<?>[] params = send.getParameterTypes();
                if (send.getName().equals(instanceMethod) && !Modifier.isStatic(send.getModifiers())
                        && params.length == 1 && params[0].isAssignableFrom(request)) return true;
            }
        }
        return false;
    }

    private static List<Method> allMethods(Class<?> cls) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            for (Method method : cur.getDeclaredMethods()) methods.add(method);
        }
        return methods;
    }
}
