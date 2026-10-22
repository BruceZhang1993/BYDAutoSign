#!/system/bin/sh
# 比亚迪自动签到脚本

# 加载公共函数库
. "/data/adb/byd_sign/lib.sh"

# 配置常量
KEY="3993014457161851"
IV="PDVcDRWMrBlLHTqh"
APPKEY="hyMinaApi"
APPSECRET="Kfl%BOk6C5PwARw8"
BASE_URL="https://mina.bydoceanauto.com"
# AppID 分段存储，运行时拼接，避免明文
APPID_P1="wxf62054"
APPID_P2="ec313d6f53"
APPID="${APPID_P1}${APPID_P2}"

# 加载邮件配置（如果存在）
if [ -f "$EMAIL_CONF" ]; then
    . "$EMAIL_CONF"
fi

# == 重试机制

# 重试执行函数
# 参数: $1=重试次数, $2=重试间隔(秒), $3=要调用的函数名, $4...=函数参数
# 说明: 通过直接调用函数（而非动态拼接命令字符串）避免命令注入和双重解析风险。
retry_command() {
    local max_retries=$1
    local retry_interval=$2
    shift 2

    local retry_count=0
    local command_status
    RETRY_OUTPUT=""
    while [ "$retry_count" -lt "$max_retries" ]; do
        RETRY_OUTPUT=$("$@")
        command_status=$?
        [ "$command_status" -eq 0 ] && return 0

        # 只有可恢复的远端网络错误才重试。调用方返回的本地接口、解密和业务错误
        # 使用非网络错误码，因此会立即失败，避免重复请求和无意义等待。
        case "$command_status" in
            6|7|28|35|52|56)
                retry_count=$((retry_count + 1))
                if [ "$retry_count" -lt "$max_retries" ]; then
                    log "网络请求失败(${command_status})，${retry_interval}秒后重试 (第${retry_count}次，共${max_retries}次)"
                    sleep "$retry_interval"
                    continue
                fi
                ;;
            *)
                log "命令失败(${command_status})，错误不可重试"
                return "$command_status"
                ;;
        esac
    done

    log "网络请求失败，已达到最大重试次数 ${max_retries}"
    return "$command_status"
}

# 包装函数只负责输出结果；retry_command 会安全地捕获 stdout，不进行二次命令解析。
_get_session_id_checked() {
    get_session_id "$1" "$2"
}

_do_sign_checked() {
    do_sign "$1" "$2"
}

# == 签到状态管理（_is_signed_today 定义在 lib.sh 中）

save_sign_success() {
    local port="$1"
    local today=$(date '+%Y-%m-%d')
    local status_file="$SIGN_STATUS_DIR/${port}.txt"
    mkdir -p "$SIGN_STATUS_DIR"
    echo "$today" > "$status_file"
    log "[端口 ${port}] 已记录签到日期: $today"
}

# == 日志清理

