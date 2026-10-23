#!/system/bin/sh
# 公共函数库 - 比亚迪签到模块
# 包含两个脚本（byd_sign.sh / webui_api.sh）重复使用的函数和常量

# === 公共常量 ===
WORK_DIR="/data/adb/byd_sign"
LOG_DIR="$WORK_DIR/logs"
SIGN_STATUS_FILE="$WORK_DIR/sign_status.txt"
SIGN_STATUS_DIR="$WORK_DIR/sign_status"
EMAIL_CONF="$WORK_DIR/email.conf"
CODE_API_CONF="$WORK_DIR/code_api.conf"
# === 工具路径初始化 ===
# curl 必须固定使用模块内置版本，不能继承调用环境中的 CURL_CMD/PATH。
# 否则 cron、WebUI 和手动终端会使用不同实现，网络响应差异会被误判为 AES 解密失败。
if [ -x "$WORK_DIR/curl" ]; then
    CURL_CMD="$WORK_DIR/curl"
else
    CURL_CMD=""
fi


if [ -z "$OPENSSL_CMD" ]; then
    if [ -f "$WORK_DIR/openssl" ]; then
        OPENSSL_CMD="$WORK_DIR/openssl"
    else
        OPENSSL_CMD=""
    fi
fi

# === 查找 busybox 路径 ===
find_busybox() {
    for b in \
        /data/adb/ksu/bin/busybox \
        /data/adb/ap/bin/busybox \
        /data/adb/ap/busybox \
        /data/adb/magisk/busybox; do
        if [ -f "$b" ]; then
            BUSYBOX="$b"
            return 0
        fi
    done
    BUSYBOX="busybox"
}

# === 获取可用 curl 路径 ===
# 统一使用模块内置 curl。模块脚本会在 service.sh / action.sh 中按 ABI
# 复制到 WORK_DIR；不要回退 PATH 中的 curl，避免 cron、WebUI 与终端环境的
# TLS、DNS、压缩行为不一致。
get_curl_bin() {
    if [ -x "$WORK_DIR/curl" ]; then
        echo "$WORK_DIR/curl"
        return 0
    fi
    return 1
}

# === 读取是否强制前台唤醒微信 ===
get_force_foreground_wake_enabled() {
    if [ ! -f "$CODE_API_CONF" ]; then
        echo "false"
        return 0
    fi

    local enabled=$(awk -F= '$1 == "force_foreground_wake" { value=$0; sub(/^[^=]*=/, "", value); gsub(/[[:space:]]*\r$/, "", value); print value; exit }' "$CODE_API_CONF" 2>/dev/null)

    if [ "$enabled" = "true" ]; then
        echo "true"
    else
        echo "false"
    fi
}

