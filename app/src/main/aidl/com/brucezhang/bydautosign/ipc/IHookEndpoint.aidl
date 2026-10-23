package com.brucezhang.bydautosign.ipc;

import com.brucezhang.bydautosign.ipc.ICodeResult;

// All methods are callable exclusively by the module's verified UID.
interface IHookEndpoint {
    String ping();
    String getHookStatus();
    void triggerProbe();
    void requestLoginCode(String requestId, String appId, ICodeResult callback);
}