clean_old_logs() {
    if [ ! -d "$LOG_DIR" ]; then
        return
    fi
    
    local current_timestamp=$(date +%s)
    local delete_days_seconds=$((3 * 24 * 60 * 60))
    local count=0
    
    for log_file in "$LOG_DIR"/*.log; do
        [ -f "$log_file" ] || continue
        
        local file_timestamp=$(stat -c %Y "$log_file" 2>/dev/null || stat -f %m "$log_file" 2>/dev/null)
        
        if [ -n "$file_timestamp" ]; then
            local age=$((current_timestamp - file_timestamp))
            if [ "$age" -gt "$delete_days_seconds" ]; then
                rm -f "$log_file"
                count=$((count + 1))
            fi
        fi
    done
    
    if [ "$count" -gt 0 ]; then
        log "已清理 $count 个超过3天的日志文件"
    fi
    
    local cron_log="$LOG_DIR/cron.log"
    local max_size=$((1024 * 1024))
    if [ -f "$cron_log" ]; then
        local file_size=$(stat -c %s "$cron_log" 2>/dev/null || stat -f %z "$cron_log" 2>/dev/null)
        if [ -n "$file_size" ] && [ "$file_size" -gt "$max_size" ]; then
            > "$cron_log"
            log "cron.log 超过 1MB，已清空"
        fi
    fi
}

# == AES-128-CBC 加密/解密

aes_encrypt() {
    local data="$1"
    local key="$2"
    local iv="$3"
    
    # 将密钥和 IV 转换为十六进制
    local key_hex=$(echo -n "$key" | xxd -p | tr -d '\n')
    local iv_hex=$(echo -n "$iv" | xxd -p | tr -d '\n')
    
    # 使用 openssl 加密，并自动进行 PKCS7 填充
    echo -n "$data" | $OPENSSL_CMD enc -aes-128-cbc -K "$key_hex" -iv "$iv_hex" -base64 -A
}

aes_decrypt() {
    local data="$1"
    local key="$2"
    local iv="$3"
    
    # 将密钥和 IV 转换为十六进制
    local key_hex=$(echo -n "$key" | xxd -p | tr -d '\n')
    local iv_hex=$(echo -n "$iv" | xxd -p | tr -d '\n')
    
    # 使用 openssl 解密
    echo "$data" | $OPENSSL_CMD enc -d -aes-128-cbc -K "$key_hex" -iv "$iv_hex" -base64 -A
}

# == SHA256 计算

calculate_checksum() {
    local app_secret="$1"
    local nonce="$2"
    local curtime="$3"
    
    printf "%s%s%s" "$app_secret" "$nonce" "$curtime" | sha256sum | cut -d' ' -f1
}

# == 网络请求

random_str() {
    local len=${1:-16}
    local chars="ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    local result=""
    for i in $(seq 1 $len); do
        local index=$(($RANDOM % ${#chars}))
        result="${result}${chars:$index:1}"
    done
    echo "$result"
}

generate_clienttraceid() {
    local hex=""
    for i in $(seq 1 32); do
        hex="${hex}$(printf '%x' $(($RANDOM % 16)))"
    done
    hex="${hex:0:14}4${hex:15}"
    local pos19=$(printf '%x' $((0x${hex:19:1} | 0x8)))
    hex="${hex:0:19}${pos19}${hex:20}"
    local result="mina-${hex:0:8}-${hex:8:4}-${hex:12:4}-${hex:16:4}-${hex:20:12}"
    echo "$result"
}

send_request() {
    local url="$1"
    local encrypted_data="$2"
    local nonce="$3"
    local curtime="$4"
    local checksum="$5"
    local curl_bin
    local clienttraceid

    # 所有网络请求统一使用模块随架构安装的 curl。不要直接调用 PATH 中的
    # curl：cron、WebUI 和 MT 管理器的 PATH 不同，会导致实际 TLS/DNS/压缩
    # 行为不一致。
    curl_bin=$(get_curl_bin)
    if [ -z "$curl_bin" ] || { [ "$curl_bin" != "curl" ] && [ ! -x "$curl_bin" ]; }; then
        log "错误: 模块 curl 不可用: ${curl_bin:-未找到}"
        return 127
    fi

    clienttraceid=$(generate_clienttraceid)
    # 明确要求不压缩。此前手动声明支持 gzip/deflate/br，但未解压响应，模块 curl
    # 收到压缩内容后会把二进制数据传给 AES，最终被误报成“无法解密 session”。
    # 不使用 --compressed，以兼容部分裁剪版 curl 未编入 brotli/zlib 的情况。
    # --noproxy '*' 防止 WebUI/cron 继承代理变量；-4 避免部分 ROM 的 IPv6
    # 路由异常。两者均只影响本次请求。
    local response
    response=$("$curl_bin" -sS --insecure --noproxy "*" -4 \
        --connect-timeout 15 --max-time 45 \
        -X POST "$url" \
        -H "Content-Type: application/json" \
        -H "X-Clienttraceid: $clienttraceid" \
        -H "Nonce: $nonce" \
        -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) UnifiedPCWindowsWechat(0xf2541a1b) XWEB/19895" \
        -H "Curtime: $curtime" \
        -H "Xweb_xhr: 1" \
        -H "Checksum: $checksum" \
        -H "Appkey: $APPKEY" \
        -H "Accept: */*" \
        -H "Accept-Encoding: identity" \
        -H "Sec-Fetch-Site: cross-site" \
        -H "Sec-Fetch-Mode: cors" \
        -H "Sec-Fetch-Dest: empty" \
        -H "Referer: https://servicewechat.com/$APPID/142/page-frame.html" \
        -H "Accept-Language: zh-CN,zh;q=0.9" \
        -H "Priority: u=1, i" \
        -H "Connection: keep-alive" \
        --data-raw "$encrypted_data")
    local curl_ret=$?

    if [ $curl_ret -ne 0 ]; then
        log "[curl错误] 请求失败，退出码=${curl_ret}，URL=${url}"
        return $curl_ret
    fi

    echo "$response"
}

# get_code_api_ports 已定义在 lib.sh 中，本脚本直接使用