# === 微信唤醒（解除休眠限制 + CoreService，可配置强制前台） ===
# 说明:
#   1. 不检测本地 /login 是否可用
#   2. 先尽量解除 Doze / App Standby / 后台运行限制
#   3. 始终执行: am startservice -n com.tencent.mm/.booter.CoreService
#   4. 默认不拉前台；开启 force_foreground_wake 后短暂打开微信并立即回桌面
#   5. 不遍历其他 Service
#   6. 如果当前微信版本/系统限制不允许启动该 Service，则可能无法真正唤醒
#
# 返回:
#   0 = 微信进程已存在或已通过 CoreService 拉起
#   1 = 未能确认微信进程启动
wake_wechat_silent() {
    local pkg="com.tencent.mm"
    local wait_secs=5
    local force_foreground_wake=$(get_force_foreground_wake_enabled)

    if pidof "$pkg" >/dev/null 2>&1; then
        log "微信进程存在，尝试解除休眠限制并 CoreService 后台唤醒..."
    else
        log "微信进程未运行，尝试解除休眠限制并 CoreService 后台唤醒..."
    fi

    # 尽量解除 Doze / App Standby / 后台运行限制，不拉前台。
    # 不同 Android 版本/ROM 支持情况不同，失败直接忽略。
    cmd deviceidle tempwhitelist -d 300000 "$pkg" >/dev/null 2>&1
    am set-inactive "$pkg" false >/dev/null 2>&1
    cmd appops set "$pkg" RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$pkg" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$pkg" WAKE_LOCK allow >/dev/null 2>&1

    # 始终调用指定 CoreService。
    am startservice -n "$pkg/.booter.CoreService" >/dev/null 2>&1

    # 可选：强制前台唤醒微信，再立即回桌面。
    # 适合微信进程存在但后台休眠，导致 get_code 服务无响应的场景。
    if [ "$force_foreground_wake" = "true" ]; then
        log "已开启强制前台唤醒微信，短暂打开微信后返回桌面..."
        am start \
            --activity-no-animation \
            -f 0x10008000 \
            -n "$pkg/.ui.LauncherUI" >/dev/null 2>&1

        sleep 1

        am start \
            --activity-no-animation \
            -a android.intent.action.MAIN \
            -c android.intent.category.HOME >/dev/null 2>&1
    else
        log "未开启强制前台唤醒微信，仅执行后台唤醒"
    fi

    if pidof "$pkg" >/dev/null 2>&1; then
        log "微信后台唤醒处理完成，已固定等待 ${wait_secs} 秒 (pid=$(pidof "$pkg" | tr ' ' ','))"
        return 0
    fi

    log "警告: 尚未确认微信进程已启动，Binder 授权可能需要重新唤醒"
    return 1
}


# === 读取 CODE_API 端口列表（返回空格分隔的端口号）===
get_code_api_ports() {
    local ports="$1"  # 可选：允许调用方传入 ports 字符串跳过文件解析

    if [ -z "$ports" ] && [ -f "$CODE_API_CONF" ]; then
        ports=$(awk -F= '$1 == "ports" { value=$0; sub(/^[^=]*=/, "", value); gsub(/[[:space:]]*\r$/, "", value); print value; exit }' "$CODE_API_CONF" 2>/dev/null)
        ports=$(echo "$ports" | tr ',' ' ')
    fi

    # 如果未解析到端口，使用默认端口
    if [ -z "$ports" ]; then
        ports="8088"
    fi

    # 去重并去空格
    local unique=""
    for p in $ports; do
        p=$(echo "$p" | tr -d ' ')
        if [ -n "$p" ] && ! echo " $unique " | grep -q " $p "; then
            unique="$unique $p"
        fi
    done
    echo "$unique" | sed 's/^ //;s/ $//'
}

# === 检测某端口今日是否已签到（参数: 端口号，返回 0=已签到, 1=未签到）===
_is_signed_today() {
    local port="$1"
    local status_file="$SIGN_STATUS_DIR/${port}.txt"

    # 向后兼容：如果旧签名文件存在且无端口参数，检查旧文件
    if [ -z "$port" ] && [ -f "$SIGN_STATUS_FILE" ]; then
        local saved_date=$(cat "$SIGN_STATUS_FILE" 2>/dev/null)
        local today=$(date '+%Y-%m-%d')
        [ "$saved_date" = "$today" ] && return 0
        return 1
    fi

    if [ -z "$port" ] || [ ! -f "$status_file" ]; then
        return 1
    fi

    local saved_date=$(cat "$status_file" 2>/dev/null)
    local today=$(date '+%Y-%m-%d')

    if [ "$saved_date" = "$today" ]; then
        return 0
    fi

    return 1
}

