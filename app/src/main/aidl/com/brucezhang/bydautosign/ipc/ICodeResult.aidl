package com.brucezhang.bydautosign.ipc;

// A code result must be consumed once, solely in an explicit authorization request.
oneway interface ICodeResult {
    void onResult(String requestId, int status, String code);
}