get_session_id() {
    local code="$1"
    local port="$2"
    
    log "[端口 ${port}] 正在通过code获取session_id..."
    
    local nonce=$(random_str 16)
    local curtime=$(date +%s)
    local checksum=$(calculate_checksum "$APPSECRET" "$nonce" "$curtime")
    
    local request_data="{\"code\":\"$code\"}"
    local encrypted_data=$(aes_encrypt "$request_data" "$KEY" "$IV")
    
    local url="${BASE_URL}/?service=mina.decryptCode"
    local response
    response=$(send_request "$url" "$encrypted_data" "$nonce" "$curtime" "$checksum")
    local request_ret=$?
    if [ "$request_ret" -ne 0 ]; then
        log "[端口 ${port}] 错误: 请求失败，无法获取session_id"
        return "$request_ret"
    fi
    
    local decrypted
    decrypted=$(aes_decrypt "$response" "$KEY" "$IV")
    local decrypt_ret=$?
    
    if [ "$decrypt_ret" -ne 0 ] || [ -z "$decrypted" ]; then
        log "[端口 ${port}] 无法解密响应，原始响应: $response"
        return 66
    fi
    
    log "[端口 ${port}] Session 响应已接收（已隐藏敏感内容）"
    
    local session_id=$(echo "$decrypted" | grep -o '"session_id"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/"session_id"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/')
    
    if [ -z "$session_id" ]; then
        log "[端口 ${port}] 错误: 无法从响应中解析session_id"
        return 66
    fi
    
    log "[端口 ${port}] 获取到session_id（已隐藏敏感内容）"
    echo "$session_id"
    return 0
}

do_sign() {
    local session_id="$1"
    local port="$2"
    
    log "[端口 ${port}] 开始签到..."
    
    local nonce=$(random_str 16)
    local curtime=$(date +%s)
    local checksum=$(calculate_checksum "$APPSECRET" "$nonce" "$curtime")
    
    local request_data="{\"date\":\"\",\"belong_brand\":\"hy\",\"session_id\":\"$session_id\",\"app_version\":\"460\",\"app_client\":\"mina\"}"
    local encrypted_data=$(aes_encrypt "$request_data" "$KEY" "$IV")
    
    local url="${BASE_URL}/?s=ForCommonUcSrv.forward&serviceDir=activity/sign/signIn"
    local response
    response=$(send_request "$url" "$encrypted_data" "$nonce" "$curtime" "$checksum")
    local request_ret=$?
    if [ "$request_ret" -ne 0 ]; then
        log "[端口 ${port}] 错误: 请求失败，无法签到"
        echo "FAILED|网络请求失败"
        return "$request_ret"
    fi
    
    local decrypted
    decrypted=$(aes_decrypt "$response" "$KEY" "$IV")
    local decrypt_ret=$?
    
    if [ "$decrypt_ret" -eq 0 ] && [ -n "$decrypted" ]; then
        log "[端口 ${port}] 签到响应已接收（已隐藏敏感内容）"
        if echo "$decrypted" | grep -q '"ret"[[:space:]]*:[[:space:]]*200'; then
            local duration_days=$(echo "$decrypted" | grep -o '"durationDays"[[:space:]]*:[[:space:]]*[0-9]*' | grep -o '[0-9]*$')
            if [ -n "$duration_days" ]; then
                log "[端口 ${port}] 签到成功！已连续签到 ${duration_days} 天"
                echo "SUCCESS|${duration_days}"
            else
                log "[端口 ${port}] 签到成功!"
                echo "SUCCESS|0"
            fi
            return 0
        else
            if printf '%s' "$decrypted" | grep -Eiq 'session[_ ]?id[^"]*(失效|过期|invalid|expired)|未登录|登录已过期|session expired|invalid session'; then
                log "[端口 ${port}] 服务器反馈会话失效，需重新授权"
                echo "FAILED|AUTH_EXPIRED"
                return 78
            fi
            log "[端口 ${port}] 签到失败（保留会话，响应已隐藏）"
            echo "FAILED|签到失败"
            return 1
        fi
    else
        log "[端口 ${port}] 无法解密响应，原始响应: $response"
        echo "FAILED|无法解密响应"
        return 66
    fi
}


# WeChat mini-program code is one-time. Persist only its exchanged BYD session_id.
SESSION_DIR="$WORK_DIR/sessions"

valid_port() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ "$1" -ge 1024 ] 2>/dev/null && [ "$1" -le 65535 ] 2>/dev/null
}

session_path() {
    valid_port "$1" || return 1
    printf '%s/%s.session\n' "$SESSION_DIR" "$1"
}