# === crond lifecycle and observable health ===
# BusyBox crond's argv format differs by Root manager and Android version.
# /proc/pid/cwd is a fallback because crond changes directory to its spool.
is_own_crond() {
    local command_line="$1" cwd="$2" comm="$3"
    # Never confuse a shell executing a crond command with the daemon itself.
    case "$comm" in crond|busybox) ;; *) return 1 ;; esac
    case "$command_line" in
        *crond*" -c $WORK_DIR/cron.d "*|*crond*" -c$WORK_DIR/cron.d "*)
            return 0
            ;;
    esac
    if [ "$cwd" = "$WORK_DIR/cron.d" ]; then
        case "$command_line" in
            *crond*) return 0 ;;
        esac
    fi
    return 1
}

own_crond_pids() {
    local file pid command_line cwd state comm
    for file in /proc/[0-9]*/cmdline; do
        [ -r "$file" ] || continue
        pid=${file#/proc/}
        pid=${pid%/cmdline}
        state=$(sed -n 's/^State:[[:space:]]*\([A-Z]\).*/\1/p' "/proc/$pid/status" 2>/dev/null)
        [ "$state" = Z ] && continue
        command_line=$(tr '\000' ' ' < "$file" 2>/dev/null) || continue
        cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null) || cwd=""
        comm=$(cat "/proc/$pid/comm" 2>/dev/null) || comm=""
        if is_own_crond "$command_line" "$cwd" "$comm"; then
            printf '%s\n' "$pid"
        fi
    done
}

cron_running() {
    [ -n "$(own_crond_pids)" ]
}

stop_crond() {
    local pid
    for pid in $(own_crond_pids); do
        kill "$pid" 2>/dev/null || true
    done
}

find_crond_busybox() {
    local candidate
    for candidate in \
        /data/adb/ksu/bin/busybox \
        /data/adb/ap/bin/busybox \
        /data/adb/ap/busybox \
        /data/adb/magisk/busybox \
        /data/adb/modules/busybox-ndk/system/xbin/busybox \
        /system/xbin/busybox \
        /system/bin/busybox; do
        if [ -x "$candidate" ] &&
            "$candidate" --list 2>/dev/null | grep -qx crond; then
            BUSYBOX="$candidate"
            return 0
        fi
    done
    if command -v busybox >/dev/null 2>&1 &&
        busybox --list 2>/dev/null | grep -qx crond; then
        BUSYBOX="$(command -v busybox)"
        return 0
    fi
    return 1
}

restart_crond() {
    local status=0
    mkdir -p "$LOG_DIR" "$WORK_DIR/cron.d" || return 1
    [ -s "$WORK_DIR/cron.d/root" ] || {
        echo "CRON_ERROR: root crontab is missing" >&2
        return 1
    }
    if ! find_crond_busybox; then
        echo "CRON_ERROR: no executable BusyBox with crond applet found" >&2
        return 127
    fi
    # Find a replacement before terminating any existing owned scheduler.
    stop_crond
    sleep 1
    "$BUSYBOX" crond -b -c "$WORK_DIR/cron.d" -L "$LOG_DIR/cron.log" </dev/null >/dev/null 2>>"$LOG_DIR/scheduler.log" || status=$?
    if [ "$status" -ne 0 ]; then
        echo "CRON_ERROR: crond launch failed (exit $status)" >&2
        return "$status"
    fi
    sleep 1
    if ! cron_running; then
        echo "CRON_ERROR: crond not detectable after launch (check ps and cron.log)" >&2
        return 1
    fi
    return 0
}

# === 日志函数 ===
log() {
    local log_file="$LOG_DIR/sign_$(date '+%Y-%m-%d').log"
    local log_msg="[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    echo "$log_msg" >&2
    echo "$log_msg" >> "$log_file"
}

# === JSON 转义 ===
json_escape() {
    local str="$1"
    str="${str//\\/\\\\}"
    str="${str//\"/\\\"}"
    str="${str//$'\n'/\\n}"
    str="${str//$'\t'/\\t}"
    str="${str//$'\r'/\\r}"
    printf '%s' "$str"
}

