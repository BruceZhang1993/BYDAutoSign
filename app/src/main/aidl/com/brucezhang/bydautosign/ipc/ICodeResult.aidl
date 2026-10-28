package com.brucezhang.bydautosign.ipc;

// A one-time code must be consumed immediately in one manual or opted-in automatic refresh.
oneway interface ICodeResult {
    void onResult(String requestId, int status, String code);
}
