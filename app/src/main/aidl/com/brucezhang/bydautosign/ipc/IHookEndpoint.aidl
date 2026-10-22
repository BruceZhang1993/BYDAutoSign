package com.brucezhang.bydautosign.ipc;

// POC A: non-sensitive health checks only. No login codes are transferred.
interface IHookEndpoint {
    String ping();
}
