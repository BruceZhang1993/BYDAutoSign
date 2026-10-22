package com.brucezhang.bydautosign.ipc;

// POC A: non-sensitive health checks only. No login codes are transferred.
import com.brucezhang.bydautosign.ipc.ICodeResult;

interface IHookEndpoint {
    String ping();
    void requestLoginCode(String requestId, String appId, ICodeResult callback);
}