# === 通用 HTTPS JSON POST（兼容 Android 静态 curl DNS/代理问题） ===
http_post_json() {
    local url="$1"
    local json_data="$2"
    local ok_msg="$3"
    local fail_msg="$4"
    local result=""
    local exit_code=0
    local curl_try=""

    # 清理可能干扰的代理环境变量
    unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY no_proxy NO_PROXY 2>/dev/null

    # 优先使用模块 curl，失败（尤其 DNS/exit 6）时回退系统 curl
    for curl_try in "$CURL_CMD" "curl"; do
        [ -z "$curl_try" ] && continue
        if [ "$curl_try" != "curl" ] && [ ! -x "$curl_try" ] && [ ! -f "$curl_try" ]; then
            continue
        fi
        result=$($curl_try -sS --insecure -4 --noproxy "*" --connect-timeout 10 --max-time 15 \
            -X POST "$url" \
            -H "Content-Type: application/json; charset=utf-8" \
            -d "$json_data" 2>&1)
        exit_code=$?
        if [ $exit_code -eq 0 ]; then
            log "$ok_msg"
            [ -n "$result" ] && log "Webhook 响应: $result"
            return 0
        fi
        # exit 6: 无法解析主机名；exit 7: 连接失败；继续尝试下一个 curl
        log "curl($curl_try) 发送失败 (exit=$exit_code): $result"
        if [ "$curl_try" = "curl" ]; then
            break
        fi
    done

    log "$fail_msg (最后 exit=$exit_code)"
    return 1
}

# === 发送邮件 ===
send_email() {
    local title="$1"
    local message="$2"

    # 检查邮件是否启用
    if [ "$EMAIL_ENABLED" != "true" ]; then
        log "邮件通知已禁用，跳过发送"
        return
    fi

    # 检查邮件配置
    if [ -z "$EMAIL_FROM" ] || [ -z "$EMAIL_TO" ] || [ -z "$EMAIL_AUTH" ]; then
        log "邮件通知未完整配置，跳过发送"
        return
    fi

    # 需要一个可用的 curl
    local curl_bin="$CURL_CMD"
    [ -z "$curl_bin" ] && curl_bin="curl"

    # 构建邮件内容
    local mail_content="From: $EMAIL_FROM
To: $EMAIL_TO
Subject: $title
Content-Type: text/plain; charset=UTF-8

$message"

    # 发送邮件
    $curl_bin -s --url "smtp://${SMTP_SERVER}:${SMTP_PORT}" \
        --ssl \
        --insecure \
        --login-options AUTH=LOGIN \
        --mail-from "$EMAIL_FROM" \
        --mail-rcpt "$EMAIL_TO" \
        --user "${EMAIL_FROM}:${EMAIL_AUTH}" \
        -T - <<EOF
$mail_content
EOF
    if [ $? -eq 0 ]; then
        log "邮件通知已发送"
    else
        log "邮件发送失败"
    fi
}