save_session() {
    local port="$1" value="$2" origin="${3:-unknown}" target temporary
    case "$origin" in binder|http|unknown) ;; *) origin="unknown" ;; esac
    target=$(session_path "$port") || return 1
    [ -n "$value" ] && [ "${#value}" -le 2048 ] || return 1
    if printf '%s' "$value" | LC_ALL=C grep -q '[[:cntrl:]]'; then return 1; fi
    umask 077
    mkdir -p "$SESSION_DIR" || return 1
    chmod 700 "$SESSION_DIR" || return 1
    temporary=$(mktemp "${target}.XXXXXX") || return 1
    (umask 077; printf '%s' "$value" > "$temporary") || return 1
    chmod 600 "$temporary" || return 1
    mv -f "$temporary" "$target" || return 1
    date '+%Y-%m-%d %H:%M:%S' > "${target}.at"
    chmod 600 "${target}.at"
    # Only provenance is stored; never write code or session text to metadata.
    if (umask 077; printf '%s' "$origin" > "${target}.via"); then
        chmod 600 "${target}.via" || true
    else
        rm -f "${target}.via"
    fi
    return 0
}

load_session() {
    local target
    target=$(session_path "$1") || return 1
    [ -f "$target" ] && [ ! -L "$target" ] && [ -s "$target" ] || return 1
    cat "$target"
}

forget_session() {
    local target
    target=$(session_path "$1") || return 1
    rm -f "$target" "${target}.at" "${target}.via"
}

session_status() {
    local port target at origin source
    for port in $(get_code_api_ports); do
        valid_port "$port" || continue
        target=$(session_path "$port") || continue
        if [ -s "$target" ] && [ ! -L "$target" ]; then
            at=$(cat "${target}.at" 2>/dev/null)
            origin=$(cat "${target}.via" 2>/dev/null)
            case "$origin" in
                binder) source="Binder IPC" ;;
                http) source="旧 HTTP" ;;
                *) source="来源未知" ;;
            esac
            printf '端口 %s：已初始化会话（%s；%s；有效期由服务端决定）\n' "$port" "${at:-时间未知}" "$source"
        else
            printf '端口 %s：未授权或会话已失效，需要重新授权\n' "$port"
        fi
    done
}

# Experimental Binder handoff: input is a one-time code from stdin, never argv.
# This path may only be invoked by the user-initiated app authorization action.
authorize_from_stdin() {
    local port="$1" code="" session=""
    valid_port "$port" || { log "Binder 授权：端口无效"; return 2; }
    IFS= read -r code || [ -n "$code" ] || { log "Binder 授权：没有凭证"; return 2; }
    [ "${#code}" -ge 6 ] && [ "${#code}" -le 4096 ] || { log "Binder 授权：凭证长度无效"; return 2; }
    case "$code" in
        *[!a-zA-Z0-9_+./=:%-]*) code=""; log "Binder 授权：凭证字符无效"; return 2 ;;
    esac
    log "[端口 ${port}] Binder 授权：兑换会话"
    if ! retry_command 2 4 _get_session_id_checked "$code" "$port"; then
        code=""
        log "[端口 ${port}] Binder 授权：会话兑换失败"
        return 1
    fi
    session="$RETRY_OUTPUT"
    code=""
    if save_session "$port" "$session" binder; then
        session=""
        log "[端口 ${port}] Binder 授权成功：会话已保存"
        return 0
    fi
    session=""
    log "[端口 ${port}] Binder 授权：保存失败"
    return 1
}

