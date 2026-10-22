package com.brucezhang.bydautosign.ipc;

import com.brucezhang.bydautosign.ipc.IHookEndpoint;

// Registration is restricted to the actual com.tencent.mm UID by the service.
interface IHookBroker {
    void registerHook(IHookEndpoint endpoint, int protocolVersion);
    void unregisterHook(IHookEndpoint endpoint);
}