# === 发送 Webhook 通知 ===
send_webhook() {
    local title="$1"
    local message="$2"

    # 检查 Webhook 类型是否已配置
    if [ -z "$WEBHOOK_TYPE" ]; then
        log "Webhook 类型未配置，跳过发送"
        return
    fi

    # 检查 Webhook 配置
    if [ -z "$WEBHOOK_URL" ]; then
        log "Webhook 未配置，跳过发送"
        return
    fi

    log "正在发送 Webhook 通知..."

    # 转义 JSON 特殊字符
    local escaped_title=$(json_escape "$title")
    local escaped_message=$(json_escape "$message")

    # 根据 Webhook 类型发送不同格式的消息
    case "$WEBHOOK_TYPE" in
        "wechat")
            # 企业微信机器人
            local json_data="{\"msgtype\":\"text\",\"text\":{\"content\":\"${escaped_title}\\n${escaped_message}\"}}"
            http_post_json "$WEBHOOK_URL" "$json_data" "企业微信通知已发送" "企业微信通知发送失败"
            ;;
        "dingtalk")
            # 钉钉机器人
            local webhook_url="$WEBHOOK_URL"
            if [ -n "$DINGTALK_SECRET" ] && [ -n "$OPENSSL_CMD" ]; then
                # 获取毫秒时间戳
                local timestamp=$(date +%s)000
                # 构造签名字符串：timestamp + "\n" + secret
                local string_to_sign="${timestamp}"$'\n'"${DINGTALK_SECRET}"
                # 计算 HMAC-SHA256 并 Base64 编码
                local sign=$(printf '%s' "$string_to_sign" | $OPENSSL_CMD dgst -sha256 -hmac "$DINGTALK_SECRET" -binary | $OPENSSL_CMD base64 | tr -d '\n' | sed 's/+/%2B/g; s/\//%2F/g; s/=/%3D/g')
                webhook_url="${webhook_url}&timestamp=${timestamp}&sign=${sign}"
            fi
            local json_data="{\"msgtype\":\"text\",\"text\":{\"content\":\"${escaped_title}\\n${escaped_message}\"}}"
            http_post_json "$webhook_url" "$json_data" "钉钉通知已发送" "钉钉通知发送失败"
            ;;
        "telegram")
            # Telegram Bot
            if [ -z "$TELEGRAM_CHAT_ID" ]; then
                log "Telegram 未配置 TELEGRAM_CHAT_ID，跳过发送"
                return
            fi
            local json_data="{\"chat_id\":\"${TELEGRAM_CHAT_ID}\",\"text\":\"${escaped_title}\\n${escaped_message}\",\"parse_mode\":\"HTML\"}"
            http_post_json "$WEBHOOK_URL" "$json_data" "Telegram 通知已发送" "Telegram 通知发送失败"
            ;;
        "pushplus")
            # PushPlus 通用推送渠道
            # channel 为空时走 PushPlus 默认渠道；部分渠道需要 option，例如企业微信应用(cp)
            if [ -z "$PUSHPLUS_TOKEN" ]; then
                log "PushPlus 未配置 PUSHPLUS_TOKEN，跳过发送"
                return
            fi
            if [ "$PUSHPLUS_CHANNEL" = "cp" ] && [ -z "$PUSHPLUS_OPTION" ]; then
                log "PushPlus 企业微信应用渠道未配置 PUSHPLUS_OPTION，跳过发送"
                return
            fi
            local pushplus_url="$WEBHOOK_URL"
            [ -z "$pushplus_url" ] && pushplus_url="https://www.pushplus.plus/send"
            local json_data="{\"token\":\"${PUSHPLUS_TOKEN}\",\"title\":\"${escaped_title}\",\"content\":\"${escaped_message}\""
            [ -n "$PUSHPLUS_CHANNEL" ] && json_data="${json_data},\"channel\":\"${PUSHPLUS_CHANNEL}\""
            [ -n "$PUSHPLUS_OPTION" ] && json_data="${json_data},\"option\":\"${PUSHPLUS_OPTION}\""
            json_data="${json_data}}"
            http_post_json "$pushplus_url" "$json_data" "PushPlus 通知已发送" "PushPlus 通知发送失败"
            ;;
        *)
            # 默认使用通用格式
            local json_data="{\"title\":\"${escaped_title}\",\"message\":\"${escaped_message}\",\"timestamp\":\"$(date '+%Y-%m-%d %H:%M:%S')\"}"
            http_post_json "$WEBHOOK_URL" "$json_data" "Webhook 通知已发送" "Webhook 通知发送失败"
            ;;
    esac
}

# === 发送通知（邮件 + Webhook） ===
send_notification() {
    local title="$1"
    local message="$2"

    # 将 \n 转义序列解析为真实换行符
    message=$(printf '%b' "$message")

    # 发送邮件通知
    send_email "$title" "$message"

    # 发送 Webhook 通知
    send_webhook "$title" "$message"
}