main() {
    umask 077
    mkdir -p "$LOG_DIR"
    case "$1" in
        authorize-stdin) authorize_from_stdin "$2"; return $? ;;
        status) session_status; return 0 ;;
        nonauto|"") ;;
        *) log "未知命令"; return 2 ;;
    esac
    auto=false
    [ "$1" != "nonauto" ] && auto=true

    mode=$([ "$auto" = true ] && echo "自动" || echo "手动")
    log "== 比亚迪${mode}签到开始"

    clean_old_logs

    # 读取 CODE_API 端口配置
    local ports=$(get_code_api_ports)
    if [ -z "$ports" ]; then
        log "错误: 没有配置任何 CODE_API 端口"
        send_notification "比亚迪签到失败" "没有配置 CODE_API 端口，签到失败。时间：$(date '+%Y-%m-%d %H:%M:%S')"
        return 1
    fi
    log "配置的 CODE_API 端口: $ports"

    # 统计结果
    local success_count=0
    local fail_count=0
    local skip_count=0
    local result_details=""
    local best_days="0"

    for port in $ports; do
        log ""
        log "== [端口 ${port}] 开始签到 =="

        # 自动模式下，检查该端口是否已签到
        if [ "$1" != "nonauto" ] && _is_signed_today "$port"; then
            log "[端口 ${port}] 今天已经签到，跳过"
            skip_count=$((skip_count + 1))
            result_details="${result_details}  - 端口 ${port}: 今日已签到（跳过）\n"
            log "== [端口 ${port}] 已跳过 =="
            continue
        fi

        # Sign-only: never touch WeChat and never exchange a new code.
        local session_id
        session_id=$(load_session "$port") || session_id=""
        if [ -z "$session_id" ]; then
            log "[端口 ${port}] 尚未初始化会话，请在应用内重新授权"
            fail_count=$((fail_count + 1))
            result_details="${result_details}  - 端口 ${port}: 需要重新授权\n"
            log "== [端口 ${port}] 缺少会话，跳过 =="
            continue
        fi

        # 执行签到（网络失败可重试，解密/业务失败快速失败）
        local sign_output=""
        if retry_command 2 10 _do_sign_checked "$session_id" "$port"; then
            sign_output="$RETRY_OUTPUT"

            # 解析签到结果
            if echo "$sign_output" | grep -q "^SUCCESS"; then
                local days=$(echo "$sign_output" | cut -d'|' -f2)
                success_count=$((success_count + 1))
                if [ "$days" -gt "$best_days" ] 2>/dev/null; then
                    best_days="$days"
                fi
                result_details="${result_details}  - 端口 ${port}: 签到成功 (连续${days}天)\n"
            else
                local reason=$(echo "$sign_output" | cut -d'|' -f2)
                fail_count=$((fail_count + 1))
                result_details="${result_details}  - 端口 ${port}: ${reason}\n"
                log "== [端口 ${port}] 签到失败 =="
                continue
            fi
        else
            if [ "$RETRY_OUTPUT" = "FAILED|AUTH_EXPIRED" ]; then
                forget_session "$port"
                result_details="${result_details}  - 端口 ${port}: 会话已过期，请重新授权\n"
            else
                result_details="${result_details}  - 端口 ${port}: 签到失败（会话保留）\n"
            fi
            fail_count=$((fail_count + 1))
            log "== [端口 ${port}] 签到失败 =="
            continue
        fi

        log "== [端口 ${port}] 签到成功 =="
        save_sign_success "$port"
    done

    # 汇总通知
    log ""
    local total_ports=$((success_count + fail_count + skip_count))
    log "== 汇总: 成功 ${success_count} / 失败 ${fail_count} / 跳过 ${skip_count} / 共 ${total_ports} 个端口 =="

    local now_time=$(date '+%Y-%m-%d %H:%M:%S')

    if [ "$success_count" -gt 0 ]; then
        local notify_msg="签到汇总：${success_count}/${total_ports} 个端口签到成功"
        if [ "$skip_count" -gt 0 ]; then
            notify_msg="${notify_msg}，${skip_count} 个已跳过"
        fi
        if [ "$fail_count" -gt 0 ]; then
            notify_msg="${notify_msg}，${fail_count} 个失败"
        fi
        notify_msg="${notify_msg}。\n\n详情：\n${result_details}\n时间：${now_time}"
        if [ "$best_days" != "0" ]; then
            send_notification "比亚迪签到成功" "签到成功！已连续签到 ${best_days} 天。\n${notify_msg}"
        else
            send_notification "比亚迪签到成功" "签到成功！\n${notify_msg}"
        fi
        log "签到完成"
    elif [ "$skip_count" -gt 0 ] && [ "$fail_count" -eq 0 ]; then
        # 全部已签到，无需操作
        local notify_msg="签到汇总：所有 ${total_ports} 个端口今日均已签到，跳过。\n\n详情：\n${result_details}\n时间：${now_time}"
        log "${notify_msg}"
        log "签到完成（全部已跳过）"
        # 不发送通知，避免骚扰
    else
        local notify_msg="签到汇总：所有 ${total_ports} 个端口"
        if [ "$skip_count" -gt 0 ] && [ "$fail_count" -gt 0 ]; then
            notify_msg="${notify_msg}（${skip_count} 已跳过，${fail_count} 失败）"
        elif [ "$fail_count" -gt 0 ]; then
            notify_msg="${notify_msg} 均签到失败"
        fi
        notify_msg="${notify_msg}。\n\n详情：\n${result_details}\n时间：${now_time}"
        send_notification "比亚迪签到失败" "${notify_msg}"
        log "签到完成（无新成功）"
    fi

    log "== 比亚迪${mode}签到结束"

    # A manual invocation must not report success when every account failed.
    # Also surface partial failures to the UI while keeping per-port success state.
    [ "$fail_count" -eq 0 ] || return 1
    return 0
}

main "$@"